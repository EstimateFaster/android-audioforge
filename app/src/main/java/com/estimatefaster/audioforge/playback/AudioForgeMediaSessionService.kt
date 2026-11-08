package com.estimatefaster.audioforge.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.estimatefaster.audioforge.R
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 后台播放服务（执行计划 §10.4、§11.2、§15 P5-08）。
 *
 * 职责：
 * - 持有进程内唯一的 [ExoPlayer]，`AudioAttributes(USAGE_MEDIA, CONTENT_TYPE_SPEECH)`
 *   且 `handleAudioFocus = true`：由 Media3 处理音频焦点、耳机/蓝牙按键；
 * - 开启 `setWakeMode(C.WAKE_MODE_LOCAL)` 并处理耳机拔出（`handleAudioBecomingNoisy`），
 *   锁屏后继续播放；Manifest 的 `mediaPlayback` 前台服务类型保证后台存活；
 * - 通过 [MediaSession] 暴露章节命令与睡眠定时 command，通知/锁屏/蓝牙都能触发；
 * - 服务侧队列：本章的 MediaItem 交给 ExoPlayer 原生逐段前进，章末再查
 *   [PlaybackQueueStore] 决定跨章（连续播放开）或停在章末；
 * - 睡眠定时到点 `pause()` 并把通知按钮状态清回“关闭”。
 *
 * 本类及 Media3 的 `MediaController` / `SessionToken` / `ControllerInfo` 在
 * Media3 1.11.1 仍带 `@UnstableApi`，因此用 `androidx.annotation.OptIn` 显式 opt-in。
 */
@OptIn(UnstableApi::class)
class AudioForgeMediaSessionService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var sleepTimer: SleepTimer
    private lateinit var sessionActivity: PendingIntent
    private lateinit var notificationProvider: PlaybackNotificationProvider

    /** 当前睡眠档位；0 表示关闭。通知按钮文案据此刷新。 */
    private var sleepMinutes: Int = PlaybackCommands.SLEEP_OFF_MINUTES

    /** 最近一次 MediaItem 元数据里的章节/片段位置；命令与章末判断用。 */
    private var currentChapterIndex: Int = -1
    private var currentSegmentIndex: Int = -1

    /** 通知权限/渠道不可用时只降级通知，不阻断播放。 */
    private var notificationsAvailable: Boolean = true

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            PlaybackMediaItems.chapterIndexOf(mediaItem)?.let { currentChapterIndex = it }
            PlaybackMediaItems.segmentIndexOf(mediaItem)?.let { currentSegmentIndex = it }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) advanceAtChapterEnd()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "播放错误：${error.errorCodeName}", error)
        }
    }

    private val sessionCallback = object : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // 通知/锁屏使用的内部 MediaController 也走 onConnect；把自定义命令加入
            // 可用集合后，DefaultMediaNotificationProvider 生成的 action 才可执行。
            // 受信/非受信控制器沿用 Media3 的默认命令集，不额外放开播放器命令。
            val baseCommands = if (controller.isTrusted) {
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
            } else {
                MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_SESSION_COMMANDS
            }
            val playerCommands = if (controller.isTrusted) {
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            } else {
                MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_PLAYER_COMMANDS
            }
            val commands = baseCommands.buildUpon()
                .add(PlaybackSessionCommands.command(PlaybackCommands.ACTION_NEXT_CHAPTER))
                .add(PlaybackSessionCommands.command(PlaybackCommands.ACTION_PREV_CHAPTER))
                .add(PlaybackSessionCommands.command(PlaybackCommands.ACTION_SLEEP_TIMER))
                .add(PlaybackSessionCommands.command(PlaybackCommands.ACTION_SET_CONTINUOUS))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setAvailablePlayerCommands(playerCommands)
                .setSessionActivity(sessionActivity)
                .setCustomLayout(notificationProvider.customLayout())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: androidx.media3.session.SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> = when (customCommand.customAction) {
            PlaybackCommands.ACTION_NEXT_CHAPTER -> handleChapterStep(next = true)
            PlaybackCommands.ACTION_PREV_CHAPTER -> handleChapterStep(next = false)
            PlaybackCommands.ACTION_SLEEP_TIMER -> handleSleepTimer(args)
            PlaybackCommands.ACTION_SET_CONTINUOUS -> handleSetContinuous(args)
            else -> super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensurePlaybackChannel()
        notificationsAvailable = NotificationManagerCompat.from(this).areNotificationsEnabled()
        if (!notificationsAvailable) {
            Log.w(TAG, "通知不可用：继续后台播放，媒体通知可能不显示")
        }
        sessionActivity = PlaybackSessionCommands.openPlayerPendingIntent(this)

        sleepTimer = SleepTimer(serviceScope) {
            sleepMinutes = PlaybackCommands.SLEEP_OFF_MINUTES
            mediaSession?.player?.pause()
            refreshNotification()
        }

        val player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            setWakeMode(C.WAKE_MODE_LOCAL)
            setHandleAudioBecomingNoisy(true)
            addListener(playerListener)
        }

        notificationProvider = PlaybackNotificationProvider(
            appContext = this,
            channelId = CHANNEL_PLAYBACK,
            channelNameResId = R.string.notif_channel_playback,
            sleepMinutes = { sleepMinutes },
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(sessionCallback)
            .build()
        setMediaNotificationProvider(notificationProvider)

        if (!notificationsAvailable) {
            // 空闲时不再尝试起通知；真正播放时仍由 Media3 走前台服务，失败会降级。
            setShowNotificationForIdlePlayer(
                MediaSessionService.SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER,
            )
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * 用户从最近任务划掉 App：停止并释放，不做后台续播（P5-08 保持最小行为）。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        sleepTimer.cancel()
        sleepMinutes = PlaybackCommands.SLEEP_OFF_MINUTES
        mediaSession?.player?.stop()
        releaseSession()
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        releaseSession()
        super.onDestroy()
    }

    /**
     * 通知构建/前台服务启动失败时降级为“继续播放但不显示媒体通知”。
     *
     * Android 13+ 用户拒绝 POST_NOTIFICATIONS，或部分 ROM 限制媒体通知时，
     * Media3 可能在更新通知时抛运行时异常；这里兜住，避免播放被通知问题打断。
     */
    override fun onUpdateNotificationAsync(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ): ListenableFuture<Void> = try {
        super.onUpdateNotificationAsync(session, startInForegroundRequired)
    } catch (error: RuntimeException) {
        Log.w(TAG, "媒体通知更新失败，降级为无通知继续播放", error)
        Futures.immediateVoidFuture()
    }

    // ------------------------------------------------------------ 章节命令

    private fun handleChapterStep(next: Boolean): ListenableFuture<SessionResult> {
        val queue = PlaybackQueueStore.current
        if (queue.isEmpty) {
            return Futures.immediateFuture(
                SessionResult(SessionError.ERROR_INVALID_STATE),
            )
        }
        val current = resolveCurrentChapterIndex()
        val target = if (next) {
            queue.nextChapterIndex(current)
        } else {
            queue.previousChapterIndex(current)
        }
        if (target == null) {
            return Futures.immediateFuture(SessionResult(SessionError.INFO_CANCELLED))
        }
        val chapter = queue.chapterAt(target)
            ?: return Futures.immediateFuture(SessionResult(SessionError.INFO_CANCELLED))
        if (!loadChapter(target, play = true)) {
            return Futures.immediateFuture(
                SessionResult(SessionError.ERROR_INVALID_STATE),
            )
        }
        return Futures.immediateFuture(
            SessionResult(
                SessionResult.RESULT_SUCCESS,
                PlaybackSessionCommands.chapterResult(target, chapter.chapterId, chapter.title),
            ),
        )
    }

    /** 把某一章的可播片段装进播放器；成功返回 true。 */
    private fun loadChapter(chapterIndex: Int, play: Boolean): Boolean {
        val chapter = PlaybackQueueStore.current.chapterAt(chapterIndex) ?: return false
        val player = mediaSession?.player ?: return false
        val items = PlaybackMediaItems.of(chapter, chapterIndex)
        if (items.isEmpty()) return false
        currentChapterIndex = chapterIndex
        currentSegmentIndex = chapter.firstItem?.segmentIndex ?: -1
        player.setMediaItems(items, /* startIndex = */ 0, /* startPositionMs = */ 0L)
        player.prepare()
        if (play) player.play() else player.pause()
        return true
    }

    /**
     * 一条片段播完（ExoPlayer 报 `STATE_ENDED`）。
     *
     * 本章还有下一段时优先 seek 过去（正常不会走到，原生队列应已自动前进）；
     * 最后一段才查连续播放开关，决定进入下一章还是停在章末。
     */
    private fun advanceAtChapterEnd() {
        val queue = PlaybackQueueStore.current
        if (queue.isEmpty) return
        val chapterIndex = resolveCurrentChapterIndex() ?: return
        val segmentIndex = resolveCurrentSegmentIndex() ?: return
        when (val advance = queue.advanceAfter(chapterIndex, segmentIndex)) {
            is PlaybackAdvance.NextItem -> {
                val itemIndex = queue.indexOfSegment(chapterIndex, advance.item.segmentIndex)
                val player = mediaSession?.player
                if (itemIndex != null && player != null) {
                    player.seekTo(itemIndex, 0L)
                    player.play()
                }
            }

            is PlaybackAdvance.NextChapter -> loadChapter(advance.chapterIndex, play = true)

            PlaybackAdvance.Stop -> Unit
        }
    }

    // ------------------------------------------------------------ 睡眠定时

    private fun handleSleepTimer(args: Bundle): ListenableFuture<SessionResult> {
        val explicitMinutes = PlaybackSessionCommands.parseSleepMinutes(args)
        val minutes = explicitMinutes ?: PlaybackCommands.nextSleepMinutes(sleepMinutes)
        if (!PlaybackCommands.isValidSleepMinutes(minutes)) {
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
        }
        if (minutes == PlaybackCommands.SLEEP_OFF_MINUTES) {
            sleepTimer.cancel()
            sleepMinutes = PlaybackCommands.SLEEP_OFF_MINUTES
        } else {
            sleepTimer.start(minutes)
            sleepMinutes = minutes
        }
        refreshNotification()
        return Futures.immediateFuture(
            SessionResult(
                SessionResult.RESULT_SUCCESS,
                PlaybackSessionCommands.sleepResult(minutes),
            ),
        )
    }

    private fun handleSetContinuous(args: Bundle): ListenableFuture<SessionResult> {
        val enabled = PlaybackSessionCommands.parseContinuous(
            args,
            default = PlaybackQueueStore.current.continuous,
        )
        PlaybackQueueStore.updateContinuous(enabled)
        return Futures.immediateFuture(
            SessionResult(
                SessionResult.RESULT_SUCCESS,
                PlaybackSessionCommands.continuousExtras(enabled),
            ),
        )
    }

    // ------------------------------------------------------------ 杂项

    /** 刷新媒体通知与系统自定义布局（睡眠档位变化、到点关闭）。 */
    private fun refreshNotification() {
        triggerNotificationUpdate()
        mediaSession?.setCustomLayout(notificationProvider.customLayout())
    }

    private fun resolveCurrentChapterIndex(): Int? {
        val fromMetadata = PlaybackMediaItems.chapterIndexOf(mediaSession?.player?.currentMediaItem)
        if (fromMetadata != null) {
            currentChapterIndex = fromMetadata
            return fromMetadata
        }
        return currentChapterIndex.takeIf { it >= 0 }
    }

    private fun resolveCurrentSegmentIndex(): Int? {
        val fromMetadata = PlaybackMediaItems.segmentIndexOf(mediaSession?.player?.currentMediaItem)
        if (fromMetadata != null) {
            currentSegmentIndex = fromMetadata
            return fromMetadata
        }
        return currentSegmentIndex.takeIf { it >= 0 }
    }

    private fun releaseSession() {
        val session = mediaSession ?: return
        mediaSession = null
        session.player.removeListener(playerListener)
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
        private const val TAG = "AudioForgeMedia"

        /** 媒体通知渠道 id（§11.2）；Manifest 里同时声明 mediaPlayback 前台服务类型。 */
        const val CHANNEL_PLAYBACK = "playback"
    }
}
