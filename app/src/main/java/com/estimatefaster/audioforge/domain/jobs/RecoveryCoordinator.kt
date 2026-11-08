package com.estimatefaster.audioforge.domain.jobs

import com.estimatefaster.audioforge.data.db.JobEntity
import com.estimatefaster.audioforge.data.db.JobStatus
import com.estimatefaster.audioforge.data.repository.JobAlreadyActiveException
import com.estimatefaster.audioforge.data.repository.JobRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** 一次被重新排程的任务；[sourceJobId] 是触发恢复的旧行，[jobId] 是实际入队的行。 */
data class RescheduledJob(
    val sourceJobId: Long,
    val jobId: Long,
    val novelId: Long,
    val workName: String,
)

/** 被跳过的恢复候选；[reason] 是给日志/诊断看的可读原因，不影响 App 启动。 */
data class SkippedJob(
    val novelId: Long,
    val jobId: Long? = null,
    val reason: String,
)

/** 恢复过程中的失败（读取任务/建行/入队）；统一进报告，不向 App 启动路径抛出。 */
data class RecoveryFailure(
    val novelId: Long,
    val jobId: Long? = null,
    val reason: String,
)

/**
 * App 启动恢复报告（执行计划 §11.2 / §15 P3-08）。
 *
 * [swept] 是 `SweepRunning.sweepOnAppStart()` 本次实际复位的 running 行数（权威值）；
 * [rescheduled] / [skipped] / [failures] 是本类本次处理的候选。
 * 三者不要求相加等于 [swept]：例如 sweep 明细缺失、同一项目还有 queued 行、
 * 或 active work 检查失败时，都会以 skipped/failures 的形式单独报告。
 */
data class RecoveryReport(
    val swept: Int = 0,
    val rescheduled: List<RescheduledJob> = emptyList(),
    val skipped: List<SkippedJob> = emptyList(),
    val failures: List<RecoveryFailure> = emptyList(),
    val at: Long = 0L,
) {
    val rescheduledCount: Int get() = rescheduled.size
    val skippedCount: Int get() = skipped.size
    val failureCount: Int get() = failures.size
    val hasFailures: Boolean get() = failures.isNotEmpty()
}

/**
 * 进程死亡/重启后的任务恢复协调器（执行计划 §11.2 / §15 P3-08）。
 *
 * 启动路径：`sweepOnAppStart()` 把上个进程残留的 `running` 复位成 `pending`，然后对
 * 「有 pending/queued 行、但 WorkManager 没有对应唯一 work」的书重新入队：
 *
 * - **queued 行**（Worker 还没开始就随进程死亡）复用原 `jobs` 行，用原 jobId 入队；
 * - **pending 行**只可能来自 sweep 明细。`JobRepository` 没有 pending→queued 的跃迁，
 *   直接拿 pending 行入队会让 Worker 的 `markRunning` 失败，因此这里通过
 *   [JobRepository.start] 新建一条 queued 行（沿用原 mode/requestedIds），旧 pending
 *   行保留为历史；这与 P3-04「收尾后重新 start 会新建 job 行」的语义一致；
 * - **active work 检查**：`isWorkActive` 为 true 时只记 skipped，不重复入队；
 *   [JobQueue.enqueue] 本身用 `KEEP`，即使检查竞态也不会替换正在跑的工作；
 * - **不重跑已完成段**：本类不读章节/片段、不调用执行器；入队后由 P2 缓存与
 *   `AnalyzeChapterUseCase`/`RenderChapterUseCase` 的 done 判定跳过已完成内容；
 * - **只重排可行任务**：`requestedIdsJson` 为空/脏、mode 非法、任务已不存在等情况
 *   记 skipped，不猜测章节范围。调用方若知道整本未完成章节，应先用
 *   `BatchRunner.start` 新建批次，而不是让本类补范围；
 * - **失败不抛**：入队失败、建行失败、读取失败都进 [RecoveryReport.failures]；
 *   只有协程取消原样透传。
 *
 * ## 幂等
 * 连续调用不会重复入队：第一次成功后 WorkManager 有 active work，第二次在
 * [rescheduleOne] 的 active 检查处 skipped；若 fake/边界情况下 active 检查返回 false，
 * [JobQueue.enqueue] 的 `KEEP` 仍会拒绝替换。新建的 queued 行在入队失败时会保留，
 * 下一次恢复会复用同一行重试，而不是每轮新建。
 *
 * ## 依赖与限制
 * - [knownNovelIds] 用于扫描 queued 行与历史；现有 `data/` API 没有「全表 pending」
 *   查询，pending 候选只能来自 [SweepRunning.sweepOnAppStart] 的 `details`，因此生产
 *   装配应给 [SweepRunning] 注入与这里相同的 `knownNovelIds`。
 * - [isWorkActive] 默认用 [JobQueue.observe] 的第一条快照。fake 队列足够；
 *   生产若需要覆盖同名唯一工作的全部历史 WorkInfo，可注入 WorkManager 版本。
 * - [clock] 供 `startedAt`/报告时间戳使用；测试注入固定值。
 *
 * @param sweep 启动 sweep（P3-05）
 * @param jobs 任务仓库（只读 + 既有状态机方法，不新增 DAO 查询）
 * @param queue 唯一工作队列；生产为 [WorkManagerJobQueue]
 * @param knownNovelIds 项目 id 列表，用于扫描 queued 行与历史
 * @param clock 时间戳来源
 * @param isWorkActive `workName -> 是否仍有活跃唯一工作`；查询失败按 active 处理
 */
class RecoveryCoordinator(
    private val sweep: SweepRunning,
    private val jobs: JobRepository,
    private val queue: JobQueue,
    private val knownNovelIds: suspend () -> List<Long> = { emptyList() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val isWorkActive: suspend (String) -> Boolean = { workName ->
        queue.observe(workName).first()?.isActive == true
    },
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * App 启动入口：先 sweep，再重排 pending/queued 的可行任务。
     *
     * sweep 本身抛数据库异常时返回带 failure 的空报告（不崩溃启动路径）；协程取消
     * 原样透传。`SweepReport.details` 为空（例如 [SweepRunning] 没注入 knownNovelIds）
     * 时，[RecoveryReport.swept] 仍会报告真实复位行数，但 pending 候选无法重排。
     */
    suspend fun recoverOnAppStart(): RecoveryReport {
        val sweepReport = try {
            sweep.sweepOnAppStart()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            return RecoveryReport(
                failures = listOf(
                    RecoveryFailure(
                        novelId = 0L,
                        reason = "启动 sweep 失败：${e.message ?: "未知错误"}",
                    ),
                ),
                at = clock(),
            )
        }
        return reschedulePendingAndQueued(sweepReport.details)
            .copy(swept = sweepReport.count, at = clock())
    }

    /**
     * 手动恢复单个任务（任务页「继续」/诊断用）。
     *
     * - queued：复用原行重新入队；
     * - pending：新建 queued 行后入队（原因见类注释）；
     * - 其他状态：skipped，不复活终态任务；
     * - jobId 非法/不存在：skipped，不抛异常。
     */
    suspend fun reschedule(jobId: Long): RecoveryReport {
        if (jobId <= 0L) {
            return RecoveryReport(
                skipped = listOf(
                    SkippedJob(novelId = 0L, jobId = jobId, reason = "jobId 必须为正数"),
                ),
                at = clock(),
            )
        }
        val job = try {
            jobs.get(jobId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            return RecoveryReport(
                failures = listOf(
                    RecoveryFailure(
                        novelId = 0L,
                        jobId = jobId,
                        reason = "读取任务失败：${e.message ?: "未知错误"}",
                    ),
                ),
                at = clock(),
            )
        }
        if (job == null) {
            return RecoveryReport(
                skipped = listOf(
                    SkippedJob(novelId = 0L, jobId = jobId, reason = "任务不存在"),
                ),
                at = clock(),
            )
        }
        return rescheduleJobs(listOf(job))
    }

    /**
     * 重新扫描所有已知项目的 queued 行并重排（不额外 sweep）。
     *
     * pending 行不在 `activeJob`/`lastFinished` 的查询范围内；需要恢复 pending 时请用
     * [recoverOnAppStart]，它会把本次 sweep 的明细一并传入。已有 active work 的项目
     * 会被 skipped，重复调用不会重复入队。
     */
    suspend fun rescheduleAll(): RecoveryReport = reschedulePendingAndQueued(emptyList())

    /**
     * 任务页历史：每个项目最近一次已结束批次（done/error/cancelled），按
     * `finishedAt` 倒序、同时间按 id 倒序，最多 [limit] 条。
     *
     * `pending/queued/running` 不进入历史；读取失败的项目跳过，保证任务页可用；
     * 协程取消原样透传。
     */
    suspend fun history(limit: Int = DEFAULT_HISTORY_LIMIT): List<JobEntity> {
        if (limit <= 0) return emptyList()
        val ids = try {
            knownNovelIds()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return emptyList()
        }
        val seen = mutableSetOf<Long>()
        val finished = mutableListOf<JobEntity>()
        for (novelId in ids) {
            if (novelId <= 0L || !seen.add(novelId)) continue
            val job = try {
                jobs.lastFinished(novelId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
            if (job != null) finished += job
        }
        return finished
            .sortedWith(
                compareByDescending<JobEntity> { it.finishedAt ?: 0L }
                    .thenByDescending { it.id },
            )
            .take(limit)
    }

    // ------------------------------------------------------------ 内部

    /**
     * 扫描 knownNovelIds 的 queued 行，再补上本次 sweep 的 pending 明细。
     * 每本书最多处理一个候选，避免同一唯一工作名重复入队。
     */
    private suspend fun reschedulePendingAndQueued(
        pendingSources: List<SweptJob>,
    ): RecoveryReport {
        val rescheduled = mutableListOf<RescheduledJob>()
        val skipped = mutableListOf<SkippedJob>()
        val failures = mutableListOf<RecoveryFailure>()
        val handledNovels = mutableSetOf<Long>()

        val known = try {
            knownNovelIds()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            failures += RecoveryFailure(
                novelId = 0L,
                reason = "读取项目列表失败：${e.message ?: "未知错误"}",
            )
            emptyList()
        }

        for (novelId in known) {
            if (novelId <= 0L || !handledNovels.add(novelId)) continue
            val active = try {
                jobs.activeJob(novelId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                failures += RecoveryFailure(
                    novelId = novelId,
                    reason = "读取活跃任务失败：${e.message ?: "未知错误"}",
                )
                null
            } ?: continue
            rescheduleOne(active, rescheduled, skipped, failures)
        }

        for (swept in pendingSources) {
            if (swept.novelId <= 0L || swept.jobId <= 0L) continue
            if (!handledNovels.add(swept.novelId)) continue
            val job = try {
                jobs.get(swept.jobId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                failures += RecoveryFailure(
                    novelId = swept.novelId,
                    jobId = swept.jobId,
                    reason = "读取被中断任务失败：${e.message ?: "未知错误"}",
                )
                null
            }
            if (job == null) {
                skipped += SkippedJob(
                    novelId = swept.novelId,
                    jobId = swept.jobId,
                    reason = "被中断的任务已不存在",
                )
                continue
            }
            rescheduleOne(job, rescheduled, skipped, failures)
        }

        return RecoveryReport(
            rescheduled = rescheduled,
            skipped = skipped,
            failures = failures,
            at = clock(),
        )
    }

    /** 手动恢复单个任务时使用；语义与启动扫描一致。 */
    private suspend fun rescheduleJobs(candidates: List<JobEntity>): RecoveryReport {
        val rescheduled = mutableListOf<RescheduledJob>()
        val skipped = mutableListOf<SkippedJob>()
        val failures = mutableListOf<RecoveryFailure>()
        for (candidate in candidates) {
            rescheduleOne(candidate, rescheduled, skipped, failures)
        }
        return RecoveryReport(
            rescheduled = rescheduled,
            skipped = skipped,
            failures = failures,
            at = clock(),
        )
    }

    /**
     * 处理一个候选。sweep 后仍为 running 的行视为竞态/并发，不去抢；终态行不复活。
     * 先查 active work 再解析章节，避免为了一个不该入队的候选制造新 job 行。
     */
    private suspend fun rescheduleOne(
        job: JobEntity,
        rescheduled: MutableList<RescheduledJob>,
        skipped: MutableList<SkippedJob>,
        failures: MutableList<RecoveryFailure>,
    ) {
        val novelId = job.novelId
        if (novelId <= 0L) {
            skipped += SkippedJob(novelId = novelId, jobId = job.id, reason = "项目 id 非法")
            return
        }
        if (job.status != JobStatus.QUEUED && job.status != JobStatus.PENDING) {
            skipped += SkippedJob(
                novelId = novelId,
                jobId = job.id,
                reason = "任务状态不可恢复：${job.status}",
            )
            return
        }

        val workName = GenerationNotifications.workName(novelId)
        val active = try {
            isWorkActive(workName)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // 查询失败按「仍有 work」处理：宁可不重排，也不能制造第二个 Worker。
            true
        }
        if (active) {
            skipped += SkippedJob(
                novelId = novelId,
                jobId = job.id,
                reason = "已有活跃任务（$workName）",
            )
            return
        }

        val chapterIds = parseChapterIds(job.requestedIdsJson)
        if (chapterIds == null) {
            skipped += SkippedJob(
                novelId = novelId,
                jobId = job.id,
                reason = "章节范围损坏，无法重排",
            )
            return
        }
        if (chapterIds.isEmpty()) {
            skipped += SkippedJob(
                novelId = novelId,
                jobId = job.id,
                reason = "章节范围为空，需调用方补齐未完成章节",
            )
            return
        }
        if (chapterIds.any { it <= 0L }) {
            skipped += SkippedJob(
                novelId = novelId,
                jobId = job.id,
                reason = "章节范围包含非法 id",
            )
            return
        }
        if (chapterIds.distinct().size != chapterIds.size) {
            skipped += SkippedJob(
                novelId = novelId,
                jobId = job.id,
                reason = "章节范围包含重复 id",
            )
            return
        }

        val mode = GenerationMode.fromWire(job.mode)
        if (mode == null) {
            skipped += SkippedJob(
                novelId = novelId,
                jobId = job.id,
                reason = "未知生成模式：${job.mode}",
            )
            return
        }

        val target = if (job.status == JobStatus.QUEUED) {
            job
        } else {
            try {
                // pending 不是可调度状态：新建 queued 行，保留旧行作为被中断历史。
                jobs.start(
                    novelId = novelId,
                    mode = mode.wire,
                    requestedIds = chapterIds,
                    stats = initialStats(chapterIds.size),
                    now = clock(),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (already: JobAlreadyActiveException) {
                skipped += SkippedJob(
                    novelId = novelId,
                    jobId = already.activeJob.id,
                    reason = "已有活跃任务",
                )
                return
            } catch (e: Exception) {
                failures += RecoveryFailure(
                    novelId = novelId,
                    jobId = job.id,
                    reason = "创建恢复任务失败：${e.message ?: "未知错误"}",
                )
                return
            }
        }

        val inputData = GenerationWorker.inputData(
            novelId = novelId,
            jobId = target.id,
            chapterIds = chapterIds.toLongArray(),
            mode = mode,
            // JobEntity 不持久化 force；恢复默认 false，让 P2 缓存尽量复用已完成结果。
            force = false,
        )
        val enqueued = try {
            queue.enqueue(workName, inputData)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
        if (!enqueued) {
            // 入队失败时保留 queued 行：下一次恢复会复用同一行重试，不会每轮新建。
            failures += RecoveryFailure(
                novelId = novelId,
                jobId = target.id,
                reason = "任务入队失败（$workName）",
            )
            return
        }
        rescheduled += RescheduledJob(
            sourceJobId = job.id,
            jobId = target.id,
            novelId = novelId,
            workName = workName,
        )
    }

    /** 容错解析 `requestedIdsJson`；脏 JSON/null/空串返回 null，空数组返回空列表。 */
    private fun parseChapterIds(raw: String?): List<Long>? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return try {
            json.decodeFromString(ListSerializer(Long.serializer()), text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun initialStats(chapterCount: Int): Map<String, Long> = mapOf(
        GenerationStats.KEY_CHAPTERS_TOTAL to chapterCount.toLong(),
        GenerationStats.KEY_CHAPTERS_DONE to 0L,
        GenerationStats.KEY_SEG_TOTAL to 0L,
        GenerationStats.KEY_SEG_DONE to 0L,
    )

    companion object {
        /** [history] 默认条数。 */
        const val DEFAULT_HISTORY_LIMIT = 20
    }
}
