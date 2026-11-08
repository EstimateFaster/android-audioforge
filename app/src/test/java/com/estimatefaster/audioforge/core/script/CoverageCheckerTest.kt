package com.estimatefaster.audioforge.core.script

import com.estimatefaster.audioforge.core.model.Emotion
import com.estimatefaster.audioforge.core.model.NARRATOR_ROLE_ID
import com.estimatefaster.audioforge.core.model.ScriptSegment
import com.estimatefaster.audioforge.core.model.SegmentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-06：fixture 三条 + 上游 `tests/test_analyzer.py` 覆盖率用例。 */
class CoverageCheckerTest {

    private val checker = CoverageChecker()
    private val fixture: JsonObject by lazy {
        Json.parseToJsonElement(readResource("porting-vectors.json")).jsonObject
    }

    @Test
    fun fixture_coverage_vectors_match_exactly() {
        val vectors = fixture.getValue("coverage").jsonArray
        assertEquals(3, vectors.size)
        for (vector in vectors) {
            val obj = vector.jsonObject
            val raw = obj.getValue("raw").jsonPrimitive.content
            val segments = obj.getValue("segments").jsonArray.map { item ->
                val seg = item.jsonObject
                val type = SegmentType.fromWire(seg.getValue("type").jsonPrimitive.content)
                segment(type, seg.getValue("text").jsonPrimitive.content)
            }
            val expect = obj.getValue("expect").jsonObject
            val expectedCoverage = expect.getValue("coverage").jsonPrimitive.double
            val expectedMissing = expect.getValue("missing").jsonArray.map { it.jsonPrimitive.content }

            val result = checker.check(raw, segments)
            assertEquals("coverage raw=$raw", expectedCoverage, result.coverage, 1e-6)
            assertEquals("missing raw=$raw", expectedMissing, result.missing)
        }
    }

    @Test
    fun full_text_is_fully_covered() {
        val result = checker.check("他抬头看向远方。", listOf(segment(SegmentType.Narration, "他抬头看向远方。")))
        assertEquals(1.0, result.coverage, 1e-9)
        assertTrue(result.missing.isEmpty())
    }

    @Test
    fun quotes_and_whitespace_are_ignored() {
        val result = checker.check(
            "他说道：“我们走吧。”",
            listOf(
                segment(SegmentType.Narration, "他说道："),
                segment(SegmentType.Dialogue, "我们走吧。"),
            ),
        )
        assertEquals(1.0, result.coverage, 1e-9)
        assertTrue(result.missing.isEmpty())
    }

    @Test
    fun stripped_guidance_is_not_counted_as_missing() {
        val result = checker.check(
            "他淡淡道：“我们走吧。”",
            listOf(segment(SegmentType.Dialogue, "我们走吧。")),
        )
        assertEquals(1.0, result.coverage, 1e-9)
        assertTrue(result.missing.isEmpty())
    }

    @Test
    fun dropped_action_is_detected() {
        val raw = "韩立三叔一见这人，立刻恭恭敬敬的上前施了一个礼：“王护法，您老人家怎么亲自带人来了？”"
        val result = checker.check(
            raw,
            listOf(segment(SegmentType.Dialogue, "王护法，您老人家怎么亲自带人来了？")),
        )
        assertTrue("coverage=${result.coverage}", result.coverage < 0.9)
        assertTrue(result.missing.any { "施了一个礼" in it })
    }

    @Test
    fun missing_for_repair_drops_pure_guidance() {
        assertEquals(
            emptyList<String>(),
            checker.missingForRepair(listOf("他淡淡道：", "和蔼的对他说道。", "阿甲")),
        )
    }

    @Test
    fun missing_for_repair_keeps_action() {
        val action = "韩立三叔一见这人，立刻恭恭敬敬的上前施了一个礼："
        assertEquals(listOf(action), checker.missingForRepair(listOf(action)))
    }

    @Test
    fun missing_for_repair_respects_limit() {
        val missing = (1..10).map { "缺失段落第${it}句。" }
        assertEquals(6, checker.missingForRepair(missing).size)
        assertEquals(3, checker.missingForRepair(missing, limit = 3).size)
    }

    @Test
    fun is_expected_drop_matches_python() {
        assertTrue(checker.isExpectedDrop(""))
        assertTrue(checker.isExpectedDrop("   "))
        assertTrue(checker.isExpectedDrop("他淡淡道："))
        assertTrue(checker.isExpectedDrop("阿甲"))
        assertFalse(checker.isExpectedDrop("远处传来钟声。"))
        assertFalse(checker.isExpectedDrop("很不错的描写"))
    }

    @Test
    fun norm_text_strips_whitespace_and_quotes() {
        assertEquals("他说道：好", checker.normText(" 他 说道：“ 好 ”\n"))
        assertEquals("", checker.normText("“”「」『』 \t\n"))
    }

    @Test
    fun empty_raw_is_fully_covered() {
        val result = checker.check("", emptyList())
        assertEquals(1.0, result.coverage, 1e-9)
        assertTrue(result.missing.isEmpty())
    }

    private fun segment(type: SegmentType, text: String): ScriptSegment =
        ScriptSegment(
            type = type,
            speaker = if (type == SegmentType.Dialogue) "role_0001" else NARRATOR_ROLE_ID,
            emotion = Emotion.Neutral,
            text = text,
        )

    private fun readResource(name: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream(name),
    ) { "缺少测试资源 $name" }
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }
}
