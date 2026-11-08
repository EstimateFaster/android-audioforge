package com.estimatefaster.audioforge.feature.project

import com.estimatefaster.audioforge.data.db.ChapterBrief
import com.estimatefaster.audioforge.domain.jobs.BatchParams
import com.estimatefaster.audioforge.domain.jobs.StartResult
import com.estimatefaster.audioforge.domain.jobs.StopResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4-06：批次 Sheet 的纯 JVM 测试。
 *
 * 不依赖 Android/Robolectric/WorkManager：`BatchRunnerGateway` 由记录型 fake 替代，
 * ViewModel 通过 `externalScope` 注入 `TestScope`，用 `advanceUntilIdle()` 推进
 * 开始/停止协程。覆盖：
 *
 * - 默认范围（全部未完成）/全选/起止校验/单批上限；
 * - 并发 1..8 校验；
 * - 按模式判断缺失 Key，并在无 Key 时禁止开始；
 * - 模式 wire 映射、force 透传、起止范围展开 chapterIds；
 * - Started/AlreadyRunning/Failure；Stopping/NotRunning/Failure 与请求中状态；
 * - 估算的章节数/分钟/费用。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BatchUiStateTest {

    // ------------------------------------------------------------------
    // 默认范围 / 全选
    // ------------------------------------------------------------------

    @Test
    fun default_range_covers_all_unfinished_chapters() {
        val chapters = listOf(
            chapter(1, parseDone = true, audioDone = true),
            chapter(2, parseDone = true, audioDone = false),
            chapter(3, parseDone = false, audioDone = false),
            chapter(4, parseDone = true, audioDone = true),
            chapter(5, parseDone = false, audioDone = false),
        )

        val state = BatchUiState.initial(
            novelId = 1L,
            chapters = chapters,
            defaultConcurrency = 5,
        )

        assertEquals("2", state.startText)
        assertEquals("5", state.endText)
        // 范围是闭区间：中间已完成的第 4 章也会被展开进 chapterIds。
        assertEquals(listOf(200L, 300L, 400L, 500L), state.selectedChapterIds)
        assertEquals(5, state.concurrency)
        assertTrue(state.validationErrors.isEmpty())
        assertTrue(state.canStart)
    }

    @Test
    fun chapter_projection_converts_zero_based_idx_to_one_based_number() {
        val brief = ChapterBrief(
            id = 11L,
            idx = 0,
            title = "第一章",
            parseStatus = "done",
            audioStatus = "pending",
            parseWarn = null,
            segCount = 0,
            segDone = 0,
        )

        val chapter = BatchChapterUi.from(brief)

        assertEquals(1, chapter.number)
        assertTrue(chapter.parseDone)
        assertFalse(chapter.audioDone)
        assertFalse(chapter.isComplete)
    }

    @Test
    fun default_range_falls_back_to_whole_book_when_nothing_is_unfinished() {
        val chapters = (1..3).map { chapter(it, parseDone = true, audioDone = true) }

        val state = BatchUiState.initial(novelId = 1L, chapters = chapters)

        assertEquals("1", state.startText)
        assertEquals("3", state.endText)
        assertEquals(3, state.selectedChapterCount)
    }

    @Test
    fun select_unfinished_and_select_all_reset_the_range() = batchTest {
        val chapters = listOf(
            chapter(1, parseDone = true, audioDone = true),
            chapter(2, parseDone = false, audioDone = false),
            chapter(3, parseDone = false, audioDone = false),
            chapter(4, parseDone = true, audioDone = true),
        )
        val viewModel = newViewModel(chapters = chapters)

        viewModel.selectAll()
        assertEquals("1", viewModel.state.value.startText)
        assertEquals("4", viewModel.state.value.endText)
        assertEquals(listOf(100L, 200L, 300L, 400L), viewModel.state.value.selectedChapterIds)

        viewModel.selectUnfinished()
        assertEquals("2", viewModel.state.value.startText)
        assertEquals("3", viewModel.state.value.endText)
        assertEquals(listOf(200L, 300L), viewModel.state.value.selectedChapterIds)
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    @Test
    fun range_validation_rejects_empty_non_numeric_out_of_bounds_and_reversed() {
        val state = BatchUiState.initial(novelId = 1L, chapters = (1..5).map { chapter(it) })

        assertEquals(
            listOf(BatchValidationError.InvalidStart),
            state.copy(startText = "").validationErrors,
        )
        assertEquals(
            listOf(BatchValidationError.InvalidStart),
            state.copy(startText = "abc").validationErrors,
        )
        assertEquals(
            listOf(BatchValidationError.InvalidStart),
            state.copy(startText = "0").validationErrors,
        )
        assertEquals(
            listOf(BatchValidationError.InvalidStart),
            state.copy(startText = "6").validationErrors,
        )
        assertEquals(
            listOf(BatchValidationError.InvalidEnd),
            state.copy(endText = "abc").validationErrors,
        )
        assertEquals(
            listOf(BatchValidationError.InvalidEnd),
            state.copy(endText = "0").validationErrors,
        )
        assertEquals(
            listOf(BatchValidationError.InvalidEnd),
            state.copy(endText = "6").validationErrors,
        )
        assertEquals(
            listOf(BatchValidationError.StartAfterEnd),
            state.copy(startText = "4", endText = "2").validationErrors,
        )

        val valid = state.copy(startText = "2", endText = "4")
        assertTrue(valid.validationErrors.isEmpty())
        assertEquals(listOf(200L, 300L, 400L), valid.selectedChapterIds)
    }

    @Test
    fun too_many_chapters_are_rejected_before_runner() = batchTest {
        val chapters = (1..25).map { chapter(it) }
        val state = BatchUiState.initial(novelId = 1L, chapters = chapters)

        assertTrue(BatchValidationError.TooManyChapters in state.validationErrors)
        assertNull(state.toBatchParams())
        assertFalse(state.canStart)

        val runner = FakeBatchRunner()
        val viewModel = newViewModel(chapters = chapters, runner = runner)
        viewModel.start()
        advanceUntilIdle()
        assertTrue(runner.starts.isEmpty())
        assertNull(viewModel.state.value.startStatus)
    }

    @Test
    fun concurrency_accepts_one_to_eight_and_rejects_outside() {
        val state = BatchUiState.initial(novelId = 1L, chapters = (1..5).map { chapter(it) })

        (1..BatchParams.MAX_CONCURRENCY).forEach { concurrency ->
            val candidate = state.copy(concurrency = concurrency)
            assertFalse(
                "并发 $concurrency 不应报错",
                BatchValidationError.InvalidConcurrency in candidate.validationErrors,
            )
            assertEquals(concurrency, candidate.toBatchParams()?.concurrency)
        }

        listOf(0, -1, BatchParams.MAX_CONCURRENCY + 1).forEach { concurrency ->
            val candidate = state.copy(concurrency = concurrency)
            assertEquals(
                "并发 $concurrency",
                listOf(BatchValidationError.InvalidConcurrency),
                candidate.validationErrors,
            )
            assertNull(candidate.toBatchParams())
        }
    }

    @Test
    fun no_key_disables_start_and_is_mode_specific() {
        val chapters = (1..3).map { chapter(it) }
        val fullNoKeys = BatchUiState.initial(
            novelId = 1L,
            chapters = chapters,
            hasLlmKey = false,
            hasFishKey = false,
        )
        assertEquals(
            listOf(BatchKeyKind.Llm, BatchKeyKind.Fish),
            fullNoKeys.missingKeys,
        )
        assertFalse(fullNoKeys.canStart)

        val parseNoLlm = fullNoKeys.copy(mode = BatchMode.Parse, hasFishKey = true)
        assertEquals(listOf(BatchKeyKind.Llm), parseNoLlm.missingKeys)
        assertFalse(parseNoLlm.canStart)

        val renderNoFish = fullNoKeys.copy(mode = BatchMode.Render, hasLlmKey = true)
        assertEquals(listOf(BatchKeyKind.Fish), renderNoFish.missingKeys)
        assertFalse(renderNoFish.canStart)

        // 仅解析不需要 Fish Key；仅渲染不需要 LLM Key。
        assertTrue(parseNoLlm.copy(hasLlmKey = true).canStart)
        assertTrue(renderNoFish.copy(hasFishKey = true).canStart)
    }

    @Test
    fun start_with_missing_key_does_not_call_runner() = batchTest {
        val runner = FakeBatchRunner()
        val viewModel = newViewModel(
            chapters = (1..3).map { chapter(it) },
            runner = runner,
            hasLlmKey = false,
            hasFishKey = false,
        )

        viewModel.start()
        advanceUntilIdle()

        assertTrue(runner.starts.isEmpty())
        assertNull(viewModel.state.value.startStatus)
        assertFalse(viewModel.state.value.canStart)

        // Key 配置完成后（例如从设置页返回）立即解除禁用。
        viewModel.updateKeys(hasLlmKey = true, hasFishKey = true)
        assertTrue(viewModel.state.value.canStart)
    }

    // ------------------------------------------------------------------
    // 模式 / force / chapterIds / 估算
    // ------------------------------------------------------------------

    @Test
    fun mode_maps_to_domain_wire_values() {
        assertEquals("full", BatchMode.Full.wire)
        assertEquals("parse", BatchMode.Parse.wire)
        assertEquals("render", BatchMode.Render.wire)
        assertTrue(BatchMode.Full.requiresLlm)
        assertTrue(BatchMode.Full.requiresFish)
        assertFalse(BatchMode.Parse.requiresFish)
        assertFalse(BatchMode.Render.requiresLlm)

        val state = BatchUiState.initial(
            novelId = 1L,
            chapters = (1..2).map { chapter(it) },
        )
        assertEquals("parse", state.copy(mode = BatchMode.Parse).toBatchParams()?.mode)
        assertEquals("render", state.copy(mode = BatchMode.Render).toBatchParams()?.mode)
    }

    @Test
    fun params_expand_interval_preserve_force_and_concurrency() {
        val state = BatchUiState.initial(
            novelId = 7L,
            chapters = (1..5).map { chapter(it) },
        ).copy(
            startText = "2",
            endText = "4",
            force = true,
            concurrency = 7,
        )

        val params = state.toBatchParams()

        assertEquals(
            BatchParams(
                novelId = 7L,
                chapterIds = listOf(200L, 300L, 400L),
                mode = "full",
                force = true,
                concurrency = 7,
            ),
            params,
        )
        assertNull(params?.validate())
    }

    @Test
    fun estimate_scales_with_selected_chapters_and_mode() {
        val state = BatchUiState.initial(
            novelId = 1L,
            chapters = (1..5).map { chapter(it) },
        )

        val full = state.copy(mode = BatchMode.Full)
        assertEquals(5, full.selectedChapterCount)
        assertEquals(15, full.estimatedMinutes)
        assertEquals(75, full.estimatedCostCents)
        assertEquals("0.75", full.estimatedCostText)

        val parse = state.copy(mode = BatchMode.Parse)
        assertEquals(5, parse.estimatedMinutes)
        assertEquals(10, parse.estimatedCostCents)
        assertEquals("0.10", parse.estimatedCostText)

        val render = state.copy(mode = BatchMode.Render)
        assertEquals(10, render.estimatedMinutes)
        assertEquals(50, render.estimatedCostCents)
        assertEquals("0.50", render.estimatedCostText)

        val narrow = full.copy(startText = "3", endText = "4")
        assertEquals(2, narrow.selectedChapterCount)
        assertEquals(6, narrow.estimatedMinutes)
    }

    // ------------------------------------------------------------------
    // 开始结果
    // ------------------------------------------------------------------

    @Test
    fun start_maps_started_and_emits_one_shot_event() = batchTest {
        val runner = FakeBatchRunner().apply {
            startResult = StartResult.Started(jobId = 42L, workName = "generation-1")
        }
        val viewModel = newViewModel(
            chapters = (1..3).map { chapter(it) },
            runner = runner,
        )

        viewModel.start()
        assertTrue(viewModel.state.value.starting)
        assertEquals(BatchStartStatus.Starting, viewModel.state.value.startStatus)

        advanceUntilIdle()

        assertEquals(1, runner.starts.size)
        val params = runner.starts.single()
        assertEquals(1L, params.novelId)
        assertEquals(listOf(100L, 200L, 300L), params.chapterIds)
        assertEquals("full", params.mode)
        assertFalse(params.force)

        assertEquals(BatchStartStatus.Started(42L), viewModel.state.value.startStatus)
        assertFalse(viewModel.state.value.starting)
        assertEquals(BatchSheetEvent.Started(42L), viewModel.events.first())
    }

    @Test
    fun start_maps_already_running_and_failure() = batchTest {
        val alreadyRunning = FakeBatchRunner().apply {
            startResult = StartResult.AlreadyRunning(jobId = 9L)
        }
        val first = newViewModel(chapters = (1..2).map { chapter(it) }, runner = alreadyRunning)
        first.start()
        advanceUntilIdle()
        assertEquals(
            BatchStartStatus.AlreadyRunning(jobId = 9L),
            first.state.value.startStatus,
        )

        val failure = FakeBatchRunner().apply {
            startResult = StartResult.Failure("任务入队失败，请稍后重试")
        }
        val second = newViewModel(chapters = (1..2).map { chapter(it) }, runner = failure)
        second.start()
        advanceUntilIdle()
        assertEquals(
            BatchStartStatus.Failure("任务入队失败，请稍后重试"),
            second.state.value.startStatus,
        )
    }

    @Test
    fun start_maps_thrown_exception_to_failure() = batchTest {
        val runner = FakeBatchRunner().apply {
            startFailure = IllegalStateException("数据库已锁定")
        }
        val viewModel = newViewModel(chapters = (1..2).map { chapter(it) }, runner = runner)

        viewModel.start()
        advanceUntilIdle()

        assertEquals(
            BatchStartStatus.Failure("数据库已锁定"),
            viewModel.state.value.startStatus,
        )
        assertFalse(viewModel.state.value.starting)
    }

    // ------------------------------------------------------------------
    // 停止结果
    // ------------------------------------------------------------------

    @Test
    fun stop_maps_stopping_and_shows_requesting_first() = batchTest {
        val runner = FakeBatchRunner().apply {
            stopResult = StopResult.Stopping(jobId = 5L)
        }
        val viewModel = newViewModel(chapters = (1..3).map { chapter(it) }, runner = runner)

        viewModel.stop()
        assertTrue(viewModel.state.value.stopping)
        assertEquals(BatchStopStatus.Requesting, viewModel.state.value.stopStatus)

        advanceUntilIdle()

        assertEquals(listOf(1L), runner.stops)
        assertEquals(BatchStopStatus.Stopping(jobId = 5L), viewModel.state.value.stopStatus)
        assertFalse(viewModel.state.value.stopping)
    }

    @Test
    fun stop_maps_not_running_failure_and_exception() = batchTest {
        val notRunning = FakeBatchRunner().apply {
            stopResult = StopResult.NotRunning(novelId = 1L)
        }
        val first = newViewModel(chapters = (1..2).map { chapter(it) }, runner = notRunning)
        first.stop()
        advanceUntilIdle()
        assertEquals(BatchStopStatus.NotRunning(novelId = 1L), first.state.value.stopStatus)

        val failure = FakeBatchRunner().apply {
            stopResult = StopResult.Failure("任务 #5 停止请求未被接受")
        }
        val second = newViewModel(chapters = (1..2).map { chapter(it) }, runner = failure)
        second.stop()
        advanceUntilIdle()
        assertEquals(
            BatchStopStatus.Failure("任务 #5 停止请求未被接受"),
            second.state.value.stopStatus,
        )

        val thrown = FakeBatchRunner().apply {
            stopFailure = IllegalArgumentException("取消失败")
        }
        val third = newViewModel(chapters = (1..2).map { chapter(it) }, runner = thrown)
        third.stop()
        advanceUntilIdle()
        assertEquals(BatchStopStatus.Failure("取消失败"), third.state.value.stopStatus)
    }

    // ------------------------------------------------------------------
    // 测试夹具
    // ------------------------------------------------------------------

    /** 在 `runTest` 的调度器上安装 Main dispatcher，并注入共享 scheduler 的作用域。 */
    private fun batchTest(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.newViewModel(
        novelId: Long = 1L,
        chapters: List<BatchChapterUi> = (1..3).map { chapter(it) },
        runner: FakeBatchRunner = FakeBatchRunner(),
        defaultConcurrency: Int = BatchParams.DEFAULT_CONCURRENCY,
        hasLlmKey: Boolean = true,
        hasFishKey: Boolean = true,
    ): BatchViewModel = BatchViewModel(
        novelId = novelId,
        chapters = chapters,
        runner = runner,
        defaultConcurrency = defaultConcurrency,
        hasLlmKey = hasLlmKey,
        hasFishKey = hasFishKey,
        externalScope = this,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    /** 章节夹具：id=idx*100，默认未完成。 */
    private fun chapter(
        idx: Int,
        parseDone: Boolean = false,
        audioDone: Boolean = false,
    ): BatchChapterUi = BatchChapterUi(
        id = idx * 100L,
        number = idx,
        parseDone = parseDone,
        audioDone = audioDone,
    )

    /** 记录参数、按配置返回结果的 fake Runner；不启动 WorkManager。 */
    private class FakeBatchRunner : BatchRunnerGateway {

        var startResult: StartResult =
            StartResult.Started(jobId = 1L, workName = "generation-1")
        var stopResult: StopResult = StopResult.Stopping(jobId = 1L)
        var startFailure: Exception? = null
        var stopFailure: Exception? = null

        val starts: MutableList<BatchParams> = mutableListOf()
        val stops: MutableList<Long> = mutableListOf()

        override suspend fun start(params: BatchParams): StartResult {
            starts += params
            startFailure?.let { throw it }
            return startResult
        }

        override suspend fun stop(novelId: Long): StopResult {
            stops += novelId
            stopFailure?.let { throw it }
            return stopResult
        }
    }
}
