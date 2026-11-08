package com.estimatefaster.audioforge.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estimatefaster.audioforge.data.db.JobEntity
import com.estimatefaster.audioforge.data.db.ProjectSummary
import com.estimatefaster.audioforge.data.repository.JobRepository
import com.estimatefaster.audioforge.data.repository.ProjectRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 任务页需要的项目摘要来源；接口化便于 JVM 测试注入 fake Flow。 */
fun interface TaskProjectSource {
    fun observeProjects(): Flow<List<ProjectSummary>>
}

/**
 * 任务页需要的任务来源。
 *
 * `JobRepository` 目前只有“按项目”的 `observeActive(novelId)` / `lastFinished(novelId)`，
 * 没有跨书的全量查询；任务页因此先观察项目列表，再为每个项目组合这两条查询。
 */
interface TaskJobSource {
    /** 该项目的 queued/running 任务；没有时发射 null。 */
    fun observeActive(novelId: Long): Flow<JobEntity?>

    /** 该项目最近一次已结束任务（done/error/cancelled）。 */
    suspend fun lastFinished(novelId: Long): JobEntity?

    /** 请求停止；任务已结束返回 false，不算错误。 */
    suspend fun requestStop(jobId: Long): Boolean
}

/**
 * 任务页 ViewModel（P4-08 / §12.4 S12）。
 *
 * 数据组合：
 * 1. `ProjectRepository.observeProjects()` 给出所有项目（id/书名）；
 * 2. 对每个项目 `JobRepository.observeActive(novelId)` 观察活跃任务；
 * 3. 每次活跃任务发射时再读一次 `JobRepository.lastFinished(novelId)`，
 *    这样 running→done/error/cancelled 的状态跃迁会同时刷新“当前任务”与“最近任务”。
 *
 * 组合结果交给纯函数 [buildTasksUiState] 折叠排序；ViewModel 只负责订阅、重试与停止。
 * 停止只写 `jobs.stopRequested`，由 Worker 在安全点收尾（§11.2），UI 不做本地状态机。
 *
 * @param clock 注入时钟，测试/预览可固定“现在”以验证 ETA 与耗时
 */
class TasksViewModel(
    private val projects: TaskProjectSource,
    private val jobs: TaskJobSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val historyLimit: Int = DEFAULT_TASK_HISTORY_LIMIT,
) : ViewModel() {

    /** 生产入口：直接注入 `AppContainer` 里的项目/任务仓库。 */
    constructor(
        projectRepository: ProjectRepository,
        jobRepository: JobRepository,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        projects = TaskProjectSource { projectRepository.observeProjects() },
        jobs = JobRepositoryTaskSource(jobRepository),
        clock = clock,
    )

    private val retrySignal = MutableStateFlow(0)
    private val _state = MutableStateFlow(TasksUiState())
    val state: StateFlow<TasksUiState> = _state.asStateFlow()

    init {
        observeTasks()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeTasks() {
        viewModelScope.launch {
            retrySignal
                .flatMapLatest {
                    projects.observeProjects()
                        .flatMapLatest { projectList ->
                            if (projectList.isEmpty()) {
                                flowOf(emptyList<TaskJobSnapshot>())
                            } else {
                                combine(
                                    projectList.map { project -> snapshotFlow(project) },
                                ) { snapshots -> snapshots.toList() }
                            }
                        }
                        .catch { throwable ->
                            _state.value = TasksUiState(
                                loading = false,
                                loadError = throwable.toReadableMessage(),
                            )
                        }
                }
                .collect { snapshots ->
                    val actionError = _state.value.actionError
                    _state.value = buildTasksUiState(
                        snapshots = snapshots,
                        now = clock(),
                        historyLimit = historyLimit,
                    ).copy(actionError = actionError)
                }
        }
    }

    /**
     * 单项目的快照流。
     *
     * `distinctUntilChanged` 很重要：Room 的 jobs 表任何写入都会让**所有**项目的
     * `observeActive` 重新查询；去重后，别的书更新进度时本项目的 null/running 不会
     * 重复触发一次 `lastFinished` 查询。活跃任务结束后 null 与上一值不同，仍会重新
     * 读取 `lastFinished`，因此“最近任务”不会漏更新。
     */
    private fun snapshotFlow(project: ProjectSummary): Flow<TaskJobSnapshot> =
        jobs.observeActive(project.id)
            .distinctUntilChanged()
            .map { active ->
                TaskJobSnapshot(
                    novelId = project.id,
                    novelTitle = project.title,
                    active = active,
                    lastFinished = jobs.lastFinished(project.id),
                )
            }

    /**
     * 请求停止指定项目的活跃任务（先由 UI 二次确认）。
     *
     * 找不到活跃任务、任务已结束或已请求过停止时静默返回；只有仓库抛异常时才写入
     * [TasksUiState.actionError]，避免把“晚到一步”的正常竞态当成错误弹给用户。
     */
    fun stop(novelId: Long) {
        val card = _state.value.taskFor(novelId) ?: return
        if (!card.status.isActive || card.stopRequested) return
        viewModelScope.launch {
            try {
                jobs.requestStop(card.jobId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                _state.update { it.copy(actionError = throwable.toReadableMessage()) }
            }
        }
    }

    /** 清掉停止失败提示（UI 的“知道了”）。 */
    fun dismissActionError() {
        _state.update { it.copy(actionError = null) }
    }

    /** 重新订阅项目/任务 Flow；用于错误态重试。 */
    fun retry() {
        _state.update { it.copy(loading = true, loadError = null, actionError = null) }
        retrySignal.update { it + 1 }
    }

    private fun Throwable.toReadableMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}

/** 把 [JobRepository] 适配成可注入的 [TaskJobSource]。 */
private class JobRepositoryTaskSource(private val repository: JobRepository) : TaskJobSource {
    override fun observeActive(novelId: Long): Flow<JobEntity?> =
        repository.observeActive(novelId)

    override suspend fun lastFinished(novelId: Long): JobEntity? =
        repository.lastFinished(novelId)

    override suspend fun requestStop(jobId: Long): Boolean =
        repository.requestStop(jobId)
}
