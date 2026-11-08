package com.estimatefaster.audioforge.feature.projects

import com.estimatefaster.audioforge.data.db.ProjectSummary
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 项目库里一张项目卡需要的**聚合字段**。
 *
 * 只从 [ProjectSummary] 投影：章节数/完成数/片段数都是 `GROUP BY` 聚合结果，
 * 不读取任何章节正文，因此 2000 章的项目卡与 2 章的项目卡开销相同。
 */
data class ProjectCardUi(
    val id: Long,
    val title: String,
    val sourceName: String,
    val charCount: Int,
    val createdAt: Long,
    val lastOpenedAt: Long?,
    val chapterCount: Int,
    val chapterDoneCount: Int,
    val segmentCount: Int,
    val segmentDoneCount: Int,
) {
    /** `LazyColumn` 的稳定 key：项目 id 在库内唯一，重命名/排序变化不会改变它。 */
    val stableKey: String
        get() = "project:$id"

    /** 章节完成进度 0..1；没有章节时为 0。 */
    val progress: Float
        get() = if (chapterCount <= 0) {
            0f
        } else {
            (chapterDoneCount.toFloat() / chapterCount).coerceIn(0f, 1f)
        }

    /** 0..100 的整数百分比（四舍五入），用于测试/无障碍文案。 */
    val percent: Int
        get() = (progress * 100f).roundToInt()

    /**
     * 音频聚合 wire 状态，可直接传给 `StatusPill(StatusKind.Audio, ...)`。
     *
     * [ProjectSummary] 没有逐章错误计数，因此只区分待生成/生成中/已生成；
     * 失败详情留给 P4-05 项目详情。
     */
    val audioStatus: String
        get() = when {
            chapterCount <= 0 -> "pending"
            chapterDoneCount >= chapterCount -> "done"
            chapterDoneCount > 0 -> "running"
            else -> "pending"
        }

    /** 带千分位的字符数（如 `156,000`）；单位文案由 `strings.xml` 拼接。 */
    val charCountText: String
        get() = formatCharCount(charCount)
}

/**
 * 项目库的不可变 UI 状态。
 *
 * [allProjects] 保持仓库 Flow 的原始顺序（DB 已按
 * `COALESCE(lastOpenedAt, createdAt) DESC, id DESC` 排序），搜索只做过滤、
 * 不重新排序，避免「最近打开优先」在客户端被破坏。
 *
 * 派生属性直接给 UI 用，避免 Composable 里堆条件判断。
 */
data class ProjectLibraryUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val query: String = "",
    val allProjects: List<ProjectCardUi> = emptyList(),
    val actionError: String? = null,
) {
    /** 标题过滤结果（trim + 大小写不敏感）；空查询原样返回，保持顺序。 */
    val projects: List<ProjectCardUi> by lazy(LazyThreadSafetyMode.NONE) {
        filterProjectCards(allProjects, query)
    }

    val totalProjectCount: Int
        get() = allProjects.size

    /** 库为空（与「搜索无匹配」区分）。 */
    val isEmpty: Boolean
        get() = !loading && loadError == null && allProjects.isEmpty()

    /** 库里有项目，但当前查询没有匹配。 */
    val isNoSearchResult: Boolean
        get() = !loading && loadError == null && allProjects.isNotEmpty() && projects.isEmpty()

    /** 有可展示的项目卡。 */
    val hasResults: Boolean
        get() = !loading && loadError == null && projects.isNotEmpty()
}

/**
 * 标题过滤：
 * - `query.trim()` 后为空 → 返回全部（保持原顺序）；
 * - 否则按标题包含匹配，大小写不敏感（中文不受影响）。
 *
 * 纯客户端过滤：项目库一次性从 DB 拿聚合结果，2000 本书的过滤成本可忽略；
 * 不额外引入分页/DB 查询，避免搜索时反复订阅 Room Flow。
 */
fun filterProjectCards(
    cards: List<ProjectCardUi>,
    query: String,
): List<ProjectCardUi> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return cards
    return cards.filter { card -> card.title.contains(normalized, ignoreCase = true) }
}

/** 字符数格式化：千分位分组，负数按 0 处理。只返回数字，单位由 `strings.xml` 提供。 */
fun formatCharCount(charCount: Int): String =
    String.format(Locale.ROOT, "%,d", charCount.coerceAtLeast(0))

/**
 * [ProjectSummary] → [ProjectCardUi]。
 *
 * 对聚合值做防御性收敛（负数归零、完成数不超过总数），避免脏数据让进度越界。
 */
fun ProjectSummary.toProjectCardUi(): ProjectCardUi {
    val chapters = chapterCount.coerceAtLeast(0)
    val segments = segmentCount.coerceAtLeast(0)
    return ProjectCardUi(
        id = id,
        title = title,
        sourceName = sourceName,
        charCount = charCount.coerceAtLeast(0),
        createdAt = createdAt,
        lastOpenedAt = lastOpenedAt,
        chapterCount = chapters,
        chapterDoneCount = chapterDoneCount.coerceIn(0, chapters),
        segmentCount = segments,
        segmentDoneCount = segmentDoneCount.coerceIn(0, segments),
    )
}

/**
 * 一次数据快照 → UI 状态（纯函数，供 ViewModel/测试/Preview 共用）。
 *
 * 不碰 Android、不启动协程；测试可直接构造 [ProjectSummary]。
 */
fun buildProjectLibraryUiState(
    summaries: List<ProjectSummary>,
    query: String = "",
    loading: Boolean = false,
    loadError: String? = null,
    actionError: String? = null,
): ProjectLibraryUiState = ProjectLibraryUiState(
    loading = loading,
    loadError = loadError,
    query = query,
    allProjects = summaries.map { summary -> summary.toProjectCardUi() },
    actionError = actionError,
)
