package com.estimatefaster.audioforge

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.estimatefaster.audioforge.playback.AudioForgeMediaSessionService
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P2-14 播放 instrumented 测试。
 *
 * 说明：P2-14 的目标设备链路是 `PlayerController` → `MediaController` →
 * `AudioForgeMediaSessionService`。在 instrumentation 进程里直接绑定 MediaSessionService
 * 依赖后台前台服务/主线程调度，在无音频的 CI emulator 上容易不稳定，因此这里按任务允许的
 * 回退方案，用同一份 0.5s 静音 WAV **直接构造 ExoPlayer**，验证 Media3 能解码本地文件并进入
 * 播放态；Manifest 服务声明由 [mediaSessionService_isDeclaredInManifest] 单独校验。
 * 真机 smoke 仍需按 §15 P2-14 验收后台/锁屏/通知控制。
 */
@RunWith(AndroidJUnit4::class)
class PlaybackInstrumentedTest {

    @Test
    fun exoPlayer_decodes_and_starts_silent_wav() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val wav = writeSilentWav(File(context.cacheDir, "playback_silence.wav"))
        lateinit var player: ExoPlayer
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build()
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(wav)))
            player.prepare()
            player.play()
        }
        try {
            val deadlineMs = SystemClock.uptimeMillis() + TIMEOUT_MS
            var ready = false
            var started = false
            var lastState = Player.STATE_IDLE
            var lastPosition = 0L
            while (SystemClock.uptimeMillis() < deadlineMs) {
                instrumentation.runOnMainSync {
                    lastState = player.playbackState
                    lastPosition = player.currentPosition
                    if (lastState == Player.STATE_READY) ready = true
                    if (player.isPlaying || lastPosition > 0L) started = true
                }
                if (started || lastState == Player.STATE_ENDED) break
                Thread.sleep(POLL_INTERVAL_MS)
            }

            var durationMs = 0L
            var errorMessage: String? = null
            instrumentation.runOnMainSync {
                durationMs = player.duration
                errorMessage = player.playerError?.message
            }
            assertTrue(
                "ExoPlayer 未在 15s 内进入 READY（state=$lastState，error=$errorMessage）",
                ready,
            )
            assertTrue(
                "0.5s 静音 WAV 的时长应约 500ms，实际 ${durationMs}ms",
                durationMs in 300..2_000,
            )
            assertTrue(
                "15s 内未开始播放（position=$lastPosition，state=$lastState）",
                started,
            )
        } finally {
            instrumentation.runOnMainSync { player.release() }
            wav.delete()
        }
    }

    @Test
    fun mediaSessionService_isDeclaredInManifest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val services = context.packageManager.queryIntentServices(
            Intent(MEDIA_SESSION_SERVICE_ACTION).setPackage(context.packageName),
            0,
        )
        val names = services.map { it.serviceInfo.name }
        assertTrue(
            "Manifest 缺少 action=$MEDIA_SESSION_SERVICE_ACTION 的 " +
                AudioForgeMediaSessionService::class.java.name + "，实际=$names",
            names.contains(AudioForgeMediaSessionService::class.java.name),
        )
    }

    /** 写一个 16kHz / 单声道 / 16-bit PCM 的静音 WAV（时长 0.5s）。 */
    private fun writeSilentWav(file: File): File {
        val sampleRate = 16_000
        val channels = 1
        val bitsPerSample = 16
        val durationMs = 500
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = sampleRate * durationMs / 1_000 * blockAlign
        val header = ByteArray(WAV_HEADER_BYTES)

        fun putAscii(offset: Int, text: String) {
            text.forEachIndexed { index, char -> header[offset + index] = char.code.toByte() }
        }

        fun putIntLe(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = (value shr 8 and 0xFF).toByte()
            header[offset + 2] = (value shr 16 and 0xFF).toByte()
            header[offset + 3] = (value shr 24 and 0xFF).toByte()
        }

        fun putShortLe(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = (value shr 8 and 0xFF).toByte()
        }

        putAscii(0, "RIFF")
        putIntLe(4, 36 + dataSize)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putIntLe(16, 16) // PCM fmt chunk size
        putShortLe(20, 1) // PCM
        putShortLe(22, channels)
        putIntLe(24, sampleRate)
        putIntLe(28, sampleRate * blockAlign) // byte rate
        putShortLe(32, blockAlign)
        putShortLe(34, bitsPerSample)
        putAscii(36, "data")
        putIntLe(40, dataSize)

        file.outputStream().use { out ->
            out.write(header)
            out.write(ByteArray(dataSize))
        }
        return file
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val POLL_INTERVAL_MS = 20L
        const val WAV_HEADER_BYTES = 44
        const val MEDIA_SESSION_SERVICE_ACTION = "androidx.media3.session.MediaSessionService"
    }
}
