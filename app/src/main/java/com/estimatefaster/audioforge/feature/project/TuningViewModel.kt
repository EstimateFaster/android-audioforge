package com.estimatefaster.audioforge.feature.project

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.data.repository.CharacterRepository
import com.estimatefaster.audioforge.data.repository.TuningDefaults
import com.estimatefaster.audioforge.data.repository.TuningRepository
import com.estimatefaster.audioforge.data.repository.TuningStore
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
 * 角色资料的最小读取端口；用途只是展示名字/音色，不影响微调读写。
 *
 * 抽成函数式接口后，纯 JVM 测试可直接返回 [CharacterEntity] 或 null（未知角色），
 * 不需要 Room。
 */
fun interface TuningCharacterSource {
    suspend fun find(novelId: Long, roleId: String): CharacterEntity?

    companion object {
        /** 默认/测试用：永远返回“未知角色”。 */
        val Unknown: TuningCharacterSource = object : TuningCharacterSource {
            override suspend fun find(novelId: Long, roleId: String): CharacterEntity? = null
        }
    }
}

/** 保存成功后的一次性事件；Sheet 用它回调宿主的 `onSaved`。 */
sealed interface TuningSheetEvent {

    data object Saved : TuningSheetEvent
}

/**
 * 角色微调 Sheet 的 ViewModel（P5-06 / 执行计划 §12.4 S09、§15 P5-06）。
 *
 * 职责：
 * 1. 载入全局默认 [TuningStore.defaults]、角色已有微调与角色资料（名字/音色）；
 * 2. 维护滑杆草稿（null = 跟随全局），由 [TuningUiState] 派生脏标记/范围/保存禁用；
 * 3. 保存时把草稿整体写给 [TuningStore.set]（三个 null 即清除微调行）；
 * 4. 成功发 [TuningSheetEvent.Saved]；失败保留草稿并记录 [TuningUiState.saveError]，
 *    用户再次 [save] 即重试，不会丢改动。
 *
 * 容错：
 * - `roleId` 在角色表里不存在时 [TuningUiState.unknownRole] = true，标题回退 roleId，
 *   仍允许保存（`character_tuning` 只对 novels 有外键）；
 * - 读失败 → `loadError` + [load] 重试；角色资料读取失败只降级为“未知角色”，
 *   不阻塞微调本身。
 *
 * @param novelId 当前项目 id
 * @param roleId 目标角色；未知角色也允许打开
 * @param store 微调读写端口（生产为 [TuningRepository]）
 * @param characterSource 角色资料来源；返回 null 表示未知角色
 * @param voiceLabelFor 音色类别 → 展示名；返回 null 时退回类别 ID
 * @param initialState 测试/Preview 用初始状态
 * @param externalScope 测试注入的协程作用域；为空时使用 [viewModelScope]
 * @param dispatcher 载入/保存协程使用的调度器；测试注入 TestDispatcher
 */
class TuningViewModel(
    private val novelId: Long,
    private val roleId: String,
    private val store: TuningStore,
    private val characterSource: TuningCharacterSource = TuningCharacterSource.Unknown,
    private val voiceLabelFor: (String) -> String? = { null },
    initialState: TuningUiState = TuningUiState(novelId = novelId, roleId = roleId),
    private val externalScope: CoroutineScope? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {

    /** 生产入口：注入仓库后即可从角色表读名字/音色类别。 */
    constructor(
        novelId: Long,
        roleId: String,
        tuningRepository: TuningRepository,
        characterRepository: CharacterRepository,
        voiceLabelFor: (String) -> String? = { null },
    ) : this(
        novelId = novelId,
        roleId = roleId,
        store = tuningRepository,
        characterSource = object : TuningCharacterSource {
            override suspend fun find(novelId: Long, roleId: String): CharacterEntity? =
                characterRepository.getByRoleId(novelId, roleId)
        },
        voiceLabelFor = voiceLabelFor,
    )

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<TuningUiState> = _state.asStateFlow()

    private val _events = Channel<TuningSheetEvent>(capacity = Channel.BUFFERED)
    val events: Flow<TuningSheetEvent> = _events.receiveAsFlow()

    private var loadJob: Job? = null
    private var saveJob: Job? = null

    private val scope: CoroutineScope
        get() = externalScope ?: viewModelScope

    init {
        require(novelId > 0) { "novelId 必须为正数：$novelId" }
        require(roleId.isNotBlank()) { "roleId 不能为空" }
        load()
    }

    // ------------------------------------------------------------------
    // 载入
    // ------------------------------------------------------------------

    /**
     * 载入全局默认 + 已有微调 + 角色资料。
     *
     * 微调读取失败会展示加载错误（保存被禁用）；全局默认读取失败只回退
     * [TuningDefaults.DEFAULT]，不阻塞用户编辑。
     */
    fun load() {
        if (loadJob?.isActive == true) return
        _state.update { it.copy(loading = true, loadError = null) }
        loadJob = scope.launch(dispatcher) {
            val defaults = try {
                store.defaults()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                TuningDefaults.DEFAULT
            }

            val tuning = try {
                store.get(novelId, roleId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                if (isActive) {
                    _state.update { it.copy(loading = false, loadError = e.reason()) }
                }
                return@launch
            }

            val character = try {
                characterSource.find(novelId, roleId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }

            if (!isActive) return@launch

            val draft = TuningDraft.from(tuning)
            val category = character?.category?.trim().orEmpty()
            val voiceLabel = category.takeIf { it.isNotEmpty() }?.let { value ->
                voiceLabelFor(value)?.takeIf { it.isNotBlank() } ?: value
            }
            _state.update {
                it.copy(
                    loading = false,
                    loadError = null,
                    roleName = character?.name?.trim()?.takeIf { name -> name.isNotEmpty() }
                        ?: roleId,
                    voiceLabel = voiceLabel,
                    unknownRole = character == null,
                    defaults = defaults,
                    initial = draft,
                    draft = draft,
                    saving = false,
                    saveError = null,
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 编辑
    // ------------------------------------------------------------------

    fun setSpeed(value: Double) = setValue(TuningField.Speed, value)

    fun setVolume(value: Double) = setValue(TuningField.Volume, value)

    fun setPitch(value: Double) = setValue(TuningField.Pitch, value)

    /** 滑杆回调；载入中/保存中忽略输入，避免覆盖进行中的快照。 */
    fun setValue(field: TuningField, value: Double) {
        _state.update { current ->
            if (current.loading || current.saving) current else current.withValue(field, value)
        }
    }

    /** “恢复默认”：只改草稿，取消 Sheet 不会落库；点保存才清除微调行。 */
    fun resetToDefaults() {
        _state.update { current ->
            if (current.loading || current.saving) current else current.resetToDefaults()
        }
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    /**
     * 保存当前草稿。
     *
     * 未改动/越界/保存中/加载失败时直接返回，不会写库。失败只记录错误，
     * 草稿保持 [TuningUiState.isDirty]，再次调用即重试。
     */
    fun save() {
        val snapshot = _state.value
        if (!snapshot.canSave || saveJob?.isActive == true) return

        _state.update { it.copy(saving = true, saveError = null) }
        saveJob = scope.launch(dispatcher) {
            val error = try {
                store.set(
                    novelId = novelId,
                    roleId = roleId,
                    speed = snapshot.draft.speed,
                    volume = snapshot.draft.volume,
                    pitch = snapshot.draft.pitch,
                )
                null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                e.reason()
            }

            if (!isActive) return@launch

            if (error == null) {
                // 保存成功后把草稿当成新的基线；Sheet 即将关闭，但状态保持一致。
                _state.update {
                    it.copy(saving = false, saveError = null, initial = it.draft)
                }
                _events.trySend(TuningSheetEvent.Saved)
            } else {
                _state.update { it.copy(saving = false, saveError = error) }
            }
        }
    }
}

private fun Throwable.reason(): String =
    message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
