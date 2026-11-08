package com.estimatefaster.audioforge.domain.jobs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.WorkManager

/**
 * 进度通知「停止」action 的接收器（执行计划 §11.2 / §15 P3-03）。
 *
 * Manifest 里 `exported=false`：只有本应用的显式 [PendingIntent]（`getBroadcast`）能触发，
 * 外部应用无法伪造停止请求。
 *
 * 只做一件事：`WorkManager.cancelUniqueWork(workName)`。Worker 侧通过 `isStopped`
 * 感知取消并在 `finally` 里把 job 收尾为 cancelled，已完成章节的结果保留（停止语义见
 * [GenerationWorker]）。这里不直接改 `jobs` 表，避免和 Worker 的收尾竞争。
 */
class GenerationNotificationsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GenerationNotifications.ACTION_STOP) {
            Log.w(TAG, "ignored unknown action: ${intent.action}")
            return
        }
        val workName = intent.getStringExtra(GenerationNotifications.EXTRA_WORK_NAME)
            ?.trim()
            .orEmpty()
        if (workName.isEmpty()) {
            Log.w(TAG, "stop ignored: missing ${GenerationNotifications.EXTRA_WORK_NAME}")
            return
        }

        try {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName)
            Log.i(TAG, "stop requested: cancelUniqueWork($workName)")
        } catch (e: IllegalStateException) {
            // WorkManager 未初始化（例如测试/进程刚起）时不要让广播抛异常。
            Log.w(TAG, "stop ignored: WorkManager unavailable ($workName)", e)
        }
    }

    private companion object {
        const val TAG = "AudioForgeGenNotify"
    }
}
