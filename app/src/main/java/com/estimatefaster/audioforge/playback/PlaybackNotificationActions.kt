package com.estimatefaster.audioforge.playback

import android.content.Context
import com.estimatefaster.audioforge.R

/**
 * 媒体通知里的四个动作（P5-08）。
 *
 * 顺序固定为：上一章 → 播放/暂停 → 下一章 → 睡眠定时。默认的
 * `DefaultMediaNotificationProvider` 只给“上一首/播放/下一首”，这里按播放器的
 * 章节队列语义替换，且保持通知最多 4 个 action，避免超出系统紧凑视图限制。
 *
 * 纯 Kotlin：只描述动作与文案资源，不依赖 Media3；具体图标/`CommandButton` 的构造
 * 在 `PlaybackNotificationProvider`。
 */
enum class PlaybackNotificationAction {
    PreviousChapter,
    PlayPause,
    NextChapter,
    SleepTimer,
}

/** 通知动作的文案与顺序（纯 Kotlin，JVM 单测可直接断言）。 */
object PlaybackNotificationActions {

    /** 固定顺序，通知 provider 与测试共用。 */
    val actions: List<PlaybackNotificationAction> = listOf(
        PlaybackNotificationAction.PreviousChapter,
        PlaybackNotificationAction.PlayPause,
        PlaybackNotificationAction.NextChapter,
        PlaybackNotificationAction.SleepTimer,
    )

    /** 播放/暂停按钮的文案：在播时显示“暂停”，否则显示“播放”。 */
    fun playPauseTitleRes(playing: Boolean): Int =
        if (playing) R.string.player_pause else R.string.player_play

    /**
     * 睡眠按钮的文案：已设置时显示“睡眠定时：N 分钟”，关闭时显示“睡眠定时：关闭”。
     *
     * 返回的字符串资源在使用时统一多传一个分钟数参数；关闭文案没有占位符，
     * 多余参数会被忽略。
     */
    fun sleepTitleRes(sleepMinutes: Int): Int =
        if (sleepMinutes > 0) {
            R.string.player_notification_sleep_minutes
        } else {
            R.string.player_notification_sleep_off
        }

    /** 生成睡眠按钮的最终文案；[minutes] 为 0 时走关闭文案。 */
    fun sleepTitle(context: Context, minutes: Int): CharSequence =
        context.getString(sleepTitleRes(minutes), minutes)
}
