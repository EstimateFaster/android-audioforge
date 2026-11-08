package com.estimatefaster.audioforge.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * jobs 表状态常量（§7.1 / §11.1）。
 *
 * 状态机：
 * ```
 * queued ──► running ──► done / error
 *    │           │
 *    └───────────┴────► cancelled
 * ```
 *
 * [PENDING] 不是可调度的业务状态：只由 [JobDao.sweepRunning] 在进程死亡/重启后写入，
 * 表示“上个进程留下的 running 已被中断、等待下次重新排队”。它既不是活跃态
 * （[ACTIVE]）也不是终态（[TERMINAL]），因此不会出现在 `observeActive` /
 * `lastFinished` 里。
 *
 * 注意：Room 注解参数必须是编译期常量，DAO 的 SQL 里状态值只能写字面量；
 * 改这里的常量时必须同步改 [JobDao] 里的查询。
 */
object JobStatus {
    const val PENDING = "pending"
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val DONE = "done"
    const val ERROR = "error"
    const val CANCELLED = "cancelled"

    val ACTIVE: Set<String> = setOf(QUEUED, RUNNING)
    val TERMINAL: Set<String> = setOf(DONE, ERROR, CANCELLED)

    fun isActive(status: String): Boolean = status in ACTIVE

    fun isTerminal(status: String): Boolean = status in TERMINAL
}

/**
 * 任务批次 DAO（P3-01）。
 *
 * 所有状态跃迁都用 `WHERE id = :id AND status = <前置状态>` 做条件更新，返回受影响行数：
 * 0 表示任务不存在或当前状态不允许该跃迁，仓库层据此拒绝非法流转（例如 done→running
 * 不会被覆盖）。这样即使调用方没有先读状态，并发下也不会把终态改回去。
 */
@Dao
interface JobDao {

    @Query("SELECT * FROM jobs WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): JobEntity?

    @Insert
    suspend fun insert(job: JobEntity): Long

    /** 当前项目的活跃批次（queued/running）；没有则 null。 */
    @Query(
        """
        SELECT * FROM jobs
        WHERE novelId = :novelId AND status IN ('queued', 'running')
        ORDER BY startedAt DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun activeJob(novelId: Long): JobEntity?

    /** 当前项目的活跃批次数；单活跃约束要求它是 0 或 1。 */
    @Query(
        """
        SELECT COUNT(*) FROM jobs
        WHERE novelId = :novelId AND status IN ('queued', 'running')
        """,
    )
    suspend fun countActive(novelId: Long): Int

    /**
     * queued → running。
     *
     * 不清 `stopRequested`：排队期间用户已经点了停止时，Worker 启动后必须能读到该标记。
     * 返回 0 表示任务不是 queued（含重复 markRunning、done/error/cancelled→running）。
     */
    @Query(
        """
        UPDATE jobs
        SET status = 'running', lastError = NULL
        WHERE id = :id AND status = 'queued'
        """,
    )
    suspend fun markRunning(id: Long): Int

    /** running → done。终态会清掉 stopRequested，statsJson 由仓库层合并后传入。 */
    @Query(
        """
        UPDATE jobs
        SET status = 'done',
            finishedAt = :now,
            lastError = NULL,
            statsJson = :statsJson,
            stopRequested = 0
        WHERE id = :id AND status = 'running'
        """,
    )
    suspend fun markDone(id: Long, now: Long, statsJson: String): Int

    /** running → error。statsJson 由仓库层合并后传入。 */
    @Query(
        """
        UPDATE jobs
        SET status = 'error',
            finishedAt = :now,
            lastError = :error,
            statsJson = :statsJson,
            stopRequested = 0
        WHERE id = :id AND status = 'running'
        """,
    )
    suspend fun markError(id: Long, now: Long, error: String, statsJson: String): Int

    /**
     * queued/running → cancelled。
     *
     * 排队后、Worker 启动前也能取消；终态不可再取消。
     */
    @Query(
        """
        UPDATE jobs
        SET status = 'cancelled',
            finishedAt = :now,
            lastError = NULL,
            stopRequested = 0
        WHERE id = :id AND status IN ('queued', 'running')
        """,
    )
    suspend fun markCancelled(id: Long, now: Long): Int

    /** 设置停止标记；只对 queued/running 生效。返回 0 表示任务不存在或已结束。 */
    @Query(
        """
        UPDATE jobs
        SET stopRequested = 1
        WHERE id = :id AND status IN ('queued', 'running')
        """,
    )
    suspend fun requestStop(id: Long): Int

    /** 复位停止标记；返回 0 表示任务不存在或本来就没有标记。 */
    @Query("UPDATE jobs SET stopRequested = 0 WHERE id = :id AND stopRequested = 1")
    suspend fun resetStopRequested(id: Long): Int

    /** 读取停止标记：1 表示已请求停止，0 表示未请求或任务不存在。 */
    @Query("SELECT COUNT(*) FROM jobs WHERE id = :id AND stopRequested = 1")
    suspend fun countStopRequested(id: Long): Int

    /**
     * 更新当前章节与合并后的 statsJson；只允许活跃批次写入，避免终态被进度覆盖。
     *
     * 返回 0 表示任务不存在或已不在 queued/running。
     */
    @Query(
        """
        UPDATE jobs
        SET currentChapterId = :chapterId,
            currentTitle = :chapterTitle,
            statsJson = :statsJson
        WHERE id = :id AND status IN ('queued', 'running')
        """,
    )
    suspend fun updateProgress(
        id: Long,
        chapterId: Long?,
        chapterTitle: String,
        statsJson: String,
    ): Int

    /** 只更新合并后的 statsJson；同样只允许活跃批次。 */
    @Query(
        """
        UPDATE jobs
        SET statsJson = :statsJson
        WHERE id = :id AND status IN ('queued', 'running')
        """,
    )
    suspend fun updateStatsJson(id: Long, statsJson: String): Int

    /** 当前项目正在排队/执行的任务；没有则发射 null。 */
    @Query(
        """
        SELECT * FROM jobs
        WHERE novelId = :novelId AND status IN ('queued', 'running')
        ORDER BY startedAt DESC, id DESC
        LIMIT 1
        """,
    )
    fun observeActive(novelId: Long): Flow<JobEntity?>

    /** 最近一次已结束的批次，用于重启后显示上次结果。 */
    @Query(
        """
        SELECT * FROM jobs
        WHERE novelId = :novelId AND status IN ('done', 'error', 'cancelled')
        ORDER BY COALESCE(finishedAt, 0) DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun lastFinished(novelId: Long): JobEntity?

    /**
     * 进程死亡/重启后把残留 running 复位为 pending（§11.2 / P3-05）。
     *
     * pending 表示“被中断”，不是终态也不会被 [observeActive] 选中；
     * 同时清掉 stopRequested，避免旧批次的停止标记泄漏到下次启动。
     */
    @Query(
        """
        UPDATE jobs
        SET status = 'pending', finishedAt = NULL, stopRequested = 0
        WHERE status = 'running'
        """,
    )
    suspend fun sweepRunning(): Int

    /** Worker 退出时只清理本项目，避免误伤别的书。 */
    @Query(
        """
        UPDATE jobs
        SET status = 'pending', finishedAt = NULL, stopRequested = 0
        WHERE status = 'running' AND novelId = :novelId
        """,
    )
    suspend fun sweepRunning(novelId: Long): Int
}
