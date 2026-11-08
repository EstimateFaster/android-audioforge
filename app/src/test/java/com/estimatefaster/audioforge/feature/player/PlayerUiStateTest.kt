package com.estimatefaster.audioforge.feature.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P5-07：播放器页 UI 状态、纯函数与 ViewModel 的纯 JVM 测试。
 *
 * 不依赖 Android/Robolectric：时间格式、进度映射、章节/片段导航都是纯函数；
 * ViewModel 用 [StandardTestDispatcher] 替换 Main，注入 fake 内容源与 fake [PlayerGateway]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerUiStateTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------
    // 时间格式
    // ------------------------------------------------------------------

    @Test
    fun format_time_zero_seconds_and_under_one_minute() {
        assertEquals("0:00", formatPlaybackTime(0L))
        assertEquals("0:01", formatPlaybackTime(1_000L))
        assertEquals("0:59", formatPlaybackTime(59_000L))
    }

    @Test
    fun format_time_rolls_over_to_hours() {
        assertEquals("1:00:00", formatPlaybackTime(3_600_000L))
        assertEquals("1:01:01", formatPlaybackTime(3_661_000L))
        assertEquals("10:00:00", formatPlaybackTime(36_000_000L))
    }

    @Test
    fun format_time_floors_partial_seconds_and_clamps_negative() {
        assertEquals("0:59", formatPlaybackTime(59_999L))
        assertEquals("0:00", formatPlaybackTime(-5_000L))
    }

    // ------------------------------------------------------------------
    // 进度 / seek 映射
    // ------------------------------------------------------------------

    @Test
    fun progress_fraction_clamps_between_zero_and_one() {
        assertEquals(0f, progressFraction(0L, 100_000L), 0.0001f)
        assertEquals(0.5f, progressFraction(50_000L, 100_000L), 0.0001f)
        assertEquals(1f, progressFraction(120_000L, 100_000L), 0.0001f)
        assertEquals(0f, progressFraction(-1_000L, 100_000L), 0.0001f)
    }

    @Test
    fun progress_fraction_is_zero_when_duration_is_unknown() {
        assertEquals(0f, progressFraction(10_000L, 0L), 0.0001f)
        assertEquals(0f, progressFraction(10_000L, -1L), 0.0001f)
    }

    @Test
    fun seek_target_maps_fraction_to_clamped_millis() {
        assertEquals(0L, seekTargetMs(0f, 100_000L))
        assertEquals(50_000L, seekTargetMs(0.5f, 100_000L))
        assertEquals(100_000L, seekTargetMs(1f, 100_000L))
        assertEquals(0L, seekTargetMs(-0.2f, 100_000L))
        assertEquals(100_000L, seekTargetMs(1.5f, 100_000L))
    }

    @Test
    fun seek_target_handles_nan_and_unknown_duration() {
        assertEquals(0L, seekTargetMs(Float.NaN, 100_000L))
        assertEquals(0L, seekTargetMs(0.5f, 0L))
        assertEquals(0L, seekTargetMs(0.5f, -1L))
    }

    @Test
    fun state_seek_target_uses_current_duration() {
        val state = sampleState().copy(positionMs = 10_000L, durationMs = 80_000L)

        assertEquals(40_000L, state.seekTargetFor(0.5f))
    }

    // ------------------------------------------------------------------
    // 倍速 / 睡眠定时选项
    // ------------------------------------------------------------------

    @Test
    fun speed_options_match_execution_plan() {
        assertEquals(
            listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f),
            PlayerSpeed.options.map { it.multiplier },
        )
        assertEquals(PlayerSpeed.X100, PlayerSpeed.options[1])
    }

    @Test
    fun speed_closest_to_snaps_to_nearest_option() {
        assertEquals(PlayerSpeed.X075, PlayerSpeed.closestTo(0.8f))
        assertEquals(PlayerSpeed.X125, PlayerSpeed.closestTo(1.2f))
        assertEquals(PlayerSpeed.X200, PlayerSpeed.closestTo(1.9f))
    }

    @Test
    fun speed_label_drops_trailing_zeros() {
        assertEquals("0.75", formatSpeedMultiplier(0.75f))
        assertEquals("1.0", formatSpeedMultiplier(1.0f))
        assertEquals("1.25", formatSpeedMultiplier(1.25f))
        assertEquals("1.5", formatSpeedMultiplier(1.5f))
        assertEquals("2.0", formatSpeedMultiplier(2.0f))
    }

    @Test
    fun sleep_options_match_execution_plan() {
        assertEquals(listOf<Int?>(null, 15, 30, 60), SleepTimerOption.options.map { it.minutes })
        assertFalse(SleepTimerOption.Off.isEnabled)
        assertTrue(SleepTimerOption.Minutes15.isEnabled)
        assertEquals(900_000L, sleepTimerDurationMs(SleepTimerOption.Minutes15) ?: -1L)
        assertEquals(1_800_000L, sleepTimerDurationMs(SleepTimerOption.Minutes30) ?: -1L)
        assertEquals(3_600_000L, sleepTimerDurationMs(SleepTimerOption.Minutes60) ?: -1L)
        assertNull(sleepTimerDurationMs(SleepTimerOption.Off))
    }

    @Test
    fun sleep_remaining_counts_down_and_never_negative() {
        assertEquals(900_000L, sleepTimerRemainingMs(900_000L, 0L))
        assertEquals(600_000L, sleepTimerRemainingMs(900_000L, 300_000L))
        assertEquals(0L, sleepTimerRemainingMs(900_000L, 1_800_000L))
    }

    // ------------------------------------------------------------------
    // 当前章 / 当前句 / 章节高亮
    // ------------------------------------------------------------------

    @Test
    fun state_resolves_current_chapter_and_segment() {
        val state = sampleState()

        assertEquals("第 1 章", state.currentChapter?.title)
        assertEquals(11L, state.currentChapter?.chapterId ?: -1L)
        assertEquals(0, state.currentSegment?.index ?: -1)
        assertEquals("第 1 句", state.currentSegment?.text)
        assertTrue(state.hasPlayableAudio)
        assertFalse(state.isEmpty)
        assertFalse(state.isInitialLoading)
    }

    @Test
    fun chapter_highlight_compares_chapter_id() {
        val state = sampleState(currentChapterIndex = 2, currentSegmentIndex = 0)

        assertFalse(state.isCurrentChapter(state.chapters[0]))
        assertTrue(state.isCurrentChapter(state.chapters[2]))
    }

    @Test
    fun chapter_duration_only_counts_segments_with_audio() {
        val first = sampleContent().chapters[0]

        assertEquals(listOf(0, 2), first.playableSegments.map { it.index })
        assertEquals(120_000L, first.durationMs)
        assertEquals(0, first.firstPlayableIndex ?: -1)
        assertTrue(first.hasAudio)
    }

    // ------------------------------------------------------------------
    // 上一章 / 下一章 / 上一句 / 下一句边界
    // ------------------------------------------------------------------

    @Test
    fun next_chapter_skips_chapters_without_audio() {
        val state = sampleState()

        assertEquals(2, state.nextChapterIndex() ?: -1)
    }

    @Test
    fun previous_chapter_skips_chapters_without_audio() {
        val state = sampleState(currentChapterIndex = 2, currentSegmentIndex = 0)

        assertEquals(0, state.previousChapterIndex() ?: -1)
    }

    @Test
    fun chapter_navigation_stops_at_book_edges() {
        val first = sampleState()
        assertNull(first.previousChapterIndex())

        val last = sampleState(currentChapterIndex = 2, currentSegmentIndex = 1)
        assertNull(last.nextChapterIndex())
        assertEquals(0, last.previousChapterIndex() ?: -1)
    }

    @Test
    fun next_segment_skips_segments_without_audio() {
        val state = sampleState(currentSegmentIndex = 0)

        assertEquals(2, state.nextSegmentIndex())
    }

    @Test
    fun previous_segment_skips_segments_without_audio() {
        val state = sampleState(currentSegmentIndex = 2)

        assertEquals(0, state.previousSegmentIndex())
    }

    @Test
    fun segment_navigation_stops_at_chapter_edges() {
        val first = sampleState(currentSegmentIndex = 0)
        assertNull(first.previousSegmentIndex())

        val last = sampleState(currentSegmentIndex = 2)
        assertNull(last.nextSegmentIndex())
    }

    // ------------------------------------------------------------------
    // 空态 / 错误态
    // ------------------------------------------------------------------

    @Test
    fun loading_empty_and_error_states_are_distinct() {
        assertTrue(PlayerUiState().isInitialLoading)
        assertFalse(PlayerUiState().isEmpty)

        val empty = PlayerUiState(
            loading = false,
            chapters = listOf(chapter(1L, 0, segment(0, hasAudio = false))),
        )
        assertTrue(empty.isEmpty)
        assertFalse(empty.isInitialLoading)
        assertNull(empty.currentChapter)
        assertFalse(empty.hasPlayableAudio)

        val error = PlayerUiState(loading = false, loadError = "数据库被锁定")
        assertFalse(error.isEmpty)
        assertFalse(error.isInitialLoading)
        assertTrue(error.chapters.isEmpty())
    }

    // ------------------------------------------------------------------
    // ViewModel：加载 / 播放 / 暂停 / seek
    // ------------------------------------------------------------------

    @Test
    fun view_model_starts_in_loading_then_publishes_content() = runTest(dispatcher) {
        val source = FakeContentSource(sampleContent())
        val gateway = FakeGateway()
        val viewModel = PlayerViewModel(source, gateway)

        assertTrue(viewModel.state.value.isInitialLoading)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.loading)
        assertNull(state.loadError)
        assertEquals("雾城旧事", state.bookTitle)
        assertEquals(3, state.chapters.size)
        assertEquals(0, state.currentChapterIndex)
        assertEquals(0, state.currentSegmentIndex)
        assertEquals(60_000L, state.durationMs)
        assertFalse(state.isPlaying)
        assertEquals(1, source.calls)
        assertEquals("内容加载不应自动起播", 0, gateway.openCalls)
    }

    @Test
    fun view_model_play_opens_queue_at_first_playable_track() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.play()
        advanceUntilIdle()

        assertEquals(1, gateway.openCalls)
        assertEquals(0, gateway.lastStartIndex)
        assertEquals(4, gateway.lastTracks.size)
        assertEquals(11L, gateway.lastTracks.first().chapterId)
        assertEquals(0, gateway.lastTracks.first().segmentIndex)
        assertEquals(2, gateway.lastTracks[1].segmentIndex)
        assertTrue(viewModel.state.value.isPlaying)
    }

    @Test
    fun view_model_pauses_after_play() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.play()
        advanceUntilIdle()
        viewModel.pause()
        advanceUntilIdle()

        assertEquals(1, gateway.pauseCalls)
        assertFalse(viewModel.state.value.isPlaying)
    }

    @Test
    fun view_model_seek_forwards_clamped_target() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.play()
        advanceUntilIdle()
        viewModel.seekTo(0.5f)
        advanceUntilIdle()

        assertEquals(30_000L, gateway.lastSeekMs ?: -1L)
        assertEquals(30_000L, viewModel.state.value.positionMs)
        assertEquals(0.5f, viewModel.state.value.progress, 0.0001f)
    }

    @Test
    fun view_model_ignores_seek_before_playback() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.seekTo(0.5f)
        advanceUntilIdle()

        assertNull(gateway.lastSeekMs)
    }

    // ------------------------------------------------------------------
    // ViewModel：倍速 / 睡眠定时
    // ------------------------------------------------------------------

    @Test
    fun view_model_changes_speed_and_passes_multiplier() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.setSpeed(PlayerSpeed.X150)
        advanceUntilIdle()

        assertEquals(PlayerSpeed.X150, viewModel.state.value.speed)
        assertEquals(1.5f, gateway.lastSpeed ?: 0f, 0.0001f)
    }

    @Test
    fun view_model_sets_sleep_timer_and_counts_down() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.setSleepTimer(SleepTimerOption.Minutes15)

        assertEquals(15, gateway.lastSleepMinutes ?: -1)
        assertEquals(SleepTimerOption.Minutes15, viewModel.state.value.sleepTimer)
        assertEquals(900_000L, viewModel.state.value.sleepRemainingMs ?: -1L)

        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(840_000L, viewModel.state.value.sleepRemainingMs ?: -1L)

        viewModel.setSleepTimer(SleepTimerOption.Off)
        advanceUntilIdle()
        assertNull(viewModel.state.value.sleepRemainingMs)
    }

    @Test
    fun view_model_cancels_sleep_timer_with_zero_minutes() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.setSleepTimer(SleepTimerOption.Minutes30)
        assertEquals(30, gateway.lastSleepMinutes ?: -1)

        viewModel.setSleepTimer(SleepTimerOption.Off)
        advanceUntilIdle()

        assertEquals(0, gateway.lastSleepMinutes ?: -1)
        assertEquals(SleepTimerOption.Off, viewModel.state.value.sleepTimer)
        assertNull(viewModel.state.value.sleepRemainingMs)
    }

    // ------------------------------------------------------------------
    // ViewModel：切章 / 切句 / 章节列表
    // ------------------------------------------------------------------

    @Test
    fun view_model_next_chapter_opens_first_track_of_target_chapter() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.play()
        advanceUntilIdle()
        viewModel.nextChapter()
        advanceUntilIdle()

        assertEquals(2, gateway.openCalls)
        assertEquals(2, gateway.lastStartIndex)
        assertEquals(2, viewModel.state.value.currentChapterIndex)
        assertEquals(13L, viewModel.state.value.currentChapter?.chapterId ?: -1L)
        assertEquals(0, viewModel.state.value.currentSegmentIndex)
        assertTrue(viewModel.state.value.isPlaying)
    }

    @Test
    fun view_model_chapter_navigation_stops_at_book_edges() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.play()
        advanceUntilIdle()
        viewModel.previousChapter()
        advanceUntilIdle()
        assertEquals(1, gateway.openCalls)
        assertEquals(0, viewModel.state.value.currentChapterIndex)

        viewModel.selectChapter(13L)
        advanceUntilIdle()
        viewModel.nextChapter()
        advanceUntilIdle()
        assertEquals(2, gateway.openCalls)
        assertEquals(2, viewModel.state.value.currentChapterIndex)
    }

    @Test
    fun view_model_select_chapter_updates_highlight_and_queue_position() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.selectChapter(13L)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(2, state.currentChapterIndex)
        assertEquals(13L, state.currentChapter?.chapterId ?: -1L)
        assertTrue(state.isCurrentChapter(state.chapters[2]))
        assertEquals(1, gateway.openCalls)
        assertEquals(2, gateway.lastStartIndex)
    }

    @Test
    fun view_model_next_segment_skips_no_audio_and_updates_highlight() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.play()
        advanceUntilIdle()
        viewModel.nextSegment()
        advanceUntilIdle()

        assertEquals(1, gateway.nextCalls)
        assertEquals(2, viewModel.state.value.currentSegmentIndex)
        assertEquals(11L, viewModel.state.value.currentChapter?.chapterId ?: -1L)
    }

    @Test
    fun view_model_previous_segment_stops_at_first_segment() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.play()
        advanceUntilIdle()
        viewModel.previousSegment()
        advanceUntilIdle()

        assertEquals(0, gateway.previousCalls)
        assertEquals(0, viewModel.state.value.currentSegmentIndex)
    }

    @Test
    fun view_model_next_segment_stops_at_chapter_end() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.play()
        advanceUntilIdle()
        viewModel.nextSegment()
        advanceUntilIdle()
        assertEquals(1, gateway.nextCalls)
        assertEquals(2, viewModel.state.value.currentSegmentIndex)

        viewModel.nextSegment()
        advanceUntilIdle()
        assertEquals(1, gateway.nextCalls)
        assertEquals(2, viewModel.state.value.currentSegmentIndex)
    }

    @Test
    fun view_model_next_segment_before_play_opens_target_track() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.nextSegment()
        advanceUntilIdle()

        assertEquals(1, gateway.openCalls)
        assertEquals(1, gateway.lastStartIndex)
        assertEquals(2, viewModel.state.value.currentSegmentIndex)
        assertTrue(viewModel.state.value.isPlaying)
    }

    @Test
    fun view_model_toggles_chapter_list() = runTest(dispatcher) {
        val viewModel = viewModel(FakeGateway())
        advanceUntilIdle()

        assertFalse(viewModel.state.value.chaptersExpanded)
        viewModel.toggleChapterList()
        assertTrue(viewModel.state.value.chaptersExpanded)
        viewModel.toggleChapterList()
        assertFalse(viewModel.state.value.chaptersExpanded)
    }

    // ------------------------------------------------------------------
    // ViewModel：空态 / 错误重试 / 外部播放状态同步
    // ------------------------------------------------------------------

    @Test
    fun view_model_empty_audio_never_opens_queue() = runTest(dispatcher) {
        val source = FakeContentSource(
            PlayerContent(
                bookTitle = "空书",
                chapters = listOf(chapter(1L, 0, segment(0, hasAudio = false))),
            ),
        )
        val gateway = FakeGateway()
        val viewModel = PlayerViewModel(source, gateway)

        advanceUntilIdle()
        assertTrue(viewModel.state.value.isEmpty)
        assertNull(viewModel.state.value.currentChapterIndex)

        viewModel.play()
        advanceUntilIdle()
        assertEquals(0, gateway.openCalls)
    }

    @Test
    fun view_model_reports_load_error_and_recovers_on_retry() = runTest(dispatcher) {
        val source = FakeContentSource(sampleContent())
        source.failure = IllegalStateException("数据库被锁定")
        val gateway = FakeGateway()
        val viewModel = PlayerViewModel(source, gateway)

        advanceUntilIdle()

        assertEquals("数据库被锁定", viewModel.state.value.loadError)
        assertFalse(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.isEmpty)

        source.failure = null
        viewModel.retry()
        advanceUntilIdle()

        assertNull(viewModel.state.value.loadError)
        assertEquals(2, source.calls)
        assertEquals(3, viewModel.state.value.chapters.size)
        assertEquals(0, viewModel.state.value.currentChapterIndex)
    }

    @Test
    fun view_model_syncs_from_gateway_playback() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.play()
        advanceUntilIdle()

        gateway.emitPlayback(
            PlayerPlaybackState(
                chapterId = 13L,
                segmentIndex = 1,
                positionMs = 5_000L,
                durationMs = 60_000L,
                isPlaying = true,
            ),
        )
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(2, state.currentChapterIndex)
        assertEquals(1, state.currentSegmentIndex)
        assertEquals(5_000L, state.positionMs)
        assertEquals(60_000L, state.durationMs)
        assertTrue(state.isPlaying)
    }

    // ------------------------------------------------------------------
    // 测试数据 / fake
    // ------------------------------------------------------------------

    private fun viewModel(
        gateway: PlayerGateway,
        content: PlayerContent = sampleContent(),
    ): PlayerViewModel = PlayerViewModel(FakeContentSource(content), gateway)

    private fun segment(
        index: Int,
        hasAudio: Boolean = true,
        durationMs: Long = 60_000L,
        roleName: String = "沈砚",
    ): PlayerSegmentUi = PlayerSegmentUi(
        index = index,
        roleName = roleName,
        text = "第 ${index + 1} 句",
        audioPath = if (hasAudio) "audio/1/seg_${index + 1}.mp3" else null,
        durationMs = durationMs,
    )

    private fun chapter(
        chapterId: Long,
        index: Int,
        vararg segments: PlayerSegmentUi,
    ): PlayerChapterUi = PlayerChapterUi(
        chapterId = chapterId,
        index = index,
        title = "第 ${index + 1} 章",
        segments = segments.toList(),
    )

    /**
     * 三章：
     * - 第 1 章有 3 段，其中第 2 段没有音频；
     * - 第 2 章整章没有音频，切章时必须跳过；
     * - 第 3 章有 2 段可播放。
     */
    private fun sampleContent(): PlayerContent = PlayerContent(
        bookTitle = "雾城旧事",
        chapters = listOf(
            chapter(11L, 0, segment(0), segment(1, hasAudio = false), segment(2)),
            chapter(12L, 1),
            chapter(13L, 2, segment(0, durationMs = 90_000L), segment(1)),
        ),
    )

    private fun sampleState(
        currentChapterIndex: Int? = 0,
        currentSegmentIndex: Int? = 0,
    ): PlayerUiState = PlayerUiState(
        loading = false,
        bookTitle = "雾城旧事",
        chapters = sampleContent().chapters,
        currentChapterIndex = currentChapterIndex,
        currentSegmentIndex = currentSegmentIndex,
        durationMs = 60_000L,
    )

    private class FakeContentSource(
        var content: PlayerContent,
    ) : PlayerContentSource {
        var calls: Int = 0
        var failure: Throwable? = null

        override suspend fun load(): PlayerContent {
            calls++
            failure?.let { throw it }
            return content
        }
    }

    private class FakeGateway : PlayerGateway {
        private val _playback = MutableStateFlow(PlayerPlaybackState())
        override val playback: StateFlow<PlayerPlaybackState> = _playback.asStateFlow()

        var openCalls: Int = 0
        var lastTracks: List<PlayerTrack> = emptyList()
        var lastStartIndex: Int = -1
        var playCalls: Int = 0
        var pauseCalls: Int = 0
        var nextCalls: Int = 0
        var previousCalls: Int = 0
        var lastSeekMs: Long? = null
        var lastSpeed: Float? = null
        var lastSleepMinutes: Int? = null

        private var queue: List<PlayerTrack> = emptyList()
        private var index: Int = -1

        override fun open(tracks: List<PlayerTrack>, startIndex: Int) {
            openCalls++
            lastTracks = tracks
            lastStartIndex = startIndex
            queue = tracks
            index = startIndex
            publishCurrent(isPlaying = true, positionMs = 0L)
        }

        override fun play() {
            playCalls++
            _playback.update { it.copy(isPlaying = true) }
        }

        override fun pause() {
            pauseCalls++
            _playback.update { it.copy(isPlaying = false) }
        }

        override fun seekTo(positionMs: Long) {
            lastSeekMs = positionMs
            _playback.update { it.copy(positionMs = positionMs) }
        }

        override fun next() {
            nextCalls++
            if (index >= 0 && index < queue.lastIndex) {
                index++
                publishCurrent(isPlaying = true, positionMs = 0L)
            }
        }

        override fun previous() {
            previousCalls++
            if (index > 0) {
                index--
                publishCurrent(isPlaying = true, positionMs = 0L)
            }
        }

        override fun setSpeed(speed: Float) {
            lastSpeed = speed
        }

        override fun sleepTimer(minutes: Int) {
            lastSleepMinutes = minutes
        }

        override fun release() {
        }

        fun emitPlayback(state: PlayerPlaybackState) {
            _playback.value = state
        }

        private fun publishCurrent(isPlaying: Boolean, positionMs: Long) {
            val track = queue.getOrNull(index)
            _playback.value = PlayerPlaybackState(
                chapterId = track?.chapterId,
                segmentIndex = track?.segmentIndex,
                positionMs = positionMs,
                durationMs = track?.durationMs ?: 0L,
                isPlaying = isPlaying,
            )
        }
    }
}
