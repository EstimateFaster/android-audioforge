package com.estimatefaster.audioforge.domain.jobs

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.estimatefaster.audioforge.MainActivity
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.ui.navigation.AppDeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager

/**
 * P3-03：生成通知（执行计划 §11.2 / §15 P3-03）。
 *
 * Robolectric 覆盖：
 * - 3 个渠道创建（幂等）与 importance；
 * - 进度通知的标题/确定进度/ongoing/onlyAlertOnce/channel；
 * - 「停止」action 是显式广播，目标 [GenerationNotificationsReceiver] 且带 workName；
 * - 完成/失败/取消通知的 autoCancel、点击 intent（`audioforge://project/{id}`）；
 * - Android 13+ 未授权 `POST_NOTIFICATIONS` 时 `canPost=false` 且不发送通知；
 * - receiver 对未知 action / 缺 workName 不崩溃（不初始化 WorkManager）。
 *
 * 停止的真实效果（`cancelUniqueWork` → `isStopped` → job=cancelled）属于 WorkManager
 * 集成测试，unitTest 没有 `work-testing` 依赖（P3-02 的同一约束），由真机 smoke 覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GenerationNotificationsTest {

    private lateinit var application: Application
    private lateinit var context: Context
    private lateinit var shadowNotifications: ShadowNotificationManager

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext<Application>()
        context = application
        shadowNotifications = shadowOf(context.getSystemService(NotificationManager::class.java))

        // 默认按“已授权”跑；未授权的行为单独在 API 33 测试里覆盖。
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        GenerationNotifications.ensureChannels(context)
    }

    private fun channel(id: String) = requireNotNull(
        shadowNotifications.notificationChannels.firstOrNull { it.id == id },
    ) { "channel $id 未创建" }

    private fun notificationOf(id: Int): Notification = requireNotNull(
        shadowNotifications.getNotification(id),
    ) { "notification $id 未发送" }

    // ------------------------------------------------------------ 渠道

    @Test
    fun ensureChannels_creates_three_channels_with_expected_importance() {
        assertEquals(3, shadowNotifications.notificationChannels.size)

        val progress = channel(GenerationNotifications.CHANNEL_PROGRESS)
        assertEquals(NotificationManager.IMPORTANCE_LOW, progress.importance)
        assertEquals(context.getString(R.string.notif_channel_generation), progress.name)
        assertEquals(context.getString(R.string.notif_channel_generation_desc), progress.description)
        assertFalse(progress.shouldShowLights())
        assertNull(progress.sound)
        assertFalse(progress.shouldVibrate())

        val done = channel(GenerationNotifications.CHANNEL_DONE)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, done.importance)
        assertEquals(context.getString(R.string.notif_channel_done), done.name)

        val failed = channel(GenerationNotifications.CHANNEL_FAILED)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, failed.importance)
        assertEquals(context.getString(R.string.notif_channel_failed), failed.name)
        assertEquals(context.getString(R.string.notif_channel_failed_desc), failed.description)
    }

    @Test
    fun ensureChannels_is_idempotent() {
        GenerationNotifications.ensureChannels(context)
        GenerationNotifications.ensureChannels(context)

        val ids = shadowNotifications.notificationChannels.map { it.id }.toSet()
        assertEquals(
            setOf(
                GenerationNotifications.CHANNEL_PROGRESS,
                GenerationNotifications.CHANNEL_DONE,
                GenerationNotifications.CHANNEL_FAILED,
            ),
            ids,
        )
    }

    // ------------------------------------------------------------ 进度通知

    @Test
    fun progressNotification_has_book_title_determinate_progress_and_flags() {
        val state = GenerationNotificationState(
            novelId = 42L,
            novelTitle = "山海秘闻",
            chaptersDone = 2,
            chaptersTotal = 5,
        )

        val notification = GenerationNotifications.progressNotification(context, state)

        assertEquals(GenerationNotifications.CHANNEL_PROGRESS, notification.channelId)
        assertEquals(
            context.getString(R.string.notif_generation_title, "山海秘闻"),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertEquals(2, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(5, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertEquals(
            context.getString(R.string.notif_generation_progress, 40, 2, 5),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        assertTrue((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertTrue((notification.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)
    }

    @Test
    fun foregroundInfo_uses_progress_notification_and_dataSync_type() {
        val state = GenerationNotificationState(42L, "山海秘闻", 0, 3)

        val info = GenerationNotifications.foregroundInfo(context, state)

        assertEquals(GenerationNotifications.PROGRESS_NOTIFICATION_ID, info.notificationId)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        assertEquals(GenerationNotifications.CHANNEL_PROGRESS, info.notification.channelId)
    }

    @Test
    fun progressNotification_stopAction_isExplicitBroadcastToReceiverWithWorkName() {
        val expectedWorkName = GenerationNotifications.workName(42L)
        assertEquals("generation-42", expectedWorkName)
        assertEquals(GenerationWorker.UNIQUE_WORK_PREFIX + 42L, expectedWorkName)

        val notification = GenerationNotifications.progressNotification(
            context,
            GenerationNotificationState(42L, "山海秘闻", 1, 3),
        )

        assertEquals(1, notification.actions.size)
        val action = notification.actions[0]
        assertEquals(
            context.getString(R.string.notif_generation_stop),
            action.title.toString(),
        )
        assertTrue(action.icon != 0)

        val pendingIntent = requireNotNull(action.actionIntent)
        val shadow = shadowOf(pendingIntent)
        assertTrue(shadow.isBroadcast)
        assertFalse(shadow.isActivity)
        assertTrue(shadow.isImmutable)

        val intent = shadow.savedIntent
        assertEquals(
            GenerationNotificationsReceiver::class.java.name,
            intent.component?.className,
        )
        assertEquals(GenerationNotifications.ACTION_STOP, intent.action)
        assertEquals(
            expectedWorkName,
            intent.getStringExtra(GenerationNotifications.EXTRA_WORK_NAME),
        )
    }

    // ------------------------------------------------------------ 完成/失败通知

    @Test
    fun doneNotification_isAutoCancel_andOpensProjectDeepLink() {
        val posted = GenerationNotifications.notifyFinished(
            context,
            GenerationNotificationResult(
                novelId = 42L,
                novelTitle = "山海秘闻",
                chaptersDone = 5,
                chaptersTotal = 5,
                status = GenerationFinishStatus.Done,
            ),
        )
        assertTrue(posted)

        val notification = notificationOf(GenerationNotifications.DONE_NOTIFICATION_ID)
        assertEquals(GenerationNotifications.CHANNEL_DONE, notification.channelId)
        assertEquals(
            context.getString(R.string.notif_generation_done_title),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertTrue((notification.flags and Notification.FLAG_AUTO_CANCEL) != 0)

        val content = shadowOf(notification.contentIntent)
        assertTrue(content.isActivity)
        val intent = content.savedIntent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(AppDeepLink.projectDetailUri(42L), intent.data.toString())
        assertEquals(42L, intent.getLongExtra(GenerationNotifications.EXTRA_NOVEL_ID, 0L))
    }

    @Test
    fun failedNotification_usesFailedChannel_withErrorSummary() {
        val posted = GenerationNotifications.notifyFinished(
            context,
            GenerationNotificationResult(
                novelId = 7L,
                novelTitle = "断章",
                chaptersDone = 0,
                chaptersTotal = 2,
                status = GenerationFinishStatus.Failed,
                errorSummary = "全部 2 章失败：第 1 章：网络错误",
            ),
        )
        assertTrue(posted)

        val notification = notificationOf(GenerationNotifications.FAILED_NOTIFICATION_ID)
        assertEquals(GenerationNotifications.CHANNEL_FAILED, notification.channelId)
        assertEquals(
            context.getString(R.string.notif_generation_failed_title),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        assertTrue(text?.contains("网络错误") == true)
        assertTrue((notification.flags and Notification.FLAG_AUTO_CANCEL) != 0)
        assertTrue(shadowOf(notification.contentIntent).isActivity)
    }

    @Test
    fun cancelledNotification_usesFailedChannel_andKeepsProgress() {
        val posted = GenerationNotifications.notifyFinished(
            context,
            GenerationNotificationResult(
                novelId = 7L,
                novelTitle = "断章",
                chaptersDone = 1,
                chaptersTotal = 4,
                status = GenerationFinishStatus.Cancelled,
            ),
        )
        assertTrue(posted)

        val notification = notificationOf(GenerationNotifications.FAILED_NOTIFICATION_ID)
        assertEquals(GenerationNotifications.CHANNEL_FAILED, notification.channelId)
        assertEquals(
            context.getString(R.string.notif_generation_cancelled_title),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertEquals(
            context.getString(R.string.notif_generation_cancelled_text, "断章", 1, 4),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }

    @Test
    fun partialNotification_usesDoneChannel_butShowsFailureSummary() {
        val posted = GenerationNotifications.notifyFinished(
            context,
            GenerationNotificationResult(
                novelId = 7L,
                novelTitle = "断章",
                chaptersDone = 3,
                chaptersTotal = 4,
                status = GenerationFinishStatus.Partial,
                errorSummary = "1 章失败：第 4 章：鉴权失败",
            ),
        )
        assertTrue(posted)

        val notification = notificationOf(GenerationNotifications.DONE_NOTIFICATION_ID)
        assertEquals(GenerationNotifications.CHANNEL_DONE, notification.channelId)
        assertEquals(
            context.getString(R.string.notif_generation_partial_title),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertTrue(
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)
                ?.toString()
                ?.contains("鉴权失败") == true,
        )
    }

    // ------------------------------------------------------------ 权限

    @Test
    @Config(sdk = [33])
    fun canPost_isFalse_onApi33_withoutPostNotifications() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertFalse(GenerationNotifications.canPost(context))
    }

    @Test
    @Config(sdk = [33])
    fun notifyFinished_postsNothing_butDoesNotThrow_whenPermissionDenied() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val posted = GenerationNotifications.notifyFinished(
            context,
            GenerationNotificationResult(
                novelId = 42L,
                novelTitle = "山海秘闻",
                chaptersDone = 5,
                chaptersTotal = 5,
                status = GenerationFinishStatus.Done,
            ),
        )

        assertFalse(posted)
        assertNull(
            shadowNotifications.getNotification(GenerationNotifications.DONE_NOTIFICATION_ID),
        )
    }

    @Test
    @Config(sdk = [33])
    fun canPost_isTrue_onApi33_withPostNotificationsGranted() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertTrue(GenerationNotifications.canPost(context))
    }

    @Test
    fun canPost_isFalse_whenSystemNotificationsDisabled() {
        shadowNotifications.setNotificationsEnabled(false)

        assertFalse(GenerationNotifications.canPost(context))
    }

    // ------------------------------------------------------------ receiver

    @Test
    fun receiver_ignoresUnknownAction() {
        GenerationNotificationsReceiver().onReceive(context, Intent("com.example.UNKNOWN"))
    }

    @Test
    fun receiver_ignoresStopWithoutWorkName() {
        GenerationNotificationsReceiver().onReceive(
            context,
            Intent(GenerationNotifications.ACTION_STOP),
        )
    }
}
