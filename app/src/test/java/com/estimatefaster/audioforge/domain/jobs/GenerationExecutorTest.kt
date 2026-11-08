package com.estimatefaster.audioforge.domain.jobs

import com.estimatefaster.audioforge.core.model.PipelineError
import com.estimatefaster.audioforge.data.db.JobStatus
import com.estimatefaster.audioforge.data.repository.ChapterInput
import com.estimatefaster.audioforge.data.repository.RepositoryFixture
import com.estimatefaster.audioforge.domain.pipeline.AnalyzeResult
import com.estimatefaster.audioforge.domain.pipeline.AnalyzeStatus
import com.estimatefaster.audioforge.domain.pipeline.RenderError
import com.estimatefaster.audioforge.domain.pipeline.RenderResult
import com.estimatefaster.audioforge.domain.pipeline.RenderStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P3-02：`GenerationExecutor` 的批次语义（执行计划 §11.1 / §15 P3-02）。
 *
 * Robolectric + in-memory Room + 临时目录；解析/渲染通过构造参数注入 fake lambda，
 * 因此不联网、不启动 TTS/FFmpeg。测试覆盖：
 * - parse-only / render-only / full 的执行顺序与 job 统计；
 * - 单章失败继续其余章并汇总为 Partial；
 * - Network/Quota（可重试）与 Auth/Model/BadJson/Storage/NoSegments（不可重试）分类；
 * - 停止：第一章前、parse 后 render 前、render 中；只处理到当前章，job 进度正确；
 * - 空 chapterIds 参数失败。
 *
 * WorkManager TestDriver 需要 `work-testing` 的 unitTest 依赖；本任务不允许改
 * `build.gradle.kts`，所以 Worker 的状态机由执行器测试覆盖（JobRepository 自身的
 * 状态机已由 P3-01 的 `JobRepositoryTest` 覆盖）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GenerationExecutorTest {

    private lateinit var fixture: RepositoryFixture

    private val analyzeCalls = mutableListOf<AnalyzeCall>()
    private val renderCalls = mutableListOf<RenderCall>()
    private var stopFlag = false

    @Before
    fun setUp() {
        fixture = RepositoryFixture()
        analyzeCalls.clear()
        renderCalls.clear()
        stopFlag = false
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    // ------------------------------------------------------------ 正常路径

    @Test
    fun parse_mode_analyzes_each_chapter_in_order_without_rendering() = runTest {
        val (novelId, ids) = createNovel("第一章", "第二章")
        val jobId = startJob(novelId, ids, GenerationMode.Parse)
        val progress = mutableListOf<GenerationProgress>()

        val executor = newExecutor(
            analyze = { _, chapterId, _ ->
                fixture.chapters.updateCounters(chapterId, segCount = 3, segDone = 0)
                AnalyzeResult(AnalyzeStatus.Done, chapterId, segmentCount = 3)
            },
        )

        val outcome = executor.run(
            input = input(novelId, ids, GenerationMode.Parse, force = true, jobId = jobId),
            onProgress = { progress += it },
        )

        assertTrue(outcome is BatchOutcome.Success)
        assertEquals(ids, analyzeCalls.map { it.chapterId })
        assertTrue("force 应透传给 analyze", analyzeCalls.all { it.force })
        assertTrue(renderCalls.isEmpty())

        assertEquals(2, outcome.stats.chaptersDone)
        assertEquals(0, outcome.stats.chaptersFailed)
        assertEquals(0L, outcome.stats.segDone)
        assertEquals(6L, outcome.stats.segTotal)
        assertEquals(100, progress.last().percent)

        val job = fixture.jobs.get(jobId)!!
        // 执行器不写终态；markDone 由 Worker 负责。
        assertEquals(JobStatus.RUNNING, job.status)
        assertEquals(ids.last(), job.currentChapterId)
        assertEquals(2L, jobStats(jobId).value("chaptersDone"))
        assertEquals(6L, jobStats(jobId).value("segTotal"))
    }

    @Test
    fun render_mode_renders_each_chapter_in_order_without_analyzing() = runTest {
        val (novelId, ids) = createNovel("第一章", "第二章")
        val jobId = startJob(novelId, ids, GenerationMode.Render)

        val executor = newExecutor(
            render = { _, chapterId, _ ->
                fixture.chapters.updateCounters(chapterId, segCount = 2, segDone = 2)
                RenderResult(
                    status = RenderStatus.Done,
                    chapterId = chapterId,
                    cachedCount = 1,
                    synthesizedCount = 1,
                )
            },
        )

        val outcome = executor.run(input(novelId, ids, GenerationMode.Render, jobId = jobId))

        assertTrue(outcome is BatchOutcome.Success)
        assertTrue("render 模式不应 analyze", analyzeCalls.isEmpty())
        assertEquals(ids, renderCalls.map { it.chapterId })
        assertEquals(2, outcome.stats.chaptersDone)
        assertEquals(4L, outcome.stats.segDone)
        assertEquals(4L, outcome.stats.segTotal)
        assertEquals(4L, outcome.stats.ttsOk)
        assertEquals(0L, outcome.stats.ttsFailed)

        assertEquals(4L, jobStats(jobId).value("segDone"))
        assertEquals(4L, jobStats(jobId).value("ttsOk"))
    }

    @Test
    fun full_mode_runs_analyze_then_render_for_each_chapter_in_order() = runTest {
        val (novelId, ids) = createNovel("第一章", "第二章")
        val jobId = startJob(novelId, ids, GenerationMode.Full)
        val events = mutableListOf<String>()

        val executor = newExecutor(
            analyze = { _, chapterId, _ ->
                events += "analyze-$chapterId"
                fixture.chapters.updateCounters(chapterId, segCount = 2, segDone = 0)
                AnalyzeResult(AnalyzeStatus.Done, chapterId, segmentCount = 2)
            },
            render = { _, chapterId, _ ->
                events += "render-$chapterId"
                fixture.chapters.updateCounters(chapterId, segCount = 2, segDone = 2)
                RenderResult(RenderStatus.Done, chapterId, synthesizedCount = 2)
            },
        )

        val outcome = executor.run(input(novelId, ids, GenerationMode.Full, jobId = jobId))

        assertTrue(outcome is BatchOutcome.Success)
        assertEquals(
            listOf(
                "analyze-${ids[0]}",
                "render-${ids[0]}",
                "analyze-${ids[1]}",
                "render-${ids[1]}",
            ),
            events,
        )
        assertEquals(2, outcome.stats.chaptersDone)
        assertEquals(4L, outcome.stats.segDone)
        assertEquals(4L, outcome.stats.segTotal)
    }

    // ------------------------------------------------------------ 单章失败

    @Test
    fun single_chapter_failure_is_recorded_and_other_chapters_continue() = runTest {
        val (novelId, ids) = createNovel("失败章", "成功章")
        val jobId = startJob(novelId, ids, GenerationMode.Full)

        val executor = newExecutor(
            analyze = { _, chapterId, _ ->
                if (chapterId == ids[0]) {
                    AnalyzeResult(
                        status = AnalyzeStatus.Failed,
                        chapterId = chapterId,
                        error = PipelineError.AuthLlm().userMessage,
                        errorCode = PipelineError.AUTH_LLM,
                    )
                } else {
                    fixture.chapters.updateCounters(chapterId, segCount = 2, segDone = 0)
                    AnalyzeResult(AnalyzeStatus.Done, chapterId, segmentCount = 2)
                }
            },
            render = { _, chapterId, _ ->
                fixture.chapters.updateCounters(chapterId, segCount = 2, segDone = 2)
                RenderResult(RenderStatus.Done, chapterId, synthesizedCount = 2)
            },
        )

        val outcome = executor.run(input(novelId, ids, GenerationMode.Full, jobId = jobId))

        assertTrue(outcome is BatchOutcome.Partial)
        assertEquals(2, analyzeCalls.size)
        // 解析失败的那一章不进入 render；成功章正常渲染。
        assertEquals(listOf(ids[1]), renderCalls.map { it.chapterId })

        assertEquals(1, outcome.stats.chaptersDone)
        assertEquals(1, outcome.stats.chaptersFailed)
        assertEquals(2L, outcome.stats.segDone)
        assertEquals(2L, outcome.stats.segTotal)
        assertEquals(1, outcome.failures.size)
        assertEquals(PipelineError.AUTH_LLM, outcome.failures.single().code)
        assertFalse(outcome.failures.single().retryable)
        assertFalse(outcome.retryable)
        assertEquals(1L, jobStats(jobId).value("chaptersDone"))
        assertEquals(1L, jobStats(jobId).value("chaptersFailed"))
    }

    // ------------------------------------------------------------ 错误分类

    @Test
    fun network_and_quota_failures_are_retryable_when_every_chapter_fails() = runTest {
        val (novelId, ids) = createNovel("网络章", "额度章")
        val jobId = startJob(novelId, ids, GenerationMode.Parse)

        val executor = newExecutor(
            analyze = { _, chapterId, _ ->
                if (chapterId == ids[0]) {
                    AnalyzeResult(
                        status = AnalyzeStatus.Failed,
                        chapterId = chapterId,
                        error = PipelineError.Network().userMessage,
                        errorCode = PipelineError.NETWORK,
                    )
                } else {
                    // 另一类是直接抛出 PipelineError；执行器也要能分类。
                    throw PipelineError.Quota()
                }
            },
        )

        val outcome = executor.run(input(novelId, ids, GenerationMode.Parse, jobId = jobId))

        assertTrue(outcome is BatchOutcome.Failed)
        assertEquals(2, outcome.failures.size)
        assertTrue(outcome.failures.all { it.retryable })
        assertTrue("全部失败且都是 Network/Quota 才允许整批重试", outcome.retryable)
        assertEquals(setOf(PipelineError.NETWORK, PipelineError.QUOTA), outcome.failures.map { it.code }.toSet())
        assertEquals(0, outcome.stats.chaptersDone)
        assertEquals(2, outcome.stats.chaptersFailed)
    }

    @Test
    fun auth_model_bad_json_truncated_storage_are_not_retryable() = runTest {
        val (novelId, ids) = createNovel("鉴权章", "模型章", "JSON 章", "截断章", "存储章")
        val jobId = startJob(novelId, ids, GenerationMode.Parse)

        val codes = listOf(
            PipelineError.AUTH_LLM,
            PipelineError.MODEL_NOT_FOUND,
            PipelineError.BAD_JSON,
            PipelineError.TRUNCATED,
            PipelineError.STORAGE,
        )
        val executor = newExecutor(
            analyze = { _, chapterId, _ ->
                val code = codes[ids.indexOf(chapterId)]
                AnalyzeResult(
                    status = AnalyzeStatus.Failed,
                    chapterId = chapterId,
                    error = code,
                    errorCode = code,
                )
            },
        )

        val outcome = executor.run(input(novelId, ids, GenerationMode.Parse, jobId = jobId))

        assertTrue(outcome is BatchOutcome.Failed)
        assertFalse(outcome.retryable)
        assertTrue(outcome.failures.none { it.retryable })
        assertEquals(codes.toSet(), outcome.failures.map { it.code }.toSet())

        // 渲染侧的错误码同样按 Network/Quota 判断可重试性。
        val renderNovel = createNovel("渲染网络", "渲染 TTS")
        val renderJob = startJob(renderNovel.first, renderNovel.second, GenerationMode.Render)
        val renderOutcome = newExecutor(
            render = { _, chapterId, _ ->
                if (chapterId == renderNovel.second[0]) {
                    RenderResult(
                        status = RenderStatus.Error,
                        chapterId = chapterId,
                        errors = listOf(RenderError(PipelineError.NETWORK, "网络不可用")),
                    )
                } else {
                    RenderResult(
                        status = RenderStatus.Error,
                        chapterId = chapterId,
                        failedCount = 1,
                        errors = listOf(RenderError(PipelineError.TTS_FAILED, "第 1 段合成失败")),
                    )
                }
            },
        ).run(input(renderNovel.first, renderNovel.second, GenerationMode.Render, jobId = renderJob))

        assertTrue(renderOutcome is BatchOutcome.Failed)
        val byChapter = renderOutcome.failures.associateBy { it.chapterId }
        assertTrue(byChapter.getValue(renderNovel.second[0]).retryable)
        assertFalse(byChapter.getValue(renderNovel.second[1]).retryable)
        assertFalse("混合错误不能整批重试", renderOutcome.retryable)
    }

    @Test
    fun no_segments_render_is_classified_as_non_retryable_failure() = runTest {
        val (novelId, ids) = createNovel("空片段")
        val jobId = startJob(novelId, ids, GenerationMode.Render)

        val outcome = newExecutor(
            render = { _, chapterId, _ ->
                RenderResult(
                    status = RenderStatus.NoSegments,
                    chapterId = chapterId,
                    errors = listOf(
                        RenderError(PipelineError.NO_SEGMENTS, "本章没有可拼接的片段"),
                    ),
                )
            },
        ).run(input(novelId, ids, GenerationMode.Render, jobId = jobId))

        assertTrue(outcome is BatchOutcome.Failed)
        assertEquals(PipelineError.NO_SEGMENTS, outcome.failures.single().code)
        assertFalse(outcome.failures.single().retryable)
        assertFalse(outcome.retryable)
    }

    // ------------------------------------------------------------ 停止

    @Test
    fun stop_before_first_chapter_processes_nothing_and_keeps_job_running() = runTest {
        val (novelId, ids) = createNovel("一", "二")
        val jobId = startJob(novelId, ids, GenerationMode.Parse)
        stopFlag = true
        val progress = mutableListOf<GenerationProgress>()

        val outcome = newExecutor().run(
            input = input(novelId, ids, GenerationMode.Parse, jobId = jobId),
            onProgress = { progress += it },
            shouldStop = { stopFlag },
        )

        assertTrue(outcome is BatchOutcome.Stopped)
        assertTrue(analyzeCalls.isEmpty())
        assertTrue(renderCalls.isEmpty())
        assertEquals(0, outcome.stats.chaptersDone)
        assertEquals(0, outcome.stats.percent)
        // 初始快照已写 job（chaptersTotal），但没有任何章节被处理。
        assertEquals(0L, jobStats(jobId).value("chaptersDone"))
        assertEquals(2L, jobStats(jobId).value("chaptersTotal"))
        assertEquals(JobStatus.RUNNING, fixture.jobs.get(jobId)!!.status)
        assertTrue(progress.isNotEmpty())
    }

    @Test
    fun stop_after_parse_skips_render_and_keeps_first_chapter_result() = runTest {
        val (novelId, ids) = createNovel("一", "二", "三")
        val jobId = startJob(novelId, ids, GenerationMode.Full)
        val executor = newExecutor(
            analyze = { _, chapterId, _ ->
                fixture.chapters.updateCounters(chapterId, segCount = 4, segDone = 0)
                // 第一章解析完成后用户点停止：下一阶段（render）开始前必须检查 shouldStop。
                if (chapterId == ids[0]) stopFlag = true
                AnalyzeResult(AnalyzeStatus.Done, chapterId, segmentCount = 4)
            },
            render = { _, chapterId, _ ->
                fixture.chapters.updateCounters(chapterId, segCount = 4, segDone = 4)
                RenderResult(RenderStatus.Done, chapterId, synthesizedCount = 4)
            },
        )

        val outcome = executor.run(
            input = input(novelId, ids, GenerationMode.Full, jobId = jobId),
            shouldStop = { stopFlag },
        )

        assertTrue(outcome is BatchOutcome.Stopped)
        // 只处理到当前章：第一章解析、未渲染；第二/三章完全没碰。
        assertEquals(listOf(ids[0]), analyzeCalls.map { it.chapterId })
        assertTrue(renderCalls.isEmpty())
        assertEquals(0, outcome.stats.chaptersDone)
        assertEquals(4L, outcome.stats.segTotal)
        assertEquals(0L, outcome.stats.segDone)

        val job = fixture.jobs.get(jobId)!!
        assertEquals(JobStatus.RUNNING, job.status)
        assertEquals(ids[0], job.currentChapterId)
        assertEquals(0L, jobStats(jobId).value("chaptersDone"))
        assertEquals(4L, jobStats(jobId).value("segTotal"))
    }

    @Test
    fun stop_during_render_keeps_completed_chapters_and_does_not_touch_later_ones() = runTest {
        val (novelId, ids) = createNovel("一", "二", "三")
        val jobId = startJob(novelId, ids, GenerationMode.Render)

        val executor = newExecutor(
            render = { _, chapterId, _ ->
                when (chapterId) {
                    ids[0] -> {
                        fixture.chapters.updateCounters(chapterId, segCount = 2, segDone = 2)
                        RenderResult(RenderStatus.Done, chapterId, synthesizedCount = 2)
                    }

                    ids[1] -> RenderResult(RenderStatus.Stopped, chapterId)

                    else -> error("停止后不应处理第三章")
                }
            },
        )

        val outcome = executor.run(input(novelId, ids, GenerationMode.Render, jobId = jobId))

        assertTrue(outcome is BatchOutcome.Stopped)
        assertEquals(listOf(ids[0], ids[1]), renderCalls.map { it.chapterId })
        assertEquals(1, outcome.stats.chaptersDone)
        assertEquals(2L, outcome.stats.segDone)
        assertEquals(2L, outcome.stats.segTotal)

        val job = fixture.jobs.get(jobId)!!
        assertEquals(JobStatus.RUNNING, job.status)
        // 当前章是停止发生的那一章；后续章节没有开始。
        assertEquals(ids[1], job.currentChapterId)
        assertEquals(1L, jobStats(jobId).value("chaptersDone"))
    }

    // ------------------------------------------------------------ 参数校验

    @Test
    fun empty_chapter_ids_is_a_parameter_failure() = runTest {
        val novelId = fixture.createEmptyNovel("空章节列表")

        val thrown = runCatching {
            newExecutor().run(GenerationInput(novelId, emptyList(), GenerationMode.Parse))
        }.exceptionOrNull()

        assertTrue("空 chapterIds 必须抛 IllegalArgumentException", thrown is IllegalArgumentException)
        assertTrue(analyzeCalls.isEmpty())
        assertTrue(renderCalls.isEmpty())
    }

    @Test
    fun duplicate_or_unknown_chapters_are_rejected_before_any_work() = runTest {
        val (novelId, ids) = createNovel("一")

        val duplicate = runCatching {
            newExecutor().run(GenerationInput(novelId, listOf(ids[0], ids[0]), GenerationMode.Parse))
        }.exceptionOrNull()
        assertTrue(duplicate is IllegalArgumentException)

        val missing = runCatching {
            newExecutor().run(GenerationInput(novelId, listOf(Long.MAX_VALUE), GenerationMode.Parse))
        }.exceptionOrNull()
        assertTrue(missing is IllegalArgumentException)

        assertTrue(analyzeCalls.isEmpty())
        assertTrue(renderCalls.isEmpty())
    }

    // ------------------------------------------------------------ helpers

    private fun newExecutor(
        analyze: AnalyzeChapterCall = { _, chapterId, _ ->
            AnalyzeResult(AnalyzeStatus.Done, chapterId)
        },
        render: RenderChapterCall = { _, chapterId, _ ->
            RenderResult(RenderStatus.Done, chapterId)
        },
    ): GenerationExecutor = GenerationExecutor(
        chapters = fixture.chapters,
        jobs = fixture.jobs,
        analyzeChapter = { novelId, chapterId, force ->
            analyzeCalls += AnalyzeCall(novelId, chapterId, force)
            analyze(novelId, chapterId, force)
        },
        renderChapter = { novelId, chapterId, shouldStop ->
            renderCalls += RenderCall(novelId, chapterId)
            render(novelId, chapterId, shouldStop)
        },
    )

    private fun input(
        novelId: Long,
        chapterIds: List<Long>,
        mode: GenerationMode,
        force: Boolean = false,
        jobId: Long = 0L,
    ): GenerationInput = GenerationInput(
        novelId = novelId,
        chapterIds = chapterIds,
        mode = mode,
        force = force,
        jobId = jobId,
    )

    private suspend fun startJob(
        novelId: Long,
        chapterIds: List<Long>,
        mode: GenerationMode,
    ): Long {
        val job = fixture.jobs.start(
            novelId = novelId,
            mode = mode.wire,
            requestedIds = chapterIds,
            stats = mapOf("chaptersTotal" to chapterIds.size.toLong()),
            now = fixture.nextTimestamp(),
        )
        fixture.jobs.markRunning(job.id)
        return job.id
    }

    private suspend fun createNovel(vararg titles: String): Pair<Long, List<Long>> {
        val novelId = fixture.projects.createProject(
            title = "批次项目-${titles.joinToString("-")}",
            sourceName = "batch.txt",
            sourceRelPath = "imports/batch.txt",
            chapters = titles.map { ChapterInput(title = it, rawText = "$it 的正文") },
            createdAt = fixture.nextTimestamp(),
        )
        val ids = fixture.chapters.observeBriefs(novelId).first().map { it.id }
        return novelId to ids
    }

    private suspend fun jobStats(jobId: Long): JsonObject {
        val job = requireNotNull(fixture.jobs.get(jobId))
        return Json.parseToJsonElement(job.statsJson).jsonObject
    }

    private fun JsonObject.value(key: String): Long = getValue(key).jsonPrimitive.long

    private data class AnalyzeCall(
        val novelId: Long,
        val chapterId: Long,
        val force: Boolean,
    )

    private data class RenderCall(
        val novelId: Long,
        val chapterId: Long,
    )
}
