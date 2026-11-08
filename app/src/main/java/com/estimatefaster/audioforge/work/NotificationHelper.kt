package com.estimatefaster.audioforge.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.estimatefaster.audioforge.MainActivity
import com.estimatefaster.audioforge.R

/**
 * 通知渠道与前台通知。Phase 0 只有探针任务；P3 的生成任务复用同一渠道约定
 * （见执行计划 §11.2）。
 */
object NotificationHelper {

    const val CHANNEL_GENERATION = "generation"
    const val CHANNEL_DONE = "generation_done"
    const val PROBE_NOTIFICATION_ID = 1001
    const val DONE_NOTIFICATION_ID = 1002
    const val GENERATION_NOTIFICATION_ID = 1003

    private const val TAG = "AudioForgeNotify"

    fun ensureChannels(context: Context) {
        val generation = NotificationChannel(
            CHANNEL_GENERATION,
            context.getString(R.string.notif_channel_generation),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_generation_desc)
            setShowBadge(false)
        }
        val done = NotificationChannel(
            CHANNEL_DONE,
            context.getString(R.string.notif_channel_done),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_channel_done_desc)
        }
        NotificationManagerCompat.from(context).createNotificationChannels(listOf(generation, done))
    }

    fun probeForegroundInfo(context: Context, progress: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, CHANNEL_GENERATION)
            .setSmallIcon(R.drawable.ic_stat_audioforge)
            .setContentTitle(context.getString(R.string.notif_probe_title))
            .setContentText(context.getString(R.string.notif_probe_progress, progress))
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        return ForegroundInfo(
            PROBE_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    /**
     * P3-02 生成批次的前台通知：复用 `generation` 渠道与现有图标，只显示标题与百分比。
     *
     * P3-03 会补「停止」action、完成/失败通知和 POST_NOTIFICATIONS 引导；本方法保持最小实现，
     * 避免在 P3-02 里提前定死通知交互。
     */
    fun generationForegroundInfo(context: Context, progress: Int): ForegroundInfo {
        val percent = progress.coerceIn(0, 100)
        val notification = NotificationCompat.Builder(context, CHANNEL_GENERATION)
            .setSmallIcon(R.drawable.ic_stat_audioforge)
            .setContentTitle(context.getString(R.string.status_audio_running))
            .setContentText(context.getString(R.string.notif_probe_progress, percent))
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        return ForegroundInfo(
            GENERATION_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    @SuppressLint("MissingPermission") // 前置 canPostNotifications() 已检查 POST_NOTIFICATIONS
    fun notifyDone(context: Context, success: Boolean, text: String) {
        if (!canPostNotifications(context)) {
            // 用户拒绝通知权限时任务仍应跑完，只是通知不可见（§11.2）。
            Log.i(TAG, "skip done notification: POST_NOTIFICATIONS not granted")
            return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_audioforge)
            .setContentTitle(
                context.getString(
                    if (success) R.string.notif_done_success else R.string.notif_done_failure,
                ),
            )
            .setContentText(text)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(DONE_NOTIFICATION_ID, notification)
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
