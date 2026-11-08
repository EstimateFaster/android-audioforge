package com.estimatefaster.audioforge.feature.characters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estimatefaster.audioforge.core.model.Gender
import com.estimatefaster.audioforge.core.model.Importance
import com.estimatefaster.audioforge.core.voice.VoicePool
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.data.repository.CharacterRepository
import kotlinx.coroutines.CancellationException
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
 * 人物音色页的角色数据入口。
 *
 * 抽成函数式接口后，纯 JVM 测试可以注入 fake `Flow`，不需要 Room/Android；
 * 生产环境通过次级构造器适配 [CharacterRepository]。
 */
fun interface CharacterDataSource {
    fun observeCharacters(novelId: Long): Flow<List<CharacterEntity>>
}

/**
 * 人物音色页 ViewModel（P5-04 / 执行计划 §12.4 S08）。
 *
 * 数据流：`CharacterRepository.observeByNovel(novelId)` → 用 [VoicePool] 的
 * `labelFor` 把音色类别解析成展示名 → 构建 [CharactersUiState]。
 * 搜索/筛选只在内存里过滤，不重新订阅数据库；重命名/编辑别名/排除由页面回调
 * 交给宿主处理，本类不直接写库，避免与 P5-05 Sheet 的职责重叠。
 *
 * 音色池用 `(String) -> String?` 注入而不是直接依赖 `VoicePool`，这样状态层
 * 保持纯 Kotlin，测试可以给任意类别映射。
 *
 * @param novelId 当前项目 id
 * @param source 角色 Flow 来源
 * @param voiceLabelFor 音色类别 → 展示名；返回 null 表示未分配/未知
 */
class CharactersViewModel(
    private val novelId: Long,
    private val source: CharacterDataSource,
    private val voiceLabelFor: (String) -> String?,
) : ViewModel() {

    /** 生产入口：直接注入 `AppContainer.characterRepository` 与 `voicePool`。 */
    constructor(
        novelId: Long,
        characterRepository: CharacterRepository,
        voicePool: VoicePool,
    ) : this(
        novelId = novelId,
        source = CharacterDataSource { id -> characterRepository.observeByNovel(id) },
        voiceLabelFor = voicePool::labelFor,
    )

    private val retrySignal = MutableStateFlow(0)

    private val _state = MutableStateFlow(CharactersUiState())
    val state: StateFlow<CharactersUiState> = _state.asStateFlow()

    init {
        require(novelId > 0) { "novelId 必须为正数：$novelId" }
        observeCharacters()
    }

    /** 搜索框输入；只更新查询，过滤由 [CharactersUiState.visibleCharacters] 派生。 */
    fun setQuery(query: String) {
        if (query == _state.value.query) return
        _state.update { current -> current.copy(query = query) }
    }

    /** 性别筛选；`null` 表示“全部”。 */
    fun setGenderFilter(gender: Gender?) {
        if (gender == _state.value.genderFilter) return
        _state.update { current -> current.copy(genderFilter = gender) }
    }

    /** 重要性筛选；`null` 表示“全部”。 */
    fun setImportanceFilter(importance: Importance?) {
        if (importance == _state.value.importanceFilter) return
        _state.update { current -> current.copy(importanceFilter = importance) }
    }

    /** 清除搜索与两组筛选；没有生效条件时不做无谓的状态更新。 */
    fun clearFilters() {
        if (!_state.value.hasActiveFilters) return
        _state.update { current ->
            current.copy(query = "", genderFilter = null, importanceFilter = null)
        }
    }

    /** 错误态重试：回到加载态并重新订阅数据源。 */
    fun retry() {
        _state.update { current -> current.copy(loading = true, loadError = null) }
        retrySignal.update { it + 1 }
    }

    /**
     * 订阅角色列表。
     *
     * 用 `retrySignal.flatMapLatest` 让重试可以重新订阅；`catch` 放在内层，
     * 一次异常只设置错误态，不会结束外层流。搜索/筛选条件在收新快照时保留。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeCharacters() {
        viewModelScope.launch {
            retrySignal
                .flatMapLatest {
                    source.observeCharacters(novelId)
                        .map { entities ->
                            entities.map { entity ->
                                CharacterUi.from(entity, voiceLabelFor)
                            }
                        }
                        .catch { throwable ->
                            if (throwable is CancellationException) throw throwable
                            _state.update { current ->
                                current.copy(
                                    loading = false,
                                    loadError = throwable.toErrorMessage(),
                                )
                            }
                        }
                }
                .collect { characters ->
                    _state.update { current ->
                        current.copy(
                            loading = false,
                            loadError = null,
                            allCharacters = characters,
                        )
                    }
                }
        }
    }

    private fun Throwable.toErrorMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}
