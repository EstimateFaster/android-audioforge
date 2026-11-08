package com.estimatefaster.audioforge.core.audio

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * 生成并缓存章节拼接用的静音 mp3（执行计划 §10.3、P2-10）。
 *
 * - 缓存文件名固定为 `sil_<durationMs>.mp3`，存在且非空时直接复用；
 * - 生成命令：`-f lavfi -i anullsrc=r=44100:cl=mono -t <秒> -c:a libmp3lame -b:a 64k`；
 * - 先写带 `.mp3` 后缀的临时文件再 rename，失败不会留下半成品，也不会被复用；
 * - 默认值 350ms / 700ms 来自设置 `narrator_silence_ms` / `paragraph_silence_ms`；
 *   本类也接受任意正整数毫秒（缓存按毫秒区分）。
 *
 * 同一个实例内的“检查缓存 → 生成 → rename”由 [Mutex] 串行化；真正的 ffmpeg
 * 会话仍由 [FfmpegKitRunner] 全局串行。
 */
class SilenceGenerator(
    private val runner: FfmpegKitRunner,
    private val cacheDir: File,
) {

    private val mutex = Mutex()

    /**
     * 返回 [durationMs] 毫秒的静音 mp3。
     *
     * @throws IllegalArgumentException 时长不是正数
     * @throws IOException 缓存目录/临时文件不可写，或生成结果为空
     * @throws FfmpegException ffmpeg 生成失败
     */
    suspend fun silenceFile(durationMs: Int): File = mutex.withLock {
        require(durationMs > 0) { "静音时长必须为正数：$durationMs ms" }

        val target = File(cacheDir, cacheFileName(durationMs))
        if (target.isFile && target.length() > 0L) {
            target
        } else {
            generate(target, durationMs)
        }
    }

    private suspend fun generate(target: File, durationMs: Int): File {
        if (!cacheDir.isDirectory && !cacheDir.mkdirs() && !cacheDir.isDirectory) {
            throw IOException("无法创建静音缓存目录：$cacheDir")
        }

        // 保留 .mp3 后缀，让 ffmpeg 按扩展名选择 mp3 muxer；
        // 唯一临时名避免多个实例共享 cacheDir 时互相踩踏。
        val temp = File.createTempFile("sil_${durationMs}_", ".mp3", cacheDir)
        try {
            runner.run(buildCommand(temp, durationMs))
            if (!temp.isFile || temp.length() <= 0L) {
                throw IOException("静音生成失败（文件为空）：$target")
            }
            if (!temp.renameTo(target)) {
                throw IOException("无法写入静音缓存：$target")
            }
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
        return target
    }

    private fun buildCommand(output: File, durationMs: Int): String {
        // Locale.ROOT：避免小数点被格式化成逗号（部分 locale）。
        val seconds = String.format(Locale.ROOT, "%.3f", durationMs / 1000.0)
        return "-y -f lavfi -i anullsrc=r=44100:cl=mono -t $seconds " +
            "-c:a libmp3lame -b:a 64k \"${output.absolutePath}\""
    }

    companion object {
        /** 缓存文件名：`sil_<durationMs>.mp3`。 */
        fun cacheFileName(durationMs: Int): String = "sil_$durationMs.mp3"
    }
}
