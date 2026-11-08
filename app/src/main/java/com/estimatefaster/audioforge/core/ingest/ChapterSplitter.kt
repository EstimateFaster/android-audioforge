package com.estimatefaster.audioforge.core.ingest

import java.util.regex.Pattern

/** 章节（`idx` 0-based，与 Python `core/ingest.py` 一致）。 */
data class Chapter(
    val idx: Int,
    val title: String,
    val text: String,
)

/**
 * 章节切分：逐行移植自 Python `core/ingest.py::load_novel`。
 *
 * - 标题正则 `第X章/卷/节/回/部` 或 `Chapter N`，忽略大小写 + Unicode 字符类；
 * - 只有 `line.strip().length < 50` 才算标题；
 * - 有标题无正文的假标题跳过；`idx` 按最终章节顺序重新连续编号；
 * - 没有标题时整本作为单章（title=「全文」）；首个标题前的内容丢弃。
 */
class ChapterSplitter {

    fun split(text: String): List<Chapter> {
        val lines = text.lines()
        val marks = lines.mapIndexedNotNull { index, line ->
            val trimmed = line.trim()
            if (trimmed.length < MAX_TITLE_LENGTH && CHAPTER_RE.matches(line)) {
                index to trimmed
            } else {
                null
            }
        }

        if (marks.isEmpty()) {
            return listOf(Chapter(idx = 0, title = FULL_TEXT_TITLE, text = text.trim()))
        }

        val chapters = mutableListOf<Chapter>()
        marks.forEachIndexed { markIndex, (start, title) ->
            val end = if (markIndex + 1 < marks.size) marks[markIndex + 1].first else lines.size
            val body = lines.subList(start + 1, end).joinToString("\n").trim()
            if (body.isNotEmpty()) {
                chapters += Chapter(idx = chapters.size, title = title, text = body)
            }
        }
        return chapters
    }

    companion object {
        const val FULL_TEXT_TITLE = "全文"
        const val MAX_TITLE_LENGTH = 50

        private const val CHAPTER_PATTERN =
            "^(?:第\\s*([零一二三四五六七八九十百千万两\\d]+)\\s*[章卷节回部]|Chapter\\s+(\\d+))\\s*(.*)$"

        private val CHAPTER_RE: Regex = Pattern.compile(
            CHAPTER_PATTERN,
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CHARACTER_CLASS,
        ).toRegex()
    }
}
