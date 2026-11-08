package com.estimatefaster.audioforge.domain.jobs

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.estimatefaster.audioforge.MainActivity
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.ui.navigation.AppDeepLink
import com.estimatefaster.audioforge.work.NotificationHelper

/**
 * 生成任务的通知层（执行计划 §11.2 / §15 P3-03）。
 *
 * 三个渠道：
 * - [CHANNEL_PROGRESS]（id 复用 P0-06 的 `generation`，[NotificationManager.IMPORTANCE_LOW]）：
 *   前台服务进度，无声音、不震动、不显示角标；
 * - [CHANNEL_DONE]（复用 P0-06 的 `generation_done`，IMPORTANCE_DEFAULT）：批次完成/部分完成；
 * - [CHANNEL_FAILED]（`generation_failed`，IMPORTANCE_DEFAULT）：失败/取消/手动停止。
 *
 * 复用已有渠道 id 是为了不重置用户已经调整过的渠道设置（声音/横幅），渠道名与描述仍在
 * [ensureChannels] 里统一写入（首次创建时生效）。
 *
 * 权限语义（§11.2）：Android 13+ 的 `POST_NOTIFICATIONS` 只影响**能否看见**通知，
 * 不影响任务本身。前台服务通知由系统强制要求，[foregroundInfo] 必须照常返回；
 * 完成/失败这类主动通知由 [canPost] 拦截——拒绝授权时直接跳过并继续跑任务，
 * UI 侧可用 [canPost] 提示“通知不可见，但任务仍会运行”。
 *
 * 停止语义：进度通知的「停止」action 是显式广播 [ACTION_STOP]，由
 * [GenerationNotificationsReceiver] 调用 `WorkManager.cancelUniqueWork(workName)`；
 * [workName] 必须与 BatchRunner 入队时使用的唯一任务名一致
 * （`GenerationWorker.UNIQUE_WORK_PREFIX + novelId`）。
 *
 * 点击语义：所有通知都通过 `audioforge://project/{novelId}` 深链打开项目详情
 * （P4-01 的 [AppDeepLink] 约定）。这里只引用纯 JVM 的常量/构造函数，不依赖 Compose。
 */
object GenerationNotifications {

    /** 进度渠道，复用 P0-06 的 `generation`。 */
    const val CHANNEL_PROGRESS: String = NotificationHelper.CHANNEL_GENERATION

    /** 完成渠道，复用 P0-06 的 `generation_done`。 */
    const val CHANNEL_DONE: String = NotificationHelper.CHANNEL_DONE

    /** 失败/取消渠道（P3-03 新增）。 */
    const val CHANNEL_FAILED: String = "generation_failed"

    const val PROGRESS_NOTIFICATION_ID: Int = 2001
    const val DONE_NOTIFICATION_ID: Int = 2002
    const val FAILED_NOTIFICATION_ID: Int = 2003

    /** 「停止」广播 action；只由本应用的显式 PendingIntent 触发。 */
    const val ACTION_STOP: String = "com.estimatefaster.audioforge.action.STOP_GENERATION"

    /** 广播 extra：要取消的唯一任务名，例如 `generation-42`。 */
    const val EXTRA_WORK_NAME: String = "workName"

    /** 深链 intent extra：项目 id，供将来 UI 在不需要解析 URI 时直接读取。 */
    const val EXTRA_NOVEL_ID: String = "novelId"

    private const val TAG = "AudioForgeGenNotify"

    /**
     * 创建/更新三个渠道；幂等，Worker 每次启动都可以调用（不依赖 Application 启动路径）。
     *
     * 创建渠道不需要 `POST_NOTIFICATIONS` 权限，因此这里不做权限检查。
     */
    fun ensureChannels(context: Context) {
        val progress = NotificationChannel(
            CHANNEL_PROGRESS,
            context.getString(R.string.notif_channel_generation),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_generation_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        val done = NotificationChannel(
            CHANNEL_DONE,
            context.getString(R.string.notif_channel_done),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_channel_done_desc)
        }
        val failed = NotificationChannel(
            CHANNEL_FAILED,
            context.getString(R.string.notif_channel_failed),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_channel_failed_desc)
        }
        NotificationManagerCompat.from(context)
            .createNotificationChannels(listOf(progress, done, failed))
    }

    /**
     * 是否可以主动发通知（完成/失败）。
     *
     * - 系统级“通知总开关”关闭 → false；
     * - Android 13+ 未授予 `POST_NOTIFICATIONS` → false；
     * - 更早的版本只有系统开关。
     *
     * 返回 false 时调用方必须继续任务，只是不展示通知。
     */
    fun canPost(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 唯一任务名。BatchRunner（P3-04）入队、Worker 的停止 action、receiver 的
     * `cancelUniqueWork` 必须共用这一个名字，否则「停止」会静默无效。
     */
    fun workName(novelId: Long): String = GenerationWorker.UNIQUE_WORK_PREFIX + novelId

    // ------------------------------------------------------------ 进度/前台通知

    /**
     * 进度通知：标题「正在生成：<书名>」、确定进度、仅提醒一次、不可滑动清除，
     * 带「停止」action 与打开项目的点击意图。
     *
     * 即使 `POST_NOTIFICATIONS` 被拒绝也要构建：前台服务必须有一个通知对象
     * （系统只是不把它展示在通知栏）。
     */
    fun progressNotification(
        context: Context,
        state: GenerationNotificationState,
    ): Notification = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
        .setSmallIcon(R.drawable.ic_stat_audioforge)
        .setContentTitle(
            context.getString(R.string.notif_generation_title, state.novelTitle),
        )
        .setContentText(
            context.getString(
                R.string.notif_generation_progress,
                state.percent,
                state.progress,
                state.max,
            ),
        )
        .setProgress(state.max, state.progress, false)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setContentIntent(contentIntent(context, state.novelId))
        .addAction(
            R.drawable.ic_stat_audioforge,
            context.getString(R.string.notif_generation_stop),
            stopIntent(context, workName(state.novelId)),
        )
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    /** `setForeground` 用的包装；dataSync 类型与 P0-03 的 Manifest 声明一致。 */
    fun foregroundInfo(
        context: Context,
        state: GenerationNotificationState,
    ): ForegroundInfo = ForegroundInfo(
        PROGRESS_NOTIFICATION_ID,
        progressNotification(context, state),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    // ------------------------------------------------------------ 完成/失败

    /**
     * 发完成/部分完成/失败/取消通知。
     *
     * @return 是否真的发出；`POST_NOTIFICATIONS` 未授权或系统通知关闭时返回 false，
     *   调用方（Worker）必须继续收尾，不能因为通知失败改变任务结果。
     */
    @SuppressLint("MissingPermission") // 前置 canPost() 已检查 POST_NOTIFICATIONS
    fun notifyFinished(
        context: Context,
        result: GenerationNotificationResult,
    ): Boolean {
        if (!canPost(context)) {
            Log.i(TAG, "skip finish notification: notifications disabled (status=${result.status})")
            return false
        }

        val content = finishedContent(context, result)
        val notification = NotificationCompat.Builder(context, content.channelId)
            .setSmallIcon(R.drawable.ic_stat_audioforge)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
            .setContentIntent(contentIntent(context, result.novelId))
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        NotificationManagerCompat.from(context).notify(content.notificationId, notification)
        return true
    }

    private fun finishedContent(
        context: Context,
        result: GenerationNotificationResult,
    ): FinishedContent {
        val title = result.novelTitle
        return when (result.status) {
            GenerationFinishStatus.Done -> FinishedContent(
                channelId = CHANNEL_DONE,
                notificationId = DONE_NOTIFICATION_ID,
                title = context.getString(R.string.notif_generation_done_title),
                text = context.getString(
                    R.string.notif_generation_done_text,
                    title,
                    result.chaptersDone,
                ),
            )

            GenerationFinishStatus.Partial -> FinishedContent(
                channelId = CHANNEL_DONE,
                notificationId = DONE_NOTIFICATION_ID,
                title = context.getString(R.string.notif_generation_partial_title),
                text = context.getString(
                    R.string.notif_generation_partial_text,
                    title,
                    result.errorSummary.ifBlank {
                        context.getString(R.string.notif_generation_failed_unknown)
                    },
                ),
            )

            GenerationFinishStatus.Failed -> FinishedContent(
                channelId = CHANNEL_FAILED,
                notificationId = FAILED_NOTIFICATION_ID,
                title = context.getString(R.string.notif_generation_failed_title),
                text = context.getString(
                    R.string.notif_generation_failed_text,
                    title,
                    result.errorSummary.ifBlank {
                        context.getString(R.string.notif_generation_failed_unknown)
                    },
                ),
            )

            GenerationFinishStatus.Cancelled -> FinishedContent(
                channelId = CHANNEL_FAILED,
                notificationId = FAILED_NOTIFICATION_ID,
                title = context.getString(R.string.notif_generation_cancelled_title),
                text = context.getString(
                    R.string.notif_generation_cancelled_text,
                    title,
                    result.chaptersDone,
                    result.chaptersTotal.coerceAtLeast(1),
                ),
            )
        }
    }

    // ------------------------------------------------------------ Intent

    /** 点击通知：显式启动 [MainActivity] 并带上项目深链。 */
    fun contentIntent(context: Context, novelId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(AppDeepLink.projectDetailUri(novelId))
            putExtra(EXTRA_NOVEL_ID, novelId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            novelId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 「停止」广播：显式指向 [GenerationNotificationsReceiver]，只带 workName。 */
    fun stopIntent(context: Context, workName: String): PendingIntent {
        val intent = Intent(context, GenerationNotificationsReceiver::class.java).apply {
            action = ACTION_STOP
            putExtra(EXTRA_WORK_NAME, workName)
        }
        return PendingIntent.getBroadcast(
            context,
            workName.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** 进度通知的状态快照；进度条 max 用章节总数，progress 用已处理章数。 */
data class GenerationNotificationState(
    val novelId: Long,
    val novelTitle: String,
    val chaptersDone: Int,
    val chaptersTotal: Int,
) {
    /** `setProgress` 的 max；章节总数未知时退化为 1，避免除零/非法进度。 */
    val max: Int get() = chaptersTotal.coerceAtLeast(1)

    val progress: Int get() = chaptersDone.coerceIn(0, max)

    val percent: Int get() = progress * 100 / max
}

/** 批次结束时的通知状态。 */
enum class GenerationFinishStatus {
    /** 全部章节成功。 */
    Done,

    /** 有章节成功也有失败：走完成渠道，但正文带失败摘要。 */
    Partial,

    /** 全部章节失败或执行异常。 */
    Failed,

    /** 用户停止 / WorkManager 取消。 */
    Cancelled,
}

/** 完成/失败通知的数据。 */
data class GenerationNotificationResult(
    val novelId: Long,
    val novelTitle: String,
    val chaptersDone: Int,
    val chaptersTotal: Int,
    val status: GenerationFinishStatus,
    val errorSummary: String = "",
)

private data class FinishedContent(
    val channelId: String,
    val notificationId: Int,
    val title: String,
    val text: String,
)
