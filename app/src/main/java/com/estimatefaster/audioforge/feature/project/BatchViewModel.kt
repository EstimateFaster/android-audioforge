package com.estimatefaster.audioforge.feature.project

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estimatefaster.audioforge.domain.jobs.BatchParams
import com.estimatefaster.audioforge.domain.jobs.BatchRunner
import com.estimatefaster.audioforge.domain.jobs.StartResult
import com.estimatefaster.audioforge.domain.jobs.StopResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * `BatchRunner` 的最小可注入接口（P4-06）。
 *
 * 生产实现是 [BatchRunnerGatewayAdapter]（直接转发给 P3-04 的 `BatchRunner`）；
 * 纯 JVM 测试注入记录型 fake，不需要 Room/WorkManager/Robolectric。
 */
interface BatchRunnerGateway {

    suspend fun start(params: BatchParams): StartResult

    suspend fun stop(novelId: Long): StopResult
}

/** 把领域层 [BatchRunner] 适配为 [BatchRunnerGateway]。 */
class BatchRunnerGatewayAdapter(
    private val runner: BatchRunner,
) : BatchRunnerGateway {

    override suspend fun start(params: BatchParams): StartResult = runner.start(params)

    override suspend fun stop(novelId: Long): StopResult = runner.stop(novelId)
}

/**
 * 批次开始成功后的的一次性事件。
 *
 * 用 [Channel] 而不是状态重放：Sheet 关闭/旋转后不会重复触发导航。
 */
sealed interface BatchSheetEvent {

    /** [BatchRunner.start] 成功，UI 可以关闭 Sheet 并回到项目页看进度。 */
    data class Started(val jobId: Long) : BatchSheetEvent
}

/**
 * 批次 Sheet 的 ViewModel（P4-06 / 执行计划 §12.4 S05）。
 *
 * 职责：
 * 1. 维护模式、起止章、强制重跑、并发与 Key 可用性；
 * 2. 把合法表单转换为 [BatchParams]（范围展开为 chapterIds）并异步调用
 *    [BatchRunnerGateway.start]；结果映射为 Started/AlreadyRunning/Failure；
 * 3. 异步调用 [BatchRunnerGateway.stop]；结果映射为 Stopping/NotRunning/Failure；
 * 4. 开始成功时发出 [BatchSheetEvent.Started]，由 Sheet 回调 `onStarted`。
 *
 * 校验不允许时 [start] 直接返回，不会调用 Runner（无 Key、范围非法都不会留下 job 行）。
 *
 * @param novelId 当前项目 id
 * @param chapters 章节投影（id/1 基序号/完成状态）
 * @param runner 可注入的批次执行器
 * @param defaultConcurrency 设置里的默认并发（1..8）
 * @param hasLlmKey 调用方传入的 Key 状态
 * @param hasFishKey 调用方传入的 Key 状态
 * @param initialState 测试/Preview 用初始状态
 * @param externalScope 测试注入的协程作用域；为空时使用 [viewModelScope]
 * @param dispatcher 启动/停止协程使用的调度器；测试注入 TestDispatcher
 */
class BatchViewModel(
    private val novelId: Long,
    chapters: List<BatchChapterUi>,
    private val runner: BatchRunnerGateway,
    defaultConcurrency: Int = BatchParams.DEFAULT_CONCURRENCY,
    hasLlmKey: Boolean = true,
    hasFishKey: Boolean = true,
    initialState: BatchUiState = BatchUiState.initial(
        novelId = novelId,
        chapters = chapters,
        defaultConcurrency = defaultConcurrency,
        hasLlmKey = hasLlmKey,
        hasFishKey = hasFishKey,
    ),
    private val externalScope: CoroutineScope? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {

    /**
     * 生产入口：注入 P3-04 的 [BatchRunner]。
     *
     * Key 状态由调用方（项目页/设置页）传入；Sheet 显示期间变化时可通过
     * [updateKeys] 同步。
     */
    constructor(
        novelId: Long,
        chapters: List<BatchChapterUi>,
        batchRunner: BatchRunner,
        defaultConcurrency: Int = BatchParams.DEFAULT_CONCURRENCY,
        hasLlmKey: Boolean = true,
        hasFishKey: Boolean = true,
    ) : this(
        novelId = novelId,
        chapters = chapters,
        runner = BatchRunnerGatewayAdapter(batchRunner),
        defaultConcurrency = defaultConcurrency,
        hasLlmKey = hasLlmKey,
        hasFishKey = hasFishKey,
    )

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<BatchUiState> = _state.asStateFlow()

    private val _events = Channel<BatchSheetEvent>(capacity = Channel.BUFFERED)
    val events: Flow<BatchSheetEvent> = _events.receiveAsFlow()

    private val scope: CoroutineScope
        get() = externalScope ?: viewModelScope

    private var startJob: Job? = null
    private var stopJob: Job? = null

    // ------------------------------------------------------------------
    // 表单
    // ------------------------------------------------------------------

    fun setMode(mode: BatchMode) {
        _state.update { it.copy(mode = mode) }
    }

    fun setStartText(value: String) {
        _state.update { it.copy(startText = value) }
    }

    fun setEndText(value: String) {
        _state.update { it.copy(endText = value) }
    }

    fun setForce(enabled: Boolean) {
        _state.update { it.copy(force = enabled) }
    }

    fun setConcurrency(value: Int) {
        _state.update { it.copy(concurrency = value) }
    }

    /** Sheet 每次重组时同步调用方传入的 Key 状态。 */
    fun updateKeys(hasLlmKey: Boolean, hasFishKey: Boolean) {
        _state.update { state ->
            if (state.hasLlmKey == hasLlmKey && state.hasFishKey == hasFishKey) {
                state
            } else {
                state.copy(hasLlmKey = hasLlmKey, hasFishKey = hasFishKey)
            }
        }
    }

    /** “全选未完成”：回到初始的未完成首尾范围。 */
    fun selectUnfinished() {
        val chapters = _state.value.chapters
        val unfinished = chapters.filterNot { it.isComplete }
        val start = (unfinished.firstOrNull() ?: chapters.firstOrNull())?.number ?: 1
        val end = (unfinished.lastOrNull() ?: chapters.lastOrNull())?.number ?: 1
        _state.update { it.copy(startText = start.toString(), endText = end.toString()) }
    }

    /** “全选”：1..章数。 */
    fun selectAll() {
        val count = _state.value.chapterCount
        _state.update {
            it.copy(
                startText = BatchUiState.DEFAULT_START,
                endText = count.coerceAtLeast(1).toString(),
            )
        }
    }

    // ------------------------------------------------------------------
    // 开始 / 停止
    // ------------------------------------------------------------------

    /**
     * “开始生成”。
     *
     * 表单非法（范围/并发/无 Key）时直接返回；合法时先在状态里标记 `starting`，
     * 再调用 Runner。Runner 抛出的异常（非取消）映射成 [BatchStartStatus.Failure]，
     * 不把异常抛回 Compose。
     */
    fun start() {
        val snapshot = _state.value
        if (!snapshot.canStart || startJob?.isActive == true) return
        val params = snapshot.toBatchParams() ?: return

        _state.update { state ->
            state.copy(starting = true, startStatus = BatchStartStatus.Starting, stopStatus = null)
        }
        startJob = scope.launch(dispatcher) {
            val result = try {
                runner.start(params)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                StartResult.Failure(e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName)
            }
            if (!isActive) return@launch

            _state.update { state ->
                state.copy(starting = false, startStatus = result.toUiStatus())
            }
            if (result is StartResult.Started) {
                _events.trySend(BatchSheetEvent.Started(result.jobId))
            }
        }
    }

    /**
     * “停止批次”。
     *
     * 始终把请求交给 Runner；没有活跃批次时 Runner 返回 NotRunning，UI 显示即可。
     * 停止是异步的：Stopping 只表示请求已被接受，Worker 收尾后 job 才变 cancelled。
     */
    fun stop() {
        if (!_state.value.canStop || stopJob?.isActive == true) return

        _state.update { state ->
            state.copy(stopping = true, stopStatus = BatchStopStatus.Requesting)
        }
        stopJob = scope.launch(dispatcher) {
            val result = try {
                runner.stop(novelId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                StopResult.Failure(e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName)
            }
            if (!isActive) return@launch

            _state.update { state ->
                state.copy(stopping = false, stopStatus = result.toUiStatus())
            }
        }
    }

    // ------------------------------------------------------------------
    // 领域结果 → UI 状态
    // ------------------------------------------------------------------

    private fun StartResult.toUiStatus(): BatchStartStatus = when (this) {
        is StartResult.Started -> BatchStartStatus.Started(this.jobId)
        is StartResult.AlreadyRunning -> BatchStartStatus.AlreadyRunning(this.jobId)
        is StartResult.Failure -> BatchStartStatus.Failure(this.reason)
    }

    private fun StopResult.toUiStatus(): BatchStopStatus = when (this) {
        is StopResult.Stopping -> BatchStopStatus.Stopping(this.jobId)
        is StopResult.NotRunning -> BatchStopStatus.NotRunning(this.novelId)
        is StopResult.Failure -> BatchStopStatus.Failure(this.reason)
    }
}
