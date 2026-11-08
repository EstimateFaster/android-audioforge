package com.estimatefaster.audioforge.feature.projects

import com.estimatefaster.audioforge.domain.usecase.ImportNovelUseCase
import com.estimatefaster.audioforge.domain.usecase.ImportResult
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * P4-04：导入 Sheet 的纯 JVM 测试。
 *
 * 不依赖 Android/Robolectric：SAF 与用例分别由 [ImportSource] / [ImportRunner]
 * 的记录型 fake 替代；ViewModel 通过 `externalScope` 注入 `TestScope`，用
 * `advanceUntilIdle()` 推进导入协程。测试覆盖：
 *
 * - 文件选择与确认前校验（非 txt/空/超大/大小未知）；
 * - 进度 0..1、成功状态与一次性事件；
 * - 编码失败 → 选候选 → 重开源流重试成功；
 * - 重名 → 打开已有 / 取消；
 * - 失败重试、源打不开、取消清理。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportUiStateTest {

    // ------------------------------------------------------------------
    // 文件选择与确认前校验
    // ------------------------------------------------------------------

    @Test
    fun validate_selection_rejects_non_txt_empty_oversized_and_negative_size() {
        assertEquals(ImportValidationError.NotTxt, validateImportSelection("小说.pdf", 10L))
        assertEquals(ImportValidationError.NotTxt, validateImportSelection("小说.txt.bak", 10L))
        assertEquals(ImportValidationError.EmptyFile, validateImportSelection("空.txt", 0L))
        assertEquals(
            ImportValidationError.TooLarge,
            validateImportSelection("过大.txt", ImportNovelUseCase.MAX_IMPORT_BYTES + 1L),
        )
        assertEquals(ImportValidationError.InvalidSize, validateImportSelection("坏.txt", -1L))

        assertNull(validateImportSelection("小说.TXT", 10L))
        assertNull(validateImportSelection("未知大小.txt", null))
    }

    @Test
    fun non_txt_pick_is_rejected_without_opening_source_or_use_case() = importTest {
        val importer = FakeImporter()
        val source = FakeImportSource(
            metadata = ImportSource.Metadata(displayName = "小说.pdf", sizeBytes = 1_024L),
        )
        val viewModel = newViewModel(importer = importer, source = source)

        viewModel.onFilePicked("content://picked")

        val state = viewModel.state.value
        assertEquals(ImportPhase.Error, state.phase)
        assertTrue("实际是 ${state.error}", state.error is ImportError.Validation)
        assertEquals(
            ImportValidationError.NotTxt,
            (state.error as ImportError.Validation).reason,
        )
        // 保留被拒绝的文件信息，便于展示具体是哪个文件。
        assertEquals("小说.pdf", state.selection?.displayName)
        assertFalse(state.canRetry)
        assertTrue(importer.calls.isEmpty())
        assertTrue("用例不应打开源流", source.openedUris.isEmpty())
    }

    @Test
    fun empty_oversized_and_negative_size_are_rejected_before_use_case() = importTest {
        val cases = listOf(
            0L to ImportValidationError.EmptyFile,
            (ImportNovelUseCase.MAX_IMPORT_BYTES + 1L) to ImportValidationError.TooLarge,
            -1L to ImportValidationError.InvalidSize,
        )
        cases.forEach { (sizeBytes, expected) ->
            val importer = FakeImporter()
            val source = FakeImportSource(
                metadata = ImportSource.Metadata(displayName = "长夜.txt", sizeBytes = sizeBytes),
            )
            val viewModel = newViewModel(importer = importer, source = source)

            viewModel.onFilePicked("content://novel")

            val state = viewModel.state.value
            assertEquals("size=$sizeBytes", ImportPhase.Error, state.phase)
            assertTrue("size=$sizeBytes 实际是 ${state.error}", state.error is ImportError.Validation)
            assertEquals(
                expected,
                (state.error as ImportError.Validation).reason,
            )
            assertFalse(state.canRetry)
            assertTrue(importer.calls.isEmpty())
            assertTrue(source.openedUris.isEmpty())
            viewModel.cancel()
        }
    }

    @Test
    fun unreadable_metadata_becomes_source_unavailable_error() = importTest {
        val importer = FakeImporter()
        val source = FakeImportSource(metadata = null)
        val viewModel = newViewModel(importer = importer, source = source)

        viewModel.onFilePicked("content://broken")

        val state = viewModel.state.value
        assertEquals(ImportPhase.Error, state.phase)
        assertEquals(ImportError.SourceUnavailable, state.error)
        assertNull(state.selection)
        assertFalse(state.canRetry)
        assertTrue(importer.calls.isEmpty())
    }

    @Test
    fun valid_txt_pick_transitions_to_selected_with_file_info() = importTest {
        val importer = FakeImporter()
        val source = FakeImportSource(
            metadata = ImportSource.Metadata(displayName = "长夜将明.TXT", sizeBytes = 2_048L),
        )
        val viewModel = newViewModel(importer = importer, source = source)

        viewModel.onFilePicked("content://novel")

        val state = viewModel.state.value
        assertEquals(ImportPhase.Selected, state.phase)
        assertEquals("content://novel", state.selection?.uri)
        assertEquals("长夜将明.TXT", state.selection?.displayName)
        assertEquals(2_048L, state.selection?.sizeBytes ?: -1L)
        assertEquals(0f, state.progress, 0f)
        assertTrue(state.canStartImport)
        assertTrue(state.isProgressDeterminate)
        assertTrue(importer.calls.isEmpty())
        assertTrue(source.openedUris.isEmpty())
    }

    @Test
    fun unknown_size_is_allowed_but_progress_stays_indeterminate() = importTest {
        val source = FakeImportSource(
            metadata = ImportSource.Metadata(displayName = "大小未知.txt", sizeBytes = null),
        )
        val viewModel = newViewModel(source = source)

        viewModel.onFilePicked("content://unknown")

        val state = viewModel.state.value
        assertEquals(ImportPhase.Selected, state.phase)
        assertTrue(state.canStartImport)
        assertFalse("大小未知时不能伪造百分比", state.isProgressDeterminate)
    }

    // ------------------------------------------------------------------
    // 进度 / 成功
    // ------------------------------------------------------------------

    @Test
    fun import_reports_progress_and_emits_success_event() = importTest {
        val importer = FakeImporter().apply {
            handler = { call ->
                call.onProgress?.invoke(25L)
                call.onProgress?.invoke(50L)
                call.onProgress?.invoke(100L)
                ImportResult.Success(novelId = 7L, chapterCount = 3, charCount = 1_000)
            }
        }
        val source = FakeImportSource(
            metadata = ImportSource.Metadata(displayName = "长夜将明.txt", sizeBytes = 100L),
        )
        val viewModel = newViewModel(importer = importer, source = source)

        val states = mutableListOf<ImportUiState>()
        val events = mutableListOf<ImportEvent>()
        val collectors = mutableListOf<Job>()
        collectors += launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect { states += it }
        }
        collectors += launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { events += it }
        }

        viewModel.onFilePicked("content://novel")
        viewModel.startImport()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(ImportPhase.Success, state.phase)
        assertEquals(7L, state.successNovelId ?: -1L)
        assertEquals(1f, state.progress, 0f)
        assertEquals(100, state.percent)
        assertEquals(1, importer.calls.size)
        assertEquals(100L, importer.calls.single().sizeBytes)

        // 中途确实按累计字节数更新过 0..1 的进度。
        assertTrue(
            "没有看到 25% 的进度：${states.map { it.phase to it.progress }}",
            states.any { it.phase == ImportPhase.Importing && it.progress == 0.25f },
        )
        assertTrue(states.any { it.progress == 0.5f })
        assertEquals(listOf<ImportEvent>(ImportEvent.Imported(7L)), events)

        collectors.forEach { it.cancel() }
    }

    // ------------------------------------------------------------------
    // 编码重试
    // ------------------------------------------------------------------

    @Test
    fun needs_encoding_then_candidate_retry_reopens_stream_and_succeeds() = importTest {
        val importer = FakeImporter().apply {
            enqueue(
                ImportResult.NeedsEncoding(listOf("GB18030", "Big5", "UTF-8")),
                ImportResult.Success(novelId = 9L, chapterCount = 2, charCount = 500),
            )
        }
        val source = FakeImportSource(
            metadata = ImportSource.Metadata(displayName = "低中文.txt", sizeBytes = 64L),
        )
        val viewModel = newViewModel(importer = importer, source = source)
        val events = mutableListOf<ImportEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { events += it }
        }

        viewModel.onFilePicked("content://gbk")
        viewModel.startImport()
        advanceUntilIdle()

        val needsEncoding = viewModel.state.value
        assertEquals(ImportPhase.NeedsEncoding, needsEncoding.phase)
        assertEquals(listOf("GB18030", "Big5", "UTF-8"), needsEncoding.encodingCandidates)
        assertTrue(needsEncoding.canChooseEncoding)
        assertNull("自动识别失败时还没有手动编码", needsEncoding.selectedEncoding)
        assertEquals(1, importer.calls.size)
        assertNull(importer.calls[0].encoding)
        assertEquals(1, source.openedUris.size)

        viewModel.chooseEncoding("GB18030")
        advanceUntilIdle()

        val success = viewModel.state.value
        assertEquals(ImportPhase.Success, success.phase)
        assertEquals(9L, success.successNovelId ?: -1L)
        assertEquals(2, importer.calls.size)
        assertEquals("GB18030", importer.calls[1].encoding)
        // 用例在 NeedsEncoding 时已删除复制文件，必须重新打开源流。
        assertEquals(2, source.openedUris.size)
        assertEquals(listOf<ImportEvent>(ImportEvent.Imported(9L)), events)

        collector.cancel()
    }

    // ------------------------------------------------------------------
    // 重名
    // ------------------------------------------------------------------

    @Test
    fun duplicate_offers_open_existing_and_emits_one_shot_event() = importTest {
        val importer = FakeImporter().apply {
            enqueue(ImportResult.Duplicate(existingNovelId = 42L, title = "长夜将明"))
        }
        val source = FakeImportSource(
            metadata = ImportSource.Metadata(displayName = "长夜将明.txt", sizeBytes = 128L),
        )
        val viewModel = newViewModel(importer = importer, source = source)
        val events = mutableListOf<ImportEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { events += it }
        }

        viewModel.onFilePicked("content://novel")
        viewModel.startImport()
        advanceUntilIdle()

        val duplicateState = viewModel.state.value
        assertEquals(ImportPhase.Duplicate, duplicateState.phase)
        assertEquals(ImportDuplicate(existingNovelId = 42L, title = "长夜将明"), duplicateState.duplicate)
        assertNull(duplicateState.error)

        viewModel.openExistingProject()
        advanceUntilIdle()

        assertEquals(ImportPhase.Idle, viewModel.state.value.phase)
        assertNull(viewModel.state.value.selection)
        assertEquals(listOf<ImportEvent>(ImportEvent.OpenExisting(42L)), events)
        // 打开已有项目不应再次调用用例。
        assertEquals(1, importer.calls.size)

        collector.cancel()
    }

    @Test
    fun duplicate_cancel_discards_selection_without_opening_anything() = importTest {
        val importer = FakeImporter().apply {
            enqueue(ImportResult.Duplicate(existingNovelId = 42L, title = "长夜将明"))
        }
        val source = FakeImportSource(
            metadata = ImportSource.Metadata(displayName = "长夜将明.txt", sizeBytes = 128L),
        )
        val viewModel = newViewModel(importer = importer, source = source)
        val events = mutableListOf<ImportEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { events += it }
        }

        viewModel.onFilePicked("content://novel")
        viewModel.startImport()
        advanceUntilIdle()
        assertEquals(ImportPhase.Duplicate, viewModel.state.value.phase)

        viewModel.dismissDuplicate()
        advanceUntilIdle()

        assertEquals(ImportPhase.Idle, viewModel.state.value.phase)
        assertNull(viewModel.state.value.duplicate)
        assertNull(viewModel.state.value.selection)
        assertTrue(events.isEmpty())

        collector.cancel()
    }

    // ------------------------------------------------------------------
    // 失败 / 重试 / 取消
    // ------------------------------------------------------------------

    @Test
    fun failure_can_be_retried_without_reselecting_the_file() = importTest {
        val importer = FakeImporter().apply {
            enqueue(
                ImportResult.Failure("磁盘已满"),
                ImportResult.Success(novelId = 5L, chapterCount = 1, charCount = 10),
            )
        }
        val source = FakeImportSource(
            metadata = ImportSource.Metadata(displayName = "长夜.txt", sizeBytes = 32L),
        )
        val viewModel = newViewModel(importer = importer, source = source)

        viewModel.onFilePicked("content://novel")
        viewModel.startImport()
        advanceUntilIdle()

        val errorState = viewModel.state.value
        assertEquals(ImportPhase.Error, errorState.phase)
        assertEquals(ImportError.ImportFailed("磁盘已满"), errorState.error)
        assertTrue(errorState.canRetry)
        assertEquals("长夜.txt", errorState.selection?.displayName)

        viewModel.retry()
        advanceUntilIdle()

        assertEquals(ImportPhase.Success, viewModel.state.value.phase)
        assertEquals(5L, viewModel.state.value.successNovelId ?: -1L)
        assertEquals(2, importer.calls.size)
        assertEquals(2, source.openedUris.size)
    }

    @Test
    fun retry_after_manual_encoding_failure_keeps_the_chosen_encoding() = importTest {
        val importer = FakeImporter().apply {
            enqueue(
                ImportResult.NeedsEncoding(listOf("GB18030", "UTF-8")),
                ImportResult.Failure("无法用 GB18030 严格解码该文件"),
                ImportResult.Success(novelId = 11L, chapterCount = 1, charCount = 20),
            )
        }
        val source = FakeImportSource(
            metadata = ImportSource.Metadata(displayName = "低中文.txt", sizeBytes = 48L),
        )
        val viewModel = newViewModel(importer = importer, source = source)

        viewModel.onFilePicked("content://gbk")
        viewModel.startImport()
        advanceUntilIdle()
        assertEquals(ImportPhase.NeedsEncoding, viewModel.state.value.phase)

        viewModel.chooseEncoding("GB18030")
        advanceUntilIdle()
        assertEquals(ImportPhase.Error, viewModel.state.value.phase)
        assertEquals("GB18030", viewModel.state.value.selectedEncoding)

        viewModel.retry()
        advanceUntilIdle()

        assertEquals(ImportPhase.Success, viewModel.state.value.phase)
        assertEquals("GB18030", importer.calls[2].encoding)
        assertEquals(3, source.openedUris.size)
    }

    @Test
    fun source_open_failure_is_retryable_without_calling_use_case() = importTest {
        val importer = FakeImporter()
        val source = FakeImportSource(
            metadata = ImportSource.Metadata(displayName = "长夜.txt", sizeBytes = 32L),
            openStream = { null },
        )
        val viewModel = newViewModel(importer = importer, source = source)

        viewModel.onFilePicked("content://novel")
        viewModel.startImport()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(ImportPhase.Error, state.phase)
        assertEquals(ImportError.SourceUnavailable, state.error)
        assertTrue(state.canRetry)
        assertTrue(importer.calls.isEmpty())

        // 源恢复后重试即可复用同一个选择，无需重新选择文件。
        source.openStream = { ByteArrayInputStream(ByteArray(8)) }
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(ImportPhase.Success, viewModel.state.value.phase)
        assertEquals(1, importer.calls.size)
        assertEquals(2, source.openedUris.size)
    }

    @Test
    fun cancel_cancels_in_flight_import_and_resets_state() = importTest {
        val importer = FakeImporter().apply {
            handler = { awaitCancellation() }
        }
        val source = FakeImportSource(
            metadata = ImportSource.Metadata(displayName = "长夜.txt", sizeBytes = 32L),
        )
        val viewModel = newViewModel(importer = importer, source = source)

        viewModel.onFilePicked("content://novel")
        viewModel.startImport()
        advanceUntilIdle()
        assertEquals(ImportPhase.Importing, viewModel.state.value.phase)

        viewModel.cancel()
        advanceUntilIdle()

        assertTrue("取消应传播到用例协程", importer.cancelled)
        assertEquals(ImportPhase.Idle, viewModel.state.value.phase)
        assertNull(viewModel.state.value.selection)
        assertNull(viewModel.state.value.error)
    }

    // ------------------------------------------------------------------
    // 测试夹具
    // ------------------------------------------------------------------

    /**
     * 在 `runTest` 的调度器上安装 Main dispatcher，并注入共享 scheduler 的作用域。
     *
     * 即使 ViewModel 因配置问题回退到 `viewModelScope`，`advanceUntilIdle()` 也能
     * 推进导入协程。
     */
    private fun importTest(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.newViewModel(
        importer: ImportRunner = FakeImporter(),
        source: ImportSource = FakeImportSource(),
        initialState: ImportUiState = ImportUiState(),
    ): ImportViewModel = ImportViewModel(
        importer = importer,
        source = source,
        initialState = initialState,
        externalScope = this,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    /** 内存版 SAF：可配置元数据与每次打开的流，并记录打开的 uri。 */
    private class FakeImportSource(
        var metadata: ImportSource.Metadata? = ImportSource.Metadata(
            displayName = "长夜将明.txt",
            sizeBytes = 128L,
        ),
        var openStream: (String) -> InputStream? = { ByteArrayInputStream(ByteArray(8)) },
    ) : ImportSource {

        val openedUris: MutableList<String> = mutableListOf()

        override fun describe(uri: String): ImportSource.Metadata? = metadata

        override fun open(uri: String): InputStream? {
            openedUris += uri
            return openStream(uri)
        }
    }

    /**
     * 记录调用参数的假用例。
     *
     * [handler] 可模拟进度回调、挂起（取消测试）或任何结果；未设置时按 [queued]
     * 顺序出队，队列为空则返回成功。
     */
    private class FakeImporter : ImportRunner {

        data class Call(
            val displayName: String,
            val sizeBytes: Long?,
            val encoding: String?,
            val input: InputStream,
            val onProgress: ((Long) -> Unit)?,
        )

        val calls: MutableList<Call> = mutableListOf()
        var cancelled: Boolean = false
        var handler: (suspend (Call) -> ImportResult)? = null

        private val queued: MutableList<ImportResult> = mutableListOf()

        fun enqueue(vararg results: ImportResult) {
            queued += results
        }

        override suspend fun import(
            displayName: String,
            input: InputStream,
            sizeBytes: Long?,
            encoding: String?,
            onProgress: ((Long) -> Unit)?,
        ): ImportResult {
            val call = Call(
                displayName = displayName,
                sizeBytes = sizeBytes,
                encoding = encoding,
                input = input,
                onProgress = onProgress,
            )
            calls += call
            return try {
                handler?.invoke(call) ?: nextQueuedResult()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }

        private fun nextQueuedResult(): ImportResult =
            if (queued.isEmpty()) {
                ImportResult.Success(novelId = 1L, chapterCount = 1, charCount = 1)
            } else {
                queued.removeAt(0)
            }
    }
}
