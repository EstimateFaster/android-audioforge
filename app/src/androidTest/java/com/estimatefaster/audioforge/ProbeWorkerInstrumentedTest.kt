package com.estimatefaster.audioforge

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.estimatefaster.audioforge.work.NotificationHelper
import com.estimatefaster.audioforge.work.ProbeWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P0-06 的真机/模拟器验证：
 * - 前台任务能跑完（SUCCEEDED）；
 * - 运行期间能看到 `dataSync` 前台通知；
 * - 取消任务后状态为 CANCELLED 且进程不崩。
 *
 * 启动 MainActivity 是为了让应用处于前台，避免 Android 12+ 的后台启动前台服务限制。
 */
@RunWith(AndroidJUnit4::class)
class ProbeWorkerInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Before
    fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 模拟器上通过 shell 授权，避免 UI 弹窗阻塞测试。
            val pfd = instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}",
            )
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
        NotificationHelper.ensureChannels(context)
    }

    @Test
    fun foreground_worker_runs_and_posts_notification() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val workManager = WorkManager.getInstance(context)
            ProbeWorker.enqueue(context, workManager)

            var sawNotification = false
            var finalState: WorkInfo.State? = null
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val info = workManager
                    .getWorkInfosForUniqueWork(ProbeWorker.UNIQUE_NAME)
                    .get()
                    .firstOrNull()
                if (info?.state?.isFinished == true) {
                    finalState = info.state
                    break
                }
                if (hasProbeNotification()) sawNotification = true
                Thread.sleep(POLL_MS)
            }

            assertEquals(WorkInfo.State.SUCCEEDED, finalState)
            assertTrue("前台任务运行期间应能看到通知", sawNotification)
        }
    }

    @Test
    fun cancelling_worker_ends_with_cancelled_state() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val workManager = WorkManager.getInstance(context)
            ProbeWorker.enqueue(context, workManager)
            Thread.sleep(1_000)
            ProbeWorker.cancel(context, workManager)

            var finalState: WorkInfo.State? = null
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val info = workManager
                    .getWorkInfosForUniqueWork(ProbeWorker.UNIQUE_NAME)
                    .get()
                    .firstOrNull()
                if (info?.state?.isFinished == true) {
                    finalState = info.state
                    break
                }
                Thread.sleep(POLL_MS)
            }
            assertEquals(WorkInfo.State.CANCELLED, finalState)
        }
    }

    private fun hasProbeNotification(): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.activeNotifications.any {
            it.id == NotificationHelper.PROBE_NOTIFICATION_ID
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val POLL_MS = 200L
    }
}
