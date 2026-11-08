package com.estimatefaster.audioforge.core.tts

import com.estimatefaster.audioforge.TestFixtures
import com.estimatefaster.audioforge.core.model.PipelineError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P2-08：Fish TTS 客户端（执行计划 §9.3、§9.6）。
 *
 * 用 MockWebServer 覆盖请求体、二进制响应、错误映射与退避重试；
 * 所有重试都注入记录型 sleeper（不真实等待），因此测试是秒级的。
 */
class FishTtsClientTest {

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
        server.shutdown()
    }

    // ---------- 正常路径 ----------

    @Test
    fun success_returns_mp3_bytes_and_sends_spec_json() = runTest {
        server.enqueue(audioResponse(MP3_BYTES))

        val bytes = client().synthesize(text = "[happy] 你好。", voiceId = "voice-1")

        assertArrayEquals(MP3_BYTES, bytes)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/tts", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertEquals("s2.1-pro-free", request.getHeader("model"))
        assertTrue(
            "Content-Type 必须是 JSON：${request.getHeader("Content-Type")}",
            request.getHeader("Content-Type")!!.startsWith("application/json"),
        )

        val body = request.jsonBody()
        assertEquals("[happy] 你好。", body.string("text"))
        assertEquals("voice-1", body.string("reference_id"))
        assertEquals("mp3", body.string("format"))
        assertEquals(64, body.getValue("mp3_bitrate").jsonPrimitive.int)
        assertTrue(body.getValue("normalize").jsonPrimitive.boolean)
        assertFalse("speed=null 时不应发送 prosody", body.containsKey("prosody"))
        assertEquals("不应有重试", 1, server.requestCount)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun speed_sets_prosody_speed() = runTest {
        server.enqueue(audioResponse())

        client().synthesize(text = "你好。", voiceId = "voice-1", speed = 1.15)

        val body = server.takeRequest().jsonBody()
        assertTrue(body.containsKey("prosody"))
        assertEquals(
            1.15,
            body.getValue("prosody").jsonObject.getValue("speed").jsonPrimitive.double,
            0.0,
        )
    }

    @Test
    fun null_speed_omits_prosody() = runTest {
        server.enqueue(audioResponse())

        client().synthesize(text = "你好。", voiceId = "voice-1", speed = null)

        val body = server.takeRequest().jsonBody()
        assertFalse(body.containsKey("prosody"))
    }

    // ---------- 不可重试错误 ----------

    @Test
    fun unauthorized_401_maps_to_auth_fish_without_retry() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"unauthorized\"}"))

        val error = expectError { client().synthesize("你好。", "voice-1") }

        assertTrue(error is PipelineError.AuthFish)
        assertEquals(PipelineError.AUTH_FISH, error.code)
        assertEquals("401 不应重试", 1, server.requestCount)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun payment_required_402_maps_to_quota_without_retry() = runTest {
        server.enqueue(MockResponse().setResponseCode(402).setBody("{\"error\":\"quota\"}"))

        val error = expectError { client().synthesize("你好。", "voice-1") }

        assertTrue(error is PipelineError.Quota)
        assertEquals(PipelineError.QUOTA, error.code)
        assertEquals("402 不应重试", 1, server.requestCount)
    }

    @Test
    fun bad_request_400_maps_to_tts_failed_without_retry() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"bad text\"}"))

        val error = expectError { client().synthesize(" ", "voice-1") }

        assertTrue(error is PipelineError.TtsFailed)
        assertEquals(PipelineError.TTS_FAILED, error.code)
        assertTrue("应带上 HTTP 400：${error.userMessage}", error.userMessage.contains("HTTP 400"))
        assertEquals("400 不应重试", 1, server.requestCount)
    }

    // ---------- 可重试错误 ----------

    @Test
    fun rate_limit_429_then_success_retries_and_honors_retry_after() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429).addHeader("Retry-After", "2"),
        )
        server.enqueue(audioResponse())

        val bytes = client().synthesize(text = "你好。", voiceId = "voice-1")

        assertArrayEquals(MP3_BYTES, bytes)
        assertEquals("429 后应重试一次", 2, server.requestCount)
        assertEquals("必须按 Retry-After 等待", listOf(2_000L), delays)
    }

    @Test
    fun rate_limit_exhausted_maps_to_quota() = runTest {
        repeat(5) {
            server.enqueue(MockResponse().setResponseCode(429))
        }

        val error = expectError { client().synthesize("你好。", "voice-1") }

        assertEquals(PipelineError.QUOTA, error.code)
        assertEquals("1 + maxRetries 次后停止", 5, server.requestCount)
        assertEquals("每次重试都要等待", 4, delays.size)
    }

    @Test
    fun service_unavailable_503_retries_then_maps_to_network() = runTest {
        repeat(5) {
            server.enqueue(MockResponse().setResponseCode(503))
        }

        val error = expectError { client().synthesize("你好。", "voice-1") }

        assertEquals(PipelineError.NETWORK, error.code)
        assertEquals("1 + maxRetries 次后停止", 5, server.requestCount)
        assertEquals(4, delays.size)
        // 指数退避（2s 起、30s 封顶）都落在合法区间。
        assertTrue("4 次退避应落在 2s–30.25s，实际 $delays", delays.all { it in 2_000L..30_250L })
    }

    // ---------- 协议错误 ----------

    @Test
    fun empty_body_is_protocol_error() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer()),
        )

        val error = expectError { client().synthesize("你好。", "voice-1") }

        assertTrue(error is PipelineError.TtsFailed)
        assertEquals(PipelineError.TTS_FAILED, error.code)
        assertTrue("应说明响应为空：${error.userMessage}", error.userMessage.contains("响应为空"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun non_audio_body_is_protocol_error() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/html")
                .setBody("<html>not an audio file</html>"),
        )

        val error = expectError { client().synthesize("你好。", "voice-1") }

        assertTrue(error is PipelineError.TtsFailed)
        assertEquals(PipelineError.TTS_FAILED, error.code)
        assertTrue(
            "应说明不是音频：${error.userMessage}",
            error.userMessage.contains("不是音频"),
        )
        assertEquals(1, server.requestCount)
    }

    // ---------- helpers ----------

    private fun client(maxRetries: Int = 4): FishTtsClient = FishTtsClient(
        config = FishTtsConfig(
            baseUrl = server.url("/").toString(),
            apiKey = "test-key",
            maxRetries = maxRetries,
        ),
        sleeper = { delays += it },
    )

    private fun audioResponse(bytes: ByteArray = MP3_BYTES): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "audio/mpeg")
            .setBody(Buffer().write(bytes))

    private suspend fun expectError(block: suspend () -> Unit): PipelineError {
        try {
            block()
        } catch (error: PipelineError) {
            return error
        }
        throw AssertionError("应当抛出 PipelineError")
    }

    private fun RecordedRequest.jsonBody(): JsonObject =
        TestFixtures.json.parseToJsonElement(body.readUtf8()).jsonObject

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private companion object {
        /** 以 ID3v2 头开头的伪 mp3，字节逐位比较，确保客户端不做文本转换。 */
        val MP3_BYTES: ByteArray = byteArrayOf(
            0x49, 0x44, 0x33, // "ID3"
            0x04, 0x00, 0x00, // version 2.4.0
            0x00, 0x00, 0x00, 0x00, // flags + size
            0x7F, 0x00, 0x00, 0x01, // payload
            0x02, 0x03, 0x04, 0x05,
        )
    }
}
