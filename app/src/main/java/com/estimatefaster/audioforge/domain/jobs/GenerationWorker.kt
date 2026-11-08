package com.estimatefaster.audioforge.domain.jobs

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.estimatefaster.audioforge.AudioForgeApp
import com.estimatefaster.audioforge.data.db.JobEntity
import com.estimatefaster.audioforge.data.db.JobStatus
import com.estimatefaster.audioforge.data.repository.JobRepository
import com.estimatefaster.audioforge.work.NotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 生产依赖装配槽（P3-02）。
 *
 * `GenerationExecutor` 需要真实的 `AnalyzeChapterUseCase` / `RenderChapterUseCase`
 * 与语音池、TTS、FFmpeg 等对象；这些由 P3-04 的 `BatchRunner`/AppContainer 负责装配：
 *
 * ```kotlin
 * GenerationGraph.executorFactory = { context ->
 *     val container = (context.applicationContext as AudioForgeApp).container
 *     GenerationExecutor(
 *         chapters = container.chapterRepository,
 *         jobs = container.jobRepository,
 *         analyzeChapter = { novelId, chapterId, force ->
 *             container.analyzeChapterUseCase.analyze(novelId, chapterId, force)
 *         },
 *         renderChapter = { novelId, chapterId, shouldStop ->
 *             container.renderChapterUseCase.render(novelId, chapterId, shouldStop)
 *         },
 *     )
 * }
 * ```
 *
 * 未装配时 [GenerationWorker] 只写日志并返回 `Result.failure()`，不会静默吞任务。
 */
object GenerationGraph {
    @Volatile
    var executorFactory: ((Context) -> GenerationExecutor)? = null
}

/**
 * 生成批次 Worker（执行计划 §11.1 / §11.2 / §15 P3-02）。
 *
 * 输入（`Data`）：
 * - [KEY_NOVEL_ID]：项目 id；
 * - [KEY_CHAPTER_IDS]：`LongArray`，本次按顺序处理的章节；
 * - [KEY_MODE]：`full` / `parse` / `render`；
 * - [KEY_FORCE]：解析时是否忽略 `parse_status=done`；
 * - [KEY_JOB_ID]：`jobs` 行 id；P3-04 必须传。缺失时回退到 `activeJob(novelId)`，
 *   仅为兼容手工/调试入队。
 *
 * 状态机：
 * ```
 * 读输入 → 找到 job → markRunning → setForeground(0)
 *   ├─ Success   → markDone
 *   ├─ Partial   → markError（有失败章）+ Result.success()（不整批重试）
 *   ├─ Failed    → 全失败且可重试且 runAttemptCount < MAX_RETRIES
 *   │              → 保留 running + mergeStats + Result.retry()
 *   │              → 否则 markError + Result.failure()
 *   └─ Stopped   → markCancelled + Result.success()
 * finally → 仅当自己的 job 异常地仍停在 running 时 sweepRunning(novelId)（重试分支除外，见下）
 * ```
 *
 * 为什么重试分支不清 job：`Result.retry()` 会再次进入同一 `doWork`，下一次
 * `markRunning` 要求 job 仍是 running；若此时 sweep 成 pending，重试会直接失败。
 * 因此只有非重试收尾才执行 `sweepRunning`；进程死亡后的清理由 App 启动的
 * `sweepRunning`（P3-05）负责。
 *
 * 停止语义：`isStopped`（WorkManager 取消）与 `jobs.stopRequested` 都会传给
 * `GenerationExecutor`；停止时已完成章节的 DB 结果保留，job 记为 cancelled。
 *
 * 通知：本任务只用 [NotificationHelper.generationForegroundInfo] 的“生成中 + 进度”；
 * 停止 action、完成通知与 POST_NOTIFICATIONS 提示由 P3-03 完善。
 */
class GenerationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val input = readInput()
        if (input == null) {
            Log.e(TAG, "invalid input: novelId=${inputData.getLong(KEY_NOVEL_ID, 0L)}")
            return Result.failure()
        }

        val factory = GenerationGraph.executorFactory
        if (factory == null) {
            Log.e(TAG, "GenerationGraph.executorFactory is not wired; job=${input.jobId}")
            return Result.failure()
        }

        // 与 ProbeWorker 一样：Worker 自己保证渠道存在，不依赖 App 启动路径。
        NotificationHelper.ensureChannels(applicationContext)

        val jobRepository = jobRepository()
        if (jobRepository == null) {
            Log.e(TAG, "AudioForgeApp container is unavailable")
            return Result.failure()
        }

        val job = resolveJob(jobRepository, input)
        if (job == null) {
            Log.e(TAG, "no runnable job for novel=${input.novelId} job=${input.jobId}")
            return Result.failure()
        }
        if (job.novelId != input.novelId) {
            Log.e(TAG, "job ${job.id} belongs to novel ${job.novelId}, not ${input.novelId}")
            return Result.failure()
        }

        // queued → running；retry 时 job 已经是 running，markRunning 返回 false 是正常情况。
        jobRepository.markRunning(job.id)
        val running = jobRepository.get(job.id)
        if (running == null || running.status != JobStatus.RUNNING) {
            Log.e(TAG, "job ${job.id} is not running: ${running?.status}")
            return Result.failure()
        }

        var keepRunningForRetry = false
        return try {
            announceProgress(0)
            val outcome = factory(applicationContext).run(
                input = input.copy(jobId = running.id),
                onProgress = { progress ->
                    try {
                        setProgress(workDataOf(KEY_PROGRESS to progress.percent))
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        // 进度只用于展示；WorkManager 已收尾时写失败不应中断批次。
                    }
                    announceProgress(progress.percent)
                },
                shouldStop = { isStopped },
            )
            val completion = finish(jobRepository, running.id, outcome)
            keepRunningForRetry = completion.keepRunningForRetry
            completion.result
        } catch (cancellation: CancellationException) {
            // WorkManager cancelUniqueWork / 用户停止：尽量把 job 收尾成 cancelled。
            withContext(NonCancellable) {
                runCatching { jobRepository.markCancelled(running.id) }
            }
            throw cancellation
        } catch (e: Exception) {
            Log.e(TAG, "generation crashed; job=${running.id}", e)
            withContext(NonCancellable) {
                runCatching {
                    jobRepository.markError(
                        id = running.id,
                        error = e.message?.takeIf { it.isNotBlank() } ?: "生成失败",
                    )
                }
            }
            Result.failure()
        } finally {
            if (!keepRunningForRetry) {
                withContext(NonCancellable) {
                    // 只清「自己的 job 仍停在 running」的异常退出，避免把随后新启动的批次扫成 pending。
                    val status = runCatching { jobRepository.get(running.id)?.status }.getOrNull()
                    if (status == JobStatus.RUNNING) {
                        runCatching { jobRepository.sweepRunning(input.novelId) }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ 收尾

    private suspend fun finish(
        jobs: JobRepository,
        jobId: Long,
        outcome: BatchOutcome,
    ): WorkerCompletion {
        val stats = outcome.stats.toJobStats()
        return when (outcome) {
            is BatchOutcome.Success -> {
                jobs.markDone(jobId, stats)
                WorkerCompletion(Result.success(), keepRunningForRetry = false)
            }

            is BatchOutcome.Partial -> {
                // 有章节失败：job 记 error 并带可读摘要；但不整批重试（已完成章节已保留）。
                jobs.markError(
                    id = jobId,
                    error = outcome.errorSummary.ifBlank { "部分章节失败" },
                    stats = stats,
                )
                WorkerCompletion(Result.success(), keepRunningForRetry = false)
            }

            is BatchOutcome.Stopped -> {
                jobs.markCancelled(jobId)
                Log.i(TAG, "generation stopped at job=$jobId (${outcome.stats.chaptersDone} 章完成)")
                WorkerCompletion(Result.success(), keepRunningForRetry = false)
            }

            is BatchOutcome.Failed -> {
                if (outcome.retryable && runAttemptCount < MAX_RETRIES) {
                    // 保留 running，等待 WorkManager 下一次尝试；已完成的章节会被跳过。
                    val stillActive = jobs.mergeStats(jobId, stats)
                    if (!stillActive) {
                        // 用户已取消/别的路径已收尾：不要再 retry，避免无意义重跑。
                        Log.w(TAG, "retry skipped: job=$jobId is no longer active")
                        return WorkerCompletion(
                            Result.failure(),
                            keepRunningForRetry = false,
                        )
                    }
                    Log.w(
                        TAG,
                        "generation failed (retryable), attempt=$runAttemptCount job=$jobId",
                    )
                    WorkerCompletion(Result.retry(), keepRunningForRetry = true)
                } else {
                    jobs.markError(
                        id = jobId,
                        error = outcome.errorSummary.ifBlank { "生成失败" },
                        stats = stats,
                    )
                    WorkerCompletion(Result.failure(), keepRunningForRetry = false)
                }
            }
        }
    }

    // ------------------------------------------------------------ 输入

    private fun readInput(): GenerationInput? {
        val novelId = inputData.getLong(KEY_NOVEL_ID, 0L)
        val jobId = inputData.getLong(KEY_JOB_ID, 0L)
        val chapterIds = inputData.getLongArray(KEY_CHAPTER_IDS)?.toList().orEmpty()
        val mode = GenerationMode.fromWire(inputData.getString(KEY_MODE))
        val force = inputData.getBoolean(KEY_FORCE, false)

        if (novelId <= 0L || jobId < 0L || chapterIds.isEmpty() || mode == null) {
            Log.e(
                TAG,
                "invalid input: novelId=$novelId jobId=$jobId mode=${inputData.getString(KEY_MODE)} " +
                    "chapters=${chapterIds.size}",
            )
            return null
        }
        return GenerationInput(
            novelId = novelId,
            chapterIds = chapterIds,
            mode = mode,
            force = force,
            jobId = jobId,
        )
    }

    /** 优先用输入里的 jobId；P3-04 之前的手工入队回退到当前活跃任务。 */
    private suspend fun resolveJob(jobs: JobRepository, input: GenerationInput): JobEntity? {
        val found = if (input.jobId > 0L) {
            jobs.get(input.jobId)
        } else {
            jobs.activeJob(input.novelId)
        }
        return found?.takeUnless { JobStatus.isTerminal(it.status) }
    }

    private fun jobRepository(): JobRepository? =
        (applicationContext as? AudioForgeApp)?.container?.jobRepository

    // ------------------------------------------------------------ 通知

    private suspend fun announceProgress(progress: Int) {
        try {
            setForeground(
                NotificationHelper.generationForegroundInfo(applicationContext, progress),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            // 前台服务启动被系统拒绝时任务仍继续；P3-03 会补完整通知/权限提示。
            Log.w(TAG, "setForeground failed: ${e.message}")
        }
    }

    private data class WorkerCompletion(
        val result: Result,
        val keepRunningForRetry: Boolean,
    )

    companion object {
        const val TAG = "AudioForgeGenerationWorker"

        const val KEY_NOVEL_ID = "novelId"
        const val KEY_CHAPTER_IDS = "chapterIds"
        const val KEY_MODE = "mode"
        const val KEY_FORCE = "force"
        const val KEY_JOB_ID = "jobId"
        const val KEY_PROGRESS = "progress"

        /** 唯一任务名前缀：`generation-<novelId>`（P3-04 BatchRunner 使用）。 */
        const val UNIQUE_WORK_PREFIX = "generation-"

        /** 首次执行 + 最多 3 次 `Result.retry()`（`runAttemptCount` 从 0 开始）。 */
        const val MAX_RETRIES = 3

        /** P3-04/BatchRunner 构造 Worker 输入；集中 key 防止拼错。 */
        fun inputData(
            novelId: Long,
            jobId: Long,
            chapterIds: LongArray,
            mode: GenerationMode,
            force: Boolean,
        ): Data = workDataOf(
            KEY_NOVEL_ID to novelId,
            KEY_JOB_ID to jobId,
            KEY_CHAPTER_IDS to chapterIds,
            KEY_MODE to mode.wire,
            KEY_FORCE to force,
        )
    }
}
