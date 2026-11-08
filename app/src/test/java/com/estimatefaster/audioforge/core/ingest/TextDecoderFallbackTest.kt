package com.estimatefaster.audioforge.core.ingest

import org.junit.Assert.assertEquals
import org.junit.Test

/** 注入 fake detector，避免测试依赖 ICU 的具体识别结果。 */
class TextDecoderFallbackTest {

    private val chinese = "第一章 风起\n\n他抬头看向天空，云层很厚，像是要下雨了。"

    @Test
    fun uses_detected_charset_when_utf8_fails() {
        val bytes = chinese.toByteArray(java.nio.charset.Charset.forName("GBK"))
        val decoder = TextDecoder(detector = { java.nio.charset.Charset.forName("GB18030") })
        val result = decoder.decode(bytes)
        assertEquals(chinese, (result as TextDecodeResult.Success).text)
    }

    @Test
    fun detector_exception_falls_back_to_gb18030() {
        val bytes = chinese.toByteArray(java.nio.charset.Charset.forName("GB18030"))
        val decoder = TextDecoder(detector = { throw IllegalStateException("boom") })
        val result = decoder.decode(bytes)
        assertEquals(chinese, (result as TextDecodeResult.Success).text)
        assertEquals("GB18030", result.charsetName)
    }

    @Test
    fun low_cjk_ratio_with_detector_still_falls_through_to_manual() {
        val decoder = TextDecoder(detector = { java.nio.charset.StandardCharsets.UTF_8 })
        val result = decoder.decode("plain english text".toByteArray())
        org.junit.Assert.assertTrue(result is TextDecodeResult.ManualEncodingRequired)
    }
}
