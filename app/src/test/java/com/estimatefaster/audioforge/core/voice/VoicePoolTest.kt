package com.estimatefaster.audioforge.core.voice

import android.content.Context
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import com.estimatefaster.audioforge.core.model.Gender
import com.estimatefaster.audioforge.core.model.Importance
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P1-02/P1-08：63 个类别、voice_id、note、speed 与 fixture 快照一致；旁白不进 LLM 池；缓存。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoicePoolTest {

    private lateinit var assets: AssetManager
    private lateinit var pool: VoicePool

    @Before
    fun setUp() {
        VoicePoolProvider.clearForTest()
        val context = ApplicationProvider.getApplicationContext<Context>()
        assets = context.assets
        pool = VoicePool.parse(assets.open("voice_pool.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    @After
    fun tearDown() {
        VoicePoolProvider.clearForTest()
    }

    private fun fixtureJson(): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("voice-pool.json")) {
            "缺少测试资源 voice-pool.json"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun has_63_locked_categories() {
        assertEquals(63, pool.size)
        assertEquals(63, pool.categories().size)
        assertEquals(63, pool.all().size)
        assertEquals("narrator_male", pool.narratorCategory)
        assertTrue(pool.valid("narrator_male"))
        assertTrue(pool.valid("male_brother"))
        assertFalse(pool.valid(null))
        assertFalse(pool.valid("nope"))
    }

    @Test
    fun matches_fixture_snapshot_for_every_category() {
        val expected = VoicePool.parse(fixtureJson())
        assertEquals(expected.keys, pool.keys)
        expected.keys.forEach { key ->
            assertEquals("类别 $key 的 voice_id/label/note/speed 不一致", expected.get(key), pool.get(key))
        }
        // 完整快照：所有类别逐个对比，防止只测到部分 key。
        assertEquals(expected.categories(), pool.categories())
        assertEquals(expected.all(), pool.all())
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
    fun label_and_note_accessors() {
        assertEquals("【旁白】沉稳大气播音腔(固定旁白,不分配给角色)", pool.labelFor("narrator_male"))
        assertEquals(pool.get("male_brother")?.label, pool.labelFor("male_brother"))
        assertEquals("样音语速偏慢,已默认按 1.15 倍速合成", pool.noteFor("male_brother"))
        assertEquals(pool.get("male_lead_warm")?.note, pool.noteFor("male_lead_warm"))
        assertNull("无 note 的类别应返回 null", pool.noteFor("male_lead_serious"))
        assertNull("未知类别应返回 null", pool.labelFor("nope"))
        assertNull("未知类别应返回 null", pool.noteFor("nope"))
        assertNull("空 key 应返回 null", pool.labelFor(""))
    }

    @Test
    fun categories_and_all_are_immutable_snapshots() {
        val categories = pool.categories()
        assertEquals(pool.keys, categories.keys)
        assertEquals(categories.values.toList(), pool.all())
        assertEquals(55, pool.all().count { it.note == null })

        // 只读快照：外部无法修改内部状态。
        @Suppress("UNCHECKED_CAST")
        val mutableCategories = categories as MutableMap<String, VoiceCategory>
        assertThrows(UnsupportedOperationException::class.java) {
            mutableCategories["hacked"] = VoiceCategory(voiceId = "0".repeat(32), label = "注入")
        }
        assertThrows(UnsupportedOperationException::class.java) { mutableCategories.clear() }

        @Suppress("UNCHECKED_CAST")
        val mutableKeys = pool.keys as MutableSet<String>
        assertThrows(UnsupportedOperationException::class.java) { mutableKeys.clear() }

        @Suppress("UNCHECKED_CAST")
        val mutableAll = pool.all() as MutableList<VoiceCategory>
        assertThrows(UnsupportedOperationException::class.java) { mutableAll.clear() }

        // 篡改尝试后池仍完整。
        assertEquals(63, pool.size)
    }

    @Test
    fun fallback_mapping_matches_python() {
        assertEquals("male_lead_steady", pool.fallbackCategory(Gender.Male, Importance.Protagonist))
        assertEquals("male_gentle_young", pool.fallbackCategory(Gender.Male, Importance.Supporting))
        assertEquals("male_young_support", pool.fallbackCategory(Gender.Male, Importance.Minor))
        assertEquals("female_lead_gentle", pool.fallbackCategory(Gender.Female, Importance.Protagonist))
        assertEquals("female_young", pool.fallbackCategory(Gender.Female, Importance.Supporting))
        assertEquals("female_mature", pool.fallbackCategory(Gender.Female, Importance.Minor))
        // Unknown 按男性兜底，三种重要度都锁定。
        assertEquals("male_lead_steady", pool.fallbackCategory(Gender.Unknown, Importance.Protagonist))
        assertEquals("male_gentle_young", pool.fallbackCategory(Gender.Unknown, Importance.Supporting))
        assertEquals("male_young_support", pool.fallbackCategory(Gender.Unknown, Importance.Minor))
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

    @Test
    fun every_voice_id_is_32_hex() {
        val hex = Regex("[0-9a-f]{32}")
        pool.categories().forEach { (key, category) ->
            assertTrue(
                "类别 $key 的 voice_id 不是 32 位小写十六进制：${category.voiceId}",
                hex.matches(category.voiceId),
            )
        }
    }

    @Test
    fun provider_parses_asset_and_caches_single_instance() {
        val first = VoicePoolProvider.get(assets)
        assertSame("重复 get 应返回同一实例", first, VoicePoolProvider.get(assets))
        assertEquals("narrator_male", first.narratorCategory)
        assertEquals(pool.keys, first.keys)
        pool.keys.forEach { key ->
            assertEquals("类别 $key 与 fixture 不一致", pool.get(key), first.get(key))
        }

        VoicePoolProvider.clearForTest()
        val second = VoicePoolProvider.get(assets)
        assertNotSame("clearForTest 后应重新解析出新实例", first, second)
        assertEquals(first.keys, second.keys)
        assertEquals(first.categories(), second.categories())
        assertSame("重新解析后再次 get 仍应命中缓存", second, VoicePoolProvider.get(assets))
    }

    @Test
    fun provider_concurrent_get_returns_single_instance() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..32).map { executor.submit(Callable { VoicePoolProvider.get(assets) }) }
            val instances = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(32, instances.size)
            assertTrue("并发 get 必须只解析一次", instances.all { it === instances.first() })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun parse_rejects_fewer_than_63_categories() {
        val tooSmall = """
            {"narrator_category":"narrator_male","pool":{"narrator_male":{"voice_id":"59cb5986671546eaa6ca8ae6f29f6d22","label":"旁白"}}}
        """.trimIndent()
        val error = assertThrows(IllegalArgumentException::class.java) { VoicePool.parse(tooSmall) }
        assertTrue("错误信息应说明类别数不足：${error.message}", error.message.orEmpty().contains("63"))
    }

    @Test
    fun parse_rejects_missing_narrator_category() {
        // 首个 "narrator_male" 是 narrator_category 的值；改成池中不存在的类别。
        val raw = fixtureJson()
        val invalid = raw.replaceFirst("\"narrator_male\"", "\"narrator_female\"")
        assertTrue("fixture 预期以 narrator_category 开头", invalid != raw)
        val error = assertThrows(IllegalArgumentException::class.java) { VoicePool.parse(invalid) }
        assertTrue(
            "错误信息应指出缺失的旁白类别：${error.message}",
            error.message.orEmpty().contains("narrator_female"),
        )
    }

    @Test
    fun parse_rejects_blank_voice_id() {
        val invalid = fixtureJson().replaceFirst(
            Regex("\"voice_id\"\\s*:\\s*\"[0-9a-f]{32}\""),
            "\"voice_id\": \"\"",
        )
        val error = assertThrows(IllegalArgumentException::class.java) { VoicePool.parse(invalid) }
        assertTrue("错误信息应指出空 voice_id：${error.message}", error.message.orEmpty().contains("voice_id"))
    }
}
