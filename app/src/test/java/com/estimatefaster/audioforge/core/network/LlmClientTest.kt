package com.estimatefaster.audioforge.core.network

import com.estimatefaster.audioforge.core.model.PipelineError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P2-07：`LlmClient` 的 MockWebServer 测试（执行计划 §9.2 / §9.6）。
 *
 * 覆盖：正常 JSON、截断、非法 JSON / 空 content、401/402/404/400 不重试、
 * 400 去掉 response_format 回退、429 指数退避后成功、5xx 重试耗尽、
 * `Retry-After`（429/503、上限 60s、非法值回退）、jitter 上限 250ms、
 * 超时 / 连接失败 → Network，以及请求体与 Authorization 字段。
 *
 * 所有退避等待都通过注入的 no-op sleeper 记录，不产生真实延迟；
 * jitter 注入 0.0，以便锁定 2s / 4s 的退避序列。
 */
@Suppress("DEPRECATION")
class LlmClientTest {

    private lateinit var server: MockWebServer

    private val delays = mutableListOf<Long>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        delays.clear()
    }

    @After
    fun tearDown() {
        server.close()
    }

    // ---------- 正常路径 ----------

    @Test
    fun chat_returns_content_and_sends_expected_request() = runBlocking {
        server.enqueue(jsonResponse(SUCCESS_RESPONSE))

        val content = newClient().chat(PROMPT)

        assertEquals("""{"ok":true}""", content)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/chat/completions", request.path)
        assertEquals("Bearer $API_KEY", request.getHeader("Authorization"))
        assertNotNull(request.getHeader("Content-Type"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))

        val body = request.jsonBody()
        assertEquals(MODEL, body.string("model"))
        assertEquals(0.0, body["temperature"]!!.jsonPrimitive.double, 0.0)

        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages.single().jsonObject.string("role"))
        assertEquals(PROMPT, messages.single().jsonObject.string("content"))

        val responseFormat = body["response_format"]!!.jsonObject
        assertEquals("json_object", responseFormat.string("type"))
    }

    @Test
    fun response_format_is_omitted_when_disabled() = runBlocking {
        server.enqueue(jsonResponse(SUCCESS_RESPONSE))

        newClient().chat(PROMPT, responseFormatJson = false)

        val body = server.takeRequest().jsonBody()
        assertFalse(body.containsKey("response_format"))
    }

    // ---------- 响应体错误 ----------

    @Test
    fun finish_reason_length_throws_truncated() {
        server.enqueue(jsonResponse(TRUNCATED_RESPONSE))

        val error = chatErrorOf(PipelineError.Truncated::class.java)

        assertEquals(PipelineError.TRUNCATED, error.code)
    }

    @Test
    fun malformed_json_throws_bad_json() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("this is not json"))

        val error = chatErrorOf(PipelineError.BadJson::class.java)

        assertEquals(PipelineError.BAD_JSON, error.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun blank_content_throws_bad_json() {
        server.enqueue(jsonResponse(BLANK_CONTENT_RESPONSE))

        val error = chatErrorOf(PipelineError.BadJson::class.java)

        assertEquals(PipelineError.BAD_JSON, error.code)
    }

    // ---------- 不重试的 HTTP 错误 ----------

    @Test
    fun unauthorized_maps_to_auth_llm_without_retry() {
        server.enqueue(errorResponse(401, "invalid api key"))
        server.enqueue(jsonResponse(SUCCESS_RESPONSE))

        val error = chatErrorOf(PipelineError.AuthLlm::class.java)

        assertEquals(PipelineError.AUTH_LLM, error.code)
        assertEquals(1, server.requestCount)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun payment_required_maps_to_quota_without_retry() {
        server.enqueue(errorResponse(402, "insufficient quota"))
        server.enqueue(jsonResponse(SUCCESS_RESPONSE))

        val error = chatErrorOf(PipelineError.Quota::class.java)

        assertEquals(PipelineError.QUOTA, error.code)
        assertEquals(1, server.requestCount)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun not_found_maps_to_model_not_found_without_retry() {
        server.enqueue(errorResponse(404, "model not found"))
        server.enqueue(jsonResponse(SUCCESS_RESPONSE))

        val error = chatErrorOf(PipelineError.ModelNotFound::class.java)

        assertEquals(PipelineError.MODEL_NOT_FOUND, error.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun plain_bad_request_does_not_retry() {
        server.enqueue(errorResponse(400, "invalid request body"))
        server.enqueue(jsonResponse(SUCCESS_RESPONSE))

        val error = chatErrorOf(PipelineError.ModelNotFound::class.java)

        assertEquals(PipelineError.MODEL_NOT_FOUND, error.code)
        assertEquals(1, server.requestCount)
        assertTrue(delays.isEmpty())
    }

    // ---------- 400 response_format 回退 ----------

    @Test
    fun bad_request_mentioning_response_format_retries_once_without_it() = runBlocking {
        server.enqueue(errorResponse(400, "Unknown parameter: response_format"))
        server.enqueue(jsonResponse(SUCCESS_RESPONSE))

        val content = newClient().chat(PROMPT)

        assertEquals("""{"ok":true}""", content)
        assertEquals(2, server.requestCount)
        val first = server.takeRequest().jsonBody()
        val second = server.takeRequest().jsonBody()
        assertTrue(first.containsKey("response_format"))
        assertFalse(second.containsKey("response_format"))
        assertTrue("回退不应计入普通退避等待", delays.isEmpty())
    }

    @Test
    fun response_format_fallback_can_be_disabled() {
        server.enqueue(errorResponse(400, "Unknown parameter: response_format"))
        server.enqueue(jsonResponse(SUCCESS_RESPONSE))

        val error = chatErrorOf(
            PipelineError.ModelNotFound::class.java,
            client = newClient(responseFormatFallback = false),
        )

        assertEquals(PipelineError.MODEL_NOT_FOUND, error.code)
        assertEquals(1, server.requestCount)
    }

    // ---------- 重试与退避 ----------

    @Test
    fun retries_429_with_exponential_backoff_then_succeeds() = runBlocking {
        server.enqueue(errorResponse(429, "rate limited"))
        server.enqueue(errorResponse(429, "rate limited"))
        server.enqueue(jsonResponse(SUCCESS_RESPONSE))
        server.enqueue(jsonResponse(SUCCESS_RESPONSE))

        val content = newClient().chat(PROMPT)

        assertEquals("""{"ok":true}""", content)
        assertEquals(3, server.requestCount)
        assertEquals(listOf(2_000L, 4_000L), delays)
    }

    @Test
    fun exhausted_5xx_retries_map_to_network() {
        repeat(5) { server.enqueue(errorResponse(500, "boom")) }

        val error = chatErrorOf(
            PipelineError.Network::class.java,
            client = newClient(config = testConfig(maxRetries = 2)),
        )

        assertEquals(PipelineError.NETWORK, error.code)
        assertEquals(3, server.requestCount)
        assertEquals(listOf(2_000L, 4_000L), delays)
    }

    @Test
    fun retry_after_seconds_are_honored_on_429() = runBlocking {
        server.enqueue(errorResponse(429, "rate limited", retryAfter = "5"))
        server.enqueue(jsonResponse(SUCCESS_RESPONSE))

        val content = newClient().chat(PROMPT)

        assertEquals("""{"ok":true}""", content)
        assertEquals(2, server.requestCount)
        assertEquals("必须按 Retry-After 的秒数等待", listOf(5_000L), delays)
    }

    @Test
    fun retry_after_seconds_are_honored_on_503() = runBlocking {
        server.enqueue(errorResponse(503, "service unavailable", retryAfter = "1"))
        server.enqueue(jsonResponse(SUCCESS_RESPONSE))

        val content = newClient().chat(PROMPT)

        assertEquals("""{"ok":true}""", content)
        assertEquals(2, server.requestCount)
        assertEquals("Retry-After 优先于指数退避（首轮本应 2s）", listOf(1_000L), delays)
    }

    @Test
    fun retry_after_is_capped_at_sixty_seconds() = runBlocking {
        server.enqueue(errorResponse(429, "rate limited", retryAfter = "3600"))
        server.enqueue(jsonResponse(SUCCESS_RESPONSE))

        newClient().chat(PROMPT)

        assertEquals("服务端给再久也只等 60s", listOf(60_000L), delays)
    }

    @Test
    fun invalid_retry_after_falls_back_to_exponential_backoff() = runBlocking {
        server.enqueue(errorResponse(429, "rate limited", retryAfter = "later"))
        server.enqueue(jsonResponse(SUCCESS_RESPONSE))

        newClient().chat(PROMPT)

        assertEquals("非数字 Retry-After 应回退到 2s 指数退避", listOf(2_000L), delays)
    }

    @Test
    fun jitter_is_capped_at_250ms() = runBlocking {
        server.enqueue(errorResponse(429, "rate limited"))
        server.enqueue(jsonResponse(SUCCESS_RESPONSE))

        newClient(jitterSeconds = { 5.0 }).chat(PROMPT)

        assertEquals("jitter 上限 250ms：2s + 0.25s", listOf(2_250L), delays)
    }

    @Test
    fun read_timeout_maps_to_network() {
        repeat(2) {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        }

        val error = chatErrorOf(
            PipelineError.Network::class.java,
            client = newClient(config = testConfig(maxRetries = 0, readTimeoutMs = 150)),
        )

        assertEquals(PipelineError.NETWORK, error.code)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun connection_failure_maps_to_network() {
        val deadServer = MockWebServer()
        deadServer.start()
        val deadUrl = deadServer.url("/").toString()
        deadServer.close()

        val deadConfig = testConfig(maxRetries = 0).copy(baseUrl = deadUrl)
        val error = chatErrorOf(PipelineError.Network::class.java, client = newClient(deadConfig))

        assertEquals(PipelineError.NETWORK, error.code)
    }

    // ---------- helpers ----------

    private fun testConfig(
        maxRetries: Int = 3,
        readTimeoutMs: Int = 2_000,
    ): LlmConfig = LlmConfig(
        baseUrl = server.url("/").toString(),
        model = MODEL,
        apiKey = API_KEY,
        connectTimeoutMs = 1_000,
        readTimeoutMs = readTimeoutMs,
        maxRetries = maxRetries,
    )

    private fun newClient(
        config: LlmConfig = testConfig(),
        responseFormatFallback: Boolean = true,
        jitterSeconds: () -> Double = { 0.0 },
    ): LlmClient = LlmClient(
        config = config,
        responseFormatFallback = responseFormatFallback,
        sleeper = { delays += it },
        jitterSeconds = jitterSeconds,
    )

    private fun <T : PipelineError> chatErrorOf(
        type: Class<T>,
        client: LlmClient = newClient(),
    ): T = assertThrows(type) {
        runBlocking { client.chat(PROMPT) }
    }

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun errorResponse(
        code: Int,
        message: String,
        retryAfter: String? = null,
    ): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .apply { if (retryAfter != null) addHeader("Retry-After", retryAfter) }
        .setBody("""{"error":{"message":"$message"}}""")

    private fun RecordedRequest.jsonBody(): JsonObject =
        Json.parseToJsonElement(body.readUtf8()).jsonObject

    private fun JsonObject.string(key: String): String =
        this[key]!!.jsonPrimitive.content

    private companion object {
        const val MODEL = "qwen3.7-flash"
        const val API_KEY = "sk-test-not-a-real-key"
        const val PROMPT = "请分析这一章"

        val SUCCESS_RESPONSE = """
            {"choices":[{"message":{"role":"assistant","content":"{\"ok\":true}"},"finish_reason":"stop"}]}
        """.trimIndent()

        val TRUNCATED_RESPONSE = """
            {"choices":[{"message":{"role":"assistant","content":"partial"},"finish_reason":"length"}]}
        """.trimIndent()

        val BLANK_CONTENT_RESPONSE = """
            {"choices":[{"message":{"role":"assistant","content":"   "},"finish_reason":"stop"}]}
        """.trimIndent()
    }

    @Test
    fun config_to_string_masks_api_key() {
        val config = LlmConfig(baseUrl = "https://example.test", model = MODEL, apiKey = API_KEY)
        val text = config.toString()
        assertFalse("toString 不应包含明文 Key", text.contains(API_KEY))
        assertTrue("应保留可识别的前后缀", text.contains("sk-***"))
    }
}
