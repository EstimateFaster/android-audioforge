package com.estimatefaster.audioforge.data.repository

import androidx.room.withTransaction
import com.estimatefaster.audioforge.data.db.AppDatabase
import com.estimatefaster.audioforge.data.db.JobEntity
import com.estimatefaster.audioforge.data.db.JobStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** 同一本书已有活跃任务（queued/running）时，[JobRepository.start] 抛这个异常。 */
class JobAlreadyActiveException(val activeJob: JobEntity) : IllegalStateException(
    "项目 ${activeJob.novelId} 已有任务 #${activeJob.id}（${activeJob.status}），不能重复启动",
)

/**
 * 任务批次仓库（P2-02 / P3-01）。
 *
 * 状态机（§11.1）：
 * ```
 * queued ──► running ──► done / error
 *    │           │
 *    └───────────┴────► cancelled
 * ```
 *
 * 规则：
 * - 所有跃迁都走 DAO 的条件更新，**非法跃迁返回 false 且不写库**（done/error/cancelled
 *   不会被 running 覆盖，重复 markRunning 也不会重复生效）；
 * - `markDone/markError` 会先读出旧 `statsJson` 做浅合并（保留调用方没提到的 key），
 *   再和终态一起原子写入；
 * - 同一本书最多一个活跃批次：`start` 在事务里检查，已有任务时抛
 *   [JobAlreadyActiveException]；
 * - `sweepRunning` 把上个进程残留的 running 复位成 pending（§11.2 / P3-05）；
 * - `stopRequested` 由 [requestStop] 置位、[isStopRequested] 读取、[clearStopRequested]
 *   或终态自动复位。
 *
 * `statsJson` 结构见 §11.3（chaptersDone/chaptersTotal/segDone/segTotal/ttsOk/
 * ttsFailed/retries/startedAt）；仓库层按 `Map<String, Long>` 做浅合并，不限定字段，
 * 未知 key 原样保留。
 */
class JobRepository(private val db: AppDatabase) {

    private val jobDao = db.jobDao()

    private val json = Json { ignoreUnknownKeys = true }

    fun observeActive(novelId: Long): Flow<JobEntity?> = jobDao.observeActive(novelId)

    suspend fun get(id: Long): JobEntity? = jobDao.get(id)

    /**
     * 低层插入，仅供测试 / 历史数据导入等需要指定 status 的场景；
     * 正常批次请走 [start]（会校验单活跃约束并写入 requestedIds/stats）。
     */
    suspend fun insert(job: JobEntity): Long = jobDao.insert(job)

    /** 当前项目的活跃批次；调度层可用于判断是否需要先停止/拒绝。 */
    suspend fun activeJob(novelId: Long): JobEntity? = jobDao.activeJob(novelId)

    suspend fun countActive(novelId: Long): Int = jobDao.countActive(novelId)

    suspend fun lastFinished(novelId: Long): JobEntity? = jobDao.lastFinished(novelId)

    /**
     * 新建一个排队批次（queued）。
     *
     * 单活跃约束在事务内检查；已有 queued/running 时抛 [JobAlreadyActiveException]，
     * 由 BatchRunner 决定先优雅停止还是提示用户（§11.1）。
     *
     * @param requestedIds 本次要处理的 chapterId；为空表示按 mode 由 Worker 自行决定范围。
     * @param stats 初始统计（常见的是 `chaptersTotal`），会与 `{}` 合并后写入。
     * @param now `startedAt` 时间戳，测试可注入。
     */
    suspend fun start(
        novelId: Long,
        mode: String,
        requestedIds: List<Long> = emptyList(),
        stats: Map<String, Long> = emptyMap(),
        now: Long = System.currentTimeMillis(),
    ): JobEntity = db.withTransaction {
        require(novelId > 0) { "novelId 必须为正数：$novelId" }
        require(mode.isNotBlank()) { "mode 不能为空" }
        require(now > 0) { "startedAt 必须为正数：$now" }
        require(requestedIds.all { it > 0 }) { "requestedIds 只能包含正数：$requestedIds" }
        requireValidStats(stats)

        val active = jobDao.activeJob(novelId)
        if (active != null) throw JobAlreadyActiveException(active)

        val entity = JobEntity(
            novelId = novelId,
            mode = mode.trim(),
            status = JobStatus.QUEUED,
            requestedIdsJson = json.encodeToString(
                ListSerializer(Long.serializer()),
                requestedIds.distinct(),
            ),
            startedAt = now,
            statsJson = mergeStatsJson("{}", stats),
        )
        val id = jobDao.insert(entity)
        entity.copy(id = id)
    }

    /**
     * queued → running。
     *
     * @return false 表示任务不存在或不是 queued（含重复调用、终态回退），此时库内状态不变。
     */
    suspend fun markRunning(id: Long): Boolean = jobDao.markRunning(id) > 0

    /**
     * running → done，并把 [stats] 合并进旧 `statsJson`。
     *
     * @return false 表示任务不存在或不是 running（例如 queued 直接标完成、done 重复标完成）。
     */
    suspend fun markDone(
        id: Long,
        stats: Map<String, Long> = emptyMap(),
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        require(now > 0) { "finishedAt 必须为正数：$now" }
        requireValidStats(stats)
        return db.withTransaction {
            val job = jobDao.get(id) ?: return@withTransaction false
            if (job.status != JobStatus.RUNNING) return@withTransaction false
            jobDao.markDone(id, now, mergeStatsJson(job.statsJson, stats)) > 0
        }
    }

    /**
     * running → error，并把错误信息与 [stats] 一起落库。
     *
     * @return false 表示任务不存在或不是 running。
     */
    suspend fun markError(
        id: Long,
        error: String,
        stats: Map<String, Long> = emptyMap(),
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        require(error.isNotBlank()) { "error 不能为空" }
        require(now > 0) { "finishedAt 必须为正数：$now" }
        requireValidStats(stats)
        return db.withTransaction {
            val job = jobDao.get(id) ?: return@withTransaction false
            if (job.status != JobStatus.RUNNING) return@withTransaction false
            jobDao.markError(id, now, error.trim(), mergeStatsJson(job.statsJson, stats)) > 0
        }
    }

    /**
     * queued/running → cancelled。
     *
     * @return false 表示任务不存在或已是终态。
     */
    suspend fun markCancelled(
        id: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        require(now > 0) { "finishedAt 必须为正数：$now" }
        return jobDao.markCancelled(id, now) > 0
    }

    /**
     * 请求停止：置 `stopRequested=true`（Worker 轮询/`isStopped` 配合）。
     *
     * @return false 表示任务不存在或已结束（终态不需要停止）。
     */
    suspend fun requestStop(id: Long): Boolean = jobDao.requestStop(id) > 0

    /** 读取停止标记；任务不存在按未请求处理。 */
    suspend fun isStopRequested(id: Long): Boolean = jobDao.countStopRequested(id) > 0

    /** 复位停止标记（例如 Worker 在安全点自行收尾后）。 */
    suspend fun clearStopRequested(id: Long): Boolean = jobDao.resetStopRequested(id) > 0

    /**
     * 更新当前章节 + 合并 stats；只对活跃批次生效。
     *
     * @return false 表示任务不存在或已结束，避免终态被迟到的进度覆盖。
     */
    suspend fun updateProgress(
        id: Long,
        chapterId: Long?,
        chapterTitle: String,
        stats: Map<String, Long> = emptyMap(),
    ): Boolean {
        requireValidStats(stats)
        return db.withTransaction {
            val job = jobDao.get(id) ?: return@withTransaction false
            if (!JobStatus.isActive(job.status)) return@withTransaction false
            val merged = mergeStatsJson(job.statsJson, stats)
            jobDao.updateProgress(id, chapterId, chapterTitle, merged) > 0
        }
    }

    /**
     * 只合并 stats；只对活跃批次生效（终态统计应通过 markDone/markError 一次写入）。
     *
     * @return false 表示任务不存在或已结束。
     */
    suspend fun mergeStats(id: Long, stats: Map<String, Long>): Boolean {
        requireValidStats(stats)
        return db.withTransaction {
            val job = jobDao.get(id) ?: return@withTransaction false
            if (!JobStatus.isActive(job.status)) return@withTransaction false
            jobDao.updateStatsJson(id, mergeStatsJson(job.statsJson, stats)) > 0
        }
    }

    /**
     * 全书复位：App 启动时调用，把上个进程残留的 running 改成 pending。
     *
     * @return 被复位的行数
     */
    suspend fun sweepRunning(): Int = jobDao.sweepRunning()

    /** 只复位指定项目，避免误伤其他正在跑的书。 */
    suspend fun sweepRunning(novelId: Long): Int = jobDao.sweepRunning(novelId)

    private fun requireValidStats(stats: Map<String, Long>) {
        require(stats.keys.none { it.isBlank() }) { "statsJson 的 key 不能为空" }
    }

    /**
     * 浅合并 statsJson：旧对象的所有 key 保留，[updates] 覆盖同名 key；
     * 旧值不是合法 JSON 对象时按空对象重建，避免一条脏数据让整个任务无法收尾。
     */
    private fun mergeStatsJson(existingJson: String, updates: Map<String, Long>): String {
        if (updates.isEmpty()) return existingJson
        val existing = try {
            json.parseToJsonElement(existingJson) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: JsonObject(emptyMap())

        val merged = buildJsonObject {
            existing.forEach { (key, value) -> put(key, value) }
            updates.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
        }
        return merged.toString()
    }
}
