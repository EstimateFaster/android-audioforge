package com.estimatefaster.audioforge.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface JobDao {

    @Query("SELECT * FROM jobs WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): JobEntity?

    @Insert
    suspend fun insert(job: JobEntity): Long

    @Query(
        """
        UPDATE jobs
        SET status = :status, finishedAt = :finishedAt, lastError = :lastError
        WHERE id = :id
        """,
    )
    suspend fun updateStatus(
        id: Long,
        status: String,
        finishedAt: Long?,
        lastError: String?,
    )

    @Query(
        """
        UPDATE jobs
        SET currentChapterId = :chapterId, currentTitle = :chapterTitle, statsJson = :statsJson
        WHERE id = :id
        """,
    )
    suspend fun updateProgress(
        id: Long,
        chapterId: Long?,
        chapterTitle: String,
        statsJson: String,
    )

    @Query("UPDATE jobs SET stopRequested = :stopRequested WHERE id = :id")
    suspend fun updateStopRequested(id: Long, stopRequested: Boolean)

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

    /** 进程死亡/重启后把残留 running 复位为 pending（§13）。 */
    @Query("UPDATE jobs SET status = 'pending', finishedAt = NULL WHERE status = 'running'")
    suspend fun sweepRunning(): Int

    /** Worker 退出时只清理本项目，避免误伤别的书。 */
    @Query(
        """
        UPDATE jobs
        SET status = 'pending', finishedAt = NULL
        WHERE status = 'running' AND novelId = :novelId
        """,
    )
    suspend fun sweepRunning(novelId: Long): Int
}
