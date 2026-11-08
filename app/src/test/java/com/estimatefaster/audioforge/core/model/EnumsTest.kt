package com.estimatefaster.audioforge.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** P1-01：枚举 wire/前缀必须与 Python 版（`porting-vectors.json`）一致。 */
class EnumsTest {

    @Test
    fun emotion_wires_are_exact() {
        val expected = listOf(
            "neutral",
            "happy",
            "sad",
            "angry",
            "anxious",
            "excited",
            "surprised",
            "soft tone",
            "serious",
            "calm",
            "whispering",
        )
        assertEquals(expected, Emotion.entries.map { it.wire })
    }

    @Test
    fun emotion_prefixes_match_fixture() {
        val expected = mapOf(
            "happy" to "[happy]",
            "sad" to "[sad]",
            "angry" to "[angry]",
            "anxious" to "[anxious]",
            "excited" to "[excited]",
            "surprised" to "[surprised]",
            "soft tone" to "[soft tone]",
            "serious" to "[serious]",
            "calm" to "[calm]",
            "whispering" to "[whispering]",
        )
        assertEquals(expected, Emotion.entries.mapNotNull { e -> e.ttsPrefix?.let { e.wire to it } }.toMap())
        assertNull("Neutral 不应有 TTS 前缀", Emotion.Neutral.ttsPrefix)
    }

    @Test
    fun emotion_applyTo_adds_prefix_only_when_present() {
        assertEquals("[happy]今天真好", Emotion.Happy.applyTo("今天真好"))
        assertEquals("今天真好", Emotion.Neutral.applyTo("今天真好"))
    }

    @Test
    fun emotion_fromWire_round_trips_and_falls_back() {
        Emotion.entries.forEach { assertEquals(it, Emotion.fromWire(it.wire)) }
        assertEquals(Emotion.Neutral, Emotion.fromWire("不存在"))
    }

    @Test
    fun status_wires_are_exact() {
        assertEquals(listOf("pending", "running", "done", "error"), ParseStatus.entries.map { it.wire })
        assertEquals(listOf("pending", "running", "done", "error"), AudioStatus.entries.map { it.wire })
        assertEquals(listOf("pending", "running", "done", "error"), SegmentStatus.entries.map { it.wire })
    }

    @Test
    fun type_importance_gender_batch_wires_are_exact() {
        assertEquals(listOf("narration", "dialogue"), SegmentType.entries.map { it.wire })
        assertEquals(listOf("protagonist", "supporting", "minor"), Importance.entries.map { it.wire })
        assertEquals(listOf("male", "female", "unknown"), Gender.entries.map { it.wire })
        assertEquals(listOf("full", "parse", "render"), BatchMode.entries.map { it.wire })
    }

    @Test
    fun fromWire_falls_back_to_defaults() {
        assertEquals(ParseStatus.Pending, ParseStatus.fromWire("nope"))
        assertEquals(AudioStatus.Pending, AudioStatus.fromWire("nope"))
        assertEquals(SegmentStatus.Pending, SegmentStatus.fromWire("nope"))
        assertEquals(SegmentType.Narration, SegmentType.fromWire("nope"))
        assertEquals(Importance.Supporting, Importance.fromWire("nope"))
        assertEquals(Gender.Unknown, Gender.fromWire("nope"))
        assertEquals(BatchMode.Full, BatchMode.fromWire("nope"))
    }

    @Test
    fun role_ids_follow_the_contract() {
        assertEquals("narrator", NARRATOR_ROLE_ID)
        assertEquals("unknown", UNKNOWN_ROLE_ID)
        assertEquals("narrator_male", NARRATOR_CATEGORY)
        assertEquals("role_0001", roleIdFor(1))
        assertEquals("role_0042", roleIdFor(42))
        assertEquals("role_9999", roleIdFor(9_999))
        assertThrows(IllegalArgumentException::class.java) { roleIdFor(0) }
    }
}
