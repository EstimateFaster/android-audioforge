package com.estimatefaster.audioforge.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
 * 播放控制门面（执行计划 §10.4）。
 *
 * 通过 `MediaController` 连接 [AudioForgeMediaSessionService] 里的 ExoPlayer：
 * - 构造时异步连接，连接完成前调用 [playChapter] 会暂存，连接后自动播放；
 * - [state] 反射当前标题/副标题/位置/时长/播放态，UI 直接 collect；
 * - 提供 [play] / [pause] / [seekTo] / [setSpeed] / [sleepTimer] / [release]；
 * - 睡眠定时由 [SleepTimer] 协程实现，到点 `pause()`，取消不会误触发。
 *
 * 播放位置**不**写 DataStore（P5 再实现续播）；本类不依赖 AppContainer，
 * 由后续 P2-02/UI 接线为单例。
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
    private var pendingChapter: ChapterRequest? = null
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
                    pendingChapter?.let { request ->
                        pendingChapter = null
                        playChapter(request.file, request.title, request.subtitle)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "连接 MediaSessionService 失败", e)
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    /**
     * 播放单个章节音频文件。[title] / [subtitle] 进入媒体元数据，用于通知与锁屏。
     * 仅支持本地文件（章节音频由 AudioStorage 产出）。
     */
    fun playChapter(file: File, title: String, subtitle: String?) {
        val request = ChapterRequest(file, title, subtitle)
        val connected = controller
        if (connected == null) {
            pendingChapter = request
            return
        }
        connected.setMediaItem(request.toMediaItem())
        connected.prepare()
        connected.play()
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
     */
    fun sleepTimer(minutes: Int) {
        if (minutes <= 0) {
            sleepTimer.cancel()
        } else {
            sleepTimer.start(minutes)
        }
    }

    /** 取消睡眠定时器。 */
    fun cancelSleepTimer() {
        sleepTimer.cancel()
    }

    /** 睡眠定时器剩余毫秒；未启用时返回 `null`。 */
    fun remainingSleepMs(): Long? = sleepTimer.remainingMs()

    /**
     * 断开连接并释放播放器资源；可重复调用。
     * 之后本实例不可复用（需要新实例重新连接）。
     */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        positionJob?.cancel()
        positionJob = null
        sleepTimer.cancel()

        val connected = controller
        controller = null
        pendingChapter = null
        if (connected != null) {
            connected.removeListener(playerListener)
            connected.release()
        } else {
            controllerFuture.cancel(false)
        }
        _state.value = PlayerUiState()
        scope.cancel()
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

    private data class ChapterRequest(
        val file: File,
        val title: String,
        val subtitle: String?,
    ) {
        fun toMediaItem(): MediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setMediaId(file.absolutePath)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(subtitle)
                    .build(),
            )
            .build()
    }

    private companion object {
        const val TAG = "AudioForgePlayer"
        const val POSITION_UPDATE_INTERVAL_MS = 500L
    }
}
