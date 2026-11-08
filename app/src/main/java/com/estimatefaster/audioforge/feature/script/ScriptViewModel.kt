package com.estimatefaster.audioforge.feature.script

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.data.db.SegmentEntity
import com.estimatefaster.audioforge.data.repository.CharacterRepository
import com.estimatefaster.audioforge.data.repository.SegmentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 剧本页数据源：只订阅本章片段 + 本书角色，便于纯 JVM 测试注入 fake。
 *
 * 生产环境通过次级构造器适配 [SegmentRepository]/[CharacterRepository]；
 * 页面不需要知道 Room 或仓库实现。
 */
interface ScriptDataSource {
    fun observeSegments(chapterId: Long): Flow<List<SegmentEntity>>

    fun observeCharacters(novelId: Long): Flow<List<CharacterEntity>>
}

/**
 * 剧本页 ViewModel（P5-01）。
 *
 * 数据流：`SegmentRepository.observeByChapter(chapterId)` 与
 * `CharacterRepository.observeByNovel(novelId)` 两路 `combine`，每次发射都重建
 * 整章投影与角色 chips；筛选/搜索只在内存里过滤，不重新订阅数据库。
 *
 * 播放高亮用整章 0 基位置 [ScriptUiState.playingSegmentIndex]，与筛选解耦：
 * 当前段被筛掉时 `scrollTargetFor` 返回 null，页面不强制滚动。
 *
 * 所有状态更新都在主线程；`retry()` 重新收集两路 Flow，用于首屏失败恢复。
 *
 * @param chapterId 当前章节 id
 * @param novelId 当前项目 id（角色按书查询）
 * @param source 片段/角色数据源
 * @param chapterTitle 导航传入的章节标题；为空时页面显示兜底标题
 */
class ScriptViewModel(
    private val chapterId: Long,
    private val novelId: Long,
    private val source: ScriptDataSource,
    chapterTitle: String = "",
) : ViewModel() {

    /** 生产环境入口：直接注入 `AppContainer` 里的仓库。 */
    constructor(
        chapterId: Long,
        novelId: Long,
        chapterTitle: String,
        segmentRepository: SegmentRepository,
        characterRepository: CharacterRepository,
    ) : this(
        chapterId = chapterId,
        novelId = novelId,
        source = object : ScriptDataSource {
            override fun observeSegments(chapterId: Long): Flow<List<SegmentEntity>> =
                segmentRepository.observeByChapter(chapterId)

            override fun observeCharacters(novelId: Long): Flow<List<CharacterEntity>> =
                characterRepository.observeByNovel(novelId)
        },
        chapterTitle = chapterTitle,
    )

    private val _state = MutableStateFlow(ScriptUiState(chapterTitle = chapterTitle))
    val state: StateFlow<ScriptUiState> = _state.asStateFlow()

    private var observeJob: Job? = null

    init {
        require(chapterId > 0) { "chapterId 必须为正数：$chapterId" }
        require(novelId > 0) { "novelId 必须为正数：$novelId" }
        observe()
    }

    /** 台词关键词变化；纯内存过滤，不重新订阅数据库。 */
    fun setQuery(query: String) {
        if (query == _state.value.query) return
        _state.update { it.copy(query = query) }
    }

    /** 角色筛选；`null` 表示“全部”。 */
    fun setRoleFilter(roleId: String?) {
        val normalized = roleId?.trim()?.takeIf { it.isNotEmpty() }
        if (normalized == _state.value.selectedRoleId) return
        _state.update { it.copy(selectedRoleId = normalized) }
    }

    /** 清除角色筛选与搜索；没有生效条件时不做无谓的状态更新。 */
    fun clearFilters() {
        if (!_state.value.hasActiveFilters) return
        _state.update { it.copy(selectedRoleId = null, query = "") }
    }

    /**
     * 更新播放高亮段（整章 0 基位置）。
     * 越界/为负的索引会被收敛为 null，避免 UI 残留错误高亮。
     */
    fun setPlayingSegmentIndex(index: Int?) {
        _state.update { state ->
            val valid = index != null && state.segments.any { it.index == index }
            state.copy(playingSegmentIndex = if (valid) index else null)
        }
    }

    /** 首屏失败重试：重新收集两路 Flow，清空旧错误。 */
    fun retry() {
        if (_state.value.loadError == null) return
        observe()
    }

    private fun observe() {
        observeJob?.cancel()
        _state.update { it.copy(loading = true, loadError = null) }
        observeJob = viewModelScope.launch {
            try {
                combine(
                    source.observeSegments(chapterId),
                    source.observeCharacters(novelId),
                ) { segments, characters -> segments to characters }
                    .collect { (segments, characters) ->
                        publish(segments, characters)
                    }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(
                        loading = false,
                        loadError = throwable.toLoadErrorMessage(),
                    )
                }
            }
        }
    }

    private fun publish(
        segmentEntities: List<SegmentEntity>,
        characterEntities: List<CharacterEntity>,
    ) {
        val segments = mapScriptSegments(segmentEntities, characterEntities)
        val characters = buildScriptCharacters(characterEntities, segments)
        val current = _state.value
        // 角色被删/旁白消失后，不要留下选不中的 chip。
        val selectedRoleId = current.selectedRoleId?.takeIf { roleId ->
            characters.any { it.roleId == roleId }
        }
        val playingSegmentIndex = current.playingSegmentIndex?.takeIf { index ->
            segments.any { it.index == index }
        }
        _state.value = current.copy(
            loading = false,
            loadError = null,
            segments = segments,
            characters = characters,
            selectedRoleId = selectedRoleId,
            playingSegmentIndex = playingSegmentIndex,
        )
    }

    private fun Throwable.toLoadErrorMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}
