package com.estimatefaster.audioforge.core.script

import com.estimatefaster.audioforge.core.model.Emotion
import com.estimatefaster.audioforge.core.model.NARRATOR_ROLE_ID
import com.estimatefaster.audioforge.core.model.ScriptSegment
import com.estimatefaster.audioforge.core.model.SegmentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-05：逐条对照 fixture 与上游 `tests/test_analyzer.py`。 */
class ScriptSanitizerTest {

    private val sanitizer = ScriptSanitizer()

    private val fixture: JsonObject by lazy {
        Json.parseToJsonElement(readResource("porting-vectors.json")).jsonObject
    }

    // ---------- stripSpeechTail ----------

    @Test
    fun strip_speech_tail_matches_fixture() {
        val vectors = fixture.getValue("sanitizer").jsonObject
            .getValue("strip_speech_tail").jsonArray
        assertTrue("fixture 至少 15 条", vectors.size >= 15)
        for (vector in vectors) {
            val obj = vector.jsonObject
            val input = obj.getValue("in").jsonPrimitive.content
            val expected = obj.getValue("out").jsonPrimitive.content
            assertEquals("输入：$input", expected, sanitizer.stripSpeechTail(input))
        }
    }

    @Test
    fun strip_speech_tail_python_cases() {
        assertEquals("", sanitizer.stripSpeechTail("他淡淡道："))
        assertEquals("阿甲", sanitizer.stripSpeechTail("阿甲吃惊道。"))
        assertEquals("", sanitizer.stripSpeechTail("和蔼的对他说道。"))
        assertEquals("", sanitizer.stripSpeechTail("笑呵呵道："))
        assertEquals("", sanitizer.stripSpeechTail("自言自语道。"))
        assertEquals("", sanitizer.stripSpeechTail("他幽幽道："))
        assertEquals("", sanitizer.stripSpeechTail("缓缓的说"))
        assertEquals("", sanitizer.stripSpeechTail("有些恍然"))
        assertEquals("", sanitizer.stripSpeechTail("异口同声的"))
    }

    @Test
    fun strip_speech_tail_keeps_actions_and_real_words() {
        assertEquals("他轻轻一笑。", sanitizer.stripSpeechTail("他轻轻一笑。"))
        assertEquals("递出了自己的卡，", sanitizer.stripSpeechTail("递出了自己的卡，"))
        assertEquals("眉头一皱，", sanitizer.stripSpeechTail("眉头一皱，"))
        assertEquals("知道", sanitizer.stripSpeechTail("知道"))
        assertEquals("正道", sanitizer.stripSpeechTail("正道"))
        assertEquals("他猛地站起", sanitizer.stripSpeechTail("他猛地站起，怒道"))
    }

    @Test
    fun is_residue_matches_python() {
        assertTrue(sanitizer.isResidue("刘弟"))
        assertTrue(sanitizer.isResidue("他"))
        assertTrue(sanitizer.isResidue("知道"))
        assertFalse(sanitizer.isResidue("他轻轻一笑。"))
        assertFalse(sanitizer.isResidue("很不错的描写"))
    }

    // ---------- findQuoted ----------

    @Test
    fun find_quoted_handles_paired_and_single_sided_quotes() {
        val paired = sanitizer.findQuoted("他说：“你好。”之后")
        assertEquals("你好。", paired?.inner)

        val onlyLeft = sanitizer.findQuoted("他说：“你好。")
        assertEquals("你好。", onlyLeft?.inner)

        val onlyRight = sanitizer.findQuoted("你好。”")
        assertEquals("你好。", onlyRight?.inner)

        assertNull(sanitizer.findQuoted("没有引号的一段话"))

        val term = sanitizer.findQuoted("“七玄门”大比开始了")
        assertEquals("七玄门", term?.inner)
    }

    // ---------- splitMixed ----------

    @Test
    fun split_mixed_splits_leading_clause_and_dedupes() {
        val segments = listOf(
            dialogue("他淡淡道：“我们走吧。”"),
            narration("我们走吧。"),
        )
        val out = sanitizer.splitMixed(segments)
        assertEquals(listOf("我们走吧。"), out.map { it.text })
        assertEquals(listOf(SegmentType.Dialogue), out.map { it.type })
    }

    @Test
    fun split_mixed_keeps_term_quotes_in_dialogue() {
        val out = sanitizer.splitMixed(listOf(dialogue("“七玄门”大比开始了。")))
        assertEquals(listOf("“七玄门”大比开始了。"), out.map { it.text })
    }

    @Test
    fun split_mixed_strips_quoted_only_dialogue() {
        val out = sanitizer.splitMixed(listOf(dialogue("“你好。”")))
        assertEquals(listOf("你好。"), out.map { it.text })
    }

    @Test
    fun split_mixed_merges_adjacent_narration() {
        val out = sanitizer.splitMixed(
            listOf(
                narration("山门终年云雾缭绕"),
                narration("弟子们晨起练功。"),
            ),
        )
        assertEquals(1, out.size)
        assertEquals("山门终年云雾缭绕。弟子们晨起练功。", out[0].text)
    }

    @Test
    fun split_mixed_drops_guidance_residue_before_dialogue() {
        val out = sanitizer.splitMixed(
            listOf(
                narration("阿甲"),
                narration("他淡淡道："),
                dialogue("我们走吧。"),
            ),
        )
        // 与 Python 一致："阿甲" 后面不是台词（是另一段旁白），因此保留；"他淡淡道：" 紧邻台词被丢弃。
        assertEquals(listOf("阿甲", "我们走吧。"), out.map { it.text })
        assertEquals(listOf(SegmentType.Narration, SegmentType.Dialogue), out.map { it.type })
    }

    @Test
    fun split_mixed_keeps_action_before_dialogue() {
        val out = sanitizer.splitMixed(
            listOf(
                narration("他轻轻一笑。"),
                narration("递出了自己的卡，"),
                dialogue("给你。"),
            ),
        )
        // 相邻旁白会合并（< segment_max_chars），与 Python 一致。
        assertEquals(listOf("他轻轻一笑。递出了自己的卡", "给你。"), out.map { it.text })
    }

    @Test
    fun split_dialogue_mixed_extracts_action_and_dialogue() {
        val segment = dialogue(
            "韩立三叔一见这人，立刻恭恭敬敬的上前施了一个礼：“王护法，您老人家怎么亲自带人来了？”",
        )
        val out = sanitizer.splitDialogueMixed(segment)
        assertTrue(out != null)
        out!!
        assertEquals(2, out.size)
        assertEquals(SegmentType.Narration, out[0].type)
        assertEquals("韩立三叔一见这人，立刻恭恭敬敬的上前施了一个礼", out[0].text)
        assertEquals(SegmentType.Dialogue, out[1].type)
        assertEquals("王护法，您老人家怎么亲自带人来了？", out[1].text)
    }

    // ---------- splitLong ----------

    @Test
    fun split_long_matches_fixture() {
        val vectors = fixture.getValue("sanitizer").jsonObject
            .getValue("split_long").jsonArray
        assertTrue("fixture 至少 4 条", vectors.size >= 4)
        for (vector in vectors) {
            val obj = vector.jsonObject
            val text = obj.getValue("text").jsonPrimitive.content
            val limit = obj.getValue("limit").jsonPrimitive.int
            val expected = obj.getValue("out").jsonArray.map { it.jsonPrimitive.content }
            assertEquals("text=$text limit=$limit", expected, sanitizer.splitLong(text, limit))
        }
    }

    @Test
    fun split_long_python_cases() {
        assertEquals(listOf("短句。"), sanitizer.splitLong("短句。", 10))
        assertEquals(
            listOf("第一句。第二句。", "第三句。"),
            sanitizer.splitLong("第一句。第二句。第三句。", 8),
        )
        assertEquals(
            listOf("甲乙丙。", "丁戊己。", "庚辛壬。"),
            sanitizer.splitLong("甲乙丙。丁戊己。庚辛壬。", 6),
        )
        assertEquals(
            listOf("无标点的", "超长句子"),
            sanitizer.splitLong("无标点的超长句子", 4),
        )

        val text = "甲".repeat(25) + "。" + "乙".repeat(7)
        val parts = sanitizer.splitLong(text, 10)
        assertTrue(parts.all { it.length <= 10 })
        assertEquals(text, parts.joinToString(""))
    }

    // ---------- sanitize ----------

    @Test
    fun sanitize_drops_empty_and_splits_overlong() {
        val segments = listOf(
            narration("第一句。第二句。第三句。第四句。第五句。第六句。第七句。"),
            narration("   "),
        )
        val out = sanitizer.sanitize(segments, maxChars = 20)
        assertTrue(out.size > 1)
        assertTrue(out.all { it.text.length <= 20 })
        assertTrue(out.all { it.type == SegmentType.Narration })
    }

    @Test
    fun sanitize_keeps_type_speaker_and_emotion() {
        val out = sanitizer.sanitize(
            listOf(
                ScriptSegment(SegmentType.Dialogue, "role_0001", Emotion.Happy, "你好。"),
            ),
        )
        assertEquals(1, out.size)
        assertEquals(SegmentType.Dialogue, out[0].type)
        assertEquals("role_0001", out[0].speaker)
        assertEquals(Emotion.Happy, out[0].emotion)
        assertEquals("你好。", out[0].text)
    }

    @Test
    fun sanitize_does_not_mutate_input() {
        val input = narration("正常一句。")
        sanitizer.sanitize(listOf(input))
        assertEquals("正常一句。", input.text)
    }

    private fun dialogue(text: String): ScriptSegment =
        ScriptSegment(SegmentType.Dialogue, "role_0001", Emotion.Neutral, text)

    private fun narration(text: String): ScriptSegment =
        ScriptSegment(SegmentType.Narration, NARRATOR_ROLE_ID, Emotion.Neutral, text)

    private fun readResource(name: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream(name),
    ) { "缺少测试资源 $name" }
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }
}
