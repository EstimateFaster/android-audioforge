package com.estimatefaster.audioforge.playback

/**
 * 服务侧播放队列的一条 MediaItem 描述（P5-08）。
 *
 * 只描述“要播什么”，不持有 Android 对象，方便纯 JVM 单测与在服务/控制器之间共享。
 * [uri] 用字符串保存（`file:///...`），构造 `MediaItem` 时再转换。
 *
 * @property mediaId Media3 的稳定 id；章节内唯一即可
 * @property uri 本地音频地址；只支持文件 URI
 * @property title 通知/锁屏的标题（通常是章节名）
 * @property subtitle 通知/锁屏的副标题（通常是角色名），可为空
 * @property segmentIndex 片段在本章的 0 基 index，与 P5-03 同一坐标系
 * @property durationMs 已知时长，仅用于估算；播放器上报为准
 */
data class PlaybackQueueItem(
    val mediaId: String,
    val uri: String,
    val title: String,
    val subtitle: String?,
    val segmentIndex: Int,
    val durationMs: Long = 0L,
)

/**
 * 播放队列的一章。
 *
 * [items] 只包含**有音频**的片段，且按章节内顺序排列；空章会留在列表里以保留
 * 章节位置，队列导航时会跳过它们。
 *
 * @property chapterId 数据库主键
 * @property title 章节标题（通知标题）
 */
data class PlaybackQueueChapter(
    val chapterId: Long,
    val title: String,
    val items: List<PlaybackQueueItem> = emptyList(),
) {
    val isEmpty: Boolean get() = items.isEmpty()

    val firstItem: PlaybackQueueItem? get() = items.firstOrNull()
}

/**
 * 一条片段播完后的去向（纯 Kotlin）。
 *
 * 服务侧在 ExoPlayer 报告 `STATE_ENDED` 时调用 [PlaybackQueue.advanceAfter]：
 * - 本章还有下一段 → [NextItem]；
 * - 本章最后一段且连续播放开、后面还有有音频章 → [NextChapter]；
 * - 其它情况 → [Stop]（停在章末，等待用户操作）。
 */
sealed interface PlaybackAdvance {

    /** 本章内的下一有音频段。 */
    data class NextItem(val item: PlaybackQueueItem) : PlaybackAdvance

    /** 队列里的下一个有音频章（含其在队列中的位置）。 */
    data class NextChapter(
        val chapterIndex: Int,
        val chapter: PlaybackQueueChapter,
    ) : PlaybackAdvance

    /** 不再前进：本章播完且连续播放关闭，或已是全书最后一章。 */
    data object Stop : PlaybackAdvance
}

/**
 * 服务侧 MediaItem 队列（P5-08）。
 *
 * 为什么在服务侧建队列：P5-03 的 `PlaybackFollowController` 是页面级跟随状态机，
 * 锁屏/通知/蓝牙按键不经过页面；把“下一章/自动续播”放进服务，后台也能正确前进，
 * 且不会和页面的“下一句”语义抢状态。本章内逐段前进交给 ExoPlayer 原生队列，
 * 服务只处理章边界与跨章。
 *
 * 语义：
 * - 章位置就是 [chapters] 的下标（不是数据库 idx）；空章保留位置但导航跳过；
 * - [nextChapterIndex] / [previousChapterIndex] 不循环，边界返回 null；
 * - [continuous] 只控制“章末是否自动进入下一章”，不影响本章内自动前进；
 * - [advanceAfter] 是纯函数，服务与单测共用同一套边界判断。
 */
data class PlaybackQueue(
    val chapters: List<PlaybackQueueChapter> = emptyList(),
    val continuous: Boolean = true,
) {

    /** 全书都没有可播片段。 */
    val isEmpty: Boolean get() = chapters.none { it.items.isNotEmpty() }

    /** 取一个可播章；空章/越界返回 null。 */
    fun chapterAt(index: Int?): PlaybackQueueChapter? {
        val position = index ?: return null
        if (position !in chapters.indices) return null
        return chapters[position].takeIf { it.items.isNotEmpty() }
    }

    /** 第一个可播章的位置；全书无音频时返回 null。 */
    fun firstPlayableChapterIndex(): Int? =
        chapters.indexOfFirst { it.items.isNotEmpty() }.takeIf { it >= 0 }

    /**
     * 从 [current] 之后找下一个可播章。
     *
     * [current] 为 null 表示尚未开始：返回第一个可播章，便于“下一章”从头起播。
     */
    fun nextChapterIndex(current: Int?): Int? {
        val start = if (current == null) 0 else (current + 1).coerceAtLeast(0)
        if (start >= chapters.size) return null
        return (start until chapters.size).firstOrNull { chapters[it].items.isNotEmpty() }
    }

    /** 从 [current] 之前找上一个可播章；未开始或已到第一章时返回 null。 */
    fun previousChapterIndex(current: Int?): Int? {
        val start = (current ?: return null) - 1
        if (start < 0) return null
        return (start downTo 0).firstOrNull { chapters[it].items.isNotEmpty() }
    }

    /** 片段在本章内的位置；找不到返回 null。 */
    fun indexOfSegment(chapterIndex: Int, segmentIndex: Int): Int? {
        val chapter = chapterAt(chapterIndex) ?: return null
        return chapter.items.indexOfFirst { it.segmentIndex == segmentIndex }.takeIf { it >= 0 }
    }

    /**
     * 一条片段播完后该去哪。
     *
     * 只有“当前章最后一段播完”才会考虑跨章；跨章还要求 [continuous] 为 true。
     * 当前段找不到（例如元数据缺失）时返回 [PlaybackAdvance.Stop]，避免误跳。
     */
    fun advanceAfter(chapterIndex: Int?, segmentIndex: Int?): PlaybackAdvance {
        val index = chapterIndex ?: return PlaybackAdvance.Stop
        val chapter = chapterAt(index) ?: return PlaybackAdvance.Stop
        val position = segmentIndex?.let { indexOfSegment(index, it) }
            ?: return PlaybackAdvance.Stop
        chapter.items.getOrNull(position + 1)?.let { return PlaybackAdvance.NextItem(it) }
        if (!continuous) return PlaybackAdvance.Stop
        val nextIndex = nextChapterIndex(index) ?: return PlaybackAdvance.Stop
        return PlaybackAdvance.NextChapter(nextIndex, chapters[nextIndex])
    }

    /** 返回替换连续开关后的副本。 */
    fun withContinuous(enabled: Boolean): PlaybackQueue = copy(continuous = enabled)
}

/**
 * 进程内队列桥（P5-08）。
 *
 * [PlayerController] 与 [AudioForgeMediaSessionService] 同进程（Manifest 未指定
 * `android:process`），控制器把完整队列放在这里，服务在锁屏/通知命令与章末自动续播时
 * 读取。这样不必把整本书的 MediaItem 一次性塞进 Binder，也避免 Binder 事务大小限制。
 *
 * 进程被杀时服务与控制器一起结束，队列丢失是可接受的；播放位置恢复不属于 P5-08。
 */
internal object PlaybackQueueStore {

    @Volatile
    private var queue: PlaybackQueue = PlaybackQueue()

    val current: PlaybackQueue get() = queue

    fun set(value: PlaybackQueue) {
        queue = value
    }

    fun updateContinuous(enabled: Boolean) {
        queue = queue.withContinuous(enabled)
    }
}
