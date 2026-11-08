package com.estimatefaster.audioforge.core.tts

import com.estimatefaster.audioforge.core.audio.FfmpegException
import com.estimatefaster.audioforge.core.audio.FfmpegResult
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P2-09：pitch / volume 后处理（执行计划 §10.2）。
 *
 * 纯 JVM 测试：用 [AudioCommandRunner] 假实现记录命令并写出假 mp3，
 * 不启动 FFmpeg、不依赖 Android。
 */
class AudioPostProcessorTest {

    private lateinit var dir: File
    private lateinit var input: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("audio-post-").toFile()
        input = File(dir, "in.mp3").apply { writeBytes(RAW_BYTES) }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ---------- 纯函数 ----------

    @Test
    fun needs_processing_is_false_only_for_zero_pitch_and_unit_volume() {
        val processor = processor(RecordingRunner())

        assertFalse(processor.needsProcessing(0.0, 1.0))
        assertFalse("负零也应视为 0", processor.needsProcessing(-0.0, 1.0))
        assertTrue(processor.needsProcessing(0.5, 1.0))
        assertTrue(processor.needsProcessing(-2.0, 1.0))
        assertTrue(processor.needsProcessing(0.0, 1.01))
        assertTrue(processor.needsProcessing(0.0, 0.85))
    }

    @Test
    fun build_filter_is_exact_for_positive_pitch() {
        val processor = processor(RecordingRunner())

        assertEquals(
            "asetrate=44100*2^(2/12),aresample=44100,atempo=2^(-2/12),volume=0.8",
            processor.buildFilter(2.0, 0.8),
        )
    }

    @Test
    fun build_filter_negates_pitch_for_atempo() {
        val processor = processor(RecordingRunner())

        assertEquals(
            "asetrate=44100*2^(-3.5/12),aresample=44100,atempo=2^(3.5/12),volume=1.25",
            processor.buildFilter(-3.5, 1.25),
        )
    }

    @Test
    fun build_filter_uses_plain_decimal_format() {
        val processor = processor(RecordingRunner())

        // 0.0001 不能变成 1.0E-4；整数倍不能变成 1.0。
        assertEquals(
            "asetrate=44100*2^(0/12),aresample=44100,atempo=2^(0/12),volume=0.0001",
            processor.buildFilter(0.0, 0.0001),
        )
        assertEquals(
            "asetrate=44100*2^(12/12),aresample=44100,atempo=2^(-12/12),volume=1",
            processor.buildFilter(12.0, 1.0),
        )
    }

    @Test
    fun build_command_quotes_paths_and_uses_64k_bitrate() {
        val processor = processor(RecordingRunner())
        val output = File(dir, "out.mp3")

        assertEquals(
            "-y -i \"${input.absolutePath}\" -af \"f\" -b:a 64k \"${output.absolutePath}\"",
            processor.buildCommand(input, output, "f"),
        )
    }

    // ---------- process ----------

    @Test
    fun pitch_zero_volume_one_copies_without_running_ffmpeg() = runTest {
        val runner = RecordingRunner()
        val output = File(dir, "out.mp3")

        val ok = processor(runner, UnconfinedTestDispatcher(testScheduler))
            .process(input, output, pitch = 0.0, volume = 1.0)

        assertTrue(ok)
        assertTrue("无需处理时不应调用 ffmpeg", runner.commands.isEmpty())
        assertArrayEquals(RAW_BYTES, output.readBytes())
        assertArrayEquals("输入不能被修改", RAW_BYTES, input.readBytes())
    }

    @Test
    fun success_uses_full_filter_and_replaces_output() = runTest {
        val runner = RecordingRunner()
        val output = File(dir, "out.mp3")

        val ok = processor(runner, UnconfinedTestDispatcher(testScheduler))
            .process(input, output, pitch = 2.0, volume = 0.5)

        assertTrue(ok)
        assertEquals(1, runner.commands.size)
        val command = runner.commands.single()
        assertTrue(command.startsWith("-y -i \"${input.absolutePath}\""))
        assertTrue(
            command.contains(
                "-af \"asetrate=44100*2^(2/12),aresample=44100," +
                    "atempo=2^(-2/12),volume=0.5\"",
            ),
        )
        assertTrue(command.contains("-b:a 64k"))

        assertArrayEquals(FAKE_MP3, output.readBytes())
        assertArrayEquals(RAW_BYTES, input.readBytes())
        assertEquals("临时文件必须被 rename 掉", listOf("in.mp3", "out.mp3"), namesIn())
    }

    @Test
    fun failed_filter_falls_back_to_volume_only() = runTest {
        val runner = RecordingRunner(failFirstAttempt = true)
        val output = File(dir, "out.mp3")

        val ok = processor(runner, UnconfinedTestDispatcher(testScheduler))
            .process(input, output, pitch = 2.0, volume = 0.8)

        assertTrue("仅 volume 兜底应成功", ok)
        assertEquals(2, runner.commands.size)
        assertTrue(
            "第一次是完整滤镜",
            runner.commands[0].contains(
                "asetrate=44100*2^(2/12),aresample=44100,atempo=2^(-2/12),volume=0.8",
            ),
        )
        assertTrue(
            "兜底只用 volume（pitch=0）",
            runner.commands[1].contains(
                "asetrate=44100*2^(0/12),aresample=44100,atempo=2^(0/12),volume=0.8",
            ),
        )
        assertArrayEquals(FAKE_MP3, output.readBytes())
        assertArrayEquals(RAW_BYTES, input.readBytes())
        assertEquals(listOf("in.mp3", "out.mp3"), namesIn())
    }

    @Test
    fun total_failure_keeps_input_and_old_output_and_returns_false() = runTest {
        val runner = RecordingRunner(alwaysFail = true)
        val output = File(dir, "out.mp3").apply { writeBytes(OLD_OUTPUT) }

        val ok = processor(runner, UnconfinedTestDispatcher(testScheduler))
            .process(input, output, pitch = 2.0, volume = 0.8)

        assertFalse(ok)
        assertEquals("完整滤镜 + 仅 volume 兜底共两次", 2, runner.commands.size)
        assertArrayEquals("旧输出必须保留", OLD_OUTPUT, output.readBytes())
        assertArrayEquals("输入必须保留", RAW_BYTES, input.readBytes())
        assertEquals("不能留下临时文件", listOf("in.mp3", "out.mp3"), namesIn())
    }

    @Test
    fun missing_input_returns_false_without_running_ffmpeg() = runTest {
        val runner = RecordingRunner()

        val ok = processor(runner, UnconfinedTestDispatcher(testScheduler))
            .process(File(dir, "missing.mp3"), File(dir, "out.mp3"), 2.0, 0.8)

        assertFalse(ok)
        assertTrue(runner.commands.isEmpty())
        assertFalse(File(dir, "out.mp3").exists())
    }

    @Test
    fun empty_ffmpeg_output_is_a_failure() = runTest {
        val runner = RecordingRunner(writeEmptyOutput = true)

        val ok = processor(runner, UnconfinedTestDispatcher(testScheduler))
            .process(input, File(dir, "out.mp3"), 2.0, 0.8)

        assertFalse(ok)
        assertEquals(2, runner.commands.size)
        assertEquals(listOf("in.mp3"), namesIn())
    }

    private fun processor(
        runner: AudioCommandRunner,
        dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
    ): AudioPostProcessor = AudioPostProcessor(runner, dispatcher)

    private fun namesIn(): List<String> = dir.listFiles().orEmpty().map { it.name }.sorted()

    /** 记录命令；成功时把假 mp3 写到命令末尾引号里的输出路径。 */
    private class RecordingRunner(
        private val failFirstAttempt: Boolean = false,
        private val alwaysFail: Boolean = false,
        private val writeEmptyOutput: Boolean = false,
    ) : AudioCommandRunner {

        val commands = mutableListOf<String>()

        override suspend fun run(command: String): FfmpegResult {
            commands += command
            if (alwaysFail || (failFirstAttempt && commands.size == 1)) {
                throw FfmpegException(returnCode = 1, log = "boom")
            }
            val output = File(command.split('"').last { it.isNotBlank() })
            output.writeBytes(if (writeEmptyOutput) ByteArray(0) else FAKE_MP3)
            return FfmpegResult(sessionId = commands.size.toLong(), output = "")
        }
    }
}

private val RAW_BYTES = byteArrayOf(0x49, 0x44, 0x33, 0x01)
private val FAKE_MP3 = byteArrayOf(0x49, 0x44, 0x33, 0x02, 0x03)
private val OLD_OUTPUT = byteArrayOf(0x6F, 0x6C, 0x64)
