package com.estimatefaster.audioforge.feature.characters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.estimatefaster.audioforge.core.voice.VoicePool
import com.estimatefaster.audioforge.data.db.AppDatabase
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.data.repository.CharacterRepository
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
 * 音色保存的最小可注入接口（P5-05）。
 *
 * 生产实现是 [RoomVoiceAssigner]；纯 JVM 测试注入记录型 fake，
 * 不需要 Room/Android。失败通过 [Result.failure] 返回，让 ViewModel
 * 保留草稿并展示可重试错误；取消异常仍按协程语义向上传播。
 */
interface VoiceAssigner {

    /**
     * 只更新角色的 `category` / `voiceId` / `locked`。
     *
     * @param roleId 角色稳定 id（`role_0001` 等）
     * @param category VoicePool 的稳定类别 key
     * @param voiceId 该类别对应的 Fish Audio voice id
     */
    suspend fun assign(roleId: String, category: String, voiceId: String): Result<Unit>
}

/**
 * 生产适配器：写 `characters` 表的三个音色字段，**不覆盖 aliases**。
 *
 * `CharacterDao.updateVoice` 会同时写 `speed`，且没有 `locked` 字段，无法满足
 * “只改 category/voiceId/locked”的要求；因此先用仓库按 roleId 定位行，再在事务里
 * 执行窄 UPDATE。SQLite 的 UPDATE 仍会触发 Room 的 invalidation trigger，
 * 角色页的 Flow 能收到变更。
 */
class RoomVoiceAssigner(
    private val novelId: Long,
    private val repository: CharacterRepository,
    private val database: AppDatabase,
    private val voicePool: VoicePool,
) : VoiceAssigner {

    init {
        require(novelId > 0L) { "novelId 必须为正数：$novelId" }
    }

    override suspend fun assign(
        roleId: String,
        category: String,
        voiceId: String,
    ): Result<Unit> {
        val normalizedRoleId = roleId.trim()
        val normalizedCategory = category.trim()
        val normalizedVoiceId = voiceId.trim()
        return try {
            require(normalizedRoleId.isNotEmpty()) { "roleId 不能为空" }
            val expectedVoiceId = voicePool.voiceIdFor(normalizedCategory)
                ?: throw IllegalArgumentException("未知音色类别：$normalizedCategory")
            require(expectedVoiceId == normalizedVoiceId) {
                "音色 $normalizedVoiceId 与类别 $normalizedCategory 不匹配"
            }

            database.withTransaction {
                val existing = repository.getByRoleId(novelId, normalizedRoleId)
                    ?: throw IllegalStateException("角色不存在：$normalizedRoleId")
                database.openHelper.writableDatabase.execSQL(
                    """
                    UPDATE characters
                    SET category = ?, voiceId = ?, locked = 1
                    WHERE id = ? AND novelId = ?
                    """.trimIndent(),
                    arrayOf(normalizedCategory, expectedVoiceId, existing.id, novelId),
                )
            }
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    }
}

/**
 * 音色选择的一次性事件。
 *
 * 用 [Channel] 而不是状态重放：Sheet 关闭/旋转后不会重复触发保存成功回调。
 * [Assigned] 只在写库成功后发送一次。
 */
sealed interface VoicePickerEvent {

    /** 写库成功；宿主要据此刷新角色卡并关闭 Sheet。 */
    data class Assigned(
        val roleId: String,
        val category: String,
        val voiceId: String,
    ) : VoicePickerEvent

    /** 用户取消/下滑关闭；没有写库。 */
    data object Cancelled : VoicePickerEvent
}

/**
 * 音色选择 Sheet 的 ViewModel（P5-05 / 执行计划 §12.4 S09）。
 *
 * 职责：
 * 1. 维护搜索、档位过滤、选中音色；
 * 2. 保存前判断 locked 是否需要二次确认；
 * 3. 通过 [VoiceAssigner] 异步写入；成功发一次 [VoicePickerEvent.Assigned]，
 *    失败保留选中项并给出可重试错误；
 * 4. 取消只恢复草稿，不写库。
 *
 * @param roleId 角色稳定 id
 * @param roleName 角色展示名
 * @param currentCategory 打开 Sheet 时数据库里的音色类别
 * @param currentVoiceId 打开 Sheet 时数据库里的 voice id（类别未知时用于回显）
 * @param locked 是否已锁定；锁定角色保存前必须二次确认
 * @param voices 音色池投影
 * @param assigner 可注入保存适配器
 * @param externalScope 测试注入的作用域；为空时使用 [viewModelScope]
 * @param dispatcher 保存协程使用的调度器；测试注入 TestDispatcher
 */
class VoicePickerViewModel(
    roleId: String,
    roleName: String,
    currentCategory: String,
    currentVoiceId: String,
    locked: Boolean,
    voices: List<VoiceCategoryUi>,
    private val assigner: VoiceAssigner,
    private val externalScope: CoroutineScope? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {

    /**
     * 生产入口：从角色实体 + 音色池构造，保存走 [RoomVoiceAssigner]。
     *
     * 宿主只需传 `AppContainer.database` / `characterRepository` / `voicePool`；
     * 本类不直接持有 DAO 做读取，读取统一走仓库。
     */
    constructor(
        novelId: Long,
        role: CharacterEntity,
        voicePool: VoicePool,
        repository: CharacterRepository,
        database: AppDatabase,
        externalScope: CoroutineScope? = null,
    ) : this(
        roleId = role.roleId,
        roleName = role.name,
        currentCategory = role.category,
        currentVoiceId = role.voiceId,
        locked = role.locked,
        voices = voiceCategoriesFrom(voicePool),
        assigner = RoomVoiceAssigner(novelId, repository, database, voicePool),
        externalScope = externalScope,
    )

    private val _state = MutableStateFlow(
        buildVoicePickerUiState(
            roleId = roleId,
            roleName = roleName,
            currentCategory = currentCategory,
            currentVoiceId = currentVoiceId,
            locked = locked,
            voices = voices,
        ),
    )
    val state: StateFlow<VoicePickerUiState> = _state.asStateFlow()

    private val _events = Channel<VoicePickerEvent>(capacity = Channel.BUFFERED)
    val events: Flow<VoicePickerEvent> = _events.receiveAsFlow()

    private val scope: CoroutineScope
        get() = externalScope ?: viewModelScope

    private var saveJob: Job? = null

    init {
        require(roleId.trim().isNotEmpty()) { "roleId 不能为空" }
    }

    // ------------------------------------------------------------------
    // 搜索 / 档位 / 选中
    // ------------------------------------------------------------------

    /** 搜索框输入；只更新内存查询，不重新加载音色池。 */
    fun setQuery(query: String) {
        _state.update { state ->
            if (state.saving || state.query == query) {
                state
            } else {
                state.copy(query = query, saveError = null)
            }
        }
    }

    /** 档位过滤；`null` 表示全部。 */
    fun setGenderFilter(group: VoiceGenderGroup?) {
        _state.update { state ->
            if (state.saving || state.genderFilter == group) {
                state
            } else {
                state.copy(genderFilter = group, saveError = null)
            }
        }
    }

    /**
     * 选中一个音色类别。
     *
     * 未知 category 直接忽略，避免把脏值写进 [VoicePickerUiState.selectedCategory]。
     */
    fun selectVoice(category: String) {
        _state.update { state ->
            if (state.saving) return@update state
            val voice = state.voices.firstOrNull { it.category == category.trim() }
                ?: return@update state
            if (voice.category == state.selectedCategory && state.saveError == null) {
                state
            } else {
                state.copy(
                    selectedCategory = voice.category,
                    saveError = null,
                    assignedCategory = null,
                )
            }
        }
    }

    /** 清除搜索与档位；不影响已选音色。 */
    fun clearFilters() {
        _state.update { state ->
            if (state.saving || !state.hasActiveFilters) {
                state
            } else {
                state.copy(query = "", genderFilter = null, saveError = null)
            }
        }
    }

    // ------------------------------------------------------------------
    // 保存 / 二次确认 / 取消
    // ------------------------------------------------------------------

    /**
     * 保存选中音色。
     *
     * 无有效选择、与当前相同、或已有在途保存时直接返回。locked 角色第一次点击
     * 只进入 [VoicePickerUiState.pendingLockedConfirm]，不会调用 [VoiceAssigner]；
     * 用户确认后由 [confirmLockedSave] 真正写入。
     */
    fun save() {
        if (saveJob?.isActive == true) return
        val snapshot = _state.value
        if (!snapshot.canSave) return
        val voice = snapshot.selectedVoice ?: return
        if (snapshot.locked) {
            _state.update { state ->
                if (state.pendingLockedConfirm) state
                else state.copy(pendingLockedConfirm = true, saveError = null)
            }
            return
        }
        persist(voice)
    }

    /** 确认更换锁定角色的音色；只有 [save] 已经弹过确认时才会执行。 */
    fun confirmLockedSave() {
        if (saveJob?.isActive == true) return
        val snapshot = _state.value
        if (!snapshot.pendingLockedConfirm) return
        val voice = snapshot.selectedVoice
        if (voice == null || !snapshot.canSave) {
            _state.update { it.copy(pendingLockedConfirm = false) }
            return
        }
        _state.update { it.copy(pendingLockedConfirm = false) }
        persist(voice)
    }

    /** 关闭二次确认，不写库。 */
    fun dismissLockedConfirm() {
        _state.update { it.copy(pendingLockedConfirm = false) }
    }

    /** 保存失败后的重试；没有错误时等价于普通保存。 */
    fun retry() {
        save()
    }

    /**
     * 取消/关闭。
     *
     * 不写库；把选中项恢复到当前音色并清掉临时状态，然后发一次
     * [VoicePickerEvent.Cancelled]。保存进行中忽略取消，避免界面已关但写入还在跑。
     */
    fun cancel() {
        if (_state.value.saving) return
        _state.update { state ->
            val current = state.currentCategory.trim()
            val restored = current.takeIf { category ->
                category.isNotEmpty() && state.voices.any { it.category == category }
            }
            state.copy(
                selectedCategory = restored,
                pendingLockedConfirm = false,
                saveError = null,
                assignedCategory = null,
            )
        }
        _events.trySend(VoicePickerEvent.Cancelled)
    }

    private fun persist(voice: VoiceCategoryUi) {
        if (saveJob?.isActive == true) return
        val snapshot = _state.value
        _state.update { state ->
            state.copy(
                saving = true,
                saveError = null,
                pendingLockedConfirm = false,
                assignedCategory = null,
            )
        }
        saveJob = scope.launch(dispatcher) {
            try {
                val result = assigner.assign(
                    roleId = snapshot.roleId,
                    category = voice.category,
                    voiceId = voice.voiceId,
                )
                if (!isActive) return@launch
                result.fold(
                    onSuccess = {
                        _state.update { state ->
                            state.copy(
                                saving = false,
                                saveError = null,
                                currentCategory = voice.category,
                                currentVoiceId = voice.voiceId,
                                selectedCategory = voice.category,
                                assignedCategory = voice.category,
                            )
                        }
                        _events.trySend(
                            VoicePickerEvent.Assigned(
                                roleId = snapshot.roleId,
                                category = voice.category,
                                voiceId = voice.voiceId,
                            ),
                        )
                    },
                    onFailure = { throwable ->
                        _state.update { state ->
                            state.copy(
                                saving = false,
                                saveError = throwable.toSaveErrorMessage(),
                            )
                        }
                    },
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                if (!isActive) return@launch
                _state.update { state ->
                    state.copy(
                        saving = false,
                        saveError = throwable.toSaveErrorMessage(),
                    )
                }
            }
        }
    }

    private fun Throwable.toSaveErrorMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}
