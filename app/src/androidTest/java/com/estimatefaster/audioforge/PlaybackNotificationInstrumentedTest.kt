package com.estimatefaster.audioforge

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.estimatefaster.audioforge.playback.AudioForgeMediaSessionService
import com.estimatefaster.audioforge.playback.PlaybackCommands
import com.estimatefaster.audioforge.playback.PlaybackSessionCommands
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P5-08：媒体通知/后台服务 instrumented 测试。
 *
 * 用真实 `MediaController` 绑定 [AudioForgeMediaSessionService]，验证：
 * - Manifest 的服务导出 + `mediaPlayback` 前台服务类型；
 * - `playback` 通知渠道在服务启动后存在；
 * - 睡眠定时自定义命令的设置/循环/取消与参数校验；
 * - 没有队列时章节命令不会假装成功。
 *
 * 刻意不检查通知是否可见，也不申请 `POST_NOTIFICATIONS`：CI emulator 上通知权限
 * 不稳定，通知按钮的内容由 [com.estimatefaster.audioforge.playback.PlayerCommandsTest]
 * 覆盖。所有 controller 调用都切到主线程，避免 Media3 的线程断言。
 */
@RunWith(AndroidJUnit4::class)
class PlaybackNotificationInstrumentedTest {

    private var controller: MediaController? = null

    @After
    fun tearDown() {
        controller?.let { connected ->
            runOnMain { connected.release() }
        }
        controller = null
    }

    @Test
    fun mediaSessionService_isExportedWithMediaPlaybackType() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, AudioForgeMediaSessionService::class.java),
            0,
        )
        assertTrue("MediaSessionService 必须导出给系统媒体控件", info.exported)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertTrue(
                "服务必须声明 mediaPlayback 前台服务类型",
                info.foregroundServiceType and
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0,
            )
        }
    }

    @Test
    fun notificationChannel_isCreatedWhenServiceStarts() {
        connect()
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: error("NotificationManager 不可用")
        assertNotNull(
            "服务启动后应存在 ${AudioForgeMediaSessionService.CHANNEL_PLAYBACK} 渠道",
            manager.getNotificationChannel(AudioForgeMediaSessionService.CHANNEL_PLAYBACK),
        )
    }

    @Test
    fun sleepTimerCommand_setsCyclesAndCancels() {
        connect()

        val set = send(
            PlaybackCommands.ACTION_SLEEP_TIMER,
            PlaybackSessionCommands.sleepTimerExtras(15),
        )
        assertEquals(SessionResult.RESULT_SUCCESS, set.resultCode)
        assertEquals(15, set.extras?.getInt(PlaybackCommands.EXTRA_SLEEP_MINUTES, -1) ?: -1)

        // 不带分钟数 = 通知按钮循环：15 → 30。
        val cycled = send(PlaybackCommands.ACTION_SLEEP_TIMER, Bundle.EMPTY)
        assertEquals(SessionResult.RESULT_SUCCESS, cycled.resultCode)
        assertEquals(30, cycled.extras?.getInt(PlaybackCommands.EXTRA_SLEEP_MINUTES, -1) ?: -1)

        // 显式 0 = 关闭。
        val cancelled = send(
            PlaybackCommands.ACTION_SLEEP_TIMER,
            PlaybackSessionCommands.sleepTimerExtras(0),
        )
        assertEquals(SessionResult.RESULT_SUCCESS, cancelled.resultCode)
        assertEquals(0, cancelled.extras?.getInt(PlaybackCommands.EXTRA_SLEEP_MINUTES, -1) ?: -1)
    }

    @Test
    fun sleepTimerCommand_rejectsUnsupportedMinutes() {
        connect()
        val result = send(
            PlaybackCommands.ACTION_SLEEP_TIMER,
            PlaybackSessionCommands.sleepTimerExtras(7),
        )
        assertEquals(SessionResult.RESULT_ERROR_BAD_VALUE, result.resultCode)
    }

    @Test
    fun chapterCommand_withoutQueue_doesNotPretendSuccess() {
        val connected = connect()
        assertTrue(
            "未起播时播放器应处于 IDLE/ENDED，实际=${connected.playbackState}",
            connected.playbackState == Player.STATE_IDLE ||
                connected.playbackState == Player.STATE_ENDED,
        )
        val result = send(PlaybackCommands.ACTION_NEXT_CHAPTER, Bundle.EMPTY)
        assertNotEquals(
            "空队列时下一章命令不能返回成功",
            SessionResult.RESULT_SUCCESS,
            result.resultCode,
        )
    }

    // ------------------------------------------------------------ helpers

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun connect(): MediaController {
        controller?.let { return it }
        val futureRef = AtomicReference<ListenableFuture<MediaController>>()
        val latch = CountDownLatch(1)
        runOnMain {
            val future = MediaController.Builder(
                context,
                SessionToken(
                    context,
                    ComponentName(context, AudioForgeMediaSessionService::class.java),
                ),
            ).buildAsync()
            futureRef.set(future)
            future.addListener(
                { latch.countDown() },
                ContextCompat.getMainExecutor(context),
            )
        }
        assertTrue(
            "15s 内未连接上 MediaSessionService",
            latch.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        val connected = futureRef.get().get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        controller = connected
        return connected
    }

    private fun send(action: String, extras: Bundle): SessionResult {
        val connected = controller ?: error("controller 未连接")
        val futureRef = AtomicReference<ListenableFuture<SessionResult>>()
        runOnMain {
            futureRef.set(
                connected.sendCustomCommand(
                    SessionCommand(action, Bundle.EMPTY),
                    extras,
                ),
            )
        }
        return futureRef.get().get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun runOnMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
    }
}
