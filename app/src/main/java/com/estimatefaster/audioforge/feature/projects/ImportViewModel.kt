package com.estimatefaster.audioforge.feature.projects

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estimatefaster.audioforge.core.ingest.ChapterSplitter
import com.estimatefaster.audioforge.core.ingest.TextDecoder
import com.estimatefaster.audioforge.core.storage.AudioStorage
import com.estimatefaster.audioforge.data.repository.ProjectRepository
import com.estimatefaster.audioforge.domain.usecase.ImportNovelUseCase
import com.estimatefaster.audioforge.domain.usecase.ImportResult
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 打开 SAF 源文件的最小抽象（P4-04）。
 *
 * 生产实现是 [ContentResolverImportSource]；测试注入内存 fake，不需要 Android 或
 * 真实文件。注意 [open] 每次都必须返回**全新的流**：编码重试会用同一个 uri 重新打开，
 * 用例会消费并关闭上一次的流。
 */
interface ImportSource {

    /** `OpenableColumns` 中导入 Sheet 需要的字段；大小可能为 null（未知）。 */
    data class Metadata(
        val displayName: String,
        val sizeBytes: Long?,
    )

    /** 读取显示名/大小；无法读取时返回 null。 */
    fun describe(uri: String): Metadata?

    /** 打开新的输入流；失败返回 null。调用方（导入用例）负责关闭。 */
    fun open(uri: String): InputStream?
}

/**
 * 基于 `ContentResolver` 的 SAF 实现。
 *
 * 只使用 [OpenableColumns]：不请求持久化权限，也不复制文件——复制与清理由
 * [ImportNovelUseCase] 负责。
 */
class ContentResolverImportSource(
    private val resolver: ContentResolver,
) : ImportSource {

    override fun describe(uri: String): ImportSource.Metadata? {
        val parsed = Uri.parse(uri)
        var displayName: String? = null
        var sizeBytes: Long? = null
        runCatching {
            resolver.query(
                parsed,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    displayName = cursor.getString(nameIndex)
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex)
                }
            }
        }
        // 部分 provider 不返回 DISPLAY_NAME；退回 Uri 最后一段，仍要求非空。
        val resolvedName = displayName?.takeIf { it.isNotBlank() }
            ?: parsed.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: return null
        return ImportSource.Metadata(displayName = resolvedName, sizeBytes = sizeBytes)
    }

    override fun open(uri: String): InputStream? =
        runCatching { resolver.openInputStream(Uri.parse(uri)) }.getOrNull()
}

/**
 * [ImportNovelUseCase] 的最小抽象。
 *
 * ViewModel 只依赖这个接口：纯 JVM 测试注入 fake，生产环境用
 * [ImportNovelUseCaseRunner] 适配。参数语义与用例完全一致（流由被调用方关闭）。
 */
interface ImportRunner {

    suspend fun import(
        displayName: String,
        input: InputStream,
        sizeBytes: Long? = null,
        encoding: String? = null,
        onProgress: ((bytesRead: Long) -> Unit)? = null,
    ): ImportResult
}

/** 把 [ImportNovelUseCase] 适配成 [ImportRunner]。 */
class ImportNovelUseCaseRunner(
    private val useCase: ImportNovelUseCase,
) : ImportRunner {

    override suspend fun import(
        displayName: String,
        input: InputStream,
        sizeBytes: Long?,
        encoding: String?,
        onProgress: ((Long) -> Unit)?,
    ): ImportResult = useCase.import(
        displayName = displayName,
        input = input,
        sizeBytes = sizeBytes,
        encoding = encoding,
        onProgress = onProgress,
    )
}

/**
 * 一次性 UI 事件：成功/打开已有项目后需要导航或关闭 Sheet，不能只靠状态重放。
 *
 * 用 [Channel] 而不是 `SharedFlow(replay=1)`：事件只消费一次，旋转屏幕不会重复导航。
 */
sealed interface ImportEvent {

    data class Imported(val novelId: Long) : ImportEvent

    data class OpenExisting(val novelId: Long) : ImportEvent
}

/**
 * 导入 Sheet 的 ViewModel（P4-04 / 执行计划 §12.4 S03）。
 *
 * 职责：
 * 1. 接收 SAF 选择的 uri，经 [ImportSource] 读取名称/大小并做**确认前校验**
 *    （非 txt/空/超大直接报错，完全不调用用例）；
 * 2. 调用 [ImportRunner]（生产环境即 [ImportNovelUseCase]）并转发累计字节数进度；
 * 3. 处理四类结果：成功 → [ImportEvent.Imported]；重名 → `Duplicate` 状态；
 *    编码失败 → `NeedsEncoding` + 候选；失败 → `Error`，可重试；
 * 4. 编码重试/失败重试都**重新打开源流**，因为用例在非 Success 结果下已删除
 *    本次复制并关闭了旧流；
 * 5. 取消/关闭时取消协程；用例的 `finally` 会删除半成品导入文件。
 *
 * @param importer 用例适配层（测试注入 fake）
 * @param source 源文件抽象（测试注入 fake）
 * @param initialState 测试/Preview 用初始状态
 * @param externalScope 测试注入的协程作用域；为空时使用 [viewModelScope]
 * @param dispatcher 启动导入用的调度器；测试注入 `StandardTestDispatcher(testScheduler)`
 */
class ImportViewModel(
    private val importer: ImportRunner,
    private val source: ImportSource,
    initialState: ImportUiState = ImportUiState(),
    private val externalScope: CoroutineScope? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {

    /**
     * 生产入口：用仓库 + 存储组装 [ImportNovelUseCase]，用 `ContentResolver` 打开 SAF 流。
     *
     * 集成提示：`AppContainer` 目前不暴露导入用例；接入 `AppNavHost`/`AppViewModelFactory`
     * 时，在工厂里用 `ImportViewModel(container.projectRepository,
     * container.audioStorage, context.contentResolver)` 创建即可。
     */
    constructor(
        projectRepository: ProjectRepository,
        audioStorage: AudioStorage,
        contentResolver: ContentResolver,
        dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    ) : this(
        importer = ImportNovelUseCaseRunner(
            ImportNovelUseCase(
                projects = projectRepository,
                audioStorage = audioStorage,
                textDecoder = TextDecoder(),
                chapterSplitter = ChapterSplitter(),
            ),
        ),
        source = ContentResolverImportSource(contentResolver),
        dispatcher = dispatcher,
    )

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    private val _events = Channel<ImportEvent>(capacity = Channel.BUFFERED)
    val events: Flow<ImportEvent> = _events.receiveAsFlow()

    private val scope: CoroutineScope
        get() = externalScope ?: viewModelScope

    private var importJob: Job? = null

    // ------------------------------------------------------------------
    // 文件选择
    // ------------------------------------------------------------------

    /**
     * SAF 选择回调。
     *
     * 先读元数据并校验；非法时进入 `Error`（保留文件信息便于展示），不会调用用例。
     * 若上一次导入还在进行，先取消它（用例会清理已复制的临时文件）。
     */
    fun onFilePicked(uri: String) {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return

        importJob?.cancel()
        importJob = null

        val metadata = try {
            source.describe(trimmed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (metadata == null) {
            _state.value = ImportUiState(
                phase = ImportPhase.Error,
                error = ImportError.SourceUnavailable,
            )
            return
        }

        val selection = ImportFileSelection(
            uri = trimmed,
            displayName = metadata.displayName,
            sizeBytes = metadata.sizeBytes,
        )
        val validation = validateImportSelection(selection.displayName, selection.sizeBytes)
        _state.value = if (validation == null) {
            ImportUiState(phase = ImportPhase.Selected, selection = selection)
        } else {
            ImportUiState(
                phase = ImportPhase.Error,
                selection = selection,
                error = ImportError.Validation(validation),
            )
        }
    }

    // ------------------------------------------------------------------
    // 导入 / 重试 / 编码
    // ------------------------------------------------------------------

    /** 「导入」按钮：只在 `Selected` 有效。 */
    fun startImport() {
        if (_state.value.phase != ImportPhase.Selected) return
        launchImport(encoding = null)
    }

    /**
     * 失败重试：沿用上次的编码（如果有）重新打开源流。
     *
     * 确认前校验失败（非 txt/空/超大）不可重试，必须重新选择文件。
     */
    fun retry() {
        val current = _state.value
        if (current.phase != ImportPhase.Error || !current.canRetry) return
        launchImport(encoding = current.selectedEncoding)
    }

    /**
     * 用户从候选里选编码：重新打开源文件并以该编码调用用例。
     *
     * 选错编码时用例会返回 [ImportResult.Failure]，进入可重试的 `Error`；
     * 用户仍可取消后重新选择文件。
     */
    fun chooseEncoding(encoding: String) {
        val current = _state.value
        if (current.phase != ImportPhase.NeedsEncoding) return
        val normalized = encoding.trim()
        if (normalized.isEmpty()) return
        launchImport(encoding = normalized)
    }

    // ------------------------------------------------------------------
    // 重名 / 取消
    // ------------------------------------------------------------------

    /** 「打开已有项目」：发出一次性事件，由 Sheet 回调 `onOpenExisting(id)`。 */
    fun openExistingProject() {
        val duplicate = _state.value.duplicate ?: return
        importJob?.cancel()
        importJob = null
        _events.trySend(ImportEvent.OpenExisting(duplicate.existingNovelId))
        _state.value = ImportUiState()
    }

    /** 重名对话框的「取消」：丢弃本次选择，不创建也不覆盖任何项目。 */
    fun dismissDuplicate() {
        cancel()
    }

    /**
     * 取消导入 / 关闭 Sheet。
     *
     * 取消在途协程后，[ImportNovelUseCase] 的 `finally` 会删除本次复制到
     * `imports/` 的文件（`committed=false`），不会留下半成品项目。
     */
    fun cancel() {
        importJob?.cancel()
        importJob = null
        _state.value = ImportUiState()
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun launchImport(encoding: String?) {
        val current = _state.value
        val selection = current.selection ?: return
        if (current.phase == ImportPhase.Importing) return
        val validation = validateImportSelection(selection.displayName, selection.sizeBytes)
        if (validation != null) {
            _state.value = current.copy(
                phase = ImportPhase.Error,
                progress = 0f,
                error = ImportError.Validation(validation),
            )
            return
        }

        importJob?.cancel()
        _state.value = current.copy(
            phase = ImportPhase.Importing,
            progress = 0f,
            error = null,
            duplicate = null,
            successNovelId = null,
            // 自动识别重试时不保留旧候选；手动编码重试时保留，失败后仍可换一个。
            encodingCandidates = if (encoding == null) emptyList() else current.encodingCandidates,
            selectedEncoding = encoding,
        )

        importJob = scope.launch(dispatcher) {
            val stream = try {
                source.open(selection.uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (!isActive) return@launch
            if (stream == null) {
                _state.update { state ->
                    state.copy(
                        phase = ImportPhase.Error,
                        progress = 0f,
                        error = ImportError.SourceUnavailable,
                    )
                }
                return@launch
            }

            val result = try {
                importer.import(
                    displayName = selection.displayName,
                    input = stream,
                    sizeBytes = selection.sizeBytes,
                    encoding = encoding,
                    onProgress = { bytesRead ->
                        if (isActive) reportProgress(selection.sizeBytes, bytesRead)
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImportResult.Failure(
                    message = e.message ?: e::class.java.simpleName,
                    cause = e,
                )
            }
            if (!isActive) return@launch
            applyResult(result, encoding)
        }
    }

    /** 累计字节数 → 0..1；总大小未知时保持不确定进度（不伪造百分比）。 */
    private fun reportProgress(totalBytes: Long?, bytesRead: Long) {
        if (totalBytes == null || totalBytes <= 0L) return
        val value = (bytesRead.toDouble() / totalBytes.toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
        _state.update { state ->
            if (state.phase == ImportPhase.Importing) {
                state.copy(progress = value)
            } else {
                state
            }
        }
    }

    private fun applyResult(result: ImportResult, encoding: String?) {
        when (result) {
            is ImportResult.Success -> {
                _state.update { state ->
                    state.copy(
                        phase = ImportPhase.Success,
                        progress = 1f,
                        successNovelId = result.novelId,
                        error = null,
                        duplicate = null,
                    )
                }
                _events.trySend(ImportEvent.Imported(result.novelId))
            }

            is ImportResult.Duplicate -> {
                _state.update { state ->
                    state.copy(
                        phase = ImportPhase.Duplicate,
                        progress = 0f,
                        duplicate = ImportDuplicate(
                            existingNovelId = result.existingNovelId,
                            title = result.title,
                        ),
                        error = null,
                    )
                }
            }

            is ImportResult.NeedsEncoding -> {
                _state.update { state ->
                    state.copy(
                        phase = ImportPhase.NeedsEncoding,
                        progress = 0f,
                        encodingCandidates = result.candidates.ifEmpty {
                            TextDecoder.MANUAL_CANDIDATES
                        },
                        selectedEncoding = null,
                        error = null,
                    )
                }
            }

            is ImportResult.Failure -> {
                _state.update { state ->
                    state.copy(
                        phase = ImportPhase.Error,
                        progress = 0f,
                        error = ImportError.ImportFailed(result.message),
                        // 保留本次手动编码：重试仍用它，用户也可重新选文件。
                        selectedEncoding = encoding,
                    )
                }
            }
        }
    }
}
