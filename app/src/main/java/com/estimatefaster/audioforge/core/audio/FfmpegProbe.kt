package com.estimatefaster.audioforge.core.audio

import android.util.Log
import com.arthenica.ffmpegkit.FFprobeKit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** P0-07 FFmpeg 冒烟状态；首页 Debug 卡直接订阅。 */
sealed interface FfmpegProbeState {
    data object Idle : FfmpegProbeState
    data object Running : FfmpegProbeState
    data class Passed(
        val version: String,
        val silenceBytes: Long,
        val joinedDurationMs: Long,
    ) : FfmpegProbeState

    data class Failed(val message: String) : FfmpegProbeState
}

/**
 * 真机/模拟器 FFmpeg 冒烟（执行计划 §15 P0-07）：
 * `-version` → 350ms 静音 → 两段短 mp3 → concat 拼接 → ffprobe 时长。
 * 只写应用 cacheDir，不碰用户目录。
 */
class FfmpegProbe(
    private val runner: FfmpegKitRunner,
    private val cacheDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<FfmpegProbeState>(FfmpegProbeState.Idle)
    val state: StateFlow<FfmpegProbeState> = _state.asStateFlow()

    suspend fun run(): FfmpegProbeState = withContext(io) {
        _state.value = FfmpegProbeState.Running
        val result = try {
            val version = runner.version()
                .lineSequence()
                .firstOrNull { it.contains("ffmpeg version") }
                ?.trim()
                ?: "ffmpeg version unknown"

            val dir = File(cacheDir, "ffmpeg-smoke").apply {
                deleteRecursively()
                mkdirs()
            }

            val silence = File(dir, "sil350.mp3")
            runner.run(silenceCommand(silence))
            check(silence.length() > 0) { "静音文件为空" }

            val toneA = File(dir, "tone_a.mp3")
            val toneB = File(dir, "tone_b.mp3")
            runner.run(toneCommand(toneA, frequency = 440))
            runner.run(toneCommand(toneB, frequency = 660))

            val concatList = File(dir, "concat.txt").apply {
                writeText("file '${toneA.absolutePath}'\nfile '${toneB.absolutePath}'\n")
            }
            val joined = File(dir, "joined.mp3")
            runner.run(
                "-y -f concat -safe 0 -i \"${concatList.absolutePath}\" " +
                    "-c copy \"${joined.absolutePath}\"",
            )
            check(joined.length() > 0) { "拼接文件为空" }

            val durationSeconds = FFprobeKit.getMediaInformation(joined.absolutePath)
                .mediaInformation
                ?.duration
                ?.toDoubleOrNull() ?: 0.0
            check(durationSeconds > 0.6) { "拼接时长异常：${durationSeconds}s" }

            FfmpegProbeState.Passed(
                version = version,
                silenceBytes = silence.length(),
                joinedDurationMs = (durationSeconds * 1000).toLong(),
            ).also {
                Log.i(TAG, "ffmpeg smoke passed：$version，拼接 ${it.joinedDurationMs}ms")
            }
        } catch (e: Exception) {
            val message = e.message ?: e::class.java.simpleName
            Log.e(TAG, "ffmpeg smoke failed：$message", e)
            FfmpegProbeState.Failed(message)
        }
        _state.value = result
        result
    }

    private fun silenceCommand(output: File): String =
        "-y -f lavfi -i anullsrc=r=44100:cl=mono -t 0.35 -c:a libmp3lame -b:a 64k " +
            "\"${output.absolutePath}\""

    private fun toneCommand(output: File, frequency: Int): String =
        "-y -f lavfi -i \"sine=frequency=$frequency:duration=0.5\" " +
            "-c:a libmp3lame -b:a 64k \"${output.absolutePath}\""

    companion object {
        const val TAG = "AudioForgeFfmpegProbe"
    }
}
