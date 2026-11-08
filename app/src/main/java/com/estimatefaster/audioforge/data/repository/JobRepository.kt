package com.estimatefaster.audioforge.data.repository

import com.estimatefaster.audioforge.data.db.AppDatabase
import com.estimatefaster.audioforge.data.db.JobEntity
import kotlinx.coroutines.flow.Flow

/**
 * 任务批次仓库（P2-02 / P3-01）。
 *
 * 只做状态读写；具体状态机（queued → running → done/error/cancelled）由
 * Worker/协调器负责。`sweepRunning` 用于进程死亡后把残留 running 复位。
 */
class JobRepository(private val db: AppDatabase) {

    private val jobDao = db.jobDao()

    fun observeActive(novelId: Long): Flow<JobEntity?> = jobDao.observeActive(novelId)

    suspend fun get(id: Long): JobEntity? = jobDao.get(id)

    suspend fun insert(job: JobEntity): Long = jobDao.insert(job)

    suspend fun updateStatus(
        id: Long,
        status: String,
        finishedAt: Long? = null,
        lastError: String? = null,
    ) {
        require(status.isNotBlank()) { "job status 不能为空" }
        jobDao.updateStatus(id, status, finishedAt, lastError)
    }

    suspend fun lastFinished(novelId: Long): JobEntity? = jobDao.lastFinished(novelId)

    /** 全书复位：进程重启时用。 */
    suspend fun sweepRunning(): Int = jobDao.sweepRunning()

    /** 只复位指定项目，避免误伤其他正在跑的书。 */
    suspend fun sweepRunning(novelId: Long): Int = jobDao.sweepRunning(novelId)
}
