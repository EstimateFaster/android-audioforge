package com.estimatefaster.audioforge.feature.script

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 播放跟随的一次快照（P5-03 / 执行计划 §12.4 S06、§15 P5-03）。
 *
 * [currentIndex] 是**整章 0 基位置**（不是筛选后的行号），与
 * [ScriptSegmentUi.index] / [ScriptUiState.playingSegmentIndex] 同一坐标系；
 * 筛选只影响 [ScriptUiState.scrollTargetFor] 的返回值，不会改变这里的含义。
 *
 * @property currentIndex 当前应高亮/播放的片段；`null` 表示没有在跟随播放
 * @property shouldScroll 当前段是否由本控制器“切过来”（播放、上一句/下一句、
 *   自动前进、外部播放器切段）；暂停/恢复不置位，页面据此只在换段时滚动
 * @property chapterFinished 本章最后一首可播放片段已经播完
 * @property nextChapterRequested 本章播完且连续播放开启；宿主可据此加载下一章。
 *   播放器/章节边界回调是同一信号的两种消费方式，页面只需读其中一个
 * @property skippedNoAudio 最近一次 [PlaybackFollowController.playFrom] 因为请求段
 *   没有音频而跳到了后面的段，或请求段之后已经没有可播放段；UI 用于显示跳过提示
 */
data class FollowState(
    val currentIndex: Int? = null,
    val shouldScroll: Boolean = false,
    val chapterFinished: Boolean = false,
    val nextChapterRequested: Boolean = false,
    val skippedNoAudio: Boolean = false,
) {

    val isActive: Boolean
        get() = currentIndex != null

    companion object {
        /** 空列表/无播放时的稳定初始值。 */
        val Idle = FollowState()
    }
}

/**
 * 播放跟随 + 连播状态机（P5-03）。
 *
 * 纯 Kotlin：不依赖 Android / Media3 / ViewModel，可在 JVM 单测里直接驱动。
 * 输入只有三样：
 * 1. 本章**有音频**的片段 index 列表（升序去重后保存，见 [setPlayableIndexes]）；
 * 2. 播放器状态（当前 index / 位置 / 时长 / 是否在播，见 [onPlaybackState]）；
 * 3. 连续播放开关（[setContinuous]）与可选章节边界回调。
 *
 * 语义约定：
 * - “下一段”永远指本章 index 更大的下一个**有音频**段，中间的无声段被跳过；
 * - 最后一段完成：连续播放开 → `chapterFinished + nextChapterRequested`，同时触发
 *   构造时传入的章节边界回调；连续播放关 → 只 `chapterFinished`，停在原地；
 * - `onSegmentFinished` 对过期/越界 index 是幂等的：只有 index 等于当前段才前进，
 *   因此播放器重复上报完成事件不会跳段；
 * - 播放器上报 `position >= duration` 且由播放转为暂停/停止时，等价于该段完成，
 *   这样不依赖调用方额外发完成事件也能自动前进；
 * - 暂停/恢复只改跟随的“是否在播”语义（由调用方 [onPlaybackState] 传入），
 *   `currentIndex` 与 `shouldScroll` 保持不变，避免暂停时页面被拉回。
 *
 * 所有 mutator 都同步返回新的 [FollowState]，同时通过 [state] 暴露 `StateFlow`，
 * 便于 ViewModel 直接转发给 Compose。
 *
 * @param playableIndexes 初始有音频段 index；会被规范化（过滤负数、去重、升序）
 * @param continuousPlayback 初始连续播放开关，默认开
 * @param onChapterBoundary 最后一段播完且连续播放开启时回调**一次**；用于请求下一章
 */
class PlaybackFollowController(
    playableIndexes: List<Int> = emptyList(),
    continuousPlayback: Boolean = true,
    private val onChapterBoundary: (() -> Unit)? = null,
) {

    private var playable: List<Int> = normalize(playableIndexes)
    private var continuous: Boolean = continuousPlayback

    /** 上一次 [onPlaybackState] 是否在播，用于识别“播到结尾”的暂停。 */
    private var wasPlaying: Boolean = false

    /**
     * 最近一次“本章播完”的那个 index。
     *
     * ExoPlayer 在播完后仍会把 `currentMediaItem` 报成最后一段；没有这个记录时，
     * 下一次暂停态上报会被当成“外部切段”，把高亮又拉回最后一段并撤销跨章请求。
     */
    private var finishedIndex: Int? = null

    private val _state = MutableStateFlow(FollowState.Idle)
    val state: StateFlow<FollowState> = _state.asStateFlow()

    /** 当前可播放段数量。 */
    val playableCount: Int
        get() = playable.size

    /** 当前连续播放开关。 */
    val isContinuous: Boolean
        get() = continuous

    /** 该整章 index 是否有音频。 */
    fun isPlayable(index: Int): Boolean = index in playable

    /**
     * 替换“有音频段”列表（例如数据库 Flow 重新发射）。
     *
     * - 当前段仍有音频：保留高亮与连续开关；
     * - 当前段失去音频/被删：回到 [FollowState.Idle]，不残留错误高亮；
     * - 空列表：幂等回到 Idle。
     */
    fun setPlayableIndexes(indexes: List<Int>): FollowState {
        playable = normalize(indexes)
        if (playable.isEmpty()) {
            wasPlaying = false
            finishedIndex = null
            return publish(FollowState.Idle)
        }
        val current = _state.value
        if (current.currentIndex != null && current.currentIndex !in playable) {
            wasPlaying = false
            finishedIndex = null
            return publish(FollowState.Idle)
        }
        return current
    }

    /** 从整章 [ScriptSegmentUi] 列表提取有音频段并替换；筛选后的列表同样保留整章 index。 */
    fun setSegments(segments: List<ScriptSegmentUi>): FollowState =
        setPlayableIndexes(playableIndexesOf(segments))

    /**
     * 从整章 [index] 开始播放：
     * - index 本身有音频 → 从它开始；
     * - index 无音频 → 跳到后面第一个有音频段，并置 [FollowState.skippedNoAudio]；
     * - 后面没有可播放段 → 不打断当前播放，只给跳过提示；
     * - 负数 index 视为非法，保持现状。
     */
    fun playFrom(index: Int): FollowState {
        if (index < 0 || playable.isEmpty()) {
            return publish(_state.value.copy(shouldScroll = false, skippedNoAudio = false))
        }
        val target = if (index in playable) {
            index
        } else {
            playable.firstOrNull { it > index }
        }
        if (target == null) {
            return publish(_state.value.copy(shouldScroll = false, skippedNoAudio = true))
        }
        wasPlaying = false
        finishedIndex = null
        return publish(
            FollowState(
                currentIndex = target,
                shouldScroll = true,
                skippedNoAudio = target != index,
            ),
        )
    }

    /** 下一有音频段；已在最后一段时等价于“本章播完”。 */
    fun playNext(): FollowState {
        if (playable.isEmpty()) return publish(FollowState.Idle)
        val current = _state.value.currentIndex
        val target = if (current == null) {
            playable.first()
        } else {
            playable.firstOrNull { it > current }
        }
        if (target == null) return finishChapter(current)
        wasPlaying = false
        finishedIndex = null
        return publish(FollowState(currentIndex = target, shouldScroll = true))
    }

    /** 上一有音频段；已在第一段时从第一段重新开始。 */
    fun playPrevious(): FollowState {
        if (playable.isEmpty()) return publish(FollowState.Idle)
        val current = _state.value.currentIndex
        val target = if (current == null) {
            playable.first()
        } else {
            playable.lastOrNull { it < current } ?: playable.first()
        }
        wasPlaying = false
        finishedIndex = null
        return publish(FollowState(currentIndex = target, shouldScroll = true))
    }

    /**
     * 切换连续播放。
     *
     * 已在最后一段之后关闭开关时，会同步撤销尚未消费的
     * [FollowState.nextChapterRequested]，避免页面已经离开本章又被拉去下一章。
     */
    fun setContinuous(enabled: Boolean): FollowState {
        if (continuous == enabled) return _state.value
        continuous = enabled
        val current = _state.value
        val next = if (!enabled && current.nextChapterRequested) {
            current.copy(nextChapterRequested = false)
        } else {
            current
        }
        return publish(next)
    }

    /**
     * 同步播放器状态。
     *
     * - 播放器上报的 index 有音频且与当前不同 → 采用它并置 shouldScroll（例如
     *   MediaSession/锁屏切段）；
     * - index 无音频/越界 → 忽略，不把高亮带到不可播段；
     * - 暂停（`isPlaying=false`）保留当前段，恢复时无需页面重新滚动；
     * - 从“在播”变为“暂停/停止”且位置到达时长末尾 → 视作该段完成。
     *
     * @param playingIndex 播放器当前段的整章 index；`null` 表示没有当前项
     * @param positionMs 当前播放位置（毫秒，负数按 0）
     * @param durationMs 当前段时长（毫秒；`<= 0` 表示未知，不判完成）
     * @param isPlaying 播放器是否正在播放
     */
    fun onPlaybackState(
        playingIndex: Int?,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
    ): FollowState {
        if (playable.isEmpty()) {
            wasPlaying = isPlaying
            return publish(FollowState.Idle)
        }

        if (isPlaying) finishedIndex = null
        var next = _state.value
        val validIndex = playingIndex?.takeIf { it in playable }
        val repeatAfterFinish = !isPlaying && validIndex != null && validIndex == finishedIndex
        if (repeatAfterFinish) {
            // 播完后播放器仍会上报最后一段；保持“已播完”，不要重新高亮。
            wasPlaying = isPlaying
            return publish(next)
        }
        if (validIndex != null && validIndex != next.currentIndex) {
            next = FollowState(currentIndex = validIndex, shouldScroll = true)
        } else if (validIndex != null) {
            // 同一段：只清掉上一轮的章节完成/跳过提示，保留 shouldScroll 语义。
            next = next.copy(
                chapterFinished = false,
                nextChapterRequested = false,
                skippedNoAudio = false,
            )
        }

        val reachedEnd = wasPlaying &&
            !isPlaying &&
            validIndex != null &&
            durationMs > 0L &&
            positionMs.coerceAtLeast(0L) >= durationMs - END_TOLERANCE_MS
        wasPlaying = isPlaying
        if (reachedEnd) {
            // 直接按播放器上报的段前进：此时 next 可能还没发布，不能再读 _state。
            return advanceFrom(validIndex!!)
        }
        return publish(next)
    }

    /**
     * 播放器报告某段播完。
     *
     * 只有 [index] 等于当前段才生效（过期/越界事件忽略）：
     * - 后面还有有音频段 → 自动前进，`shouldScroll=true`；
     * - 已是最后一段 → 见 [finishChapter]：连续播放开时请求下一章，关时停止。
     */
    fun onSegmentFinished(index: Int): FollowState {
        if (playable.isEmpty()) return publish(FollowState.Idle)
        val current = _state.value.currentIndex
        if (current == null || index != current) return _state.value
        wasPlaying = false
        return advanceFrom(index)
    }

    /** 从 [index] 前进到下一个有音频段；没有则本章结束。 */
    private fun advanceFrom(index: Int): FollowState {
        val next = playable.firstOrNull { it > index }
        return if (next == null) {
            finishChapter(index)
        } else {
            finishedIndex = null
            publish(FollowState(currentIndex = next, shouldScroll = true))
        }
    }

    private fun finishChapter(lastIndex: Int?): FollowState {
        wasPlaying = false
        finishedIndex = lastIndex
        val requestNext = continuous && playable.isNotEmpty()
        val finished = FollowState(
            currentIndex = null,
            shouldScroll = false,
            chapterFinished = true,
            nextChapterRequested = requestNext,
        )
        publish(finished)
        if (requestNext) onChapterBoundary?.invoke()
        return finished
    }

    private fun publish(next: FollowState): FollowState {
        _state.value = next
        return next
    }

    private fun normalize(indexes: List<Int>): List<Int> =
        indexes.asSequence()
            .filter { it >= 0 }
            .distinct()
            .sorted()
            .toList()

    private companion object {
        /** 播放器位置与时长允许的误差；只用于识别“播到结尾”。 */
        const val END_TOLERANCE_MS = 200L
    }
}

/**
 * 从 UI 片段列表提取“有音频段”的整章 index。
 *
 * 传入筛选后的 [ScriptUiState.visibleSegments] 也安全：每项的 [ScriptSegmentUi.index]
 * 本来就是整章位置，因此结果仍与整章坐标系对齐，不会变成筛选后的行号。
 */
fun playableIndexesOf(segments: List<ScriptSegmentUi>): List<Int> =
    segments.asSequence()
        .filter { it.hasAudio }
        .map { it.index }
        .toList()
