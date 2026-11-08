package com.estimatefaster.audioforge.feature.projects

import com.estimatefaster.audioforge.data.db.ProjectSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4-03：项目库 UI 状态的纯 JVM 测试。
 *
 * 不依赖 Android/Robolectric、不启动协程、不碰 Room：只验证
 * [filterProjectCards] / [formatCharCount] / [ProjectSummary.toProjectCardUi] /
 * [buildProjectLibraryUiState] 的纯函数规则。
 */
class ProjectLibraryUiStateTest {

    // ------------------------------------------------------------------
    // 搜索过滤
    // ------------------------------------------------------------------

    @Test
    fun blank_query_returns_all_projects_in_repository_order() {
        val state = buildProjectLibraryUiState(
            summaries = listOf(
                summary(id = 1L, title = "长夜将明"),
                summary(id = 2L, title = "Nightfall"),
            ),
            query = "   ",
        )

        assertEquals(listOf(1L, 2L), state.projects.map { it.id })
        assertTrue(state.hasResults)
        assertFalse(state.isNoSearchResult)
    }

    @Test
    fun search_trims_query_and_ignores_case() {
        val state = buildProjectLibraryUiState(
            summaries = listOf(
                summary(id = 1L, title = "Nightfall"),
                summary(id = 2L, title = "长夜将明"),
            ),
            query = "  nightFALL ",
        )

        assertEquals(listOf(1L), state.projects.map { it.id })
        assertEquals(2, state.totalProjectCount)
    }

    @Test
    fun search_matches_chinese_title_substring() {
        val cards = listOf(
            summary(id = 1L, title = "长夜将明").toProjectCardUi(),
            summary(id = 2L, title = "白夜行").toProjectCardUi(),
        )

        assertEquals(listOf(1L), filterProjectCards(cards, "夜将").map { it.id })
        assertEquals(emptyList<ProjectCardUi>(), filterProjectCards(cards, "不存在"))
    }

    @Test
    fun filtering_never_reorders_last_opened_or_created_order() {
        // 仓库 SQL 已按 COALESCE(lastOpenedAt, createdAt) DESC, id DESC 排序；
        // 这里按该顺序喂入，验证过滤只做子集、不重新排序。
        val newestOpened = summary(id = 1L, title = "最近阅读", createdAt = 10L, lastOpenedAt = 300L)
        val olderOpened = summary(id = 2L, title = "稍早阅读", createdAt = 20L, lastOpenedAt = 200L)
        val neverOpened = summary(id = 3L, title = "从未打开", createdAt = 100L, lastOpenedAt = null)

        val all = buildProjectLibraryUiState(
            summaries = listOf(newestOpened, olderOpened, neverOpened),
        )
        assertEquals(listOf(1L, 2L, 3L), all.projects.map { it.id })

        val filtered = buildProjectLibraryUiState(
            summaries = listOf(newestOpened, olderOpened, neverOpened),
            query = "阅读",
        )
        assertEquals(listOf(1L, 2L), filtered.projects.map { it.id })
    }

    // ------------------------------------------------------------------
    // 空库 / 无匹配 / 加载 / 错误
    // ------------------------------------------------------------------

    @Test
    fun empty_library_is_empty_but_not_a_search_miss() {
        val state = buildProjectLibraryUiState(emptyList())

        assertFalse(state.loading)
        assertTrue(state.isEmpty)
        assertFalse(state.isNoSearchResult)
        assertFalse(state.hasResults)
        assertTrue(state.projects.isEmpty())
        assertEquals(0, state.totalProjectCount)
    }

    @Test
    fun search_miss_is_distinct_from_empty_library() {
        val state = buildProjectLibraryUiState(
            summaries = listOf(summary(id = 1L, title = "长夜将明")),
            query = "白夜",
        )

        assertFalse(state.isEmpty)
        assertTrue(state.isNoSearchResult)
        assertFalse(state.hasResults)
        assertTrue(state.projects.isEmpty())
        assertEquals(1, state.totalProjectCount)
    }

    @Test
    fun loading_state_exposes_no_content_flags() {
        val state = ProjectLibraryUiState()

        assertTrue(state.loading)
        assertFalse(state.isEmpty)
        assertFalse(state.isNoSearchResult)
        assertFalse(state.hasResults)
        assertTrue(state.projects.isEmpty())
    }

    @Test
    fun error_state_exposes_no_content_flags() {
        val state = ProjectLibraryUiState(
            loading = false,
            loadError = "database is locked",
        )

        assertFalse(state.loading)
        assertFalse(state.isEmpty)
        assertFalse(state.isNoSearchResult)
        assertFalse(state.hasResults)
        assertEquals("database is locked", state.loadError)
    }

    // ------------------------------------------------------------------
    // 项目卡映射
    // ------------------------------------------------------------------

    @Test
    fun card_maps_progress_stats_and_metadata_from_project_summary() {
        val card = summary(
            id = 7L,
            title = "长夜将明",
            sourceName = "long-night.txt",
            charCount = 156_000,
            createdAt = 100L,
            lastOpenedAt = 300L,
            chapterCount = 5,
            chapterDoneCount = 2,
            segmentCount = 40,
            segmentDoneCount = 12,
        ).toProjectCardUi()

        assertEquals(7L, card.id)
        assertEquals("长夜将明", card.title)
        assertEquals("long-night.txt", card.sourceName)
        assertEquals("156,000", card.charCountText)
        assertEquals(100L, card.createdAt)
        assertEquals(300L, card.lastOpenedAt)
        assertEquals(5, card.chapterCount)
        assertEquals(2, card.chapterDoneCount)
        assertEquals(40, card.segmentCount)
        assertEquals(12, card.segmentDoneCount)
        assertEquals(0.4f, card.progress, 0.0001f)
        assertEquals(40, card.percent)
        assertEquals("running", card.audioStatus)
    }

    @Test
    fun card_progress_handles_empty_pending_and_complete_projects() {
        val empty = summary(id = 1L, title = "空项目", chapterCount = 0).toProjectCardUi()
        assertEquals(0f, empty.progress, 0f)
        assertEquals(0, empty.percent)
        assertEquals("pending", empty.audioStatus)

        val pending = summary(id = 2L, title = "未开始", chapterCount = 3).toProjectCardUi()
        assertEquals(0f, pending.progress, 0f)
        assertEquals("pending", pending.audioStatus)

        val complete = summary(
            id = 3L,
            title = "已完成",
            chapterCount = 3,
            chapterDoneCount = 3,
        ).toProjectCardUi()
        assertEquals(1f, complete.progress, 0f)
        assertEquals(100, complete.percent)
        assertEquals("done", complete.audioStatus)
    }

    @Test
    fun card_clamps_inconsistent_aggregate_counts() {
        val card = summary(
            id = 1L,
            title = "脏数据",
            chapterCount = 2,
            chapterDoneCount = 99,
            segmentCount = 5,
            segmentDoneCount = 99,
        ).toProjectCardUi()

        assertEquals(2, card.chapterDoneCount)
        assertEquals(5, card.segmentDoneCount)
        assertEquals(1f, card.progress, 0f)
        assertEquals("done", card.audioStatus)

        val negative = summary(
            id = 2L,
            title = "负数",
            chapterCount = -3,
            chapterDoneCount = -1,
            segmentCount = -4,
            segmentDoneCount = -2,
        ).toProjectCardUi()

        assertEquals(0, negative.chapterCount)
        assertEquals(0, negative.chapterDoneCount)
        assertEquals(0, negative.segmentCount)
        assertEquals(0, negative.segmentDoneCount)
        assertEquals(0f, negative.progress, 0f)
        assertEquals("pending", negative.audioStatus)
    }

    @Test
    fun char_count_uses_grouping_and_clamps_negative_values() {
        assertEquals("0", formatCharCount(-1))
        assertEquals("0", formatCharCount(0))
        assertEquals("999", formatCharCount(999))
        assertEquals("1,234", formatCharCount(1_234))
        assertEquals("156,000", formatCharCount(156_000))
    }

    // ------------------------------------------------------------------
    // 2000 章 / 2000 项
    // ------------------------------------------------------------------

    @Test
    fun two_thousand_chapter_card_uses_only_aggregate_fields() {
        val card = summary(
            id = 1L,
            title = "大长篇",
            chapterCount = 2_000,
            chapterDoneCount = 1_500,
            segmentCount = 24_000,
            segmentDoneCount = 18_000,
        ).toProjectCardUi()

        assertEquals(2_000, card.chapterCount)
        assertEquals(1_500, card.chapterDoneCount)
        assertEquals(24_000, card.segmentCount)
        assertEquals(18_000, card.segmentDoneCount)
        assertEquals(0.75f, card.progress, 0.0001f)
        assertEquals(75, card.percent)
        assertEquals("running", card.audioStatus)
    }

    @Test
    fun two_thousand_projects_have_unique_stable_keys() {
        val summaries = (1L..2_000L).map { id ->
            summary(
                id = id,
                title = if (id % 2L == 0L) "长夜 $id" else "Nightfall $id",
                charCount = id.toInt() * 10,
                chapterCount = 2_000,
                chapterDoneCount = (id % 2_001L).toInt(),
                segmentCount = 20_000,
                segmentDoneCount = 1_000,
            )
        }

        val state = buildProjectLibraryUiState(summaries)

        assertEquals(2_000, state.projects.size)
        assertEquals(2_000, state.projects.map { it.stableKey }.toSet().size)
        assertEquals(2_000, state.projects.map { it.id }.toSet().size)
        assertEquals("project:1", state.projects.first().stableKey)

        val filtered = buildProjectLibraryUiState(summaries, "nightfall").projects
        assertTrue(filtered.isNotEmpty())
        assertTrue(filtered.all { it.title.startsWith("Nightfall") })
        assertEquals(filtered.size, filtered.map { it.stableKey }.toSet().size)
    }

    // ------------------------------------------------------------------
    // 测试夹具
    // ------------------------------------------------------------------

    private fun summary(
        id: Long,
        title: String,
        sourceName: String = "$title.txt",
        charCount: Int = 1_000,
        createdAt: Long = 0L,
        lastOpenedAt: Long? = null,
        chapterCount: Int = 0,
        chapterDoneCount: Int = 0,
        segmentCount: Int = 0,
        segmentDoneCount: Int = 0,
    ): ProjectSummary = ProjectSummary(
        id = id,
        title = title,
        sourceName = sourceName,
        charCount = charCount,
        createdAt = createdAt,
        lastOpenedAt = lastOpenedAt,
        chapterCount = chapterCount,
        chapterDoneCount = chapterDoneCount,
        segmentCount = segmentCount,
        segmentDoneCount = segmentDoneCount,
    )
}
