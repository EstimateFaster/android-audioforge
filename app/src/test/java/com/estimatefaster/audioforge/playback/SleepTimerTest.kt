package com.estimatefaster.audioforge.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * P2-14：睡眠定时器纯 JVM 测试。
 *
 * 用 `runTest` 的虚拟时间代替真实等待：`delay(1 分钟)` 由 `advanceTimeBy` 推进，
 * 同时把 `testScheduler.currentTime` 作为 `nowMs`，保证 `remainingMs()` 与虚拟时间一致。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {

    @Test
    fun expires_exactly_after_the_configured_minutes_and_pauses() = runTest {
        var pauseCount = 0
        val timer = SleepTimer(this, nowMs = { testScheduler.currentTime }) {
            pauseCount++
        }

        timer.start(1)
        assertTrue(timer.isActive)
        assertEquals(60_000L, timer.remainingMs())

        advanceTimeBy(59_999L)
        runCurrent()
        assertEquals("未到点不应暂停", 0, pauseCount)
        assertEquals(1L, timer.remainingMs())

        advanceTimeBy(1L)
        runCurrent()
        assertEquals("到点应暂停一次", 1, pauseCount)
        assertFalse(timer.isActive)
        assertNull(timer.remainingMs())
    }

    @Test
    fun cancel_before_deadline_never_pauses() = runTest {
        var paused = false
        val timer = SleepTimer(this, nowMs = { testScheduler.currentTime }) {
            paused = true
        }

        timer.start(1)
        advanceTimeBy(30_000L)
        runCurrent()
        timer.cancel()

        assertFalse(timer.isActive)
        assertNull(timer.remainingMs())

        // 越过原定到期时间很多，也不应回调。
        advanceTimeBy(10L * 60_000L)
        runCurrent()
        assertFalse("取消后不应暂停", paused)
    }

    @Test
    fun restart_replaces_the_previous_deadline() = runTest {
        var pauseCount = 0
        val timer = SleepTimer(this, nowMs = { testScheduler.currentTime }) {
            pauseCount++
        }

        timer.start(1)
        advanceTimeBy(30_000L)
        runCurrent()

        // 重新设置 2 分钟：旧定时器必须失效，否则会在第 60s 误暂停。
        timer.start(2)
        assertEquals(120_000L, timer.remainingMs())

        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals("旧定时器不应触发", 0, pauseCount)

        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals("新定时器到点应暂停", 1, pauseCount)
        assertFalse(timer.isActive)
    }

    @Test
    fun cancel_is_idempotent() = runTest {
        val timer = SleepTimer(this, nowMs = { testScheduler.currentTime }) {}
        timer.cancel()
        timer.cancel()
        assertFalse(timer.isActive)
        assertNull(timer.remainingMs())
    }

    @Test
    fun non_positive_minutes_are_rejected() = runTest {
        val timer = SleepTimer(this, nowMs = { testScheduler.currentTime }) {}
        assertThrows(IllegalArgumentException::class.java) { timer.start(0) }
        assertThrows(IllegalArgumentException::class.java) { timer.start(-5) }
    }
}
