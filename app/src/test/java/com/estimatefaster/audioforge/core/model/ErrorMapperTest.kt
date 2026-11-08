package com.estimatefaster.audioforge.core.model

import com.estimatefaster.audioforge.core.audio.FfmpegException
import com.estimatefaster.audioforge.core.model.PipelineError.Source
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.MalformedInputException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2-16：异常 → [PipelineError] 的映射、§9.5 文案可达性与 HTTP 状态码委托。 */
class ErrorMapperTest {

    // ---------- §9.5 的 11 个错误码 ----------

    @Test
    fun all_eleven_codes_are_reachable_with_stable_non_blank_messages() {
        val errors = representativeErrors()
        assertEquals("应覆盖 §9.5 全部 11 个错误码", 11, errors.size)
        assertEquals(ALL_CODES, errors.keys)

        val firstPass = errors.mapValues { ErrorMapper.userMessage(it.value) }
        val secondPass = errors.mapValues { ErrorMapper.userMessage(it.value) }
        assertEquals("userMessage 应稳定可重复", firstPass, secondPass)

        val expected = mapOf(
            PipelineError.AUTH_LLM to "LLM Key 无效，请在设置中检查",
            PipelineError.AUTH_FISH to "Fish Key 无效，请在设置中检查",
            PipelineError.QUOTA to "额度不足或被限流，稍后再试",
            PipelineError.MODEL_NOT_FOUND to "模型不存在，请检查模型名",
            PipelineError.NETWORK to "网络不可用，已暂停，恢复后继续",
            PipelineError.TRUNCATED to "本章过长，输出被截断",
            PipelineError.BAD_JSON to "模型输出不是合法 JSON，已降级为旁白",
            PipelineError.TTS_FAILED to "第 3 段合成失败：连接超时",
            PipelineError.STORAGE to "存储空间不足，请清理后重试",
            PipelineError.FFMPEG to "音频拼接失败：FFmpeg 返回码 1",
            PipelineError.NO_SEGMENTS to "本章没有可拼接的片段",
        )
        assertEquals(expected, firstPass)
        for ((code, message) in firstPass) {
            assertTrue("$code 的 userMessage 不应为空", message.isNotBlank())
            assertEquals("$code 的 message 应等于 userMessage", message, errors.getValue(code).message)
        }
    }

    // ---------- 透传 ----------

    @Test
    fun pipeline_error_is_returned_as_the_same_instance() {
        val errors = listOf(
            PipelineError.AuthLlm(),
            PipelineError.Quota(),
            PipelineError.Network(),
            PipelineError.BadJson(),
            PipelineError.TtsFailed(2, "服务端 500", retryable = true),
            PipelineError.Ffmpeg("返回码 1", retryable = true),
            PipelineError.NoSegments(),
        )
        for (error in errors) {
            assertSame("${error.code} 应原样透传", error, ErrorMapper.map(error))
        }
    }

    @Test
    fun passthrough_keeps_segment_index_detail_and_retryable() {
        val error = PipelineError.TtsFailed(5, "连接超时", retryable = true)
        val mapped = ErrorMapper.map(error)
        assertSame(error, mapped)
        assertEquals(5, (mapped as PipelineError.TtsFailed).segmentIndex)
        assertEquals("连接超时", mapped.detail)
        assertTrue(mapped.retryable)
        assertEquals("第 5 段合成失败：连接超时", ErrorMapper.userMessage(error))
    }

    // ---------- IO / 网络 ----------

    @Test
    fun socket_timeout_unknown_host_and_io_exception_map_to_network_with_cause() {
        val causes = listOf<Throwable>(
            SocketTimeoutException("timeout"),
            UnknownHostException("api.example.com"),
            IOException("connection reset by peer"),
        )
        for (cause in causes) {
            val mapped = ErrorMapper.map(cause)
            assertEquals("${cause::class.java.simpleName} 应映射为 NETWORK", PipelineError.NETWORK, mapped.code)
            assertTrue(mapped.retryable)
            assertSame(cause, mapped.cause)
            assertEquals("网络不可用，已暂停，恢复后继续", mapped.userMessage)
            assertFalse(mapped.userMessage.contains(cause.message.orEmpty()))
        }
    }

    // ---------- FFmpeg ----------

    @Test
    fun ffmpeg_exception_maps_to_ffmpeg_and_puts_log_detail_in_message() {
        val cause = FfmpegException(
            returnCode = 1,
            log = "Invalid data found when processing input",
        )
        val mapped = ErrorMapper.map(cause)
        assertEquals(PipelineError.FFMPEG, mapped.code)
        assertFalse(mapped.retryable)
        assertSame(cause, mapped.cause)
        assertTrue(
            "detail 应进入文案：${mapped.userMessage}",
            mapped.userMessage.contains("Invalid data found when processing input"),
        )
        assertTrue(mapped.userMessage.startsWith("音频拼接失败："))
        assertTrue(mapped.userMessage.contains("FFmpeg 返回码 1"))
    }

    @Test
    fun ffmpeg_exception_with_blank_log_still_has_return_code() {
        val mapped = ErrorMapper.map(FfmpegException(returnCode = 7, log = "  \n\t "))
        assertEquals("音频拼接失败：FFmpeg 返回码 7", mapped.userMessage)
    }

    @Test
    fun ffmpeg_detail_is_capped_so_ui_is_not_flooded() {
        val mapped = ErrorMapper.map(FfmpegException(returnCode = 1, log = "x".repeat(500)))
        assertTrue("超长日志应被截断并加省略号", mapped.userMessage.endsWith("…"))
        assertTrue("文案不应超过 300 字符，实际 ${mapped.userMessage.length}", mapped.userMessage.length < 300)
    }

    // ---------- 解析 / 字符集 ----------

    @Test
    fun serialization_and_character_coding_exceptions_map_to_bad_json() {
        val causes = listOf<Throwable>(
            SerializationException("Unexpected JSON token"),
            MalformedInputException(2),
        )
        for (cause in causes) {
            val mapped = ErrorMapper.map(cause)
            assertEquals("${cause::class.java.simpleName} 应映射为 BAD_JSON", PipelineError.BAD_JSON, mapped.code)
            assertFalse(mapped.retryable)
            assertSame(cause, mapped.cause)
            assertEquals("模型输出不是合法 JSON，已降级为旁白", mapped.userMessage)
        }
    }

    // ---------- 存储 ----------

    @Test
    fun security_exception_maps_to_storage() {
        val cause = SecurityException("EACCES: permission denied")
        val mapped = ErrorMapper.map(cause)
        assertEquals(PipelineError.STORAGE, mapped.code)
        assertFalse(mapped.retryable)
        assertSame(cause, mapped.cause)
        assertEquals("存储空间不足，请清理后重试", mapped.userMessage)
    }

    // ---------- 兜底 ----------

    @Test
    fun unknown_exception_falls_back_to_network() {
        val causes = listOf<Throwable>(
            IllegalStateException("boom"),
            RuntimeException("unexpected"),
        )
        for (cause in causes) {
            val mapped = ErrorMapper.map(cause)
            assertEquals(PipelineError.NETWORK, mapped.code)
            assertTrue(mapped.retryable)
            assertSame(cause, mapped.cause)
            assertEquals("网络不可用，已暂停，恢复后继续", mapped.userMessage)
            assertFalse(mapped.userMessage.contains("boom"))
        }
    }

    // ---------- userMessage 便捷方法 ----------

    @Test
    fun user_message_helper_equals_map_user_message() {
        val causes = listOf<Throwable>(
            IOException("offline"),
            FfmpegException(1, "failed"),
            PipelineError.TtsFailed(2),
        )
        for (cause in causes) {
            assertEquals(ErrorMapper.map(cause).userMessage, ErrorMapper.userMessage(cause))
        }
    }

    // ---------- HTTP ----------

    @Test
    fun http_error_delegates_401_402_404_400_to_from_http() {
        assertEquals(
            PipelineError.AUTH_LLM,
            requireNotNull(ErrorMapper.httpError(401, Source.LLM)).code,
        )
        assertEquals(
            PipelineError.AUTH_FISH,
            requireNotNull(ErrorMapper.httpError(401, Source.FISH)).code,
        )
        assertEquals(
            PipelineError.QUOTA,
            requireNotNull(ErrorMapper.httpError(402, Source.LLM)).code,
        )
        assertEquals(
            PipelineError.QUOTA,
            requireNotNull(ErrorMapper.httpError(429, Source.FISH)).code,
        )
        assertEquals(
            PipelineError.MODEL_NOT_FOUND,
            requireNotNull(ErrorMapper.httpError(404, Source.LLM)).code,
        )
        assertEquals(
            PipelineError.MODEL_NOT_FOUND,
            requireNotNull(ErrorMapper.httpError(400, Source.FISH)).code,
        )

        assertEquals("LLM Key 无效，请在设置中检查", ErrorMapper.userMessage(ErrorMapper.httpError(401, Source.LLM)!!))
        assertEquals("Fish Key 无效，请在设置中检查", ErrorMapper.userMessage(ErrorMapper.httpError(401, Source.FISH)!!))
    }

    @Test
    fun http_error_returns_null_for_unmapped_statuses() {
        for (source in Source.entries) {
            for (status in listOf(0, 200, 201, 204, 301, 403, 418, 500, 502, 503)) {
                assertNull(
                    "status=$status source=$source 不应映射成 PipelineError",
                    ErrorMapper.httpError(status, source),
                )
            }
        }
    }

    // ---------- helpers ----------

    /**
     * 每个错误码选一个真实来源：能经 [ErrorMapper.httpError] 得到的走 HTTP，
     * 其余走 [ErrorMapper.map]，从而验证「11 个 code 都可达」。
     */
    private fun representativeErrors(): Map<String, PipelineError> = linkedMapOf(
        PipelineError.AUTH_LLM to requireNotNull(ErrorMapper.httpError(401, Source.LLM)),
        PipelineError.AUTH_FISH to requireNotNull(ErrorMapper.httpError(401, Source.FISH)),
        PipelineError.QUOTA to requireNotNull(ErrorMapper.httpError(402, Source.LLM)),
        PipelineError.MODEL_NOT_FOUND to requireNotNull(ErrorMapper.httpError(404, Source.LLM)),
        PipelineError.NETWORK to ErrorMapper.map(SocketTimeoutException("timeout")),
        PipelineError.TRUNCATED to ErrorMapper.map(PipelineError.Truncated()),
        PipelineError.BAD_JSON to ErrorMapper.map(SerializationException("bad json")),
        PipelineError.TTS_FAILED to ErrorMapper.map(PipelineError.TtsFailed(3, "连接超时")),
        PipelineError.STORAGE to ErrorMapper.map(SecurityException("denied")),
        PipelineError.FFMPEG to ErrorMapper.map(FfmpegException(1, "")),
        PipelineError.NO_SEGMENTS to ErrorMapper.map(PipelineError.NoSegments()),
    )

    private companion object {
        val ALL_CODES = setOf(
            PipelineError.AUTH_LLM,
            PipelineError.AUTH_FISH,
            PipelineError.QUOTA,
            PipelineError.MODEL_NOT_FOUND,
            PipelineError.NETWORK,
            PipelineError.TRUNCATED,
            PipelineError.BAD_JSON,
            PipelineError.TTS_FAILED,
            PipelineError.STORAGE,
            PipelineError.FFMPEG,
            PipelineError.NO_SEGMENTS,
        )
    }
}
