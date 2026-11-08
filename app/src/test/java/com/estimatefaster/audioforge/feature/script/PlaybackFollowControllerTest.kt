package com.estimatefaster.audioforge.feature.script

import com.estimatefaster.audioforge.core.model.Emotion
import com.estimatefaster.audioforge.core.model.SegmentType
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.data.db.SegmentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
 * P5-03：播放跟随/连播的纯 JVM 测试。
 *
 * 覆盖 [PlaybackFollowController] 的跳段、自动前进、暂停/恢复、最后一段与跨章
 * 语义，以及它和 [ScriptViewModel] / [ScriptUiState.visibleSegments] 的整章 index
 * 对齐。全程不依赖 Android、Media3 或 Robolectric。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackFollowControllerTest {

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
    // 起播：命中 / 跳过无音频 / 越界 / 空列表
    // ------------------------------------------------------------------

    @Test
    fun play_from_starts_at_the_requested_playable_segment() {
        val controller = controller(0, 1, 2)

        val state = controller.playFrom(1)

        assertEquals(1, state.currentIndex)
        assertTrue(state.shouldScroll)
        assertFalse(state.skippedNoAudio)
        assertFalse(state.chapterFinished)
        assertEquals(state, controller.state.value)
    }

    @Test
    fun play_from_skips_segments_without_audio() {
        val controller = controller(0, 3, 5)

        val state = controller.playFrom(1)

        assertEquals("1/2/4 没有音频，应落到 3", 3, state.currentIndex)
        assertTrue(state.shouldScroll)
        assertTrue(state.skippedNoAudio)
    }

    @Test
    fun play_from_last_playable_segment_keeps_it() {
        val controller = controller(0, 3, 5)

        val state = controller.playFrom(5)

        assertEquals(5, state.currentIndex)
        assertFalse(state.skippedNoAudio)
    }

    @Test
    fun play_from_after_last_keeps_current_playback_and_only_reports_skip() {
        val controller = controller(0, 2)
        controller.playFrom(0)

        val state = controller.playFrom(9)

        assertEquals("没有后续可播段时不应中断当前播放", 0, state.currentIndex)
        assertFalse(state.shouldScroll)
        assertTrue(state.skippedNoAudio)
    }

    @Test
    fun play_from_negative_index_is_a_noop() {
        val controller = controller(0, 1)
        controller.playFrom(0)

        val state = controller.playFrom(-1)

        assertEquals(0, state.currentIndex)
        assertFalse(state.skippedNoAudio)
    }

    @Test
    fun play_from_empty_playlist_is_idle() {
        val controller = controller()

        val state = controller.playFrom(0)

        assertNull(state.currentIndex)
        assertFalse(state.shouldScroll)
        assertFalse(state.chapterFinished)
    }

    // ------------------------------------------------------------------
    // 自动前进 / 跳过空段 / 最后一段
    // ------------------------------------------------------------------

    @Test
    fun segment_finished_advances_to_next_playable() {
        val controller = controller(0, 1, 2)
        controller.playFrom(0)

        val state = controller.onSegmentFinished(0)

        assertEquals(1, state.currentIndex)
        assertTrue(state.shouldScroll)
        assertFalse(state.chapterFinished)
        assertFalse(state.nextChapterRequested)
    }

    @Test
    fun segment_finished_skips_gaps() {
        val controller = controller(0, 3, 7)
        controller.playFrom(0)

        val state = controller.onSegmentFinished(0)

        assertEquals("中间的 1/2 无音频，应直接跳到 3", 3, state.currentIndex)
    }

    @Test
    fun segment_finished_on_last_stops_when_continuous_is_off() {
        var boundaryCalls = 0
        val controller = controller(0, 1, continuous = false, onBoundary = { boundaryCalls++ })
        controller.playFrom(1)

        val state = controller.onSegmentFinished(1)

        assertNull(state.currentIndex)
        assertTrue(state.chapterFinished)
        assertFalse(state.nextChapterRequested)
        assertEquals("关闭连播不应请求下一章", 0, boundaryCalls)
    }

    @Test
    fun segment_finished_on_last_requests_next_chapter_when_continuous_is_on() {
        var boundaryCalls = 0
        val controller = controller(0, 1, continuous = true, onBoundary = { boundaryCalls++ })
        controller.playFrom(1)

        val state = controller.onSegmentFinished(1)

        assertNull(state.currentIndex)
        assertTrue(state.chapterFinished)
        assertTrue(state.nextChapterRequested)
        assertEquals(1, boundaryCalls)
    }

    @Test
    fun duplicate_finish_after_chapter_ends_fires_boundary_only_once() {
        var boundaryCalls = 0
        val controller = controller(0, 1, onBoundary = { boundaryCalls++ })
        controller.playFrom(1)
        controller.onSegmentFinished(1)

        val state = controller.onSegmentFinished(1)

        assertNull(state.currentIndex)
        assertTrue(state.nextChapterRequested)
        assertEquals("重复完成事件不能再次请求下一章", 1, boundaryCalls)
    }

    @Test
    fun stale_or_out_of_range_finish_is_ignored() {
        val controller = controller(0, 2)
        controller.playFrom(0)

        assertEquals(0, controller.onSegmentFinished(1).currentIndex)
        assertEquals(0, controller.onSegmentFinished(99).currentIndex)
        assertEquals(0, controller.onSegmentFinished(-1).currentIndex)
    }

    @Test
    fun finishing_when_idle_is_ignored() {
        val controller = controller(0, 1)

        val state = controller.onSegmentFinished(0)

        assertNull(state.currentIndex)
        assertFalse(state.chapterFinished)
    }

    @Test
    fun continuous_off_before_finish_stops_at_the_last_segment() {
        var boundaryCalls = 0
        val controller = controller(0, 1, onBoundary = { boundaryCalls++ })
        controller.setContinuous(false)
        controller.playFrom(1)

        val state = controller.onSegmentFinished(1)

        assertNull(state.currentIndex)
        assertTrue(state.chapterFinished)
        assertFalse(state.nextChapterRequested)
        assertEquals(0, boundaryCalls)
    }

    @Test
    fun continuous_off_clears_a_pending_next_chapter_request() {
        val controller = controller(0)
        controller.playFrom(0)
        val finished = controller.onSegmentFinished(0)
        assertTrue(finished.nextChapterRequested)

        val state = controller.setContinuous(false)

        assertFalse("关闭连播应撤销尚未消费的跨章请求", state.nextChapterRequested)
        assertTrue(state.chapterFinished)
        assertFalse(controller.isContinuous)
    }

    // ------------------------------------------------------------------
    // 暂停 / 恢复 / 播放器状态同步 / 索引越界
    // ------------------------------------------------------------------

    @Test
    fun pause_keeps_current_index_and_does_not_finish_mid_segment() {
        val controller = controller(0, 1)
        controller.playFrom(0)
        controller.onPlaybackState(0, 1_000L, 5_000L, isPlaying = true)

        val state = controller.onPlaybackState(0, 2_000L, 5_000L, isPlaying = false)

        assertEquals(0, state.currentIndex)
        assertFalse(state.chapterFinished)
        assertFalse(state.nextChapterRequested)
    }

    @Test
    fun resume_keeps_current_index_and_does_not_finish() {
        val controller = controller(0, 1)
        controller.playFrom(0)
        controller.onPlaybackState(0, 1_000L, 5_000L, isPlaying = true)
        controller.onPlaybackState(0, 2_000L, 5_000L, isPlaying = false)

        val state = controller.onPlaybackState(0, 2_500L, 5_000L, isPlaying = true)

        assertEquals(0, state.currentIndex)
        assertFalse(state.chapterFinished)
        assertFalse(state.nextChapterRequested)
    }

    @Test
    fun reaching_end_while_pausing_advances_to_next_segment() {
        val controller = controller(0, 1)
        controller.playFrom(0)
        controller.onPlaybackState(0, 500L, 1_000L, isPlaying = true)

        val state = controller.onPlaybackState(0, 1_000L, 1_000L, isPlaying = false)

        assertEquals(1, state.currentIndex)
        assertTrue(state.shouldScroll)
        assertFalse(state.chapterFinished)
    }

    @Test
    fun reaching_end_on_last_segment_finishes_the_chapter() {
        var boundaryCalls = 0
        val controller = controller(0, 1, onBoundary = { boundaryCalls++ })
        controller.playFrom(1)
        controller.onPlaybackState(1, 500L, 1_000L, isPlaying = true)

        val state = controller.onPlaybackState(1, 1_000L, 1_000L, isPlaying = false)

        assertNull(state.currentIndex)
        assertTrue(state.chapterFinished)
        assertTrue(state.nextChapterRequested)
        assertEquals(1, boundaryCalls)
    }

    @Test
    fun out_of_range_playing_index_is_ignored() {
        val controller = controller(0, 2)
        controller.playFrom(0)

        val state = controller.onPlaybackState(99, 1_000L, 5_000L, isPlaying = true)

        assertEquals(0, state.currentIndex)
        assertFalse(state.chapterFinished)
    }

    @Test
    fun external_player_index_change_moves_highlight_and_scrolls() {
        val controller = controller(0, 2, 4)
        controller.playFrom(0)

        val state = controller.onPlaybackState(2, 100L, 1_000L, isPlaying = true)

        assertEquals(2, state.currentIndex)
        assertTrue(state.shouldScroll)
    }

    @Test
    fun empty_playlist_ignores_player_state_and_finish_events() {
        val controller = controller()

        assertEquals(FollowState.Idle, controller.onPlaybackState(0, 0L, 0L, isPlaying = true))
        assertEquals(FollowState.Idle, controller.onSegmentFinished(0))
        assertEquals(0, controller.playableCount)
    }

    @Test
    fun repeat_player_state_after_chapter_end_does_not_restore_the_last_segment() {
        var boundaryCalls = 0
        val controller = controller(0, 1, onBoundary = { boundaryCalls++ })
        controller.playFrom(1)
        controller.onPlaybackState(1, 500L, 1_000L, isPlaying = true)
        controller.onPlaybackState(1, 1_000L, 1_000L, isPlaying = false)

        // ExoPlayer 结束后仍会把 currentMediaItem 报成最后一段。
        val state = controller.onPlaybackState(1, 1_000L, 1_000L, isPlaying = false)

        assertNull(state.currentIndex)
        assertTrue(state.chapterFinished)
        assertTrue(state.nextChapterRequested)
        assertEquals(1, boundaryCalls)
    }

    // ------------------------------------------------------------------
    // 上一句 / 下一句 / 可播放列表维护
    // ------------------------------------------------------------------

    @Test
    fun next_and_previous_follow_only_playable_segments() {
        val controller = controller(0, 2, 4)
        controller.playFrom(2)

        assertEquals(0, controller.playPrevious().currentIndex)
        assertEquals(2, controller.playNext().currentIndex)
        assertEquals(4, controller.playNext().currentIndex)
    }

    @Test
    fun next_after_last_segment_finishes_and_requests_next_chapter() {
        var boundaryCalls = 0
        val controller = controller(0, 2, onBoundary = { boundaryCalls++ })
        controller.playFrom(2)

        val state = controller.playNext()

        assertNull(state.currentIndex)
        assertTrue(state.chapterFinished)
        assertTrue(state.nextChapterRequested)
        assertEquals(1, boundaryCalls)
    }

    @Test
    fun previous_on_first_segment_restarts_it() {
        val controller = controller(2, 4)
        controller.playFrom(2)

        val state = controller.playPrevious()

        assertEquals(2, state.currentIndex)
        assertTrue(state.shouldScroll)
    }

    @Test
    fun playable_indexes_are_normalized_and_deduplicated() {
        val controller = PlaybackFollowController(
            playableIndexes = listOf(3, 1, 3, -1, 5),
        )

        assertEquals(3, controller.playableCount)
        assertEquals(1, controller.playFrom(0).currentIndex)
        assertTrue(controller.isPlayable(3))
        assertFalse(controller.isPlayable(2))
    }

    @Test
    fun set_playable_indexes_keeps_the_current_segment_when_it_still_has_audio() {
        val controller = controller(0, 1)
        controller.playFrom(1)

        val state = controller.setPlayableIndexes(listOf(0, 1, 2))

        assertEquals(1, state.currentIndex)
    }

    @Test
    fun set_playable_indexes_drops_the_current_segment_when_audio_disappears() {
        val controller = controller(0, 1)
        controller.playFrom(0)

        val state = controller.setPlayableIndexes(listOf(1))

        assertNull(state.currentIndex)
        assertEquals(1, controller.playableCount)
    }

    // ------------------------------------------------------------------
    // 与筛选后 visibleSegments 的整章 index 对齐
    // ------------------------------------------------------------------

    @Test
    fun playable_indexes_from_filtered_visible_segments_stay_chapter_global() {
        val all = listOf(
            uiSegment(index = 0, hasAudio = true, roleId = "role_a"),
            uiSegment(index = 1, hasAudio = true, roleId = "role_b"),
            uiSegment(index = 2, hasAudio = false, roleId = "role_b"),
            uiSegment(index = 3, hasAudio = true, roleId = "role_a"),
            uiSegment(index = 4, hasAudio = false, roleId = "role_a"),
        )
        val filtered = ScriptUiState(
            loading = false,
            segments = all,
            selectedRoleId = "role_b",
        )

        assertEquals(listOf(1), filtered.visibleSegments.map { it.index })
        assertEquals("筛选后的列表仍应给出整章 index", listOf(1), playableIndexesOf(filtered.visibleSegments))
        assertEquals(0, filtered.scrollTargetFor(1))
    }

    @Test
    fun mini_player_can_show_a_current_segment_that_is_filtered_out() {
        val all = listOf(
            uiSegment(index = 0, hasAudio = true, roleId = "role_a"),
            uiSegment(index = 1, hasAudio = true, roleId = "role_b"),
        )
        val filtered = ScriptUiState(
            loading = false,
            segments = all,
            selectedRoleId = "role_b",
            playingSegmentIndex = 0,
        )

        assertNull("筛选后列表里没有当前段", filtered.playingSegment)
        assertNull("被筛掉时页面不强制滚动", filtered.scrollTargetFor())
        assertEquals("迷你播放条仍能显示整章当前段", 0, filtered.currentSegment?.index)
    }

    @Test
    fun controller_and_scroll_target_agree_after_filtering_and_advancing() {
        val all = listOf(
            uiSegment(index = 0, hasAudio = true, roleId = "role_a"),
            uiSegment(index = 1, hasAudio = true, roleId = "role_b"),
            uiSegment(index = 2, hasAudio = true, roleId = "role_b"),
            uiSegment(index = 3, hasAudio = true, roleId = "role_a"),
        )
        val filtered = ScriptUiState(
            loading = false,
            segments = all,
            selectedRoleId = "role_b",
        )
        val controller = PlaybackFollowController()
        controller.setSegments(filtered.visibleSegments)

        val start = controller.playFrom(1)
        assertEquals(1, start.currentIndex)
        assertEquals("visible 行 0", 0, filtered.scrollTargetFor(start.currentIndex))

        val next = controller.onSegmentFinished(1)
        assertEquals(2, next.currentIndex)
        assertEquals("visible 行 1", 1, filtered.scrollTargetFor(next.currentIndex))
    }

    // ------------------------------------------------------------------
    // ViewModel 集成：followState / 暂停恢复 / 自动前进 / 跨章
    // ------------------------------------------------------------------

    @Test
    fun view_model_play_from_updates_follow_state_and_skips_no_audio() = runTest(dispatcher) {
        val viewModel = viewModel(
            entities = listOf(
                entity(index = 0, hasAudio = false),
                entity(index = 1, hasAudio = true),
                entity(index = 2, hasAudio = true),
            ),
        )
        advanceUntilIdle()

        val resolved = viewModel.playFrom(0)

        assertEquals(1, resolved)
        assertEquals(1, viewModel.state.value.playingSegmentIndex)
        assertTrue(viewModel.state.value.playing)
        assertEquals(1, viewModel.followState.value.currentIndex)
        assertTrue(viewModel.followState.value.skippedNoAudio)
    }

    @Test
    fun view_model_pause_and_resume_keep_the_current_segment() = runTest(dispatcher) {
        val viewModel = viewModel(audioEntities())
        advanceUntilIdle()
        viewModel.playFrom(1)

        viewModel.onPlaybackState(1, 500L, 1_000L, isPlaying = true)
        assertEquals(1, viewModel.state.value.playingSegmentIndex)
        assertTrue(viewModel.state.value.playing)

        viewModel.onPlaybackState(1, 700L, 1_000L, isPlaying = false)
        assertEquals(1, viewModel.state.value.playingSegmentIndex)
        assertFalse("暂停后迷你条应显示播放按钮", viewModel.state.value.playing)
        assertFalse(viewModel.followState.value.chapterFinished)

        viewModel.onPlaybackState(1, 800L, 1_000L, isPlaying = true)
        assertEquals(1, viewModel.state.value.playingSegmentIndex)
        assertTrue(viewModel.state.value.playing)
    }

    @Test
    fun view_model_auto_advance_reports_the_next_index_to_the_host() = runTest(dispatcher) {
        val advanced = mutableListOf<Int>()
        val viewModel = viewModel(audioEntities(), advanced = advanced)
        advanceUntilIdle()
        viewModel.playFrom(1)
        viewModel.onPlaybackState(1, 500L, 1_000L, isPlaying = true)

        viewModel.onPlaybackState(1, 1_000L, 1_000L, isPlaying = false)

        assertEquals(listOf(2), advanced)
        assertEquals(2, viewModel.state.value.playingSegmentIndex)
        assertTrue("自动前进后应保持“继续播放”语义", viewModel.state.value.playing)
        assertEquals(2, viewModel.followState.value.currentIndex)
    }

    @Test
    fun view_model_explicit_finish_advances_and_notifies_host() = runTest(dispatcher) {
        val advanced = mutableListOf<Int>()
        val viewModel = viewModel(audioEntities(), advanced = advanced)
        advanceUntilIdle()
        viewModel.playFrom(1)

        viewModel.onSegmentFinished(1)

        assertEquals(listOf(2), advanced)
        assertEquals(2, viewModel.state.value.playingSegmentIndex)
    }

    @Test
    fun view_model_chapter_boundary_callback_fires_only_with_continuous_on() = runTest(dispatcher) {
        var nextChapterCalls = 0
        val viewModel = viewModel(audioEntities(), nextChapter = { nextChapterCalls++ })
        advanceUntilIdle()

        viewModel.setContinuous(false)
        viewModel.playFrom(3)
        viewModel.onSegmentFinished(3)

        assertFalse(viewModel.state.value.playing)
        assertFalse(viewModel.followState.value.nextChapterRequested)
        assertEquals(0, nextChapterCalls)

        viewModel.setContinuous(true)
        viewModel.playFrom(3)
        viewModel.onSegmentFinished(3)

        assertTrue(viewModel.followState.value.chapterFinished)
        assertTrue(viewModel.followState.value.nextChapterRequested)
        assertEquals(1, nextChapterCalls)
    }

    @Test
    fun view_model_without_any_audio_does_not_start_playback() = runTest(dispatcher) {
        val viewModel = viewModel(
            entities = listOf(entity(0, hasAudio = false), entity(1, hasAudio = false)),
        )
        advanceUntilIdle()

        assertNull(viewModel.playFrom(0))
        assertNull(viewModel.state.value.playingSegmentIndex)
        assertFalse(viewModel.state.value.playing)
        assertFalse(viewModel.state.value.hasPlayableAudio)
        assertNull(viewModel.followState.value.currentIndex)
    }

    @Test
    fun view_model_playing_index_survives_filtering_with_correct_scroll_target() = runTest(dispatcher) {
        val viewModel = viewModel(
            entities = listOf(
                entity(0, hasAudio = true, roleId = "role_b"),
                entity(1, hasAudio = true, roleId = "role_a"),
                entity(2, hasAudio = true, roleId = "role_b"),
            ),
        )
        advanceUntilIdle()
        viewModel.setRoleFilter("role_b")
        assertEquals(listOf(0, 2), viewModel.state.value.visibleSegments.map { it.index })

        viewModel.playFrom(2)

        assertEquals(2, viewModel.state.value.playingSegmentIndex)
        assertEquals(1, viewModel.state.value.scrollTargetFor())
    }

    @Test
    fun view_model_one_shot_continuous_off_clears_pending_request() = runTest(dispatcher) {
        val viewModel = viewModel(audioEntities())
        advanceUntilIdle()
        viewModel.playFrom(3)
        viewModel.onSegmentFinished(3)
        assertTrue(viewModel.followState.value.nextChapterRequested)

        viewModel.setContinuous(false)

        assertFalse(viewModel.state.value.continuousPlayback)
        assertFalse(viewModel.followState.value.nextChapterRequested)
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun controller(
        vararg indexes: Int,
        continuous: Boolean = true,
        onBoundary: () -> Unit = {},
    ): PlaybackFollowController = PlaybackFollowController(
        playableIndexes = indexes.toList(),
        continuousPlayback = continuous,
        onChapterBoundary = onBoundary,
    )

    /** 0 无音频 / 1、2、3 有音频。 */
    private fun audioEntities(): List<SegmentEntity> = listOf(
        entity(index = 0, hasAudio = false),
        entity(index = 1, hasAudio = true),
        entity(index = 2, hasAudio = true),
        entity(index = 3, hasAudio = true),
    )

    private fun entity(
        index: Int,
        hasAudio: Boolean,
        roleId: String = "role_a",
    ): SegmentEntity = SegmentEntity(
        id = (index + 1).toLong(),
        chapterId = 1L,
        seq = index,
        type = "dialogue",
        roleId = roleId,
        emotion = "neutral",
        text = "第${index + 1}句",
        status = "done",
        audioRelPath = if (hasAudio) "audio/seg_${index + 1}.mp3" else null,
    )

    private fun uiSegment(
        index: Int,
        hasAudio: Boolean,
        roleId: String = "role_a",
    ): ScriptSegmentUi = ScriptSegmentUi(
        id = (index + 1).toLong(),
        index = index,
        type = SegmentType.Dialogue,
        roleId = roleId,
        roleName = "",
        emotion = Emotion.Neutral,
        text = "第${index + 1}句",
        status = "done",
        hasAudio = hasAudio,
        errorMessage = null,
    )

    private fun viewModel(
        entities: List<SegmentEntity>,
        advanced: MutableList<Int> = mutableListOf(),
        nextChapter: () -> Unit = {},
    ): ScriptViewModel = ScriptViewModel(
        chapterId = 1L,
        novelId = 2L,
        source = FakeScriptDataSource(entities),
        chapterTitle = "第三章 旧信",
        onPlaybackAdvanced = { index -> advanced += index },
        onNextChapter = nextChapter,
    )

    private class FakeScriptDataSource(
        entities: List<SegmentEntity>,
    ) : ScriptDataSource {
        private val segments = MutableStateFlow(entities)

        override fun observeSegments(chapterId: Long): Flow<List<SegmentEntity>> = segments

        override fun observeCharacters(novelId: Long): Flow<List<CharacterEntity>> =
            MutableStateFlow<List<CharacterEntity>>(emptyList())
    }
}
