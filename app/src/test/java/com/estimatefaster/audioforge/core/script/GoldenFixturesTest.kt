package com.estimatefaster.audioforge.core.script

import com.estimatefaster.audioforge.TestFixtures
import com.estimatefaster.audioforge.core.ingest.ChapterSplitter
import com.estimatefaster.audioforge.core.model.Gender
import com.estimatefaster.audioforge.core.model.Importance
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-12：用 `app/src/test/resources/golden/` 的冻结快照跑跨模块 golden 断言。
 *
 * 覆盖 sanitizer（strip/split）、coverage（3 条）、voice pool（63 条）、
 * chapter splitter（上游 sample_novel 2 章）。全部为纯 JVM 测试：
 * 不加载 Android Framework、不用 Robolectric、不访问网络。
 */
class GoldenFixturesTest {

    private val sanitizer = ScriptSanitizer()
    private val coverageChecker = CoverageChecker(sanitizer)
    private val chapterSplitter = ChapterSplitter()

    @Test
    fun sanitizer_strip_speech_tail_matches_golden() {
        val vectors = TestFixtures.goldenArray("sanitizer.json", "strip_speech_tail")
        assertTrue(
            "strip_speech_tail golden 至少 15 条，实际 ${vectors.size}",
            vectors.size >= 15,
        )
        vectors.forEachIndexed { index, element ->
            val vector = element.jsonObject
            val input = vector.getValue("in").jsonPrimitive.content
            val expected = vector.getValue("out").jsonPrimitive.content
            assertEquals(
                "strip_speech_tail[$index] 输入：$input",
                expected,
                sanitizer.stripSpeechTail(input),
            )
        }
    }

    @Test
    fun sanitizer_split_long_matches_golden() {
        val vectors = TestFixtures.goldenArray("sanitizer.json", "split_long")
        assertTrue(
            "split_long golden 至少 4 条，实际 ${vectors.size}",
            vectors.size >= 4,
        )
        vectors.forEachIndexed { index, element ->
            val vector = element.jsonObject
            val text = vector.getValue("text").jsonPrimitive.content
            val limit = vector.getValue("limit").jsonPrimitive.int
            val expected = TestFixtures.strings(vector.getValue("out").jsonArray)
            assertEquals(
                "split_long[$index] text=$text limit=$limit",
                expected,
                sanitizer.splitLong(text, limit),
            )
        }
    }

    @Test
    fun coverage_matches_golden() {
        val root = TestFixtures.goldenJson("coverage.json")
        val threshold = root.getValue("threshold").jsonPrimitive.double
        assertEquals("golden 覆盖率阈值", CoverageChecker.DEFAULT_MIN_COVERAGE, threshold, 0.0)

        val vectors = root.getValue("vectors").jsonArray
        assertEquals("coverage golden 固定 3 条", 3, vectors.size)
        vectors.forEachIndexed { index, element ->
            val vector = element.jsonObject
            val raw = vector.getValue("raw").jsonPrimitive.content
            val segments = TestFixtures.segments(vector.getValue("segments").jsonArray)
            val expected = vector.getValue("expect").jsonObject
            val expectedCoverage = expected.getValue("coverage").jsonPrimitive.double
            val expectedMissing = TestFixtures.strings(expected.getValue("missing").jsonArray)

            val result = coverageChecker.check(raw, segments)
            assertEquals("coverage[$index] raw=$raw", expectedCoverage, result.coverage, 1e-6)
            assertEquals("missing[$index] raw=$raw", expectedMissing, result.missing)
        }
    }

    @Test
    fun voice_pool_matches_golden_snapshot() {
        val golden = TestFixtures.loadVoicePool()
        val fixture = TestFixtures.loadVoicePool("voice-pool.json")

        assertEquals("golden 音色数", 63, golden.size)
        assertEquals("fixture 音色数", 63, fixture.size)
        assertEquals("narrator_male", golden.narratorCategory)
        assertEquals("golden 与 fixture 的 key 集合必须一致", golden.keys, fixture.keys)
        golden.keys.forEach { key ->
            assertEquals("类别 $key 的 golden 快照不一致", golden.get(key), fixture.get(key))
        }

        // 锁定契约：旁白/男配 voice_id、唯一 speed、8 条 note。
        assertEquals("59cb5986671546eaa6ca8ae6f29f6d22", golden.voiceIdFor("narrator_male"))
        assertEquals("3f62b49f697a4c0da3ee5cfd91d896b4", golden.voiceIdFor("male_brother"))
        assertEquals(1.15, golden.speedFor("male_brother") ?: 0.0, 1e-9)
        assertEquals(8, golden.keys.count { golden.get(it)?.note != null })

        // 旁白不参与 LLM 选角，note 不进提示词。
        val llmPool = golden.poolForLlm()
        assertEquals(62, llmPool.lines().size)
        assertFalse(llmPool.contains("- narrator_male:"))
        assertFalse("note 不应进 LLM 提示词", llmPool.contains("样音"))

        // 与 Python 一致的兜底映射，且每个兜底类别都真实存在。
        assertEquals("female_mature", golden.fallbackCategory(Gender.Female, Importance.Minor))
        assertEquals("male_gentle_young", golden.fallbackCategory(Gender.Male, Importance.Supporting))
        Gender.entries.forEach { gender ->
            Importance.entries.forEach { importance ->
                val category = golden.fallbackCategory(gender, importance)
                assertTrue("$gender/$importance 兜底类别 $category 不在池中", golden.valid(category))
            }
        }
    }

    @Test
    fun chapter_splitter_matches_golden_snapshot() {
        val root = TestFixtures.goldenJson("chapters.json")
        val expectedChapters = root.getValue("chapters").jsonArray
        val chapters = chapterSplitter.split(TestFixtures.readText("sample_novel.txt"))

        assertEquals("上游 sample_novel 的章节数", expectedChapters.size, chapters.size)
        expectedChapters.forEachIndexed { index, element ->
            val expected = element.jsonObject
            val chapter = chapters[index]
            assertEquals(
                "chapter[$index].idx",
                expected.getValue("idx").jsonPrimitive.int,
                chapter.idx,
            )
            assertEquals(
                "chapter[$index].title",
                expected.getValue("title").jsonPrimitive.content,
                chapter.title,
            )
            assertEquals(
                "chapter[$index].chars",
                expected.getValue("chars").jsonPrimitive.int,
                chapter.text.length,
            )
            assertEquals(
                "chapter[$index].text",
                expected.getValue("text").jsonPrimitive.content,
                chapter.text,
            )
            assertEquals(
                "chapter[$index].sha256",
                expected.getValue("sha256").jsonPrimitive.content,
                TestFixtures.sha256(chapter.text),
            )
        }
    }
}
