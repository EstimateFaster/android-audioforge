package com.estimatefaster.audioforge.domain.jobs

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3-07：`JobWatchdog` 的阈值、幂等 start、停止标记、提示文案与并发安全。
 *
 * 纯 JVM 测试：不需要 Robolectric/Room/WorkManager；时钟用可变 `nowMs` 注入，
 * 阈值缩短到 [MAX_DURATION_MS] 以免测试真的等 5 小时。
 */
class JobWatchdogTest {

    private var nowMs: Long = 1_000L

    private val watchdog = JobWatchdog(
        maxDurationMs = MAX_DURATION_MS,
        clock = { nowMs },
    )

    // ------------------------------------------------------------ 阈值

    @Test
    fun before_threshold_returns_continue() {
        watchdog.start(JOB_ID, startedAt = nowMs)

        nowMs += MAX_DURATION_MS - 1

        assertSame(WatchdogDecision.Continue, watchdog.check(JOB_ID))
    }

    @Test
    fun exact_threshold_returns_expired() {
        val startedAt = 5_000L
        watchdog.start(JOB_ID, startedAt = startedAt)
        nowMs = startedAt + MAX_DURATION_MS

        val decision = watchdog.check(JOB_ID)

        assertTrue("正好阈值应到期，实际 $decision", decision is WatchdogDecision.Expired)
        assertTrue(decision is WatchdogDecision.Stop)
        assertEquals(JobWatchdog.EXPIRED_REASON, (decision as WatchdogDecision.Expired).reason)
    }

    @Test
    fun over_threshold_returns_expired() {
        watchdog.start(JOB_ID, startedAt = 1_000L)
        nowMs = 1_000L + MAX_DURATION_MS + 1

        assertTrue(watchdog.check(JOB_ID) is WatchdogDecision.Expired)
    }

    @Test
    fun default_max_duration_is_five_hours() {
        assertEquals(5L * 60L * 60L * 1000L, JobWatchdog.DEFAULT_MAX_DURATION_MS)
        assertEquals(18_000_000L, JobWatchdog.DEFAULT_MAX_DURATION_MS)
    }

    // ------------------------------------------------------------ start / clear

    @Test
    fun duplicate_start_does_not_reset_the_timer() {
        watchdog.start(JOB_ID, startedAt = 1_000L)
        nowMs = 1_000L + MAX_DURATION_MS / 2

        // 重试/进度回调可能再次 start；不能把 5h 重新计时。
        watchdog.start(JOB_ID)
        watchdog.start(JOB_ID, startedAt = 2_000L)

        nowMs = 1_000L + MAX_DURATION_MS
        assertTrue(watchdog.check(JOB_ID) is WatchdogDecision.Expired)
    }

    @Test
    fun unknown_job_returns_continue() {
        assertSame(WatchdogDecision.Continue, watchdog.check(JOB_ID))
    }

    @Test
    fun clear_forgets_state_and_allows_a_fresh_start() {
        watchdog.start(JOB_ID, startedAt = 1_000L)
        watchdog.markStopped(JOB_ID)
        nowMs = 1_000L + MAX_DURATION_MS + 1

        watchdog.clear(JOB_ID)
        assertSame(WatchdogDecision.Continue, watchdog.check(JOB_ID))

        watchdog.start(JOB_ID, startedAt = 2_000L)
        nowMs = 2_000L + MAX_DURATION_MS
        assertTrue(watchdog.check(JOB_ID) is WatchdogDecision.Expired)
    }

    // ------------------------------------------------------------ markStopped

    @Test
    fun mark_stopped_returns_stopped_instead_of_expired() {
        watchdog.start(JOB_ID, startedAt = 1_000L)
        nowMs = 1_000L + MAX_DURATION_MS + 5
        assertTrue(watchdog.check(JOB_ID) is WatchdogDecision.Expired)

        watchdog.markStopped(JOB_ID)

        val decision = watchdog.check(JOB_ID)
        assertTrue(decision is WatchdogDecision.Stopped)
        assertFalse(decision is WatchdogDecision.Expired)
        assertEquals(JobWatchdog.STOPPED_REASON, (decision as WatchdogDecision.Stopped).reason)

        // 幂等：重复标记仍是 Stopped。
        watchdog.markStopped(JOB_ID)
        assertTrue(watchdog.check(JOB_ID) is WatchdogDecision.Stopped)
    }

    @Test
    fun mark_stopped_before_start_is_still_stopped() {
        watchdog.markStopped(JOB_ID)

        assertTrue(watchdog.check(JOB_ID) is WatchdogDecision.Stopped)
    }

    @Test
    fun start_after_mark_stopped_rearms_the_timer() {
        watchdog.start(JOB_ID, startedAt = 1_000L)
        watchdog.markStopped(JOB_ID)
        assertTrue(watchdog.check(JOB_ID) is WatchdogDecision.Stopped)

        nowMs = 2_000L
        watchdog.start(JOB_ID)

        nowMs = 2_000L + MAX_DURATION_MS - 1
        assertSame(WatchdogDecision.Continue, watchdog.check(JOB_ID))

        nowMs = 2_000L + MAX_DURATION_MS
        assertTrue(watchdog.check(JOB_ID) is WatchdogDecision.Expired)
    }

    // ------------------------------------------------------------ 下一批提示

    @Test
    fun next_batch_hint_for_an_unfinished_batch() {
        assertEquals(
            "已完成 2/5，可稍后继续",
            watchdog.nextBatchHint(JOB_ID, completedChapters = 2, totalChapters = 5),
        )
    }

    @Test
    fun next_batch_hint_with_zero_completed_chapters() {
        assertEquals(
            "已完成 0/5，可稍后继续",
            watchdog.nextBatchHint(JOB_ID, completedChapters = 0, totalChapters = 5),
        )
    }

    @Test
    fun next_batch_hint_returns_null_when_everything_is_done() {
        assertNull(watchdog.nextBatchHint(JOB_ID, completedChapters = 5, totalChapters = 5))
        assertNull(watchdog.nextBatchHint(JOB_ID, completedChapters = 9, totalChapters = 5))
    }

    @Test
    fun next_batch_hint_returns_null_for_an_empty_batch() {
        assertNull(watchdog.nextBatchHint(JOB_ID, completedChapters = 0, totalChapters = 0))
        assertNull(watchdog.nextBatchHint(JOB_ID, completedChapters = 3, totalChapters = 0))
    }

    @Test
    fun next_batch_hint_clamps_negative_completed_chapters() {
        assertEquals(
            "已完成 0/5，可稍后继续",
            watchdog.nextBatchHint(JOB_ID, completedChapters = -2, totalChapters = 5),
        )
    }

    // ------------------------------------------------------------ 并发

    @Test
    fun concurrent_access_keeps_state_consistent() {
        val concurrent = JobWatchdog(maxDurationMs = 10L, clock = { 1_000L })
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val gate = CountDownLatch(1)
        try {
            val futures = (0 until threads).map { index ->
                pool.submit(
                    Runnable {
                        gate.await()
                        repeat(500) {
                            when (index % 4) {
                                0 -> concurrent.start(JOB_ID, startedAt = 1_000L)
                                1 -> concurrent.check(JOB_ID)
                                2 -> concurrent.markStopped(JOB_ID)
                                else -> concurrent.clear(JOB_ID)
                            }
                        }
                    },
                )
            }
            gate.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        // 竞争结束后再置一次，确认 tombstone 生效且 clear 能彻底移除。
        concurrent.markStopped(JOB_ID)
        assertTrue(concurrent.check(JOB_ID) is WatchdogDecision.Stopped)
        concurrent.clear(JOB_ID)
        assertSame(WatchdogDecision.Continue, concurrent.check(JOB_ID))
    }

    private companion object {
        const val JOB_ID = 7L
        const val MAX_DURATION_MS = 10_000L
    }
}
