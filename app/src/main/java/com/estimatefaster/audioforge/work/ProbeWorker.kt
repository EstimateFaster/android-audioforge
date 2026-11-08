package com.estimatefaster.audioforge.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.estimatefaster.audioforge.AudioForgeApp
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.data.db.RoomProbeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/** P0-06：跑 5 步、更新前台通知与进度，并在结束时做一次 Room 写入。 */
class ProbeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        NotificationHelper.ensureChannels(applicationContext)
        setForeground(NotificationHelper.probeForegroundInfo(applicationContext, progress = 0))
        Log.i(TAG, "probe worker started")

        return try {
            repeat(STEPS) { step ->
                if (isStopped) {
                    Log.i(TAG, "probe worker stopped at step $step")
                    return Result.failure()
                }
                delay(STEP_DELAY_MS)
                val progress = (step + 1) * 100 / STEPS
                setProgress(workDataOf(KEY_PROGRESS to progress))
                setForeground(NotificationHelper.probeForegroundInfo(applicationContext, progress))
            }

            when (val probeResult = (applicationContext as AudioForgeApp).container.roomProbe.run()) {
                is RoomProbeState.Passed -> {
                    Log.i(TAG, "probe worker succeeded: counter=${probeResult.counter}")
                    NotificationHelper.notifyDone(
                        applicationContext,
                        success = true,
                        text = applicationContext.getString(
                            R.string.notif_done_probe_success,
                            probeResult.counter,
                        ),
                    )
                    Result.success()
                }

                is RoomProbeState.Failed -> {
                    NotificationHelper.notifyDone(
                        applicationContext,
                        success = false,
                        text = probeResult.message,
                    )
                    Result.failure()
                }

                else -> Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "probe worker failed", e)
            NotificationHelper.notifyDone(
                applicationContext,
                success = false,
                text = e.message ?: e::class.java.simpleName,
            )
            Result.failure()
        }
    }

    companion object {
        const val TAG = "AudioForgeProbeWorker"
        const val UNIQUE_NAME = "probe-worker"
        const val KEY_PROGRESS = "progress"
        const val STEPS = 5
        const val STEP_DELAY_MS = 800L

        fun enqueue(context: Context, workManager: WorkManager = WorkManager.getInstance(context)) {
            val request = OneTimeWorkRequestBuilder<ProbeWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .addTag("probe")
                .build()
            workManager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context, workManager: WorkManager = WorkManager.getInstance(context)) {
            workManager.cancelUniqueWork(UNIQUE_NAME)
        }

        fun observe(
            context: Context,
            workManager: WorkManager = WorkManager.getInstance(context),
        ): Flow<List<WorkInfo>> = workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_NAME)
    }
}

/** 首页调试卡用的轻量状态，不把 WorkManager 类型带进 UI 层。 */
enum class ProbeWorkStatus {
    IDLE,
    ENQUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    BLOCKED,
}

data class ProbeWorkSnapshot(
    val status: ProbeWorkStatus = ProbeWorkStatus.IDLE,
    val progress: Int = 0,
)

fun workInfoToSnapshot(info: WorkInfo?): ProbeWorkSnapshot {
    if (info == null) return ProbeWorkSnapshot()
    val status = when (info.state) {
        WorkInfo.State.ENQUEUED -> ProbeWorkStatus.ENQUEUED
        WorkInfo.State.RUNNING -> ProbeWorkStatus.RUNNING
        WorkInfo.State.SUCCEEDED -> ProbeWorkStatus.SUCCEEDED
        WorkInfo.State.FAILED -> ProbeWorkStatus.FAILED
        WorkInfo.State.CANCELLED -> ProbeWorkStatus.CANCELLED
        WorkInfo.State.BLOCKED -> ProbeWorkStatus.BLOCKED
        else -> ProbeWorkStatus.IDLE
    }
    return ProbeWorkSnapshot(
        status = status,
        progress = info.progress.getInt(ProbeWorker.KEY_PROGRESS, 0),
    )
}
