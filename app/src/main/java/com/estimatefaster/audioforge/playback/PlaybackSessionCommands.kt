package com.estimatefaster.audioforge.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.session.SessionCommand
import com.estimatefaster.audioforge.MainActivity

/**
 * 自定义命令的 Android/Media3 包装（P5-08）。
 *
 * [PlaybackCommands] 保持纯 Kotlin；这里负责生成 `SessionCommand`、`Bundle` 参数与结果，
 * 以及媒体通知点击后回到播放器的 `PendingIntent`。所有 Bundle key 都集中在
 * [PlaybackCommands]，避免服务与控制器各写一套字符串。
 */
object PlaybackSessionCommands {

    /** 构造一个只带 action 的自定义命令（参数通过单独的 Bundle 传）。 */
    fun command(action: String): SessionCommand = SessionCommand(action, Bundle.EMPTY)

    /** 睡眠定时参数：显式分钟数（0 表示取消）。 */
    fun sleepTimerExtras(minutes: Int): Bundle = Bundle().apply {
        putInt(PlaybackCommands.EXTRA_SLEEP_MINUTES, minutes)
    }

    /**
     * 从命令参数里取分钟数。
     *
     * 返回 null 表示**没有传**参数，服务应解释为“按通知按钮循环”；这与显式传 0
     * （取消）是两种不同语义，不能合并。
     */
    fun parseSleepMinutes(args: Bundle?): Int? {
        if (args == null || !args.containsKey(PlaybackCommands.EXTRA_SLEEP_MINUTES)) return null
        return args.getInt(PlaybackCommands.EXTRA_SLEEP_MINUTES)
    }

    /** 连续播放参数。 */
    fun continuousExtras(enabled: Boolean): Bundle = Bundle().apply {
        putBoolean(PlaybackCommands.EXTRA_CONTINUOUS, enabled)
    }

    /** 从参数里取连续开关；缺省返回 [default]。 */
    fun parseContinuous(args: Bundle?, default: Boolean = true): Boolean {
        if (args == null || !args.containsKey(PlaybackCommands.EXTRA_CONTINUOUS)) return default
        return args.getBoolean(PlaybackCommands.EXTRA_CONTINUOUS, default)
    }

    /** 跨章命令成功后的结果：目标章位置/主键/标题。 */
    fun chapterResult(chapterIndex: Int, chapterId: Long, title: String): Bundle = Bundle().apply {
        putInt(PlaybackCommands.EXTRA_CHAPTER_INDEX, chapterIndex)
        putLong(PlaybackCommands.EXTRA_CHAPTER_ID, chapterId)
        putString(PlaybackCommands.EXTRA_CHAPTER_TITLE, title)
    }

    /** 睡眠定时命令成功后的结果：当前生效分钟数（0 表示已关闭）。 */
    fun sleepResult(minutes: Int): Bundle = Bundle().apply {
        putInt(PlaybackCommands.EXTRA_SLEEP_MINUTES, minutes)
    }

    /**
     * 媒体通知/锁屏点击后的入口：显式打开 [MainActivity]，带播放器深链。
     *
     * 目前 App 的 NavHost 还没有播放器路由，因此这里只保证“回到 App + 携带
     * [PlaybackCommands.PLAYER_DEEP_LINK]”；后续接线播放器路由时无需改服务。
     */
    fun openPlayerIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse(PlaybackCommands.PLAYER_DEEP_LINK)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

    /** 供 [AudioForgeMediaSessionService] 的 `setSessionActivity` 使用。 */
    fun openPlayerPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_OPEN_PLAYER,
            openPlayerIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private const val REQUEST_OPEN_PLAYER = 0x508
}
