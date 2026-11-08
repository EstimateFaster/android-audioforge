package com.estimatefaster.audioforge.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.session.SessionCommand
import androidx.test.core.app.ApplicationProvider
import com.estimatefaster.audioforge.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P5-08：自定义命令的 Bundle/Intent 包装测试（Robolectric）。
 *
 * 纯 Kotlin 的常量/队列语义在 [PlayerCommandsTest]；这里只验证真正跨进程的边界：
 * - `SessionCommand` 的 customAction 原样保留；
 * - 睡眠参数“显式 0”与“完全没传”能区分；
 * - 通知点击的 PendingIntent 指向 MainActivity 并带播放器深链。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackCommandExtrasTest {

    @Test
    fun sleep_timer_extras_round_trip_explicit_minutes() {
        val extras = PlaybackSessionCommands.sleepTimerExtras(30)
        assertEquals(30, PlaybackSessionCommands.parseSleepMinutes(extras))
    }

    @Test
    fun explicit_zero_is_not_the_same_as_missing_minutes() {
        assertEquals(
            0,
            PlaybackSessionCommands.parseSleepMinutes(
                PlaybackSessionCommands.sleepTimerExtras(0),
            ),
        )
        assertNull(PlaybackSessionCommands.parseSleepMinutes(Bundle.EMPTY))
        assertNull(PlaybackSessionCommands.parseSleepMinutes(null))
    }

    @Test
    fun custom_command_keeps_action_and_is_custom_code() {
        val command = PlaybackSessionCommands.command(PlaybackCommands.ACTION_NEXT_CHAPTER)
        assertEquals(PlaybackCommands.ACTION_NEXT_CHAPTER, command.customAction)
        assertEquals(SessionCommand.COMMAND_CODE_CUSTOM, command.commandCode)
    }

    @Test
    fun chapter_and_sleep_results_carry_expected_extras() {
        val chapter = PlaybackSessionCommands.chapterResult(
            chapterIndex = 3,
            chapterId = 42L,
            title = "第三章",
        )
        assertEquals(3, chapter.getInt(PlaybackCommands.EXTRA_CHAPTER_INDEX))
        assertEquals(42L, chapter.getLong(PlaybackCommands.EXTRA_CHAPTER_ID))
        assertEquals("第三章", chapter.getString(PlaybackCommands.EXTRA_CHAPTER_TITLE))

        val sleep = PlaybackSessionCommands.sleepResult(60)
        assertEquals(60, sleep.getInt(PlaybackCommands.EXTRA_SLEEP_MINUTES))
    }

    @Test
    fun continuous_switch_round_trip_defaults_to_current_value() {
        assertEquals(
            true,
            PlaybackSessionCommands.parseContinuous(
                PlaybackSessionCommands.continuousExtras(true),
                default = false,
            ),
        )
        assertEquals(
            false,
            PlaybackSessionCommands.parseContinuous(
                PlaybackSessionCommands.continuousExtras(false),
                default = true,
            ),
        )
        assertEquals(true, PlaybackSessionCommands.parseContinuous(null, default = true))
    }

    @Test
    fun open_player_intent_points_to_main_activity_with_deep_link() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = PlaybackSessionCommands.openPlayerIntent(context)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(PlaybackCommands.PLAYER_DEEP_LINK, intent.dataString)
    }
}
