package com.estimatefaster.audioforge.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 播放器 UI 状态（执行计划 §10.4）。`StateFlow` 由 [PlayerController] 暴露，
 * UI 只读，不直接接触 MediaController。
 */
data class PlayerUiState(
    val currentTitle: String = "",
    val currentSubtitle: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
)

/**
 * 播放控制门面（执行计划 §10.4、§15 P5-08）。
 *
 * 通过 `MediaController` 连接 [AudioForgeMediaSessionService] 里的 ExoPlayer：
 * - 构造时异步连接，连接完成前调用 [playChapter] / [playQueue] 会暂存，连接后自动起播；
 * - [state] 反射当前标题/副标题/位置/时长/播放态，UI 直接 collect；
 * - 提供 [play] / [pause] / [seekTo] / [setSpeed] / [sleepTimer] / [release]；
 * - [playQueue] 把整本书章节交给服务侧队列：本章内由 ExoPlayer 逐段前进，章末由
 *   服务按连续播放开关跨章；[nextChapter] / [previousChapter] 走 MediaSession
 *   自定义命令，和通知/锁屏按钮是同一条链路；
 * - 睡眠定时由本地 [SleepTimer] 负责 UI 剩余时间，同时把分钟数作为
 *   [PlaybackCommands.ACTION_SLEEP_TIMER] 发给服务，服务到点暂停并刷新通知按钮。
 *
 * [playChapter] 保留为单段入口（P2-14/P5-07 兼容）：只装一个 MediaItem，不跨章；
 * 需要自动续播时改用 [playQueue]。
 *
 * 线程约束：构造与所有方法都应在主线程调用（Media3 `MediaController` 绑定在
 * 应用主 Looper）；[scope] 使用 `Dispatchers.Main.immediate`。
 */
@OptIn(UnstableApi::class)
class PlayerController(
    context: Context,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val released = AtomicBoolean(false)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** 定时器到点回调 `pause()`；[controller] 为空时安全 no-op。 */
    private val sleepTimer = SleepTimer(scope) { controller?.pause() }

    private var controller: MediaController? = null
    private var pendingPlayback: PendingPlayback? = null
    private var pendingSleepMinutes: Int? = null
    private var positionJob: Job? = null

    /** 是否已连上播放服务。 */
    val isConnected: Boolean get() = controller?.isConnected == true

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publishState(player)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.w(TAG, "播放错误：${error.errorCodeName}", error)
            publishState(controller ?: return)
        }
    }

    private val controllerFuture = MediaController.Builder(
        appContext,
        SessionToken(
            appContext,
            ComponentName(appContext, AudioForgeMediaSessionService::class.java),
        ),
    ).buildAsync()

    init {
        controllerFuture.addListener(
            {
                if (released.get()) {
                    // release() 与连接回调竞态：确保底层 controller 最终被释放。
                    runCatching { MediaController.releaseFuture(controllerFuture) }
                    return@addListener
                }
                try {
                    val connected = controllerFuture.get()
                    controller = connected
                    connected.addListener(playerListener)
                    publishState(connected)
                    pendingPlayback?.let { request ->
                        pendingPlayback = null
                        applyQueue(connected, request)
                    }
                    pendingSleepMinutes?.let { minutes ->
                        pendingSleepMinutes = null
                        sendSleepTimer(connected, minutes)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "连接 MediaSessionService 失败", e)
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    /**
     * 播放单个音频文件。[title] / [subtitle] 进入媒体元数据，用于通知与锁屏。
     *
     * 这是 P2-14/P5-07 的单段入口：只装一条 MediaItem，不自动跨章。需要同章连播/
     * 跨章续播时用 [playQueue]。
     */
    fun playChapter(file: File, title: String, subtitle: String?) {
        val item = PlaybackQueueItem(
            mediaId = file.absolutePath,
            uri = Uri.fromFile(file).toString(),
            title = title,
            subtitle = subtitle,
            segmentIndex = 0,
        )
        val chapter = PlaybackQueueChapter(
            chapterId = 0L,
            title = title,
            items = listOf(item),
        )
        playQueue(
            chapters = listOf(chapter),
            startChapterIndex = 0,
            startSegmentIndex = 0,
            continuous = false,
        )
    }

    /**
     * 装载整本书的章节队列并从 [startChapterIndex] / [startSegmentIndex] 起播。
     *
     * 只把**当前章**的 MediaItem 交给播放器，整本书的队列放在进程内
     * [PlaybackQueueStore]；章末由服务决定是否跨章。这样：
     * - 本章内逐段播放由 ExoPlayer 原生完成，不会每段重新 prepare；
     * - 连续播放关闭时不会误播下一章；
     * - 通知/锁屏的“上一章/下一章”命令能拿到完整队列。
     *
     * @param chapters 按书内顺序排列的章节；空章可以保留，队列导航会跳过
     * @param startChapterIndex 起播章在 [chapters] 里的位置，越界时回落到第一个可播章
     * @param startSegmentIndex 起播段 index；null 或找不到时从该章第一段开始
     * @param continuous 章末是否自动进入下一章
     */
    fun playQueue(
        chapters: List<PlaybackQueueChapter>,
        startChapterIndex: Int = 0,
        startSegmentIndex: Int? = null,
        continuous: Boolean = true,
    ) {
        val queue = PlaybackQueue(chapters = chapters, continuous = continuous)
        if (queue.isEmpty) return
        PlaybackQueueStore.set(queue)
        val chapterIndex = if (queue.chapterAt(startChapterIndex) != null) {
            startChapterIndex
        } else {
            queue.firstPlayableChapterIndex() ?: return
        }
        val request = PendingPlayback(queue, chapterIndex, startSegmentIndex)
        val connected = controller
        if (connected == null) {
            pendingPlayback = request
            return
        }
        applyQueue(connected, request)
    }

    /** 下一有音频章（通知/锁屏走同一个 MediaSession 命令）。 */
    fun nextChapter() {
        sendCommand(PlaybackCommands.ACTION_NEXT_CHAPTER)
    }

    /** 上一有音频章。 */
    fun previousChapter() {
        sendCommand(PlaybackCommands.ACTION_PREV_CHAPTER)
    }

    /**
     * 同步连续播放开关；只影响章末，不影响本章内逐段前进。
     * 服务在锁屏/后台也会读取最新的开关。
     */
    fun setContinuous(enabled: Boolean) {
        PlaybackQueueStore.updateContinuous(enabled)
        sendCommand(
            PlaybackCommands.ACTION_SET_CONTINUOUS,
            PlaybackSessionCommands.continuousExtras(enabled),
        )
    }

    fun play() {
        controller?.play()
    }

    fun pause() {
        controller?.pause()
    }

    /** 跳转到绝对位置（毫秒）；负数按 0 处理。 */
    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
    }

    /** 设置倍速；必须为正数（具体范围由后续 UI 限制）。 */
    fun setSpeed(speed: Float) {
        require(speed > 0f) { "倍速必须为正：$speed" }
        controller?.setPlaybackSpeed(speed)
    }

    /**
     * 设置睡眠定时器：[minutes] 分钟后自动暂停。
     * 传 0 或负数等价于取消定时器。
     *
     * 15/30/60 会同步给服务的媒体通知按钮；其它正整数仍只在本地生效（UI 目前只用
     * 这三个档位），避免服务拒绝导致 UI 与播放器状态不一致。
     */
    fun sleepTimer(minutes: Int) {
        if (minutes <= 0) {
            sleepTimer.cancel()
            sendSleepTimer(PlaybackCommands.SLEEP_OFF_MINUTES)
        } else {
            sleepTimer.start(minutes)
            if (PlaybackCommands.isSupportedSleepMinutes(minutes)) {
                sendSleepTimer(minutes)
            } else {
                Log.w(TAG, "睡眠分钟数不受媒体通知支持，仅本地生效：$minutes")
            }
        }
    }

    /** 取消睡眠定时器。 */
    fun cancelSleepTimer() {
        sleepTimer.cancel()
        sendSleepTimer(PlaybackCommands.SLEEP_OFF_MINUTES)
    }

    /** 睡眠定时器剩余毫秒；未启用时返回 `null`。 */
    fun remainingSleepMs(): Long? = sleepTimer.remainingMs()

    /**
     * 断开连接并释放播放器资源；可重复调用。
     * 之后本实例不可复用（需要新实例重新连接）。
     *
     * 注意：服务侧的睡眠定时不会被 release 取消——用户退出界面后定时仍应生效，
     * 由服务到点暂停并清理通知状态。
     */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        positionJob?.cancel()
        positionJob = null
        sleepTimer.cancel()
        pendingPlayback = null
        pendingSleepMinutes = null

        val connected = controller
        controller = null
        if (connected != null) {
            connected.removeListener(playerListener)
            connected.release()
        } else {
            controllerFuture.cancel(false)
        }
        _state.value = PlayerUiState()
        scope.cancel()
    }

    private fun sendCommand(action: String, args: Bundle = Bundle.EMPTY) {
        val connected = controller ?: return
        connected.sendCustomCommand(PlaybackSessionCommands.command(action), args)
    }

    private fun sendSleepTimer(minutes: Int) {
        val connected = controller
        if (connected == null) {
            pendingSleepMinutes = minutes
            return
        }
        sendSleepTimer(connected, minutes)
    }

    private fun sendSleepTimer(connected: MediaController, minutes: Int) {
        connected.sendCustomCommand(
            PlaybackSessionCommands.command(PlaybackCommands.ACTION_SLEEP_TIMER),
            PlaybackSessionCommands.sleepTimerExtras(minutes),
        )
    }

    private fun applyQueue(connected: MediaController, request: PendingPlayback) {
        val chapter = request.queue.chapterAt(request.chapterIndex) ?: return
        val items = PlaybackMediaItems.of(chapter, request.chapterIndex)
        if (items.isEmpty()) return
        val startIndex = request.segmentIndex
            ?.let { segment -> chapter.items.indexOfFirst { it.segmentIndex == segment } }
            ?.takeIf { it >= 0 }
            ?: 0
        connected.setMediaItems(items, startIndex, 0L)
        connected.prepare()
        connected.play()
    }

    private fun publishState(player: Player) {
        val metadata = player.currentMediaItem?.mediaMetadata
        val duration = player.duration
        _state.value = PlayerUiState(
            currentTitle = metadata?.title?.toString()
                ?: player.currentMediaItem?.mediaId.orEmpty(),
            currentSubtitle = metadata?.artist?.toString()?.takeIf { it.isNotBlank() },
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = if (duration > 0L) duration else 0L,
            isPlaying = player.isPlaying,
        )
        if (player.isPlaying) startPositionUpdates() else stopPositionUpdates()
    }

    private fun startPositionUpdates() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch {
            while (isActive && !released.get()) {
                delay(POSITION_UPDATE_INTERVAL_MS)
                controller?.let(::updatePosition)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    private fun updatePosition(player: Player) {
        val duration = player.duration
        _state.value = _state.value.copy(
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = if (duration > 0L) duration else 0L,
            isPlaying = player.isPlaying,
        )
    }

    /** 待起播的队列与起始位置；连接完成前暂存。 */
    private data class PendingPlayback(
        val queue: PlaybackQueue,
        val chapterIndex: Int,
        val segmentIndex: Int?,
    )

    private companion object {
        const val TAG = "AudioForgePlayer"
        const val POSITION_UPDATE_INTERVAL_MS = 500L
    }
}
