package com.estimatefaster.audioforge.feature.project

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estimatefaster.audioforge.data.db.ChapterBrief
import com.estimatefaster.audioforge.data.repository.ChapterRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 章节分页数据源：只做“按标题查一页”，便于 JVM 测试注入 fake。
 *
 * 真实仓库通过次级构造器适配，页面/DI 不需要了解这个接口。
 */
fun interface ChapterPageSource {
    suspend fun page(
        novelId: Long,
        query: String,
        limit: Int,
        offset: Int,
    ): List<ChapterBrief>
}

/**
 * 章节列表 ViewModel（P4-07）。
 *
 * 分页策略：
 * - 标题搜索下推到数据库（`pageBriefs` 的 `query`），按 [pageSize] 翻页；
 * - 状态筛选在内存里做（仓库没有状态查询），因此一次 [loadMore] 会连续取页，
 *   直到凑够 [pageSize] 行匹配项或数据库取空，避免“筛选后一页全被过滤掉、
 *   列表却以为已经到底”；
 * - 追加时按主键去重合并，`hasMore` 只在“取到满页”时为 true。
 *
 * 所有状态更新都在主线程；[reset] 通过 generation 让被取消的旧任务无法回写。
 *
 * @param novelId 当前项目 id
 * @param source 分页数据源（生产环境注入 [ChapterRepository]）
 * @param pageSize 每页条数，默认 50
 */
class ChapterListViewModel(
    private val novelId: Long,
    private val source: ChapterPageSource,
    private val pageSize: Int = CHAPTER_PAGE_SIZE,
) : ViewModel() {

    /** 生产环境入口：直接注入 `AppContainer` 里的章节仓库。 */
    constructor(
        novelId: Long,
        chapterRepository: ChapterRepository,
        pageSize: Int = CHAPTER_PAGE_SIZE,
    ) : this(
        novelId = novelId,
        source = ChapterPageSource { id, query, limit, offset ->
            chapterRepository.pageBriefs(id, query, limit, offset)
        },
        pageSize = pageSize,
    )

    private val _state = MutableStateFlow(ChapterListUiState())
    val state: StateFlow<ChapterListUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var nextOffset: Int = 0
    private var exhausted: Boolean = false
    private var generation: Int = 0

    init {
        require(pageSize > 0) { "pageSize 必须为正数：$pageSize" }
        reset()
    }

    /** 标题关键词变化；只有 trim 后的有效关键词变化才重新查询。 */
    fun setQuery(query: String) {
        val current = _state.value
        if (query == current.query) return
        val effectiveChanged = query.trim() != current.query.trim()
        _state.value = current.copy(query = query)
        if (effectiveChanged) reset()
    }

    fun setFilter(filter: ChapterFilter) {
        if (filter == _state.value.filter) return
        _state.update { it.copy(filter = filter) }
        reset()
    }

    /** 清除搜索与筛选；没有生效的筛选时不做无谓重查。 */
    fun clearFilters() {
        if (!_state.value.hasActiveFilters) return
        _state.update { it.copy(query = "", filter = ChapterFilter.All) }
        reset()
    }

    /** 滚动到底时追加下一页；重复调用会被 [ChapterListUiState.canLoadMore] 拦掉。 */
    fun loadMore() {
        val current = _state.value
        if (!current.canLoadMore) return
        _state.value = current.copy(loadingMore = true)
        launchLoad(initial = false)
    }

    /** 首屏错误重试。 */
    fun retry() {
        if (_state.value.loadError == null) return
        reset()
    }

    /** “加载更多”失败后的手动重试；保留已加载的行。 */
    fun retryLoadMore() {
        val current = _state.value
        if (current.loadMoreError == null || current.loadingMore) return
        _state.value = current.copy(loadingMore = true, loadMoreError = null)
        launchLoad(initial = false)
    }

    /**
     * 清空已加载的数据并回到第一页（保留搜索词/筛选条件）。
     * 首次进入、搜索/筛选变化、首屏重试都走这里。
     */
    fun reset() {
        val current = _state.value
        generation++
        loadJob?.cancel()
        _state.value = ChapterListUiState(
            loading = true,
            query = current.query,
            filter = current.filter,
        )
        nextOffset = 0
        exhausted = false
        launchLoad(initial = true)
    }

    private fun launchLoad(initial: Boolean) {
        val currentGeneration = generation
        val query = _state.value.query.trim()
        val filter = _state.value.filter
        loadJob = viewModelScope.launch {
            val collected = ArrayList<ChapterListItemUi>()
            var hasMoreRaw = !exhausted
            var matched = 0
            try {
                while (hasMoreRaw && matched < pageSize) {
                    if (currentGeneration != generation) return@launch
                    val page = source.page(novelId, query, pageSize, nextOffset)
                    if (page.isEmpty()) {
                        hasMoreRaw = false
                        break
                    }
                    nextOffset += page.size
                    hasMoreRaw = hasMoreAfter(page, pageSize)
                    page.forEach { brief ->
                        val item = ChapterListItemUi.from(brief)
                        if (item.matches(query, filter)) {
                            collected += item
                            matched++
                        }
                    }
                }
                if (currentGeneration != generation) return@launch
                exhausted = !hasMoreRaw
                _state.update { state ->
                    state.copy(
                        loading = false,
                        loadingMore = false,
                        loadError = null,
                        loadMoreError = null,
                        chapters = mergeChapterItems(state.chapters, collected),
                        hasMore = hasMoreRaw,
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (throwable: Throwable) {
                if (currentGeneration != generation) return@launch
                val message = throwable.toLoadErrorMessage()
                if (initial) {
                    // 首屏失败：清掉半页数据，重试从头开始。
                    nextOffset = 0
                    exhausted = false
                    _state.update {
                        it.copy(
                            loading = false,
                            loadingMore = false,
                            hasMore = false,
                            loadError = message,
                        )
                    }
                } else {
                    // 追加失败：保留已取到的行，offset 已跨过成功的页，重试从断点继续。
                    exhausted = !hasMoreRaw
                    _state.update {
                        it.copy(
                            loading = false,
                            loadingMore = false,
                            loadMoreError = message,
                            chapters = mergeChapterItems(it.chapters, collected),
                            hasMore = hasMoreRaw,
                        )
                    }
                }
            }
        }
    }

    private fun Throwable.toLoadErrorMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}
