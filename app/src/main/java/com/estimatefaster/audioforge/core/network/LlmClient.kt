package com.estimatefaster.audioforge.core.network

import com.estimatefaster.audioforge.core.model.PipelineError
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * OpenAI 兼容的 LLM 客户端（P2-07）。
 *
 * 行为约定：
 * - `POST {baseUrl}/chat/completions`，`Authorization: Bearer <apiKey>`；
 * - 首次请求按需携带 `response_format={"type":"json_object"}`；若服务端以 400
 *   明确拒绝该字段（错误信息提到 `response_format`），去掉该字段重试一次；
 * - 429 / 5xx / IO / 超时按指数退避重试：`min(2^attempt * 2, 30)` 秒 +
 *   `0–250ms` jitter；429 / 503 响应带 `Retry-After`（秒数）时优先按它等待，
 *   上限 [MAX_RETRY_AFTER_MS]，此时不再叠加 jitter（服务端已给出明确时间）；
 * - 401 / 402 / 404 / 其它 4xx 不重试，映射为 [PipelineError]；
 * - `finish_reason=length` → [PipelineError.Truncated]；非法 JSON / 空 content →
 *   [PipelineError.BadJson]；
 * - 取消协程时取消底层 OkHttp `Call`。
 *
 * 可注入项（测试用）：
 * - [httpClient]：默认取 [HttpClients.defaultLlm]；[LlmConfig] 改过超时时自动换成
 *   同连接池 / 同 Dispatcher 的派生客户端，用户配置仍然生效；
 * - [sleeper]：退避等待，默认 `delay(ms)`；测试注入 no-op 以免真等；
 * - [jitterSeconds]：返回非负 jitter 秒数，默认随机；实际使用前夹到 `0–0.25s`；
 * - [json]：默认 `ignoreUnknownKeys`，兼容各家多返回的字段；
 * - [responseFormatFallback]：是否允许 400 后去掉 response_format 重试，默认开启。
 */
class LlmClient(
    private val config: LlmConfig,
    private val httpClient: OkHttpClient = HttpClients.defaultLlm(
        connectTimeoutMs = config.connectTimeoutMs.toLong(),
        readTimeoutMs = config.readTimeoutMs.toLong(),
    ),
    private val responseFormatFallback: Boolean = true,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val jitterSeconds: () -> Double = { Random.nextDouble() },
    private val json: Json = DEFAULT_JSON,
) {

    private val endpoint: String = config.baseUrl.trimEnd('/') + CHAT_COMPLETIONS_PATH

    private val maxRetries: Int = config.maxRetries.coerceAtLeast(0)

    /**
     * 发一次对话请求并返回 `choices[0].message.content`。
     *
     * @param prompt 用户消息内容。
     * @param temperature 采样温度，默认 0（确定性输出，便于解析 JSON）。
     * @param responseFormatJson 是否在首次请求携带 `response_format=json_object`。
     * @throws PipelineError 网络、鉴权、额度、模型、截断、JSON 解析等错误。
     */
    suspend fun chat(
        prompt: String,
        temperature: Double = 0.0,
        responseFormatJson: Boolean = true,
    ): String {
        var sendResponseFormat = responseFormatJson
        var formatFallbackUsed = false
        var retries = 0

        while (true) {
            val request = buildRequest(prompt, temperature, sendResponseFormat)
            val result = try {
                execute(request)
            } catch (e: IOException) {
                if (retries < maxRetries) {
                    sleepBeforeRetry(retries, retryAfter = null)
                    retries++
                    continue
                }
                throw PipelineError.Network(e)
            }

            if (result.code in 200..299) {
                return parseContent(result.body)
            }

            // 400 且明确提到 response_format：去掉该字段重试一次（不占普通重试额度）。
            if (
                result.code == 400 &&
                sendResponseFormat &&
                responseFormatFallback &&
                !formatFallbackUsed &&
                mentionsResponseFormat(result.body)
            ) {
                formatFallbackUsed = true
                sendResponseFormat = false
                continue
            }

            if (isRetryableStatus(result.code) && retries < maxRetries) {
                sleepBeforeRetry(retries, result.retryAfter)
                retries++
                continue
            }

            throw httpError(result.code, result.body)
        }
    }

    private fun buildRequest(
        prompt: String,
        temperature: Double,
        includeResponseFormat: Boolean,
    ): Request {
        val payload = ChatCompletionRequest(
            model = config.model,
            messages = listOf(ChatMessage(role = ROLE_USER, content = prompt)),
            temperature = temperature,
            responseFormat = if (includeResponseFormat) ResponseFormat(ResponseFormat.JSON_OBJECT) else null,
        )
        val body = json
            .encodeToString(ChatCompletionRequest.serializer(), payload)
            .toRequestBody(JSON_MEDIA_TYPE)

        return Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(body)
            .build()
    }

    /**
     * 异步执行请求；协程取消时 `call.cancel()`。
     *
     * 响应体在 OkHttp 回调线程内读完并关闭，避免把 [Response] 泄漏给协程；
     * 回调线程阻塞在 socket 读上正是 OkHttp 的预期用法。
     */
    private suspend fun execute(request: Request): HttpResult =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            HttpResult(
                                code = it.code,
                                retryAfter = it.header(RETRY_AFTER_HEADER),
                                body = it.body?.string().orEmpty(),
                            )
                        }
                    } catch (e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                        return
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }

    private fun parseContent(body: String): String {
        val parsed = try {
            json.decodeFromString(ChatCompletionResponse.serializer(), body)
        } catch (e: SerializationException) {
            throw PipelineError.BadJson(e)
        }

        val choice = parsed.choices.firstOrNull() ?: throw PipelineError.BadJson()
        if (choice.finishReason == FINISH_REASON_LENGTH) throw PipelineError.Truncated()

        val content = choice.message?.content
        if (content.isNullOrBlank()) throw PipelineError.BadJson()
        return content
    }

    /**
     * 等待下一次重试。
     *
     * 服务端给了合法的 `Retry-After` 秒数时按它等待（`[0, MAX_RETRY_AFTER_MS]`），
     * 否则走 `min(2^retriesDone * 2, 30)` 秒 + 至多 250ms jitter 的指数退避。
     * `Retry-After` 已给出明确时间，因此不再叠加 jitter。
     */
    private suspend fun sleepBeforeRetry(retriesDone: Int, retryAfter: String?) {
        val retryAfterMs = retryAfterMillis(retryAfter)
        sleeper(retryAfterMs ?: backoffMs(retriesDone))
    }

    /** `min(2^retry * 2, 30)` 秒 + `[0, 250]ms` jitter。 */
    private fun backoffMs(retriesDone: Int): Long {
        val exponent = retriesDone.coerceIn(0, MAX_BACKOFF_EXPONENT)
        val baseSeconds = min(2.0.pow(exponent) * 2.0, MAX_BACKOFF_SECONDS)
        val jitterMs = (jitterSeconds().coerceAtLeast(0.0) * 1_000.0)
            .coerceAtMost(MAX_JITTER_MS)
        return ((baseSeconds * 1_000.0) + jitterMs).toLong()
    }

    /**
     * 解析 `Retry-After` 的秒数形式；缺失 / 非数字（如 HTTP-date）时返回 `null`，
     * 交给指数退避兜底。结果夹在 `[0, MAX_RETRY_AFTER_MS]`。
     */
    private fun retryAfterMillis(raw: String?): Long? {
        val seconds = raw?.trim()?.toLongOrNull() ?: return null
        // 先夹住秒数再乘，避免超大 / 负数在乘法时溢出。
        return seconds.coerceIn(0L, MAX_RETRY_AFTER_MS / 1_000L) * 1_000L
    }

    private fun mentionsResponseFormat(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("response_format") || lower.contains("response format")
    }

    private fun httpError(code: Int, body: String): PipelineError {
        PipelineError.fromHttp(code, PipelineError.Source.LLM)?.let { return it }

        val cause = IOException("HTTP $code: ${body.take(ERROR_BODY_PREVIEW)}")
        return when {
            // 部分网关用 403 表示 Key 无权限（§9.2 归入鉴权类）。
            code == 403 -> PipelineError.AuthLlm(cause)
            code in 400..499 -> PipelineError.ModelNotFound(cause)
            else -> PipelineError.Network(cause)
        }
    }

    private data class HttpResult(
        val code: Int,
        val retryAfter: String?,
        val body: String,
    )

    private companion object {
        const val CHAT_COMPLETIONS_PATH = "/chat/completions"
        const val ROLE_USER = "user"
        const val FINISH_REASON_LENGTH = "length"
        const val RETRY_AFTER_HEADER = "Retry-After"
        const val MAX_BACKOFF_SECONDS = 30.0
        const val MAX_BACKOFF_EXPONENT = 10
        const val MAX_JITTER_MS = 250.0
        const val MAX_RETRY_AFTER_MS = 60_000L
        const val ERROR_BODY_PREVIEW = 500

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun isRetryableStatus(code: Int): Boolean = code == 429 || code in 500..599
    }
}

private val DEFAULT_JSON: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}
