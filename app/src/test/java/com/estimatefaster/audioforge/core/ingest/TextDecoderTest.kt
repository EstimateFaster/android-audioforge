package com.estimatefaster.audioforge.core.ingest

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P1-03：UTF-8/BOM/GBK/UTF-16/Big5/非法字节；失败不抛异常。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TextDecoderTest {

    private val decoder = TextDecoder()

    private val simplified =
        "第一章 风起\n\n他抬头看向天空，云层很厚，像是要下雨了。风声穿过山谷，带着泥土的气味。"

    private val traditional =
        "第一章 風起\n\n他抬頭看向天空，雲層很厚，像是要下雨了。風聲穿過山谷，帶著泥土的氣味。"

    @Test
    fun utf8_plain() {
        val result = decoder.decode(simplified.toByteArray(Charsets.UTF_8))
        assertTrue("实际是 $result", result is TextDecodeResult.Success)
        result as TextDecodeResult.Success
        assertEquals(simplified, result.text)
        assertEquals("UTF-8", result.charsetName)
    }

    @Test
    fun utf8_with_bom_is_stripped() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val result = decoder.decode(bom + simplified.toByteArray(Charsets.UTF_8))
        assertTrue("实际是 $result", result is TextDecodeResult.Success)
        assertEquals(simplified, (result as TextDecodeResult.Success).text)
    }

    @Test
    fun gbk_auto_detected() {
        val bytes = simplified.toByteArray(Charset.forName("GBK"))
        val result = decoder.decode(bytes)
        assertTrue("实际是 $result", result is TextDecodeResult.Success)
        assertEquals(simplified, (result as TextDecodeResult.Success).text)
    }

    @Test
    fun gb18030_auto_detected() {
        val bytes = simplified.toByteArray(Charset.forName("GB18030"))
        val result = decoder.decode(bytes)
        assertTrue("实际是 $result", result is TextDecodeResult.Success)
        assertEquals(simplified, (result as TextDecodeResult.Success).text)
    }

    @Test
    fun utf16le_with_bom() {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val bytes = bom + simplified.toByteArray(Charset.forName("UTF-16LE"))
        val result = decoder.decode(bytes)
        assertTrue("实际是 $result", result is TextDecodeResult.Success)
        result as TextDecodeResult.Success
        assertEquals(simplified, result.text)
        assertEquals("UTF-16LE", result.charsetName)
    }

    @Test
    fun utf16be_with_bom() {
        val bom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        val bytes = bom + simplified.toByteArray(Charset.forName("UTF-16BE"))
        val result = decoder.decode(bytes)
        assertTrue("实际是 $result", result is TextDecodeResult.Success)
        result as TextDecodeResult.Success
        assertEquals(simplified, result.text)
        assertEquals("UTF-16BE", result.charsetName)
    }

    @Test
    fun big5_auto_detected() {
        val bytes = traditional.toByteArray(Charset.forName("Big5"))
        val result = decoder.decode(bytes)
        assertTrue("实际是 $result", result is TextDecodeResult.Success)
        assertEquals(traditional, (result as TextDecodeResult.Success).text)
    }

    @Test
    fun big5_manual_fallback() {
        val bytes = traditional.toByteArray(Charset.forName("Big5"))
        val result = decoder.decode(bytes, manualCharset = "Big5")
        assertTrue("实际是 $result", result is TextDecodeResult.Success)
        assertEquals(traditional, (result as TextDecodeResult.Success).text)
    }

    @Test
    fun illegal_bytes_require_manual_encoding() {
        val result = decoder.decode(ByteArray(64) { 0xFF.toByte() })
        assertTrue("实际是 $result", result is TextDecodeResult.ManualEncodingRequired)
        val manual = result as TextDecodeResult.ManualEncodingRequired
        assertTrue(manual.candidates.contains("GB18030"))
    }

    @Test
    fun plain_ascii_is_rejected_by_cjk_ratio() {
        val result = decoder.decode("hello world, no chinese at all".toByteArray(Charsets.UTF_8))
        assertTrue("实际是 $result", result is TextDecodeResult.ManualEncodingRequired)
    }

    @Test
    fun empty_file_fails() {
        val result = decoder.decode(ByteArray(0))
        assertTrue("实际是 $result", result is TextDecodeResult.Failure)
    }

    @Test
    fun too_large_file_is_rejected() {
        val small = TextDecoder(maxBytes = 8)
        val result = small.decode("123456789".toByteArray(Charsets.UTF_8))
        assertTrue("实际是 $result", result is TextDecodeResult.TooLarge)
        assertEquals(9L, (result as TextDecodeResult.TooLarge).sizeBytes)
    }

    @Test
    fun unsupported_manual_charset_is_reported() {
        val result = decoder.decode(simplified.toByteArray(Charsets.UTF_8), manualCharset = "NOPE")
        assertTrue("实际是 $result", result is TextDecodeResult.Failure)
    }

    @Test
    fun manual_charset_can_bypass_detection() {
        val bytes = simplified.toByteArray(Charset.forName("GBK"))
        val result = decoder.decode(bytes, manualCharset = "GBK")
        assertTrue("实际是 $result", result is TextDecodeResult.Success)
        assertEquals(simplified, (result as TextDecodeResult.Success).text)
    }
}
