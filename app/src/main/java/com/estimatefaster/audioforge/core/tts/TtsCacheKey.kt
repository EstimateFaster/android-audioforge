package com.estimatefaster.audioforge.core.tts

import com.estimatefaster.audioforge.core.model.Emotion
import java.math.BigDecimal
import java.security.MessageDigest

/**
 * 生成 TTS 缓存键所需的全部输入（执行计划 §8.8）。
 *
 * 七个字段缺一不可：换音色 / 换情绪 / 改语速 / 改音量 / 改音调 / 换模型 / 改文本
 * 都必须产生新键，否则会错误复用旧音频。
 */
data class TtsCacheInput(
    val text: String,
    val emotion: Emotion,
    val voiceId: String,
    val speed: Double,
    val volume: Double,
    val pitch: Double,
    val model: String,
)

/**
 * TTS 缓存键 v2（执行计划 §8.8）。
 *
 * 规范：
 * ```
 * "v2|" + sha1(
 *   "e=" + emotion.wire + "\n" +
 *   "m=" + model + "\n" +
 *   "p=" + pitchPlain + "\n" +
 *   "s=" + speedPlain + "\n" +
 *   "t=" + text + "\n" +
 *   "v=" + voiceId + "\n" +
 *   "vol=" + volumePlain
 * ).hex().take(12)
 * ```
 *
 * 与 Python v1 的 JSON 口径**故意不同**：v2 是逐行 `key=value` 的纯文本，
 * 不依赖 JSON 序列化器的空格/浮点/转义行为，因此同输入在 Android 侧永远稳定。
 *
 * 注意：v2 不做转义，文本里出现 `"\nv="` 这类内容时理论上可与其它字段组合出相同
 * 载荷（§8.8 未规定转义，故这里不擅自加）。若要导入服务器历史数据，
 * 按 §8.8 另加 `LegacyCacheKey`（Python JSON 口径），不要改 v2。
 */
object TtsCacheKey {

    /** 键前缀，含版本号；用于失效判断。 */
    const val PREFIX: String = "v2|"

    /** 截取的 hex 长度。 */
    const val HASH_LENGTH: Int = 12

    /** 生成 v2 缓存键：`v2|` + 12 位小写 hex，总长固定 [TOTAL_LENGTH]。 */
    fun v2(input: TtsCacheInput): String =
        PREFIX + sha1Hex(canonicalPayload(input)).take(HASH_LENGTH)

    /**
     * 参与 SHA-1 的规范载荷；字段顺序即 §8.8 的规定顺序，不能重排。
     * 暴露给测试用于锁定格式（线上调用方只需 [v2]）。
     */
    internal fun canonicalPayload(input: TtsCacheInput): String = buildString {
        append("e=").append(input.emotion.wire).append('\n')
        append("m=").append(input.model).append('\n')
        append("p=").append(plainDecimal(input.pitch)).append('\n')
        append("s=").append(plainDecimal(input.speed)).append('\n')
        append("t=").append(input.text).append('\n')
        append("v=").append(input.voiceId).append('\n')
        append("vol=").append(plainDecimal(input.volume))
    }

    /**
     * 浮点的规范文本：`BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()`。
     * `1.0 -> "1"`、`0.0001 -> "0.0001"`（绝不出现 `1.0E-4`）；`-0.0` 归一为 `"0"`。
     */
    internal fun plainDecimal(value: Double): String {
        // `BigDecimal.valueOf(-0.0)` 在部分 JDK 上仍保留负号；缓存键不应区分 ±0。
        val normalized = if (value == 0.0) 0.0 else value
        return BigDecimal.valueOf(normalized).stripTrailingZeros().toPlainString()
    }

    /** SHA-1 → 小写 hex（UTF-8 编码）。 */
    internal fun sha1Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val unsigned = byte.toInt() and 0xFF
            out.append(HEX_DIGITS[unsigned ushr 4])
            out.append(HEX_DIGITS[unsigned and 0x0F])
        }
        return out.toString()
    }

    /** `v2|` + 12 位 hex = 15。 */
    const val TOTAL_LENGTH: Int = 3 + HASH_LENGTH

    private const val HEX_DIGITS = "0123456789abcdef"
}
