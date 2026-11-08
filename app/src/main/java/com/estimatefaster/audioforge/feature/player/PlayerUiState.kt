package com.estimatefaster.audioforge.feature.player

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 播放器倍速档位（执行计划 §12.7：0.75 / 1.0 / 1.25 / 1.5 / 2.0）。
 *
 * 枚举值就是 UI 上可选的完整列表，顺序即展示顺序；[multiplier] 直接传给播放器。
 * 不依赖 Android，纯 JVM 测试可直接断言档位与选择结果。
 */
enum class PlayerSpeed(val multiplier: Float) {
    X075(0.75f),
    X100(1.0f),
    X125(1.25f),
    X150(1.5f),
    X200(2.0f);

    companion object {
        /** UI 展示顺序。 */
        val options: List<PlayerSpeed> = entries.toList()

        /** 把播放器回报的实际倍速收敛到最近的档位；未知值回落到 1.0。 */
        fun closestTo(multiplier: Float): PlayerSpeed =
            entries.minByOrNull { abs(it.multiplier - multiplier) } ?: X100
    }
}

/**
 * 睡眠定时选项（15 / 30 / 60 分钟 / 关闭）。
 *
 * [minutes] 为 null 表示关闭；时长是 UI 与播放器共用的唯一来源，避免“选项文案”
 * 和“实际分钟数”两套值漂移。
 */
enum class SleepTimerOption(val minutes: Int?) {
    Off(null),
    Minutes15(15),
    Minutes30(30),
    Minutes60(60);

    val isEnabled: Boolean
        get() = minutes != null

    companion object {
        /** UI 展示顺序。 */
        val options: List<SleepTimerOption> = entries.toList()
    }
}

/**
 * 播放器章节内的一段台词（不可变投影）。
 *
 * [audioPath] 是本地音频的相对路径；空白或 null 表示该段尚未生成音频，播放队列与
 * 上一句/下一句都会跳过它。[durationMs] 只用于队列估算与播放器尚未上报时的进度兜底，
 * 真实进度以播放器回报为准。
 *
 * [index] 是段在本章的 0 基位置，与剧本页的片段 index 同一含义；[roleId] 用于角色名
 * 为空时区分旁白与未注册角色（展示文案仍由 UI 从字符串资源取）。
 */
data class PlayerSegmentUi(
    val index: Int,
    val roleName: String,
    val text: String,
    val roleId: String = "",
    val audioPath: String? = null,
    val durationMs: Long = 0L,
) {
    val hasAudio: Boolean
        get() = !audioPath.isNullOrBlank()

    /** 列表稳定 key；段内唯一即可。 */
    val key: Int
        get() = index
}

/**
 * 播放器的一章。
 *
 * [chapterId] 是数据库主键；[index] 是整本书的 0 基顺序，用于上一章/下一章。
 * [segments] 必须按 [PlayerSegmentUi.index] 升序传入（宿主从数据库读取时已排序），
 * 本类不再排序，避免每次重组都做一次排序。
 */
data class PlayerChapterUi(
    val chapterId: Long,
    val index: Int,
    val title: String,
    val segments: List<PlayerSegmentUi> = emptyList(),
) {
    /** 本章可播放的段（按原顺序）；无音频段被排除。 */
    val playableSegments: List<PlayerSegmentUi> = segments.filter { it.hasAudio }

    val hasAudio: Boolean
        get() = playableSegments.isNotEmpty()

    /** 本章第一句有音频的段 index；整章无音频时为 null。 */
    val firstPlayableIndex: Int?
        get() = playableSegments.firstOrNull()?.index

    /** 本章可播放音频的总时长（不含静音间隙，用于进度兜底）。 */
    val durationMs: Long
        get() = playableSegments.sumOf { it.durationMs.coerceAtLeast(0L) }

    /** 列表稳定 key：主键优先，测试/预览数据没有主键时用位置兜底。 */
    val key: Long
        get() = if (chapterId > 0L) chapterId else -(index.toLong() + 1L)
}

/** 播放器内容快照：书名 + 按顺序的章节（章节内含按顺序的片段）。 */
data class PlayerContent(
    val bookTitle: String = "",
    val chapters: List<PlayerChapterUi> = emptyList(),
)

/**
 * 播放器页状态。
 *
 * 坐标约定：
 * - [currentChapterIndex] 是 [chapters] 中的 0 基位置，不是数据库 idx；
 * - [currentSegmentIndex] 是当前章节内段的 0 基 index，与 [PlayerSegmentUi.index] 一致；
 * - [positionMs] / [durationMs] 是当前播放队列项（一条片段音频）的进度，不是整章进度。
 *
 * 空态/错误态：
 * - [isInitialLoading] 首屏加载中；
 * - [isEmpty] 加载完成但全书没有任何可播放片段（没有音频）；
 * - [loadError] 内容加载失败，页面显示错误与重试。
 */
data class PlayerUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val bookTitle: String = "",
    val chapters: List<PlayerChapterUi> = emptyList(),
    val currentChapterIndex: Int? = null,
    val currentSegmentIndex: Int? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val speed: PlayerSpeed = PlayerSpeed.X100,
    val sleepTimer: SleepTimerOption = SleepTimerOption.Off,
    val sleepRemainingMs: Long? = null,
    val chaptersExpanded: Boolean = false,
) {
    /** 当前章；索引越界或未选中时为 null。 */
    val currentChapter: PlayerChapterUi?
        get() = currentChapterIndex?.let { chapters.getOrNull(it) }

    /** 当前句；章节/段索引对不上时为 null。 */
    val currentSegment: PlayerSegmentUi?
        get() = currentChapter?.segments?.firstOrNull { it.index == currentSegmentIndex }

    val isInitialLoading: Boolean
        get() = loading && chapters.isEmpty() && loadError == null

    val isEmpty: Boolean
        get() = !loading && loadError == null && chapters.none { it.hasAudio }

    val hasPlayableAudio: Boolean
        get() = chapters.any { it.hasAudio }

    /** 当前播放位置占时长的比例，已限制在 0..1；时长未知时为 0。 */
    val progress: Float
        get() = progressFraction(positionMs, durationMs)

    /** 该章是否是当前章（按主键比较，避免列表位置漂移造成错误高亮）。 */
    fun isCurrentChapter(chapter: PlayerChapterUi): Boolean {
        val current = currentChapter ?: return false
        return current.chapterId == chapter.chapterId
    }

    /**
     * 下一有音频章在 [chapters] 里的位置；没有下一章时为 null。
     *
     * 未选中任何章时返回第一章有音频的位置，方便“从头开始”。
     */
    fun nextChapterIndex(): Int? {
        val current = currentChapterIndex?.takeIf { it in chapters.indices }
        if (current == null) {
            return chapters.indexOfFirst { it.hasAudio }.takeIf { it >= 0 }
        }
        return (current + 1 until chapters.size).firstOrNull { chapters[it].hasAudio }
    }

    /** 上一有音频章在 [chapters] 里的位置；已是第一章或未选中时为 null。 */
    fun previousChapterIndex(): Int? {
        val current = currentChapterIndex?.takeIf { it in chapters.indices } ?: return null
        return (current - 1 downTo 0).firstOrNull { chapters[it].hasAudio }
    }

    /**
     * 当前章内下一有音频段的 index；已到本章最后一句时为 null。
     *
     * 跳过无音频段；未选中当前段时返回本章第一句有音频的段。
     */
    fun nextSegmentIndex(): Int? {
        val chapter = currentChapter ?: return null
        val current = currentSegmentIndex ?: return chapter.firstPlayableIndex
        return chapter.playableSegments.firstOrNull { it.index > current }?.index
    }

    /** 当前章内上一有音频段的 index；已是本章第一句时为 null。 */
    fun previousSegmentIndex(): Int? {
        val chapter = currentChapter ?: return null
        val current = currentSegmentIndex ?: return null
        return chapter.playableSegments.lastOrNull { it.index < current }?.index
    }

    /** 进度条比例 → 目标毫秒；时长未知时返回 0。 */
    fun seekTargetFor(fraction: Float): Long = seekTargetMs(fraction, durationMs)
}

/** 毫秒 → `m:ss` 或 `h:mm:ss`；负数按 0，秒向下取整。 */
fun formatPlaybackTime(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / MILLIS_PER_SECOND
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

/**
 * 播放位置占时长的比例，限制在 0..1。
 *
 * 时长未知（小于等于 0）时返回 0，避免进度条出现 NaN 或除零。
 */
fun progressFraction(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

/**
 * 拖动比例 → 目标毫秒。
 *
 * 比例先收敛到 0..1；NaN 视为 0；时长未知时返回 0。结果不会越过 [durationMs]，
 * 直接作为播放器 seek 的入参。
 */
fun seekTargetMs(fraction: Float, durationMs: Long): Long {
    if (durationMs <= 0L) return 0L
    if (fraction.isNaN()) return 0L
    val clamped = fraction.coerceIn(0f, 1f)
    return (durationMs.toDouble() * clamped).roundToLong().coerceIn(0L, durationMs)
}

/**
 * 倍速的短数字文案（不带倍号），用于组合 `%1$s×`。
 *
 * 0.75 → "0.75"、1.0 → "1.0"、1.25 → "1.25"、1.5 → "1.5"、2.0 → "2.0"。
 * 固定 [Locale.ROOT]，避免某些地区用逗号做小数点导致显示不一致。
 */
fun formatSpeedMultiplier(multiplier: Float): String {
    val hundredths = (multiplier * 100f).roundToLong()
    return when {
        hundredths % 10L == 0L -> String.format(Locale.ROOT, "%.1f", multiplier)
        else -> String.format(Locale.ROOT, "%.2f", multiplier)
    }
}

/** 睡眠定时选项对应的总时长；关闭时返回 null。 */
fun sleepTimerDurationMs(option: SleepTimerOption): Long? =
    option.minutes?.toLong()?.times(MILLIS_PER_MINUTE)

/** 睡眠定时剩余毫秒，最少为 0。 */
fun sleepTimerRemainingMs(totalMs: Long, elapsedMs: Long): Long =
    (totalMs - elapsedMs).coerceAtLeast(0L)

internal const val MILLIS_PER_MINUTE = 60_000L

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
