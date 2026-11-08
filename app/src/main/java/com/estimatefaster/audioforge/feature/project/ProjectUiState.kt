package com.estimatefaster.audioforge.feature.project

import com.estimatefaster.audioforge.data.db.ChapterBrief
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.data.db.ProjectSummary
import kotlin.math.roundToInt

/** 项目详情页头部展示所需的字段（从 [ProjectSummary] 投影而来）。 */
data class ProjectHeaderUi(
    val id: Long,
    val title: String,
    val sourceName: String,
    val charCount: Int,
)

/**
 * 章节统计。
 *
 * 聚合四组 [done]/[running]/[error]/[pending] 互斥且相加恒等于 [total]：
 * 一章同时有解析错误与音频完成时归入 [error]（错误优先级最高），
 * 解析/音频任一 running 归入 [running]，只有音频完成才算 [done]。
 *
 * [parsed]/[parseRunning]/[parseError] 与 [audioDone]/[audioRunning]/[audioError]
 * 是解析/音频各自的明细计数，可能与聚合组重叠，仅用于顶部状态胶囊。
 */
data class ChapterStatsUi(
    val total: Int = 0,
    val done: Int = 0,
    val running: Int = 0,
    val error: Int = 0,
    val pending: Int = 0,
    val parsed: Int = 0,
    val parseRunning: Int = 0,
    val parseError: Int = 0,
    val audioDone: Int = 0,
    val audioRunning: Int = 0,
    val audioError: Int = 0,
    val segmentsTotal: Int = 0,
    val segmentsDone: Int = 0,
) {
    /** 0..1，直接喂给 `ProgressRing`；无章节时为 0。 */
    val progress: Float
        get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

    /** 0..100 的整数百分比（四舍五入），用于文案与测试。 */
    val percent: Int
        get() = (progress * 100f).roundToInt()

    /** 解析域的聚合 wire 状态，可直接传给 `StatusPill(StatusKind.Parse, ...)`。 */
    fun parseAggregateStatus(): String =
        aggregateStatus(total = total, done = parsed, running = parseRunning, error = parseError)

    /** 音频域的聚合 wire 状态，可直接传给 `StatusPill(StatusKind.Audio, ...)`。 */
    fun audioAggregateStatus(): String =
        aggregateStatus(total = total, done = audioDone, running = audioRunning, error = audioError)

    companion object {
        val EMPTY: ChapterStatsUi = ChapterStatsUi()

        /** 只依赖 [ChapterBrief]，不读取正文，2000 章也是 O(n) 的轻量映射。 */
        fun from(chapters: List<ChapterBrief>): ChapterStatsUi {
            var done = 0
            var running = 0
            var error = 0
            var parsed = 0
            var parseRunning = 0
            var parseError = 0
            var audioDone = 0
            var audioRunning = 0
            var audioError = 0
            var segmentsTotal = 0
            var segmentsDone = 0

            chapters.forEach { chapter ->
                val parseDone = chapter.parseStatus.isStatus("done")
                val parseIsRunning = chapter.parseStatus.isStatus("running")
                val parseIsError = chapter.parseStatus.isStatus("error")
                val audioIsDone = chapter.audioStatus.isStatus("done")
                val audioIsRunning = chapter.audioStatus.isStatus("running")
                val audioIsError = chapter.audioStatus.isStatus("error")

                if (parseDone) parsed++
                if (parseIsRunning) parseRunning++
                if (parseIsError) parseError++
                if (audioIsDone) audioDone++
                if (audioIsRunning) audioRunning++
                if (audioIsError) audioError++

                when {
                    parseIsError || audioIsError -> error++
                    parseIsRunning || audioIsRunning -> running++
                    audioIsDone -> done++
                }

                val count = chapter.segCount.coerceAtLeast(0)
                segmentsTotal += count
                segmentsDone += chapter.segDone.coerceIn(0, count)
            }

            return ChapterStatsUi(
                total = chapters.size,
                done = done,
                running = running,
                error = error,
                pending = (chapters.size - done - running - error).coerceAtLeast(0),
                parsed = parsed,
                parseRunning = parseRunning,
                parseError = parseError,
                audioDone = audioDone,
                audioRunning = audioRunning,
                audioError = audioError,
                segmentsTotal = segmentsTotal,
                segmentsDone = segmentsDone,
            )
        }

        private fun aggregateStatus(
            total: Int,
            done: Int,
            running: Int,
            error: Int,
        ): String {
            if (total <= 0) return "pending"
            return when {
                error > 0 -> "error"
                running > 0 -> "running"
                done >= total -> "done"
                else -> "pending"
            }
        }
    }
}

/** 解析告警汇总。[summary] 用中文分号拼接去重后的前 [DEFAULT_WARNING_SAMPLES] 条。 */
data class WarningSummaryUi(
    val warningCount: Int = 0,
    val samples: List<String> = emptyList(),
) {
    val hasWarnings: Boolean
        get() = warningCount > 0

    val summary: String
        get() = samples.joinToString(separator = "；")

    companion object {
        val EMPTY: WarningSummaryUi = WarningSummaryUi()

        fun from(
            chapters: List<ChapterBrief>,
            maxSamples: Int = DEFAULT_WARNING_SAMPLES,
        ): WarningSummaryUi {
            val messages = chapters.mapNotNull { chapter ->
                chapter.parseWarn?.trim()?.takeIf { it.isNotEmpty() }
            }
            val sampleLimit = maxSamples.coerceAtLeast(0)
            return WarningSummaryUi(
                warningCount = messages.size,
                samples = messages.distinct().take(sampleLimit),
            )
        }
    }
}

/** 人物条里单个角色需要的最小字段。 */
data class CharacterUi(
    val roleId: String,
    val name: String,
)

/** 人物截断结果：展示 [visible]，其余用 [hiddenCount] 显示“+N”。 */
data class CharacterStripUi(
    val visible: List<CharacterUi> = emptyList(),
    val hiddenCount: Int = 0,
) {
    companion object {
        fun from(
            characters: List<CharacterEntity>,
            limit: Int = DEFAULT_VISIBLE_CHARACTERS,
        ): CharacterStripUi {
            val visible = characters
                .take(limit.coerceAtLeast(0))
                .map { CharacterUi(roleId = it.roleId, name = it.name) }
            return CharacterStripUi(
                visible = visible,
                hiddenCount = (characters.size - visible.size).coerceAtLeast(0),
            )
        }
    }
}

/** 项目详情页的不可变 UI 状态；空态/错误态由派生属性判断，避免 UI 里写复杂条件。 */
data class ProjectUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val project: ProjectHeaderUi? = null,
    val chapterStats: ChapterStatsUi = ChapterStatsUi.EMPTY,
    val warnings: WarningSummaryUi = WarningSummaryUi.EMPTY,
    val characters: List<CharacterUi> = emptyList(),
    val hiddenCharacterCount: Int = 0,
) {
    /** 加载完成但项目已不存在（被删除/ id 错误）。 */
    val isMissing: Boolean
        get() = !loading && loadError == null && project == null

    /** 项目存在但没有任何章节；展示导入引导空态。 */
    val isEmpty: Boolean
        get() = !loading && loadError == null && project != null && chapterStats.total == 0

    /** 至少有一个章节，可以展示进度/控制/人物等卡片。 */
    val hasChapters: Boolean
        get() = !loading && loadError == null && project != null && chapterStats.total > 0
}

/** 人物条最多展示的头像数；超过时显示“+N”。 */
const val DEFAULT_VISIBLE_CHARACTERS: Int = 8

/** 告警条最多拼接的样例数。 */
const val DEFAULT_WARNING_SAMPLES: Int = 3

/**
 * 把三个仓库/数据源的一次快照映射为 UI 状态。
 *
 * 保持为纯函数：不碰 Android、不启动协程，ViewModel 只负责组合 Flow，
 * 单元测试可以直接构造 [ProjectSummary]/[ChapterBrief]/[CharacterEntity]。
 */
internal fun buildProjectUiState(
    novelId: Long,
    projects: List<ProjectSummary>,
    chapters: List<ChapterBrief>,
    characters: List<CharacterEntity>,
    characterLimit: Int = DEFAULT_VISIBLE_CHARACTERS,
    warningSampleLimit: Int = DEFAULT_WARNING_SAMPLES,
): ProjectUiState {
    val project = projects.firstOrNull { it.id == novelId }
        ?: return ProjectUiState(loading = false)

    val strip = CharacterStripUi.from(characters, characterLimit)
    return ProjectUiState(
        loading = false,
        project = ProjectHeaderUi(
            id = project.id,
            title = project.title,
            sourceName = project.sourceName,
            charCount = project.charCount,
        ),
        chapterStats = ChapterStatsUi.from(chapters),
        warnings = WarningSummaryUi.from(chapters, warningSampleLimit),
        characters = strip.visible,
        hiddenCharacterCount = strip.hiddenCount,
    )
}

/** wire 状态大小写不敏感；非法/未知值按未完成处理。 */
private fun String.isStatus(expected: String): Boolean = trim().equals(expected, ignoreCase = true)
