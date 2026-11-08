package com.estimatefaster.audioforge.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 播放睡眠定时器（执行计划 §10.4：协程 `delay(minutes)` → pause）。
 *
 * 纯 JVM 实现，不依赖 Android，因此可以在虚拟时间的协程测试里验证：
 * - [start] 到点后回调 [onExpire]（通常是 `pause()`）；
 * - [cancel] 立即作废，到点也不再回调；
 * - 重复 [start] 会替换上一个定时器（旧定时器不会误触发）。
 *
 * [nowMs] 是可注入的单调时钟，仅用于 [remainingMs]；测试里可传
 * `testScheduler.currentTime`，与 `delay` 的虚拟时间保持一致。
 *
 * 线程约束：[CoroutineScope] 的调度器决定 [onExpire] 在哪个线程执行；
 * 播放器场景传入主线程作用域。
 */
class SleepTimer(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val onExpire: suspend () -> Unit,
) {

    private var job: Job? = null
    private var endAtMs: Long? = null

    /** 是否有尚未到点的定时器。 */
    val isActive: Boolean get() = job?.isActive == true

    /** 剩余毫秒；没有定时器时返回 `null`，到点边界返回 0。 */
    fun remainingMs(): Long? {
        val end = endAtMs ?: return null
        return (end - nowMs()).coerceAtLeast(0L)
    }

    /**
     * 启动（或替换）一个 [minutes] 分钟的睡眠定时器。
     *
     * @throws IllegalArgumentException [minutes] 不是正数
     */
    fun start(minutes: Int) {
        require(minutes > 0) { "睡眠定时器分钟数必须为正：$minutes" }
        cancel()
        val durationMs = minutes.toLong() * MILLIS_PER_MINUTE
        // 先自增 generation 再赋值，保证旧的 finally 不会清掉新定时器的状态。
        endAtMs = nowMs() + durationMs
        job = scope.launch {
            delay(durationMs)
            onExpire()
            // 自然到期才清理；被 cancel() 时该协程已取消，不会走到这里。
            if (job === coroutineContext[Job]) {
                endAtMs = null
                job = null
            }
        }
    }

    /** 取消定时器；可重复调用。 */
    fun cancel() {
        job?.cancel()
        job = null
        endAtMs = null
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
