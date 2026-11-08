package com.estimatefaster.audioforge.domain.pipeline

import com.estimatefaster.audioforge.core.audio.ChapterStitcher
import com.estimatefaster.audioforge.core.audio.FfmpegKitRunner
import com.estimatefaster.audioforge.core.audio.StitchResult
import com.estimatefaster.audioforge.core.model.Emotion
import com.estimatefaster.audioforge.core.model.NARRATOR_ROLE_ID
import com.estimatefaster.audioforge.core.model.PipelineError
import com.estimatefaster.audioforge.core.model.SegmentType
import com.estimatefaster.audioforge.core.storage.AudioStorage
import com.estimatefaster.audioforge.core.tts.AudioCommandRunner
import com.estimatefaster.audioforge.core.tts.AudioPostProcessor
import com.estimatefaster.audioforge.core.tts.TtsCacheInput
import com.estimatefaster.audioforge.core.tts.TtsCacheKey
import com.estimatefaster.audioforge.core.tts.TtsResult
import com.estimatefaster.audioforge.core.tts.TtsSpec
import com.estimatefaster.audioforge.core.tts.TtsSynthesizer
import com.estimatefaster.audioforge.core.tts.TtsTransport
import com.estimatefaster.audioforge.core.voice.VoicePool
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.data.db.SegmentEntity
import com.estimatefaster.audioforge.data.repository.ChapterInput
import com.estimatefaster.audioforge.data.repository.CharacterRepository
import com.estimatefaster.audioforge.data.repository.RepositoryFixture
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P2-12：渲染用例的核心语义（执行计划 §8.9 / §10.3）。
 *
 * Robolectric + in-memory Room + 临时 AudioStorage；TTS 与拼接通过构造参数注入 fake
 * （`TtsSynthesizer` / `ChapterStitcher` 是 final 且依赖网络/FFmpegKit，真实实例只占位）。
 * 因此测的是「片段规划 → 缓存判定 → worker 池 → 落库 → 拼接」这条用例链路。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RenderChapterUseCaseTest {

    private lateinit var fixture: RepositoryFixture
    private lateinit var pool: VoicePool
    private lateinit var stitchCacheDir: File

    @Before
    fun setUp() {
        fixture = RepositoryFixture()
        pool = VoicePool.parse(readResource("voice-pool.json"))
        stitchCacheDir = Files.createTempDirectory("render-stitch-").toFile()
    }

    @After
    fun tearDown() {
        fixture.close()
        stitchCacheDir.deleteRecursively()
    }

    // ------------------------------------------------------------ 空章节

    @Test
    fun empty_chapter_returns_no_segments_without_touching_tts_or_ffmpeg() = runTest {
        val scene = createScene()
        val tts = FakeTts(fixture.audioStorage)
        val stitch = FakeStitch(fixture.audioStorage)

        val result = newUseCase(tts, stitch).render(scene.novelId, scene.chapterId)

        assertEquals(RenderStatus.NoSegments, result.status)
        assertEquals(0, result.cachedCount)
        assertEquals(0, result.synthesizedCount)
        assertEquals(0, result.failedCount)
        assertNull(result.outputRelative)
        assertTrue("空章节不应调用 TTS", tts.calls.isEmpty())
        assertTrue("空章节不应调用拼接", stitch.calls.isEmpty())
        assertEquals(PipelineError.NO_SEGMENTS, result.errors.single().code)

        val chapter = requireNotNull(fixture.chapters.getChapter(scene.chapterId))
        assertEquals("pending", chapter.audioStatus)
        assertEquals(0, chapter.segDone)
    }

    // ------------------------------------------------------------ 缓存

    @Test
    fun cache_hit_does_not_call_synthesizer_and_still_stitches() = runTest {
        val scene = createScene()
        val tts = FakeTts(fixture.audioStorage)
        val stitch = FakeStitch(fixture.audioStorage)

        val relative = fixture.audioStorage.segmentAudioRelative(scene.novelId, CHAPTER_IDX, 0)
        fixture.audioStorage.writeBytes(relative, FAKE_MP3)
        fixture.segments.replace(
            scene.chapterId,
            listOf(
                segment(
                    seq = 0,
                    text = "缓存段",
                    status = "done",
                    audioRelPath = relative,
                    ttsKey = keyFor("缓存段"),
                ),
            ),
        )

        val result = newUseCase(tts, stitch).render(scene.novelId, scene.chapterId)

        assertEquals(RenderStatus.Done, result.status)
        assertEquals(1, result.cachedCount)
        assertEquals(0, result.synthesizedCount)
        assertTrue("缓存命中不应调用合成器", tts.calls.isEmpty())
        assertEquals(1, stitch.calls.size)
        assertEquals(
            listOf(relative),
            stitch.calls.single().files.map { fixture.audioStorage.relativeOf(it) },
        )
        assertEquals(fixture.audioStorage.chapterAudioRelative(scene.novelId, CHAPTER_IDX), result.outputRelative)
        assertEquals("done", requireNotNull(fixture.chapters.getChapter(scene.chapterId)).audioStatus)
    }

    @Test
    fun stale_tts_key_forces_resynthesis() = runTest {
        val scene = createScene()
        val tts = FakeTts(fixture.audioStorage)
        val stitch = FakeStitch(fixture.audioStorage)

        val relative = fixture.audioStorage.segmentAudioRelative(scene.novelId, CHAPTER_IDX, 0)
        fixture.audioStorage.writeBytes(relative, FAKE_MP3)
        fixture.segments.replace(
            scene.chapterId,
            listOf(
                segment(
                    seq = 0,
                    text = "旧键段",
                    status = "done",
                    audioRelPath = relative,
                    ttsKey = "v2|deadbeef0000",
                ),
            ),
        )

        val result = newUseCase(tts, stitch).render(scene.novelId, scene.chapterId)

        assertEquals(RenderStatus.Done, result.status)
        assertEquals(0, result.cachedCount)
        assertEquals(1, result.synthesizedCount)
        assertEquals(1, tts.calls.size)
        assertEquals(keyFor("旧键段"), requireNotNull(fixture.segments.list(scene.chapterId).single().ttsKey))
    }

    // ------------------------------------------------------------ 角色音色

    @Test
    fun character_voice_and_speed_override_global_defaults() = runTest {
        val scene = createScene()
        fixture.characters.upsert(
            CharacterEntity(
                novelId = scene.novelId,
                roleId = "role_0001",
                name = "韩立",
                voiceId = "voice-custom",
                speed = 0.9,
            ),
        )
        val tts = FakeTts(fixture.audioStorage)
        val stitch = FakeStitch(fixture.audioStorage)
        fixture.segments.replace(
            scene.chapterId,
            listOf(
                segment(
                    seq = 0,
                    text = "台词",
                    type = SegmentType.Dialogue,
                    roleId = "role_0001",
                ),
            ),
        )

        val result = newUseCase(tts, stitch, characterRepository = fixture.characters)
            .render(scene.novelId, scene.chapterId)

        assertEquals(RenderStatus.Done, result.status)
        val spec = tts.calls.single()
        assertEquals("角色锁定音色必须优先", "voice-custom", spec.voiceId)
        assertEquals("角色锁定语速必须优先", 0.9, spec.speed, 1e-9)
        assertEquals(
            keyFor("台词", voiceId = "voice-custom", speed = 0.9),
            requireNotNull(fixture.segments.list(scene.chapterId).single().ttsKey),
        )
    }

    // ------------------------------------------------------------ 并行 / 拼接

    @Test
    fun three_segments_run_in_parallel_and_stitch_once_in_seq_order() = runTest {
        val scene = createScene()
        val tts = FakeTts(fixture.audioStorage, gateAfter = 3)
        val stitch = FakeStitch(fixture.audioStorage)
        fixture.segments.replace(
            scene.chapterId,
            (0..2).map { seq -> segment(seq = seq, text = "第${seq}段") },
        )

        val result = newUseCase(tts, stitch, ttsConcurrency = 4)
            .render(scene.novelId, scene.chapterId)

        assertEquals(RenderStatus.Done, result.status)
        assertEquals(0, result.cachedCount)
        assertEquals(3, result.synthesizedCount)
        assertEquals(0, result.failedCount)
        assertEquals("3 段并发 4 应同时跑满 3 个 worker", 3, tts.maxActive.get())
        assertEquals(3, tts.calls.size)
        assertEquals(
            setOf("第0段", "第1段", "第2段"),
            tts.calls.map { it.text }.toSet(),
        )

        assertEquals(1, stitch.calls.size)
        val call = stitch.calls.single()
        val expectedFiles = (0..2).map {
            fixture.audioStorage.segmentAudioRelative(scene.novelId, CHAPTER_IDX, it)
        }
        assertEquals(expectedFiles, call.files.map { fixture.audioStorage.relativeOf(it) })
        assertEquals(
            listOf(SegmentType.Narration, SegmentType.Narration, SegmentType.Narration),
            call.types,
        )

        val stored = fixture.segments.list(scene.chapterId)
        assertEquals(listOf(0, 1, 2), stored.map { it.seq })
        assertTrue("全部片段应 done", stored.all { it.status == "done" })
        stored.forEach { segment ->
            assertEquals(
                fixture.audioStorage.segmentAudioRelative(scene.novelId, CHAPTER_IDX, segment.seq),
                segment.audioRelPath,
            )
            assertEquals(keyFor(segment.text), segment.ttsKey)
            assertEquals(1_000, requireNotNull(segment.durationMs))
            assertNull(segment.errorMsg)
        }

        val chapter = requireNotNull(fixture.chapters.getChapter(scene.chapterId))
        assertEquals("done", chapter.audioStatus)
        assertNull(chapter.audioError)
        assertEquals(3, chapter.segDone)

        assertEquals(fixture.audioStorage.chapterAudioRelative(scene.novelId, CHAPTER_IDX), result.outputRelative)
        assertEquals(STITCHED_DURATION_MS, result.durationMs)
    }

    // ------------------------------------------------------------ 失败

    @Test
    fun single_failure_marks_segment_and_chapter_error_but_keeps_others() = runTest {
        val scene = createScene()
        val tts = FakeTts(fixture.audioStorage, gateAfter = 3).apply { failOnText = "失败段" }
        val stitch = FakeStitch(fixture.audioStorage)
        fixture.segments.replace(
            scene.chapterId,
            listOf(
                segment(seq = 0, text = "正常0"),
                segment(seq = 1, text = "失败段"),
                segment(seq = 2, text = "正常2"),
            ),
        )

        val result = newUseCase(tts, stitch).render(scene.novelId, scene.chapterId)

        assertEquals(RenderStatus.Partial, result.status)
        assertEquals(1, result.failedCount)
        assertEquals(2, result.synthesizedCount)
        assertEquals(0, result.cachedCount)
        assertNull("有失败时不拼接", result.outputRelative)
        assertTrue("有失败时不应调用拼接", stitch.calls.isEmpty())

        val stored = fixture.segments.list(scene.chapterId)
        assertEquals(listOf("done", "error", "done"), stored.map { it.status })
        val failed = stored[1]
        assertNotNull(failed.errorMsg)
        assertTrue(
            "错误文案应包含失败原因：${failed.errorMsg}",
            requireNotNull(failed.errorMsg).contains("模拟失败"),
        )
        assertTrue("失败段不应保留为 done", failed.ttsKey == null || failed.audioRelPath == null)

        val chapter = requireNotNull(fixture.chapters.getChapter(scene.chapterId))
        assertEquals("error", chapter.audioStatus)
        assertNotNull(chapter.audioError)
        assertEquals(2, chapter.segDone)

        val error = result.errors.single()
        assertEquals("TTS_FAILED", error.code)
        assertEquals(1, error.segmentSeq)
        assertEquals(failed.id, error.segmentId)
    }

    @Test
    fun stitch_failure_marks_chapter_error_but_keeps_segments_done() = runTest {
        val scene = createScene()
        val tts = FakeTts(fixture.audioStorage)
        val stitch = FakeStitch(fixture.audioStorage).apply {
            failure = PipelineError.Ffmpeg(detail = "模拟拼接失败")
        }
        fixture.segments.replace(scene.chapterId, listOf(segment(seq = 0, text = "唯一段")))

        val result = newUseCase(tts, stitch).render(scene.novelId, scene.chapterId)

        assertEquals(RenderStatus.Error, result.status)
        assertEquals(1, result.synthesizedCount)
        assertEquals(0, result.failedCount)
        assertNull(result.outputRelative)
        assertEquals("FFMPEG", result.errors.single().code)

        val stored = fixture.segments.list(scene.chapterId)
        assertEquals("done", stored.single().status)

        val chapter = requireNotNull(fixture.chapters.getChapter(scene.chapterId))
        assertEquals("error", chapter.audioStatus)
        assertNotNull(chapter.audioError)
    }

    // ------------------------------------------------------------ 停止

    @Test
    fun stop_after_first_segment_keeps_remaining_segments_pending() = runTest {
        val scene = createScene()
        val tts = FakeTts(fixture.audioStorage)
        val stitch = FakeStitch(fixture.audioStorage)
        fixture.segments.replace(
            scene.chapterId,
            (0..2).map { seq -> segment(seq = seq, text = "第${seq}段") },
        )

        val result = newUseCase(tts, stitch, ttsConcurrency = 1)
            .render(scene.novelId, scene.chapterId, shouldStop = { tts.startedCount.get() > 0 })

        assertEquals(RenderStatus.Stopped, result.status)
        assertEquals(1, result.synthesizedCount)
        assertEquals(0, result.failedCount)
        assertEquals(1, tts.calls.size)
        assertTrue("停止后不应拼接", stitch.calls.isEmpty())

        val stored = fixture.segments.list(scene.chapterId)
        assertEquals(listOf("done", "pending", "pending"), stored.map { it.status })

        val chapter = requireNotNull(fixture.chapters.getChapter(scene.chapterId))
        assertEquals(1, chapter.segDone)
        assertEquals("pending", chapter.audioStatus)
        assertNull("停止不是错误", chapter.audioError)
    }

    @Test
    fun stop_before_start_renders_nothing_and_preserves_statuses() = runTest {
        val scene = createScene()
        val tts = FakeTts(fixture.audioStorage)
        val stitch = FakeStitch(fixture.audioStorage)
        fixture.segments.replace(
            scene.chapterId,
            (0..1).map { seq -> segment(seq = seq, text = "第${seq}段") },
        )

        val result = newUseCase(tts, stitch).render(
            scene.novelId,
            scene.chapterId,
            shouldStop = { true },
        )

        assertEquals(RenderStatus.Stopped, result.status)
        assertEquals(0, result.synthesizedCount)
        assertEquals(0, result.cachedCount)
        assertTrue(tts.calls.isEmpty())
        assertTrue(stitch.calls.isEmpty())
        assertEquals(listOf("pending", "pending"), fixture.segments.list(scene.chapterId).map { it.status })
        assertEquals("pending", requireNotNull(fixture.chapters.getChapter(scene.chapterId)).audioStatus)
    }

    // ------------------------------------------------------------ helpers

    private fun newUseCase(
        tts: FakeTts,
        stitch: FakeStitch,
        ttsConcurrency: Int = RenderChapterUseCase.DEFAULT_TTS_CONCURRENCY,
        characterRepository: CharacterRepository? = null,
    ): RenderChapterUseCase = RenderChapterUseCase(
        segmentRepository = fixture.segments,
        chapterRepository = fixture.chapters,
        db = fixture.db,
        audioStorage = fixture.audioStorage,
        ttsSynthesizer = dummySynthesizer(),
        chapterStitcher = dummyStitcher(),
        voicePool = pool,
        characterRepository = characterRepository,
        dispatcher = Dispatchers.Unconfined,
        ttsConcurrency = ttsConcurrency,
        durationReader = { 1_000L },
        tts = { spec -> tts.synthesize(spec) },
        stitch = { novelId, chapterIdx, files, types ->
            stitch.stitch(novelId, chapterIdx, files, types)
        },
    )

    /** 真实实例只用于满足构造签名；所有调用都走注入的 fake。 */
    private fun dummySynthesizer(): TtsSynthesizer = TtsSynthesizer(
        transport = object : TtsTransport {
            override suspend fun synthesize(text: String, voiceId: String, speed: Double?): ByteArray =
                error("测试不应调用真实 TTS transport")
        },
        postProcessor = AudioPostProcessor(
            runner = object : AudioCommandRunner {
                override suspend fun run(command: String) = error("测试不应调用真实 ffmpeg")
            },
            io = Dispatchers.Unconfined,
        ),
        storage = fixture.audioStorage,
        io = Dispatchers.Unconfined,
    )

    private fun dummyStitcher(): ChapterStitcher = ChapterStitcher(
        runner = FfmpegKitRunner(),
        cacheDir = stitchCacheDir,
        durationReader = { 0L },
        io = Dispatchers.Unconfined,
    )

    private suspend fun createScene(): Scene {
        val novelId = fixture.projects.createProject(
            title = "渲染测试",
            sourceName = "render.txt",
            sourceRelPath = "imports/render.txt",
            chapters = listOf(ChapterInput(title = "第一章", rawText = "第一章正文")),
        )
        val chapterId = fixture.chapters.observeBriefs(novelId).first().single().id
        return Scene(novelId = novelId, chapterId = chapterId)
    }

    private fun segment(
        seq: Int,
        text: String,
        type: SegmentType = SegmentType.Narration,
        roleId: String = NARRATOR_ROLE_ID,
        status: String = "pending",
        audioRelPath: String? = null,
        ttsKey: String? = null,
    ): SegmentEntity = SegmentEntity(
        chapterId = 0,
        seq = seq,
        type = type.wire,
        roleId = roleId,
        emotion = Emotion.Neutral.wire,
        text = text,
        status = status,
        audioRelPath = audioRelPath,
        ttsKey = ttsKey,
    )

    private fun keyFor(
        text: String,
        emotion: Emotion = Emotion.Neutral,
        voiceId: String = requireNotNull(
            pool.voiceIdFor(RenderChapterUseCase.DEFAULT_VOICE_CATEGORY),
        ),
        speed: Double = RenderChapterUseCase.DEFAULT_SPEED,
        volume: Double = RenderChapterUseCase.DEFAULT_VOLUME,
        pitch: Double = RenderChapterUseCase.DEFAULT_PITCH,
        model: String = RenderChapterUseCase.DEFAULT_TTS_MODEL,
    ): String = TtsCacheKey.v2(
        TtsCacheInput(
            text = text,
            emotion = emotion,
            voiceId = voiceId,
            speed = speed,
            volume = volume,
            pitch = pitch,
            model = model,
        ),
    )

    private fun readResource(name: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream(name),
    ) { "缺少测试资源 $name" }
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }

    private data class Scene(val novelId: Long, val chapterId: Long)

    /** fake TTS：记录调用/并发峰值，按文本模拟失败；成功时真的写目标文件。 */
    private class FakeTts(
        private val storage: AudioStorage,
        /**
         * 同时进入 synthesize 的调用数达到该值后统一放行。
         * 用 `CompletableDeferred` 而不是 `delay`，避免 runTest 虚拟时间提前推进导致并发峰值不稳定。
         */
        private val gateAfter: Int = 1,
    ) {
        val calls: MutableList<TtsSpec> = Collections.synchronizedList(mutableListOf<TtsSpec>())
        val startedCount = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        private val active = AtomicInteger(0)
        private val release = CompletableDeferred<Unit>()

        @Volatile
        var failOnText: String? = null

        suspend fun synthesize(spec: TtsSpec): TtsResult {
            calls += spec
            startedCount.incrementAndGet()
            val now = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, now) }
            try {
                if (now >= gateAfter) release.complete(Unit)
                release.await()
                if (spec.text == failOnText) {
                    return TtsResult.Failed(
                        PipelineError.TtsFailed(
                            segmentIndex = spec.segmentIndex,
                            detail = "模拟失败",
                        ),
                    )
                }
                storage.writeBytes(spec.outputRelative, FAKE_MP3)
                return TtsResult.Synthesized(spec.outputRelative)
            } finally {
                active.decrementAndGet()
            }
        }
    }

    /** fake 拼接：记录入参并返回固定成品路径；可注入失败。 */
    private class FakeStitch(private val storage: AudioStorage) {

        data class Call(
            val novelId: Long,
            val chapterIdx: Int,
            val files: List<File>,
            val types: List<SegmentType>,
        )

        val calls = mutableListOf<Call>()
        var failure: Exception? = null

        suspend fun stitch(
            novelId: Long,
            chapterIdx: Int,
            files: List<File>,
            types: List<SegmentType>,
        ): StitchResult {
            calls += Call(novelId, chapterIdx, files.toList(), types.toList())
            failure?.let { throw it }
            return StitchResult(
                relativePath = storage.chapterAudioRelative(novelId, chapterIdx),
                durationMs = STITCHED_DURATION_MS,
            )
        }
    }

    private companion object {
        const val CHAPTER_IDX = 0
        const val STITCHED_DURATION_MS = 12_345L
    }
}

/** 伪 mp3 字节：只要非空即可，真实解码不在本用例范围内。 */
private val FAKE_MP3 = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x00, 0x00)
