package com.estimatefaster.audioforge.domain.jobs

import java.util.concurrent.ConcurrentHashMap

/**
 * 看门狗对某个批次的判定（§11.2 / §15 P3-07）。
 *
 * [Stop] 是所有「应当停止」判定的共同父类型，调用方可以只匹配
 * `is WatchdogDecision.Stop` 后统一收尾；需要区分文案时再细分：
 * - [Expired]：到达/超过 [JobWatchdog.maxDurationMs]；
 * - [Stopped]：已经 [JobWatchdog.markStopped] 过，不再重复报过期。
 */
sealed interface WatchdogDecision {

    /** 尚未到期，也没有被标记停止；继续跑。 */
    data object Continue : WatchdogDecision

    /** 统一停止决策；[reason] 可直接展示/写入日志。 */
    sealed interface Stop : WatchdogDecision {
        val reason: String
    }

    /** 到达或超过时长上限；应由调用方优雅收尾并提示下一批。 */
    data class Expired(override val reason: String) : Stop

    /** 该 job 已被显式标记停止；即使超过阈值也不再返回 [Expired]。 */
    data class Stopped(override val reason: String) : Stop
}

/**
 * 批次时长看门狗（执行计划 §11.2 / §15 P3-07）。
 *
 * 背景：Android 15+ 单次前台数据同步有 6 小时上限（§11.2），产品按 **5 小时**
 * 主动收尾，避免系统在不可控的时刻直接掐断任务。本类只负责**记录开始时间并给出
 * 决定**，不碰 WorkManager、不写 `jobs` 表、不取消 `WorkManager`：
 *
 * ```kotlin
 * watchdog.start(jobId)                       // Worker 开始执行时
 * when (val decision = watchdog.check(jobId)) {
 *     is WatchdogDecision.Expired -> {
 *         val hint = watchdog.nextBatchHint(jobId, done, total)
 *         // 调用方自行停止当前批次；下一个安全点收尾
 *     }
 *     is WatchdogDecision.Stopped -> Unit     // 已被停止流程处理
 *     WatchdogDecision.Continue -> Unit
 * }
 * ```
 *
 * 这样看门狗与 `SweepRunning` / [BatchRunner] / Worker 的停止路径解耦，
 * 单元测试不需要协程、Room 或 Android 组件。
 *
 * ## 语义
 * - **阈值**：`elapsed >= maxDurationMs` 即 [WatchdogDecision.Expired]（默认 5h，
 *   正好等于阈值也算到期）；`elapsed <` 返回 [WatchdogDecision.Continue]。
 * - **start 幂等**：同一个 job 重复 [start] 不会重置开始时间（避免每次重试/进度
 *   回调把 5 小时又往后推）；只有 [markStopped] 之后再次 [start] 才会重新计时
 *   （同一 job 行被复用的场景；终态 job 不应再 start）。
 * - **markStopped**：留下「已停止」标记，[check] 从此返回 [WatchdogDecision.Stopped]
 *   而不是 [WatchdogDecision.Expired]；重复调用幂等。[clear] 才彻底移除。
 * - **未知 job**：`check` 返回 [WatchdogDecision.Continue]（fail-open），避免因进程
 *   重启丢失内存状态而误杀任务；调用方应先 [start]。
 * - **进程死亡**：状态只在内存，重启后自然清空；残留 running 由 P3-05 sweep 负责，
 *   不靠看门狗。
 * - **时钟**：[clock] 可注入（测试用假时钟）。默认 `System.currentTimeMillis`，
 *   生产如需避免用户改系统时间，可注入 `SystemClock.elapsedRealtime` 等单调时钟。
 *
 * 线程安全：内部用 [ConcurrentHashMap]，[start] / [markStopped] 通过 `compute`
 * 原子更新；[check] 读取单个不可变 entry。并发下最终状态取决于操作顺序，
 * 但不会丢更新或抛 `ConcurrentModificationException`。
 *
 * @param maxDurationMs 时长上限，默认 [DEFAULT_MAX_DURATION_MS]（5 小时）；
 *   测试可缩短（必须为正数）。
 * @param clock 可注入的 `now` 来源；[now] 只是它的公开读取入口。
 */
class JobWatchdog(
    val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    init {
        require(maxDurationMs > 0L) { "maxDurationMs 必须为正数：$maxDurationMs" }
    }

    /** jobId → 开始时间/停止标记；不可变 entry，更新走 compute。 */
    private val entries = ConcurrentHashMap<Long, Entry>()

    /** 当前时钟（毫秒），也是 [start] 默认 `startedAt` 的来源。 */
    fun now(): Long = clock()

    /**
     * 记录批次开始时间。
     *
     * 已存在且未停止的 entry 不会被覆盖；已停止的 entry 会被重新武装（重新计时），
     * 供同一 job 行复用的重试路径使用。调用方通常只在 Worker 真正开始执行时调用一次。
     *
     * @param startedAt 开始时间戳；默认取 [now]，测试可显式传入。
     */
    fun start(jobId: Long, startedAt: Long = now()) {
        require(jobId > 0L) { "jobId 必须为正数：$jobId" }
        require(startedAt >= 0L) { "startedAt 不能为负：$startedAt" }

        entries.compute(jobId) { _, existing ->
            when {
                existing == null -> Entry(startedAt = startedAt, stopped = false)
                existing.stopped -> Entry(startedAt = startedAt, stopped = false)
                else -> existing
            }
        }
    }

    /**
     * 检查某个 job 是否该停。
     *
     * - 未登记 → [WatchdogDecision.Continue]（fail-open，见类注释）；
     * - 已 [markStopped] → [WatchdogDecision.Stopped]；
     * - `now - startedAt >= maxDurationMs` → [WatchdogDecision.Expired]；
     * - 其他 → [WatchdogDecision.Continue]。
     *
     * 时钟回拨导致 elapsed 为负时按未到期处理。
     */
    fun check(jobId: Long): WatchdogDecision {
        require(jobId > 0L) { "jobId 必须为正数：$jobId" }

        val entry = entries[jobId] ?: return WatchdogDecision.Continue
        if (entry.stopped) return WatchdogDecision.Stopped(STOPPED_REASON)

        val elapsedMs = now() - entry.startedAt
        return if (elapsedMs >= maxDurationMs) {
            WatchdogDecision.Expired(EXPIRED_REASON)
        } else {
            WatchdogDecision.Continue
        }
    }

    /**
     * 收尾时给用户的「下一批」提示。
     *
     * - 还有未完成章节：`已完成 X/Y，可稍后继续`；
     * - 已全部完成或 `totalChapters <= 0`：返回 null（没有可继续的内容）；
     * - 负数完成数按 0 处理，超过总数的完成数按全部完成处理。
     *
     * 本方法不判断是否真的到期/停止；调用方在收到 [WatchdogDecision.Expired] 或
     * [WatchdogDecision.Stopped] 后、写收尾文案时调用即可。
     *
     * @param jobId 仅用于关联/校验，不影响文案。
     */
    fun nextBatchHint(jobId: Long, completedChapters: Int, totalChapters: Int): String? {
        require(jobId > 0L) { "jobId 必须为正数：$jobId" }
        if (totalChapters <= 0) return null

        val done = completedChapters.coerceIn(0, totalChapters)
        if (done >= totalChapters) return null
        return "已完成 $done/$totalChapters，可稍后继续"
    }

    /**
     * 标记该 job 已停止。
     *
     * 保留一条 stopped tombstone，使后续 [check] 返回 [WatchdogDecision.Stopped]、
     * 不再报过期；即使之前没有 [start] 过也可以调用（防止停止后仍被误判 Expired）。
     * 需要彻底遗忘时用 [clear]。
     */
    fun markStopped(jobId: Long) {
        require(jobId > 0L) { "jobId 必须为正数：$jobId" }

        entries.compute(jobId) { _, existing ->
            if (existing == null) {
                Entry(startedAt = 0L, stopped = true)
            } else {
                existing.copy(stopped = true)
            }
        }
    }

    /** 移除该 job 的所有内存状态；之后 [check] 重新回到 Continue。 */
    fun clear(jobId: Long) {
        require(jobId > 0L) { "jobId 必须为正数：$jobId" }
        entries.remove(jobId)
    }

    private data class Entry(
        val startedAt: Long,
        val stopped: Boolean,
    )

    companion object {
        /** 默认 5 小时（Android 15+ 的 6h 系统上限之前主动收尾）。 */
        const val DEFAULT_MAX_DURATION_MS: Long = 5L * 60L * 60L * 1000L

        /**
         * 到期文案。按产品 5h 上限固定；测试缩短 [maxDurationMs] 时仍使用同一句，
         * 便于文案断言和 UI 复用。
         */
        const val EXPIRED_REASON: String = "已连续运行 5 小时，将收尾并建议下一批继续"

        /** 已标记停止时的决策文案。 */
        const val STOPPED_REASON: String = "任务已停止"
    }
}
