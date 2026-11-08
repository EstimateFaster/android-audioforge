package com.estimatefaster.audioforge.core.audio

import android.media.MediaMetadataRetriever
import com.estimatefaster.audioforge.core.model.SegmentType
import com.estimatefaster.audioforge.core.storage.AudioStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 拼接计划中的一项：先播 [file]，若 [silenceAfterMs] > 0 再插入对应长度静音。
 *
 * [buildPlan] 只描述“停顿意图”，不接触静音文件；[ChapterStitcher.stitch]
 * 再把停顿解析为 [SilenceGenerator] 的缓存文件。
 */
data class StitchItem(
    val file: File,
    val type: SegmentType,
    val silenceAfterMs: Int,
)

/** 章节拼接结果；[relativePath] 是 `audio/<novelId>/chapter_<nnn>.m4a` 形式的相对路径。 */
data class StitchResult(
    val relativePath: String,
    val durationMs: Long,
    /** 直接 AAC 失败、走了 WAV → AAC 回退时为 true（诊断/日志用）。 */
    val usedWavFallback: Boolean = false,
)

/**
 * 章节拼接（执行计划 §10.3、P2-10）。
 *
 * 顺序与停顿：
 * - 依次放入 `files[i]`；
 * - 若 `types[i] == Dialogue && types[i+1] == Narration` → [DEFAULT_PARAGRAPH_SILENCE_MS]（700ms）；
 * - 其它相邻片段 → [DEFAULT_NARRATOR_SILENCE_MS]（350ms）；
 * - 最后一段之后不加静音。
 *
 * 实现：
 * 1. 由 [SilenceGenerator] 生成/复用静音 mp3；
 * 2. 写 `concat.txt`（绝对路径、只允许可打印 ASCII、单引号按 `'\''` 转义）；
 * 3. 优先直接 AAC；失败回退 WAV → AAC；
 * 4. 输出 `AudioStorage.chapterAudioRelative(...)`，用 [durationReader] 读时长；
 * 5. 成功/失败都清理 `concat.txt`、中间 WAV 与半成品输出（静音缓存保留）。
 *
 * 注意：`dialogue → narration` 使用 700ms、其余 350ms 是计划 §10.3 的明确规则；
 * 参数名沿用设置里的 `narrator_silence_ms` / `paragraph_silence_ms`。
 */
class ChapterStitcher(
    private val runner: FfmpegKitRunner,
    private val cacheDir: File,
    private val durationReader: (File) -> Long = ::readDurationMs,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val silenceGenerator = SilenceGenerator(runner, cacheDir)

    /**
     * 把一个章节的片段拼成 `<root>/audio/<novelId>/chapter_<nnn>.m4a`。
     *
     * @param segmentFiles 已生成的片段文件（按剧本顺序）
     * @param types 与 [segmentFiles] 等长的片段类型
     * @param storage P2-05 存储层，负责输出相对路径与目录
     * @param maxChars 可选的 concat 清单字符上限（防御性检查，null 表示不限制）；
     *   统计的是清单文本长度，不是章节正文字符数
     *
     * @throws IllegalArgumentException 空列表、长度不一致、片段文件不存在/为空、
     *   路径非 ASCII、[maxChars] 不是正数或清单超限
     * @throws FfmpegException 直接 AAC 与 WAV 回退都失败
     * @throws IOException 输出目录不可写、时长读取失败或 rename 失败
     */
    suspend fun stitch(
        novelId: Long,
        chapterIdx: Int,
        segmentFiles: List<File>,
        types: List<SegmentType>,
        storage: AudioStorage,
        maxChars: Int? = null,
    ): StitchResult = withContext(io) {
        val plan = buildPlan(segmentFiles, types)
        plan.forEach { item ->
            require(item.file.isFile) { "片段文件不存在：${item.file}" }
            require(item.file.length() > 0L) { "片段文件为空：${item.file}" }
        }

        // 同一种停顿只生成/复用一次静音缓存。
        val silenceByMs = mutableMapOf<Int, File>()
        plan.forEach { item ->
            val ms = item.silenceAfterMs
            if (ms > 0 && ms !in silenceByMs) {
                silenceByMs[ms] = silenceGenerator.silenceFile(ms)
            }
        }

        val inputs = buildList {
            plan.forEach { item ->
                add(item.file)
                silenceByMs[item.silenceAfterMs]?.let { add(it) }
            }
        }
        val manifest = buildConcatManifest(inputs, maxChars)

        storage.ensureNovelDirectories(novelId)
        val outputRelative = storage.chapterAudioRelative(novelId, chapterIdx)
        val output = storage.resolve(outputRelative)
        val outputDir = output.parentFile
            ?: throw IOException("章节音频没有父目录：$output")

        val workDir = File(cacheDir, WORK_DIR)
        if (!workDir.isDirectory && !workDir.mkdirs() && !workDir.isDirectory) {
            throw IOException("无法创建拼接临时目录：$workDir")
        }
        val concat = File.createTempFile("concat_${novelId}_${chapterIdx}_", ".txt", workDir)
        var tempOutput: File? = null
        try {
            concat.writeText(manifest, Charsets.US_ASCII)

            val tmpOutput = File.createTempFile(
                "chapter_${novelId}_${chapterIdx}_",
                ".m4a",
                outputDir,
            )
            tempOutput = tmpOutput

            var usedWavFallback = false
            try {
                runner.run(directAacCommand(concat, tmpOutput))
            } catch (e: FfmpegException) {
                usedWavFallback = true
                transcodeViaWav(concat, tmpOutput, workDir, novelId, chapterIdx)
            }

            if (!tmpOutput.isFile || tmpOutput.length() <= 0L) {
                throw IOException("章节音频为空：$output")
            }
            val durationMs = durationReader(tmpOutput)
            if (durationMs <= 0L) {
                throw IOException("无法读取章节音频时长：$output")
            }
            if (!tmpOutput.renameTo(output)) {
                throw IOException("无法写入章节音频：$output")
            }

            StitchResult(
                relativePath = outputRelative,
                durationMs = durationMs,
                usedWavFallback = usedWavFallback,
            )
        } finally {
            concat.delete()
            tempOutput?.delete()
        }
    }

    /** 回退路径：concat → WAV → AAC；无论成功失败都删除中间 WAV。 */
    private suspend fun transcodeViaWav(
        concat: File,
        target: File,
        workDir: File,
        novelId: Long,
        chapterIdx: Int,
    ) {
        val wav = File.createTempFile("chapter_${novelId}_${chapterIdx}_", ".wav", workDir)
        try {
            runner.run(wavCommand(concat, wav))
            if (!wav.isFile || wav.length() <= 0L) {
                throw IOException("WAV 拼接结果为空：$wav")
            }
            runner.run(aacFromWavCommand(wav, target))
        } finally {
            wav.delete()
        }
    }

    private fun directAacCommand(concat: File, output: File): String =
        "-y -f concat -safe 0 -i \"${concat.absolutePath}\" " +
            "-ar $SAMPLE_RATE -ac $CHANNELS -c:a aac -b:a $AAC_BITRATE " +
            "-movflags +faststart \"${output.absolutePath}\""

    private fun wavCommand(concat: File, output: File): String =
        "-y -f concat -safe 0 -i \"${concat.absolutePath}\" " +
            "-ar $SAMPLE_RATE -ac $CHANNELS -c:a pcm_s16le \"${output.absolutePath}\""

    private fun aacFromWavCommand(wav: File, output: File): String =
        "-y -i \"${wav.absolutePath}\" " +
            "-ar $SAMPLE_RATE -ac $CHANNELS -c:a aac -b:a $AAC_BITRATE " +
            "-movflags +faststart \"${output.absolutePath}\""

    companion object {
        /** 默认“其它相邻片段”停顿（设置 `narrator_silence_ms`）。 */
        const val DEFAULT_NARRATOR_SILENCE_MS: Int = 350

        /** 默认“对白 → 旁白”停顿（设置 `paragraph_silence_ms`）。 */
        const val DEFAULT_PARAGRAPH_SILENCE_MS: Int = 700

        private const val SAMPLE_RATE = 44_100
        private const val CHANNELS = 1
        private const val AAC_BITRATE = "96k"
        private const val WORK_DIR = "stitch"

        /**
         * 纯函数：按片段顺序和类型生成拼接计划。
         *
         * 规则（计划 §10.3）：
         * - `Dialogue → Narration` 插入 [paragraphSilenceMs]（默认 700ms）；
         * - 其余相邻片段插入 [narratorSilenceMs]（默认 350ms）；
         * - 最后一段之后不插静音（[StitchItem.silenceAfterMs] = 0）。
         *
         * @throws IllegalArgumentException 列表为空、长度不一致或停顿为负数
         */
        fun buildPlan(
            files: List<File>,
            types: List<SegmentType>,
            narratorSilenceMs: Int = DEFAULT_NARRATOR_SILENCE_MS,
            paragraphSilenceMs: Int = DEFAULT_PARAGRAPH_SILENCE_MS,
        ): List<StitchItem> {
            require(files.isNotEmpty()) { "片段列表不能为空" }
            require(files.size == types.size) {
                "files/types 长度不一致：${files.size} != ${types.size}"
            }
            require(narratorSilenceMs >= 0) { "narratorSilenceMs 不能为负：$narratorSilenceMs" }
            require(paragraphSilenceMs >= 0) { "paragraphSilenceMs 不能为负：$paragraphSilenceMs" }

            return files.mapIndexed { index, file ->
                val silenceAfterMs = when {
                    index == files.lastIndex -> 0
                    types[index] == SegmentType.Dialogue &&
                        types[index + 1] == SegmentType.Narration -> paragraphSilenceMs
                    else -> narratorSilenceMs
                }
                StitchItem(file = file, type = types[index], silenceAfterMs = silenceAfterMs)
            }
        }

        /**
         * 纯函数：生成 ffmpeg concat demuxer 清单。
         *
         * - 每行 `file '<absolute path>'`，以 `\n` 结尾；
         * - 路径必须是可打印 ASCII（应用内路径本来就是 ASCII）；
         * - 单引号转义为 `'\''`；
         * - [maxChars] 非 null 时校验清单总字符数（防御性上限）。
         *
         * @throws IllegalArgumentException 空列表、路径非 ASCII、清单超限或 maxChars 非正
         */
        fun buildConcatManifest(files: List<File>, maxChars: Int? = null): String {
            require(files.isNotEmpty()) { "concat 清单不能为空" }
            if (maxChars != null) {
                require(maxChars > 0) { "maxChars 必须为正数：$maxChars" }
            }

            val builder = StringBuilder()
            files.forEach { file ->
                val path = file.absolutePath
                require(path.all { it.code in ASCII_MIN..ASCII_MAX }) {
                    "concat 路径必须只含可打印 ASCII：$path"
                }
                builder.append("file '")
                    .append(path.replace("'", "'\\''"))
                    .append("'\n")
            }

            val manifest = builder.toString()
            if (maxChars != null) {
                require(manifest.length <= maxChars) {
                    "concat 清单过长：${manifest.length} > $maxChars"
                }
            }
            return manifest
        }

        private const val ASCII_MIN = 0x20
        private const val ASCII_MAX = 0x7E
    }
}

/** 默认时长读取：MediaMetadataRetriever；测试通过构造参数注入 fake。 */
private fun readDurationMs(file: File): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?: 0L
    } finally {
        retriever.release()
    }
}
