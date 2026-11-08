package com.estimatefaster.audioforge.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.estimatefaster.audioforge.data.db.ProjectSummary
import com.estimatefaster.audioforge.data.repository.ProjectRepository
import kotlinx.coroutines.flow.Flow

/**
 * 手写 ViewModel 工厂（P2-02）。
 *
 * 用一张 `Class -> (AppContainer) -> ViewModel` 的注册表创建 ViewModel；新增页面时
 * 在 [defaultCreators] 里加一行即可，不需要反射/Hilt。未注册的类明确抛
 * [IllegalArgumentException]，避免静默创建错对象。
 */
class AppViewModelFactory(
    internal val container: AppContainer,
    private val creators: Map<Class<*>, (AppContainer) -> ViewModel> = defaultCreators(),
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val creator = creators[modelClass]
            ?: creators.entries.firstOrNull { (key, _) -> key.isAssignableFrom(modelClass) }?.value
            ?: throw IllegalArgumentException("未注册的 ViewModel：${modelClass.name}")
        return creator(container) as T
    }

    companion object {
        /** 示例注册表；P2 之后按页面继续追加。 */
        fun defaultCreators(): Map<Class<*>, (AppContainer) -> ViewModel> =
            mapOf<Class<*>, (AppContainer) -> ViewModel>(
                ProjectListViewModel::class.java to { container ->
                    ProjectListViewModel(container.projectRepository)
                },
            )
    }
}

/**
 * 最小示例 ViewModel（无 UI）：只暴露项目列表 Flow，证明 ViewModel 能通过工厂拿到
 * AppContainer 的仓库依赖。真正的页面 ViewModel 在 P3/P4 替换/扩展。
 */
class ProjectListViewModel internal constructor(
    internal val projectRepository: ProjectRepository,
) : ViewModel() {

    val projects: Flow<List<ProjectSummary>> = projectRepository.observeProjects()
}
