package com.estimatefaster.audioforge.domain.pipeline

import android.media.MediaMetadataRetriever
import com.estimatefaster.audioforge.core.audio.ChapterStitcher
import com.estimatefaster.audioforge.core.model.AudioStatus
import com.estimatefaster.audioforge.core.model.Emotion
import com.estimatefaster.audioforge.core.model.PipelineError
import com.estimatefaster.audioforge.core.model.SegmentStatus
import com.estimatefaster.audioforge.core.model.SegmentType
import com.estimatefaster.audioforge.core.storage.AudioStorage
import com.estimatefaster.audioforge.core.tts.TtsCacheInput
import com.estimatefaster.audioforge.core.tts.TtsCacheKey
import com.estimatefaster.audioforge.core.tts.TtsResult
import com.estimatefaster.audioforge.core.tts.TtsSpec
import com.estimatefaster.audioforge.core.tts.TtsSynthesizer
import com.estimatefaster.audioforge.core.voice.VoicePool
import com.estimatefaster.audioforge.data.db.AppDatabase
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.data.db.SegmentEntity
import com.estimatefaster.audioforge.data.repository.ChapterRepository
import com.estimatefaster.audioforge.data.repository.CharacterRepository
import com.estimatefaster.audioforge.data.repository.SegmentRepository
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 渲染单章为成品 m4a（执行计划 §8.9、§10.3、§15 P2-12）。
 *
 * 流程：
 * 1. 读章节与片段；章节不存在 → [RenderStatus.Error]，空片段 → [RenderStatus.NoSegments]
 *    （都不调用 TTS/ffmpeg，也不改章节状态）；
 * 2. 逐段求实际音色参数（角色锁定 voiceId/speed → 全局默认）与 v2 缓存键；
 *    `status=done && ttsKey==当前键 && 文件存在` → **缓存命中**，不调用合成器；
 * 3. 其余片段进固定 worker 池（`min(ttsConcurrency, pending.size)` 个 worker 抢队列，
 *    无批屏障）：
 *    - 段前 `setStatus(running)`；
 *    - `ttsSynthesizer.synthesize(TtsSpec)`；
 *    - `Synthesized`/`Cached` → 写 `audioRelPath/ttsKey/durationMs` + `done`；
 *    - `Failed` → 段 `error` + 错误文案，**不中断**其它 worker；
 * 4. 有失败 → 章节 `audio_status=error`，保留已完成片段，不拼接；
 * 5. 全部就绪 → 按 `seq` 顺序拼 `files/types`，成功写章节 `audio_status=done`。
 *
 * 停止语义（§8.9）：
 * - 每个 worker 在「取任务前 / 真正开始前 / 完成一段后」检查 [render] 的 `shouldStop`；
 * - 停止时不取消已开始的片段（让它们正常收尾并保留结果），也未开始的片段不改 DB，
 *   因此保持原 `pending/error` 状态；
 * - 停止后章节回到 `audio_status=pending`（不做 error/done 判定），方便下次接着渲染。
 *
 * 错误语义：
 * - 单段失败只影响该段，其它段继续；失败计数进 [RenderResult.failedCount]；
 * - 已成功片段在重跑时是缓存命中，所以「重跑只补失败段」是天然结果；
 * - 拼接失败 → 章节 `error`，片段保持 `done`（音频文件已生成，下次只重拼）。
 *
 * 换音色重渲染：本用例只渲染 pending / 缓存键失效的片段，不主动重置任何片段；
 * 「只重置目标角色片段」由调用方用 `SegmentRepository.resetByRole(novelId, roleId)`
 * 决定（§8.9）。`resetByRole` 同时把这些章节的 `audio_status` 置回 pending。
 *
 * 已知缺口（本任务不改 `core/**`、`data/**`）：
 * - `SegmentRepository` 没有「写回片段音频字段」的方法，这里用 [db] 的
 *   `segmentDao.update` 写 `audioRelPath/ttsKey/durationMs/errorMsg`，**再**调用
 *   [SegmentRepository.setStatus] 让仓库在同一事务里同步 `segDone` 计数；
 * - `character_tuning` 表没有 DAO/仓库，调参（speed/volume/pitch）暂不生效，
 *   volume/pitch 用构造参数默认值；角色锁定语速取 `characters.speed`；
 * - [narratorSilenceMs]/[paragraphSilenceMs] 只做校验与暴露，`ChapterStitcher.stitch`
 *   目前固定用 §10.3 的 350/700ms（P2-10 未开放入参），接线时需在 core 侧补参数。
 *
 * 并发约束：同一章节不要并发调用 [render]（worker 池按片段 id 写入，没有章级互斥）；
 * 调用方（WorkManager/前台服务）负责串行化。
 *
 * @param db 仅用于写片段音频字段（见上「已知缺口」），不参与其它查询
 * @param characterRepository 可空：未接线时全部使用全局默认音色/语速
 * @param voicePool 必填，用于把角色类别 / `defaultVoiceCategory` 解析成 Fish voice_id
 * @param tts 合成注入槽（默认 [TtsSynthesizer.synthesize]），测试用 fake 替换
 * @param stitch 拼接注入槽（默认 [ChapterStitcher.stitch]），测试用 fake 替换
 * @param durationReader 读取片段音频时长（默认 MediaMetadataRetriever），测试可注入
 */
class RenderChapterUseCase(
    private val segmentRepository: SegmentRepository,
    private val chapterRepository: ChapterRepository,
    private val db: AppDatabase,
    private val audioStorage: AudioStorage,
    ttsSynthesizer: TtsSynthesizer,
    chapterStitcher: ChapterStitcher,
    private val voicePool: VoicePool,
    private val characterRepository: CharacterRepository? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val ttsConcurrency: Int = DEFAULT_TTS_CONCURRENCY,
    private val defaultSpeed: Double = DEFAULT_SPEED,
    private val defaultVolume: Double = DEFAULT_VOLUME,
    private val defaultPitch: Double = DEFAULT_PITCH,
    private val ttsModel: String = DEFAULT_TTS_MODEL,
    private val narratorSilenceMs: Int = ChapterStitcher.DEFAULT_NARRATOR_SILENCE_MS,
    private val paragraphSilenceMs: Int = ChapterStitcher.DEFAULT_PARAGRAPH_SILENCE_MS,
    private val defaultVoiceCategory: String = DEFAULT_VOICE_CATEGORY,
    private val durationReader: (File) -> Long = ::probeDurationMs,
    private val tts: suspend (TtsSpec) -> TtsResult = { spec -> ttsSynthesizer.synthesize(spec) },
    private val stitch: ChapterStitchCall = { novelId, chapterIdx, files, types ->
        chapterStitcher.stitch(novelId, chapterIdx, files, types, audioStorage)
    },
) {

    /**
     * 全局默认音色：`default_voice_category` 不在池里时退回旁白类别（§8.3 契约里有）。
     * 构造期就解析，避免每段重复查池。
     */
    private val fallbackVoiceId: String =
        (voicePool.voiceIdFor(defaultVoiceCategory)
            ?: voicePool.voiceIdFor(voicePool.narratorCategory))
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("音色池缺少默认音色：$defaultVoiceCategory")

    init {
        require(ttsConcurrency >= 1) { "ttsConcurrency 必须为正数：$ttsConcurrency" }
        require(defaultSpeed in SPEED_RANGE) { "defaultSpeed 超出 ${SPEED_RANGE}：$defaultSpeed" }
        require(defaultVolume in VOLUME_RANGE) {
            "defaultVolume 超出 ${VOLUME_RANGE}：$defaultVolume"
        }
        require(defaultPitch in PITCH_RANGE) { "defaultPitch 超出 ${PITCH_RANGE}：$defaultPitch" }
        require(ttsModel.isNotBlank()) { "ttsModel 不能为空" }
        require(narratorSilenceMs >= 0) { "narratorSilenceMs 不能为负：$narratorSilenceMs" }
        require(paragraphSilenceMs >= 0) {
            "paragraphSilenceMs 不能为负：$paragraphSilenceMs"
        }
        require(defaultVoiceCategory.isNotBlank()) { "defaultVoiceCategory 不能为空" }
    }

    /** 配置校验后的有效值，便于调用方/日志核对。 */
    val configuredSilences: Pair<Int, Int> get() = narratorSilenceMs to paragraphSilenceMs

    /**
     * 渲染一章。
     *
     * @param novelId 章节所属项目；与 `chapter.novelId` 不一致时抛
     *   [IllegalArgumentException]（编程错误，不写库）
     * @param chapterId 要渲染的章节
     * @param shouldStop 每段前/后查询；返回 `true` 时优雅停止（见类注释）
     */
    suspend fun render(
        novelId: Long,
        chapterId: Long,
        shouldStop: () -> Boolean = { false },
    ): RenderResult = withContext(dispatcher) {
        require(novelId > 0) { "novelId 必须为正数：$novelId" }
        require(chapterId > 0) { "chapterId 必须为正数：$chapterId" }

        val chapter = chapterRepository.getChapter(chapterId)
            ?: return@withContext RenderResult(
                status = RenderStatus.Error,
                chapterId = chapterId,
                errors = listOf(
                    RenderError(code = ERROR_CHAPTER_NOT_FOUND, message = "章节不存在：$chapterId"),
                ),
            )
        require(chapter.novelId == novelId) {
            "章节 $chapterId 属于项目 ${chapter.novelId}，与传入的 $novelId 不一致"
        }

        val segments = segmentRepository.list(chapterId)
        if (segments.isEmpty()) {
            // §8.9：空片段章节明确报错，不调用 ffmpeg，也不改章节状态（避免污染未解析章节）。
            return@withContext RenderResult(
                status = RenderStatus.NoSegments,
                chapterId = chapterId,
                errors = listOf(
                    RenderError(
                        code = PipelineError.NO_SEGMENTS,
                        message = PipelineError.NoSegments().userMessage,
                    ),
                ),
            )
        }

        val characters = characterRepository?.list(novelId)
            ?.associateBy { it.roleId }
            .orEmpty()
        val tasks = segments.map { segment ->
            planTask(
                novelId = novelId,
                chapterIdx = chapter.idx,
                segment = segment,
                character = characters[segment.roleId],
            )
        }
        val cachedTasks = tasks.filter { it.cached }
        val pendingTasks = tasks.filterNot { it.cached }

        if (shouldStop()) {
            return@withContext stoppedResult(chapterId, cachedTasks.size)
        }

        chapterRepository.updateAudioStatus(chapterId, AudioStatus.Running.wire, null)

        val batch = runWorkers(pendingTasks, shouldStop)
        val outcomes = batch.outcomes
        val cachedCount = cachedTasks.size + outcomes.count { it.kind == OutcomeKind.Cached }
        val synthesizedCount = outcomes.count { it.kind == OutcomeKind.Synthesized }
        val failedOutcomes = outcomes.filter { it.kind == OutcomeKind.Failed }
        val errors = failedOutcomes.mapNotNull { it.error }
            .sortedBy { it.segmentSeq ?: Int.MAX_VALUE }

        if (batch.stopped) {
            // 未开始的片段保持原状态；章节回到 pending，下次渲染接着补。
            return@withContext stoppedResult(chapterId, cachedCount, synthesizedCount, errors)
        }

        if (failedOutcomes.isNotEmpty()) {
            // §8.9：单段失败 → 章节 error，但保留已完成片段；有失败就不拼接。
            chapterRepository.updateAudioStatus(
                chapterId,
                AudioStatus.Error.wire,
                chapterErrorMessage(failedOutcomes),
            )
            return@withContext RenderResult(
                status = if (failedOutcomes.size == tasks.size) {
                    RenderStatus.Error
                } else {
                    RenderStatus.Partial
                },
                chapterId = chapterId,
                cachedCount = cachedCount,
                synthesizedCount = synthesizedCount,
                failedCount = failedOutcomes.size,
                errors = errors,
            )
        }

        val ordered = tasks.sortedBy { it.segment.seq }
        val files = ordered.map { audioStorage.resolve(it.audioRelative) }
        val types = ordered.map { it.type }
        val stitched = try {
            stitch(novelId, chapter.idx, files, types)
        } catch (cancellation: CancellationException) {
            // 外部取消：不动章节状态（sweepRunning 负责复位 running）。
            throw cancellation
        } catch (e: Exception) {
            val error = asStitchError(e)
            chapterRepository.updateAudioStatus(chapterId, AudioStatus.Error.wire, error.userMessage)
            return@withContext RenderResult(
                status = RenderStatus.Error,
                chapterId = chapterId,
                cachedCount = cachedCount,
                synthesizedCount = synthesizedCount,
                errors = errors + RenderError(code = error.code, message = error.userMessage),
            )
        }

        chapterRepository.updateAudioStatus(chapterId, AudioStatus.Done.wire, null)
        RenderResult(
            status = RenderStatus.Done,
            chapterId = chapterId,
            cachedCount = cachedCount,
            synthesizedCount = synthesizedCount,
            outputRelative = stitched.relativePath,
            durationMs = stitched.durationMs,
            errors = errors,
        )
    }

    // ------------------------------------------------------------ 工作池

    /**
     * 固定 worker 抢同一个队列，**没有批屏障**：某段一完成，空闲 worker 立刻取下一段。
     * 返回所有「已完成收尾」的片段结果与是否被停止（停止时不含未开始的段）。
     */
    private suspend fun runWorkers(
        pendingTasks: List<RenderTask>,
        shouldStop: () -> Boolean,
    ): WorkerBatch {
        if (pendingTasks.isEmpty()) return WorkerBatch(stopped = false, outcomes = emptyList())

        val queue = ArrayDeque(pendingTasks)
        val queueLock = Mutex()
        val outcomes = Collections.synchronizedList(mutableListOf<SegmentOutcome>())
        val stoppedFlag = AtomicBoolean(false)
        val workers = minOf(ttsConcurrency, pendingTasks.size)

        coroutineScope {
            repeat(workers) {
                launch {
                    while (!stoppedFlag.get()) {
                        if (shouldStop()) {
                            stoppedFlag.set(true)
                            break
                        }
                        val task = queueLock.withLock { queue.removeFirstOrNull() }
                            ?: break
                        if (shouldStop()) {
                            // 已出队但尚未开始：不改 DB，片段保持原状态。
                            stoppedFlag.set(true)
                            break
                        }
                        outcomes += processTask(task)
                        // 每段完成后再次检查，停止时本轮剩余片段保持 pending。
                        if (shouldStop()) stoppedFlag.set(true)
                    }
                }
            }
        }

        return WorkerBatch(stopped = stoppedFlag.get(), outcomes = outcomes.toList())
    }

    /** 处理一段：running → 合成 → done/error。异常不向外抛（除取消）。 */
    private suspend fun processTask(task: RenderTask): SegmentOutcome {
        segmentRepository.setStatus(listOf(task.segment.id), SegmentStatus.Running.wire)

        val result = try {
            tts(task.spec)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            TtsResult.Failed(asTtsError(task.spec, e))
        }

        return when (result) {
            is TtsResult.Cached -> {
                saveDone(task, result.relativePath)
                SegmentOutcome(kind = OutcomeKind.Cached)
            }

            is TtsResult.Synthesized -> {
                saveDone(task, result.relativePath)
                SegmentOutcome(kind = OutcomeKind.Synthesized)
            }

            is TtsResult.Failed -> {
                saveError(task.segment, result.error)
                SegmentOutcome(
                    kind = OutcomeKind.Failed,
                    error = RenderError(
                        code = result.error.code,
                        message = result.error.userMessage,
                        segmentId = task.segment.id,
                        segmentSeq = task.segment.seq,
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------ 片段规划

    /**
     * 求一段的 [TtsSpec] 与缓存状态。
     *
     * 缓存命中条件（§8.9）：`status=done`、`ttsKey==当前 v2 键`、`audioRelPath` 指向的
     * 文件存在且非空。三者缺一都重新合成；`legacy` 键不再接受（v2 之前的数据直接重跑）。
     */
    private fun planTask(
        novelId: Long,
        chapterIdx: Int,
        segment: SegmentEntity,
        character: CharacterEntity?,
    ): RenderTask {
        val canonicalRelative = audioStorage.segmentAudioRelative(novelId, chapterIdx, segment.seq)
        val speed = character?.speed
            ?.takeIf { it.isFinite() }
            ?.coerceIn(SPEED_RANGE)
            ?: defaultSpeed
        val spec = TtsSpec(
            text = segment.text,
            emotion = Emotion.fromWire(segment.emotion),
            voiceId = voiceIdFor(character),
            speed = speed,
            volume = defaultVolume,
            pitch = defaultPitch,
            model = ttsModel,
            outputRelative = canonicalRelative,
            segmentIndex = segment.seq + 1,
        )
        val key = TtsCacheKey.v2(
            TtsCacheInput(
                text = spec.text,
                emotion = spec.emotion,
                voiceId = spec.voiceId,
                speed = spec.speed,
                volume = spec.volume,
                pitch = spec.pitch,
                model = spec.model,
            ),
        )

        val existing = segment.audioRelPath?.takeIf { it.isNotBlank() }
        val cached = segment.status == SegmentStatus.Done.wire &&
            existing != null &&
            segment.ttsKey == key &&
            isUsableFile(existing)

        return RenderTask(
            segment = segment,
            type = SegmentType.fromWire(segment.type),
            spec = spec,
            key = key,
            cached = cached,
            audioRelative = if (cached) requireNotNull(existing) else canonicalRelative,
        )
    }

    /**
     * §8.9 的音色优先级：角色锁定 `voiceId` → 角色类别 → 全局默认。
     * `characters.voiceId` 为空时（历史/脏数据）用 `category` 再查一次池。
     */
    private fun voiceIdFor(character: CharacterEntity?): String {
        character?.voiceId?.takeIf { it.isNotBlank() }?.let { return it }
        character?.category?.takeIf { it.isNotBlank() }?.let { category ->
            voicePool.voiceIdFor(category)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return fallbackVoiceId
    }

    // ------------------------------------------------------------ 落库

    /**
     * 写片段音频字段，再由仓库改状态（仓库在同一事务里同步 `chapters.segDone`）。
     *
     * 分开写是因为 P2-02 的 [SegmentRepository] 只有 `setStatus`，没有写音频字段的方法；
     * 两次写之间崩溃最多留下 `running + 新字段`，由 §8.9 的 `sweepRunning` 复位。
     */
    private suspend fun saveDone(task: RenderTask, audioRelative: String) {
        val updatedAt = System.currentTimeMillis()
        val durationMs = readDuration(audioStorage.resolve(audioRelative))
        db.segmentDao().update(
            task.segment.copy(
                audioRelPath = audioRelative,
                ttsKey = task.key,
                durationMs = durationMs,
                errorMsg = null,
                updatedAt = updatedAt,
            ),
        )
        segmentRepository.setStatus(
            ids = listOf(task.segment.id),
            status = SegmentStatus.Done.wire,
            updatedAt = updatedAt,
        )
    }

    /** 失败只写 `errorMsg`，**保留**旧的 audioRelPath/ttsKey/duration（旧音频不被销毁）。 */
    private suspend fun saveError(segment: SegmentEntity, error: PipelineError) {
        val updatedAt = System.currentTimeMillis()
        db.segmentDao().update(
            segment.copy(
                errorMsg = error.userMessage.take(MAX_ERROR_CHARS),
                updatedAt = updatedAt,
            ),
        )
        segmentRepository.setStatus(
            ids = listOf(segment.id),
            status = SegmentStatus.Error.wire,
            updatedAt = updatedAt,
        )
    }

    // ------------------------------------------------------------ 工具

    private suspend fun stoppedResult(
        chapterId: Long,
        cachedCount: Int,
        synthesizedCount: Int = 0,
        errors: List<RenderError> = emptyList(),
    ): RenderResult {
        chapterRepository.updateAudioStatus(chapterId, AudioStatus.Pending.wire, null)
        return RenderResult(
            status = RenderStatus.Stopped,
            chapterId = chapterId,
            cachedCount = cachedCount,
            synthesizedCount = synthesizedCount,
            failedCount = errors.size,
            errors = errors,
        )
    }

    private fun chapterErrorMessage(failed: List<SegmentOutcome>): String {
        val first = failed.firstOrNull()?.error?.message ?: "片段合成失败"
        return if (failed.size == 1) {
            first
        } else {
            "有 ${failed.size} 段合成失败，可重试失败片段（$first）"
        }
    }

    private fun asTtsError(spec: TtsSpec, cause: Throwable): PipelineError =
        cause as? PipelineError ?: PipelineError.TtsFailed(
            segmentIndex = spec.segmentIndex,
            detail = cause.message ?: cause.javaClass.simpleName,
            cause = cause,
        )

    private fun asStitchError(cause: Throwable): PipelineError =
        cause as? PipelineError ?: PipelineError.Ffmpeg(
            detail = cause.message.orEmpty(),
            cause = cause,
        )

    private fun isUsableFile(relative: String): Boolean = try {
        val file = audioStorage.resolve(relative)
        file.isFile && file.length() > 0L
    } catch (_: IllegalArgumentException) {
        false
    }

    /** 读片段时长；读不到/异常返回 null（不阻断已成功的合成）。 */
    private fun readDuration(file: File): Int? = try {
        durationReader(file).takeIf { it > 0L }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------ 内部类型

    /** 一段的渲染计划；[cached] 为 true 时 [audioRelative] 是旧文件（不重新合成）。 */
    private data class RenderTask(
        val segment: SegmentEntity,
        val type: SegmentType,
        val spec: TtsSpec,
        val key: String,
        val cached: Boolean,
        val audioRelative: String,
    )

    private enum class OutcomeKind { Cached, Synthesized, Failed }

    private data class SegmentOutcome(
        val kind: OutcomeKind,
        val error: RenderError? = null,
    )

    /** 一轮 worker 扫描的结果；[stopped] 表示被 `shouldStop` 提前终止。 */
    private data class WorkerBatch(
        val stopped: Boolean,
        val outcomes: List<SegmentOutcome>,
    )

    companion object {
        /** §7.4 TTS 并发默认值（1–8）。 */
        const val DEFAULT_TTS_CONCURRENCY: Int = 4

        /** §7.4 全局默认语速/音量/音调。 */
        const val DEFAULT_SPEED: Double = 1.0
        const val DEFAULT_VOLUME: Double = 1.0
        const val DEFAULT_PITCH: Double = 0.0

        /** §7.4 默认 TTS 模型；与 `AppSettings.fishTtsModel` 保持一致。 */
        const val DEFAULT_TTS_MODEL: String = "s2.1-pro-free"

        /** §7.4 默认音色类别；池里缺失时回退旁白类别。 */
        const val DEFAULT_VOICE_CATEGORY: String = "narrator_male"

        /** 章节不存在时的错误码（与 `AnalyzeChapterUseCase` 的约定一致）。 */
        const val ERROR_CHAPTER_NOT_FOUND: String = "CHAPTER_NOT_FOUND"

        private const val MAX_ERROR_CHARS = 500

        private val SPEED_RANGE = 0.5..2.0
        private val VOLUME_RANGE = 0.5..2.0
        private val PITCH_RANGE = -12.0..12.0
    }
}

/**
 * 默认片段时长读取：`MediaMetadataRetriever`（与 `ChapterStitcher` 内部实现同口径）。
 * 测试通过 [RenderChapterUseCase] 构造参数注入 fake，不需要 Android 媒体栈。
 */
private fun probeDurationMs(file: File): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?: 0L
    } catch (_: Exception) {
        0L
    } finally {
        runCatching { retriever.release() }
    }
}
