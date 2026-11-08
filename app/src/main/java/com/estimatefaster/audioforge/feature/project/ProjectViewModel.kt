package com.estimatefaster.audioforge.feature.project

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estimatefaster.audioforge.data.db.ChapterBrief
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.data.db.ProjectSummary
import com.estimatefaster.audioforge.data.repository.ChapterRepository
import com.estimatefaster.audioforge.data.repository.CharacterRepository
import com.estimatefaster.audioforge.data.repository.ProjectRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 数据源接口：仓库只暴露观察方法，便于 JVM 测试注入 fake Flow。
 *
 * 真实仓库通过 [ProjectViewModel] 的次级构造器适配，页面/DI 不需要了解这些接口。
 */
fun interface ProjectSummarySource {
    fun observeProjects(): Flow<List<ProjectSummary>>
}

fun interface ChapterBriefsSource {
    fun observeBriefs(novelId: Long): Flow<List<ChapterBrief>>
}

fun interface CharacterSource {
    fun observeByNovel(novelId: Long): Flow<List<CharacterEntity>>
}

/**
 * 项目详情页 ViewModel（P4-05）。
 *
 * 组合三个 Room Flow：项目摘要、章节 brief、角色列表；任何一处数据变化都会
 * 重新计算 [ProjectUiState]（含统计/告警/人物截断）。
 *
 * 批次执行（P3-04 BatchRunner）尚未接入：本 ViewModel 不启动任务，开始/停止
 * 由 UI 回调解耦注入。
 *
 * @param novelId 当前项目 id
 * @param projects 项目摘要来源
 * @param chapters 章节 brief 来源（统计不读正文）
 * @param characters 人物来源
 * @param characterLimit 人物条最多展示的头像数
 */
class ProjectViewModel(
    private val novelId: Long,
    private val projects: ProjectSummarySource,
    private val chapters: ChapterBriefsSource,
    private val characters: CharacterSource,
    private val characterLimit: Int = DEFAULT_VISIBLE_CHARACTERS,
) : ViewModel() {

    /** 生产环境入口：直接注入 `AppContainer` 里的三个仓库。 */
    constructor(
        novelId: Long,
        projectRepository: ProjectRepository,
        chapterRepository: ChapterRepository,
        characterRepository: CharacterRepository,
        characterLimit: Int = DEFAULT_VISIBLE_CHARACTERS,
    ) : this(
        novelId = novelId,
        projects = ProjectSummarySource { projectRepository.observeProjects() },
        chapters = ChapterBriefsSource { id -> chapterRepository.observeBriefs(id) },
        characters = CharacterSource { id -> characterRepository.observeByNovel(id) },
        characterLimit = characterLimit,
    )

    private val retrySignal = MutableStateFlow(0)
    private val _state = MutableStateFlow(ProjectUiState())
    val state: StateFlow<ProjectUiState> = _state.asStateFlow()

    init {
        observeProject()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeProject() {
        viewModelScope.launch {
            // retrySignal 变化时重新订阅三个 Room Flow；catch 放在 flatMapLatest 内部，
            // 这样一次异常不会结束外层流，用户仍可重试。
            retrySignal
                .flatMapLatest {
                    combine(
                        projects.observeProjects(),
                        chapters.observeBriefs(novelId),
                        characters.observeByNovel(novelId),
                    ) { projectList, chapterList, characterList ->
                        buildProjectUiState(
                            novelId = novelId,
                            projects = projectList,
                            chapters = chapterList,
                            characters = characterList,
                            characterLimit = characterLimit,
                        )
                    }.catch { throwable ->
                        _state.value = ProjectUiState(
                            loading = false,
                            loadError = throwable.toLoadErrorMessage(),
                        )
                    }
                }
                .collect { uiState -> _state.value = uiState }
        }
    }

    /** 重新订阅数据源；用于错误态/项目刚创建时的刷新。 */
    fun retry() {
        _state.update { it.copy(loading = true, loadError = null) }
        retrySignal.update { it + 1 }
    }

    private fun Throwable.toLoadErrorMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}
