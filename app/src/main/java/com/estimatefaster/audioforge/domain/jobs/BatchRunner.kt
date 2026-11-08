package com.estimatefaster.audioforge.domain.jobs

import androidx.work.Data
import com.estimatefaster.audioforge.data.db.AppDatabase
import com.estimatefaster.audioforge.data.db.JobEntity
import com.estimatefaster.audioforge.data.repository.JobAlreadyActiveException
import com.estimatefaster.audioforge.data.repository.JobRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 一次批量生成请求的参数（§11.1 / §15 P3-04）。
 *
 * [mode] 用 wire 字符串（`full` / `parse` / `render`），以便把非法值当参数错误拒绝，
 * 而不是在调用方直接构造枚举时抛异常；[resolvedMode] 给出解析结果，null 表示非法。
 *
 * [concurrency] 默认 5、允许 1..8；[maxBatch] 默认 20、允许 1..20，本次
 * [chapterIds] 超过 [maxBatch] 直接拒绝。v1 同一本书串行处理章节，[concurrency]
 * 预留给段级/后续版本的并发控制（P3-06），不改变单活跃与串行语义。
 *
 * 校验由 [validate] 完成，[BatchRunner.start] 在写库/入队前调用；
 * 参数非法时不会留下任何 `jobs` 行。
 */
data class BatchParams(
    val novelId: Long,
    val chapterIds: List<Long>,
    val mode: String,
    val force: Boolean = false,
    val concurrency: Int = DEFAULT_CONCURRENCY,
    val maxBatch: Int = DEFAULT_MAX_BATCH,
) {

    val resolvedMode: GenerationMode?
        get() = GenerationMode.fromWire(mode)

    /** 返回第一条不合法原因；null 表示通过。保持纯函数，方便 UI 预校验。 */
    fun validate(): String? {
        if (novelId <= 0L) return "novelId 必须为正数"
        if (concurrency !in 1..MAX_CONCURRENCY) {
            return "并发数必须在 1..$MAX_CONCURRENCY 之间"
        }
        if (maxBatch !in 1..MAX_BATCH) {
            return "单批章节上限必须在 1..$MAX_BATCH 之间"
        }
        if (chapterIds.isEmpty()) return "chapterIds 不能为空"
        if (chapterIds.any { it <= 0L }) return "chapterIds 只能包含正数"
        if (chapterIds.size != chapterIds.distinct().size) {
            return "chapterIds 不能包含重复章节"
        }
        if (chapterIds.size > maxBatch) {
            return "章节数 ${chapterIds.size} 超过单批上限 $maxBatch"
        }
        if (resolvedMode == null) return "未知生成模式：$mode"
        return null
    }

    companion object {
        const val DEFAULT_CONCURRENCY = 5
        const val MAX_CONCURRENCY = 8
        const val DEFAULT_MAX_BATCH = 20
        const val MAX_BATCH = 20
    }
}

/** [BatchRunner.start] 的结果。 */
sealed interface StartResult {

    /** 已建 queued job 并成功入队。[workName] 供日志/诊断使用。 */
    data class Started(val jobId: Long, val workName: String) : StartResult

    /** 同一本书已有 queued/running 任务；本次没有落库、没有入队。 */
    data class AlreadyRunning(val jobId: Long) : StartResult

    /** 参数非法、建 job 失败或入队失败；入队失败时 job 已被标成 error。 */
    data class Failure(val reason: String) : StartResult
}

/** [BatchRunner.stop] 的结果。 */
sealed interface StopResult {

    /** 已置 `stopRequested` 并请求取消唯一工作；Worker 会在下一个安全点收尾。 */
    data class Stopping(val jobId: Long) : StopResult

    /** 当前没有活跃批次（已结束或从未启动）。 */
    data class NotRunning(val novelId: Long) : StopResult

    /** 既没写进停止标记、也没能请求取消（DB/WorkManager 异常）。 */
    data class Failure(val reason: String) : StopResult
}

/**
 * 批次调度器（执行计划 §11.1 / §15 P3-04）。
 *
 * 职责：参数校验 → 单活跃检查 → 建 `jobs` 行（queued）→ 以唯一任务名入队
 * [GenerationWorker]。它不执行章节，进度/终态由 Worker/执行器回写。
 *
 * ## 单活跃与队列语义
 * - **同一本书同一时间最多一个活跃批次**：`jobs` 表已有 queued/running 时直接返回
 *   [StartResult.AlreadyRunning]，不重复入队；并发 start 由 [JobRepository.start] 的
 *   事务内唯一约束兜底（[JobAlreadyActiveException] 同样映射成 AlreadyRunning）。
 * - **不同书互不阻塞**：唯一任务名是 `generation-<novelId>`，每本书一条 WorkManager
 *   工作；能否真正同时跑取决于 WorkManager 调度（§11.1 的 v1 串行策略属于产品层，
 *   本类不做跨书互斥）。
 * - **不自动抢占**：不做「先停旧任务再启动新任务」，避免用户误触导致两次生成；
 *   UI 需要先调用 [stop]。
 * - **入队失败**：job 行不会留在 queued 占住名额——先 `markRunning` 再 `markError`
 *   （`JobRepository` 的状态机只允许 running→error），并返回 [StartResult.Failure]。
 *
 * ## 停止与重启语义
 * [stop] 只做两件事：`requestStop(jobId)`（Worker/执行器在每个安全点检查）与
 * `queue.cancel(workName)`（WorkManager 取消协程）。**停止是异步的**：job 要等
 * Worker 收尾后才变成 `cancelled`；在此之前再次 [start] 仍返回 AlreadyRunning。
 * 收尾完成后 [start] 会创建**新的 job 行**（不复用旧的 pending/cancelled 行）：
 * 旧行保留历史与错误信息，新行重新记录本次参数；已完成的章节由用例层的
 * `done` 判定跳过，因此效果等同断点续传。`pending`（sweep 产物）只作历史，
 * 不会复活成可调度的任务。
 *
 * @param queue 生产用 [WorkManagerJobQueue]，测试用 fake
 * @param jobs 任务仓库（`data/**` 只读依赖）
 * @param db 用于 [observe] 读取 `lastFinished`；也保证装配方传入的是同一实例
 * @param clock 时间戳来源，测试注入固定值
 */
class BatchRunner(
    private val queue: JobQueue,
    private val jobs: JobRepository,
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * 启动一个批次。
     *
     * 成功返回 [StartResult.Started]（此时 job 已落库为 queued 且入队请求已被接受）；
     * 失败路径保证不会留下活跃 job。
     */
    suspend fun start(params: BatchParams): StartResult {
        params.validate()?.let { return StartResult.Failure(it) }
        val mode = params.resolvedMode ?: return StartResult.Failure("未知生成模式：${params.mode}")

        // 先查活跃行：重复 start 不落库、不重复入队。
        val active = jobs.activeJob(params.novelId)
        if (active != null) return StartResult.AlreadyRunning(active.id)

        val job = try {
            jobs.start(
                novelId = params.novelId,
                mode = mode.wire,
                requestedIds = params.chapterIds,
                stats = initialStats(params.chapterIds.size),
                now = clock(),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (already: JobAlreadyActiveException) {
            // 并发 start 的败者；胜者已经插入并（即将）入队，这里只报告。
            return StartResult.AlreadyRunning(already.activeJob.id)
        } catch (e: Exception) {
            // 例如 project 不存在时的外键失败；不能把异常抛给 UI，也不能留下半条任务。
            return StartResult.Failure("创建任务失败：${e.message ?: "未知错误"}")
        }

        val workName = GenerationNotifications.workName(params.novelId)
        val enqueued = try {
            queue.enqueue(workName, workerInput(job.id, params, mode))
        } catch (cancellation: CancellationException) {
            // 调用方在入队完成前被取消：不要留下永远 queued 的活跃行。
            withContext(NonCancellable) {
                runCatching { jobs.markCancelled(job.id, now = clock()) }
            }
            throw cancellation
        } catch (_: Exception) {
            false
        }

        if (!enqueued) {
            markEnqueueFailure(job.id)
            return StartResult.Failure("任务入队失败，请稍后重试")
        }
        return StartResult.Started(jobId = job.id, workName = workName)
    }

    /**
     * 请求停止当前批次。
     *
     * 先写 `stopRequested`（保证正在跑的 Worker 也能在安全点停下），再取消 WorkManager
     * 唯一工作（正在 suspend 的 Worker 会收到取消）。两者都失败才返回
     * [StopResult.Failure]；没有活跃批次返回 [StopResult.NotRunning]。
     */
    suspend fun stop(novelId: Long): StopResult {
        if (novelId <= 0L) return StopResult.NotRunning(novelId)
        val active = jobs.activeJob(novelId) ?: return StopResult.NotRunning(novelId)
        val workName = GenerationNotifications.workName(novelId)

        val requested = try {
            jobs.requestStop(active.id)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
        val cancelled = try {
            queue.cancel(workName)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }

        if (!requested && !cancelled) {
            return StopResult.Failure("任务 #${active.id} 停止请求未被接受")
        }
        // 注意：job 仍是 queued/running，直到 Worker 收尾写成 cancelled；这期间 start 会
        // 继续返回 AlreadyRunning（见类注释）。
        return StopResult.Stopping(active.id)
    }

    /**
     * 观察某本书当前批次：优先活跃任务（queued/running），没有则回退到最近一次已结束
     * 批次（done/error/cancelled）。`jobs` 表变化会触发重新查询，UI 不需要轮询。
     *
     * 这是「active/lastFinished 组合」的简化实现：`lastFinished` 是挂起查询，无法直接
     * 与 Flow 合并（Repository 只暴露 observeActive），因此在每次 active 发射时补查。
     */
    fun observe(novelId: Long): Flow<JobEntity?> =
        jobs.observeActive(novelId).map { active ->
            active ?: db.jobDao().lastFinished(novelId)
        }

    // ------------------------------------------------------------ 内部

    private fun workerInput(jobId: Long, params: BatchParams, mode: GenerationMode): Data =
        GenerationWorker.inputData(
            novelId = params.novelId,
            jobId = jobId,
            chapterIds = params.chapterIds.toLongArray(),
            mode = mode,
            force = params.force,
        )

    /**
     * 入队失败时收尾。`jobs` 状态机只允许 running→error，而这时行还是 queued，
     * 所以先 markRunning 再 markError；任一步失败都退回 cancelled，避免活跃名额泄漏。
     */
    private suspend fun markEnqueueFailure(jobId: Long) {
        val running = runCatching { jobs.markRunning(jobId) }.getOrDefault(false)
        if (running) {
            val marked = runCatching {
                jobs.markError(jobId, error = ENQUEUE_ERROR, now = clock())
            }.getOrDefault(false)
            if (marked) return
        }
        runCatching { jobs.markCancelled(jobId, now = clock()) }
    }

    private fun initialStats(chapterCount: Int): Map<String, Long> = mapOf(
        GenerationStats.KEY_CHAPTERS_TOTAL to chapterCount.toLong(),
        GenerationStats.KEY_CHAPTERS_DONE to 0L,
        GenerationStats.KEY_SEG_TOTAL to 0L,
        GenerationStats.KEY_SEG_DONE to 0L,
    )

    private companion object {
        const val ENQUEUE_ERROR = "任务入队失败"
    }
}
