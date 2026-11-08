package com.estimatefaster.audioforge.core.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.estimatefaster.audioforge.core.model.Gender
import com.estimatefaster.audioforge.core.model.Importance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P1-02：63 个类别、voice_id、note、speed 与 fixture 快照一致；旁白不进 LLM 池。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoicePoolTest {

    private lateinit var pool: VoicePool

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        pool = VoicePool.parse(
            context.assets.open("voice_pool.json").bufferedReader(Charsets.UTF_8).use { it.readText() },
        )
    }

    @Test
    fun has_63_locked_categories() {
        assertEquals(63, pool.size)
        assertEquals("narrator_male", pool.narratorCategory)
        assertTrue(pool.valid("narrator_male"))
        assertTrue(pool.valid("male_brother"))
        assertFalse(pool.valid(null))
        assertFalse(pool.valid("nope"))
    }

    @Test
    fun matches_fixture_snapshot_for_every_category() {
        val expected = VoicePool.parse(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("voice-pool.json")) {
                "缺少测试资源 voice-pool.json"
            }.bufferedReader(Charsets.UTF_8).use { it.readText() },
        )
        assertEquals(expected.keys, pool.keys)
        expected.keys.forEach { key ->
            assertEquals("类别 $key 的 voice_id/label/note/speed 不一致", expected.get(key), pool.get(key))
        }
    }

    @Test
    fun speed_and_notes_match_fixture() {
        assertEquals(1.15, pool.speedFor("male_brother") ?: 0.0, 1e-9)
        assertEquals(8, pool.keys.count { pool.get(it)?.note != null })
        assertEquals(
            "样音语速偏慢,已默认按 1.15 倍速合成",
            pool.get("male_brother")?.note,
        )
        assertEquals(
            "样音有杂音、音质一般,优先用其它男主音色",
            pool.get("male_lead_warm")?.note,
        )
    }

    @Test
    fun llm_pool_excludes_narrator_and_notes() {
        val lines = pool.poolForLlm().lines()
        assertEquals(62, lines.size)
        assertTrue(lines.none { it.startsWith("- narrator_male:") })
        assertTrue(lines.all { it.startsWith("- ") && it.contains(": ") })
        assertFalse("note 不应进 LLM 提示词", pool.poolForLlm().contains("样音"))

        val withNarrator = pool.poolForLlm(includeNarrator = true).lines()
        assertEquals(63, withNarrator.size)
        assertTrue(withNarrator.any { it.startsWith("- narrator_male:") })
    }

    @Test
    fun fallback_mapping_matches_python() {
        assertEquals("male_lead_steady", pool.fallbackCategory(Gender.Male, Importance.Protagonist))
        assertEquals("male_gentle_young", pool.fallbackCategory(Gender.Male, Importance.Supporting))
        assertEquals("male_young_support", pool.fallbackCategory(Gender.Male, Importance.Minor))
        assertEquals("female_lead_gentle", pool.fallbackCategory(Gender.Female, Importance.Protagonist))
        assertEquals("female_young", pool.fallbackCategory(Gender.Female, Importance.Supporting))
        assertEquals("female_mature", pool.fallbackCategory(Gender.Female, Importance.Minor))
        // Unknown 按男性兜底
        assertEquals("male_lead_steady", pool.fallbackCategory(Gender.Unknown, Importance.Protagonist))
    }

    @Test
    fun every_fallback_category_exists_in_pool() {
        Gender.entries.forEach { gender ->
            Importance.entries.forEach { importance ->
                val category = pool.fallbackCategory(gender, importance)
                assertTrue("兜底类别 $category 不在池中", pool.valid(category))
            }
        }
    }
}
