package com.estimatefaster.audioforge.domain.pipeline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.estimatefaster.audioforge.core.audio.ChapterStitcher
import com.estimatefaster.audioforge.core.audio.FfmpegKitRunner
import com.estimatefaster.audioforge.core.audio.FfmpegResult
import com.estimatefaster.audioforge.core.audio.StitchResult
import com.estimatefaster.audioforge.core.ingest.ChapterSplitter
import com.estimatefaster.audioforge.core.ingest.TextDecoder
import com.estimatefaster.audioforge.core.model.AudioStatus
import com.estimatefaster.audioforge.core.model.NARRATOR_ROLE_ID
import com.estimatefaster.audioforge.core.model.ParseStatus
import com.estimatefaster.audioforge.core.model.PipelineError
import com.estimatefaster.audioforge.core.model.SegmentStatus
import com.estimatefaster.audioforge.core.model.SegmentType
import com.estimatefaster.audioforge.core.network.LlmClient
import com.estimatefaster.audioforge.core.network.LlmConfig
import com.estimatefaster.audioforge.core.storage.AudioStorage
import com.estimatefaster.audioforge.core.tts.AudioCommandRunner
import com.estimatefaster.audioforge.core.tts.AudioPostProcessor
import com.estimatefaster.audioforge.core.tts.TtsSynthesizer
import com.estimatefaster.audioforge.core.tts.TtsTransport
import com.estimatefaster.audioforge.core.voice.PromptAssets
import com.estimatefaster.audioforge.core.voice.PromptRenderer
import com.estimatefaster.audioforge.core.voice.PromptTemplates
import com.estimatefaster.audioforge.core.voice.VoicePool
import com.estimatefaster.audioforge.data.db.JobStatus
import com.estimatefaster.audioforge.data.repository.RepositoryFixture
import com.estimatefaster.audioforge.domain.jobs.BatchOutcome
import com.estimatefaster.audioforge.domain.jobs.GenerationExecutor
import com.estimatefaster.audioforge.domain.jobs.GenerationInput
import com.estimatefaster.audioforge.domain.jobs.GenerationMode
import com.estimatefaster.audioforge.domain.jobs.GenerationStats
import com.estimatefaster.audioforge.domain.usecase.ImportNovelUseCase
import com.estimatefaster.audioforge.domain.usecase.ImportResult
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
 * P2-17：导入 → 解析 → 渲染 → 拼接 的端到端 fake 测试（执行计划 §15 P2-17）。
 *
 * 真实链路：
 * - `ImportNovelUseCase` + [TextDecoder] + [ChapterSplitter] + in-memory Room；
 * - `AnalyzeChapterUseCase` + 真实 [LlmClient]（指向 [MockWebServer]）+ 真实提示词/清洗/
 *   覆盖率/角色注册；
 * - `RenderChapterUseCase` + 真实 [TtsSynthesizer]（fake [TtsTransport] 返回固定 mp3 字节，
 *   因此 `.hash` 缓存是真实写出来的）+ fake 拼接（真实写临时成品文件）。
 *
 * 只有「网络传输」与「ffmpeg / 媒体栈」被替换：TTS 不连 Fish，拼接不启动 FFmpegKit，
 * 时长读取不碰 `MediaMetadataRetriever`，所以整条链路可以在 JVM + Robolectric 上跑完。
 *
 * 覆盖：
 * - 两章完整链路与段落/章节状态、成品文件；
 * - 断点续传：解析完成后再 analyze 是 Skipped 且不发请求；渲染完成后再 render 命中缓存
 *   且不调 TTS；
 * - 中断（shouldStop）后重跑只补未完成段；
 * - 失败注入：LLM 剧本非法 JSON / 响应体非法 JSON → 降级旁白；TTS 首次失败 → 章节
 *   error，第二次渲染补失败段成功；
 * - Job 统计与章节 `segCount/segDone` 一致。
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PipelineE2ETest {

    private lateinit var fixture: RepositoryFixture
    private lateinit var server: MockWebServer
    private lateinit var pool: VoicePool
    private lateinit var templates: PromptTemplates
    private lateinit var stitchCacheDir: File
    private lateinit var importUseCase: ImportNovelUseCase

    @Before
    fun setUp() {
        fixture = RepositoryFixture()
        server = MockWebServer()
        server.start()
        pool = VoicePool.parse(readResource("voice-pool.json"))
        templates = PromptAssets(
            ApplicationProvider.getApplicationContext<Context>().assets,
        ).templates
        stitchCacheDir = Files.createTempDirectory("pipeline-e2e-stitch-").toFile()
        importUseCase = ImportNovelUseCase(
            projects = fixture.projects,
            audioStorage = fixture.audioStorage,
            textDecoder = TextDecoder(),
            chapterSplitter = ChapterSplitter(),
            io = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        server.close()
        fixture.close()
        stitchCacheDir.deleteRecursively()
    }

    // ------------------------------------------------------------ 完整链路

    @Test
    fun full_pipeline_imports_analyzes_and_renders_two_chapters() = runTest {
        val novel = importNovel()
        val analyzeUseCase = newAnalyzeUseCase(novel.novelId)
        val transport = E2eFakeTtsTransport()
        val stitch = E2eFakeStitch(fixture.audioStorage)
        val renderUseCase = newRenderUseCase(transport, stitch)

        server.enqueue(llmResponse(E2E_SCRIPT_CHAPTER_ONE))
        server.enqueue(llmResponse(E2E_SCRIPT_CHAPTER_TWO))

        // ---- 第一章：解析
        val firstChapterId = novel.chapterIds[0]
        val firstAnalyze = analyzeUseCase.analyze(novel.novelId, firstChapterId)

        assertEquals(AnalyzeStatus.Done, firstAnalyze.status)
        assertEquals(2, firstAnalyze.segmentCount)
        assertEquals(1.0, firstAnalyze.coverage ?: -1.0, 1e-9)
        assertNull("覆盖率 100% 时不应有告警", firstAnalyze.warning)
        assertEquals(0, firstAnalyze.unknownSpeakers)

        val firstAfterParse = requireNotNull(fixture.chapters.getChapter(firstChapterId))
        assertEquals("山风掠过青石阶。\n\n“走吧。”", firstAfterParse.rawText)
        assertEquals(ParseStatus.Done.wire, firstAfterParse.parseStatus)
        assertNull(firstAfterParse.parseError)
        assertNull(firstAfterParse.parseWarn)
        assertEquals(2, firstAfterParse.segCount)
        assertEquals(0, firstAfterParse.segDone)

        val firstSegments = fixture.segments.list(firstChapterId)
        assertEquals(
            listOf(SegmentType.Narration.wire, SegmentType.Dialogue.wire),
            firstSegments.map { it.type },
        )
        assertEquals(listOf(NARRATOR_ROLE_ID, "role_0001"), firstSegments.map { it.roleId })
        assertEquals(listOf("neutral", "happy"), firstSegments.map { it.emotion })
        assertEquals(listOf("山风掠过青石阶。", "走吧。"), firstSegments.map { it.text })
        assertTrue(firstSegments.all { it.status == SegmentStatus.Pending.wire })

        // ---- 第一章：渲染（真实 TtsSynthesizer + fake transport + fake 拼接）
        val firstRender = renderUseCase.render(novel.novelId, firstChapterId)

        assertEquals(RenderStatus.Done, firstRender.status)
        assertEquals(0, firstRender.cachedCount)
        assertEquals(2, firstRender.synthesizedCount)
        assertEquals(0, firstRender.failedCount)
        assertEquals(E2E_SEGMENT_DURATION_MS * 2, firstRender.durationMs)

        val firstOutput = requireNotNull(firstRender.outputRelative)
        assertEquals(
            fixture.audioStorage.chapterAudioRelative(novel.novelId, 0),
            firstOutput,
        )
        val firstOutputFile = fixture.audioStorage.resolve(firstOutput)
        assertTrue("成品文件必须真实存在：$firstOutput", firstOutputFile.isFile)
        assertTrue(firstOutputFile.length() > 0L)

        val firstAfterRender = requireNotNull(fixture.chapters.getChapter(firstChapterId))
        assertEquals(AudioStatus.Done.wire, firstAfterRender.audioStatus)
        assertNull(firstAfterRender.audioError)
        assertEquals(2, firstAfterRender.segCount)
        assertEquals(2, firstAfterRender.segDone)

        val doneSegments = fixture.segments.list(firstChapterId)
        assertTrue(doneSegments.all { it.status == SegmentStatus.Done.wire })
        doneSegments.forEach { segment ->
            assertEquals(
                fixture.audioStorage.segmentAudioRelative(novel.novelId, 0, segment.seq),
                segment.audioRelPath,
            )
            assertTrue(
                "v2 缓存键应写回片段：${segment.ttsKey}",
                requireNotNull(segment.ttsKey).startsWith("v2|"),
            )
            assertEquals(E2E_SEGMENT_DURATION_MS.toInt(), requireNotNull(segment.durationMs))
            assertNull(segment.errorMsg)
        }

        // ---- 第二章：同一套链路，且复用第一章注册的角色
        val secondChapterId = novel.chapterIds[1]
        val secondAnalyze = analyzeUseCase.analyze(novel.novelId, secondChapterId)

        assertEquals(AnalyzeStatus.Done, secondAnalyze.status)
        assertEquals(2, secondAnalyze.segmentCount)
        assertEquals(0, secondAnalyze.unknownSpeakers)
        assertEquals(1, fixture.characters.list(novel.novelId).size)

        val secondSegments = fixture.segments.list(secondChapterId)
        assertEquals(listOf(NARRATOR_ROLE_ID, "role_0001"), secondSegments.map { it.roleId })

        val secondRender = renderUseCase.render(novel.novelId, secondChapterId)

        assertEquals(RenderStatus.Done, secondRender.status)
        assertEquals(2, secondRender.synthesizedCount)
        assertEquals(
            fixture.audioStorage.chapterAudioRelative(novel.novelId, 1),
            requireNotNull(secondRender.outputRelative),
        )
        assertTrue(
            fixture.audioStorage.resolve(requireNotNull(secondRender.outputRelative)).isFile,
        )
        val secondAfterRender = requireNotNull(fixture.chapters.getChapter(secondChapterId))
        assertEquals(AudioStatus.Done.wire, secondAfterRender.audioStatus)
        assertEquals(2, secondAfterRender.segCount)
        assertEquals(2, secondAfterRender.segDone)

        // ---- 外部调用次数：两章各一次 LLM、每段一次 TTS、每章一次拼接
        assertEquals(2, server.requestCount)
        assertEquals(
            listOf("山风掠过青石阶。", "[happy]走吧。", "夜色渐深。", "[calm]小心。"),
            transport.calls.map { it.text },
        )
        assertEquals(
            pool.voiceIdFor("narrator_male"),
            transport.calls[0].voiceId,
        )
        assertEquals(
            pool.voiceIdFor("male_lead_steady"),
            transport.calls[1].voiceId,
        )
        assertEquals(2, stitch.calls.size)
        assertEquals(listOf(2, 2), stitch.calls.map { it.files.size })
    }

    // ------------------------------------------------------------ 断点续传

    @Test
    fun done_analyze_is_skipped_and_done_render_hits_cache_on_rerun() = runTest {
        val novel = importNovel()
        val analyzeUseCase = newAnalyzeUseCase(novel.novelId)
        val transport = E2eFakeTtsTransport()
        val stitch = E2eFakeStitch(fixture.audioStorage)
        val renderUseCase = newRenderUseCase(transport, stitch)
        val chapterId = novel.chapterIds[0]

        server.enqueue(llmResponse(E2E_SCRIPT_CHAPTER_ONE))
        assertEquals(
            AnalyzeStatus.Done,
            analyzeUseCase.analyze(novel.novelId, chapterId).status,
        )
        assertEquals(1, server.requestCount)

        // 解析已完成：force=false 应 Skipped，且不再调用 LLM。
        val skipped = analyzeUseCase.analyze(novel.novelId, chapterId)
        assertEquals(AnalyzeStatus.Skipped, skipped.status)
        assertEquals(2, skipped.segmentCount)
        assertEquals("断点续传：解析完成后再 analyze 不应调用 LLM", 1, server.requestCount)

        // 首次渲染：两段都合成，拼接一次。
        val firstRender = renderUseCase.render(novel.novelId, chapterId)
        assertEquals(RenderStatus.Done, firstRender.status)
        assertEquals(0, firstRender.cachedCount)
        assertEquals(2, firstRender.synthesizedCount)
        assertEquals(2, transport.calls.size)
        assertEquals(1, stitch.calls.size)

        // 再次渲染：全部命中缓存，不调 TTS，但仍重新拼接。
        val cachedRender = renderUseCase.render(novel.novelId, chapterId)
        assertEquals(RenderStatus.Done, cachedRender.status)
        assertEquals(2, cachedRender.cachedCount)
        assertEquals(0, cachedRender.synthesizedCount)
        assertEquals("断点续传：渲染完成后再 render 不应调用 TTS", 2, transport.calls.size)
        assertEquals("缓存命中仍需要重新拼接", 2, stitch.calls.size)
        assertTrue(
            fixture.audioStorage.resolve(requireNotNull(cachedRender.outputRelative)).isFile,
        )

        val chapter = requireNotNull(fixture.chapters.getChapter(chapterId))
        assertEquals(AudioStatus.Done.wire, chapter.audioStatus)
        assertEquals(2, chapter.segCount)
        assertEquals(2, chapter.segDone)
    }

    @Test
    fun stopped_render_resumes_and_only_synthesizes_unfinished_segments() = runTest {
        val novel = importNovel()
        val analyzeUseCase = newAnalyzeUseCase(novel.novelId)
        val transport = E2eFakeTtsTransport()
        val stitch = E2eFakeStitch(fixture.audioStorage)
        val renderUseCase = newRenderUseCase(transport, stitch)
        val chapterId = novel.chapterIds[0]

        server.enqueue(llmResponse(E2E_SCRIPT_CHAPTER_ONE))
        analyzeUseCase.analyze(novel.novelId, chapterId)

        // 第一段合成完成后请求停止：已开始的段保留结果，其余段保持 pending。
        val stopped = renderUseCase.render(
            novel.novelId,
            chapterId,
            shouldStop = { transport.calls.size >= 1 },
        )

        assertEquals(RenderStatus.Stopped, stopped.status)
        assertEquals(1, stopped.synthesizedCount)
        assertEquals(0, stopped.failedCount)
        assertEquals(1, transport.calls.size)
        assertTrue("停止后不应拼接", stitch.calls.isEmpty())

        val afterStop = fixture.segments.list(chapterId)
        assertEquals(
            listOf(SegmentStatus.Done.wire, SegmentStatus.Pending.wire),
            afterStop.map { it.status },
        )
        val stoppedChapter = requireNotNull(fixture.chapters.getChapter(chapterId))
        assertEquals(AudioStatus.Pending.wire, stoppedChapter.audioStatus)
        assertNull("停止不是错误", stoppedChapter.audioError)
        assertEquals(1, stoppedChapter.segDone)

        // 重跑：只补未完成段，已完成段命中缓存。
        val resumed = renderUseCase.render(novel.novelId, chapterId)

        assertEquals(RenderStatus.Done, resumed.status)
        assertEquals(1, resumed.cachedCount)
        assertEquals(1, resumed.synthesizedCount)
        assertEquals(2, transport.calls.size)
        assertEquals("重跑只补未完成段", "[happy]走吧。", transport.calls[1].text)
        assertEquals(1, stitch.calls.size)

        val chapter = requireNotNull(fixture.chapters.getChapter(chapterId))
        assertEquals(AudioStatus.Done.wire, chapter.audioStatus)
        assertEquals(2, chapter.segDone)
        assertTrue(fixture.audioStorage.resolve(requireNotNull(resumed.outputRelative)).isFile)
    }

    // ------------------------------------------------------------ 失败注入

    @Test
    fun invalid_script_json_degrades_to_narration_and_still_renders() = runTest {
        val novel = importNovel()
        val analyzeUseCase = newAnalyzeUseCase(novel.novelId)
        val transport = E2eFakeTtsTransport()
        val stitch = E2eFakeStitch(fixture.audioStorage)
        val renderUseCase = newRenderUseCase(transport, stitch)
        val chapterId = novel.chapterIds[0]

        // HTTP 响应本身合法，但 choices[0].message.content 不是剧本 JSON。
        server.enqueue(llmResponse("这不是合法的剧本 JSON"))

        val degraded = analyzeUseCase.analyze(novel.novelId, chapterId)

        assertEquals(AnalyzeStatus.Degraded, degraded.status)
        assertEquals(PipelineError.BAD_JSON, degraded.errorCode)
        assertNotNull(degraded.warning)
        assertTrue(requireNotNull(degraded.warning).contains("BAD_JSON"))
        assertEquals(1, server.requestCount)

        val chapterAfterParse = requireNotNull(fixture.chapters.getChapter(chapterId))
        assertEquals(ParseStatus.Done.wire, chapterAfterParse.parseStatus)
        assertTrue(requireNotNull(chapterAfterParse.parseWarn).contains("BAD_JSON"))
        assertEquals(1, chapterAfterParse.segCount)
        assertEquals(0, chapterAfterParse.segDone)

        val fallback = fixture.segments.list(chapterId).single()
        assertEquals(SegmentType.Narration.wire, fallback.type)
        assertEquals(NARRATOR_ROLE_ID, fallback.roleId)
        assertEquals("整章旁白降级必须覆盖全文", chapterAfterParse.rawText, fallback.text)

        // 降级结果仍能走完合成/拼接。
        val rendered = renderUseCase.render(novel.novelId, chapterId)
        assertEquals(RenderStatus.Done, rendered.status)
        assertEquals(1, rendered.synthesizedCount)
        assertEquals(1, transport.calls.size)
        assertEquals(
            AudioStatus.Done.wire,
            requireNotNull(fixture.chapters.getChapter(chapterId)).audioStatus,
        )
        assertTrue(fixture.audioStorage.resolve(requireNotNull(rendered.outputRelative)).isFile)
    }

    @Test
    fun malformed_llm_http_body_degrades_to_narration() = runTest {
        val novel = importNovel()
        val analyzeUseCase = newAnalyzeUseCase(novel.novelId)
        val chapterId = novel.chapterIds[0]

        // 响应体不是合法 JSON：LlmClient 抛 BadJson，解析用例按非法 JSON 降级。
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\": [broken"),
        )

        val degraded = analyzeUseCase.analyze(novel.novelId, chapterId)

        assertEquals(AnalyzeStatus.Degraded, degraded.status)
        assertEquals(PipelineError.BAD_JSON, degraded.errorCode)
        assertEquals(1, server.requestCount)

        val chapter = requireNotNull(fixture.chapters.getChapter(chapterId))
        assertEquals(ParseStatus.Done.wire, chapter.parseStatus)
        assertEquals(1, chapter.segCount)
        val fallback = fixture.segments.list(chapterId).single()
        assertEquals(SegmentType.Narration.wire, fallback.type)
        assertEquals(chapter.rawText, fallback.text)
    }

    @Test
    fun tts_failure_marks_chapter_error_then_retry_succeeds() = runTest {
        val novel = importNovel()
        val analyzeUseCase = newAnalyzeUseCase(novel.novelId)
        // 第 1 次 transport 调用失败，之后恢复。
        val transport = E2eFakeTtsTransport(failOnCalls = setOf(1))
        val stitch = E2eFakeStitch(fixture.audioStorage)
        val renderUseCase = newRenderUseCase(transport, stitch)
        val chapterId = novel.chapterIds[0]

        server.enqueue(llmResponse(E2E_SCRIPT_CHAPTER_ONE))
        analyzeUseCase.analyze(novel.novelId, chapterId)

        val failed = renderUseCase.render(novel.novelId, chapterId)

        assertEquals(RenderStatus.Partial, failed.status)
        assertEquals(0, failed.cachedCount)
        assertEquals(1, failed.synthesizedCount)
        assertEquals(1, failed.failedCount)
        assertNull("有失败时不拼接", failed.outputRelative)
        assertTrue(stitch.calls.isEmpty())
        assertEquals(PipelineError.TTS_FAILED, failed.errors.single().code)
        assertEquals(0, failed.errors.single().segmentSeq)

        val failedSegments = fixture.segments.list(chapterId)
        assertEquals(
            listOf(SegmentStatus.Error.wire, SegmentStatus.Done.wire),
            failedSegments.map { it.status },
        )
        assertTrue(
            requireNotNull(failedSegments[0].errorMsg).contains("模拟 TTS 网络失败"),
        )
        val errorChapter = requireNotNull(fixture.chapters.getChapter(chapterId))
        assertEquals(AudioStatus.Error.wire, errorChapter.audioStatus)
        assertNotNull(errorChapter.audioError)
        assertEquals(2, errorChapter.segCount)
        assertEquals(1, errorChapter.segDone)

        // 第二次渲染：已完成段命中缓存，只补失败段。
        val retried = renderUseCase.render(novel.novelId, chapterId)

        assertEquals(RenderStatus.Done, retried.status)
        assertEquals(1, retried.cachedCount)
        assertEquals(1, retried.synthesizedCount)
        assertEquals(0, retried.failedCount)
        assertEquals(3, transport.calls.size)
        assertEquals("重试只补失败段", "山风掠过青石阶。", transport.calls[2].text)
        assertEquals(1, stitch.calls.size)

        val chapter = requireNotNull(fixture.chapters.getChapter(chapterId))
        assertEquals(AudioStatus.Done.wire, chapter.audioStatus)
        assertNull(chapter.audioError)
        assertEquals(2, chapter.segCount)
        assertEquals(2, chapter.segDone)
        assertTrue(fixture.audioStorage.resolve(requireNotNull(retried.outputRelative)).isFile)
    }

    // -------------------------------------------------------- Job / 章节统计

    @Test
    fun job_stats_stay_consistent_with_chapter_counters() = runTest {
        val novel = importNovel()
        val analyzeUseCase = newAnalyzeUseCase(novel.novelId)
        val transport = E2eFakeTtsTransport()
        val stitch = E2eFakeStitch(fixture.audioStorage)
        val renderUseCase = newRenderUseCase(transport, stitch)
        val executor = GenerationExecutor(
            chapters = fixture.chapters,
            jobs = fixture.jobs,
            analyzeChapter = { novelId, chapterId, force ->
                analyzeUseCase.analyze(novelId, chapterId, force = force)
            },
            renderChapter = { novelId, chapterId, shouldStop ->
                renderUseCase.render(novelId, chapterId, shouldStop = shouldStop)
            },
        )

        server.enqueue(llmResponse(E2E_SCRIPT_CHAPTER_ONE))
        server.enqueue(llmResponse(E2E_SCRIPT_CHAPTER_TWO))

        val job = fixture.jobs.start(
            novelId = novel.novelId,
            mode = GenerationMode.Full.wire,
            requestedIds = novel.chapterIds,
            stats = mapOf(GenerationStats.KEY_CHAPTERS_TOTAL to 2L),
            now = fixture.nextTimestamp(),
        )
        assertTrue(fixture.jobs.markRunning(job.id))

        val outcome = executor.run(
            GenerationInput(
                novelId = novel.novelId,
                chapterIds = novel.chapterIds,
                mode = GenerationMode.Full,
                jobId = job.id,
            ),
        )

        val success = outcome as? BatchOutcome.Success
            ?: error("整批应成功，实际：$outcome")
        assertTrue(success.failures.isEmpty())
        assertEquals(2, success.stats.chaptersTotal)
        assertEquals(2, success.stats.chaptersDone)
        assertEquals(0, success.stats.chaptersFailed)
        assertEquals(4L, success.stats.segTotal)
        assertEquals(4L, success.stats.segDone)
        assertEquals(4L, success.stats.ttsOk)
        assertEquals(0L, success.stats.ttsFailed)

        assertTrue(
            fixture.jobs.markDone(
                id = job.id,
                stats = success.stats.toJobStats(),
                now = fixture.nextTimestamp(),
            ),
        )

        val storedJob = requireNotNull(fixture.jobs.get(job.id))
        assertEquals(JobStatus.DONE, storedJob.status)
        val stats = jobStats(job.id)
        assertEquals(2L, stats.value(GenerationStats.KEY_CHAPTERS_TOTAL))
        assertEquals(2L, stats.value(GenerationStats.KEY_CHAPTERS_DONE))
        assertEquals(0L, stats.value(GenerationStats.KEY_CHAPTERS_FAILED))
        assertEquals(4L, stats.value(GenerationStats.KEY_SEG_TOTAL))
        assertEquals(4L, stats.value(GenerationStats.KEY_SEG_DONE))
        assertEquals(4L, stats.value(GenerationStats.KEY_TTS_OK))
        assertEquals(0L, stats.value(GenerationStats.KEY_TTS_FAILED))

        // Job 统计必须与章节冗余计数（segCount/segDone）逐项一致。
        val dbSegTotal = novel.chapterIds.sumOf { chapterId ->
            (requireNotNull(fixture.chapters.getChapter(chapterId)).segCount).toLong()
        }
        val dbSegDone = novel.chapterIds.sumOf { chapterId ->
            (requireNotNull(fixture.chapters.getChapter(chapterId)).segDone).toLong()
        }
        assertEquals(dbSegTotal, stats.value(GenerationStats.KEY_SEG_TOTAL))
        assertEquals(dbSegDone, stats.value(GenerationStats.KEY_SEG_DONE))
        assertEquals(4L, dbSegTotal)
        assertEquals(4L, dbSegDone)

        novel.chapterIds.forEach { chapterId ->
            val chapter = requireNotNull(fixture.chapters.getChapter(chapterId))
            assertEquals(ParseStatus.Done.wire, chapter.parseStatus)
            assertEquals(AudioStatus.Done.wire, chapter.audioStatus)
            assertEquals(2, chapter.segCount)
            assertEquals(2, chapter.segDone)
            assertEquals(2, fixture.segments.list(chapterId).count { it.status == SegmentStatus.Done.wire })
        }

        assertEquals(2, server.requestCount)
        assertEquals(4, transport.calls.size)
        assertEquals(2, stitch.calls.size)
    }

    // ------------------------------------------------------------ helpers

    private suspend fun importNovel(): ImportedNovel {
        val bytes = E2E_NOVEL_TEXT.toByteArray(Charsets.UTF_8)
        val result = importUseCase.import(
            displayName = "长夜.txt",
            input = ByteArrayInputStream(bytes),
            sizeBytes = bytes.size.toLong(),
        )

        assertTrue("导入应成功，实际：$result", result is ImportResult.Success)
        val success = result as ImportResult.Success
        assertEquals(2, success.chapterCount)

        val project = requireNotNull(fixture.projects.getProject(success.novelId))
        assertEquals("长夜", project.title)

        val briefs = fixture.chapters.observeBriefs(success.novelId).first()
        assertEquals(listOf("第一章 风起", "第二章 夜行"), briefs.map { it.title })
        assertEquals(listOf(0, 1), briefs.map { it.idx })
        return ImportedNovel(success.novelId, briefs.map { it.id })
    }

    private fun newLlmClient(): LlmClient = LlmClient(
        config = LlmConfig(
            baseUrl = server.url("/").toString(),
            model = E2E_LLM_MODEL,
            apiKey = "sk-test-not-a-real-key",
            connectTimeoutMs = E2E_TIMEOUT_MS,
            readTimeoutMs = E2E_TIMEOUT_MS,
            // 测试要断言「没有额外重试」，退避重试在这里只会拖慢用例。
            maxRetries = 0,
        ),
    )

    private fun newAnalyzeUseCase(novelId: Long): AnalyzeChapterUseCase = AnalyzeChapterUseCase(
        db = fixture.db,
        chapterRepository = fixture.chapters,
        segmentRepository = fixture.segments,
        llmClient = newLlmClient(),
        registryAdapter = CharacterRegistryAdapter(
            fixture.db,
            fixture.characters,
            pool,
            novelId,
        ),
        voicePool = pool,
        promptRenderer = PromptRenderer(templates),
        dispatcher = Dispatchers.Unconfined,
    )

    private fun newRenderUseCase(
        transport: E2eFakeTtsTransport,
        stitch: E2eFakeStitch,
    ): RenderChapterUseCase = RenderChapterUseCase(
        segmentRepository = fixture.segments,
        chapterRepository = fixture.chapters,
        db = fixture.db,
        audioStorage = fixture.audioStorage,
        ttsSynthesizer = TtsSynthesizer(
            transport = transport,
            postProcessor = AudioPostProcessor(
                runner = object : AudioCommandRunner {
                    override suspend fun run(command: String): FfmpegResult =
                        error("E2E 不应调用真实 ffmpeg 后处理：$command")
                },
                io = Dispatchers.Unconfined,
            ),
            storage = fixture.audioStorage,
            io = Dispatchers.Unconfined,
        ),
        chapterStitcher = dummyStitcher(),
        voicePool = pool,
        characterRepository = fixture.characters,
        dispatcher = Dispatchers.Unconfined,
        // 单 worker 让「停止 / 重试只补某一段」的调用顺序可断言。
        ttsConcurrency = 1,
        durationReader = { E2E_SEGMENT_DURATION_MS },
        stitch = { novelId, chapterIdx, files, types ->
            stitch.stitch(novelId, chapterIdx, files, types)
        },
    )

    /** 真实实例只用于满足构造签名；所有拼接调用都走注入的 fake。 */
    private fun dummyStitcher(): ChapterStitcher = ChapterStitcher(
        runner = FfmpegKitRunner(),
        cacheDir = stitchCacheDir,
        durationReader = { 0L },
        io = Dispatchers.Unconfined,
    )

    private suspend fun jobStats(jobId: Long): JsonObject {
        val job = requireNotNull(fixture.jobs.get(jobId))
        return E2E_JSON.parseToJsonElement(job.statsJson).jsonObject
    }

    private fun JsonObject.value(key: String): Long = getValue(key).jsonPrimitive.long

    private fun readResource(name: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream(name),
    ) { "缺少测试资源 $name" }
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }

    private data class ImportedNovel(
        val novelId: Long,
        val chapterIds: List<Long>,
    )
}

// ---------------------------------------------------------------- fakes

/**
 * fake TTS 传输：返回固定的伪 mp3 字节；可按「第 N 次调用」注入失败。
 *
 * 与直接替换 `RenderChapterUseCase.tts` 不同，这里保留了真实 [TtsSynthesizer]，
 * 因此 `.raw.mp3 → target`、`.hash`、失败清理等真实文件语义都被端到端覆盖。
 */
private class E2eFakeTtsTransport(
    private val failOnCalls: Set<Int> = emptySet(),
) : TtsTransport {

    data class Call(
        val text: String,
        val voiceId: String,
        val speed: Double?,
    )

    val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf<Call>())

    private val counter = AtomicInteger()

    override suspend fun synthesize(text: String, voiceId: String, speed: Double?): ByteArray {
        val index = counter.incrementAndGet()
        calls += Call(text = text, voiceId = voiceId, speed = speed)
        if (index in failOnCalls) {
            throw IOException("模拟 TTS 网络失败（第 $index 次调用）")
        }
        return E2E_FAKE_MP3
    }
}

/**
 * fake 拼接：校验片段文件真实存在，并把片段字节写进章节成品路径。
 *
 * 不调用 FFmpegKit / MediaMetadataRetriever，但产物是磁盘上的真实文件，
 * 所以后续「成品文件存在且非空」的断言与真实链路同口径。
 */
private class E2eFakeStitch(private val storage: AudioStorage) {

    data class Call(
        val novelId: Long,
        val chapterIdx: Int,
        val files: List<File>,
        val types: List<SegmentType>,
    )

    val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf<Call>())

    suspend fun stitch(
        novelId: Long,
        chapterIdx: Int,
        files: List<File>,
        types: List<SegmentType>,
    ): StitchResult {
        calls += Call(novelId, chapterIdx, files.toList(), types.toList())
        files.forEach { file ->
            check(file.isFile && file.length() > 0L) { "片段文件缺失或为空：$file" }
        }
        val stitched = files.fold(ByteArray(0)) { acc, file -> acc + file.readBytes() }
        val relative = storage.chapterAudioRelative(novelId, chapterIdx)
        storage.writeBytes(relative, if (stitched.isEmpty()) E2E_FAKE_MP3 else stitched)
        return StitchResult(
            relativePath = relative,
            durationMs = files.size * E2E_SEGMENT_DURATION_MS,
        )
    }
}

// ---------------------------------------------------------------- fixtures

private const val E2E_LLM_MODEL = "qwen3.7-flash"
private const val E2E_TIMEOUT_MS = 5_000
private const val E2E_SEGMENT_DURATION_MS = 1_000L

private val E2E_JSON = Json { ignoreUnknownKeys = true }

/** 伪 mp3 字节：只要非空即可，真实解码不在本测试范围内。 */
private val E2E_FAKE_MP3 = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x00, 0x00, 0x00)

private val E2E_NOVEL_TEXT = """
    第一章 风起

    山风掠过青石阶。

    “走吧。”

    第二章 夜行

    夜色渐深。

    “小心。”
""".trimIndent()

/**
 * 第一章剧本：旁白 + 韩立（首次登场）。两段拼起来正好覆盖清洗后的原文，
 * 覆盖率 100%，因此不会触发修复重试、不会产生额外 LLM 请求。
 */
private val E2E_SCRIPT_CHAPTER_ONE = """
    {
      "new_characters": [
        {
          "name": "韩立",
          "gender": "male",
          "importance": "protagonist",
          "aliases": [],
          "note": "主角",
          "voice": "male_lead_steady",
          "alias_of": ""
        }
      ],
      "segments": [
        {"type": "narration", "speaker": "narrator", "emotion": "neutral", "text": "山风掠过青石阶。"},
        {"type": "dialogue", "speaker": "韩立", "emotion": "happy", "text": "走吧。"}
      ]
    }
""".trimIndent()

/** 第二章剧本：不新增角色，复用第一章注册的韩立（role_0001）。 */
private val E2E_SCRIPT_CHAPTER_TWO = """
    {
      "new_characters": [],
      "segments": [
        {"type": "narration", "speaker": "narrator", "emotion": "neutral", "text": "夜色渐深。"},
        {"type": "dialogue", "speaker": "韩立", "emotion": "calm", "text": "小心。"}
      ]
    }
""".trimIndent()

/**
 * 把剧本 JSON 包进 OpenAI 兼容响应，`content` 用 kotlinx.serialization 转义，
 * 避免手写转义引入非法 JSON。
 */
private fun llmResponse(content: String): MockResponse {
    val escaped = E2E_JSON.encodeToString(String.serializer(), content)
    return MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":$escaped}," +
                "\"finish_reason\":\"stop\"}]}",
        )
}
