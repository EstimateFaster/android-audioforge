package com.estimatefaster.audioforge.core.model

import com.estimatefaster.audioforge.core.model.PipelineError.Source
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-11：执行计划 §9.5 的错误码、重试语义、中文文案与 HTTP 映射。 */
class PipelineErrorTest {

    // ---------- code / retryable / userMessage ----------

    @Test
    fun auth_errors_are_terminal() {
        assertError(PipelineError.AuthLlm(), "AUTH_LLM", false, "LLM Key 无效，请在设置中检查")
        assertError(PipelineError.AuthFish(), "AUTH_FISH", false, "Fish Key 无效，请在设置中检查")
    }

    @Test
    fun quota_and_network_are_retryable() {
        assertError(PipelineError.Quota(), "QUOTA", true, "额度不足或被限流，稍后再试")
        assertError(PipelineError.Network(), "NETWORK", true, "网络不可用，已暂停，恢复后继续")
    }

    @Test
    fun model_truncated_bad_json_storage_and_no_segments_are_terminal() {
        assertError(
            PipelineError.ModelNotFound(),
            "MODEL_NOT_FOUND",
            false,
            "模型不存在，请检查模型名",
        )
        assertError(PipelineError.Truncated(), "TRUNCATED", false, "本章过长，输出被截断")
        assertError(
            PipelineError.BadJson(),
            "BAD_JSON",
            false,
            "模型输出不是合法 JSON，已降级为旁白",
        )
        assertError(PipelineError.Storage(), "STORAGE", false, "存储空间不足，请清理后重试")
        assertError(PipelineError.NoSegments(), "NO_SEGMENTS", false, "本章没有可拼接的片段")
    }

    @Test
    fun tts_failed_and_ffmpeg_are_not_retryable_by_default() {
        assertError(PipelineError.TtsFailed(1), "TTS_FAILED", false, "第 1 段合成失败")
        assertError(PipelineError.Ffmpeg(), "FFMPEG", false, "音频拼接失败")
    }

    @Test
    fun tts_failed_and_ffmpeg_can_opt_into_retry() {
        assertTrue(PipelineError.TtsFailed(1, retryable = true).retryable)
        assertTrue(PipelineError.Ffmpeg(retryable = true).retryable)
    }

    @Test
    fun code_set_covers_all_eleven_codes_without_duplicates() {
        val expected = setOf(
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
        assertEquals(11, expected.size)

        val errors = listOf(
            PipelineError.AuthLlm(),
            PipelineError.AuthFish(),
            PipelineError.Quota(),
            PipelineError.ModelNotFound(),
            PipelineError.Network(),
            PipelineError.Truncated(),
            PipelineError.BadJson(),
            PipelineError.TtsFailed(1),
            PipelineError.Storage(),
            PipelineError.Ffmpeg(),
            PipelineError.NoSegments(),
        )
        assertEquals(expected, errors.map { it.code }.toSet())
        assertEquals(errors.size, errors.map { it.code }.toSet().size)
    }

    // ---------- detail 拼接 ----------

    @Test
    fun tts_failed_appends_detail_after_colon() {
        assertEquals(
            "第 3 段合成失败：连接超时",
            PipelineError.TtsFailed(3, "连接超时").userMessage,
        )
        assertEquals(
            "第 3 段合成失败：连接超时",
            PipelineError.TtsFailed(3, "  连接超时  ").userMessage,
        )
    }

    @Test
    fun tts_failed_omits_colon_for_blank_detail() {
        assertEquals("第 3 段合成失败", PipelineError.TtsFailed(3).userMessage)
        assertEquals("第 3 段合成失败", PipelineError.TtsFailed(3, "").userMessage)
        assertEquals("第 3 段合成失败", PipelineError.TtsFailed(3, "   ").userMessage)
    }

    @Test
    fun ffmpeg_appends_detail_after_colon() {
        assertEquals(
            "音频拼接失败：FFmpeg 返回码 1",
            PipelineError.Ffmpeg("FFmpeg 返回码 1").userMessage,
        )
        assertEquals(
            "音频拼接失败：FFmpeg 返回码 1",
            PipelineError.Ffmpeg("  FFmpeg 返回码 1  ").userMessage,
        )
    }

    @Test
    fun ffmpeg_omits_colon_for_blank_detail() {
        assertEquals("音频拼接失败", PipelineError.Ffmpeg().userMessage)
        assertEquals("音频拼接失败", PipelineError.Ffmpeg("").userMessage)
        assertEquals("音频拼接失败", PipelineError.Ffmpeg("   ").userMessage)
    }

    // ---------- 异常可读性 / cause ----------

    @Test
    fun exception_message_equals_user_message() {
        val errors = listOf(
            PipelineError.AuthLlm(),
            PipelineError.AuthFish(),
            PipelineError.Quota(),
            PipelineError.ModelNotFound(),
            PipelineError.Network(),
            PipelineError.Truncated(),
            PipelineError.BadJson(),
            PipelineError.TtsFailed(2, "服务端 500"),
            PipelineError.Storage(),
            PipelineError.Ffmpeg("返回码 1"),
            PipelineError.NoSegments(),
        )
        for (error in errors) {
            assertEquals(error.userMessage, error.message)
            assertFalse("userMessage 不应为空", error.userMessage.isBlank())
        }
    }

    @Test
    fun cause_is_preserved_but_not_leaked_into_user_message() {
        val cause = IOException("connection reset by peer")
        val error = PipelineError.Network(cause)
        assertSame(cause, error.cause)
        assertEquals("网络不可用，已暂停，恢复后继续", error.message)
        assertFalse(error.userMessage.contains("connection reset"))
    }

    // ---------- HTTP 映射 ----------

    @Test
    fun fromHttp_401_maps_to_auth_error_by_source() {
        assertError(
            requireNotNull(PipelineError.fromHttp(401, Source.LLM)),
            "AUTH_LLM",
            false,
            "LLM Key 无效，请在设置中检查",
        )
        assertError(
            requireNotNull(PipelineError.fromHttp(401, Source.FISH)),
            "AUTH_FISH",
            false,
            "Fish Key 无效，请在设置中检查",
        )
    }

    @Test
    fun fromHttp_402_and_429_map_to_quota_for_both_sources() {
        for (source in Source.entries) {
            for (status in listOf(402, 429)) {
                val error = requireNotNull(PipelineError.fromHttp(status, source)) {
                    "status=$status source=$source 应有映射"
                }
                assertError(error, "QUOTA", true, "额度不足或被限流，稍后再试")
            }
        }
    }

    @Test
    fun fromHttp_404_and_400_map_to_model_not_found_for_both_sources() {
        for (source in Source.entries) {
            for (status in listOf(404, 400)) {
                val error = requireNotNull(PipelineError.fromHttp(status, source)) {
                    "status=$status source=$source 应有映射"
                }
                assertError(error, "MODEL_NOT_FOUND", false, "模型不存在，请检查模型名")
            }
        }
    }

    @Test
    fun fromHttp_other_statuses_return_null() {
        for (source in Source.entries) {
            for (status in listOf(0, 200, 201, 204, 301, 403, 418, 500, 502, 503)) {
                assertNull(
                    "status=$status source=$source 不应映射成终止性 PipelineError",
                    PipelineError.fromHttp(status, source),
                )
            }
        }
    }

    // ---------- helper ----------

    private fun assertError(
        error: PipelineError,
        code: String,
        retryable: Boolean,
        userMessage: String,
    ) {
        assertEquals(code, error.code)
        if (retryable) {
            assertTrue("$code 应可重试", error.retryable)
        } else {
            assertFalse("$code 不应重试", error.retryable)
        }
        assertEquals(userMessage, error.userMessage)
        assertEquals("Exception.message 必须等于 userMessage", userMessage, error.message)
    }
}
