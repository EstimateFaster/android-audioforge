package com.estimatefaster.audioforge.playback

import com.estimatefaster.audioforge.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-08：播放命令、睡眠参数、章节索引与通知动作的纯 JVM 测试。
 *
 * 这一层不启动服务、不碰 Media3：只验证服务与控制器共用的纯 Kotlin 语义，
 * 保证锁屏命令、自动续播和通知按钮的顺序/边界不会漂移。
 */
class PlayerCommandsTest {

    // ------------------------------------------------------------ 命令常量

    @Test
    fun custom_actions_are_namespaced_and_distinct() {
        assertEquals(4, PlaybackCommands.ALL_ACTIONS.size)
        assertEquals(
            "action 不能重复",
            PlaybackCommands.ALL_ACTIONS.size,
            PlaybackCommands.ALL_ACTIONS.toSet().size,
        )
        PlaybackCommands.ALL_ACTIONS.forEach { action ->
            assertTrue(
                "自定义 action 必须以应用包名前缀开始：$action",
                action.startsWith("com.estimatefaster.audioforge.playback.action."),
            )
        }
    }

    @Test
    fun kind_of_maps_each_known_action() {
        assertEquals(
            PlaybackCommandKind.NextChapter,
            PlaybackCommands.kindOf(PlaybackCommands.ACTION_NEXT_CHAPTER),
        )
        assertEquals(
            PlaybackCommandKind.PreviousChapter,
            PlaybackCommands.kindOf(PlaybackCommands.ACTION_PREV_CHAPTER),
        )
        assertEquals(
            PlaybackCommandKind.SleepTimer,
            PlaybackCommands.kindOf(PlaybackCommands.ACTION_SLEEP_TIMER),
        )
        assertEquals(
            PlaybackCommandKind.SetContinuous,
            PlaybackCommands.kindOf(PlaybackCommands.ACTION_SET_CONTINUOUS),
        )
        assertEquals(PlaybackCommandKind.Unknown, PlaybackCommands.kindOf(null))
        assertEquals(PlaybackCommandKind.Unknown, PlaybackCommands.kindOf("unknown.action"))
    }

    @Test
    fun player_deep_link_is_a_stable_uri() {
        assertEquals("audioforge://player", PlaybackCommands.PLAYER_DEEP_LINK)
    }

    // ------------------------------------------------------------ 睡眠参数

    @Test
    fun supported_sleep_minutes_are_15_30_and_60() {
        assertEquals(listOf(15, 30, 60), PlaybackCommands.SUPPORTED_SLEEP_MINUTES)
        PlaybackCommands.SUPPORTED_SLEEP_MINUTES.forEach { minutes ->
            assertTrue("$minutes 分钟应被接受", PlaybackCommands.isSupportedSleepMinutes(minutes))
            assertTrue("$minutes 分钟应是合法参数", PlaybackCommands.isValidSleepMinutes(minutes))
        }
    }

    @Test
    fun zero_minutes_means_off_and_is_valid() {
        assertEquals(0, PlaybackCommands.SLEEP_OFF_MINUTES)
        assertTrue(PlaybackCommands.isValidSleepMinutes(PlaybackCommands.SLEEP_OFF_MINUTES))
        assertFalse(PlaybackCommands.isSupportedSleepMinutes(PlaybackCommands.SLEEP_OFF_MINUTES))
    }

    @Test
    fun invalid_sleep_minutes_are_rejected() {
        listOf(-1, 1, 14, 16, 29, 31, 61, 120).forEach { minutes ->
            assertFalse("$minutes 分钟应被拒绝", PlaybackCommands.isValidSleepMinutes(minutes))
            assertFalse("$minutes 分钟不是档位", PlaybackCommands.isSupportedSleepMinutes(minutes))
        }
    }

    @Test
    fun sleep_button_cycles_15_30_60_off() {
        assertEquals(15, PlaybackCommands.nextSleepMinutes(PlaybackCommands.SLEEP_OFF_MINUTES))
        assertEquals(30, PlaybackCommands.nextSleepMinutes(15))
        assertEquals(60, PlaybackCommands.nextSleepMinutes(30))
        assertEquals(0, PlaybackCommands.nextSleepMinutes(60))
        // 未知/旧值回落到第一档，保证按钮永远可点。
        assertEquals(15, PlaybackCommands.nextSleepMinutes(7))
        assertEquals(15, PlaybackCommands.nextSleepMinutes(-5))
    }

    // ------------------------------------------------------------ 通知动作

    @Test
    fun notification_actions_have_fixed_order() {
        assertEquals(
            listOf(
                PlaybackNotificationAction.PreviousChapter,
                PlaybackNotificationAction.PlayPause,
                PlaybackNotificationAction.NextChapter,
                PlaybackNotificationAction.SleepTimer,
            ),
            PlaybackNotificationActions.actions,
        )
    }

    @Test
    fun play_pause_title_follows_playing_state() {
        assertEquals(
            R.string.player_pause,
            PlaybackNotificationActions.playPauseTitleRes(playing = true),
        )
        assertEquals(
            R.string.player_play,
            PlaybackNotificationActions.playPauseTitleRes(playing = false),
        )
    }

    @Test
    fun sleep_title_uses_active_or_off_resource() {
        assertEquals(
            R.string.player_notification_sleep_minutes,
            PlaybackNotificationActions.sleepTitleRes(15),
        )
        assertEquals(
            R.string.player_notification_sleep_minutes,
            PlaybackNotificationActions.sleepTitleRes(60),
        )
        assertEquals(
            R.string.player_notification_sleep_off,
            PlaybackNotificationActions.sleepTitleRes(0),
        )
    }

    // ------------------------------------------------------------ 章节队列

    @Test
    fun next_chapter_skips_empty_chapters() {
        val queue = queue()
        assertEquals(2, queue.nextChapterIndex(0))
        assertNull("最后一章之后没有下一章", queue.nextChapterIndex(2))
    }

    @Test
    fun next_chapter_from_unstarted_returns_first_playable_chapter() {
        assertEquals(0, queue().nextChapterIndex(null))
        assertNull("空队列应从零章开始且无下一章", PlaybackQueue().nextChapterIndex(null))
        assertNull("空队列没有下一章", emptyQueue().nextChapterIndex(null))
    }

    @Test
    fun previous_chapter_skips_empty_chapters() {
        val queue = queue()
        assertEquals(0, queue.previousChapterIndex(2))
        assertNull("第一章之前没有上一章", queue.previousChapterIndex(0))
        assertNull("未开始时没有上一章", queue.previousChapterIndex(null))
    }

    @Test
    fun advance_after_middle_segment_moves_within_chapter() {
        val advance = queue().advanceAfter(chapterIndex = 0, segmentIndex = 0)
        assertTrue("本章还有下一段时应返回 NextItem", advance is PlaybackAdvance.NextItem)
        assertEquals(1, (advance as PlaybackAdvance.NextItem).item.segmentIndex)
    }

    @Test
    fun advance_after_last_segment_with_continuous_enters_next_chapter() {
        val advance = queue(continuous = true)
            .advanceAfter(chapterIndex = 0, segmentIndex = 1)
        assertTrue(advance is PlaybackAdvance.NextChapter)
        advance as PlaybackAdvance.NextChapter
        assertEquals(2, advance.chapterIndex)
        assertEquals(5, advance.chapter.firstItem?.segmentIndex)
    }

    @Test
    fun advance_after_last_segment_without_continuous_stops() {
        val advance = queue(continuous = false)
            .advanceAfter(chapterIndex = 0, segmentIndex = 1)
        assertEquals(PlaybackAdvance.Stop, advance)
    }

    @Test
    fun advance_after_last_chapter_stops() {
        val advance = queue(continuous = true)
            .advanceAfter(chapterIndex = 2, segmentIndex = 5)
        assertEquals(PlaybackAdvance.Stop, advance)
    }

    @Test
    fun advance_with_unknown_segment_stops_instead_of_jumping() {
        val queue = queue()
        assertEquals(PlaybackAdvance.Stop, queue.advanceAfter(0, segmentIndex = null))
        assertEquals(PlaybackAdvance.Stop, queue.advanceAfter(0, segmentIndex = 99))
        assertEquals(PlaybackAdvance.Stop, queue.advanceAfter(99, segmentIndex = 0))
    }

    @Test
    fun queue_index_of_segment_and_empty_checks() {
        val queue = queue()
        assertEquals(1, queue.indexOfSegment(chapterIndex = 0, segmentIndex = 1))
        assertNull(queue.indexOfSegment(chapterIndex = 1, segmentIndex = 0))
        assertNull(queue.indexOfSegment(chapterIndex = 0, segmentIndex = 99))
        assertFalse(queue.isEmpty)
        assertTrue(PlaybackQueue().isEmpty)
        assertTrue(emptyQueue().isEmpty)
        assertEquals(0, queue.firstPlayableChapterIndex())
        assertNull(emptyQueue().firstPlayableChapterIndex())
    }

    // ------------------------------------------------------------ fixtures

    private fun queue(continuous: Boolean = true): PlaybackQueue = PlaybackQueue(
        chapters = listOf(
            chapter(id = 1L, segments = listOf(0, 1)),
            chapter(id = 2L, segments = emptyList()),
            chapter(id = 3L, segments = listOf(5)),
        ),
        continuous = continuous,
    )

    private fun emptyQueue(): PlaybackQueue =
        PlaybackQueue(chapters = listOf(chapter(id = 9L, segments = emptyList())))

    private fun chapter(id: Long, segments: List<Int>): PlaybackQueueChapter =
        PlaybackQueueChapter(
            chapterId = id,
            title = "第 $id 章",
            items = segments.map { segment ->
                PlaybackQueueItem(
                    mediaId = "ch$id-seg$segment",
                    uri = "file:///tmp/ch$id-seg$segment.mp3",
                    title = "第 $id 章",
                    subtitle = null,
                    segmentIndex = segment,
                )
            },
        )
}
