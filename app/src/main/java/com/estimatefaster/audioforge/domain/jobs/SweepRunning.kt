package com.estimatefaster.audioforge.domain.jobs

import androidx.work.WorkManager
import com.estimatefaster.audioforge.core.model.PipelineError
import com.estimatefaster.audioforge.data.db.JobDao
import com.estimatefaster.audioforge.data.db.JobEntity
import com.estimatefaster.audioforge.data.db.JobStatus
import com.estimatefaster.audioforge.data.repository.JobRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 被 sweep 复位的一行 `jobs` 快照（明细只用于日志/诊断，不驱动调度）。 */
data class SweptJob(
    val jobId: Long,
    val novelId: Long,
    val mode: String,
    val startedAt: Long,
    val currentChapterId: Long? = null,
    val currentTitle: String = "",
)

/**
 * 一次 sweep 的结果（§11.2 / §15 P3-05）。
 *
 * [count] 是 DAO 本次实际复位的行数（权威值）；[details] 是能定位到的明细快照，
 * 属于 best-effort：`JobDao` 没有「列出全部 running」的查询，只能通过调用方注入的
 * [SweepRunning.knownNovelIds] 逐本定位。因此 `count > details.size` 是正常情况，
 * 调用方不得用 [details] 反推 [count]。
 */
data class SweepReport(
    val count: Int = 0,
    val details: List<SweptJob> = emptyList(),
    val at: Long = 0L,
    /** true 表示 `sweepAfterBatch` 发现 WorkManager 仍有活跃工作，主动跳过。 */
    val skippedBecauseWorkActive: Boolean = false,
)

/** 单条失败明细；[code] 由错误文案归类得到，无法识别时为 [SweepRunning.UNKNOWN_ERROR_CODE]。 */
data class ErrorDetail(
    val chapterTitle: String,
    val message: String,
    val code: String,
)

/**
 * 一本书最近一次批次的错误汇总（§15 P3-05）。
 *
 * 数据源只有 `jobs` 行：`statsJson` 提供计数，`lastError` 提供可读摘要。仓库/DAO
 * 没有持久化单章错误码，因此 [errorCodes] 是从摘要文案**尽力归类**的结果，不能当作
 * 原始机器码使用；需要精确错误码时应继续走 `PipelineError` 的日志/埋点。
 *
 * [hasErrors] 在「有失败章」「有 TTS 失败计数」或「有明细」时为 true；成功/取消批次
 * 返回全零对象，`jobId` 仍可能非空（最近一次批次就是成功的那次）。
 */
data class ErrorSummary(
    val novelId: Long,
    val jobId: Long? = null,
    val status: String? = null,
    val chaptersTotal: Int = 0,
    val chaptersFailed: Int = 0,
    val ttsFailed: Long = 0L,
    val details: List<ErrorDetail> = emptyList(),
    val errorCodes: List<String> = emptyList(),
) {
    val hasErrors: Boolean
        get() = chaptersFailed > 0 || ttsFailed > 0L || details.isNotEmpty()
}

/**
 * 残留 running 清理 / 停止收尾（执行计划 §11.2 / §15 P3-05）。
 *
 * 解决的问题：
 * - 进程被杀后 `jobs` 里会留下永远不变的 `running` 行，App 启动时必须复位成 `pending`
 *   （[sweepOnAppStart]）；旧 Worker 已不在，`pending` 不会再被调度，只作历史；
 * - Worker 退出前 WorkManager 可能还没完全结束同名工作，此时不能把 job 复位，
 *   否则可能和仍在收尾的 Worker 抢写（[sweepAfterBatch] 先查 `isWorkActive`）；
 * - 停止是异步的：`requestStop` + `cancelUniqueWork` 之后要等 Worker 自己落到终态
 *   （[cancelAndAwait] / [awaitTerminal]），不能假定立刻 cancelled；
 * - 失败批次的 `lastError`/`statsJson` 需要给任务页/通知一个可读的汇总（[aggregateErrors]）。
 *
 * ## 注入与测试
 * - [jobs]/[jobDao] 来自 `data/`（本类只读 + 调用既有状态机方法，不新增 DAO 查询）；
 * - [clock] 供报告时间戳与 `finishedAt` 使用，测试注入固定值；
 * - [awaitPollMs] 是 [awaitTerminal] 的轮询间隔（默认 200ms），必须 > 0；
 * - [queue] 供 [cancelAndAwait] 取消唯一工作；为 null 时只做请求停止 + 等待，
 *   生产装配必须传入（测试用 fake）；
 * - [workManager] 供 [sweepAfterBatch] 的默认活跃检查；为 null 时保守地视为「仍有
 *   活跃工作」（宁可不 sweep，也不能把正在收尾的批次复位后让新批次并发跑）；
 * - [knownNovelIds] 只用于填充 [SweepReport.details]。默认空表示只报告条数；
 *   生产可注入 `{ db.novelDao().listNovels().map { it.id } }`。
 *
 * 所有公开方法都透传协程取消（`CancellationException` 原样抛出），不会把外部取消
 * 伪装成超时或普通失败。
 *
 * @param jobs 任务仓库（状态机与 sweep 的唯一入口）
 * @param jobDao 只读查询（`get`/`activeJob`）与全局 sweep
 * @param clock 时间戳来源，默认系统时钟
 * @param awaitPollMs 轮询间隔，默认 [DEFAULT_AWAIT_POLL_MS]
 * @param queue 停止/取消用队列；测试注入 fake，生产注入 `WorkManagerJobQueue`
 * @param workManager 默认活跃检查用；null 时保守跳过 after-batch sweep
 * @param knownNovelIds 明细定位用，默认不定位
 */
class SweepRunning(
    private val jobs: JobRepository,
    private val jobDao: JobDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val awaitPollMs: Long = DEFAULT_AWAIT_POLL_MS,
    private val queue: JobQueue? = null,
    private val workManager: WorkManager? = null,
    private val knownNovelIds: suspend () -> List<Long> = { emptyList() },
) {

    init {
        require(awaitPollMs > 0L) { "awaitPollMs 必须为正数：$awaitPollMs" }
    }

    /**
     * App 启动时调用：把所有残留 `running` 复位成 `pending`，并清掉旧批次的
     * `stopRequested`/`finishedAt`（与 `JobDao.sweepRunning()` 的 SQL 一致）。
     *
     * 只动 `running`：`queued`、`done`、`error`、`cancelled`、`pending` 一律不碰，
     * 因此正在排队的批次仍会被 Worker 正常消费，历史结果也不会被抹掉。
     *
     * 返回的 [SweepReport.count] 来自 DAO 的受影响行数；[SweepReport.details] 仅在
     * 注入的 [knownNovelIds] 能定位到对应书时才有内容。
     */
    suspend fun sweepOnAppStart(): SweepReport {
        val candidates = captureRunningCandidates()
        val count = jobs.sweepRunning()
        val details = confirmedSwept(candidates)
        return SweepReport(count = count, details = details, at = clock())
    }

    /**
     * 批次/Worker 结束后清理**指定书**的残留 `running`。
     *
     * 仅当 [isWorkActive] 对 `generation-<novelId>` 返回 false 时才真正 sweep：
     * WorkManager 里仍有 ENQUEUED/RUNNING/BLOCKED 的同名工作时，job 可能正被另一个
     * Worker 收尾，复位会造成「表里 pending、Worker 又写 running」的竞态。
     *
     * [isWorkActive] 默认委托 [workManager]（`getWorkInfosForUniqueWork`）；未注入
     * WorkManager 时返回 true（保守跳过）。探针抛异常同样按「活跃」处理。
     *
     * @param novelId 只清理这本书，避免误伤其他正在跑的项目
     * @param isWorkActive `workName -> 是否仍有活跃 work`，测试可注入 fake
     */
    suspend fun sweepAfterBatch(
        novelId: Long,
        isWorkActive: (String) -> Boolean = { name -> workManagerSaysActive(name) },
    ): SweepReport {
        if (novelId <= 0L) return SweepReport(at = clock())
        val workName = GenerationNotifications.workName(novelId)
        val active = try {
            isWorkActive(workName)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // 查询失败时按“仍有 work”处理：宁可保留 running，也不能制造并发批次。
            true
        }
        if (active) {
            return SweepReport(at = clock(), skippedBecauseWorkActive = true)
        }

        val candidate = readRunning(novelId)
        val count = jobs.sweepRunning(novelId)
        val details = confirmedSwept(listOfNotNull(candidate))
        return SweepReport(count = count, details = details, at = clock())
    }

    /**
     * 轮询等待 job 进入终态（`done`/`error`/`cancelled`）。
     *
     * @param jobId 目标 job；<=0 或行不存在返回 null
     * @param timeoutMs 超时（默认 [DEFAULT_AWAIT_TIMEOUT_MS]）；<=0 只查一次
     * @return 终态快照；超时、行被删除或仍未终态时返回 null
     *
     * 外部取消原样透传（`CancellationException`），不会被当成超时吞掉。
     */
    suspend fun awaitTerminal(
        jobId: Long,
        timeoutMs: Long = DEFAULT_AWAIT_TIMEOUT_MS,
    ): JobEntity? {
        if (jobId <= 0L) return null
        if (timeoutMs <= 0L) {
            return jobDao.get(jobId)?.takeIf { JobStatus.isTerminal(it.status) }
        }
        return withTimeoutOrNull(timeoutMs) {
            var current = jobDao.get(jobId) ?: return@withTimeoutOrNull null
            while (!JobStatus.isTerminal(current.status)) {
                delay(awaitPollMs)
                current = jobDao.get(jobId) ?: return@withTimeoutOrNull null
            }
            current
        }
    }

    /**
     * 停止一本书的活跃批次并等待收尾：`requestStop` → `queue.cancel(workName)` →
     * [awaitTerminal]。
     *
     * - 仍处于 `queued` 的行：取消唯一工作后直接 `markCancelled`（queued→cancelled 是
     *   合法跃迁），避免 Worker 根本没启动时留下一个永远占名额的 queued 行；
     * - `running` 的行：只能等 Worker 在安全点落终态，超时返回 null 且不强行改状态
     *   （后续可交给 [sweepAfterBatch]）；
     * - 没有活跃行时返回 null，且不会去取消可能无关的 WorkManager 工作；
     * - 请求停止/取消队列失败都按 best-effort 继续等待，只有数据库读取异常才会抛出。
     *
     * @param novelId 项目 id
     * @param workName 唯一任务名；空串时按 `generation-<novelId>` 推导
     * @param timeoutMs 等待终态的超时
     */
    suspend fun cancelAndAwait(
        novelId: Long,
        workName: String,
        timeoutMs: Long = DEFAULT_AWAIT_TIMEOUT_MS,
    ): JobEntity? {
        if (novelId <= 0L) return null
        val active = try {
            jobs.activeJob(novelId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        } ?: return null

        // 先写停止标记：即使 cancel 失败/Worker 已经开始，也能在安全点停下。
        try {
            jobs.requestStop(active.id)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // best-effort：继续 cancel + await，Worker 仍可能自然结束。
        }

        val name = workName.ifBlank { GenerationNotifications.workName(novelId) }
        try {
            queue?.cancel(name)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // 同 requestStop：停止是尽力而为，awaitTerminal 仍会给出确定结果。
        }

        if (active.status == JobStatus.QUEUED) {
            val marked = try {
                jobs.markCancelled(active.id, now = clock())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
            if (marked) return jobs.get(active.id)
        }
        return awaitTerminal(active.id, timeoutMs)
    }

    /**
     * 汇总一本书最近一次批次的失败：失败章数、TTS 失败数、可读明细与归类后的错误码。
     *
     * 数据源是 `lastFinished(novelId)`（done/error/cancelled 中最近结束的一条）：
     * - `statsJson` 的 `chaptersTotal/chaptersFailed/ttsFailed`（脏 JSON/负数按 0 处理）；
     * - `lastError` 的摘要文案（`GenerationExecutor.errorSummary` 形如
     *   `2 章失败：第一章：网络不可用…；第二章：…`）；没有 `stats` 时用摘要里的
     *   “N 章失败”与明细条数兜底。
     *
     * [limit] 只限制 [ErrorSummary.details] 条数（默认 [DEFAULT_ERROR_LIMIT]），
     * [ErrorSummary.errorCodes] 仍覆盖全部可解析明细。没有任何历史批次时返回全零对象。
     */
    suspend fun aggregateErrors(
        novelId: Long,
        limit: Int = DEFAULT_ERROR_LIMIT,
    ): ErrorSummary {
        if (novelId <= 0L) return ErrorSummary(novelId = novelId)
        val job = try {
            jobs.lastFinished(novelId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        } ?: return ErrorSummary(novelId = novelId)

        val stats = parseStats(job.statsJson)
        val parsed = parseErrorDetails(job.lastError)
        val headerCount = parseFailedCount(job.lastError)
        val failed = maxOf(stats.chaptersFailed, headerCount, parsed.size)
        val details = if (limit <= 0) emptyList() else parsed.take(limit)
        return ErrorSummary(
            novelId = novelId,
            jobId = job.id,
            status = job.status,
            chaptersTotal = stats.chaptersTotal,
            chaptersFailed = failed,
            ttsFailed = stats.ttsFailed,
            details = details,
            errorCodes = parsed.map { it.code }.distinct(),
        )
    }

    // ------------------------------------------------------------ 内部

    /**
     * 通过 [knownNovelIds] 定位残留 running。`JobDao` 没有全表 running 查询，因此
     * 明细只能按书扫描；单活跃约束保证每本书至多一条活跃行，`activeJob` 足够用。
     * 任一步读取失败都跳过该书（sweep 本身仍会按 DAO 的全局 SQL 执行）。
     */
    private suspend fun captureRunningCandidates(): List<JobEntity> {
        val ids = try {
            knownNovelIds()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return emptyList()
        }
        val seen = mutableSetOf<Long>()
        val result = mutableListOf<JobEntity>()
        for (id in ids) {
            if (id <= 0L || !seen.add(id)) continue
            val active = try {
                jobDao.activeJob(id)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
            if (active != null && active.status == JobStatus.RUNNING) {
                result += active
            }
        }
        return result
    }

    private suspend fun readRunning(novelId: Long): JobEntity? = try {
        jobDao.activeJob(novelId)?.takeIf { it.status == JobStatus.RUNNING }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    /** 只把「sweep 后确实变成 pending」的行放进明细，避免把并发收尾的终态行报错。 */
    private suspend fun confirmedSwept(candidates: List<JobEntity>): List<SweptJob> =
        candidates.mapNotNull { candidate ->
            val after = try {
                jobDao.get(candidate.id)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
            if (after != null && after.status == JobStatus.PENDING) candidate.toSweptJob() else null
        }

    /**
     * [sweepAfterBatch] 的默认活跃检查：查 WorkManager 的唯一工作。
     *
     * 该调用是阻塞的（`ListenableFuture.get`），应只在 IO/Worker 线程使用；
     * [workManager] 未注入或查询失败时返回 true（保守，不 sweep）。
     */
    private fun workManagerSaysActive(workName: String): Boolean {
        val manager = workManager ?: return true
        return try {
            manager.getWorkInfosForUniqueWork(workName).get().any { !it.state.isFinished }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            true
        }
    }

    companion object {
        /** [awaitTerminal] 默认轮询间隔（§15 P3-05）。 */
        const val DEFAULT_AWAIT_POLL_MS = 200L

        /** [awaitTerminal]/[cancelAndAwait] 默认超时：60s。 */
        const val DEFAULT_AWAIT_TIMEOUT_MS = 60_000L

        /** [aggregateErrors] 默认最多返回的明细条数。 */
        const val DEFAULT_ERROR_LIMIT = 5

        /** 无法从文案归类时的错误码。 */
        const val UNKNOWN_ERROR_CODE = "UNKNOWN"
    }
}

// ------------------------------------------------------------ 解析

private data class StatsSnapshot(
    val chaptersTotal: Int = 0,
    val chaptersFailed: Int = 0,
    val ttsFailed: Long = 0L,
)

/** 容错解析 `statsJson`：非法 JSON、缺字段、负数都退化为 0，绝不抛出。 */
private fun parseStats(raw: String?): StatsSnapshot {
    if (raw.isNullOrBlank()) return StatsSnapshot()
    val obj = try {
        Json.parseToJsonElement(raw) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } ?: return StatsSnapshot()
    return StatsSnapshot(
        chaptersTotal = obj.longOrZero(GenerationStats.KEY_CHAPTERS_TOTAL).toIntSafe(),
        chaptersFailed = obj.longOrZero(GenerationStats.KEY_CHAPTERS_FAILED).toIntSafe(),
        ttsFailed = obj.longOrZero(GenerationStats.KEY_TTS_FAILED),
    )
}

private fun JsonObject.longOrZero(key: String): Long =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

private fun Long.toIntSafe(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

private val FAILED_HEADER_REGEX = Regex("""^(?:全部\s*)?(\d+)\s*章失败[：:]?\s*""")

/** 从 `lastError` 的 “N 章失败” 头提取数量；没有则 0。 */
private fun parseFailedCount(raw: String?): Int {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return 0
    return FAILED_HEADER_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

/**
 * 解析 `GenerationExecutor.errorSummary` 的摘要：
 * `2 章失败：第一章：网络不可用…；第二章：额度不足…`。
 *
 * 兼容 ASCII/中文冒号与分号、换行，以及没有章节前缀的单条错误；脏文案不抛异常，
 * 最坏情况退化成一条无标题明细。
 */
private fun parseErrorDetails(raw: String?): List<ErrorDetail> {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return emptyList()
    val body = FAILED_HEADER_REGEX.replaceFirst(text, "").trim()
    if (body.isEmpty()) return emptyList()
    return body.split('；', ';', '\n').mapNotNull { rawItem ->
        val item = rawItem.trim()
        if (item.isEmpty()) return@mapNotNull null
        val colon = item.indexOfFirst { it == '：' || it == ':' }
        if (colon <= 0 || colon == item.lastIndex) {
            val message = item.trim('：', ':').trim()
            if (message.isEmpty()) {
                null
            } else {
                ErrorDetail(
                    chapterTitle = "",
                    message = message,
                    code = classifyErrorCode(message),
                )
            }
        } else {
            val title = item.substring(0, colon).trim()
            val message = item.substring(colon + 1).trim()
            if (message.isEmpty()) {
                ErrorDetail(
                    chapterTitle = title,
                    message = item,
                    code = classifyErrorCode(item),
                )
            } else {
                ErrorDetail(
                    chapterTitle = title,
                    message = message,
                    code = classifyErrorCode(message),
                )
            }
        }
    }
}

/** `jobs` 表只存可读文案，不存单章错误码；这里按文案里出现的稳定错误码/中文文案归类。 */
private val EXPLICIT_ERROR_CODES = listOf(
    PipelineError.AUTH_LLM,
    PipelineError.AUTH_FISH,
    PipelineError.QUOTA,
    PipelineError.MODEL_NOT_FOUND,
    PipelineError.NETWORK,
    PipelineError.TRUNCATED,
    PipelineError.BAD_JSON,
    PipelineError.TTS_FAILED,
    PipelineError.STORAGE,
    PipelineError.FFMPEG,
    PipelineError.NO_SEGMENTS,
    "CHAPTER_TOO_LONG",
)

/**
 * 文案 → 错误码的兜底表，顺序即优先级。
 *
 * 这些中文片段来自 `PipelineError.userMessage` / `AnalyzeChapterUseCase`；
 * 调整上游文案时必须同步这里，否则只会退化成 [SweepRunning.UNKNOWN_ERROR_CODE]，
 * 不会影响任务状态机。
 */
private val MESSAGE_ERROR_CODES = listOf(
    PipelineError.AUTH_LLM to "LLM Key 无效",
    PipelineError.AUTH_FISH to "Fish Key 无效",
    PipelineError.QUOTA to "额度不足",
    PipelineError.QUOTA to "限流",
    PipelineError.MODEL_NOT_FOUND to "模型不存在",
    PipelineError.NETWORK to "网络不可用",
    PipelineError.NETWORK to "断网",
    PipelineError.TRUNCATED to "输出被截断",
    PipelineError.TRUNCATED to "本章过长",
    PipelineError.BAD_JSON to "不是合法 JSON",
    PipelineError.TTS_FAILED to "合成失败",
    PipelineError.STORAGE to "存储空间不足",
    PipelineError.FFMPEG to "拼接失败",
    PipelineError.NO_SEGMENTS to "没有可拼接的片段",
    "CHAPTER_TOO_LONG" to "章节过长",
)

private fun classifyErrorCode(message: String): String {
    EXPLICIT_ERROR_CODES.firstOrNull { message.contains(it) }?.let { return it }
    return MESSAGE_ERROR_CODES.firstOrNull { message.contains(it.second) }?.first
        ?: SweepRunning.UNKNOWN_ERROR_CODE
}

private fun JobEntity.toSweptJob(): SweptJob = SweptJob(
    jobId = id,
    novelId = novelId,
    mode = mode,
    startedAt = startedAt,
    currentChapterId = currentChapterId,
    currentTitle = currentTitle,
)
