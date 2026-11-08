package com.estimatefaster.audioforge.feature.project

import com.estimatefaster.audioforge.data.db.ChapterBrief

/** 每页章节数；与 `ChapterRepository.DEFAULT_PAGE_SIZE` 保持一致。 */
const val CHAPTER_PAGE_SIZE: Int = 50

/**
 * 章节列表的状态筛选。
 *
 * 语义（wire 状态大小写不敏感）：
 * - [Parsing]：解析中（只匹配解析状态 running，不看音频）；
 * - [ParseFailed]：解析失败（parseStatus error）；
 * - [AudioDone]：音频完成（audioStatus done）；
 * - [Incomplete]：未完成（audioStatus 不是 done，含待生成/生成中/失败）。
 *
 * “全部”与“未完成”之外的条件互不重叠，便于用户逐层收窄。
 */
enum class ChapterFilter {
    All,
    Parsing,
    ParseFailed,
    AudioDone,
    Incomplete,
}

/**
 * 单行章节的不可变投影。
 *
 * 所有展示字段在分页映射时一次算好（状态归一化、告警、片段进度收敛），
 * `LazyColumn` 的 item 内不再查询数据库/仓库，2000 章滚动时只做渲染。
 *
 * [id]（Room 主键）用作稳定 key；[number] 是展示序号（1 起）。
 */
data class ChapterListItemUi(
    val id: Long,
    val number: Int,
    val title: String,
    val parseStatus: String,
    val audioStatus: String,
    val hasWarning: Boolean,
    val segmentDone: Int,
    val segmentTotal: Int,
) {
    /** 稳定 key：分页追加/去重后同一个章节始终复用同一个 item。 */
    val key: Long
        get() = id

    val hasSegments: Boolean
        get() = segmentTotal > 0

    /** 0..1；无片段时为 0，供进度条使用。 */
    val segmentProgress: Float
        get() = if (segmentTotal <= 0) {
            0f
        } else {
            (segmentDone.toFloat() / segmentTotal).coerceIn(0f, 1f)
        }

    val isParseRunning: Boolean
        get() = parseStatus.isWireStatus("running")

    val isParseFailed: Boolean
        get() = parseStatus.isWireStatus("error")

    val isAudioDone: Boolean
        get() = audioStatus.isWireStatus("done")

    /** 未完成：音频尚未 done（待生成、生成中、失败都算）。 */
    val isIncomplete: Boolean
        get() = !isAudioDone

    fun matchesFilter(filter: ChapterFilter): Boolean = when (filter) {
        ChapterFilter.All -> true
        ChapterFilter.Parsing -> isParseRunning
        ChapterFilter.ParseFailed -> isParseFailed
        ChapterFilter.AudioDone -> isAudioDone
        ChapterFilter.Incomplete -> isIncomplete
    }

    /** 标题包含关键词（trim 后、忽略大小写）；空查询匹配全部。 */
    fun matchesQuery(query: String): Boolean {
        val trimmed = query.trim()
        return trimmed.isEmpty() || title.contains(trimmed, ignoreCase = true)
    }

    fun matches(query: String, filter: ChapterFilter): Boolean =
        matchesFilter(filter) && matchesQuery(query)

    companion object {
        /** 只读 [ChapterBrief]，不碰正文；用于分页映射。 */
        fun from(brief: ChapterBrief): ChapterListItemUi {
            val segmentTotal = brief.segCount.coerceAtLeast(0)
            return ChapterListItemUi(
                id = brief.id,
                number = brief.idx.coerceIn(0, Int.MAX_VALUE - 1) + 1,
                title = brief.title,
                parseStatus = brief.parseStatus,
                audioStatus = brief.audioStatus,
                hasWarning = !brief.parseWarn.isNullOrBlank(),
                segmentDone = brief.segDone.coerceIn(0, segmentTotal),
                segmentTotal = segmentTotal,
            )
        }
    }
}

/**
 * 分页合并：按 [ChapterListItemUi.id] 去重，保留首次出现的顺序；
 * 同一 id 再次出现时用新数据覆盖（数据库在翻页期间可能更新状态）。
 */
internal fun mergeChapterItems(
    existing: List<ChapterListItemUi>,
    incoming: List<ChapterListItemUi>,
): List<ChapterListItemUi> {
    if (incoming.isEmpty()) return existing
    val merged = LinkedHashMap<Long, ChapterListItemUi>(existing.size + incoming.size)
    existing.forEach { merged[it.id] = it }
    incoming.forEach { merged[it.id] = it }
    return merged.values.toList()
}

/**
 * 判断是否还有下一页：只有“满页”才可能是最后一页之后还有数据。
 * 空页或不足一页都表示已经到底。
 */
internal fun hasMoreAfter(page: List<*>, pageSize: Int = CHAPTER_PAGE_SIZE): Boolean =
    pageSize > 0 && page.size >= pageSize

/**
 * 章节列表的不可变 UI 状态。
 *
 * [chapters] 是“已加载并已通过查询/筛选”的行，分页由 ViewModel 维护；
 * 空态/加载/错误由派生属性判断，避免 Composable 里写复杂条件。
 */
data class ChapterListUiState(
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val loadError: String? = null,
    val loadMoreError: String? = null,
    val chapters: List<ChapterListItemUi> = emptyList(),
    val query: String = "",
    val filter: ChapterFilter = ChapterFilter.All,
    val hasMore: Boolean = false,
) {
    /** 首屏加载中：展示居中加载而不是空态。 */
    val isInitialLoading: Boolean
        get() = loading && chapters.isEmpty() && loadError == null

    /** 加载完成但没有可展示的行（可能是无章节，也可能是筛选后为空）。 */
    val isEmpty: Boolean
        get() = !loading && loadError == null && chapters.isEmpty()

    val hasActiveFilters: Boolean
        get() = query.isNotBlank() || filter != ChapterFilter.All

    val loadedCount: Int
        get() = chapters.size

    /** 滚动到底可以自动加载下一页；有“加载更多”错误时改为手动重试。 */
    val canLoadMore: Boolean
        get() = hasMore && !loading && !loadingMore && loadMoreError == null

    /** 已全部加载完（至少有一行），用于底部“已加载全部”。 */
    val isComplete: Boolean
        get() = !loading && loadError == null && !hasMore && chapters.isNotEmpty()
}

/** wire 状态大小写不敏感；未知/空白值按“不匹配”处理。 */
private fun String.isWireStatus(expected: String): Boolean =
    trim().equals(expected, ignoreCase = true)
