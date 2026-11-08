package com.estimatefaster.audioforge.core.model

/**
 * 流水线统一错误模型（执行计划 §9.5）。
 *
 * - [code]：稳定的机器可读错误码，用于落库、日志与重试策略，不能随意改名；
 * - [retryable]：是否值得自动重试。原样重试能成功的才设为 `true`，
 *   需要用户改设置 / 换模型 / 清理空间 / 换章节的错误一律为 `false`；
 * - [userMessage]：可直接展示给用户的中文文案；
 * - [cause]：底层异常（HTTP / IO / 解析），只用于日志和诊断，不直接展示。
 *
 * 重试语义：
 * - 可重试：[Network]（超时 / 断网，恢复后继续）、[Quota]（402/429，退避后可能成功）。
 * - 不可重试：[AuthLlm]、[AuthFish]、[ModelNotFound]、[Truncated]、[BadJson]、
 *   [Storage]、[NoSegments] —— 这些是配置 / 输入 / 环境问题，重试只会再次失败。
 * - 默认不可重试、但允许调用方显式开启：[TtsFailed]、[Ffmpeg]（单段合成 / 拼接失败
 *   通常与文本、参数或输出文件有关；确认是瞬时故障时可传 `retryable = true`）。
 */
sealed class PipelineError(
    val code: String,
    val retryable: Boolean,
    val userMessage: String,
    cause: Throwable? = null,
) : Exception(userMessage, cause) {

    /** LLM Key 无效（HTTP 401）。需要在设置里换 Key，重试无意义。 */
    class AuthLlm(cause: Throwable? = null) : PipelineError(
        code = PipelineError.AUTH_LLM,
        retryable = false,
        userMessage = "LLM Key 无效，请在设置中检查",
        cause = cause,
    )

    /** Fish Audio Key 无效（HTTP 401）。需要在设置里换 Key，重试无意义。 */
    class AuthFish(cause: Throwable? = null) : PipelineError(
        code = PipelineError.AUTH_FISH,
        retryable = false,
        userMessage = "Fish Key 无效，请在设置中检查",
        cause = cause,
    )

    /** 额度不足或被限流（HTTP 402 / 429）。退避后可能成功。 */
    class Quota(cause: Throwable? = null) : PipelineError(
        code = PipelineError.QUOTA,
        retryable = true,
        userMessage = "额度不足或被限流，稍后再试",
        cause = cause,
    )

    /** 模型不存在（HTTP 404）或模型名 / 请求体不被接受（HTTP 400，LLM 侧常见）。 */
    class ModelNotFound(cause: Throwable? = null) : PipelineError(
        code = PipelineError.MODEL_NOT_FOUND,
        retryable = false,
        userMessage = "模型不存在，请检查模型名",
        cause = cause,
    )

    /** 超时或断网。恢复网络后可从断点继续。 */
    class Network(cause: Throwable? = null) : PipelineError(
        code = PipelineError.NETWORK,
        retryable = true,
        userMessage = "网络不可用，已暂停，恢复后继续",
        cause = cause,
    )

    /** `finish_reason=length`，本章输出被截断。需要拆分章节后重跑。 */
    class Truncated(cause: Throwable? = null) : PipelineError(
        code = PipelineError.TRUNCATED,
        retryable = false,
        userMessage = "本章过长，输出被截断",
        cause = cause,
    )

    /** 模型输出不是合法 JSON，已降级为旁白。重试不会改变模型输出。 */
    class BadJson(cause: Throwable? = null) : PipelineError(
        code = PipelineError.BAD_JSON,
        retryable = false,
        userMessage = "模型输出不是合法 JSON，已降级为旁白",
        cause = cause,
    )

    /**
     * 第 [segmentIndex] 段语音合成失败（段序号从 1 开始）。
     *
     * @param detail 失败原因，拼在文案冒号后；为空时只显示「第 N 段合成失败」。
     * @param retryable 默认 `false`；确认是瞬时故障时可显式传 `true`。
     */
    class TtsFailed(
        val segmentIndex: Int,
        val detail: String = "",
        cause: Throwable? = null,
        retryable: Boolean = false,
    ) : PipelineError(
        code = PipelineError.TTS_FAILED,
        retryable = retryable,
        userMessage = ttsFailedMessage(segmentIndex, detail),
        cause = cause,
    )

    /** 存储空间不足。清理后重试。 */
    class Storage(cause: Throwable? = null) : PipelineError(
        code = PipelineError.STORAGE,
        retryable = false,
        userMessage = "存储空间不足，请清理后重试",
        cause = cause,
    )

    /**
     * 音频拼接失败。
     *
     * @param detail FFmpeg 的失败原因（返回码 / 关键日志），拼在文案冒号后；
     *   为空时只显示「音频拼接失败」。
     * @param retryable 默认 `false`；确认是瞬时故障（如临时文件被占用）时可显式传 `true`。
     */
    class Ffmpeg(
        val detail: String = "",
        cause: Throwable? = null,
        retryable: Boolean = false,
    ) : PipelineError(
        code = PipelineError.FFMPEG,
        retryable = retryable,
        userMessage = ffmpegMessage(detail),
        cause = cause,
    )

    /** 章节为空，没有可拼接的片段。需要换章节，重试无意义。 */
    class NoSegments(cause: Throwable? = null) : PipelineError(
        code = PipelineError.NO_SEGMENTS,
        retryable = false,
        userMessage = "本章没有可拼接的片段",
        cause = cause,
    )

    /** HTTP 错误来源：LLM 或 Fish Audio。仅用于区分 401 的文案。 */
    enum class Source {
        LLM,
        FISH,
    }

    companion object {
        const val AUTH_LLM = "AUTH_LLM"
        const val AUTH_FISH = "AUTH_FISH"
        const val QUOTA = "QUOTA"
        const val MODEL_NOT_FOUND = "MODEL_NOT_FOUND"
        const val NETWORK = "NETWORK"
        const val TRUNCATED = "TRUNCATED"
        const val BAD_JSON = "BAD_JSON"
        const val TTS_FAILED = "TTS_FAILED"
        const val STORAGE = "STORAGE"
        const val FFMPEG = "FFMPEG"
        const val NO_SEGMENTS = "NO_SEGMENTS"

        /**
         * 把 HTTP 状态码映射为 [PipelineError]；无法归类的状态返回 `null`，
         * 由调用方决定退避（如 5xx / 503）或原样透传。
         *
         * - 401：按 [source] 区分 [AuthLlm] / [AuthFish]；
         * - 402 / 429：[Quota]；
         * - 404：[ModelNotFound]；
         * - 400：[ModelNotFound]（LLM 侧通常是模型名或请求体问题）；
         * - 其它（200 / 403 / 5xx 等）：`null`。
         */
        fun fromHttp(status: Int, source: Source): PipelineError? = when (status) {
            401 -> when (source) {
                Source.LLM -> AuthLlm()
                Source.FISH -> AuthFish()
            }
            402, 429 -> Quota()
            404 -> ModelNotFound()
            400 -> ModelNotFound()
            else -> null
        }
    }
}

private fun ttsFailedMessage(segmentIndex: Int, detail: String): String {
    val base = "第 $segmentIndex 段合成失败"
    val trimmed = detail.trim()
    return if (trimmed.isEmpty()) base else "$base：$trimmed"
}

private fun ffmpegMessage(detail: String): String {
    val trimmed = detail.trim()
    return if (trimmed.isEmpty()) "音频拼接失败" else "音频拼接失败：$trimmed"
}
