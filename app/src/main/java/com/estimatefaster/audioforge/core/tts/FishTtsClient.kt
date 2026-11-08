package com.estimatefaster.audioforge.core.tts

import com.estimatefaster.audioforge.core.model.PipelineError
import com.estimatefaster.audioforge.core.network.HttpClients
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Fish Audio TTS 客户端配置（执行计划 §9.3）。
 *
 * @param baseUrl Fish API 的根地址，默认官方地址；端点固定拼 `/v1/tts`；
 * @param model TTS 模型名，作为 `model` 请求头发送；
 * @param apiKey Fish Audio API Key，只放 `Authorization: Bearer`，不落日志；
 * @param connectTimeoutMs 建连超时，§9.3 规定 10s；
 * @param readTimeoutMs 读超时，§9.3 规定 120s；
 * @param callTimeoutMs 单次调用总超时，§9.3 规定 150s；
 * @param maxRetries 首次请求失败后的最大重试次数（总请求数 = 1 + maxRetries）。
 */
data class FishTtsConfig(
    val baseUrl: String = "https://api.fish.audio",
    val model: String = "s2.1-pro-free",
    val apiKey: String,
    val connectTimeoutMs: Int = 10_000,
    val readTimeoutMs: Int = 120_000,
    val callTimeoutMs: Int = 150_000,
    val maxRetries: Int = 4,
) {
    /** 不要让日志 / 崩溃报告带出 Key。 */
    override fun toString(): String =
        "FishTtsConfig(baseUrl=$baseUrl, model=$model, apiKey=***, " +
            "connectTimeoutMs=$connectTimeoutMs, readTimeoutMs=$readTimeoutMs, " +
            "callTimeoutMs=$callTimeoutMs, maxRetries=$maxRetries)"
}

/**
 * Fish Audio TTS 客户端（执行计划 §9.3、§15 P2-08）。
 *
 * 只负责「文本 → mp3 字节」这一段传输：
 * - 文本情绪前缀由调用方通过 [com.estimatefaster.audioforge.core.model.Emotion.applyTo]
 *   加好，本客户端原样发送、不重复加前缀；
 * - 不写文件、不做后处理、不碰缓存，这些是 P2-09 `TtsSynthesizer` 的职责；
 * - 失败统一抛 [PipelineError] 子类，调用方按 `retryable` 决定是否继续。
 *
 * 请求体固定为：
 * ```json
 * {"text":"...","reference_id":"...","format":"mp3","mp3_bitrate":64,"normalize":true}
 * ```
 * `prosody` 只在 [synthesize] 的 `speed` 非空时出现，形如 `{"speed":1.15}`。
 *
 * 错误 / 重试映射（§9.3、§9.5）：
 * | HTTP | 行为 |
 * |---|---|
 * | 401 | 立即 [PipelineError.AuthFish]，不重试 |
 * | 402 | 立即 [PipelineError.Quota]，不重试 |
 * | 400 | 立即 [PipelineError.TtsFailed]，不重试 |
 * | 404 | 立即 [PipelineError.ModelNotFound]，不重试 |
 * | 429 | 退避重试；尊重 `Retry-After`；耗尽后 [PipelineError.Quota] |
 * | 5xx | 退避重试；耗尽后 [PipelineError.Network] |
 * | IO / 超时 | 退避重试；耗尽后 [PipelineError.Network] |
 * | 其它 4xx | [PipelineError.TtsFailed]，不重试 |
 * | 2xx 空 body / 非音频 | [PipelineError.TtsFailed]（协议错误），不重试 |
 *
 * 重试间隔：`min(2^retry * 2, 30)` 秒 + 0–250ms jitter；服务端给了
 * `Retry-After`（秒数或 HTTP-date）时优先用它，上限 [MAX_RETRY_AFTER_MS]。
 *
 * 取消：协程取消时通过 `call.cancel()` 中断在途请求；[delay]/[sleeper]
 * 退避期间取消直接抛 `CancellationException`。
 *
 * @param httpClient 传输层客户端；默认取 [HttpClients.defaultTts]，与 LLM 共用
 *   连接池 / Dispatcher，配置改过超时时自动派生；测试可注入 MockWebServer 客户端。
 * @param sleeper 退避等待函数，默认 [delay]；测试注入 no-op 以避免真实等待。
 */
class FishTtsClient(
    private val config: FishTtsConfig,
    private val httpClient: OkHttpClient = HttpClients.defaultTts(
        connectTimeoutMs = config.connectTimeoutMs.toLong(),
        readTimeoutMs = config.readTimeoutMs.toLong(),
        callTimeoutMs = config.callTimeoutMs.toLong(),
    ),
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {

    private val endpoint: String = config.baseUrl.trimEnd('/') + ENDPOINT_PATH

    /**
     * 合成一段语音，返回 mp3 原始字节。
     *
     * @param text 已按情绪加好前缀的文本（Neutral 就是原文），本客户端不再修改；
     * @param voiceId Fish Audio 音色 id，落到请求体 `reference_id`；
     * @param speed 语速倍数；`null` 表示不发 `prosody`（沿用音色默认语速）。
     * @throws PipelineError 认证 / 额度 / 协议 / 网络失败；`retryable` 见类注释。
     */
    suspend fun synthesize(text: String, voiceId: String, speed: Double? = null): ByteArray {
        val request = buildRequest(text = text, voiceId = voiceId, speed = speed)

        var lastStatus: Int? = null
        var lastIo: IOException? = null
        var nextDelayMs: Long? = null

        for (attempt in 0..config.maxRetries) {
            if (attempt > 0) {
                sleeper(nextDelayMs ?: backoffMs(attempt - 1))
                nextDelayMs = null
            }

            val response = try {
                execute(request)
            } catch (io: IOException) {
                // 连接失败 / 读超时 / callTimeout：可重试，耗尽后归入 Network。
                lastIo = io
                lastStatus = null
                continue
            }

            lastIo = null
            lastStatus = response.code
            when {
                response.code in 200..299 -> return audioBytes(response)
                response.code == 401 -> throw PipelineError.AuthFish()
                response.code == 402 -> throw PipelineError.Quota()
                response.code == 400 -> throw ttsFailed("HTTP 400 请求被拒绝")
                response.code == 404 -> throw PipelineError.ModelNotFound()
                response.code == 429 -> nextDelayMs = retryAfterMillis(response.retryAfter)
                response.code in 500..599 -> nextDelayMs = retryAfterMillis(response.retryAfter)
                else -> throw ttsFailed("HTTP ${response.code}")
            }
        }

        // 重试耗尽：429 视为额度 / 限流，其余（5xx、IO、超时）视为网络不可用。
        val cause = lastIo
            ?: lastStatus?.let { IOException("Fish TTS 重试耗尽，最后状态码 $it") }
        if (lastStatus == 429) {
            throw PipelineError.Quota(cause)
        }
        throw PipelineError.Network(cause)
    }

    private fun buildRequest(text: String, voiceId: String, speed: Double?): Request {
        val payload = buildJsonObject {
            put("text", text)
            put("reference_id", voiceId)
            put("format", "mp3")
            put("mp3_bitrate", 64)
            put("normalize", true)
            if (speed != null) {
                put(
                    "prosody",
                    buildJsonObject {
                        put("speed", speed)
                    },
                )
            }
        }
        val body: RequestBody = payload.toString().toByteArray(Charsets.UTF_8)
            .toRequestBody(JSON_MEDIA_TYPE)

        return Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .header("model", config.model)
            .post(body)
            .build()
    }

    /**
     * 在协程里执行一次 OkHttp 调用：body 在 OkHttp 线程读完再 resume，
     * 避免把磁盘/网络读取带回调用方调度器（可能是主线程）。
     */
    private suspend fun execute(request: Request): RawResponse =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val raw = try {
                            response.use {
                                val body = it.body
                                RawResponse(
                                    code = it.code,
                                    contentType = body.contentType()?.toString(),
                                    retryAfter = it.header("Retry-After"),
                                    bytes = body.bytes(),
                                )
                            }
                        } catch (io: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(io)
                            return
                        }
                        if (continuation.isActive) continuation.resume(raw)
                    }
                },
            )
        }

    /** 2xx 响应校验：空 body 或明确非音频的 Content-Type 都算协议错误。 */
    private fun audioBytes(response: RawResponse): ByteArray {
        if (response.bytes.isEmpty()) {
            throw ttsFailed("响应为空（HTTP ${response.code}）")
        }
        if (!isAudioContentType(response.contentType)) {
            throw ttsFailed("响应不是音频（Content-Type=${response.contentType ?: "未知"}）")
        }
        return response.bytes
    }

    private fun isAudioContentType(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        // 自建代理可能不带 Content-Type；此时不误杀，交给上层播放器校验。
        if (type.isEmpty()) return true
        return type.startsWith("audio/") ||
            type == "application/octet-stream" ||
            type == "binary/octet-stream" ||
            type == "application/mp3" ||
            type == "application/mpeg"
    }

    /**
     * 解析 `Retry-After`：支持秒数与 HTTP-date；无法解析时返回 `null`，
     * 由指数退避兜底。结果夹在 `[0, MAX_RETRY_AFTER_MS]`。
     */
    private fun retryAfterMillis(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val millis = value.toLongOrNull()?.times(1_000L) ?: run {
            val instant = runCatching {
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            }.getOrNull() ?: return null
            Duration.between(Instant.now(), instant).toMillis()
        }
        return millis.coerceIn(0L, MAX_RETRY_AFTER_MS)
    }

    /** `min(2^retry * 2, 30)` 秒 + jitter；retry 从 0 开始（第一次重试 2s）。 */
    private fun backoffMs(retry: Int): Long {
        val exponent = retry.coerceIn(0, MAX_BACKOFF_EXPONENT)
        val base = minOf((1L shl exponent) * 2_000L, MAX_BACKOFF_MS)
        return base + Random.nextLong(0L, JITTER_MAX_MS + 1L)
    }

    private fun ttsFailed(detail: String, cause: Throwable? = null): PipelineError.TtsFailed =
        // 本层不知道片段序号（P2-09 的 TtsSynthesizer 才知道），用 0 表示「未知段」；
        // 合成器捕获后可用真实段号重建，避免这里编造一个错误的段号。
        PipelineError.TtsFailed(segmentIndex = UNKNOWN_SEGMENT_INDEX, detail = detail, cause = cause)

    private data class RawResponse(
        val code: Int,
        val contentType: String?,
        val retryAfter: String?,
        val bytes: ByteArray,
    )

    private companion object {
        const val ENDPOINT_PATH = "/v1/tts"

        /** `TtsFailed.segmentIndex` 的「未知段」哨兵值。 */
        const val UNKNOWN_SEGMENT_INDEX = 0

        const val MAX_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_EXPONENT = 10
        const val JITTER_MAX_MS = 250L
        const val MAX_RETRY_AFTER_MS = 60_000L

        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
