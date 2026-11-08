package com.estimatefaster.audioforge.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 播放器内容入口。
 *
 * 宿主用 Room 仓库与音频存储实现它，把整本书的章节与片段映射成 [PlayerContent]；
 * 纯 JVM 测试注入 fake 即可，不需要 Android。加载失败时 [PlayerViewModel] 显示错误态
 * 并允许重试。
 */
fun interface PlayerContentSource {
    suspend fun load(): PlayerContent
}

/**
 * 播放器回报的实时播放状态。
 *
 * [chapterId] / [segmentIndex] 用于“耳机或锁屏切句”、适配器 `next()` 之后让页面同步
 * 高亮；页面自己发起的切章/切句会先乐观更新，再被这里的值校正。
 */
data class PlayerPlaybackState(
    val chapterId: Long? = null,
    val segmentIndex: Int? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
)

/**
 * 播放队列项：一条可播放的片段音频。
 *
 * [audioPath] 由内容源给出（相对 App 私有根目录），生产适配器用音频存储解析成文件；
 * 单测不需要真实文件。[subtitle] 进媒体通知，通常是角色名。
 */
data class PlayerTrack(
    val chapterId: Long,
    val chapterIndex: Int,
    val segmentIndex: Int,
    val title: String,
    val subtitle: String?,
    val audioPath: String,
    val durationMs: Long = 0L,
)

/**
 * 播放控制门面（P5-07）。
 *
 * 抽象出来有两个目的：
 * - 纯 JVM 单测用 fake 断言“按了什么、传了什么”，不碰 Media3；
 * - 生产实现 `PlayerControllerGateway` 才依赖 PlayerController 与 MediaController。
 *
 * 队列与“当前项”由实现持有：页面只发 [open] / [next] / [previous] 等命令，真实进度
 * 通过 [playback] 回流。`next` / `previous` 是队列内相邻项（通常是下一句/上一句），
 * 章节切换由页面调用 [open] 指定起始项，避免“下一句”越过本章边界。
 */
interface PlayerGateway {
    val playback: StateFlow<PlayerPlaybackState>

    /** 装载整个队列并从 [startIndex] 开始播放（替换旧队列并立即起播）。 */
    fun open(tracks: List<PlayerTrack>, startIndex: Int)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun next()

    fun previous()

    fun setSpeed(speed: Float)

    /** 分钟数；小于等于 0 等价于取消（由播放器侧的睡眠定时器解释）。 */
    fun sleepTimer(minutes: Int)

    /** 由门面的持有者（通常是应用级容器）释放；页面退出不调用，后台播放要继续。 */
    fun release()
}

/**
 * 播放器页 ViewModel（P5-07 / 执行计划 §15 P5-07）。
 *
 * 职责：
 * - [PlayerContentSource] 加载整本书，展平成可播放片段队列 [PlayerTrack]；
 * - 维护当前章/当前句、进度、播放态、倍速、睡眠定时与章节列表展开态；
 * - 所有控制都转发给 [PlayerGateway]，页面不直接接触 PlayerController；
 * - 监听 [PlayerGateway.playback] 校正进度与高亮，适配器自己切句或锁屏切句也能同步。
 *
 * 语义：
 * - 上一章/下一章只在有音频的章节之间跳，边界返回 null（按钮禁用）；
 * - 上一句/下一句只在当前章内跳并跳过无音频段，边界返回 null；
 * - 章节列表点击直接起播该章第一句有音频的片段；
 * - 睡眠定时只负责“选项与剩余”的 UI 状态，真正的到点暂停由播放器侧的定时器完成
 *   （[PlayerGateway.sleepTimer] 只转发分钟数）。
 *
 * 线程约束：与 `viewModelScope` 一致，所有状态更新在主线程。
 *
 * @param contentSource 内容加载器（宿主用仓库/存储实现）
 * @param gateway 播放控制门面（生产用 PlayerControllerGateway）
 */
class PlayerViewModel(
    private val contentSource: PlayerContentSource,
    private val gateway: PlayerGateway,
) : ViewModel() {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** 全书可播放片段队列（章节顺序 + 段顺序）；内容加载后一次性构建。 */
    private var queue: List<PlayerTrack> = emptyList()

    private var loadJob: Job? = null
    private var sleepTicker: Job? = null

    /** 是否已经向播放器装载过队列；用于区分“首次播放”和“暂停后恢复”。 */
    private var started: Boolean = false

    private val playbackJob: Job = viewModelScope.launch {
        gateway.playback.collect { playback -> syncFromPlayback(playback) }
    }

    init {
        load()
    }

    /**
     * 首次加载/重试：取消旧任务与睡眠倒计时，回到加载态后重新读取内容。
     *
     * 倍速与章节列表展开态在重试时保留；睡眠定时会重置为关闭（重试不应该留下一个
     * 已经无人消费的倒计时）。
     */
    fun load() {
        loadJob?.cancel()
        val previous = _state.value
        if (previous.sleepTimer != SleepTimerOption.Off) {
            // 重试会丢弃本地倒计时，播放器侧的定时器也要一起取消，避免稍后意外暂停。
            gateway.sleepTimer(0)
        }
        cancelSleepTicker()
        started = false
        queue = emptyList()
        _state.value = PlayerUiState(
            loading = true,
            speed = previous.speed,
            chaptersExpanded = previous.chaptersExpanded,
        )
        loadJob = viewModelScope.launch {
            try {
                applyContent(contentSource.load())
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(loading = false, loadError = throwable.toLoadError())
                }
            }
        }
    }

    /** 错误态重试。 */
    fun retry() {
        load()
    }

    /** 播放/恢复：还没装载过队列时从当前选中的片段开始。 */
    fun play() {
        val current = _state.value
        if (current.isEmpty || !current.hasPlayableAudio) return
        if (!started) {
            startTrack(current.currentChapterIndex, current.currentSegmentIndex)
            return
        }
        gateway.play()
        _state.update { it.copy(isPlaying = true) }
    }

    /** 暂停；从未起播时是安全 no-op。 */
    fun pause() {
        if (!started) return
        gateway.pause()
        _state.update { it.copy(isPlaying = false) }
    }

    fun togglePlayPause() {
        if (_state.value.isPlaying) pause() else play()
    }

    /**
     * 按进度条比例 seek。
     *
     * 未起播或时长未知时忽略，避免把“还没加载的 0 秒”当成可跳转目标。
     */
    fun seekTo(fraction: Float) {
        val current = _state.value
        if (!started || current.durationMs <= 0L) return
        val target = seekTargetMs(fraction, current.durationMs)
        gateway.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    /** 下一有音频章；已在最后一章时 no-op。 */
    fun nextChapter() {
        val target = _state.value.nextChapterIndex() ?: return
        val chapter = _state.value.chapters[target]
        startTrack(target, chapter.firstPlayableIndex)
    }

    /** 上一有音频章；已在第一章时 no-op。 */
    fun previousChapter() {
        val target = _state.value.previousChapterIndex() ?: return
        val chapter = _state.value.chapters[target]
        startTrack(target, chapter.firstPlayableIndex)
    }

    /** 章节列表点击：起播该章第一句有音频的片段。 */
    fun selectChapter(chapterId: Long) {
        val current = _state.value
        val index = current.chapters.indexOfFirst { it.chapterId == chapterId }
        if (index < 0) return
        startTrack(index, current.chapters[index].firstPlayableIndex)
    }

    /**
     * 当前章内下一句（跳过无音频段）。
     *
     * 已起播时委托给 [PlayerGateway.next]（队列相邻项）；未起播时直接打开目标片段，
     * 这样用户可以先点“下一句”再播放。
     */
    fun nextSegment() {
        val current = _state.value
        val target = current.nextSegmentIndex() ?: return
        val chapterIndex = current.currentChapterIndex ?: return
        if (!started) {
            startTrack(chapterIndex, target)
            return
        }
        gateway.next()
        publishSegmentTarget(chapterIndex, target)
    }

    /** 当前章内上一句（跳过无音频段）；已到第一句时 no-op。 */
    fun previousSegment() {
        val current = _state.value
        val target = current.previousSegmentIndex() ?: return
        val chapterIndex = current.currentChapterIndex ?: return
        if (!started) {
            startTrack(chapterIndex, target)
            return
        }
        gateway.previous()
        publishSegmentTarget(chapterIndex, target)
    }

    /** 选择倍速档位；重复选择同一档位不会重复下发。 */
    fun setSpeed(speed: PlayerSpeed) {
        if (_state.value.speed == speed) return
        gateway.setSpeed(speed.multiplier)
        _state.update { it.copy(speed = speed) }
    }

    /**
     * 设置睡眠定时。
     *
     * [SleepTimerOption.Off] 会取消播放器侧的定时器并清空剩余；其余选项先同步播放器，
     * 再启动一个 1 秒粒度的倒计时更新 [PlayerUiState.sleepRemainingMs]。到点后只把 UI
     * 切回关闭并标记暂停，真正的暂停由播放器侧定时器执行。
     */
    fun setSleepTimer(option: SleepTimerOption) {
        if (_state.value.sleepTimer == option) return
        gateway.sleepTimer(option.minutes ?: 0)
        cancelSleepTicker()
        val durationMs = sleepTimerDurationMs(option)
        if (durationMs == null) {
            _state.update {
                it.copy(sleepTimer = option, sleepRemainingMs = null)
            }
            return
        }
        _state.update {
            it.copy(sleepTimer = option, sleepRemainingMs = durationMs)
        }
        sleepTicker = viewModelScope.launch {
            var elapsed = 0L
            while (isActive && elapsed < durationMs) {
                delay(SLEEP_TICK_MS)
                elapsed += SLEEP_TICK_MS
                val remaining = sleepTimerRemainingMs(durationMs, elapsed)
                _state.update { it.copy(sleepRemainingMs = remaining) }
            }
            if (elapsed >= durationMs) {
                _state.update {
                    it.copy(
                        sleepTimer = SleepTimerOption.Off,
                        sleepRemainingMs = null,
                        isPlaying = false,
                    )
                }
            }
        }
    }

    /** 展开/收起章节列表。 */
    fun toggleChapterList() {
        _state.update { it.copy(chaptersExpanded = !it.chaptersExpanded) }
    }

    override fun onCleared() {
        loadJob?.cancel()
        playbackJob.cancel()
        cancelSleepTicker()
        super.onCleared()
    }

    private fun applyContent(content: PlayerContent) {
        val chapters = content.chapters
        queue = buildTrackQueue(chapters)
        val firstChapterIndex = chapters.indexOfFirst { it.hasAudio }.takeIf { it >= 0 }
        val firstChapter = firstChapterIndex?.let { chapters[it] }
        val previous = _state.value
        _state.value = PlayerUiState(
            loading = false,
            loadError = null,
            bookTitle = content.bookTitle,
            chapters = chapters,
            currentChapterIndex = firstChapterIndex,
            currentSegmentIndex = firstChapter?.firstPlayableIndex,
            durationMs = firstChapter?.playableSegments?.firstOrNull()
                ?.durationMs?.coerceAtLeast(0L) ?: 0L,
            speed = previous.speed,
            chaptersExpanded = previous.chaptersExpanded,
        )
    }

    private fun buildTrackQueue(chapters: List<PlayerChapterUi>): List<PlayerTrack> =
        chapters.mapIndexed { chapterPosition, chapter ->
            chapter.playableSegments.map { segment ->
                PlayerTrack(
                    chapterId = chapter.chapterId,
                    chapterIndex = chapterPosition,
                    segmentIndex = segment.index,
                    title = chapter.title,
                    subtitle = segment.roleName.takeIf { it.isNotBlank() },
                    audioPath = segment.audioPath.orEmpty(),
                    durationMs = segment.durationMs.coerceAtLeast(0L),
                )
            }
        }.flatten()

    /**
     * 打开队列里的目标片段。
     *
     * 返回 false 表示索引无效或该段没有音频，调用方不需要再做状态更新。
     */
    private fun startTrack(chapterIndex: Int?, segmentIndex: Int?): Boolean {
        val chapterIdx = chapterIndex ?: return false
        val chapter = _state.value.chapters.getOrNull(chapterIdx) ?: return false
        val segmentIdx = segmentIndex ?: chapter.firstPlayableIndex ?: return false
        val queueIndex = queueIndexFor(chapterIdx, segmentIdx) ?: return false
        val track = queue[queueIndex]
        started = true
        gateway.open(queue, queueIndex)
        _state.update {
            it.copy(
                currentChapterIndex = chapterIdx,
                currentSegmentIndex = segmentIdx,
                positionMs = 0L,
                durationMs = track.durationMs.coerceAtLeast(0L),
                isPlaying = true,
            )
        }
        return true
    }

    private fun queueIndexFor(chapterIndex: Int, segmentIndex: Int): Int? =
        queue.indexOfFirst {
            it.chapterIndex == chapterIndex && it.segmentIndex == segmentIndex
        }.takeIf { it >= 0 }

    private fun publishSegmentTarget(chapterIndex: Int, segmentIndex: Int) {
        val segment = _state.value.chapters.getOrNull(chapterIndex)
            ?.segments
            ?.firstOrNull { it.index == segmentIndex }
        _state.update {
            it.copy(
                currentChapterIndex = chapterIndex,
                currentSegmentIndex = segmentIndex,
                positionMs = 0L,
                durationMs = segment?.durationMs?.coerceAtLeast(0L) ?: it.durationMs,
                isPlaying = true,
            )
        }
    }

    /**
     * 用播放器回报校正进度与高亮。
     *
     * 加载中不覆盖状态（内容快照还没到，章节列表为空）；回报的章/段在内容里找不到时
     * 保留页面当前选择，只同步进度。
     */
    private fun syncFromPlayback(playback: PlayerPlaybackState) {
        _state.update { current ->
            if (current.loading) return@update current
            val chapterIndex = playback.chapterId
                ?.let { chapterId -> current.chapters.indexOfFirst { it.chapterId == chapterId } }
                ?.takeIf { it >= 0 }
            val chapter = chapterIndex?.let { current.chapters[it] }
            val segmentIndex = playback.segmentIndex?.takeIf { index ->
                chapter?.segments?.any { it.index == index } == true
            }
            current.copy(
                currentChapterIndex = chapterIndex ?: current.currentChapterIndex,
                currentSegmentIndex = segmentIndex ?: current.currentSegmentIndex,
                positionMs = playback.positionMs.coerceAtLeast(0L),
                durationMs = if (playback.durationMs > 0L) {
                    playback.durationMs
                } else {
                    current.durationMs
                },
                isPlaying = playback.isPlaying,
            )
        }
    }

    private fun cancelSleepTicker() {
        sleepTicker?.cancel()
        sleepTicker = null
    }

    private fun Throwable.toLoadError(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    private companion object {
        /** 睡眠定时 UI 的刷新粒度。 */
        const val SLEEP_TICK_MS = 1_000L
    }
}
