package com.estimatefaster.audioforge.playback

/**
 * 播放通知/后台的 MediaSession 自定义命令定义（P5-08）。
 *
 * 这里只放**纯 Kotlin** 的常量与校验，不引用 Android/Media3，便于 JVM 单测直接断言；
 * 需要用到 [android.os.Bundle] 与 `SessionCommand` 的构造放在 `PlaybackSessionCommands`。
 *
 * 命令按“动作 + 可选参数”划分：
 * - [ACTION_NEXT_CHAPTER] / [ACTION_PREV_CHAPTER]：跨章，参数为空，结果在
 *   [EXTRA_CHAPTER_INDEX] / [EXTRA_CHAPTER_ID] 里返回目标章；
 * - [ACTION_SLEEP_TIMER]：可选参数 [EXTRA_SLEEP_MINUTES]。带 15/30/60 表示设置，
 *   带 0 表示取消，**不带参数**表示按 15→30→60→关闭循环；
 * - [ACTION_SET_CONTINUOUS]：可选参数 [EXTRA_CONTINUOUS]，供 UI 同步连续播放开关。
 *
 * 所有自定义 action 都带应用包名前缀，避免与系统/其它库的 action 冲突。
 */
object PlaybackCommands {

    /** 下一有音频章。 */
    const val ACTION_NEXT_CHAPTER: String =
        "com.estimatefaster.audioforge.playback.action.NEXT_CHAPTER"

    /** 上一有音频章。 */
    const val ACTION_PREV_CHAPTER: String =
        "com.estimatefaster.audioforge.playback.action.PREV_CHAPTER"

    /** 睡眠定时（设置/取消/循环）。 */
    const val ACTION_SLEEP_TIMER: String =
        "com.estimatefaster.audioforge.playback.action.SLEEP_TIMER"

    /** 连续播放开关，仅用于跨章自动续播；不影响本章内逐段前进。 */
    const val ACTION_SET_CONTINUOUS: String =
        "com.estimatefaster.audioforge.playback.action.SET_CONTINUOUS"

    /** 睡眠定时分钟数（Int）。 */
    const val EXTRA_SLEEP_MINUTES: String =
        "com.estimatefaster.audioforge.playback.extra.SLEEP_MINUTES"

    /** 目标/当前章节在播放队列里的 0 基位置（Int）。 */
    const val EXTRA_CHAPTER_INDEX: String =
        "com.estimatefaster.audioforge.playback.extra.CHAPTER_INDEX"

    /** 章节数据库主键（Long）。 */
    const val EXTRA_CHAPTER_ID: String =
        "com.estimatefaster.audioforge.playback.extra.CHAPTER_ID"

    /** 章节标题（String），通知/结果回传用。 */
    const val EXTRA_CHAPTER_TITLE: String =
        "com.estimatefaster.audioforge.playback.extra.CHAPTER_TITLE"

    /** 连续播放开关（Boolean）。 */
    const val EXTRA_CONTINUOUS: String =
        "com.estimatefaster.audioforge.playback.extra.CONTINUOUS"

    /** 片段在本章的 0 基 index（Int），写进 MediaItem 元数据。 */
    const val EXTRA_SEGMENT_INDEX: String =
        "com.estimatefaster.audioforge.playback.extra.SEGMENT_INDEX"

    /** 点击媒体通知/锁屏控件时打开的播放器深链。 */
    const val PLAYER_DEEP_LINK: String = "audioforge://player"

    /** 通知睡眠按钮可选的三个档位，顺序即循环顺序。 */
    val SUPPORTED_SLEEP_MINUTES: List<Int> = listOf(15, 30, 60)

    /** 所有自定义命令；用于测试与文档，不代表权限控制。 */
    val ALL_ACTIONS: List<String> = listOf(
        ACTION_NEXT_CHAPTER,
        ACTION_PREV_CHAPTER,
        ACTION_SLEEP_TIMER,
        ACTION_SET_CONTINUOUS,
    )

    /** 命令分类；空/未知 action 归到 [PlaybackCommandKind.Unknown]。 */
    fun kindOf(action: String?): PlaybackCommandKind = when (action) {
        ACTION_NEXT_CHAPTER -> PlaybackCommandKind.NextChapter
        ACTION_PREV_CHAPTER -> PlaybackCommandKind.PreviousChapter
        ACTION_SLEEP_TIMER -> PlaybackCommandKind.SleepTimer
        ACTION_SET_CONTINUOUS -> PlaybackCommandKind.SetContinuous
        else -> PlaybackCommandKind.Unknown
    }

    /** 是否是 UI 可设置的睡眠档位（15/30/60）。 */
    fun isSupportedSleepMinutes(minutes: Int): Boolean = minutes in SUPPORTED_SLEEP_MINUTES

    /**
     * 睡眠参数校验：0 表示关闭，属合法值；其余只接受 15/30/60。
     *
     * 负数、1～14、16～29 等一律拒绝，由服务返回 `RESULT_ERROR_BAD_VALUE`。
     */
    fun isValidSleepMinutes(minutes: Int): Boolean =
        minutes == SLEEP_OFF_MINUTES || isSupportedSleepMinutes(minutes)

    /**
     * 通知睡眠按钮的循环：关闭 → 15 → 30 → 60 → 关闭。
     *
     * 未知值（例如旧版本残留）回落到 15，避免按钮点不动。
     */
    fun nextSleepMinutes(currentMinutes: Int): Int = when (currentMinutes) {
        15 -> 30
        30 -> 60
        60 -> SLEEP_OFF_MINUTES
        else -> 15
    }

    /** 0 分钟的语义：取消睡眠定时。 */
    const val SLEEP_OFF_MINUTES: Int = 0
}

/** 自定义命令分类（纯 Kotlin，不依赖 Media3 的 SessionCommand）。 */
enum class PlaybackCommandKind {
    NextChapter,
    PreviousChapter,
    SleepTimer,
    SetContinuous,
    Unknown,
}
