package com.estimatefaster.audioforge

import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.estimatefaster.audioforge.core.audio.ChapterStitcher
import com.estimatefaster.audioforge.core.audio.FfmpegKitRunner
import com.estimatefaster.audioforge.core.audio.SilenceGenerator
import com.estimatefaster.audioforge.core.model.SegmentType
import com.estimatefaster.audioforge.core.storage.AudioStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P2-10 的真机/模拟器 smoke：
 * 3 段 0.3s 正弦 mp3 → 350/700ms 静音 → stitch → m4a 存在且时长约 1.5–2.1s；
 * 空片段列表抛 [IllegalArgumentException]。
 */
@RunWith(AndroidJUnit4::class)
class ChapterStitcherInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = 120_000L)
    fun stitch_three_segments_with_pauses() = runBlocking {
        val runner = FfmpegKitRunner()
        val cacheRoot = File(context.cacheDir, "p210-stitch-it").apply {
            deleteRecursively()
            mkdirs()
        }
        val storageRoot = File(context.filesDir, "p210-stitch-it").apply {
            deleteRecursively()
            mkdirs()
        }
        val storage = AudioStorage(storageRoot)
        val stitcher = ChapterStitcher(runner, cacheRoot)
        try {
            val segments = List(3) { index -> createTone(runner, cacheRoot, index) }

            val generator = SilenceGenerator(runner, cacheRoot)
            val sil350 = generator.silenceFile(350)
            val sil700 = generator.silenceFile(700)
            assertEquals("sil_350.mp3", sil350.name)
            assertEquals("sil_700.mp3", sil700.name)
            assertTrue("350ms 静音应非空", sil350.length() > 0L)
            assertTrue("700ms 静音应非空", sil700.length() > 0L)
            assertEquals("静音缓存应按毫秒复用", sil350, generator.silenceFile(350))

            val types = listOf(
                SegmentType.Narration,
                SegmentType.Dialogue,
                SegmentType.Narration,
            )
            val result = stitcher.stitch(NOVEL_ID, CHAPTER_IDX, segments, types, storage)

            assertEquals("audio/$NOVEL_ID/chapter_001.m4a", result.relativePath)

            val output = storage.resolve(result.relativePath)
            assertTrue("输出文件应存在：$output", output.isFile)
            assertTrue("输出文件应非空", output.length() > 0L)

            // 3 × 0.3s + 0.35s + 0.70s ≈ 1.95s；给 MP3 编码填充留余量。
            assertTrue(
                "结果时长应约 1.5–2.1s，实际 ${result.durationMs}ms",
                result.durationMs in DURATION_MIN_MS..DURATION_MAX_MS,
            )
            val measured = readDurationMs(output)
            assertTrue(
                "MediaMetadataRetriever 时长应约 1.5–2.1s，实际 ${measured}ms",
                measured in DURATION_MIN_MS..DURATION_MAX_MS,
            )

            // 成功后不应残留 concat.txt / 中间 WAV / 半成品。
            val workDir = File(cacheRoot, "stitch")
            assertTrue(
                "拼接临时目录应清空：${workDir.listFiles()?.toList()}",
                workDir.listFiles().isNullOrEmpty(),
            )
            assertTrue("静音缓存应保留", sil350.isFile && sil700.isFile)
        } finally {
            cacheRoot.deleteRecursively()
            storageRoot.deleteRecursively()
        }
    }

    @Test
    fun empty_segments_throws_illegal_argument() = runBlocking {
        val storageRoot = File(context.filesDir, "p210-stitch-empty").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val stitcher = ChapterStitcher(
                FfmpegKitRunner(),
                File(context.cacheDir, "p210-stitch-empty"),
            )
            val error = runCatching {
                stitcher.stitch(1, 0, emptyList(), emptyList(), AudioStorage(storageRoot))
            }.exceptionOrNull()

            assertTrue("空列表应抛 IAE，实际：$error", error is IllegalArgumentException)
        } finally {
            storageRoot.deleteRecursively()
        }
    }

    private suspend fun createTone(runner: FfmpegKitRunner, dir: File, index: Int): File {
        val output = File(dir, "tone_$index.mp3")
        runner.run(
            "-y -f lavfi -i \"sine=frequency=${440 + index * 110}:duration=0.3\" " +
                "-c:a libmp3lame -b:a 64k \"${output.absolutePath}\"",
        )
        assertTrue("正弦片段应非空：$output", output.isFile && output.length() > 0L)
        return output
    }

    private fun readDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
        } finally {
            retriever.release()
        }
    }

    private companion object {
        const val NOVEL_ID = 1L
        const val CHAPTER_IDX = 0
        const val DURATION_MIN_MS = 1_500L
        const val DURATION_MAX_MS = 2_100L
    }
}
