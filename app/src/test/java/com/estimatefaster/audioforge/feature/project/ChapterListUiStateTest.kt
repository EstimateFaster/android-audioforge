package com.estimatefaster.audioforge.feature.project

import com.estimatefaster.audioforge.data.db.ChapterBrief
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P4-07：章节列表 UI 状态 + 分页 ViewModel 的纯 JVM 测试。
 *
 * 不依赖 Android/Robolectric：映射、去重合并、筛选、hasMore 都是纯函数；
 * ViewModel 用 [StandardTestDispatcher] 替换 Main，注入 fake [ChapterPageSource]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChapterListUiStateTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------
    // 单行映射：序号 / 状态 / 告警 / 片段进度
    // ------------------------------------------------------------------

    @Test
    fun chapter_item_maps_number_statuses_warning_and_segment_progress() {
        val item = ChapterListItemUi.from(
            brief(
                id = 7,
                idx = 6,
                parse = "done",
                audio = "running",
                warn = "覆盖率 42%，低于阈值",
                segCount = 10,
                segDone = 4,
            ),
        )

        assertEquals(7L, item.key)
        assertEquals(7, item.number)
        assertEquals("第7章", item.title)
        assertEquals("done", item.parseStatus)
        assertTrue(item.hasWarning)
        assertFalse(item.isParseRunning)
        assertFalse(item.isParseFailed)
        assertFalse(item.isAudioDone)
        assertTrue(item.isIncomplete)
        assertEquals(4, item.segmentDone)
        assertEquals(10, item.segmentTotal)
        assertEquals(0.4f, item.segmentProgress, 0.0001f)
        assertTrue(item.hasSegments)
    }

    @Test
    fun chapter_item_blank_warning_is_not_a_warning() {
        assertFalse(ChapterListItemUi.from(brief(1, warn = null)).hasWarning)
        assertFalse(ChapterListItemUi.from(brief(2, warn = "")).hasWarning)
        assertFalse(ChapterListItemUi.from(brief(3, warn = "   ")).hasWarning)
        assertTrue(ChapterListItemUi.from(brief(4, warn = "有告警")).hasWarning)
    }

    @Test
    fun chapter_item_segment_counters_are_clamped_and_never_divide_by_zero() {
        val negativeCount = ChapterListItemUi.from(brief(1, segCount = -5, segDone = 3))
        assertEquals(0, negativeCount.segmentTotal)
        assertEquals(0, negativeCount.segmentDone)
        assertEquals(0f, negativeCount.segmentProgress, 0f)
        assertFalse(negativeCount.hasSegments)

        val overflowingDone = ChapterListItemUi.from(brief(2, segCount = 4, segDone = 9))
        assertEquals(4, overflowingDone.segmentTotal)
        assertEquals(4, overflowingDone.segmentDone)
        assertEquals(1f, overflowingDone.segmentProgress, 0f)
    }

    @Test
    fun chapter_item_number_is_clamped_for_invalid_index() {
        assertEquals(1, ChapterListItemUi.from(brief(1, idx = -3)).number)
        assertEquals(
            Int.MAX_VALUE,
            ChapterListItemUi.from(brief(2, idx = Int.MAX_VALUE)).number,
        )
    }

    @Test
    fun chapter_item_status_helpers_are_case_and_whitespace_insensitive() {
        val item = ChapterListItemUi.from(
            brief(1, parse = "  RUNNING ", audio = " Done "),
        )

        assertTrue(item.isParseRunning)
        assertTrue(item.isAudioDone)
        assertFalse(item.isParseFailed)
        assertFalse(item.isIncomplete)

        val failed = ChapterListItemUi.from(brief(2, parse = "Error", audio = "error"))
        assertTrue(failed.isParseFailed)
        assertFalse(failed.isAudioDone)
        assertTrue(failed.isIncomplete)
    }

    // ------------------------------------------------------------------
    // 分页合并 / hasMore
    // ------------------------------------------------------------------

    @Test
    fun merge_dedupes_by_id_keeps_first_position_and_uses_latest_values() {
        val existing = listOf(
            ChapterListItemUi.from(brief(1, title = "第一章")),
            ChapterListItemUi.from(brief(2, title = "第二章")),
        )
        val incoming = listOf(
            ChapterListItemUi.from(brief(2, title = "第二章（更新）", audio = "done")),
            ChapterListItemUi.from(brief(3, title = "第三章")),
        )

        val merged = mergeChapterItems(existing, incoming)

        assertEquals(listOf(1L, 2L, 3L), merged.map { it.id })
        assertEquals("第二章（更新）", merged[1].title)
        assertTrue(merged[1].isAudioDone)
        assertEquals(3, merged.size)
    }

    @Test
    fun merge_dedupes_within_a_single_page() {
        val incoming = listOf(
            ChapterListItemUi.from(brief(1, title = "旧")),
            ChapterListItemUi.from(brief(1, title = "新")),
        )

        val merged = mergeChapterItems(emptyList(), incoming)

        assertEquals(1, merged.size)
        assertEquals("新", merged.single().title)
    }

    @Test
    fun merge_handles_empty_inputs() {
        val existing = listOf(ChapterListItemUi.from(brief(1)))

        assertTrue(mergeChapterItems(emptyList(), emptyList()).isEmpty())
        assertEquals(existing, mergeChapterItems(existing, emptyList()))
        assertEquals(
            listOf(1L),
            mergeChapterItems(emptyList(), existing).map { it.id },
        )
    }

    @Test
    fun has_more_only_when_the_page_is_full() {
        assertTrue(hasMoreAfter(List(CHAPTER_PAGE_SIZE) { it }, CHAPTER_PAGE_SIZE))
        assertFalse(hasMoreAfter(List(CHAPTER_PAGE_SIZE - 1) { it }, CHAPTER_PAGE_SIZE))
        assertFalse(hasMoreAfter(emptyList<Any>(), CHAPTER_PAGE_SIZE))
        assertFalse(hasMoreAfter(List(3) { it }, pageSize = 0))
    }

    // ------------------------------------------------------------------
    // 状态筛选与标题搜索
    // ------------------------------------------------------------------

    @Test
    fun filter_parsing_matches_parse_running_only() {
        val items = listOf(
            item(1, parse = "running"),
            item(2, parse = "pending", audio = "running"),
            item(3, parse = "error"),
            item(4, parse = "done"),
        )

        assertEquals(
            listOf(1L),
            items.filter { it.matchesFilter(ChapterFilter.Parsing) }.map { it.id },
        )
    }

    @Test
    fun filter_parse_failed_matches_parse_error_only() {
        val items = listOf(
            item(1, parse = "error"),
            item(2, parse = "done", audio = "error"),
            item(3, parse = "failed"),
        )

        assertEquals(
            listOf(1L),
            items.filter { it.matchesFilter(ChapterFilter.ParseFailed) }.map { it.id },
        )
    }

    @Test
    fun filter_audio_done_matches_done_audio_status() {
        val items = listOf(
            item(1, audio = "done"),
            item(2, audio = "running"),
            item(3, audio = " DONE "),
            item(4, audio = "success"),
        )

        assertEquals(
            listOf(1L, 3L),
            items.filter { it.matchesFilter(ChapterFilter.AudioDone) }.map { it.id },
        )
    }

    @Test
    fun filter_incomplete_includes_pending_running_and_error_audio() {
        val items = listOf(
            item(1, audio = "done"),
            item(2, audio = "pending"),
            item(3, audio = "running"),
            item(4, audio = "error"),
        )

        assertEquals(
            listOf(2L, 3L, 4L),
            items.filter { it.matchesFilter(ChapterFilter.Incomplete) }.map { it.id },
        )
    }

    @Test
    fun filter_all_matches_everything() {
        val items = listOf(item(1), item(2, parse = "error"), item(3, audio = "done"))

        assertEquals(items, items.filter { it.matchesFilter(ChapterFilter.All) })
    }

    @Test
    fun search_matches_title_with_trim_and_case_insensitivity() {
        val items = listOf(
            item(1, title = "Chapter One"),
            item(2, title = "第二章 夜访"),
            item(3, title = "chapter two"),
        )

        assertEquals(
            listOf(1L, 3L),
            items.filter { it.matchesQuery("  CHAPTER ") }.map { it.id },
        )
        assertEquals(listOf(2L), items.filter { it.matchesQuery("夜访") }.map { it.id })
        assertEquals(items, items.filter { it.matchesQuery("   ") })
        assertTrue(items.filter { it.matchesQuery("不存在") }.isEmpty())
    }

    @Test
    fun matches_combines_query_and_filter() {
        val items = listOf(
            item(1, title = "第一章", parse = "error"),
            item(2, title = "第二章", parse = "error", audio = "done"),
            item(3, title = "第三章", parse = "done"),
        )

        assertEquals(
            listOf(1L),
            items.filter { it.matches("第一", ChapterFilter.ParseFailed) }.map { it.id },
        )
        assertTrue(items.filter { it.matches("第一", ChapterFilter.AudioDone) }.isEmpty())
    }

    // ------------------------------------------------------------------
    // 2000 条映射稳定
    // ------------------------------------------------------------------

    @Test
    fun mapping_2000_chapters_keeps_stable_keys_and_ordinals() {
        val items = (0 until 2000).map { index ->
            ChapterListItemUi.from(
                brief(
                    id = (index + 1).toLong(),
                    idx = index,
                    title = "第${index + 1}章",
                ),
            )
        }

        assertEquals(2000, items.size)
        assertEquals("key 必须唯一", 2000, items.map { it.key }.distinct().size)
        assertEquals(1, items.first().number)
        assertEquals(2000, items.last().number)
        items.forEachIndexed { index, item ->
            assertEquals((index + 1).toLong(), item.key)
            assertEquals(index + 1, item.number)
        }
        assertEquals(items.map { it.number }, items.map { it.number }.sorted())
        assertTrue(hasMoreAfter(items.take(CHAPTER_PAGE_SIZE), CHAPTER_PAGE_SIZE))
        assertFalse(hasMoreAfter(items, CHAPTER_PAGE_SIZE * 100))
    }

    // ------------------------------------------------------------------
    // 空态 / 加载 / 错误态
    // ------------------------------------------------------------------

    @Test
    fun default_state_is_initial_loading_and_not_empty() {
        val state = ChapterListUiState()

        assertTrue(state.loading)
        assertTrue(state.isInitialLoading)
        assertFalse(state.isEmpty)
        assertFalse(state.hasActiveFilters)
        assertFalse(state.canLoadMore)
        assertFalse(state.isComplete)
    }

    @Test
    fun empty_state_without_filters_is_empty_but_not_filtered() {
        val state = ChapterListUiState(loading = false)

        assertTrue(state.isEmpty)
        assertFalse(state.hasActiveFilters)
        assertFalse(state.isComplete)
        assertEquals(0, state.loadedCount)
    }

    @Test
    fun empty_state_with_search_or_filter_suggests_clearing() {
        val byQuery = ChapterListUiState(loading = false, query = "不存在")
        assertTrue(byQuery.isEmpty)
        assertTrue(byQuery.hasActiveFilters)

        val byFilter = ChapterListUiState(loading = false, filter = ChapterFilter.ParseFailed)
        assertTrue(byFilter.isEmpty)
        assertTrue(byFilter.hasActiveFilters)

        val whitespaceQuery = ChapterListUiState(loading = false, query = "   ")
        assertFalse(whitespaceQuery.hasActiveFilters)
    }

    @Test
    fun error_state_is_not_empty_and_not_complete() {
        val state = ChapterListUiState(loading = false, loadError = "database is locked")

        assertFalse(state.isInitialLoading)
        assertFalse(state.isEmpty)
        assertFalse(state.isComplete)
        assertNull(state.loadMoreError)
    }

    @Test
    fun complete_state_requires_rows_and_no_more_pages() {
        val rows = listOf(item(1))

        assertFalse(ChapterListUiState(loading = false, chapters = rows, hasMore = true).isComplete)
        assertFalse(ChapterListUiState(loading = false, chapters = emptyList(), hasMore = false).isComplete)
        assertTrue(ChapterListUiState(loading = false, chapters = rows, hasMore = false).isComplete)
    }

    @Test
    fun can_load_more_is_blocked_while_loading_or_after_an_error() {
        val base = ChapterListUiState(
            loading = false,
            chapters = listOf(item(1)),
            hasMore = true,
        )

        assertTrue(base.canLoadMore)
        assertFalse(base.copy(loadingMore = true).canLoadMore)
        assertFalse(base.copy(loadMoreError = "boom").canLoadMore)
        assertFalse(base.copy(hasMore = false).canLoadMore)
        assertFalse(base.copy(loading = true).canLoadMore)
    }

    // ------------------------------------------------------------------
    // ViewModel：首屏 / 追加 / 去重 / 搜索 / 筛选 / 错误
    // ------------------------------------------------------------------

    @Test
    fun view_model_loads_first_page_then_appends_until_exhausted() = runTest(dispatcher) {
        val source = FakeChapterSource((1..120).map { brief(it.toLong()) })
        val viewModel = viewModel(source)

        advanceUntilIdle()
        assertEquals(50, viewModel.state.value.loadedCount)
        assertTrue(viewModel.state.value.hasMore)
        assertFalse(viewModel.state.value.loading)

        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(100, viewModel.state.value.loadedCount)
        assertTrue(viewModel.state.value.hasMore)

        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(120, viewModel.state.value.loadedCount)
        assertFalse(viewModel.state.value.hasMore)
        assertTrue(viewModel.state.value.isComplete)

        assertEquals(listOf(0, 50, 100), source.calls.map { it.offset })
        assertEquals(listOf(50, 50, 50), source.calls.map { it.limit })
        assertEquals(listOf("", "", ""), source.calls.map { it.query })
    }

    @Test
    fun view_model_keeps_has_more_until_an_empty_page_proves_the_end() = runTest(dispatcher) {
        val source = FakeChapterSource((1..100).map { brief(it.toLong()) })
        val viewModel = viewModel(source)

        advanceUntilIdle()
        assertEquals(50, viewModel.state.value.loadedCount)
        assertTrue(viewModel.state.value.hasMore)

        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(100, viewModel.state.value.loadedCount)
        assertTrue("满页仍可能还有下一页", viewModel.state.value.hasMore)

        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(100, viewModel.state.value.loadedCount)
        assertFalse(viewModel.state.value.hasMore)
        assertEquals(listOf(0, 50, 100), source.calls.map { it.offset })
    }

    @Test
    fun view_model_dedupes_overlapping_pages() = runTest(dispatcher) {
        val source = FakeChapterSource((1..10).map { brief(it.toLong()) })
        source.repeatFirstPage = true
        val viewModel = viewModel(source, pageSize = 5)

        advanceUntilIdle()
        assertEquals(5, viewModel.state.value.loadedCount)

        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals("重复页不能把同一章插入两次", 5, viewModel.state.value.loadedCount)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), viewModel.state.value.chapters.map { it.id })
    }

    @Test
    fun view_model_search_resets_and_filters_titles() = runTest(dispatcher) {
        val source = FakeChapterSource(
            listOf(
                brief(1, title = "Alpha"),
                brief(2, title = "Beta"),
                brief(3, title = "Alpha Two"),
            ),
        )
        val viewModel = viewModel(source)
        advanceUntilIdle()
        assertEquals(3, viewModel.state.value.loadedCount)

        viewModel.setQuery("alpha")
        advanceUntilIdle()
        assertEquals("alpha", viewModel.state.value.query)
        assertEquals(listOf(1L, 3L), viewModel.state.value.chapters.map { it.id })
        assertEquals("alpha", source.calls.last().query)

        val callsBeforeWhitespace = source.calls.size
        viewModel.setQuery("alpha  ")
        advanceUntilIdle()
        assertEquals("只有有效关键词变化才重查", callsBeforeWhitespace, source.calls.size)
        assertEquals("alpha  ", viewModel.state.value.query)

        viewModel.clearFilters()
        advanceUntilIdle()
        assertEquals("", viewModel.state.value.query)
        assertTrue(viewModel.state.value.loadedCount >= 3)
    }

    @Test
    fun view_model_status_filter_scans_pages_until_matches_or_exhaustion() = runTest(dispatcher) {
        val chapters = (1..200).map { id ->
            brief(id.toLong(), parse = if (id == 160) "error" else "done")
        }
        val source = FakeChapterSource(chapters)
        val viewModel = viewModel(source)
        advanceUntilIdle()
        source.calls.clear()

        viewModel.setFilter(ChapterFilter.ParseFailed)
        advanceUntilIdle()

        assertEquals(ChapterFilter.ParseFailed, viewModel.state.value.filter)
        assertEquals(listOf(160L), viewModel.state.value.chapters.map { it.id })
        assertFalse("最后一页为空后不再有更多", viewModel.state.value.hasMore)
        assertEquals(listOf(0, 50, 100, 150, 200), source.calls.map { it.offset })
    }

    @Test
    fun view_model_reports_initial_error_and_recovers_on_retry() = runTest(dispatcher) {
        val source = FakeChapterSource((1..10).map { brief(it.toLong()) })
        source.failInitial = true
        val viewModel = viewModel(source)

        advanceUntilIdle()
        assertNotNull(viewModel.state.value.loadError)
        assertFalse(viewModel.state.value.isInitialLoading)
        assertFalse(viewModel.state.value.isEmpty)
        assertEquals(0, viewModel.state.value.loadedCount)

        source.failInitial = false
        viewModel.retry()
        advanceUntilIdle()
        assertNull(viewModel.state.value.loadError)
        assertEquals(10, viewModel.state.value.loadedCount)
    }

    @Test
    fun view_model_reports_load_more_error_and_keeps_loaded_rows() = runTest(dispatcher) {
        val source = FakeChapterSource((1..120).map { brief(it.toLong()) })
        val viewModel = viewModel(source)
        advanceUntilIdle()
        assertEquals(50, viewModel.state.value.loadedCount)

        source.failOffset = 50
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(50, viewModel.state.value.loadedCount)
        assertNotNull(viewModel.state.value.loadMoreError)
        assertTrue(viewModel.state.value.hasMore)
        assertFalse(viewModel.state.value.canLoadMore)

        viewModel.retryLoadMore()
        advanceUntilIdle()
        assertNull(viewModel.state.value.loadMoreError)
        assertEquals(100, viewModel.state.value.loadedCount)
        assertEquals(listOf(0, 50, 50), source.calls.map { it.offset })
    }

    @Test
    fun view_model_ignores_load_more_while_one_is_running() = runTest(dispatcher) {
        val source = FakeChapterSource((1..120).map { brief(it.toLong()) })
        val viewModel = viewModel(source)
        advanceUntilIdle()

        viewModel.loadMore()
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals("并发的 loadMore 只能发一次请求", 2, source.calls.size)
        assertEquals(100, viewModel.state.value.loadedCount)
    }

    @Test
    fun view_model_empty_source_is_empty_without_more() = runTest(dispatcher) {
        val viewModel = viewModel(FakeChapterSource(emptyList()))

        advanceUntilIdle()
        assertTrue(viewModel.state.value.isEmpty)
        assertFalse(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.loadingMore)
        assertFalse(viewModel.state.value.hasMore)
        assertFalse(viewModel.state.value.isComplete)
        assertEquals(0, viewModel.state.value.loadedCount)
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun viewModel(
        source: ChapterPageSource,
        pageSize: Int = CHAPTER_PAGE_SIZE,
    ): ChapterListViewModel = ChapterListViewModel(
        novelId = 1L,
        source = source,
        pageSize = pageSize,
    )

    private fun item(
        id: Long,
        parse: String = "pending",
        audio: String = "pending",
        title: String = "第${id}章",
        warn: String? = null,
        segCount: Int = 0,
        segDone: Int = 0,
    ): ChapterListItemUi = ChapterListItemUi.from(
        brief(
            id = id,
            parse = parse,
            audio = audio,
            title = title,
            warn = warn,
            segCount = segCount,
            segDone = segDone,
        ),
    )

    private fun brief(
        id: Long,
        idx: Int = (id - 1).toInt(),
        title: String = "第${id}章",
        parse: String = "pending",
        audio: String = "pending",
        warn: String? = null,
        segCount: Int = 0,
        segDone: Int = 0,
    ): ChapterBrief = ChapterBrief(
        id = id,
        idx = idx,
        title = title,
        parseStatus = parse,
        audioStatus = audio,
        parseWarn = warn,
        segCount = segCount,
        segDone = segDone,
    )

    private data class PageCall(
        val query: String,
        val limit: Int,
        val offset: Int,
    )

    private class FakeChapterSource(
        val chapters: List<ChapterBrief>,
    ) : ChapterPageSource {
        val calls = mutableListOf<PageCall>()
        var failInitial: Boolean = false
        var failOffset: Int? = null
        var repeatFirstPage: Boolean = false

        override suspend fun page(
            novelId: Long,
            query: String,
            limit: Int,
            offset: Int,
        ): List<ChapterBrief> {
            assertEquals("测试只使用一个项目", 1L, novelId)
            calls += PageCall(query = query, limit = limit, offset = offset)

            if (failInitial && offset == 0) {
                throw IllegalStateException("database is locked")
            }
            if (failOffset == offset) {
                failOffset = null
                throw IllegalStateException("load more failed")
            }

            val keyword = query.trim()
            val matched = chapters.filter { chapter ->
                keyword.isEmpty() || chapter.title.contains(keyword, ignoreCase = true)
            }
            return if (repeatFirstPage) {
                matched.take(limit)
            } else {
                matched.drop(offset).take(limit)
            }
        }
    }
}
