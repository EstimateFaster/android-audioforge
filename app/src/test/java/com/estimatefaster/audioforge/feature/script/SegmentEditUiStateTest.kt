package com.estimatefaster.audioforge.feature.script

import com.estimatefaster.audioforge.core.model.Emotion
import com.estimatefaster.audioforge.core.model.NARRATOR_ROLE_ID
import com.estimatefaster.audioforge.core.model.SegmentType
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.data.db.SegmentEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P5-02：片段编辑 Sheet 的纯 JVM 测试。
 *
 * 不依赖 Android/Robolectric：校验、脏标记、重置都是纯状态；ViewModel 用
 * [StandardTestDispatcher] 替换 Main，注入 `externalScope`/`dispatcher` 和记录型
 * [FakeSegmentEditor]。覆盖：
 *
 * - 实体 → 状态投影（旁白角色收敛、未知对白角色保留）；
 * - 字段编辑/脏标记/重置；
 * - 空文本/超长/旁白必须 narrator/对白必须选角色/未知角色；
 * - 保存校验拦截、保存成功后只该段标 pending + 清音频字段；
 * - 保存失败可重试、成功事件一次性、取消不写库、重新生成只发事件。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SegmentEditUiStateTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------
    // 实体 → 状态
    // ------------------------------------------------------------------

    @Test
    fun state_from_entity_maps_type_role_emotion_text_and_roles() {
        val state = SegmentEditUiState.from(sampleEntity(), sampleCharacters())

        assertEquals(7L, state.segmentId)
        assertEquals(3, state.segmentNumber)
        assertEquals(SegmentType.Dialogue, state.type)
        assertEquals("role_0001", state.roleId)
        assertEquals(Emotion.Neutral, state.emotion)
        assertEquals("你好，顾青。", state.text)
        assertEquals(
            listOf(NARRATOR_ROLE_ID, "role_0001", "role_0002"),
            state.roles.map { it.roleId },
        )
        assertEquals("", state.roles.first().name)
        assertTrue(state.roles.first().isNarrator)
        assertEquals(listOf("沈砚", "顾青"), state.roles.drop(1).map { it.name })
        assertFalse(state.isDirty)
        assertTrue(state.validationErrors.isEmpty())
        assertTrue(state.canSave)
    }

    @Test
    fun narration_entity_is_normalized_to_narrator() {
        val state = SegmentEditUiState.from(
            sampleEntity(type = "narration", roleId = "role_0001"),
            sampleCharacters(),
        )

        assertEquals(SegmentType.Narration, state.type)
        assertEquals(NARRATOR_ROLE_ID, state.roleId)
        assertTrue(state.validationErrors.isEmpty())
        assertTrue(state.canSave)
    }

    @Test
    fun unknown_dialogue_role_is_kept_and_blocks_save() {
        val state = SegmentEditUiState.from(
            sampleEntity(roleId = "role_9999"),
            sampleCharacters(),
        )

        assertEquals("role_9999", state.roleId)
        assertEquals(listOf(SegmentEditError.UnknownRole), state.validationErrors)
        assertFalse(state.canSave)
    }

    @Test
    fun role_options_put_narrator_first_and_drop_duplicates() {
        val roles = buildSegmentRoleOptions(
            listOf(
                character(NARRATOR_ROLE_ID, "旁白"),
                character("role_0001", "沈砚"),
                character("role_0001", "重复角色"),
                character("  ", "空白"),
            ),
        )

        assertEquals(listOf(NARRATOR_ROLE_ID, "role_0001"), roles.map { it.roleId })
        assertEquals("", roles.first().name)
        assertEquals("沈砚", roles.last().name)
    }

    // ------------------------------------------------------------------
    // 字段编辑 / 脏标记 / 重置
    // ------------------------------------------------------------------

    @Test
    fun editing_fields_marks_dirty_and_reset_restores_original() = editTest { viewModel, _ ->
        val original = viewModel.state.value
        viewModel.setText("改过的台词")
        viewModel.setEmotion(Emotion.Happy)
        viewModel.setRoleId("role_0002")

        val edited = viewModel.state.value
        assertTrue(edited.isDirty)
        assertEquals("改过的台词", edited.text)
        assertEquals(Emotion.Happy, edited.emotion)
        assertEquals("role_0002", edited.roleId)

        viewModel.reset()

        val reset = viewModel.state.value
        assertFalse(reset.isDirty)
        assertEquals(original.type, reset.type)
        assertEquals(original.roleId, reset.roleId)
        assertEquals(original.emotion, reset.emotion)
        assertEquals(original.text, reset.text)
        assertNull(reset.saveError)
    }

    @Test
    fun switching_to_narration_forces_narrator_and_dialogue_requires_a_new_role() =
        editTest { viewModel, _ ->
            viewModel.setType(SegmentType.Narration)

            assertEquals(NARRATOR_ROLE_ID, viewModel.state.value.roleId)
            assertTrue(viewModel.state.value.isDirty)

            // 旁白状态下即使传入别的角色也强制收敛为 narrator。
            viewModel.setRoleId("role_0001")
            assertEquals(NARRATOR_ROLE_ID, viewModel.state.value.roleId)

            viewModel.setType(SegmentType.Dialogue)
            assertEquals("", viewModel.state.value.roleId)
            assertEquals(
                listOf(SegmentEditError.DialogueRequiresRole),
                viewModel.state.value.validationErrors,
            )

            viewModel.setRoleId("role_0002")
            assertTrue(viewModel.state.value.validationErrors.isEmpty())
            assertEquals("role_0002", viewModel.state.value.roleId)
        }

    @Test
    fun role_change_clears_save_error() = editTest { viewModel, editor ->
        editor.failure = IllegalStateException("disk full")
        viewModel.setText("新台词")
        viewModel.save()
        advanceUntilIdle()
        assertEquals("disk full", viewModel.state.value.saveError)

        viewModel.setRoleId("role_0002")

        assertNull(viewModel.state.value.saveError)
        assertEquals("role_0002", viewModel.state.value.roleId)
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    @Test
    fun blank_text_is_empty_error_and_disables_save() {
        val state = state(text = "   ")

        assertEquals(listOf(SegmentEditError.EmptyText), state.validationErrors)
        assertFalse(state.canSave)
    }

    @Test
    fun text_longer_than_limit_is_invalid() {
        val state = state(text = "字".repeat(SegmentEditUiState.MAX_TEXT_LENGTH + 1))

        assertEquals(listOf(SegmentEditError.TextTooLong), state.validationErrors)
        assertFalse(state.canSave)
    }

    @Test
    fun text_at_the_limit_is_valid() {
        val state = state(text = "字".repeat(SegmentEditUiState.MAX_TEXT_LENGTH))

        assertTrue(state.validationErrors.isEmpty())
        assertTrue(state.canSave)
    }

    @Test
    fun narration_with_non_narrator_role_is_invalid() {
        val state = state(
            type = SegmentType.Narration,
            roleId = "role_0001",
            text = "旁白一句",
        )

        assertEquals(
            listOf(SegmentEditError.NarrationRequiresNarrator),
            state.validationErrors,
        )
        assertFalse(state.canSave)
    }

    @Test
    fun dialogue_without_role_is_invalid_and_narrator_is_not_a_dialogue_role() {
        val blank = state(type = SegmentType.Dialogue, roleId = "", text = "台词")
        assertEquals(listOf(SegmentEditError.DialogueRequiresRole), blank.validationErrors)
        assertFalse(blank.canSave)

        val narrator = state(
            type = SegmentType.Dialogue,
            roleId = NARRATOR_ROLE_ID,
            text = "台词",
        )
        assertEquals(listOf(SegmentEditError.DialogueRequiresRole), narrator.validationErrors)
        assertFalse(narrator.canSave)
    }

    @Test
    fun dialogue_with_unknown_role_is_invalid() {
        val state = state(type = SegmentType.Dialogue, roleId = "role_9999")

        assertEquals(listOf(SegmentEditError.UnknownRole), state.validationErrors)
        assertFalse(state.canSave)
    }

    @Test
    fun saving_state_disables_save_until_it_finishes() = editTest { viewModel, editor ->
        val gate = CompletableDeferred<Unit>()
        editor.gate = gate
        viewModel.setText("新台词")

        viewModel.save()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.saving)
        assertFalse(viewModel.state.value.canSave)

        // 在途保存时再次点保存不应发起第二次写入。
        viewModel.save()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, editor.updates.size)
        assertFalse(viewModel.state.value.saving)
        assertTrue(viewModel.state.value.canSave)
    }

    @Test
    fun save_with_invalid_draft_never_calls_editor() = editTest { viewModel, editor ->
        viewModel.setText("   ")

        viewModel.save()
        advanceUntilIdle()

        assertTrue(editor.updates.isEmpty())
        assertFalse(viewModel.state.value.saving)
        assertTrue(SegmentEditError.EmptyText in viewModel.state.value.validationErrors)
    }

    // ------------------------------------------------------------------
    // 保存 / 失败重试 / 取消 / 重新生成
    // ------------------------------------------------------------------

    @Test
    fun save_writes_pending_segment_and_emits_saved_once() = editTest { viewModel, editor ->
        viewModel.setRoleId("role_0002")
        viewModel.setEmotion(Emotion.Happy)
        viewModel.setText("  改过的台词  ")

        viewModel.save()
        advanceUntilIdle()

        val updated = editor.updates.single()
        assertEquals(7L, updated.id)
        assertEquals(1L, updated.chapterId)
        assertEquals(2, updated.seq)
        assertEquals("dialogue", updated.type)
        assertEquals("role_0002", updated.roleId)
        assertEquals("happy", updated.emotion)
        assertEquals("改过的台词", updated.text)
        assertEquals("pending", updated.status)
        assertNull(updated.audioRelPath)
        assertNull(updated.durationMs)
        assertNull(updated.errorMsg)
        assertNull(updated.ttsKey)

        assertFalse(viewModel.state.value.saving)
        assertNull(viewModel.state.value.saveError)
        assertFalse(viewModel.state.value.isDirty)
        assertEquals("改过的台词", viewModel.state.value.text)
        assertEquals(SegmentEditEvent.Saved(updated), viewModel.events.first())
    }

    @Test
    fun save_failure_keeps_draft_and_retry_succeeds() = editTest { viewModel, editor ->
        editor.failure = IllegalStateException("disk full")
        viewModel.setText("新台词")

        viewModel.save()
        advanceUntilIdle()

        assertTrue(editor.updates.isEmpty())
        assertFalse(viewModel.state.value.saving)
        assertEquals("disk full", viewModel.state.value.saveError)
        assertTrue(viewModel.state.value.isDirty)
        assertEquals("新台词", viewModel.state.value.text)

        editor.failure = null
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(1, editor.updates.size)
        assertEquals("新台词", editor.updates.single().text)
        assertNull(viewModel.state.value.saveError)
        assertFalse(viewModel.state.value.isDirty)
        assertTrue(viewModel.events.first() is SegmentEditEvent.Saved)
    }

    @Test
    fun saved_event_is_not_replayed_after_more_edits() = editTest { viewModel, _ ->
        viewModel.setText("新台词")
        viewModel.save()
        advanceUntilIdle()

        assertTrue(viewModel.events.first() is SegmentEditEvent.Saved)

        viewModel.setText("又改了")
        advanceUntilIdle()

        assertNull(
            "成功后不应重放 Saved 事件",
            withTimeoutOrNull(100) { viewModel.events.first() },
        )
    }

    @Test
    fun cancel_restores_draft_and_never_writes() = editTest { viewModel, editor ->
        viewModel.setText("未保存的草稿")
        assertTrue(viewModel.state.value.isDirty)

        viewModel.cancel()
        advanceUntilIdle()

        assertTrue(editor.updates.isEmpty())
        assertFalse(viewModel.state.value.isDirty)
        assertEquals("你好，顾青。", viewModel.state.value.text)
        assertEquals(SegmentEditEvent.Cancelled, viewModel.events.first())
    }

    @Test
    fun regenerate_only_emits_event_without_writing() = editTest { viewModel, editor ->
        viewModel.setText("草稿")

        viewModel.requestRegenerate()
        advanceUntilIdle()

        assertTrue(editor.updates.isEmpty())
        assertEquals(
            SegmentEditEvent.RegenerateRequested(7L),
            viewModel.events.first(),
        )
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun editTest(
        segment: SegmentEntity = sampleEntity(),
        characters: List<CharacterEntity> = sampleCharacters(),
        block: suspend TestScope.(SegmentEditViewModel, FakeSegmentEditor) -> Unit,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val editor = FakeSegmentEditor()
            val viewModel = SegmentEditViewModel(
                segment = segment,
                characters = characters,
                editor = editor,
                externalScope = this,
                dispatcher = StandardTestDispatcher(testScheduler),
            )
            block(viewModel, editor)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun state(
        type: SegmentType = SegmentType.Dialogue,
        roleId: String = "role_0001",
        emotion: Emotion = Emotion.Neutral,
        text: String = "这一句台词",
        saving: Boolean = false,
    ): SegmentEditUiState = SegmentEditUiState(
        segmentId = 7L,
        segmentNumber = 3,
        type = type,
        roleId = roleId,
        emotion = emotion,
        text = text,
        roles = buildSegmentRoleOptions(sampleCharacters()),
        saving = saving,
    )

    private fun sampleEntity(
        id: Long = 7L,
        seq: Int = 2,
        type: String = "dialogue",
        roleId: String = "role_0001",
        emotion: String = "neutral",
        text: String = "你好，顾青。",
    ): SegmentEntity = SegmentEntity(
        id = id,
        chapterId = 1L,
        seq = seq,
        type = type,
        roleId = roleId,
        emotion = emotion,
        text = text,
        status = "error",
        audioRelPath = "audio/1/ch_001/seg_000003.mp3",
        durationMs = 1200,
        errorMsg = "旧错误",
        ttsKey = "old-hash",
        updatedAt = 100L,
    )

    private fun sampleCharacters(): List<CharacterEntity> = listOf(
        character("role_0001", "沈砚"),
        character("role_0002", "顾青"),
    )

    private fun character(roleId: String, name: String): CharacterEntity = CharacterEntity(
        novelId = 1L,
        roleId = roleId,
        name = name,
    )

    /** 记录写入实体；可配置失败或在测试里用 [gate] 卡住保存。 */
    private class FakeSegmentEditor : SegmentEditor {

        val updates: MutableList<SegmentEntity> = mutableListOf()
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun update(segment: SegmentEntity) {
            gate?.await()
            failure?.let { throw it }
            updates += segment
        }
    }
}
