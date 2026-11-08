package com.estimatefaster.audioforge.feature.characters

import com.estimatefaster.audioforge.core.voice.VoicePool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-05：音色选择 Sheet 的纯 JVM 测试。
 *
 * 不依赖 Android/Robolectric：搜索/分组/回显都是纯状态；ViewModel 用
 * [StandardTestDispatcher] 替换 Main，注入 `externalScope`/`dispatcher` 和记录型
 * [FakeVoiceAssigner]。覆盖：
 *
 * - 63 类音色池加载、按 locale/性别分组、label/voiceId 映射；
 * - 搜索类别/音色/note、档位过滤、无结果；
 * - 当前选择回显、未知 category 与空音色池容错；
 * - locked 二次确认、保存成功/失败重试/取消不写库、在途保存去重。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoicePickerUiStateTest {

    // ------------------------------------------------------------------
    // 音色池加载与分组
    // ------------------------------------------------------------------

    @Test
    fun voice_pool_loads_all_63_categories_with_labels_ids_and_groups() {
        val pool = realVoicePool()
        val voices = voiceCategoriesFrom(pool)

        assertEquals(63, voices.size)
        assertEquals(63, voices.map { it.category }.distinct().size)
        assertTrue(voices.all { it.voiceId.isNotBlank() })

        val first = voices.first()
        assertEquals("narrator_male", first.category)
        assertEquals(pool.voiceIdFor("narrator_male"), first.voiceId)
        assertEquals(pool.labelFor("narrator_male"), first.label)
        assertEquals(VoiceGenderGroup.Narrator, first.gender)

        val sections = buildVoiceSections(voices)
        assertEquals(3, sections.size)
        assertEquals(
            listOf(
                VoiceGenderGroup.Narrator,
                VoiceGenderGroup.Male,
                VoiceGenderGroup.Female,
            ),
            sections.map { it.gender },
        )
        assertEquals(63, sections.sumOf { it.voices.size })
        assertTrue(sections.all { it.locale == DEFAULT_VOICE_LOCALE })
    }

    @Test
    fun sections_keep_first_seen_locale_and_gender_order() {
        val voices = listOf(
            VoiceCategoryUi("male_a", "A", "v1"),
            VoiceCategoryUi("female_b", "B", "v2"),
            VoiceCategoryUi("male_c", "C", "v3", locale = "en-US"),
            VoiceCategoryUi("male_d", "D", "v4", locale = "en-US"),
        )

        val sections = buildVoiceSections(voices)

        assertEquals(
            listOf(
                DEFAULT_VOICE_LOCALE to VoiceGenderGroup.Male,
                DEFAULT_VOICE_LOCALE to VoiceGenderGroup.Female,
                "en-US" to VoiceGenderGroup.Male,
            ),
            sections.map { it.locale to it.gender },
        )
        assertEquals(
            listOf("male_a"),
            sections.first().voices.map { it.category },
        )
        assertEquals(
            listOf("male_c", "male_d"),
            sections.last().voices.map { it.category },
        )
    }

    @Test
    fun available_groups_follow_enum_order_and_only_include_present_groups() {
        assertEquals(
            listOf(
                VoiceGenderGroup.Narrator,
                VoiceGenderGroup.Male,
                VoiceGenderGroup.Female,
            ),
            state().availableGroups,
        )
        assertEquals(
            listOf(VoiceGenderGroup.Female),
            state(
                voices = listOf(female("female_a")),
                selectedCategory = null,
            ).availableGroups,
        )
        assertTrue(
            state(voices = emptyList(), selectedCategory = null).availableGroups.isEmpty(),
        )
    }

    @Test
    fun unknown_category_prefix_falls_back_to_other_group() {
        assertEquals(VoiceGenderGroup.Other, voiceGenderGroupFor("legacy_voice"))
        assertEquals(VoiceGenderGroup.Other, voiceGenderGroupFor(""))
        assertEquals(VoiceGenderGroup.Narrator, voiceGenderGroupFor("narrator_male"))
        assertEquals(VoiceGenderGroup.Male, voiceGenderGroupFor("male_lead_steady"))
        assertEquals(VoiceGenderGroup.Female, voiceGenderGroupFor("female_young"))
    }

    // ------------------------------------------------------------------
    // 搜索 / 档位 / 无结果
    // ------------------------------------------------------------------

    @Test
    fun search_matches_category_label_voice_id_and_note() {
        assertEquals(
            listOf("male_brother"),
            state(query = " VOICE-MALE-BROTHER ").visibleVoices.map { it.category },
        )
        assertEquals(
            listOf("female_young"),
            state(query = "知性").visibleVoices.map { it.category },
        )
        assertEquals(
            listOf("male_brother"),
            state(query = "样音偏慢").visibleVoices.map { it.category },
        )
        assertEquals(
            listOf("female_lead_gentle", "female_young"),
            state(query = "female_").visibleVoices.map { it.category },
        )
    }

    @Test
    fun whitespace_only_search_returns_all_and_is_not_an_active_filter() {
        val result = state(query = " \t\n ")

        assertEquals(5, result.visibleCount)
        assertFalse(result.hasActiveFilters)
        assertEquals(sampleVoices().map { it.category }, result.visibleVoices.map { it.category })
    }

    @Test
    fun no_match_is_filtered_empty_but_pool_is_not_empty() {
        val result = state(query = "不存在的声音")

        assertTrue(result.isFilteredEmpty)
        assertFalse(result.hasNoVoices)
        assertTrue(result.visibleSections.isEmpty())
        assertEquals(0, result.visibleCount)
        assertEquals(5, result.totalVoiceCount)
    }

    @Test
    fun gender_filter_combines_with_search_using_and_semantics() {
        assertEquals(
            listOf("male_lead_steady", "male_brother"),
            state(genderFilter = VoiceGenderGroup.Male).visibleVoices.map { it.category },
        )
        assertEquals(
            listOf("male_brother"),
            state(
                query = "brother",
                genderFilter = VoiceGenderGroup.Male,
            ).visibleVoices.map { it.category },
        )
        assertTrue(
            state(
                query = "brother",
                genderFilter = VoiceGenderGroup.Female,
            ).visibleVoices.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 当前选择回显 / 容错
    // ------------------------------------------------------------------

    @Test
    fun current_selection_is_echoed_from_pool_and_persisted_voice_id_is_fallback() {
        val result = state(
            currentCategory = "male_brother",
            currentVoiceId = "stale-id",
        )

        assertEquals("male_brother", result.selectedCategory)
        assertEquals("【男配】兄长", result.currentDisplayName)
        assertEquals("voice-male-brother", result.currentDisplayVoiceId)
        assertTrue(result.hasSelection)
        assertTrue(result.isSameAsCurrent)
        assertFalse(result.canSave)
    }

    @Test
    fun unknown_current_category_keeps_raw_values_and_has_no_valid_selection() {
        val result = state(
            currentCategory = "legacy_voice",
            currentVoiceId = "legacy-voice-id",
            selectedCategory = null,
        )

        assertNull(result.selectedCategory)
        assertNull(result.currentVoice)
        assertEquals("legacy_voice", result.currentDisplayName)
        assertEquals("legacy-voice-id", result.currentDisplayVoiceId)
        assertFalse(result.canSave)
    }

    @Test
    fun empty_voice_pool_and_blank_labels_are_tolerated() {
        val empty = state(voices = emptyList(), selectedCategory = null)
        assertTrue(empty.hasNoVoices)
        assertTrue(empty.visibleVoices.isEmpty())
        assertTrue(empty.visibleSections.isEmpty())
        assertFalse(empty.canSave)

        val blank = VoiceCategoryUi(
            category = "male_custom",
            label = "   ",
            voiceId = "   ",
        )
        assertEquals("male_custom", blank.displayLabel)
        assertEquals("", blank.displayVoiceId)
        assertTrue(blank.matches("male_custom"))
        assertEquals(VoiceGenderGroup.Male, blank.gender)

        val blankSelection = state(
            voices = listOf(blank),
            currentCategory = "male_lead_steady",
            currentVoiceId = "",
            selectedCategory = "male_custom",
        )
        assertTrue(blankSelection.hasSelection)
        assertFalse(blankSelection.hasWritableSelection)
        assertFalse(blankSelection.canSave)
    }

    @Test
    fun selecting_an_unknown_category_is_ignored() = voicePickerTest {
        val viewModel = newViewModel()

        viewModel.selectVoice("not_in_pool")

        assertEquals("male_lead_steady", viewModel.state.value.selectedCategory)
    }

    @Test
    fun selecting_a_voice_updates_selection_and_enables_save() = voicePickerTest {
        val viewModel = newViewModel()

        viewModel.selectVoice("female_young")

        assertEquals("female_young", viewModel.state.value.selectedCategory)
        assertEquals("voice-female-young", viewModel.state.value.selectedVoice?.voiceId)
        assertFalse(viewModel.state.value.isSameAsCurrent)
        assertTrue(viewModel.state.value.canSave)
    }

    @Test
    fun clear_filters_resets_query_and_gender_but_keeps_selection() = voicePickerTest {
        val viewModel = newViewModel()
        viewModel.selectVoice("female_young")
        viewModel.setQuery("女")
        viewModel.setGenderFilter(VoiceGenderGroup.Female)

        assertTrue(viewModel.state.value.hasActiveFilters)
        viewModel.clearFilters()

        assertEquals("", viewModel.state.value.query)
        assertNull(viewModel.state.value.genderFilter)
        assertFalse(viewModel.state.value.hasActiveFilters)
        assertEquals("female_young", viewModel.state.value.selectedCategory)
    }

    // ------------------------------------------------------------------
    // 保存 / locked 二次确认 / 失败重试 / 取消
    // ------------------------------------------------------------------

    @Test
    fun unlocked_save_writes_once_and_emits_assigned_event() = voicePickerTest {
        val assigner = FakeVoiceAssigner()
        val viewModel = newViewModel(assigner = assigner)
        viewModel.selectVoice("female_young")

        viewModel.save()
        advanceUntilIdle()

        assertEquals(
            listOf(FakeVoiceAssigner.Call("role_0001", "female_young", "voice-female-young")),
            assigner.calls,
        )
        assertEquals("female_young", viewModel.state.value.currentCategory)
        assertEquals("female_young", viewModel.state.value.selectedCategory)
        assertEquals("voice-female-young", viewModel.state.value.currentVoiceId)
        assertNull(viewModel.state.value.saveError)
        assertTrue(viewModel.state.value.assigned)
        assertEquals(
            VoicePickerEvent.Assigned("role_0001", "female_young", "voice-female-young"),
            viewModel.events.first(),
        )
    }

    @Test
    fun locked_save_requires_confirmation_and_dismiss_never_writes() = voicePickerTest {
        val assigner = FakeVoiceAssigner()
        val viewModel = newViewModel(locked = true, assigner = assigner)
        viewModel.selectVoice("female_young")

        viewModel.save()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.pendingLockedConfirm)
        assertTrue(assigner.calls.isEmpty())

        viewModel.dismissLockedConfirm()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.pendingLockedConfirm)
        assertTrue(assigner.calls.isEmpty())

        viewModel.save()
        assertTrue(viewModel.state.value.pendingLockedConfirm)
        viewModel.confirmLockedSave()
        advanceUntilIdle()

        assertEquals(1, assigner.calls.size)
        assertFalse(viewModel.state.value.pendingLockedConfirm)
        assertEquals("female_young", viewModel.state.value.currentCategory)
    }

    @Test
    fun locked_but_unchanged_selection_never_prompts_or_writes() = voicePickerTest {
        val assigner = FakeVoiceAssigner()
        val viewModel = newViewModel(locked = true, assigner = assigner)

        viewModel.save()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.pendingLockedConfirm)
        assertTrue(assigner.calls.isEmpty())
    }

    @Test
    fun save_failure_keeps_selection_and_retry_succeeds() = voicePickerTest {
        val assigner = FakeVoiceAssigner()
        assigner.failuresRemaining = 1
        val viewModel = newViewModel(assigner = assigner)
        viewModel.selectVoice("female_young")

        viewModel.save()
        advanceUntilIdle()

        assertEquals(1, assigner.calls.size)
        assertEquals("模拟保存失败", viewModel.state.value.saveError)
        assertEquals("male_lead_steady", viewModel.state.value.currentCategory)
        assertEquals("female_young", viewModel.state.value.selectedCategory)
        assertTrue(viewModel.state.value.canSave)
        assertFalse(viewModel.state.value.saving)

        viewModel.retry()
        advanceUntilIdle()

        assertEquals(2, assigner.calls.size)
        assertNull(viewModel.state.value.saveError)
        assertEquals("female_young", viewModel.state.value.currentCategory)
        assertTrue(viewModel.events.first() is VoicePickerEvent.Assigned)
    }

    @Test
    fun cancel_restores_selection_and_never_writes() = voicePickerTest {
        val assigner = FakeVoiceAssigner()
        val viewModel = newViewModel(assigner = assigner)
        viewModel.selectVoice("female_young")

        viewModel.cancel()
        advanceUntilIdle()

        assertTrue(assigner.calls.isEmpty())
        assertEquals("male_lead_steady", viewModel.state.value.selectedCategory)
        assertEquals(VoicePickerEvent.Cancelled, viewModel.events.first())
    }

    @Test
    fun same_as_current_cannot_be_saved() = voicePickerTest {
        val assigner = FakeVoiceAssigner()
        val viewModel = newViewModel(assigner = assigner)

        assertFalse(viewModel.state.value.canSave)
        viewModel.save()
        advanceUntilIdle()

        assertTrue(assigner.calls.isEmpty())
    }

    @Test
    fun save_with_unknown_current_category_and_no_selection_is_noop() = voicePickerTest {
        val assigner = FakeVoiceAssigner()
        val viewModel = newViewModel(
            currentCategory = "legacy_voice",
            currentVoiceId = "legacy-voice-id",
            assigner = assigner,
        )

        assertNull(viewModel.state.value.selectedCategory)
        viewModel.save()
        advanceUntilIdle()

        assertTrue(assigner.calls.isEmpty())
    }

    @Test
    fun second_save_is_ignored_while_first_is_in_flight() = voicePickerTest {
        val assigner = GatedVoiceAssigner()
        val viewModel = newViewModel(assigner = assigner)
        viewModel.selectVoice("female_young")

        viewModel.save()
        assertTrue(viewModel.state.value.saving)
        assertTrue(assigner.calls.isEmpty())

        runCurrent()
        assertEquals(1, assigner.calls.size)
        viewModel.save()
        runCurrent()
        assertEquals(1, assigner.calls.size)

        assigner.gate.complete(Result.success(Unit))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.saving)
        assertEquals("female_young", viewModel.state.value.currentCategory)
    }

    @Test
    fun save_is_ignored_while_saving_for_locked_role() = voicePickerTest {
        val assigner = GatedVoiceAssigner()
        val viewModel = newViewModel(locked = true, assigner = assigner)
        viewModel.selectVoice("female_young")

        viewModel.save()
        viewModel.save()
        assertTrue(viewModel.state.value.pendingLockedConfirm)
        assertTrue(assigner.calls.isEmpty())

        viewModel.confirmLockedSave()
        assertTrue(viewModel.state.value.saving)
        runCurrent()
        assertEquals(1, assigner.calls.size)

        assigner.gate.complete(Result.success(Unit))
        advanceUntilIdle()
        assertEquals("female_young", viewModel.state.value.currentCategory)
    }

    // ------------------------------------------------------------------
    // 测试夹具
    // ------------------------------------------------------------------

    private fun voicePickerTest(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.newViewModel(
        voices: List<VoiceCategoryUi> = sampleVoices(),
        currentCategory: String = "male_lead_steady",
        currentVoiceId: String = "voice-male-lead",
        locked: Boolean = false,
        assigner: VoiceAssigner = FakeVoiceAssigner(),
    ): VoicePickerViewModel = VoicePickerViewModel(
        roleId = "role_0001",
        roleName = "沈砚",
        currentCategory = currentCategory,
        currentVoiceId = currentVoiceId,
        locked = locked,
        voices = voices,
        assigner = assigner,
        externalScope = this,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun state(
        currentCategory: String = "male_lead_steady",
        currentVoiceId: String = "voice-male-lead",
        locked: Boolean = false,
        voices: List<VoiceCategoryUi> = sampleVoices(),
        query: String = "",
        genderFilter: VoiceGenderGroup? = null,
        selectedCategory: String? = currentCategory,
    ): VoicePickerUiState = buildVoicePickerUiState(
        roleId = "role_0001",
        roleName = "沈砚",
        currentCategory = currentCategory,
        currentVoiceId = currentVoiceId,
        locked = locked,
        voices = voices,
        query = query,
        genderFilter = genderFilter,
        selectedCategory = selectedCategory,
    )

    private fun sampleVoices(): List<VoiceCategoryUi> = listOf(
        VoiceCategoryUi(
            category = "narrator_male",
            label = "【旁白】沉稳大气",
            voiceId = "voice-narrator",
        ),
        VoiceCategoryUi(
            category = "male_lead_steady",
            label = "【男主】沉稳磁性",
            voiceId = "voice-male-lead",
        ),
        VoiceCategoryUi(
            category = "male_brother",
            label = "【男配】兄长",
            voiceId = "voice-male-brother",
            note = "样音偏慢",
            speed = 1.15,
        ),
        VoiceCategoryUi(
            category = "female_lead_gentle",
            label = "【女主】清冷温柔",
            voiceId = "voice-female-lead",
        ),
        VoiceCategoryUi(
            category = "female_young",
            label = "【女配】知性",
            voiceId = "voice-female-young",
        ),
    )

    private fun female(category: String): VoiceCategoryUi = VoiceCategoryUi(
        category = category,
        label = category,
        voiceId = "voice-$category",
    )

    private fun realVoicePool(): VoicePool {
        val raw = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("voice-pool.json"),
        ) {
            "缺少测试资源 voice-pool.json"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return VoicePool.parse(raw)
    }

    private class FakeVoiceAssigner : VoiceAssigner {

        data class Call(
            val roleId: String,
            val category: String,
            val voiceId: String,
        )

        val calls: MutableList<Call> = mutableListOf()

        var failuresRemaining: Int = 0

        override suspend fun assign(
            roleId: String,
            category: String,
            voiceId: String,
        ): Result<Unit> {
            calls += Call(roleId, category, voiceId)
            if (failuresRemaining > 0) {
                failuresRemaining--
                return Result.failure(IllegalStateException("模拟保存失败"))
            }
            return Result.success(Unit)
        }
    }

    private class GatedVoiceAssigner : VoiceAssigner {

        val gate = CompletableDeferred<Result<Unit>>()

        val calls: MutableList<Unit> = mutableListOf()

        override suspend fun assign(
            roleId: String,
            category: String,
            voiceId: String,
        ): Result<Unit> {
            calls += Unit
            return gate.await()
        }
    }
}
