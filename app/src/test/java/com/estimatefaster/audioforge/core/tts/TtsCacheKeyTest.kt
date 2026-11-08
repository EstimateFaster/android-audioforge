package com.estimatefaster.audioforge.core.tts

import com.estimatefaster.audioforge.core.model.Emotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-10：TTS 缓存键 v2（执行计划 §8.8）。 */
class TtsCacheKeyTest {

    private fun input(
        text: String = "你好。",
        emotion: Emotion = Emotion.Happy,
        voiceId: String = "voice1",
        speed: Double = 1.0,
        volume: Double = 1.0,
        pitch: Double = 0.0,
        model: String = "s2.1-pro-free",
    ): TtsCacheInput = TtsCacheInput(
        text = text,
        emotion = emotion,
        voiceId = voiceId,
        speed = speed,
        volume = volume,
        pitch = pitch,
        model = model,
    )

    // ---------- 稳定性 ----------

    @Test
    fun same_input_is_stable() {
        val a = input()
        assertEquals(TtsCacheKey.v2(a), TtsCacheKey.v2(a))
        // 重新构造一份等价输入也必须得到同一个键。
        assertEquals(TtsCacheKey.v2(a), TtsCacheKey.v2(input()))
        assertEquals(TtsCacheKey.v2(a), TtsCacheKey.v2(a.copy()))
    }

    @Test
    fun keys_are_deterministic_golden_vectors() {
        // 这两个 v2 向量是按 §8.8 规范在 Android 侧独立算出的（不是 Python 的 v1 键）。
        assertEquals("v2|8eeb5804d7b8", TtsCacheKey.v2(input()))
        assertEquals(
            "v2|3e03de8112fb",
            TtsCacheKey.v2(
                input(
                    text = "他轻轻一笑。",
                    emotion = Emotion.Neutral,
                    voiceId = "59cb5986671546eaa6ca8ae6f29f6d22",
                ),
            ),
        )
    }

    // ---------- 敏感性：任一字段变化 → 键变化 ----------

    @Test
    fun text_change_changes_key() {
        assertNotEquals(TtsCacheKey.v2(input()), TtsCacheKey.v2(input(text = "你好！")))
        assertNotEquals(TtsCacheKey.v2(input()), TtsCacheKey.v2(input(text = "你好")))
    }

    @Test
    fun emotion_change_changes_key() {
        assertNotEquals(
            TtsCacheKey.v2(input(emotion = Emotion.Happy)),
            TtsCacheKey.v2(input(emotion = Emotion.Sad)),
        )
        assertNotEquals(
            TtsCacheKey.v2(input(emotion = Emotion.SoftTone)),
            TtsCacheKey.v2(input(emotion = Emotion.Neutral)),
        )
    }

    @Test
    fun voice_change_changes_key() {
        assertNotEquals(
            TtsCacheKey.v2(input(voiceId = "voice1")),
            TtsCacheKey.v2(input(voiceId = "voice2")),
        )
    }

    @Test
    fun speed_change_changes_key() {
        assertNotEquals(TtsCacheKey.v2(input(speed = 1.0)), TtsCacheKey.v2(input(speed = 1.5)))
    }

    @Test
    fun volume_change_changes_key() {
        assertNotEquals(TtsCacheKey.v2(input(volume = 1.0)), TtsCacheKey.v2(input(volume = 0.5)))
    }

    @Test
    fun pitch_change_changes_key() {
        assertNotEquals(TtsCacheKey.v2(input(pitch = 0.0)), TtsCacheKey.v2(input(pitch = -2.0)))
    }

    @Test
    fun model_change_changes_key() {
        assertNotEquals(
            TtsCacheKey.v2(input(model = "s2.1-pro-free")),
            TtsCacheKey.v2(input(model = "s2.1-pro")),
        )
    }

    @Test
    fun all_fields_participate() {
        val base = input()
        val variants = listOf(
            input(text = "再见。"),
            input(emotion = Emotion.Angry),
            input(voiceId = "voice9"),
            input(speed = 0.9),
            input(volume = 0.9),
            input(pitch = 1.0),
            input(model = "s2.1"),
        )
        val keys = (listOf(base) + variants).map(TtsCacheKey::v2)
        assertEquals("每个字段都必须改变键", keys.size, keys.toSet().size)
    }

    @Test
    fun speed_volume_and_pitch_are_not_interchangeable() {
        // 三者规范化后都是 "1.5"，必须靠 "s=" / "vol=" / "p=" 区分。
        assertNotEquals(
            TtsCacheKey.v2(input(speed = 1.5, volume = 1.0, pitch = 0.0)),
            TtsCacheKey.v2(input(speed = 1.0, volume = 1.5, pitch = 0.0)),
        )
        assertNotEquals(
            TtsCacheKey.v2(input(speed = 1.5, volume = 1.0, pitch = 0.0)),
            TtsCacheKey.v2(input(speed = 1.0, volume = 1.0, pitch = 1.5)),
        )
        assertNotEquals(
            TtsCacheKey.v2(input(speed = 1.0, volume = 1.5, pitch = 0.0)),
            TtsCacheKey.v2(input(speed = 1.0, volume = 1.0, pitch = 1.5)),
        )
    }

    // ---------- 浮点格式 ----------

    @Test
    fun plain_decimal_formats_per_spec() {
        assertEquals("1", TtsCacheKey.plainDecimal(1.0))
        assertEquals("1.15", TtsCacheKey.plainDecimal(1.15))
        assertEquals("0", TtsCacheKey.plainDecimal(0.0))
        assertEquals("0", TtsCacheKey.plainDecimal(-0.0))
        assertEquals("-12", TtsCacheKey.plainDecimal(-12.0))
        assertEquals("0.0001", TtsCacheKey.plainDecimal(0.0001))
        assertEquals("10000000", TtsCacheKey.plainDecimal(1.0E7))
    }

    @Test
    fun plain_decimal_never_uses_scientific_notation() {
        for (value in listOf(0.0001, 1.0E-4, 1.0E7, 0.00001, 12345.6789, -2.0E-6)) {
            val plain = TtsCacheKey.plainDecimal(value)
            assertFalse("$value -> $plain", plain.contains('E', ignoreCase = true))
        }
    }

    @Test
    fun integer_valued_doubles_normalize_to_same_key() {
        // 1.0 与 1 规范化后都是 "1"，键必须相同（对应同一份音频）。
        assertEquals(TtsCacheKey.v2(input(speed = 1.0)), TtsCacheKey.v2(input(speed = 1.00)))
        assertEquals(TtsCacheKey.v2(input(pitch = 0.0)), TtsCacheKey.v2(input(pitch = -0.0)))
    }

    // ---------- 键外形 ----------

    @Test
    fun key_shape_is_v2_prefix_plus_12_hex() {
        val key = TtsCacheKey.v2(input())
        assertEquals(TtsCacheKey.TOTAL_LENGTH, key.length)
        assertEquals(15, key.length)
        assertTrue(key.startsWith("v2|"))
        val hex = key.removePrefix("v2|")
        assertEquals(12, hex.length)
        assertTrue("必须是 12 位小写 hex：$hex", hex.matches(Regex("[0-9a-f]{12}")))
    }

    @Test
    fun canonical_payload_field_order_matches_spec() {
        assertEquals(
            "e=happy\nm=s2.1-pro-free\np=0\ns=1\nt=你好。\nv=voice1\nvol=1",
            TtsCacheKey.canonicalPayload(input()),
        )
        assertEquals(
            "e=soft tone\nm=s2.1-pro-free\np=-12.5\ns=1.15\nt=a\nb\nv=v 1\nvol=0.0001",
            TtsCacheKey.canonicalPayload(
                input(
                    text = "a\nb",
                    emotion = Emotion.SoftTone,
                    voiceId = "v 1",
                    speed = 1.15,
                    volume = 0.0001,
                    pitch = -12.5,
                ),
            ),
        )
    }
}
