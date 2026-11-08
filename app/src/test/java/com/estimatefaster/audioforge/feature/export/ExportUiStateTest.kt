package com.estimatefaster.audioforge.feature.export

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P5-11：导出状态与 ViewModel 的纯 JVM 测试。
 *
 * 不依赖 Android/Robolectric：范围校验、选中推导、进度与分享列表都是纯状态；
 * ViewModel 用 [StandardTestDispatcher] 替换 Main，注入 fake [ExportGateway]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportUiStateTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------
    // 空态 / 已完成筛选 / 范围校验
    // ------------------------------------------------------------------

    @Test
    fun state_without_chapters_reports_no_chapters() {
        val state = ExportUiState(loading = false)

        assertTrue(state.isEmpty)
        assertEquals(ExportValidationError.NoChapters, state.selectionError)
        assertTrue(state.selectedChapters.isEmpty())
        assertFalse(state.canStart)
    }

    @Test
    fun state_without_completed_chapters_is_empty_and_cannot_start() {
        val state = ExportUiState(
            loading = false,
            chapters = listOf(chapter(0, done = false), chapter(1, done = false)),
        )

        assertTrue(state.isEmpty)
        assertEquals(ExportValidationError.NoCompleted, state.selectionError)
        assertTrue(state.selectedChapters.isEmpty())
        assertFalse(state.canStart)
    }

    @Test
    fun loading_state_suppresses_selection_errors() {
        val state = ExportUiState(loading = true, chapters = sampleChapters())

        assertNull(state.selectionError)
        assertFalse(state.isEmpty)
        assertFalse(state.canStart)
    }

    @Test
    fun all_completed_range_selects_only_done_chapters() {
        val state = sampleState()

        assertEquals(listOf(0, 1, 2), state.selectedChapters.map { it.chapterIdx })
        assertEquals(3, state.selectedCount)
        assertEquals(180_000L, state.selectedDurationMs)
        assertEquals(1_440_000L, state.selectedSizeBytes)
        assertNull(state.selectionError)
        assertTrue(state.canStart)
    }

    @Test
    fun current_chapter_range_requires_current_chapter_done() {
        val ready = sampleState(range = ExportRange.CurrentChapter, current = 1)
        assertEquals(listOf(1), ready.selectedChapters.map { it.chapterIdx })
        assertNull(ready.selectionError)
        assertTrue(ready.canStart)

        val notReady = sampleState(range = ExportRange.CurrentChapter, current = 3)
        assertTrue(notReady.selectedChapters.isEmpty())
        assertEquals(
            ExportValidationError.CurrentChapterNotCompleted,
            notReady.selectionError,
        )
        assertFalse(notReady.canStart)

        val missing = sampleState(range = ExportRange.CurrentChapter, current = 99)
        assertEquals(
            ExportValidationError.CurrentChapterNotCompleted,
            missing.selectionError,
        )
        assertNull(missing.currentChapter)
    }

    @Test
    fun chapter_range_selects_completed_chapters_inside_bounds() {
        val state = sampleState(range = ExportRange.ChapterRange)
            .copy(startText = "2", endText = "4")

        // 1 基第 2..4 章 = idx 1..3；其中 idx 3 还没有音频。
        assertEquals(listOf(1, 2), state.selectedChapters.map { it.chapterIdx })
        assertEquals(2, state.selectedCount)
        assertNull(state.selectionError)
        assertTrue(state.canStart)
    }

    @Test
    fun chapter_range_reports_invalid_input() {
        val base = sampleState(range = ExportRange.ChapterRange)

        assertEquals(
            ExportValidationError.InvalidStart,
            base.copy(startText = "", endText = "3").selectionError,
        )
        assertEquals(
            ExportValidationError.InvalidStart,
            base.copy(startText = "0", endText = "3").selectionError,
        )
        assertEquals(
            ExportValidationError.InvalidStart,
            base.copy(startText = "6", endText = "6").selectionError,
        )
        assertEquals(
            ExportValidationError.InvalidEnd,
            base.copy(startText = "1", endText = "9").selectionError,
        )
        assertEquals(
            ExportValidationError.StartAfterEnd,
            base.copy(startText = "4", endText = "2").selectionError,
        )
    }

    @Test
    fun chapter_range_without_completed_chapters_is_invalid() {
        val base = sampleState(range = ExportRange.ChapterRange)

        assertEquals(
            ExportValidationError.NoCompletedInRange,
            base.copy(startText = "4", endText = "4").selectionError,
        )
        assertEquals(
            ExportValidationError.NoCompletedInRange,
            base.copy(startText = "4", endText = "5").selectionError,
        )

        val inRange = base.copy(startText = "4", endText = "5")
        assertTrue(inRange.selectedChapters.isEmpty())
        assertFalse(inRange.canStart)
    }

    // ------------------------------------------------------------------
    // 进度 / 分享列表 / 格式化
    // ------------------------------------------------------------------

    @Test
    fun progress_is_completed_over_total_and_clamped() {
        val half = sampleState().copy(
            phase = ExportPhase.Exporting,
            total = 4,
            completed = 2,
        )
        assertEquals(0.5f, half.progress, 0.001f)
        assertEquals(2, half.pendingCount)
        assertTrue(half.isExporting)
        assertFalse(half.canShare)

        val idle = sampleState().copy(
            phase = ExportPhase.Exporting,
            total = 0,
            completed = 0,
        )
        assertEquals(0f, idle.progress, 0.001f)

        val over = sampleState().copy(
            phase = ExportPhase.Exporting,
            total = 2,
            completed = 3,
        )
        assertEquals(1f, over.progress, 0.001f)
        assertEquals(0, over.pendingCount)
    }

    @Test
    fun share_uris_follow_success_order() {
        val state = sampleState().copy(
            phase = ExportPhase.Result,
            succeeded = listOf(
                exportedFile(1, "content://media/audio/1"),
                exportedFile(0, "content://media/audio/0"),
            ),
        )

        assertEquals(
            listOf("content://media/audio/1", "content://media/audio/0"),
            state.shareUris,
        )
        assertTrue(state.canShare)
        assertFalse(state.hasFailures)
        assertFalse(state.canRetryFailed)

        val empty = state.copy(succeeded = emptyList())
        assertTrue(empty.shareUris.isEmpty())
        assertFalse(empty.canShare)
    }

    @Test
    fun failure_state_enables_retry_but_not_share_without_success() {
        val state = sampleState().copy(
            phase = ExportPhase.Result,
            failures = listOf(
                ExportFailure(chapterIdx = 1, title = "第 2 章", message = "导出失败"),
            ),
        )

        assertTrue(state.hasFailures)
        assertTrue(state.canRetryFailed)
        assertFalse(state.canShare)
        assertTrue(state.shareUris.isEmpty())
    }

    @Test
    fun format_duration_and_file_size() {
        assertEquals("0:00", formatExportDuration(0L))
        assertEquals("1:01", formatExportDuration(61_000L))
        assertEquals("1:00:01", formatExportDuration(3_601_000L))
        assertEquals("0:00", formatExportDuration(-5_000L))

        assertEquals("0 B", formatExportFileSize(0L))
        assertEquals("512 B", formatExportFileSize(512L))
        assertEquals("1.0 KB", formatExportFileSize(1024L))
        assertEquals("1.5 MB", formatExportFileSize(1_572_864L))
    }

    @Test
    fun duration_estimate_uses_64kbps_rough_rate() {
        assertEquals(0L, estimateDurationMs(0L))
        assertEquals(100_000L, estimateDurationMs(800_000L))
    }

    // ------------------------------------------------------------------
    // ViewModel：加载 / 错误重试
    // ------------------------------------------------------------------

    @Test
    fun view_model_loads_chapters_and_exposes_selection() = runTest(dispatcher) {
        val gateway = FakeGateway(sampleChapters())
        val viewModel = ExportViewModel(NOVEL_ID, gateway)

        assertTrue(viewModel.state.value.loading)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.loading)
        assertNull(state.loadError)
        assertEquals(5, state.chapters.size)
        assertEquals(3, state.completedChapters.size)
        assertEquals(ExportPhase.Selection, state.phase)
        assertEquals(3, state.selectedCount)
        assertEquals(1, gateway.loadCalls)
    }

    @Test
    fun view_model_reports_load_error_and_recovers_on_retry() = runTest(dispatcher) {
        val gateway = FakeGateway(sampleChapters())
        gateway.loadFailure = IllegalStateException("数据库被锁定")
        val viewModel = ExportViewModel(NOVEL_ID, gateway)

        advanceUntilIdle()

        assertEquals("数据库被锁定", viewModel.state.value.loadError)
        assertFalse(viewModel.state.value.loading)
        assertTrue(viewModel.state.value.chapters.isEmpty())

        gateway.loadFailure = null
        viewModel.load()
        advanceUntilIdle()

        assertNull(viewModel.state.value.loadError)
        assertEquals(2, gateway.loadCalls)
        assertEquals(5, viewModel.state.value.chapters.size)
        assertEquals(3, viewModel.state.value.selectedCount)
    }

    @Test
    fun view_model_range_input_keeps_digits_and_prefills_custom_range() = runTest(dispatcher) {
        val viewModel = ExportViewModel(NOVEL_ID, FakeGateway(sampleChapters()))
        advanceUntilIdle()

        viewModel.setRange(ExportRange.ChapterRange)
        assertEquals(ExportRange.ChapterRange, viewModel.state.value.range)
        assertEquals("1", viewModel.state.value.startText)
        assertEquals("5", viewModel.state.value.endText)

        viewModel.setStartText("a12b3456789")
        assertEquals("12345", viewModel.state.value.startText)

        viewModel.setEndText("")
        assertEquals("", viewModel.state.value.endText)
    }

    // ------------------------------------------------------------------
    // ViewModel：顺序导出与进度
    // ------------------------------------------------------------------

    @Test
    fun view_model_exports_sequentially_and_reports_progress() = runTest(dispatcher) {
        val gateway = FakeGateway(sampleChapters())
        val gate = CompletableDeferred<Unit>()
        gateway.beforeExport = { index -> if (index == 1) gate.await() }
        val viewModel = ExportViewModel(NOVEL_ID, gateway)
        advanceUntilIdle()

        viewModel.start()
        runCurrent()

        // 第 2 章在网关里挂起：第 1 章已完成，下一个章名已经公布。
        assertEquals(listOf(0, 1), gateway.calls)
        val mid = viewModel.state.value
        assertEquals(ExportPhase.Exporting, mid.phase)
        assertEquals(1, mid.completed)
        assertEquals(3, mid.total)
        assertEquals("第 2 章", mid.currentChapterTitle)
        assertEquals(1f / 3f, mid.progress, 0.001f)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(0, 1, 2), gateway.calls)
        val done = viewModel.state.value
        assertEquals(ExportPhase.Result, done.phase)
        assertEquals(3, done.completed)
        assertNull(done.currentChapterTitle)
        assertEquals(listOf(0, 1, 2), done.succeeded.map { it.chapterIdx })
        assertEquals(
            listOf("content://media/audio/0", "content://media/audio/1", "content://media/audio/2"),
            done.shareUris,
        )
    }

    @Test
    fun view_model_records_partial_failure_and_keeps_successes() = runTest(dispatcher) {
        val gateway = FakeGateway(sampleChapters())
        gateway.failures = setOf(1)
        val viewModel = ExportViewModel(NOVEL_ID, gateway)
        advanceUntilIdle()

        viewModel.start()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(ExportPhase.Result, state.phase)
        assertEquals(listOf(0, 1, 2), gateway.calls)
        assertEquals(listOf(0, 2), state.succeeded.map { it.chapterIdx })
        assertEquals(1, state.failures.size)
        assertEquals(1, state.failures.first().chapterIdx)
        assertEquals("导出失败：第 2 章", state.failures.first().message)
        assertEquals(3, state.completed)
        assertTrue(state.hasFailures)
        assertTrue(state.canRetryFailed)
        assertTrue(state.canShare)
    }

    @Test
    fun retry_failed_only_retries_failed_chapters() = runTest(dispatcher) {
        val gateway = FakeGateway(sampleChapters())
        gateway.failures = setOf(1)
        val viewModel = ExportViewModel(NOVEL_ID, gateway)
        advanceUntilIdle()

        viewModel.start()
        advanceUntilIdle()
        assertEquals(listOf(0, 1, 2), gateway.calls)

        gateway.failures = emptySet()
        gateway.calls.clear()
        viewModel.retryFailed()
        advanceUntilIdle()

        assertEquals(listOf(1), gateway.calls)
        val state = viewModel.state.value
        assertEquals(ExportPhase.Result, state.phase)
        assertEquals(1, state.total)
        assertEquals(1, state.completed)
        assertEquals(listOf(0, 1, 2), state.succeeded.map { it.chapterIdx })
        assertTrue(state.failures.isEmpty())
        assertEquals(
            listOf("content://media/audio/0", "content://media/audio/1", "content://media/audio/2"),
            state.shareUris,
        )
    }

    @Test
    fun retry_without_failures_is_noop() = runTest(dispatcher) {
        val gateway = FakeGateway(sampleChapters())
        val viewModel = ExportViewModel(NOVEL_ID, gateway)
        advanceUntilIdle()

        viewModel.start()
        advanceUntilIdle()
        gateway.calls.clear()

        viewModel.retryFailed()
        advanceUntilIdle()

        assertTrue(gateway.calls.isEmpty())
        assertEquals(ExportPhase.Result, viewModel.state.value.phase)
        assertTrue(viewModel.state.value.failures.isEmpty())
    }

    @Test
    fun repeated_start_while_exporting_is_deduplicated() = runTest(dispatcher) {
        val gateway = FakeGateway(sampleChapters())
        val gate = CompletableDeferred<Unit>()
        gateway.beforeExport = { gate.await() }
        val viewModel = ExportViewModel(NOVEL_ID, gateway)
        advanceUntilIdle()

        viewModel.start()
        runCurrent()
        viewModel.start()
        viewModel.start()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(0, 1, 2), gateway.calls)
        assertEquals(3, viewModel.state.value.succeeded.size)
    }

    // ------------------------------------------------------------------
    // ViewModel：取消 / 分享语义
    // ------------------------------------------------------------------

    @Test
    fun cancel_stops_remaining_chapters_and_keeps_partial_result() = runTest(dispatcher) {
        val gateway = FakeGateway(sampleChapters())
        val gate = CompletableDeferred<Unit>()
        gateway.beforeExport = { index -> if (index == 1) gate.await() }
        val viewModel = ExportViewModel(NOVEL_ID, gateway)
        advanceUntilIdle()

        viewModel.start()
        runCurrent()
        assertEquals(listOf(0, 1), gateway.calls)

        viewModel.cancel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(ExportPhase.Result, state.phase)
        assertTrue(state.cancelled)
        assertFalse(state.isExporting)
        assertEquals(1, state.completed)
        assertEquals(listOf(0, 1), gateway.calls)
        assertEquals(listOf("content://media/audio/0"), state.shareUris)
        assertTrue(state.failures.isEmpty())
        assertNull(state.currentChapterTitle)
    }

    @Test
    fun cancel_when_not_exporting_is_noop() = runTest(dispatcher) {
        val viewModel = ExportViewModel(NOVEL_ID, FakeGateway(sampleChapters()))
        advanceUntilIdle()

        viewModel.cancel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(ExportPhase.Selection, state.phase)
        assertFalse(state.cancelled)
        assertEquals(3, state.selectedCount)
    }

    @Test
    fun cancel_notice_semantics_are_visible_in_state() = runTest(dispatcher) {
        val gateway = FakeGateway(sampleChapters())
        gateway.beforeExport = { CompletableDeferred<Unit>().await() }
        val viewModel = ExportViewModel(NOVEL_ID, gateway)
        advanceUntilIdle()

        viewModel.start()
        runCurrent()
        assertEquals(ExportPhase.Exporting, viewModel.state.value.phase)

        viewModel.cancel()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.cancelled)
        assertEquals(0, viewModel.state.value.succeeded.size)
        assertEquals(0, viewModel.state.value.completed)
    }

    // ------------------------------------------------------------------
    // 测试数据 / fake
    // ------------------------------------------------------------------

    private fun chapter(
        index: Int,
        done: Boolean = true,
        durationMs: Long = 60_000L,
        sizeBytes: Long = 480_000L,
    ): ExportChapterUi = ExportChapterUi(
        chapterIdx = index,
        title = "第 ${index + 1} 章",
        audioDone = done,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
    )

    /**
     * 5 章：前 3 章已完成音频，后 2 章未完成。
     */
    private fun sampleChapters(): List<ExportChapterUi> = listOf(
        chapter(0),
        chapter(1),
        chapter(2),
        chapter(3, done = false),
        chapter(4, done = false),
    )

    private fun sampleState(
        range: ExportRange = ExportRange.AllCompleted,
        current: Int? = null,
    ): ExportUiState = ExportUiState(
        loading = false,
        chapters = sampleChapters(),
        currentChapterIdx = current,
        range = range,
    )

    private fun exportedFile(index: Int, uri: String): ExportedFile = ExportedFile(
        chapterIdx = index,
        title = "第 ${index + 1} 章",
        displayName = "导出-$index.m4a",
        uri = uri,
    )

    private class FakeGateway(
        private val loadedChapters: List<ExportChapterUi>,
    ) : ExportGateway {

        var loadFailure: Throwable? = null
        var failures: Set<Int> = emptySet()
        var beforeExport: (suspend (Int) -> Unit)? = null
        var loadCalls: Int = 0
        val calls: MutableList<Int> = mutableListOf()

        override suspend fun loadChapters(novelId: Long): List<ExportChapterUi> {
            loadCalls++
            loadFailure?.let { throw it }
            return loadedChapters
        }

        override suspend fun exportChapter(
            novelId: Long,
            chapterIdx: Int,
            title: String,
        ): ExportOutcome {
            calls += chapterIdx
            beforeExport?.invoke(chapterIdx)
            return if (chapterIdx in failures) {
                ExportOutcome.Failure("导出失败：$title")
            } else {
                ExportOutcome.Success(
                    uri = "content://media/audio/$chapterIdx",
                    displayName = "导出-$chapterIdx.m4a",
                )
            }
        }
    }

    private companion object {
        const val NOVEL_ID = 42L
    }
}
