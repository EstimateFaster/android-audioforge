package com.estimatefaster.audioforge.core.tts

import com.estimatefaster.audioforge.core.audio.FfmpegResult
import com.estimatefaster.audioforge.core.model.Emotion
import com.estimatefaster.audioforge.core.model.PipelineError
import com.estimatefaster.audioforge.core.storage.AudioStorage
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P2-09：TtsSynthesizer 的缓存 / 临时文件 / 失败 / 取消语义（执行计划 §8.8、§8.9）。
 *
 * 纯 JVM：fake [TtsTransport] + fake [AudioPostProcessor] + 临时目录，
 * 不联网、不启动 FFmpeg、不依赖 Android。
 */
class TtsSynthesizerTest {

    private lateinit var root: File
    private lateinit var storage: AudioStorage
    private lateinit var transport: RecordingTransport
    private lateinit var postProcessor: RecordingPostProcessor

    private val segmentDir: File get() = File(root, "audio/1/segments/ch_001")

    @Before
    fun setUp() {
        root = Files.createTempDirectory("tts-synth-").toFile()
        storage = AudioStorage(root)
        transport = RecordingTransport()
        postProcessor = RecordingPostProcessor()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ---------- 缓存 ----------

    @Test
    fun cache_hit_returns_cached_without_calling_transport() = runTest {
        val spec = spec(emotion = Emotion.Sad, pitch = 2.0, volume = 0.8)
        storage.writeBytes(spec.outputRelative, OLD_AUDIO)
        storage.writeBytes(
            storage.hashRelativeTo(spec.outputRelative),
            keyOf(spec).toByteArray(Charsets.UTF_8),
        )

        val result = synthesizer(UnconfinedTestDispatcher(testScheduler)).synthesize(spec)

        assertEquals(TtsResult.Cached(spec.outputRelative), result)
        assertTrue("缓存命中不应调用 transport", transport.calls.isEmpty())
        assertTrue("缓存命中不应后处理", postProcessor.calls.isEmpty())
    }

    @Test
    fun stale_hash_forces_resynthesis() = runTest {
        val spec = spec()
        storage.writeBytes(spec.outputRelative, OLD_AUDIO)
        storage.writeBytes(
            storage.hashRelativeTo(spec.outputRelative),
            "v2|deadbeef0000".toByteArray(Charsets.UTF_8),
        )

        val result = synthesizer(UnconfinedTestDispatcher(testScheduler)).synthesize(spec)

        assertEquals(TtsResult.Synthesized(spec.outputRelative), result)
        assertEquals(1, transport.calls.size)
        assertEquals(
            keyOf(spec),
            storage.resolve(storage.hashRelativeTo(spec.outputRelative))
                .readText(Charsets.UTF_8),
        )
    }

    @Test
    fun cache_miss_writes_audio_and_matching_hash() = runTest {
        val spec = spec()

        val result = synthesizer(UnconfinedTestDispatcher(testScheduler)).synthesize(spec)

        assertEquals(TtsResult.Synthesized(spec.outputRelative), result)
        val target = storage.resolve(spec.outputRelative)
        assertArrayEquals("目标音频应为 transport 返回的字节", FAKE_MP3, target.readBytes())
        assertEquals(
            "hash 必须等于 §8.8 的 v2 键",
            keyOf(spec),
            storage.resolve(storage.hashRelativeTo(spec.outputRelative))
                .readText(Charsets.UTF_8),
        )
        assertTrue("键必须是 v2 格式", keyOf(spec).startsWith("v2|"))
        assertEquals(
            "不能残留 .raw/.post 临时文件",
            listOf("seg_000001.hash", "seg_000001.mp3"),
            namesIn(segmentDir),
        )
    }

    // ---------- transport 输入 ----------

    @Test
    fun emotion_prefix_is_applied_only_for_transport() = runTest {
        val cases = listOf(
            Emotion.Neutral to "原文",
            Emotion.Happy to "[happy]原文",
            Emotion.SoftTone to "[soft tone]原文",
            Emotion.Whispering to "[whispering]原文",
        )
        val synth = synthesizer(UnconfinedTestDispatcher(testScheduler))

        cases.forEachIndexed { index, (emotion, expected) ->
            val spec = spec(
                text = "原文",
                emotion = emotion,
                outputRelative = "audio/1/segments/ch_001/seg_" +
                    (index + 1).toString().padStart(6, '0') + ".mp3",
            )
            assertEquals(TtsResult.Synthesized(spec.outputRelative), synth.synthesize(spec))
            assertEquals(expected, transport.calls.last().text)
            assertEquals("voice-1", transport.calls.last().voiceId)
            assertEquals(1.15, transport.calls.last().speed!!, 0.0)
        }
    }

    // ---------- 失败 ----------

    @Test
    fun tts_failure_uses_real_segment_index_and_keeps_old_target() = runTest {
        val spec = spec(segmentIndex = 9)
        storage.writeBytes(spec.outputRelative, OLD_AUDIO)
        transport.respond = { _, _, _ ->
            throw PipelineError.TtsFailed(segmentIndex = 0, detail = "boom")
        }

        val result = synthesizer(UnconfinedTestDispatcher(testScheduler)).synthesize(spec)

        val error = (result as TtsResult.Failed).error as PipelineError.TtsFailed
        assertEquals("必须使用真实段号，而不是 transport 的 0", 9, error.segmentIndex)
        assertEquals("boom", error.detail)
        assertArrayEquals(
            "旧目标必须保留",
            OLD_AUDIO,
            storage.resolve(spec.outputRelative).readBytes(),
        )
        assertEquals("不能残留临时文件", listOf("seg_000001.mp3"), namesIn(segmentDir))
    }

    @Test
    fun io_failure_is_wrapped_as_tts_failed_without_leaving_temp_files() = runTest {
        val spec = spec(segmentIndex = 4)
        transport.respond = { _, _, _ -> throw IOException("read timed out") }

        val result = synthesizer(UnconfinedTestDispatcher(testScheduler)).synthesize(spec)

        val error = (result as TtsResult.Failed).error as PipelineError.TtsFailed
        assertEquals(4, error.segmentIndex)
        assertTrue(
            "错误详情应保留底层信息：${error.detail}",
            error.detail.contains("read timed out"),
        )
        assertFalse(storage.resolve(spec.outputRelative).exists())
        assertTrue(namesIn(segmentDir).isEmpty())
    }

    @Test
    fun other_pipeline_errors_keep_their_code() = runTest {
        val spec = spec()
        transport.respond = { _, _, _ -> throw PipelineError.Network(IOException("offline")) }

        val result = synthesizer(UnconfinedTestDispatcher(testScheduler)).synthesize(spec)

        val error = (result as TtsResult.Failed).error
        assertTrue(error is PipelineError.Network)
        assertEquals(PipelineError.NETWORK, error.code)
        assertTrue(error.retryable)
    }

    @Test
    fun post_processing_failure_returns_failed_and_keeps_old_target() = runTest {
        val spec = spec(pitch = 2.0, volume = 0.8, segmentIndex = 6)
        storage.writeBytes(spec.outputRelative, OLD_AUDIO)
        postProcessor.onProcess = { _, _, _, _ -> false }

        val result = synthesizer(UnconfinedTestDispatcher(testScheduler)).synthesize(spec)

        val error = (result as TtsResult.Failed).error as PipelineError.TtsFailed
        assertEquals(6, error.segmentIndex)
        assertTrue("错误详情应说明后处理失败：${error.detail}", error.detail.contains("后处理"))
        assertArrayEquals(OLD_AUDIO, storage.resolve(spec.outputRelative).readBytes())
        assertEquals(listOf("seg_000001.mp3"), namesIn(segmentDir))
    }

    @Test
    fun invalid_output_path_fails_before_calling_transport() = runTest {
        val spec = spec(outputRelative = "../escape.mp3")

        val result = synthesizer(UnconfinedTestDispatcher(testScheduler)).synthesize(spec)

        assertTrue(result is TtsResult.Failed)
        assertTrue(transport.calls.isEmpty())
    }

    // ---------- 后处理 ----------

    @Test
    fun zero_pitch_and_unit_volume_skip_post_processing() = runTest {
        val spec = spec(pitch = 0.0, volume = 1.0)

        val result = synthesizer(UnconfinedTestDispatcher(testScheduler)).synthesize(spec)

        assertEquals(TtsResult.Synthesized(spec.outputRelative), result)
        assertTrue("无需后处理时不应调用 AudioPostProcessor", postProcessor.calls.isEmpty())
        assertEquals(
            listOf("seg_000001.hash", "seg_000001.mp3"),
            namesIn(segmentDir),
        )
    }

    @Test
    fun pitch_and_volume_are_post_processed_before_rename() = runTest {
        val spec = spec(pitch = 2.0, volume = 0.8)

        val result = synthesizer(UnconfinedTestDispatcher(testScheduler)).synthesize(spec)

        assertEquals(TtsResult.Synthesized(spec.outputRelative), result)
        val call = postProcessor.calls.single()
        assertEquals(2.0, call.pitch, 0.0)
        assertEquals(0.8, call.volume, 0.0)
        assertEquals(File(segmentDir, "seg_000001.raw.mp3"), call.input)
        assertEquals(File(segmentDir, "seg_000001.post.mp3"), call.output)
        assertEquals(
            "post 文件应被 rename 成目标，不留临时文件",
            listOf("seg_000001.hash", "seg_000001.mp3"),
            namesIn(segmentDir),
        )
    }

    // ---------- 取消 ----------

    @Test
    fun cancellation_during_transport_rethrows_and_keeps_old_target() = runTest {
        val spec = spec()
        storage.writeBytes(spec.outputRelative, OLD_AUDIO)
        transport.respond = { _, _, _ -> awaitCancellation() }

        var caught: Throwable? = null
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                synthesizer(UnconfinedTestDispatcher(testScheduler)).synthesize(spec)
            } catch (t: Throwable) {
                caught = t
                throw t
            }
        }
        runCurrent()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue("必须原样抛出 CancellationException", caught is CancellationException)
        assertArrayEquals(OLD_AUDIO, storage.resolve(spec.outputRelative).readBytes())
        assertEquals(listOf("seg_000001.mp3"), namesIn(segmentDir))
    }

    @Test
    fun cancellation_during_post_processing_cleans_temp_files() = runTest {
        val spec = spec(pitch = 2.0, volume = 0.8)
        storage.writeBytes(spec.outputRelative, OLD_AUDIO)
        postProcessor.onProcess = { _, _, _, _ -> awaitCancellation() }

        var caught: Throwable? = null
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                synthesizer(UnconfinedTestDispatcher(testScheduler)).synthesize(spec)
            } catch (t: Throwable) {
                caught = t
                throw t
            }
        }
        runCurrent()
        assertTrue("取消前 raw 临时文件应已写出", File(segmentDir, "seg_000001.raw.mp3").isFile)

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(caught is CancellationException)
        assertArrayEquals(OLD_AUDIO, storage.resolve(spec.outputRelative).readBytes())
        assertEquals(
            "取消后不能留下 .raw/.post 临时文件",
            listOf("seg_000001.mp3"),
            namesIn(segmentDir),
        )
    }

    // ---------- 工具 ----------

    private fun synthesizer(io: CoroutineDispatcher): TtsSynthesizer =
        TtsSynthesizer(transport, postProcessor, storage, io)

    private fun spec(
        text: String = "他轻轻一笑。",
        emotion: Emotion = Emotion.Happy,
        speed: Double = 1.15,
        volume: Double = 1.0,
        pitch: Double = 0.0,
        segmentIndex: Int = 3,
        outputRelative: String = DEFAULT_SEGMENT_RELATIVE,
    ): TtsSpec = TtsSpec(
        text = text,
        emotion = emotion,
        voiceId = "voice-1",
        speed = speed,
        volume = volume,
        pitch = pitch,
        model = "s2.1-pro-free",
        outputRelative = outputRelative,
        segmentIndex = segmentIndex,
    )

    /** 与 [TtsSynthesizer] 独立地按 §8.8 计算期望键，确保缓存键口径一致。 */
    private fun keyOf(spec: TtsSpec): String = TtsCacheKey.v2(
        TtsCacheInput(
            text = spec.text,
            emotion = spec.emotion,
            voiceId = spec.voiceId,
            speed = spec.speed,
            volume = spec.volume,
            pitch = spec.pitch,
            model = spec.model,
        ),
    )

    private fun namesIn(dir: File): List<String> =
        if (!dir.isDirectory) emptyList() else dir.listFiles().orEmpty().map { it.name }.sorted()

    private class RecordingTransport(
        var respond: suspend (String, String, Double?) -> ByteArray = { _, _, _ -> FAKE_MP3 },
    ) : TtsTransport {

        data class Call(val text: String, val voiceId: String, val speed: Double?)

        val calls = mutableListOf<Call>()

        override suspend fun synthesize(text: String, voiceId: String, speed: Double?): ByteArray {
            calls += Call(text = text, voiceId = voiceId, speed = speed)
            return respond(text, voiceId, speed)
        }
    }

    /** 只覆盖判定/处理逻辑，绝不调用真实 ffmpeg。 */
    private class RecordingPostProcessor(
        var onProcess: suspend (File, File, Double, Double) -> Boolean =
            { input, output, _, _ ->
                output.writeBytes(input.readBytes())
                true
            },
    ) : AudioPostProcessor(NoFfmpegRunner, Dispatchers.Unconfined) {

        data class Call(
            val input: File,
            val output: File,
            val pitch: Double,
            val volume: Double,
        )

        val calls = mutableListOf<Call>()

        override fun needsProcessing(pitch: Double, volume: Double): Boolean =
            pitch != 0.0 || volume != 1.0

        override suspend fun process(
            input: File,
            output: File,
            pitch: Double,
            volume: Double,
        ): Boolean {
            calls += Call(input = input, output = output, pitch = pitch, volume = volume)
            return onProcess(input, output, pitch, volume)
        }
    }
}

private object NoFfmpegRunner : AudioCommandRunner {
    override suspend fun run(command: String): FfmpegResult =
        error("单元测试不应执行真实 ffmpeg：$command")
}

private val FAKE_MP3 = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x05, 0x06)
private val OLD_AUDIO = byteArrayOf(0x6F, 0x6C, 0x64)
private const val DEFAULT_SEGMENT_RELATIVE = "audio/1/segments/ch_001/seg_000001.mp3"
