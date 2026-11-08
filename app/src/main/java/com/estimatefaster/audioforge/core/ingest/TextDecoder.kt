package com.estimatefaster.audioforge.core.ingest

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 自动解码结果；失败时给 UI 可读原因与可手动选择的编码。 */
sealed interface TextDecodeResult {
    data class Success(val text: String, val charsetName: String) : TextDecodeResult
    data class ManualEncodingRequired(val reason: String, val candidates: List<String>) : TextDecodeResult
    data class TooLarge(val sizeBytes: Long, val limitBytes: Long) : TextDecodeResult
    data class Failure(val reason: String) : TextDecodeResult
}

/**
 * txt 编码识别（执行计划 §7.7 / §8.1）：
 * BOM（UTF-8/UTF-16LE/BE）→ UTF-8 严格 → GB18030 / Big5（严格解码 + 评分）。
 *
 * 计划原本要求 `android.icu.text.CharsetDetector`，但该类不在 Android 公开 SDK
 * （compileSdk 37 的 android.jar 中不存在）。这里改用启发式：
 * 候选都必须严格解码且前 2 万字中文占比 ≥15%；GB18030 与 Big5 都能解时，
 * 用「中文标点密度 + CJK 密度 - 异常字符」评分选更可信的一个。
 * 失败返回 [TextDecodeResult.ManualEncodingRequired]，不静默乱码、不抛未捕获异常。
 */
class TextDecoder(
    private val maxBytes: Long = MAX_IMPORT_BYTES,
    private val sampleChars: Int = CJK_SAMPLE_CHARS,
    private val minCjkRatio: Double = MIN_CJK_RATIO,
) {

    fun decode(bytes: ByteArray, manualCharset: String? = null): TextDecodeResult {
        if (bytes.isEmpty()) return TextDecodeResult.Failure("文件为空")
        if (bytes.size.toLong() > maxBytes) {
            return TextDecodeResult.TooLarge(bytes.size.toLong(), maxBytes)
        }

        return try {
            if (manualCharset != null) {
                decodeManually(bytes, manualCharset)
            } else {
                decodeAutomatically(bytes)
            }
        } catch (e: Exception) {
            // 兜底：任何未预期异常都不应冒到 UI。
            TextDecodeResult.Failure("解码失败：${e.message ?: e::class.java.simpleName}")
        }
    }

    private fun decodeManually(bytes: ByteArray, manualCharset: String): TextDecodeResult {
        val charset = try {
            Charset.forName(manualCharset)
        } catch (e: Exception) {
            return TextDecodeResult.Failure("不支持的编码：$manualCharset")
        }
        val text = strictDecode(bytes, charset)
            ?: return TextDecodeResult.Failure("无法用 ${charset.name()} 严格解码该文件")
        return TextDecodeResult.Success(text, charset.name())
    }

    private fun decodeAutomatically(bytes: ByteArray): TextDecodeResult {
        bomCharset(bytes)?.let { (charset, offset) ->
            val text = strictDecode(bytes, charset, offset, bytes.size - offset)
            if (text != null) {
                if (passesCjkCheck(text)) return TextDecodeResult.Success(text, charset.name())
                // BOM 明确但内容不像中文小说：不静默接受，交给用户确认。
                return TextDecodeResult.ManualEncodingRequired(
                    "BOM 指示 ${charset.name()}，但中文字符占比不足",
                    MANUAL_CANDIDATES,
                )
            }
        }

        val utf8 = strictDecode(bytes, Charsets.UTF_8)
        if (utf8 != null && passesCjkCheck(utf8)) {
            return TextDecodeResult.Success(utf8, "UTF-8")
        }

        val best = FALLBACK_CHARSETS
            .mapIndexedNotNull { order, name ->
                val charset = Charset.forName(name)
                val text = strictDecode(bytes, charset) ?: return@mapIndexedNotNull null
                if (!passesCjkCheck(text)) return@mapIndexedNotNull null
                Candidate(order, charset.name(), text, plausibility(text))
            }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.order })
            .firstOrNull()

        if (best != null) return TextDecodeResult.Success(best.text, best.charsetName)

        return TextDecodeResult.ManualEncodingRequired(
            "无法自动识别编码（前 ${sampleChars} 字中文占比需 ≥${(minCjkRatio * 100).toInt()}%）",
            MANUAL_CANDIDATES,
        )
    }

    private data class Candidate(
        val order: Int,
        val charsetName: String,
        val text: String,
        val score: Double,
    )

    private fun passesCjkCheck(text: String): Boolean = cjkRatio(text) >= minCjkRatio

    private fun cjkRatio(text: String): Double {
        if (text.isEmpty()) return 0.0
        val sample = if (text.length > sampleChars) text.substring(0, sampleChars) else text
        val cjk = sample.count { it in '\u4e00'..'\u9fff' }
        return cjk.toDouble() / sample.length
    }

    /**
     * 同一串字节用 GB18030 / Big5 都能严格解码时，选更像正常中文小说的那个：
     * 正确解码保留中文标点，错误解码多产生生僻字/假名/私用区字符。
     */
    private fun plausibility(text: String): Double {
        val sample = if (text.length > sampleChars) text.substring(0, sampleChars) else text
        if (sample.isEmpty()) return 0.0
        var punctuation = 0
        var cjk = 0
        var weird = 0
        for (ch in sample) {
            when {
                ch in CHINESE_PUNCTUATION -> punctuation++
                ch in '\u4e00'..'\u9fff' -> cjk++
                ch.isWhitespace() || ch.code < 0x80 -> Unit // 换行/ASCII 不区分
                ch.code in 0x3000..0x303F -> punctuation++
                else -> weird++
            }
        }
        return (punctuation * 6.0 + cjk * 0.5 - weird * 2.0) / sample.length
    }

    private fun bomCharset(bytes: ByteArray): Pair<Charset, Int>? = when {
        bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3

        bytes.size >= 2 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2

        bytes.size >= 2 &&
            bytes[0] == 0xFE.toByte() &&
            bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2

        else -> null
    }

    private fun strictDecode(
        bytes: ByteArray,
        charset: Charset,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): String? = try {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    companion object {
        const val MAX_IMPORT_BYTES = 64L * 1024 * 1024
        const val CJK_SAMPLE_CHARS = 20_000
        const val MIN_CJK_RATIO = 0.15

        val MANUAL_CANDIDATES = listOf("GB18030", "Big5", "UTF-8", "UTF-16LE", "UTF-16BE")

        /** 顺序即平局优先级：GB18030 优先。 */
        private val FALLBACK_CHARSETS = listOf("GB18030", "Big5")

        private const val CHINESE_PUNCTUATION = "，。！？；：、“”‘’（）《》〈〉【】…—～·"
    }
}
