package com.estimatefaster.audioforge.domain.jobs

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * 队列状态（§11.1 / §15 P3-04）。
 *
 * 刻意不复用 `WorkInfo.State`：业务层和测试只关心「排着 / 在跑 / 已结束」，
 * 换掉 WorkManager（或 fake）时不需要改调用方。
 */
enum class QueueState {
    ENQUEUED,
    RUNNING,
    BLOCKED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNKNOWN;

    /** 仍占用唯一任务名、会阻止 [ExistingWorkPolicy.KEEP] 重新入队。 */
    val isActive: Boolean
        get() = this == ENQUEUED || this == RUNNING || this == BLOCKED
}

/** 某本书唯一任务名下的队列快照；[progress] 是 Worker 写入的 0..100 百分比。 */
data class QueueSnapshot(
    val workName: String,
    val state: QueueState,
    val progress: Int = 0,
) {
    val isActive: Boolean get() = state.isActive
}

/**
 * 生成任务队列抽象（执行计划 §11.1 / §15 P3-04）。
 *
 * 生产实现是 [WorkManagerJobQueue]；测试用内存 fake 记录入队/取消，不启动真实
 * WorkManager。名称必须是 [GenerationNotifications.workName]（`generation-<novelId>`），
 * 否则通知里的「停止」action 会取消错任务。
 *
 * 约定：
 * - [enqueue] 返回 false 表示 WorkManager 拒绝/抛异常，调用方（[BatchRunner]）负责把
 *   job 标成 error 并释放单活跃名额；返回 true 只代表请求被接受，不代表 Worker 已启动。
 * - [cancel] 幂等：任务不存在/已结束时返回 true。
 * - [observe] 冷流；没有对应工作或 WorkManager 不可用时发射 null。
 */
interface JobQueue {

    /** 以 `KEEP` 策略入队唯一任务；重复调用不会替换已在排/在跑的任务。 */
    suspend fun enqueue(workName: String, inputData: Data): Boolean

    /** 取消唯一任务（运行中的 Worker 会收到 `isStopped`/协程取消）。 */
    suspend fun cancel(workName: String): Boolean

    /** 观察某个唯一任务名的状态；用于 UI 展示队列态或诊断。 */
    fun observe(workName: String): Flow<QueueSnapshot?>
}

/**
 * [JobQueue] 的生产实现：`enqueueUniqueWork(name, KEEP, OneTimeWorkRequest<GenerationWorker>)`。
 *
 * 为什么是 `KEEP`（§11.1）：同一本书同一时间只允许一个 Worker。`BatchRunner` 先查
 * `jobs` 活跃行再入队，正常情况下不会撞名；即使并发 start，`KEEP` 也保证不会替换
 * 已在跑的任务。反过来，若 WorkManager 里残留了同名工作而 `jobs` 表没有活跃行，
 * `KEEP` 会静默丢弃新请求——这属于 P3-05/P3-08 sweep 需要清理的异常场景，
 * 不在本类的职责内。
 *
 * 不使用 `REPLACE`：那会让用户在旧任务收尾前意外启动第二次生成，且旧 Worker
 * 仍可能写 job 表。
 */
class WorkManagerJobQueue(
    private val workManager: WorkManager,
) : JobQueue {

    override suspend fun enqueue(workName: String, inputData: Data): Boolean {
        val request = OneTimeWorkRequestBuilder<GenerationWorker>()
            .setInputData(inputData)
            .addTag(workName)
            .build()
        return try {
            workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, request).await()
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            // WorkManager 未初始化/数据库损坏等：交给 BatchRunner 把 job 标 error。
            Log.w(TAG, "enqueue failed: $workName", e)
            false
        }
    }

    override suspend fun cancel(workName: String): Boolean = try {
        workManager.cancelUniqueWork(workName).await()
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (e: Exception) {
        // 停止是 best-effort：取消失败时 Worker 仍会在下一个安全点读到 stopRequested。
        Log.w(TAG, "cancel failed: $workName", e)
        false
    }

    override fun observe(workName: String): Flow<QueueSnapshot?> =
        workManager.getWorkInfosForUniqueWorkFlow(workName)
            .map { infos -> infos.firstOrNull()?.toSnapshot(workName) }
            .catch { throwable ->
                if (throwable is CancellationException) throw throwable
                Log.w(TAG, "observe failed: $workName", throwable)
                emit(null)
            }

    companion object {
        private const val TAG = "AudioForgeJobQueue"

        /** 生产装配入口；测试请直接注入 fake [JobQueue]。 */
        fun create(context: Context): WorkManagerJobQueue =
            WorkManagerJobQueue(WorkManager.getInstance(context.applicationContext))
    }
}

private fun WorkInfo.toSnapshot(workName: String): QueueSnapshot = QueueSnapshot(
    workName = workName,
    state = when (state) {
        WorkInfo.State.ENQUEUED -> QueueState.ENQUEUED
        WorkInfo.State.RUNNING -> QueueState.RUNNING
        WorkInfo.State.BLOCKED -> QueueState.BLOCKED
        WorkInfo.State.SUCCEEDED -> QueueState.SUCCEEDED
        WorkInfo.State.FAILED -> QueueState.FAILED
        WorkInfo.State.CANCELLED -> QueueState.CANCELLED
        else -> QueueState.UNKNOWN
    },
    progress = this.progress.getInt(GenerationWorker.KEY_PROGRESS, 0).coerceIn(0, 100),
)
