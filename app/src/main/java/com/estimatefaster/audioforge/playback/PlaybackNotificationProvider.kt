package com.estimatefaster.audioforge.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.estimatefaster.audioforge.R
import com.google.common.collect.ImmutableList

/**
 * 媒体通知 provider（P5-08）。
 *
 * 复用 Media3 的 [DefaultMediaNotificationProvider] 默认布局（渠道、样式、点按入口、
 * 前台服务行为都不变），只覆盖 [getMediaButtons] 把默认的“上一首/播放/下一首”换成
 * 章节动作 + 睡眠定时：
 *
 * - 上一章 / 下一章：自定义 `SessionCommand`，由服务在章边界处理；
 * - 播放/暂停：标准 `COMMAND_PLAY_PAUSE`，图标随播放态切换；
 * - 睡眠定时：单按钮循环 15 → 30 → 60 → 关闭，标题显示当前档位。
 *
 * 自定义按钮必须能被控制器执行：服务在 `onConnect` 里把对应 `SessionCommand`
 * 加入可用命令。`DefaultMediaNotificationProvider.addNotificationActions` 会为
 * `sessionCommand` 非空的按钮生成自定义 action，因此这里只需给对
 * `CommandButton`（图标用 `setIconResId` 语义的构造器）。
 */
@OptIn(UnstableApi::class)
internal class PlaybackNotificationProvider(
    private val appContext: Context,
    channelId: String,
    channelNameResId: Int,
    private val sleepMinutes: () -> Int,
) : DefaultMediaNotificationProvider(
    appContext,
    DefaultMediaNotificationProvider.NotificationIdProvider {
        DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID
    },
    channelId,
    channelNameResId,
) {

    init {
        setSmallIcon(R.drawable.ic_stat_audioforge)
    }

    protected override fun getMediaButtons(
        session: MediaSession,
        playerCommands: Player.Commands,
        customLayout: ImmutableList<CommandButton>,
        showPauseButton: Boolean,
    ): ImmutableList<CommandButton> {
        val minutes = sleepMinutes()
        val buttons = PlaybackNotificationActions.actions.map { action ->
            when (action) {
                PlaybackNotificationAction.PreviousChapter -> customButton(
                    icon = CommandButton.ICON_PREVIOUS,
                    titleRes = R.string.player_previous_chapter,
                    commandAction = PlaybackCommands.ACTION_PREV_CHAPTER,
                )

                PlaybackNotificationAction.PlayPause -> CommandButton
                    .Builder(
                        if (showPauseButton) CommandButton.ICON_PAUSE
                        else CommandButton.ICON_PLAY,
                    )
                    .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                    .setDisplayName(
                        appContext.getString(
                            PlaybackNotificationActions.playPauseTitleRes(showPauseButton),
                        ),
                    )
                    .build()

                PlaybackNotificationAction.NextChapter -> customButton(
                    icon = CommandButton.ICON_NEXT,
                    titleRes = R.string.player_next_chapter,
                    commandAction = PlaybackCommands.ACTION_NEXT_CHAPTER,
                )

                PlaybackNotificationAction.SleepTimer -> CommandButton
                    .Builder(CommandButton.ICON_SETTINGS)
                    .setSessionCommand(
                        PlaybackSessionCommands.command(PlaybackCommands.ACTION_SLEEP_TIMER),
                    )
                    .setDisplayName(PlaybackNotificationActions.sleepTitle(appContext, minutes))
                    .build()
            }
        }
        return ImmutableList.copyOf(buttons)
    }

    /**
     * 让紧凑视图固定显示“上一章 / 播放暂停 / 下一章”，睡眠按钮留在展开视图。
     *
     * 默认实现的 compact 索引依赖 `CommandButton` 的 slot；我们的四个按钮都是
     * 自定义顺序，显式返回索引避免出现 -1 被系统忽略或越界告警。
     */
    protected override fun addNotificationActions(
        session: MediaSession,
        mediaButtons: ImmutableList<CommandButton>,
        builder: NotificationCompat.Builder,
        actionFactory: MediaNotification.ActionFactory,
    ): IntArray {
        super.addNotificationActions(session, mediaButtons, builder, actionFactory)
        return if (mediaButtons.size >= COMPACT_ACTION_COUNT) {
            intArrayOf(0, 1, 2)
        } else {
            IntArray(0)
        }
    }

    /**
     * 系统媒体控件（Android 13+ 的媒体通知/锁屏）使用的自定义布局。
     *
     * 只放章节前后与睡眠三个自定义按钮；播放/暂停由系统按标准播放命令提供。
     * slot 采用 BACK / FORWARD / OVERFLOW，避免占用系统的中央播放位。
     */
    fun customLayout(): List<CommandButton> = listOf(
        customButton(
            icon = CommandButton.ICON_PREVIOUS,
            titleRes = R.string.player_previous_chapter,
            commandAction = PlaybackCommands.ACTION_PREV_CHAPTER,
            slot = CommandButton.SLOT_BACK,
        ),
        customButton(
            icon = CommandButton.ICON_NEXT,
            titleRes = R.string.player_next_chapter,
            commandAction = PlaybackCommands.ACTION_NEXT_CHAPTER,
            slot = CommandButton.SLOT_FORWARD,
        ),
        CommandButton.Builder(CommandButton.ICON_SETTINGS)
            .setSessionCommand(
                PlaybackSessionCommands.command(PlaybackCommands.ACTION_SLEEP_TIMER),
            )
            .setDisplayName(PlaybackNotificationActions.sleepTitle(appContext, sleepMinutes()))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    private fun customButton(
        icon: Int,
        titleRes: Int,
        commandAction: String,
        slot: Int? = null,
    ): CommandButton {
        val builder = CommandButton.Builder(icon)
            .setSessionCommand(PlaybackSessionCommands.command(commandAction))
            .setDisplayName(appContext.getString(titleRes))
        if (slot != null) builder.setSlots(slot)
        return builder.build()
    }

    private companion object {
        /** 紧凑视图最多显示前三个 action。 */
        const val COMPACT_ACTION_COUNT = 3
    }
}
