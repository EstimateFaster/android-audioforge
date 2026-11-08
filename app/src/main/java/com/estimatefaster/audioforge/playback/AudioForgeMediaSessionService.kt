package com.estimatefaster.audioforge.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.estimatefaster.audioforge.R

/**
 * 后台播放服务（执行计划 §10.4、§11.2）。
 *
 * - 持有进程内唯一的 [ExoPlayer]，`AudioAttributes(USAGE_MEDIA, CONTENT_TYPE_SPEECH)`
 *   且 `handleAudioFocus = true`：由 Media3 处理音频焦点、耳机/蓝牙按键；
 * - [MediaSession] 暴露给 [PlayerController]（[androidx.media3.session.MediaController]）
 *   以及系统媒体控件（锁屏/通知/蓝牙）；
 * - 通知使用 Media3 的 [DefaultMediaNotificationProvider]，渠道 id 固定为 [CHANNEL_PLAYBACK]
 *   （低重要性，与 `NotificationHelper` 的渠道约定一致）；
 * - `onTaskRemoved`（用户从最近任务划掉 App）停止播放并释放，避免后台残留。
 *
 * 提醒：本类及 Media3 的 `MediaController` / `SessionToken` / `ControllerInfo` 在
 * Media3 1.11.1 仍带 `@UnstableApi`，因此用 `androidx.annotation.OptIn` 显式 opt-in。
 */
@OptIn(UnstableApi::class)
class AudioForgeMediaSessionService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        ensurePlaybackChannel()

        val player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
        }
        mediaSession = MediaSession.Builder(this, player).build()

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_PLAYBACK)
            .setChannelName(R.string.notif_channel_playback)
            .build()
        notificationProvider.setSmallIcon(R.drawable.ic_stat_audioforge)
        setMediaNotificationProvider(notificationProvider)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * 用户从最近任务划掉 App：停止并释放，不做后台续播（最小实现）。
     * 播放位置不落库（P5 再接 DataStore）。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        mediaSession?.player?.stop()
        releaseSession()
        stopSelf()
    }

    override fun onDestroy() {
        releaseSession()
        super.onDestroy()
    }

    private fun releaseSession() {
        val session = mediaSession ?: return
        mediaSession = null
        session.player.release()
        session.release()
    }

    /** 创建/校正 `playback` 渠道：低重要性、不显示角标（§11.2）。 */
    private fun ensurePlaybackChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_PLAYBACK) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PLAYBACK,
                getString(R.string.notif_channel_playback),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_playback_desc)
                setShowBadge(false)
            },
        )
    }

    companion object {
        /** 媒体通知渠道 id（§11.2）；Manifest 里同时声明 mediaPlayback 前台服务类型。 */
        const val CHANNEL_PLAYBACK = "playback"
    }
}
