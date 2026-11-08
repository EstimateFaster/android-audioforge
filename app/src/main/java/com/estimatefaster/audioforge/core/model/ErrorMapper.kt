package com.estimatefaster.audioforge.core.model

import com.estimatefaster.audioforge.core.audio.FfmpegException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.CharacterCodingException
import kotlinx.serialization.SerializationException

/**
 * 把底层异常统一收敛为 [PipelineError]（执行计划 §9.5、§15 P2-16）。
 *
 * 映射表：
 * | 输入 | 输出 |
 * |---|---|
 * | [PipelineError] | 原样返回（保留 code / retryable / detail / cause） |
 * | [FfmpegException] | [PipelineError.Ffmpeg]（返回码与日志首行进 detail） |
 * | [CharacterCodingException] | [PipelineError.BadJson] |
 * | [SerializationException] | [PipelineError.BadJson] |
 * | [SocketTimeoutException] / [UnknownHostException] / 其它 [IOException] | [PipelineError.Network] |
 * | [SecurityException] | [PipelineError.Storage] |
 * | 其它未知异常 | [PipelineError.Network]（兜底，见下） |
 *
 * §9.5 的 11 个错误码都可达：认证 / 额度 / 模型 / 截断 / TTS / 空章节由上游直接抛
 * [PipelineError] 后原样透传，其中认证 / 额度 / 模型也可经 [httpError] 按 HTTP
 * 状态码得到；网络、BAD_JSON、FFMPEG、STORAGE 则由本映射器从异常类型得到。
 *
 * 设计说明：
 * - [SecurityException] 在 Android 上通常来自无权访问文件 / 目录（如外部存储权限、
 *   分区存储受限），语义最接近 §9.5 的 STORAGE，因此映射为 [PipelineError.Storage]，
 *   而不是笼统的 Network。
 * - 未知异常兜底为 [PipelineError.Network]：§9.5 没有「未知」错误码，Network 的文案
 *   中性且可重试，能避免把堆栈 / 英文报错泄漏给用户；诊断信息仍保留在
 *   [PipelineError.cause] 里，只进日志不进 UI。
 * - 原始异常一律通过 `cause` 保留，[PipelineError.userMessage] 不含底层细节。
 */
object ErrorMapper {

    /**
     * 把任意异常映射为可直接展示 / 落库的 [PipelineError]。
     *
     * 已经是 [PipelineError] 的实例原样返回（同一个对象），避免丢失段号、detail
     * 或显式的 `retryable` 设置。
     */
    fun map(error: Throwable): PipelineError = when (error) {
        is PipelineError -> error
        is FfmpegException -> PipelineError.Ffmpeg(
            detail = ffmpegDetail(error),
            cause = error,
        )
        // CharacterCodingException 是 IOException 的子类，必须排在 IOException 之前，
        // 否则合法 JSON 解码时的字符集错误会被误判成网络问题。
        is CharacterCodingException -> PipelineError.BadJson(error)
        is SerializationException -> PipelineError.BadJson(error)
        is SocketTimeoutException -> PipelineError.Network(error)
        is UnknownHostException -> PipelineError.Network(error)
        is IOException -> PipelineError.Network(error)
        is SecurityException -> PipelineError.Storage(error)
        else -> PipelineError.Network(error)
    }

    /** [map] 之后取用户文案；调用方不需要再判空。 */
    fun userMessage(error: Throwable): String = map(error).userMessage

    /**
     * HTTP 状态码 → [PipelineError]；直接委托 [PipelineError.fromHttp]：
     * 401 按 [source] 区分 LLM / Fish，402、429 → QUOTA，400、404 → MODEL_NOT_FOUND，
     * 其它状态（5xx / 403 / 2xx 等）返回 `null`，由调用方决定退避或重试。
     */
    fun httpError(status: Int, source: PipelineError.Source): PipelineError? =
        PipelineError.fromHttp(status, source)

    /**
     * 取 FFmpeg 日志的首个非空行（避免整段 stderr 撑爆 UI），并与返回码一起
     * 拼成 detail；日志为空时只给返回码。
     */
    private fun ffmpegDetail(error: FfmpegException): String {
        val firstLine = error.log
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        val logPreview = if (firstLine.length > MAX_DETAIL_CHARS) {
            firstLine.take(MAX_DETAIL_CHARS) + ELLIPSIS
        } else {
            firstLine
        }
        return if (logPreview.isEmpty()) {
            "FFmpeg 返回码 ${error.returnCode}"
        } else {
            "FFmpeg 返回码 ${error.returnCode}：$logPreview"
        }
    }

    private const val MAX_DETAIL_CHARS = 200
    private const val ELLIPSIS = "…"
}
