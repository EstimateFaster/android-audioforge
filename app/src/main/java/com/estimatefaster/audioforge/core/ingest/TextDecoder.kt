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
 * BOM（UTF-8/UTF-16LE/BE）→ UTF-8 严格 → ICU `CharsetDetector` → GB18030 → Big5。
 * 每种候选都必须能严格解码且前 2 万字中文占比 ≥15%，否则交给用户手动选编码；不静默乱码。
 *
 * 纯 JVM + `android.icu`；不抛未捕获异常，所有失败都返回 [TextDecodeResult]。
 */
class TextDecoder(
    private val maxBytes: Long = MAX_IMPORT_BYTES,
    private val sampleChars: Int = CJK_SAMPLE_CHARS,
    private val minCjkRatio: Double = MIN_CJK_RATIO,
    private val detector: (ByteArray) -> Charset? = ::detectWithIcu,
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

        val detected = try {
            detector(bytes)
        } catch (e: Exception) {
            // 检测器自身异常不应中断回退链。
            null
        }
        if (detected != null && !detected.equals(Charsets.UTF_8)) {
            val text = strictDecode(bytes, detected)
            if (text != null && passesCjkCheck(text)) {
                return TextDecodeResult.Success(text, detected.name())
            }
        }

        for (name in FALLBACK_CHARSETS) {
            val charset = Charset.forName(name)
            val text = strictDecode(bytes, charset) ?: continue
            if (passesCjkCheck(text)) return TextDecodeResult.Success(text, charset.name())
        }

        return TextDecodeResult.ManualEncodingRequired(
            "无法自动识别编码（前 ${sampleChars} 字中文占比需 ≥${(minCjkRatio * 100).toInt()}%）",
            MANUAL_CANDIDATES,
        )
    }

    private fun passesCjkCheck(text: String): Boolean = cjkRatio(text) >= minCjkRatio

    private fun cjkRatio(text: String): Double {
        if (text.isEmpty()) return 0.0
        val sample = if (text.length > sampleChars) text.substring(0, sampleChars) else text
        val cjk = sample.count { it in '\u4e00'..'\u9fff' }
        return cjk.toDouble() / sample.length
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
        private val FALLBACK_CHARSETS = listOf("GB18030", "Big5")

        private fun detectWithIcu(bytes: ByteArray): Charset? = try {
            android.icu.text.CharsetDetector()
                .setText(bytes)
                .detect()
                ?.name
                ?.let { Charset.forName(it) }
        } catch (e: Exception) {
            null
        }
    }
}
