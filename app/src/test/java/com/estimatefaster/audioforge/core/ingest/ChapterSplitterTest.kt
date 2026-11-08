package com.estimatefaster.audioforge.core.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-04：逐行对照 Python `core/ingest.py::load_novel`。 */
class ChapterSplitterTest {

    private val splitter = ChapterSplitter()

    private fun sample(): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("sample_novel.txt"),
    ) { "缺少测试资源 sample_novel.txt" }
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }

    @Test
    fun fixture_sample_splits_into_expected_chapters() {
        val chapters = splitter.split(sample())
        assertEquals(2, chapters.size)
        assertEquals(listOf(0, 1), chapters.map { it.idx })
        assertEquals("第一章 山边小村", chapters[0].title)
        assertEquals("第二章 七玄门", chapters[1].title)
        assertEquals(145, chapters[0].text.length)
        assertEquals(664, chapters[1].text.length)
        assertTrue(chapters[0].text.startsWith("夜色渐渐笼罩了小山村"))
        assertTrue(chapters[1].text.startsWith("这是一个小城"))
    }

    @Test
    fun no_title_returns_single_chapter() {
        val text = "只是一段没有章节标题的文字。\n第二行。"
        val chapters = splitter.split(text)
        assertEquals(1, chapters.size)
        assertEquals(0, chapters[0].idx)
        assertEquals("全文", chapters[0].title)
        assertEquals(text, chapters[0].text)
    }

    @Test
    fun fake_title_without_body_is_skipped_and_idx_stays_continuous() {
        val text = "第一章 空标题\n\n第二章 有正文\n\n正文内容。\n\n第三章 另一章\n\n另一段。"
        val chapters = splitter.split(text)
        assertEquals(2, chapters.size)
        assertEquals(listOf(0, 1), chapters.map { it.idx })
        assertEquals("第二章 有正文", chapters[0].title)
        assertEquals("正文内容。", chapters[0].text)
        assertEquals("第三章 另一章", chapters[1].title)
    }

    @Test
    fun crlf_is_normalized_inside_body() {
        val lf = splitter.split(sample())
        val crlf = splitter.split(sample().replace("\n", "\r\n"))
        assertEquals(lf, crlf)
    }

    @Test
    fun long_line_is_not_a_title() {
        val longTitle = "第一章 " + "很长的标题".repeat(20)
        assertTrue(longTitle.length >= ChapterSplitter.MAX_TITLE_LENGTH)
        val text = "$longTitle\n\n正文内容。"
        val chapters = splitter.split(text)
        assertEquals(1, chapters.size)
        assertEquals("全文", chapters[0].title)
    }

    @Test
    fun supports_chinese_numerals_chapter_and_volume_forms() {
        val text = listOf(
            "第1章 起" to "甲。",
            "第一百二十三章 中" to "乙。",
            "Chapter 3 End" to "丙。",
            "第 2 卷 卷名" to "丁。",
            "第五节" to "戊。",
            "第4回" to "己。",
            "第5部" to "庚。",
        ).joinToString("\n\n") { (title, body) -> "$title\n\n$body" }

        val chapters = splitter.split(text)
        assertEquals(7, chapters.size)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), chapters.map { it.idx })
        assertEquals(
            listOf("第1章 起", "第一百二十三章 中", "Chapter 3 End", "第 2 卷 卷名", "第五节", "第4回", "第5部"),
            chapters.map { it.title },
        )
        assertEquals(listOf("甲。", "乙。", "丙。", "丁。", "戊。", "己。", "庚。"), chapters.map { it.text })
    }

    @Test
    fun content_before_first_title_is_dropped() {
        val text = "序言内容。\n\n第一章 开始\n\n正文。"
        val chapters = splitter.split(text)
        assertEquals(1, chapters.size)
        assertEquals("第一章 开始", chapters[0].title)
        assertEquals("正文。", chapters[0].text)
    }

    @Test
    fun empty_text_mirrors_python_single_empty_chapter() {
        val chapters = splitter.split("")
        assertEquals(1, chapters.size)
        assertEquals(Chapter(0, "全文", ""), chapters[0])
    }

    @Test
    fun only_fake_titles_return_no_chapters() {
        assertTrue(splitter.split("第一章\n\n第二章").isEmpty())
    }
}
