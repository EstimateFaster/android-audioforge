package com.estimatefaster.audioforge.feature.script

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estimatefaster.audioforge.core.model.Emotion
import com.estimatefaster.audioforge.core.model.NARRATOR_ROLE_ID
import com.estimatefaster.audioforge.core.model.SegmentStatus
import com.estimatefaster.audioforge.core.model.SegmentType
import com.estimatefaster.audioforge.data.db.AppDatabase
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.data.db.SegmentEntity
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
 * 片段保存的最小可注入接口（P5-02）。
 *
 * 生产实现是 [RoomSegmentEditor]（`AppDatabase.segmentDao().update`）；纯 JVM 测试
 * 注入记录型 fake，不需要 Room/Robolectric。ViewModel 交给它的实体已经把
 * `status` 置回 `pending` 并清空音频路径/哈希/错误，因此适配器只负责持久化，
 * 不重复决定“哪些字段要重置”。
 */
interface SegmentEditor {

    /** 按主键写入整条片段；失败应抛出异常，由 ViewModel 转成可重试错误。 */
    suspend fun update(segment: SegmentEntity)
}

/** 生产适配器：直接更新 `segments` 表，不触碰其他片段。 */
class RoomSegmentEditor(database: AppDatabase) : SegmentEditor {

    private val dao = database.segmentDao()

    override suspend fun update(segment: SegmentEntity) {
        dao.update(segment)
    }
}

/**
 * 片段编辑的一次性事件。
 *
 * 用 [Channel] 而不是状态重放：Sheet 关闭/旋转后不会重复触发 `onSaved` 或
 * 再次请求重生成。`Saved` 只在写库成功后发送一次。
 */
sealed interface SegmentEditEvent {

    /** 写库成功；[segment] 是实际写入的实体（已标记待重生成）。 */
    data class Saved(val segment: SegmentEntity) : SegmentEditEvent

    /** 用户取消/下滑关闭；没有写库。 */
    data object Cancelled : SegmentEditEvent

    /** 用户请求“重新生成此句”；ViewModel 不直接调用 TTS。 */
    data class RegenerateRequested(val segmentId: Long) : SegmentEditEvent
}

/**
 * 片段编辑 Sheet 的 ViewModel（P5-02 / 执行计划 §12.4 S07）。
 *
 * 职责：
 * 1. 维护类型/角色/情绪/文本草稿与原值，支持重置；
 * 2. 保存前做纯状态校验（空文本/超长/旁白必须 narrator/对白必须选角色/角色不存在）；
 * 3. 通过 [SegmentEditor] 异步写入，成功后把状态原值同步为新值并发出一次性
 *    [SegmentEditEvent.Saved]；失败保留草稿、展示可重试错误；
 * 4. 取消不写库；重新生成只发事件，由调用方决定何时/如何调用 TTS。
 *
 * 保存会把 `status` 置为 `pending`、清空 `audioRelPath/durationMs/ttsKey/errorMsg`：
 * 只重合成被编辑的这一段，其他片段不受影响。
 *
 * @param segment 被编辑片段的 Room 实体（保存时以它为基础 copy，保留 id/chapterId/seq）
 * @param characters 本书全部角色（旁白由状态层自动补在首位）
 * @param editor 可注入的保存适配器
 * @param externalScope 测试注入的作用域；为空时使用 [viewModelScope]
 * @param dispatcher 保存协程使用的调度器；测试注入 TestDispatcher
 */
class SegmentEditViewModel(
    segment: SegmentEntity,
    characters: List<CharacterEntity>,
    private val editor: SegmentEditor,
    private val externalScope: CoroutineScope? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {

    private val _state = MutableStateFlow(SegmentEditUiState.from(segment, characters))
    val state: StateFlow<SegmentEditUiState> = _state.asStateFlow()

    private val _events = Channel<SegmentEditEvent>(capacity = Channel.BUFFERED)
    val events: Flow<SegmentEditEvent> = _events.receiveAsFlow()

    private val scope: CoroutineScope
        get() = externalScope ?: viewModelScope

    /** 最近一次成功写入/初始实体；后续保存都以它为基线，避免丢字段。 */
    private var persisted: SegmentEntity = segment

    private var saveJob: Job? = null

    init {
        require(segment.id > 0L) { "segment.id 必须为正数：${segment.id}" }
    }

    // ------------------------------------------------------------------
    // 草稿编辑
    // ------------------------------------------------------------------

    /**
     * 切换类型。
     *
     * 切到旁白时角色强制 `narrator`；切回对白时如果当前还是 `narrator`，清空角色，
     * 逼用户重新选择本书角色（旁白不能当对白角色）。保存中忽略输入。
     */
    fun setType(type: SegmentType) {
        _state.update { state ->
            if (state.saving || state.type == type) {
                state
            } else {
                val roleId = when (type) {
                    SegmentType.Narration -> NARRATOR_ROLE_ID
                    SegmentType.Dialogue -> if (state.roleId == NARRATOR_ROLE_ID) {
                        EMPTY_ROLE_ID
                    } else {
                        state.roleId
                    }
                }
                state.copy(type = type, roleId = roleId, saveError = null)
            }
        }
    }

    /**
     * 选择角色。
     *
     * 旁白状态下强制保守为 `narrator`，即使调用方传了别的 roleId；对白接受任意
     * 非空 roleId（未知角色由校验拦截）。空白会 trim 成空串，方便“请选择角色”提示。
     */
    fun setRoleId(roleId: String) {
        val normalized = roleId.trim()
        _state.update { state ->
            if (state.saving) {
                state
            } else {
                val nextRoleId = if (state.type == SegmentType.Narration) {
                    NARRATOR_ROLE_ID
                } else {
                    normalized
                }
                if (nextRoleId == state.roleId && state.saveError == null) {
                    state
                } else {
                    state.copy(roleId = nextRoleId, saveError = null)
                }
            }
        }
    }

    fun setEmotion(emotion: Emotion) {
        _state.update { state ->
            if (state.saving || state.emotion == emotion) {
                state
            } else {
                state.copy(emotion = emotion, saveError = null)
            }
        }
    }

    fun setText(text: String) {
        _state.update { state ->
            if (state.saving || state.text == text) {
                state
            } else {
                state.copy(text = text, saveError = null)
            }
        }
    }

    /** 恢复到打开 Sheet 时的原值，并清掉上一次保存失败的错误。 */
    fun reset() {
        _state.update { state ->
            if (state.saving) {
                state
            } else if (!state.isDirty && state.saveError == null) {
                state
            } else {
                state.copy(
                    type = state.originalType,
                    roleId = state.originalRoleId,
                    emotion = state.originalEmotion,
                    text = state.originalText,
                    saveError = null,
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 保存 / 重试 / 取消 / 重新生成
    // ------------------------------------------------------------------

    /**
     * 保存当前草稿。
     *
     * 校验不通过或已有在途保存时直接返回，不会调用 [SegmentEditor]。成功时：
     * - 把 `status` 置回 `pending`，清空音频路径/时长/哈希/错误，仅该段待重生成；
     * - 文本以 trim 后写入；
     * - 更新原值（Sheet 保持打开也不再是脏状态）；
     * - 发出一次 [SegmentEditEvent.Saved]。
     *
     * 失败只更新 [SegmentEditUiState.saveError]，草稿和校验状态保持不变，
     * 用户可点“重试”再次调用 [save]。
     */
    fun save() {
        if (saveJob?.isActive == true) return
        val snapshot = _state.value
        if (!snapshot.canSave) return

        val updated = persisted.copy(
            type = snapshot.type.wire,
            roleId = snapshot.roleId,
            emotion = snapshot.emotion.wire,
            text = snapshot.text.trim(),
            status = SegmentStatus.Pending.wire,
            audioRelPath = null,
            durationMs = null,
            errorMsg = null,
            ttsKey = null,
            updatedAt = System.currentTimeMillis(),
        )

        _state.update { state -> state.copy(saving = true, saveError = null) }
        saveJob = scope.launch(dispatcher) {
            try {
                editor.update(updated)
                if (!isActive) return@launch

                persisted = updated
                _state.update { state ->
                    state.copy(
                        // 写入时 text 可能被 trim；同步回草稿，避免“刚保存又变脏”。
                        text = updated.text,
                        saving = false,
                        saveError = null,
                        originalType = SegmentType.fromWire(updated.type),
                        originalRoleId = updated.roleId,
                        originalEmotion = Emotion.fromWire(updated.emotion),
                        originalText = updated.text,
                    )
                }
                _events.trySend(SegmentEditEvent.Saved(updated))
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

    /** 保存失败后的重试；没有错误时等价于普通保存。 */
    fun retry() {
        save()
    }

    /**
     * 取消/关闭。
     *
     * 不写库；恢复草稿到原值并发出 [SegmentEditEvent.Cancelled]，由 Sheet 调用
     * 调用方的 `onDismiss`。保存进行中忽略取消，避免出现“界面已关但写入还在跑”。
     */
    fun cancel() {
        if (_state.value.saving) return
        reset()
        _events.trySend(SegmentEditEvent.Cancelled)
    }

    /**
     * 请求重新生成当前句。
     *
     * 只发 [SegmentEditEvent.RegenerateRequested]；不写库、不直接调用 TTS。
     * 调用方可以据此决定是否先保存、再入队重合成。
     */
    fun requestRegenerate() {
        if (_state.value.saving) return
        _events.trySend(SegmentEditEvent.RegenerateRequested(_state.value.segmentId))
    }

    private fun Throwable.toSaveErrorMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    companion object {
        /** 对白/旁白切换时“没有选角色”的合法中间态。 */
        const val EMPTY_ROLE_ID: String = ""
    }
}
