package com.estimatefaster.audioforge.feature.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estimatefaster.audioforge.data.db.ProjectSummary
import com.estimatefaster.audioforge.data.repository.ProjectRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 项目库的数据入口。
 *
 * 抽成接口是为了让纯 JVM 测试注入 fake Flow，不触碰 Room/Android；
 * 生产环境用 [ProjectRepositoryDataSource] 适配 [ProjectRepository]。
 */
interface ProjectLibraryDataSource {

    fun observeProjects(): Flow<List<ProjectSummary>>

    suspend fun rename(id: Long, title: String): Boolean

    suspend fun delete(id: Long): Boolean
}

/** 把仓库适配成 [ProjectLibraryDataSource]；页面/DI 只需要仓库本身。 */
class ProjectRepositoryDataSource(
    private val repository: ProjectRepository,
) : ProjectLibraryDataSource {

    override fun observeProjects(): Flow<List<ProjectSummary>> = repository.observeProjects()

    override suspend fun rename(id: Long, title: String): Boolean = repository.rename(id, title)

    override suspend fun delete(id: Long): Boolean = repository.delete(id)
}

/**
 * 项目库 ViewModel（P4-03 / 执行计划 §12.4 S02）。
 *
 * - 只订阅 `ProjectRepository.observeProjects()`：一次 `GROUP BY` 拿全聚合字段，
 *   不查正文；重命名/删除后 Room 失效查询，Flow 自动重新发射，无需手动刷新列表。
 * - 搜索在内存中按标题过滤（见 [filterProjectCards]），保留 DB 的
 *   「最近打开优先、其次创建时间」顺序。
 * - 删除失败（例如音频目录清理失败）不会吞掉，写入 `actionError` 由页面提示。
 *
 * @param source 可注入的数据源；测试用 fake，生产用 [ProjectRepositoryDataSource]
 */
class ProjectLibraryViewModel(
    private val source: ProjectLibraryDataSource,
) : ViewModel() {

    /** 生产入口：直接注入 `AppContainer.projectRepository`。 */
    constructor(repository: ProjectRepository) : this(ProjectRepositoryDataSource(repository))

    private val retrySignal = MutableStateFlow(0)

    private val _state = MutableStateFlow(ProjectLibraryUiState())
    val state: StateFlow<ProjectLibraryUiState> = _state.asStateFlow()

    init {
        observeProjects()
    }

    /**
     * 订阅项目列表。
     *
     * `retrySignal.flatMapLatest` 让重试可以重新订阅；`catch` 放在内层，
     * 一次异常只设置错误态，不会结束外层流，用户仍可重试。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeProjects() {
        viewModelScope.launch {
            retrySignal
                .flatMapLatest {
                    source.observeProjects()
                        .map { summaries -> summaries.map { summary -> summary.toProjectCardUi() } }
                        .catch { throwable ->
                            _state.update { current ->
                                current.copy(
                                    loading = false,
                                    loadError = throwable.toErrorMessage(),
                                )
                            }
                        }
                }
                .collect { cards ->
                    _state.update { current ->
                        current.copy(
                            loading = false,
                            loadError = null,
                            allProjects = cards,
                        )
                    }
                }
        }
    }

    /** 搜索框输入；只更新查询，过滤由 [ProjectLibraryUiState.projects] 派生。 */
    fun onQueryChange(query: String) {
        _state.update { current -> current.copy(query = query) }
    }

    /** 错误态重试：回到加载态并重新订阅数据源。 */
    fun retry() {
        _state.update { current -> current.copy(loading = true, loadError = null) }
        retrySignal.update { it + 1 }
    }

    /**
     * 重命名项目；空标题直接忽略（UI 已校验，这里再兜底）。
     *
     * 成功与否不手动改列表：仓库更新后 Room Flow 会发射新快照。
     */
    fun rename(id: Long, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching { source.rename(id, trimmed) }
                .onFailure { throwable -> reportActionError(throwable) }
        }
    }

    /**
     * 删除项目（二次确认由 UI 负责）。
     *
     * 删除成功后 Flow 自动刷新列表；清理文件失败时通过 `actionError` 提示。
     */
    fun delete(id: Long) {
        viewModelScope.launch {
            runCatching { source.delete(id) }
                .onFailure { throwable -> reportActionError(throwable) }
        }
    }

    /** 页面展示完 Snackbar 后清除，避免旋转屏幕重复提示。 */
    fun consumeActionError() {
        _state.update { current -> current.copy(actionError = null) }
    }

    private fun reportActionError(throwable: Throwable) {
        _state.update { current -> current.copy(actionError = throwable.toErrorMessage()) }
    }

    private fun Throwable.toErrorMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}
