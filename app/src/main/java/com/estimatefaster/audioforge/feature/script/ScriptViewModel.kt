package com.estimatefaster.audioforge.feature.script

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.data.db.SegmentEntity
import com.estimatefaster.audioforge.data.repository.CharacterRepository
import com.estimatefaster.audioforge.data.repository.SegmentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 剧本页数据源：只订阅本章片段 + 本书角色，便于纯 JVM 测试注入 fake。
 *
 * 生产环境通过次级构造器适配 [SegmentRepository]/[CharacterRepository]；
 * 页面不需要知道 Room 或仓库实现。
 */
interface ScriptDataSource {
    fun observeSegments(chapterId: Long): Flow<List<SegmentEntity>>

    fun observeCharacters(novelId: Long): Flow<List<CharacterEntity>>
}

/**
 * 剧本页 ViewModel（P5-01）。
 *
 * 数据流：`SegmentRepository.observeByChapter(chapterId)` 与
 * `CharacterRepository.observeByNovel(novelId)` 两路 `combine`，每次发射都重建
 * 整章投影与角色 chips；筛选/搜索只在内存里过滤，不重新订阅数据库。
 *
 * 播放高亮用整章 0 基位置 [ScriptUiState.playingSegmentIndex]，与筛选解耦：
 * 当前段被筛掉时 `scrollTargetFor` 返回 null，页面不强制滚动。
 *
 * 播放跟随/连播（P5-03）交给纯逻辑 [PlaybackFollowController]：
 * - [playFrom]/[playNext]/[playPrevious] 只更新跟随状态并返回应播放的整章 index，
 *   真正的音频起播由宿主接线到 `PlayerController`（本类不依赖 playback/**）；
 * - [onPlaybackState] 接收播放器回报的位置/时长/播放态，驱动高亮、暂停恢复与
 *   “播到结尾自动前进”；
 * - 自动前进或跨章请求通过 [onPlaybackAdvanced]/[onNextChapter] 回调通知宿主，
 *   没有回调时宿主也可以直接 collect [followState]。
 *
 * 所有状态更新都在主线程；`retry()` 重新收集两路 Flow，用于首屏失败恢复。
 *
 * @param chapterId 当前章节 id
 * @param novelId 当前项目 id（角色按书查询）
 * @param source 片段/角色数据源
 * @param chapterTitle 导航传入的章节标题；为空时页面显示兜底标题
 * @param onPlaybackAdvanced 自动前进到下一段时回调（参数是整章 index）；宿主据此起播
 * @param onNextChapter 最后一段播完且连续播放开启时回调；宿主据此加载下一章
 */
class ScriptViewModel(
    private val chapterId: Long,
    private val novelId: Long,
    private val source: ScriptDataSource,
    chapterTitle: String = "",
    private val onPlaybackAdvanced: ((Int) -> Unit)? = null,
    private val onNextChapter: (() -> Unit)? = null,
) : ViewModel() {

    /** 生产环境入口：直接注入 `AppContainer` 里的仓库。 */
    constructor(
        chapterId: Long,
        novelId: Long,
        chapterTitle: String,
        segmentRepository: SegmentRepository,
        characterRepository: CharacterRepository,
    ) : this(
        chapterId = chapterId,
        novelId = novelId,
        source = object : ScriptDataSource {
            override fun observeSegments(chapterId: Long): Flow<List<SegmentEntity>> =
                segmentRepository.observeByChapter(chapterId)

            override fun observeCharacters(novelId: Long): Flow<List<CharacterEntity>> =
                characterRepository.observeByNovel(novelId)
        },
        chapterTitle = chapterTitle,
    )

    private val _state = MutableStateFlow(ScriptUiState(chapterTitle = chapterTitle))
    val state: StateFlow<ScriptUiState> = _state.asStateFlow()

    /** 播放跟随/连播状态机；页面可只 collect 它做自动滚动与章节边界处理。 */
    private val follow = PlaybackFollowController(
        onChapterBoundary = { onNextChapter?.invoke() },
    )
    val followState: StateFlow<FollowState> = follow.state

    private var observeJob: Job? = null

    /** [setPlayingSegmentIndex] 写入的“纯高亮”是否正在生效（旧接线兼容用）。 */
    private var legacyHighlight: Boolean = false

    init {
        require(chapterId > 0) { "chapterId 必须为正数：$chapterId" }
        require(novelId > 0) { "novelId 必须为正数：$novelId" }
        observe()
    }

    /** 台词关键词变化；纯内存过滤，不重新订阅数据库。 */
    fun setQuery(query: String) {
        if (query == _state.value.query) return
        _state.update { it.copy(query = query) }
    }

    /** 角色筛选；`null` 表示“全部”。 */
    fun setRoleFilter(roleId: String?) {
        val normalized = roleId?.trim()?.takeIf { it.isNotEmpty() }
        if (normalized == _state.value.selectedRoleId) return
        _state.update { it.copy(selectedRoleId = normalized) }
    }

    /** 清除角色筛选与搜索；没有生效条件时不做无谓的状态更新。 */
    fun clearFilters() {
        if (!_state.value.hasActiveFilters) return
        _state.update { it.copy(selectedRoleId = null, query = "") }
    }

    /**
     * 更新播放高亮段（整章 0 基位置）。
     * 越界/为负的索引会被收敛为 null，避免 UI 残留错误高亮。
     *
     * 这是给“外部播放器已经报告了当前段”的轻量入口；正常播放请走 [onPlaybackState]，
     * 这样跟随状态机才能处理跳过/自动前进。
     */
    fun setPlayingSegmentIndex(index: Int?) {
        val valid = index != null && _state.value.segments.any { it.index == index }
        legacyHighlight = valid
        _state.update { it.copy(playingSegmentIndex = if (valid) index else null) }
    }

    /**
     * 从整章 [index] 开始播放/连播，返回实际应播放的 index（跳过无音频段后的结果）。
     *
     * 返回 `null` 表示没有可播放段：页面只显示 [FollowState.skippedNoAudio] 提示，
     * 不会中断已经在播的音频。起播由宿主用返回值接 `PlayerController`。
     */
    fun playFrom(index: Int): Int? {
        val next = follow.playFrom(index)
        publishFollow(next)
        return next.currentIndex
    }

    /** 下一有音频段；返回应播放的 index，本章已播完时返回 `null`。 */
    fun playNext(): Int? {
        val next = follow.playNext()
        publishFollow(next)
        return next.currentIndex
    }

    /** 上一有音频段；返回应播放的 index，无可播放段时返回 `null`。 */
    fun playPrevious(): Int? {
        val next = follow.playPrevious()
        publishFollow(next)
        return next.currentIndex
    }

    /** 连续播放开关；关闭时同步撤销尚未消费的下一章请求。 */
    fun setContinuous(enabled: Boolean) {
        follow.setContinuous(enabled)
        _state.update { it.copy(continuousPlayback = enabled) }
    }

    /**
     * 播放器状态回报（建议 500ms 一次 + 播放态变化时立即调用）。
     *
     * 除了同步高亮，还承担两件事：
     * - 暂停/恢复不改变当前段，页面不会因为暂停被拉回；
     * - 播放器从“在播”变为“停止”且位置到达时长末尾 → 自动前进/请求下一章，
     *   自动前进时通过 [onPlaybackAdvanced] 通知宿主起播下一段。
     */
    fun onPlaybackState(
        playingIndex: Int?,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
    ) {
        val previous = followState.value.currentIndex
        val next = follow.onPlaybackState(playingIndex, positionMs, durationMs, isPlaying)
        val nextIndex = next.currentIndex
        // 只有“播放器停在上一段、跟随自动前进到下一段”才需要宿主起播；
        // 外部已经切到新段（playingIndex == nextIndex）时重复起播会从头播放。
        val autoAdvanced = nextIndex != null &&
            nextIndex != previous &&
            playingIndex == previous &&
            !isPlaying
        publishFollow(next, activelyPlaying = isPlaying || autoAdvanced)
        _state.update { it.copy(continuousPlayback = follow.isContinuous) }
        if (autoAdvanced) {
            onPlaybackAdvanced?.invoke(nextIndex!!)
        }
    }

    /**
     * 播放器报告某段播完。
     *
     * 过期/越界 index 会被忽略；自动前进时通过 [onPlaybackAdvanced] 通知宿主，
     * 最后一段完成时由 [PlaybackFollowController] 触发 [onNextChapter]。
     */
    fun onSegmentFinished(index: Int) {
        val previous = followState.value
        val next = follow.onSegmentFinished(index)
        if (next == previous) return
        publishFollow(next)
        val nextIndex = next.currentIndex
        if (nextIndex != null && nextIndex != previous.currentIndex) {
            onPlaybackAdvanced?.invoke(nextIndex)
        }
    }

    private fun publishFollow(
        next: FollowState,
        activelyPlaying: Boolean = next.currentIndex != null,
    ) {
        legacyHighlight = false
        _state.update {
            it.copy(
                playingSegmentIndex = next.currentIndex,
                playing = activelyPlaying && next.currentIndex != null,
            )
        }
    }

    /** 首屏失败重试：重新收集两路 Flow，清空旧错误。 */
    fun retry() {
        if (_state.value.loadError == null) return
        observe()
    }

    private fun observe() {
        observeJob?.cancel()
        _state.update { it.copy(loading = true, loadError = null) }
        observeJob = viewModelScope.launch {
            try {
                combine(
                    source.observeSegments(chapterId),
                    source.observeCharacters(novelId),
                ) { segments, characters -> segments to characters }
                    .collect { (segments, characters) ->
                        publish(segments, characters)
                    }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(
                        loading = false,
                        loadError = throwable.toLoadErrorMessage(),
                    )
                }
            }
        }
    }

    private fun publish(
        segmentEntities: List<SegmentEntity>,
        characterEntities: List<CharacterEntity>,
    ) {
        val segments = mapScriptSegments(segmentEntities, characterEntities)
        val characters = buildScriptCharacters(characterEntities, segments)
        val current = _state.value
        // 角色被删/旁白消失后，不要留下选不中的 chip。
        val selectedRoleId = current.selectedRoleId?.takeIf { roleId ->
            characters.any { it.roleId == roleId }
        }
        // 有音频段列表变化：当前段失去音频/被删时跟随状态回到 Idle，其余情况保持。
        val followSnapshot = follow.setPlayableIndexes(playableIndexesOf(segments))
        val legacyPlaying = if (legacyHighlight) {
            current.playingSegmentIndex?.takeIf { index -> segments.any { it.index == index } }
        } else {
            null
        }
        val playingSegmentIndex = followSnapshot.currentIndex ?: legacyPlaying
        _state.value = current.copy(
            loading = false,
            loadError = null,
            segments = segments,
            characters = characters,
            selectedRoleId = selectedRoleId,
            playingSegmentIndex = playingSegmentIndex,
            continuousPlayback = follow.isContinuous,
            playing = followSnapshot.currentIndex != null && current.playing,
        )
    }

    private fun Throwable.toLoadErrorMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}
