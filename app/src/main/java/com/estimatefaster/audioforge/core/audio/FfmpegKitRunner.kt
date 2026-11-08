package com.estimatefaster.audioforge.core.audio

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class FfmpegResult(val sessionId: Long, val output: String)

class FfmpegException(val returnCode: Int, val log: String) :
    Exception("ffmpeg 失败（code=$returnCode）：${log.take(MAX_LOG_CHARS)}") {

    private companion object {
        const val MAX_LOG_CHARS = 800
    }
}

/**
 * FFmpegKit 的最小封装（执行计划 §10.1）：
 * - 全局只允许一个 ffmpeg 会话，避免 CPU/内存峰值；
 * - 协程取消时调用 `FFmpegKit.cancel`；
 * - 错误日志截断到 800 字符，避免把整段 stderr 塞进 UI/日志。
 */
class FfmpegKitRunner {

    private val mutex = Mutex()

    suspend fun run(command: String): FfmpegResult = mutex.withLock {
        suspendCancellableCoroutine { continuation ->
            val sessionHandle = FFmpegKit.executeAsync(command) { session ->
                if (!continuation.isActive) return@executeAsync
                if (ReturnCode.isSuccess(session.returnCode)) {
                    continuation.resume(
                        FfmpegResult(session.sessionId, session.output ?: ""),
                    )
                } else {
                    continuation.resumeWithException(
                        FfmpegException(
                            returnCode = session.returnCode?.value ?: -1,
                            log = session.failStackTrace ?: session.output ?: "",
                        ),
                    )
                }
            }
            continuation.invokeOnCancellation {
                FFmpegKit.cancel(sessionHandle.sessionId)
            }
        }
    }

    suspend fun version(): String = run("-version").output
}
