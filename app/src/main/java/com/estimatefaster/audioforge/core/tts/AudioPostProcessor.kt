package com.estimatefaster.audioforge.core.tts

import com.estimatefaster.audioforge.core.audio.FfmpegKitRunner
import com.estimatefaster.audioforge.core.audio.FfmpegResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 执行 ffmpeg 命令的最小抽象。
 *
 * 生产代码由 [AudioPostProcessor] 的 `FfmpegKitRunner` 构造函数适配；
 * 单元测试可注入记录型实现，避免在纯 JVM 测试里真的启动 FFmpeg。
 */
interface AudioCommandRunner {
    suspend fun run(command: String): FfmpegResult
}

/**
 * TTS 片段的音调 / 音量后处理（执行计划 §10.2、§15 P2-09）。
 *
 * 命令模板（§10.2）：
 * ```bash
 * ffmpeg -y -i <in.mp3> \
 *   -af "asetrate=44100*2^(pitch/12),aresample=44100,atempo=2^(-pitch/12),volume=<volume>" \
 *   -b:a 64k <out.mp3>
 * ```
 *
 * 语义：
 * - [needsProcessing]：`pitch == 0 && volume == 1` 时无需处理（§10.2）；
 * - [buildFilter]：数字一律走 [TtsCacheKey.plainDecimal] 的稳定格式，
 *   不出现 `1.0E-4` 之类的科学计数法；`-pitch` 在字符串里直接取反，
 *   因此 `pitch = 2.0` → `atempo=2^(-2/12)`、`pitch = -2.0` → `atempo=2^(2/12)`；
 * - [process]：先写同目录下的临时 `.mp3`，成功后才 rename 到 [output]；
 *   带 pitch 的滤镜失败时，用「仅 volume」的滤镜重试一次；两次都失败则删除
 *   半成品、不触碰 [input] 和已有 [output]，返回 `false`。
 *
 * 调用方（[TtsSynthesizer]）在 `false` 时不得把片段标记为 done，应记录 warning。
 *
 * @param runner ffmpeg 执行器；生产用 [FfmpegKitRunner]
 * @param io 文件操作 / 目录创建的调度器，默认 [Dispatchers.IO]
 */
open class AudioPostProcessor(
    private val runner: AudioCommandRunner,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    constructor(
        runner: FfmpegKitRunner,
        io: CoroutineDispatcher = Dispatchers.IO,
    ) : this(FfmpegKitRunnerAdapter(runner), io)

    /**
     * 是否需要跑 ffmpeg。
     *
     * 只有 `pitch == 0` 且 `volume == 1` 才跳过；`-0.0` 视为 `0`
     * （与 [TtsCacheKey] 的浮点归一化一致）。
     */
    open fun needsProcessing(pitch: Double, volume: Double): Boolean =
        pitch != 0.0 || volume != 1.0

    /**
     * 生成 `-af` 滤镜串（§10.2），格式稳定、可直接用于断言：
     * `asetrate=44100*2^(pitch/12),aresample=44100,atempo=2^(-pitch/12),volume=<volume>`。
     */
    open fun buildFilter(pitch: Double, volume: Double): String {
        val pitchPlain = TtsCacheKey.plainDecimal(pitch)
        val inversePitchPlain = TtsCacheKey.plainDecimal(-pitch)
        val volumePlain = TtsCacheKey.plainDecimal(volume)
        return "asetrate=44100*2^($pitchPlain/12)," +
            "aresample=44100," +
            "atempo=2^($inversePitchPlain/12)," +
            "volume=$volumePlain"
    }

    /**
     * 对 [input] 做 pitch / volume 后处理，结果写到 [output]。
     *
     * 实现约定：
     * 1. 不需要处理时（[needsProcessing] 为 `false`）不调 ffmpeg，直接把
     *    [input] 复制到 [output] 并返回 `true`（便于把本方法当作纯转换使用）；
     * 2. 需要处理时在 [output] 同目录创建带 `.mp3` 后缀的临时文件，
     *    ffmpeg 成功后 rename 到 [output]，[output] 只会在完整成功时出现；
     * 3. 首次用完整滤镜失败后，若 `pitch != 0` 再用「仅 volume」滤镜重试一次；
     * 4. 最终失败：删除临时文件与半成品，[input] 与旧 [output] 保持原样，返回 `false`；
     * 5. 协程取消：删除临时文件后原样抛出 `CancellationException`。
     *
     * @return 成功（含降级为仅 volume）为 `true`；输入缺失、ffmpeg 失败、
     *   输出为空或 rename 失败为 `false`
     */
    open suspend fun process(
        input: File,
        output: File,
        pitch: Double,
        volume: Double,
    ): Boolean {
        if (!input.isFile) return false
        if (!needsProcessing(pitch, volume)) return copyWithoutProcessing(input, output)

        val work = withContext(io) { createWorkFile(output) } ?: return false
        return try {
            if (runFilter(input, work, buildFilter(pitch, volume))) {
                return replace(work, output)
            }
            // 完整滤镜失败：先清掉可能的半成品，再用仅 volume 兜底（§10.2）。
            work.delete()
            if (pitch != 0.0 && runFilter(input, work, buildFilter(0.0, volume))) {
                return replace(work, output)
            }
            work.delete()
            false
        } catch (cancellation: CancellationException) {
            work.delete()
            throw cancellation
        } catch (t: Throwable) {
            work.delete()
            false
        }
    }

    /** 生成可执行的 ffmpeg 命令；路径统一用双引号包裹（与 §10.1/§10.3 一致）。 */
    internal fun buildCommand(input: File, output: File, filter: String): String =
        "-y -i \"${input.absolutePath}\" " +
            "-af \"$filter\" " +
            "-b:a $BITRATE " +
            "\"${output.absolutePath}\""

    private suspend fun runFilter(input: File, output: File, filter: String): Boolean = try {
        runner.run(buildCommand(input, output, filter))
        output.isFile && output.length() > 0L
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (t: Throwable) {
        false
    }

    /** 只在成功后把临时文件替换成 [output]；失败删临时文件，旧 [output] 不动。 */
    private suspend fun replace(work: File, output: File): Boolean = withContext(io) {
        if (work.renameTo(output)) {
            true
        } else {
            work.delete()
            false
        }
    }

    /** 无需处理时的直通路径；复制失败返回 `false`，不留下半成品。 */
    private suspend fun copyWithoutProcessing(input: File, output: File): Boolean =
        withContext(io) {
            try {
                val parent = output.absoluteFile.parentFile
                if (parent != null &&
                    !parent.isDirectory &&
                    !parent.mkdirs() &&
                    !parent.isDirectory
                ) {
                    return@withContext false
                }
                input.copyTo(output, overwrite = true)
                true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                false
            }
        }

    /** 在 [output] 同目录创建带 `.mp3` 后缀的临时文件，保证 ffmpeg 选对 muxer。 */
    private fun createWorkFile(output: File): File? {
        val parent = output.absoluteFile.parentFile ?: return null
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) return null
        return try {
            File.createTempFile(TEMP_PREFIX, MP3_SUFFIX, parent)
        } catch (e: Exception) {
            // IOException / SecurityException 都按「无法创建临时文件」处理。
            null
        }
    }

    private class FfmpegKitRunnerAdapter(private val runner: FfmpegKitRunner) : AudioCommandRunner {
        override suspend fun run(command: String): FfmpegResult = runner.run(command)
    }

    private companion object {
        const val BITRATE = "64k"
        const val TEMP_PREFIX = "post_"
        const val MP3_SUFFIX = ".mp3"
    }
}
