package com.estimatefaster.audioforge.feature.project

import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.data.repository.CharacterTuning
import com.estimatefaster.audioforge.data.repository.TuningDefaults
import com.estimatefaster.audioforge.data.repository.TuningStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-06：角色微调 UI 状态 + ViewModel 的纯 JVM 测试。
 *
 * 不依赖 Android/Robolectric/Room：`TuningStore` 由记录型 fake 替代，
 * ViewModel 通过 `externalScope` 注入 `TestScope`，用 `advanceUntilIdle()` 推进
 * 载入/保存协程。覆盖：
 *
 * - 滑杆显示值回退全局默认、步进吸附、数值格式化；
 * - 三个范围的边界/越界（含 NaN/Infinity）；
 * - 脏标记、保存禁用条件、恢复默认；
 * - 载入成功/失败重试、未知角色容错；
 * - 保存成功事件、失败保留草稿并重试、清除三 key 的 reset 语义。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TuningUiStateTest {

    // ------------------------------------------------------------------
    // 滑杆显示 / 范围
    // ------------------------------------------------------------------

    @Test
    fun empty_draft_displays_global_defaults() {
        val defaults = TuningDefaults(speed = 1.2, volume = 0.8, pitch = 3.0)
        val state = TuningUiState(defaults = defaults, loading = false)

        assertEquals(1.2, state.speed, 0.0)
        assertEquals(0.8, state.volume, 0.0)
        assertEquals(3.0, state.pitch, 0.0)
        assertTrue(state.draft.followsGlobal)
        assertTrue(state.followsGlobal(TuningField.Speed))
        assertFalse(state.isDirty)
        assertFalse(state.canSave)
    }

    @Test
    fun validation_accepts_range_boundaries() {
        val drafts = listOf(
            TuningDraft(speed = 0.5),
            TuningDraft(speed = 2.0),
            TuningDraft(volume = 0.0),
            TuningDraft(volume = 2.0),
            TuningDraft(pitch = -12.0),
            TuningDraft(pitch = 12.0),
            TuningDraft(speed = 2.0, volume = 2.0, pitch = 12.0),
        )

        drafts.forEach { draft ->
            val state = TuningUiState(loading = false, draft = draft)
            assertTrue("边界应合法：$draft", state.isValid)
            assertTrue(state.validationErrors.isEmpty())
        }
    }

    @Test
    fun validation_rejects_values_outside_each_range() {
        val cases = listOf(
            TuningDraft(speed = 0.49) to TuningField.Speed,
            TuningDraft(speed = 2.01) to TuningField.Speed,
            TuningDraft(speed = Double.NaN) to TuningField.Speed,
            TuningDraft(speed = Double.POSITIVE_INFINITY) to TuningField.Speed,
            TuningDraft(volume = -0.01) to TuningField.Volume,
            TuningDraft(volume = 2.01) to TuningField.Volume,
            TuningDraft(volume = Double.NaN) to TuningField.Volume,
            TuningDraft(pitch = -12.1) to TuningField.Pitch,
            TuningDraft(pitch = 12.1) to TuningField.Pitch,
            TuningDraft(pitch = Double.NEGATIVE_INFINITY) to TuningField.Pitch,
        )

        cases.forEach { (draft, field) ->
            val state = TuningUiState(loading = false, draft = draft)
            assertFalse("应判为越界：$draft", state.isValid)
            assertEquals("$draft 应只报 $field", setOf(field), state.validationErrors)
        }
    }

    @Test
    fun format_tuning_value_uses_expected_precision() {
        assertEquals("1.25", formatTuningValue(TuningField.Speed, 1.25))
        assertEquals("1.00", formatTuningValue(TuningField.Volume, 1.0))
        assertEquals("+3.0", formatTuningValue(TuningField.Pitch, 3.0))
        assertEquals("-12.0", formatTuningValue(TuningField.Pitch, -12.0))
    }

    @Test
    fun snap_tuning_value_rounds_to_step() {
        assertEquals(1.25, snapTuningValue(TuningField.Speed, 1.23), 1e-9)
        assertEquals(0.5, snapTuningValue(TuningField.Speed, 0.52), 1e-9)
        assertEquals(2.0, snapTuningValue(TuningField.Volume, 1.98), 1e-9)
        assertEquals(2.5, snapTuningValue(TuningField.Pitch, 2.3), 1e-9)
        assertTrue(snapTuningValue(TuningField.Speed, Double.NaN).isNaN())
    }

    // ------------------------------------------------------------------
    // 脏标记 / 保存禁用 / 重置
    // ------------------------------------------------------------------

    @Test
    fun dirty_flag_tracks_changes_against_initial() {
        val clean = TuningUiState(
            loading = false,
            initial = TuningDraft(),
            draft = TuningDraft(),
        )
        assertFalse(clean.isDirty)
        assertFalse(clean.canSave)

        val dirty = clean.withValue(TuningField.Speed, 1.25)
        assertTrue(dirty.isDirty)
        assertTrue(dirty.canSave)

        val backToGlobal = dirty.withValue(TuningField.Speed, null)
        assertFalse("滑杆改回 null 等于未改动", backToGlobal.isDirty)

        val explicit = TuningUiState(
            loading = false,
            initial = TuningDraft(speed = 1.2),
            draft = TuningDraft(speed = 1.2),
        )
        assertFalse(explicit.isDirty)
        assertTrue("清空显式值也是改动", explicit.resetToDefaults().isDirty)
    }

    @Test
    fun save_is_disabled_while_loading_saving_error_or_invalid() {
        val dirty = TuningDraft(speed = 1.3)
        val base = TuningUiState(loading = false, initial = TuningDraft(), draft = dirty)
        assertTrue(base.canSave)

        assertFalse("加载中", base.copy(loading = true).canSave)
        assertFalse("保存中", base.copy(saving = true).canSave)
        assertFalse("加载失败", base.copy(loadError = "读取失败").canSave)
        assertFalse("越界", base.copy(draft = TuningDraft(speed = 2.5)).canSave)
        assertFalse("未改动", base.copy(initial = dirty).canSave)
    }

    @Test
    fun reset_to_defaults_clears_draft_and_uses_global_values() {
        val defaults = TuningDefaults(speed = 1.2, volume = 0.8, pitch = 3.0)
        val state = TuningUiState(
            loading = false,
            defaults = defaults,
            initial = TuningDraft(speed = 1.4, volume = 1.1, pitch = 2.0),
            draft = TuningDraft(speed = 1.4, volume = 1.1, pitch = 2.0),
        )
        assertTrue(state.canReset)

        val reset = state.resetToDefaults()

        assertTrue(reset.draft.followsGlobal)
        assertEquals(1.2, reset.speed, 0.0)
        assertEquals(0.8, reset.volume, 0.0)
        assertEquals(3.0, reset.pitch, 0.0)
        assertTrue(reset.isDirty)
        assertTrue(reset.canSave)
        assertFalse("已经全部跟随全局后无需再重置", reset.canReset)
    }

    // ------------------------------------------------------------------
    // ViewModel：载入
    // ------------------------------------------------------------------

    @Test
    fun load_maps_existing_tuning_and_character_profile() = runTest {
        val store = FakeTuningStore(
            tuning = CharacterTuning("role_0001", speed = 1.2, volume = 0.9, pitch = -1.0),
        )
        val vm = viewModel(store = store, character = character("林黛玉", "female_lead_gentle"))
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertNull(state.loadError)
        assertEquals("林黛玉", state.roleName)
        assertEquals("female_lead_gentle", state.voiceLabel)
        assertFalse(state.unknownRole)
        assertEquals(1.2, state.speed, 0.0)
        assertEquals(0.9, state.volume, 0.0)
        assertEquals(-1.0, state.pitch, 0.0)
        assertFalse(state.isDirty)
        assertFalse(state.canSave)
        assertFalse(state.followsGlobal(TuningField.Speed))
    }

    @Test
    fun load_failure_sets_error_and_retry_recovers() = runTest {
        val store = FakeTuningStore().apply {
            getFailure = IllegalStateException("读取失败")
        }
        val vm = viewModel(store = store)
        advanceUntilIdle()

        assertEquals("读取失败", vm.state.value.loadError)
        assertFalse(vm.state.value.canSave)

        store.getFailure = null
        vm.load()
        advanceUntilIdle()

        assertNull(vm.state.value.loadError)
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.draft.followsGlobal)
    }

    @Test
    fun unknown_role_falls_back_to_role_id_and_stays_editable() = runTest {
        val store = FakeTuningStore()
        val vm = viewModel(store = store, character = null)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.unknownRole)
        assertEquals("role_0001", state.displayName)
        assertNull(state.voiceLabel)

        vm.setSpeed(1.1)
        assertTrue(vm.state.value.canSave)
        vm.save()
        advanceUntilIdle()

        assertEquals(1, store.attempts)
        assertEquals(1.1, store.successes.single().speed!!, 0.0)
    }

    @Test
    fun character_source_failure_is_tolerated() = runTest {
        val store = FakeTuningStore()
        val vm = viewModel(
            store = store,
            characterSource = object : TuningCharacterSource {
                override suspend fun find(
                    novelId: Long,
                    roleId: String,
                ): CharacterEntity? = error("角色表读取失败")
            },
        )
        advanceUntilIdle()

        assertTrue(vm.state.value.unknownRole)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.loadError)
    }

    // ------------------------------------------------------------------
    // ViewModel：保存 / 重试 / 重置
    // ------------------------------------------------------------------

    @Test
    fun save_success_updates_baseline_and_emits_event() = runTest {
        val store = FakeTuningStore()
        val vm = viewModel(store = store, character = character())
        advanceUntilIdle()

        vm.setSpeed(1.3)
        vm.setPitch(-2.0)
        assertTrue(vm.state.value.canSave)

        vm.save()
        advanceUntilIdle()

        assertEquals(1, store.attempts)
        val call = store.successes.single()
        assertEquals(1L, call.novelId)
        assertEquals("role_0001", call.roleId)
        assertEquals(1.3, call.speed!!, 0.0)
        assertNull(call.volume)
        assertEquals(-2.0, call.pitch!!, 0.0)

        val state = vm.state.value
        assertFalse(state.saving)
        assertNull(state.saveError)
        assertFalse(state.isDirty)
        assertFalse(state.canSave)
        assertEquals(TuningSheetEvent.Saved, vm.events.first())
    }

    @Test
    fun save_failure_keeps_draft_and_retry_succeeds() = runTest {
        val store = FakeTuningStore().apply {
            setFailure = IllegalStateException("网络中断")
        }
        val vm = viewModel(store = store)
        advanceUntilIdle()

        vm.setVolume(0.8)
        vm.save()
        advanceUntilIdle()

        assertEquals(1, store.attempts)
        assertEquals("网络中断", vm.state.value.saveError)
        assertTrue(vm.state.value.isDirty)
        assertTrue("失败后应可直接重试", vm.state.value.canSave)
        assertFalse(vm.state.value.saving)

        store.setFailure = null
        vm.save()
        advanceUntilIdle()

        assertEquals(2, store.attempts)
        assertEquals(1, store.successes.size)
        assertNull(vm.state.value.saveError)
        assertFalse(vm.state.value.isDirty)
        assertEquals(TuningSheetEvent.Saved, vm.events.first())
    }

    @Test
    fun save_is_noop_when_clean_or_invalid() = runTest {
        val store = FakeTuningStore()
        val vm = viewModel(store = store)
        advanceUntilIdle()

        vm.save()
        advanceUntilIdle()
        assertEquals("未改动不写库", 0, store.attempts)

        vm.setSpeed(9.0)
        vm.save()
        advanceUntilIdle()

        assertEquals("越界不写库", 0, store.attempts)
        assertTrue(vm.state.value.validationErrors.contains(TuningField.Speed))
    }

    @Test
    fun reset_to_defaults_then_save_writes_nulls() = runTest {
        val store = FakeTuningStore(
            tuning = CharacterTuning("role_0001", speed = 1.4, volume = 0.9, pitch = 2.0),
        )
        val vm = viewModel(store = store)
        advanceUntilIdle()

        vm.resetToDefaults()
        val state = vm.state.value
        assertTrue(state.draft.followsGlobal)
        assertEquals(TuningDefaults.DEFAULT.speed, state.speed, 0.0)
        assertTrue(state.canSave)

        vm.save()
        advanceUntilIdle()

        val call = store.successes.single()
        assertNull(call.speed)
        assertNull(call.volume)
        assertNull(call.pitch)
    }

    @Test
    fun edits_are_ignored_while_saving() = runTest {
        val store = FakeTuningStore()
        val vm = viewModel(store = store)
        advanceUntilIdle()

        vm.setSpeed(1.3)
        vm.save()
        // 不推进调度器：保存协程尚未完成，saving = true，滑杆输入应被忽略。
        assertTrue(vm.state.value.saving)
        vm.setSpeed(1.8)
        assertEquals(1.3, vm.state.value.speed, 0.0)

        advanceUntilIdle()
        assertEquals(1.3, store.successes.single().speed!!, 0.0)
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun TestScope.viewModel(
        store: TuningStore,
        roleId: String = "role_0001",
        character: CharacterEntity? = null,
        voiceLabelFor: (String) -> String? = { null },
        characterSource: TuningCharacterSource? = null,
    ): TuningViewModel = TuningViewModel(
        novelId = 1L,
        roleId = roleId,
        store = store,
        characterSource = characterSource ?: object : TuningCharacterSource {
            override suspend fun find(novelId: Long, roleId: String): CharacterEntity? = character
        },
        voiceLabelFor = voiceLabelFor,
        externalScope = this,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun character(
        name: String = "林黛玉",
        category: String = "female_lead_gentle",
    ): CharacterEntity = CharacterEntity(
        id = 1L,
        novelId = 1L,
        roleId = "role_0001",
        name = name,
        category = category,
        voiceId = "voice-1",
    )

    private class FakeTuningStore(
        var tuning: CharacterTuning? = null,
        var defaultValues: TuningDefaults = TuningDefaults(),
    ) : TuningStore {

        var attempts: Int = 0

        var setFailure: Exception? = null

        var getFailure: Exception? = null

        val successes: MutableList<SetCall> = mutableListOf()

        override fun observe(novelId: Long): Flow<List<CharacterTuning>> =
            flowOf(listOfNotNull(tuning))

        override suspend fun get(novelId: Long, roleId: String): CharacterTuning? {
            getFailure?.let { throw it }
            return tuning
        }

        override suspend fun set(
            novelId: Long,
            roleId: String,
            speed: Double?,
            volume: Double?,
            pitch: Double?,
        ) {
            attempts++
            setFailure?.let { throw it }
            successes += SetCall(novelId, roleId, speed, volume, pitch)
            tuning = CharacterTuning(roleId, speed, volume, pitch)
        }

        override suspend fun resetRole(novelId: Long, roleId: String): Boolean {
            val existed = tuning != null
            tuning = null
            return existed
        }

        override suspend fun defaults(): TuningDefaults = defaultValues
    }

    private data class SetCall(
        val novelId: Long,
        val roleId: String,
        val speed: Double?,
        val volume: Double?,
        val pitch: Double?,
    )
}
