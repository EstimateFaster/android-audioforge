package com.estimatefaster.audioforge.feature.player

import com.estimatefaster.audioforge.core.storage.AudioStorage
import com.estimatefaster.audioforge.playback.PlayerController
import com.estimatefaster.audioforge.playback.PlayerUiState as ControllerPlaybackState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * [PlayerGateway] 的生产实现：把页面命令翻译成 [PlayerController] 调用。
 *
 * 队列是“可播放片段”的扁平列表（章节顺序 + 片段顺序）。`next` / `previous` 在队列内
 * 移动一条片段；章节切换由页面重新 [open] 指定起始项，因此不会把下一章误当成下一句。
 * [PlayerController] 内部仍通过 `MediaController` 连接
 * `AudioForgeMediaSessionService` 里的 ExoPlayer，通知/锁屏控制与这里共享同一个播放器。
 *
 * 音频路径由 [resolveAudio] 解析：默认二级构造器用 [AudioStorage] 把相对路径解析成
 * App 私有目录下的文件；宿主也可以注入自己的解析器（例如测试或迁移期）。
 *
 * 生命周期：本类只持有 [PlayerController] 的观察协程；[release] 停止观察并清空队列，
 * 不释放播放器本身（播放器通常由应用级容器持有，退出播放器页要继续后台播放）。
 */
class PlayerControllerGateway(
    private val controller: PlayerController,
    private val resolveAudio: (String) -> File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : PlayerGateway {

    /** 生产便捷入口：相对路径按 [AudioStorage] 根目录解析。 */
    constructor(
        controller: PlayerController,
        audioStorage: AudioStorage,
    ) : this(
        controller = controller,
        resolveAudio = audioStorage::resolve,
    )

    private var queue: List<PlayerTrack> = emptyList()
    private var currentIndex: Int = -1
    private var speed: Float = 1.0f

    private val _playback = MutableStateFlow(PlayerPlaybackState())
    override val playback: StateFlow<PlayerPlaybackState> = _playback.asStateFlow()

    private val observeJob = scope.launch {
        controller.state.collect { state -> publish(state) }
    }

    override fun open(tracks: List<PlayerTrack>, startIndex: Int) {
        if (tracks.isEmpty()) return
        queue = tracks
        playAt(startIndex.coerceIn(0, tracks.lastIndex))
    }

    override fun play() {
        controller.play()
    }

    override fun pause() {
        controller.pause()
    }

    override fun seekTo(positionMs: Long) {
        controller.seekTo(positionMs)
    }

    override fun next() {
        if (currentIndex < 0 || currentIndex >= queue.lastIndex) return
        playAt(currentIndex + 1)
    }

    override fun previous() {
        if (currentIndex <= 0) {
            // 已经是队列第一项：回到开头，而不是跳出队列。
            controller.seekTo(0L)
            return
        }
        playAt(currentIndex - 1)
    }

    override fun setSpeed(speed: Float) {
        require(speed > 0f) { "倍速必须为正：$speed" }
        this.speed = speed
        controller.setSpeed(speed)
    }

    override fun sleepTimer(minutes: Int) {
        controller.sleepTimer(minutes)
    }

    override fun release() {
        observeJob.cancel()
        scope.cancel()
        queue = emptyList()
        currentIndex = -1
        _playback.value = PlayerPlaybackState()
    }

    private fun playAt(index: Int) {
        val track = queue.getOrNull(index) ?: return
        currentIndex = index
        controller.playChapter(
            file = resolveAudio(track.audioPath),
            title = track.title,
            subtitle = track.subtitle,
        )
        // playChapter 会重建媒体项；部分播放器实现会在重建后重置倍速，这里补一次。
        controller.setSpeed(speed)
        _playback.value = PlayerPlaybackState(
            chapterId = track.chapterId,
            segmentIndex = track.segmentIndex,
            positionMs = 0L,
            durationMs = track.durationMs.coerceAtLeast(0L),
            isPlaying = true,
        )
    }

    private fun publish(state: ControllerPlaybackState) {
        val track = queue.getOrNull(currentIndex)
        _playback.value = PlayerPlaybackState(
            chapterId = track?.chapterId,
            segmentIndex = track?.segmentIndex,
            positionMs = state.positionMs.coerceAtLeast(0L),
            durationMs = state.durationMs.coerceAtLeast(0L),
            isPlaying = state.isPlaying,
        )
    }
}
