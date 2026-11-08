package com.estimatefaster.audioforge.feature.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estimatefaster.audioforge.core.storage.AudioStorage
import com.estimatefaster.audioforge.core.storage.ExportManager
import com.estimatefaster.audioforge.core.storage.ExportResult
import com.estimatefaster.audioforge.data.repository.ChapterRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导出数据端口（P5-11）。
 *
 * 两个动作刻意分开：
 * - [loadChapters] 读取章节投影与音频文件的粗略时长/体积；
 * - [exportChapter] 逐章导出，签名与 `ExportManager.exportChapter` 一致。
 *
 * 纯 JVM 测试注入 fake 即可；生产实现见 [ExportManagerGateway]。
 */
interface ExportGateway {

    /** 读取整本书的章节（含完成状态与粗略时长/体积），按章节顺序返回。 */
    suspend fun loadChapters(novelId: Long): List<ExportChapterUi>

    /**
     * 导出某一章成品音频。
     *
     * 实现不应该向调用方抛业务异常：失败用 [ExportOutcome.Failure] 表达，便于逐章记录
     * 与重试。协程取消异常仍应正常向外传播（协作取消）。
     */
    suspend fun exportChapter(novelId: Long, chapterIdx: Int, title: String): ExportOutcome
}

/** 单章导出结果；[Success.uri] 是 `Uri.toString()`，[Success.displayName] 是文件名。 */
sealed interface ExportOutcome {

    data class Success(
        val uri: String,
        val displayName: String,
    ) : ExportOutcome

    data class Failure(val message: String) : ExportOutcome
}

/**
 * 生产适配器：把 `ExportManager`、`ChapterRepository` 与 `AudioStorage` 接到
 * [ExportGateway]。
 *
 * 导出本身是 IO（复制文件、写 MediaStore/FileProvider），统一切到 [ioDispatcher]；
 * ViewModel 侧顺序调用，因此不会出现并发写 MediaStore 的冲突。
 *
 * 章节时长用体积粗略估算（64 kbps 有损音频），只用于选择页展示；真实时长以播放器/导出
 * 文件为准。文件缺失或路径非法时体积按 0 处理，不影响导出阶段的失败记录。
 *
 * @param exportManager P2-15 的导出/分享实现
 * @param chapterRepository 章节投影来源
 * @param audioStorage 章节成品文件定位
 * @param ioDispatcher 文件与 MediaStore 操作使用的调度器
 */
class ExportManagerGateway(
    private val exportManager: ExportManager,
    private val chapterRepository: ChapterRepository,
    private val audioStorage: AudioStorage,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ExportGateway {

    override suspend fun loadChapters(novelId: Long): List<ExportChapterUi> =
        withContext(ioDispatcher) {
            chapterRepository.observeBriefs(novelId).first().map { brief ->
                val sizeBytes = chapterSizeBytes(novelId, brief.idx)
                ExportChapterUi(
                    chapterIdx = brief.idx,
                    title = brief.title,
                    audioDone = brief.audioStatus.isDoneStatus(),
                    durationMs = estimateDurationMs(sizeBytes),
                    sizeBytes = sizeBytes,
                )
            }
        }

    override suspend fun exportChapter(
        novelId: Long,
        chapterIdx: Int,
        title: String,
    ): ExportOutcome = withContext(ioDispatcher) {
        when (val result = exportManager.exportChapter(novelId, chapterIdx, title)) {
            is ExportResult.Success -> ExportOutcome.Success(
                uri = result.uri.toString(),
                displayName = result.displayName,
            )

            is ExportResult.Failure -> ExportOutcome.Failure(result.message)
        }
    }

    private fun chapterSizeBytes(novelId: Long, chapterIdx: Int): Long = runCatching {
        val relative = audioStorage.chapterAudioRelative(novelId, chapterIdx)
        val file = audioStorage.resolve(relative)
        if (file.isFile) file.length().coerceAtLeast(0L) else 0L
    }.getOrDefault(0L)

    private fun String.isDoneStatus(): Boolean = trim().equals("done", ignoreCase = true)
}

/** 导出编码的粗略码率：64 kbps 有损音频，每秒约 8 KB。 */
const val ESTIMATED_AUDIO_BYTES_PER_SECOND: Long = 8_000L

/**
 * 按体积估算时长（毫秒）。
 *
 * 只用于导出选择页展示；[sizeBytes] 为 0 或负数时返回 0（UI 显示“未知”）。
 */
fun estimateDurationMs(sizeBytes: Long): Long {
    if (sizeBytes <= 0L) return 0L
    return sizeBytes * ESTIMATE_MILLIS_PER_SECOND / ESTIMATED_AUDIO_BYTES_PER_SECOND
}

/**
 * 导出/分享 Sheet 的 ViewModel（P5-11 / 执行计划 §15 P5-11）。
 *
 * 职责：
 * 1. 通过 [gateway] 读取章节，维护范围/起止输入与推导出的选中章节；
 * 2. 顺序导出（并发 1）：逐章调用 [ExportGateway.exportChapter]，发布 x/y 进度、当前
 *    章名、成功文件与失败记录；
 * 3. 完成后允许分享成功文件的 Uri 列表；失败章节可单独重试，已成功的不重复导出；
 * 4. 取消采用协作取消：取消导出协程，已完成的文件保留在结果里，未开始的章节不写入。
 *
 * 线程约束：状态更新与 `viewModelScope` 一致（主线程）；网关实现自行切 IO。
 *
 * @param novelId 当前项目 id
 * @param gateway 导出数据端口（生产用 [ExportManagerGateway]）
 * @param currentChapterIdx 宿主传入的当前章 0 基序号，用于“当前章”范围
 * @param initialState 测试/Preview 用初始状态
 * @param dispatcher 启动协程使用的调度器；测试注入 TestDispatcher
 */
class ExportViewModel(
    private val novelId: Long,
    private val gateway: ExportGateway,
    currentChapterIdx: Int? = null,
    initialState: ExportUiState = ExportUiState(currentChapterIdx = currentChapterIdx),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var exportJob: Job? = null
    private var cancelRequested: Boolean = false

    init {
        load()
    }

    /**
     * 首次加载/失败重试。
     *
     * 重新加载会丢弃上一轮的进度与结果，但保留用户已选的范围与起止输入；正在导出时先
     * 取消导出，避免旧任务把结果写回新状态。
     */
    fun load() {
        loadJob?.cancel()
        if (_state.value.isExporting) {
            cancelRequested = true
            exportJob?.cancel()
            exportJob = null
        }
        _state.update { it.copy(loading = true, loadError = null) }
        loadJob = viewModelScope.launch(dispatcher) {
            try {
                val chapters = gateway.loadChapters(novelId)
                _state.update { current ->
                    current.copy(
                        loading = false,
                        loadError = null,
                        chapters = chapters,
                        phase = ExportPhase.Selection,
                        total = 0,
                        completed = 0,
                        currentChapterTitle = null,
                        succeeded = emptyList(),
                        failures = emptyList(),
                        cancelled = false,
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                _state.update {
                    it.copy(loading = false, loadError = error.readableMessage())
                }
            }
        }
    }

    /** 切换范围；导出中忽略。切到起止章时若输入为空，预填 1..章数。 */
    fun setRange(range: ExportRange) {
        if (_state.value.isExporting) return
        _state.update { current ->
            if (current.range == range) {
                current
            } else {
                current.copy(
                    range = range,
                    startText = if (
                        range == ExportRange.ChapterRange && current.startText.isBlank()
                    ) {
                        "1"
                    } else {
                        current.startText
                    },
                    endText = if (
                        range == ExportRange.ChapterRange && current.endText.isBlank()
                    ) {
                        current.chapters.size.coerceAtLeast(1).toString()
                    } else {
                        current.endText
                    },
                )
            }
        }
    }

    /** 起止章输入：只保留数字，最多 5 位；导出中忽略。 */
    fun setStartText(text: String) {
        if (_state.value.isExporting) return
        _state.update { it.copy(startText = text.filter { char -> char.isDigit() }.take(5)) }
    }

    /** 起止章输入：只保留数字，最多 5 位；导出中忽略。 */
    fun setEndText(text: String) {
        if (_state.value.isExporting) return
        _state.update { it.copy(endText = text.filter { char -> char.isDigit() }.take(5)) }
    }

    /**
     * 开始导出当前范围。
     *
     * 范围非法、无选中章节或已有导出任务时直接返回（重复点击去重）。成功后清空上一轮
     * 结果，`total` 为本次选中的章节数。
     */
    fun start() {
        val current = _state.value
        if (current.isExporting || exportJob?.isActive == true) return
        if (!current.canStart) return
        val targets = current.selectedChapters
        if (targets.isEmpty()) return
        launchExport(targets = targets, keepSucceeded = false)
    }

    /**
     * 只重试失败章节。
     *
     * 已成功文件保留在结果里；本次 `total` 只统计失败章节，失败记录在重试开始时清空。
     * 没有失败记录或仍有导出任务时是安全 no-op。
     */
    fun retryFailed() {
        val current = _state.value
        if (current.isExporting || exportJob?.isActive == true) return
        if (current.failures.isEmpty()) return
        val targets = current.failures.map { failure ->
            current.chapters.firstOrNull { it.chapterIdx == failure.chapterIdx }
                ?: ExportChapterUi(
                    chapterIdx = failure.chapterIdx,
                    title = failure.title,
                    audioDone = true,
                )
        }
        launchExport(targets = targets, keepSucceeded = true)
    }

    /**
     * 协作取消当前导出。
     *
     * 立即切到结果态并标记 [ExportUiState.cancelled]；导出协程被取消后不会再调用网关的
     * 下一章。已完成的文件保留（ExportManager 逐章原子发布，取消不会删除已发布文件），
     * 未开始的章节不会写入。非导出阶段调用是 no-op。
     */
    fun cancel() {
        if (!_state.value.isExporting) return
        cancelRequested = true
        exportJob?.cancel()
        exportJob = null
        _state.update {
            it.copy(
                phase = ExportPhase.Result,
                currentChapterTitle = null,
                cancelled = true,
            )
        }
    }

    override fun onCleared() {
        loadJob?.cancel()
        exportJob?.cancel()
        super.onCleared()
    }

    private fun launchExport(targets: List<ExportChapterUi>, keepSucceeded: Boolean) {
        if (exportJob?.isActive == true) return
        cancelRequested = false
        _state.update {
            it.copy(
                phase = ExportPhase.Exporting,
                total = targets.size,
                completed = 0,
                currentChapterTitle = targets.firstOrNull()?.title,
                succeeded = if (keepSucceeded) it.succeeded else emptyList(),
                failures = emptyList(),
                cancelled = false,
            )
        }
        exportJob = viewModelScope.launch(dispatcher) {
            runExport(targets)
        }
    }

    /**
     * 顺序导出：每一章结束后才调用下一章，避免 MediaStore/同一目录的并发写冲突。
     *
     * 每完成一章发布一次状态（x/y、当前章名、成功/失败），Web 端与页面可以实时看到进度。
     */
    private suspend fun runExport(targets: List<ExportChapterUi>) {
        val succeeded = _state.value.succeeded.toMutableList()
        val failures = mutableListOf<ExportFailure>()
        var finished = 0

        for (chapter in targets) {
            if (!currentCoroutineContext().isActive) break
            _state.update {
                it.copy(currentChapterTitle = chapter.title, completed = finished)
            }

            val outcome = try {
                gateway.exportChapter(novelId, chapter.chapterIdx, chapter.title)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                ExportOutcome.Failure(error.readableMessage())
            }

            when (outcome) {
                is ExportOutcome.Success -> {
                    // 防御性去重：同一章重试成功时替换旧 Uri，而不是累积两份。
                    succeeded.removeAll { it.chapterIdx == chapter.chapterIdx }
                    succeeded += ExportedFile(
                        chapterIdx = chapter.chapterIdx,
                        title = chapter.title,
                        displayName = outcome.displayName,
                        uri = outcome.uri,
                    )
                    // 结果按章节顺序展示/分享，重试成功的章不会跑到列表末尾。
                    succeeded.sortBy { it.chapterIdx }
                }

                is ExportOutcome.Failure -> failures += ExportFailure(
                    chapterIdx = chapter.chapterIdx,
                    title = chapter.title,
                    message = outcome.message,
                )
            }

            finished++
            _state.update {
                it.copy(
                    completed = finished,
                    succeeded = succeeded.toList(),
                    failures = failures.toList(),
                    currentChapterTitle = targets.getOrNull(finished)?.title,
                )
            }
        }

        _state.update {
            it.copy(
                phase = ExportPhase.Result,
                currentChapterTitle = null,
                completed = finished,
                cancelled = cancelRequested,
            )
        }
    }

    private fun Throwable.readableMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}

private const val ESTIMATE_MILLIS_PER_SECOND = 1_000L
