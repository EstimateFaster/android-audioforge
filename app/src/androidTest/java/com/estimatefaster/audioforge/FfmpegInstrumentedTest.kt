package com.estimatefaster.audioforge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.estimatefaster.audioforge.core.audio.FfmpegKitRunner
import com.estimatefaster.audioforge.core.audio.FfmpegProbe
import com.estimatefaster.audioforge.core.audio.FfmpegProbeState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** P0-07：`-version` + 350ms 静音 + 两段 mp3 拼接 + ffprobe 时长。 */
@RunWith(AndroidJUnit4::class)
class FfmpegInstrumentedTest {

    @Test
    fun version_silence_and_concat() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = FfmpegProbe(FfmpegKitRunner(), context.cacheDir)

        val result = probe.run()
        assertTrue("FFmpeg 冒烟失败：$result", result is FfmpegProbeState.Passed)

        val passed = result as FfmpegProbeState.Passed
        assertTrue("静音文件大小应大于 0", passed.silenceBytes > 0)
        assertTrue(
            "拼接时长应约 1s，实际 ${passed.joinedDurationMs}ms",
            passed.joinedDurationMs in 600..2_000,
        )
    }
}
