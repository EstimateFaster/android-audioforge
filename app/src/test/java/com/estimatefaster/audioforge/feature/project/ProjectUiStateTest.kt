package com.estimatefaster.audioforge.feature.project

import com.estimatefaster.audioforge.data.db.ChapterBrief
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.data.db.ProjectSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4-05：项目详情 UI 状态的纯 JVM 测试。
 *
 * 不依赖 Android/Robolectric，也不启动协程：只验证 [ChapterStatsUi] /
 * [WarningSummaryUi] / [CharacterStripUi] / [buildProjectUiState] 的映射规则。
 */
class ProjectUiStateTest {

    // ------------------------------------------------------------------
    // 章节统计
    // ------------------------------------------------------------------

    @Test
    fun chapter_stats_count_done_running_error_and_pending() {
        val stats = ChapterStatsUi.from(
            listOf(
                chapter(1, parse = "done", audio = "done", segCount = 10, segDone = 10),
                chapter(2, parse = "done", audio = "running", segCount = 10, segDone = 4),
                chapter(3, parse = "error", audio = "pending"),
                chapter(4, parse = "pending", audio = "error"),
                chapter(5),
            ),
        )

        assertEquals(5, stats.total)
        assertEquals(1, stats.done)
        assertEquals(1, stats.running)
        assertEquals(2, stats.error)
        assertEquals(1, stats.pending)
        assertEquals(
            "四组统计必须互斥且覆盖全部章节",
            stats.total,
            stats.done + stats.running + stats.error + stats.pending,
        )

        assertEquals(2, stats.parsed)
        assertEquals(0, stats.parseRunning)
        assertEquals(1, stats.parseError)
        assertEquals(1, stats.audioDone)
        assertEquals(1, stats.audioRunning)
        assertEquals(1, stats.audioError)
        assertEquals(20, stats.segmentsTotal)
        assertEquals(14, stats.segmentsDone)
    }

    @Test
    fun chapter_stats_error_wins_over_running_and_done() {
        val stats = ChapterStatsUi.from(
            listOf(
                chapter(1, parse = "running", audio = "done"),
                chapter(2, parse = "error", audio = "done"),
            ),
        )

        assertEquals(0, stats.done)
        assertEquals(1, stats.running)
        assertEquals(1, stats.error)
        assertEquals(0, stats.pending)
    }

    @Test
    fun chapter_stats_percent_rounds_and_handles_empty_and_complete() {
        val twoOfThree = ChapterStatsUi.from(
            listOf(
                chapter(1, parse = "done", audio = "done"),
                chapter(2, parse = "done", audio = "done"),
                chapter(3, parse = "done", audio = "pending"),
            ),
        )
        assertEquals(2f / 3f, twoOfThree.progress, 0.0001f)
        assertEquals(67, twoOfThree.percent)

        assertEquals(0f, ChapterStatsUi.EMPTY.progress, 0f)
        assertEquals(0, ChapterStatsUi.EMPTY.percent)

        val complete = ChapterStatsUi.from(
            listOf(
                chapter(1, parse = "done", audio = "done"),
                chapter(2, parse = "done", audio = "done"),
            ),
        )
        assertEquals(1f, complete.progress, 0f)
        assertEquals(100, complete.percent)
    }

    @Test
    fun chapter_stats_segment_counters_are_clamped_per_chapter() {
        val stats = ChapterStatsUi.from(
            listOf(
                chapter(1, parse = "done", audio = "done", segCount = -3, segDone = 5),
                chapter(2, parse = "done", audio = "done", segCount = 4, segDone = 7),
            ),
        )

        assertEquals(4, stats.segmentsTotal)
        assertEquals(4, stats.segmentsDone)
    }

    @Test
    fun chapter_stats_aggregate_wire_statuses_are_status_pill_compatible() {
        val allDone = ChapterStatsUi.from(
            listOf(chapter(1, parse = "done", audio = "done")),
        )
        assertEquals("done", allDone.parseAggregateStatus())
        assertEquals("done", allDone.audioAggregateStatus())

        val mixed = ChapterStatsUi.from(
            listOf(
                chapter(1, parse = "done", audio = "running"),
                chapter(2, parse = "error", audio = "pending"),
            ),
        )
        assertEquals("error", mixed.parseAggregateStatus())
        assertEquals("running", mixed.audioAggregateStatus())

        assertEquals("pending", ChapterStatsUi.EMPTY.parseAggregateStatus())
        assertEquals("pending", ChapterStatsUi.EMPTY.audioAggregateStatus())
    }

    // ------------------------------------------------------------------
    // 人物截断
    // ------------------------------------------------------------------

    @Test
    fun character_strip_truncates_and_reports_hidden_count() {
        val characters = (1..10).map { character(it.toLong()) }

        val capped = CharacterStripUi.from(characters, limit = 8)
        assertEquals(8, capped.visible.size)
        assertEquals("role_1", capped.visible.first().roleId)
        assertEquals("role_8", capped.visible.last().roleId)
        assertEquals(2, capped.hiddenCount)

        val exact = CharacterStripUi.from(characters, limit = 10)
        assertEquals(10, exact.visible.size)
        assertEquals(0, exact.hiddenCount)

        val roomy = CharacterStripUi.from(characters, limit = 20)
        assertEquals(10, roomy.visible.size)
        assertEquals(0, roomy.hiddenCount)

        val zero = CharacterStripUi.from(characters, limit = 0)
        assertEquals(0, zero.visible.size)
        assertEquals(10, zero.hiddenCount)
    }

    @Test
    fun character_strip_maps_name_and_role_id() {
        val strip = CharacterStripUi.from(
            listOf(CharacterEntity(id = 7, novelId = 1, roleId = "role_0007", name = "林黛玉")),
            limit = 8,
        )

        assertEquals(1, strip.visible.size)
        assertEquals("role_0007", strip.visible.single().roleId)
        assertEquals("林黛玉", strip.visible.single().name)
        assertEquals(0, strip.hiddenCount)
    }

    // ------------------------------------------------------------------
    // 告警拼接
    // ------------------------------------------------------------------

    @Test
    fun warning_summary_joins_distinct_messages_and_counts_chapters() {
        val warnings = WarningSummaryUi.from(
            listOf(
                chapter(1, warn = "覆盖率 42%，低于阈值"),
                chapter(2),
                chapter(3, warn = "  说话人未识别  "),
                chapter(4, warn = "覆盖率 42%，低于阈值"),
                chapter(5, warn = "   "),
            ),
        )

        assertEquals(3, warnings.warningCount)
        assertEquals(listOf("覆盖率 42%，低于阈值", "说话人未识别"), warnings.samples)
        assertEquals("覆盖率 42%，低于阈值；说话人未识别", warnings.summary)
        assertTrue(warnings.hasWarnings)
    }

    @Test
    fun warning_summary_caps_samples_but_keeps_total_count() {
        val warnings = WarningSummaryUi.from(
            (1..5).map { chapter(it.toLong(), warn = "告警 $it") },
            maxSamples = 2,
        )

        assertEquals(5, warnings.warningCount)
        assertEquals(listOf("告警 1", "告警 2"), warnings.samples)
        assertEquals("告警 1；告警 2", warnings.summary)
    }

    @Test
    fun warning_summary_is_empty_without_messages() {
        assertFalse(WarningSummaryUi.EMPTY.hasWarnings)
        assertEquals("", WarningSummaryUi.EMPTY.summary)
        assertEquals(0, WarningSummaryUi.from(emptyList()).warningCount)
    }

    // ------------------------------------------------------------------
    // 状态映射：空项目 / 缺失项目 / 头部与人物
    // ------------------------------------------------------------------

    @Test
    fun empty_project_maps_to_empty_state() {
        val state = buildProjectUiState(
            novelId = 1L,
            projects = listOf(project()),
            chapters = emptyList(),
            characters = emptyList(),
        )

        assertFalse(state.loading)
        assertNull(state.loadError)
        assertFalse(state.isMissing)
        assertTrue(state.isEmpty)
        assertFalse(state.hasChapters)
        assertEquals(0, state.chapterStats.total)
        assertEquals(0, state.chapterStats.percent)
        assertEquals(1L, state.project?.id)
        assertEquals("测试小说", state.project?.title)
        assertTrue(state.characters.isEmpty())
        assertEquals(0, state.hiddenCharacterCount)
    }

    @Test
    fun missing_project_is_reported_separately_from_empty() {
        val state = buildProjectUiState(
            novelId = 99L,
            projects = listOf(project(id = 1L)),
            chapters = emptyList(),
            characters = emptyList(),
        )

        assertFalse(state.loading)
        assertNull(state.loadError)
        assertTrue(state.isMissing)
        assertFalse(state.isEmpty)
        assertFalse(state.hasChapters)
        assertNull(state.project)
    }

    @Test
    fun default_state_is_loading_and_not_empty_or_missing() {
        val state = ProjectUiState()

        assertTrue(state.loading)
        assertFalse(state.isEmpty)
        assertFalse(state.isMissing)
        assertFalse(state.hasChapters)
    }

    @Test
    fun state_maps_header_chapter_stats_warnings_and_character_limit() {
        val characters = (1..10).map { character(it.toLong()) }
        val state = buildProjectUiState(
            novelId = 1L,
            projects = listOf(project(charCount = 12_345)),
            chapters = listOf(
                chapter(1, parse = "done", audio = "done", warn = "覆盖率 80%"),
                chapter(2, parse = "done", audio = "running"),
            ),
            characters = characters,
            characterLimit = 4,
        )

        assertFalse(state.loading)
        assertTrue(state.hasChapters)
        assertFalse(state.isEmpty)

        val header = state.project
        assertEquals(1L, header?.id)
        assertEquals("测试小说", header?.title)
        assertEquals("测试小说.txt", header?.sourceName)
        assertEquals(12_345, header?.charCount)

        assertEquals(2, state.chapterStats.total)
        assertEquals(1, state.chapterStats.done)
        assertEquals(1, state.chapterStats.running)
        assertEquals(1, state.warnings.warningCount)
        assertEquals("覆盖率 80%", state.warnings.summary)

        assertEquals(4, state.characters.size)
        assertEquals(6, state.hiddenCharacterCount)
    }

    // ------------------------------------------------------------------
    // Fixtures（纯数据，不触发 Android）
    // ------------------------------------------------------------------

    private fun project(
        id: Long = 1L,
        title: String = "测试小说",
        charCount: Int = 1_234,
    ): ProjectSummary = ProjectSummary(
        id = id,
        title = title,
        sourceName = "$title.txt",
        charCount = charCount,
        createdAt = 0L,
        lastOpenedAt = null,
        chapterCount = 0,
        chapterDoneCount = 0,
        segmentCount = 0,
        segmentDoneCount = 0,
    )

    private fun chapter(
        id: Long,
        parse: String = "pending",
        audio: String = "pending",
        warn: String? = null,
        segCount: Int = 0,
        segDone: Int = 0,
    ): ChapterBrief = ChapterBrief(
        id = id,
        idx = (id - 1).toInt(),
        title = "第${id}章",
        parseStatus = parse,
        audioStatus = audio,
        parseWarn = warn,
        segCount = segCount,
        segDone = segDone,
    )

    private fun character(
        id: Long,
        name: String = "角色$id",
    ): CharacterEntity = CharacterEntity(
        id = id,
        novelId = 1L,
        roleId = "role_$id",
        name = name,
    )
}
