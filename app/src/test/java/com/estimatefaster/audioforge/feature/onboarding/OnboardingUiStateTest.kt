package com.estimatefaster.audioforge.feature.onboarding

import com.estimatefaster.audioforge.core.settings.AppSettings
import com.estimatefaster.audioforge.core.settings.SecureKeyStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4-10：Onboarding 的纯 JVM 测试。
 *
 * 不依赖 Android / Robolectric：ViewModel 通过依赖注入接收记录型 fake 的
 * SecureKeyStore / SettingsRepository 适配层，并用 `runTest` 的 `backgroundScope`
 * 执行异步保存；状态与校验逻辑本身是纯函数。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingUiStateTest {

    // ------------------------------------------------------------------
    // 步骤前进 / 后退 / 跳过
    // ------------------------------------------------------------------

    @Test
    fun defaults_start_on_welcome_with_valid_credentials() {
        val state = OnboardingUiState()

        assertEquals(OnboardingStep.Welcome, state.step)
        assertEquals(1, state.stepNumber)
        assertEquals(3, state.stepCount)
        assertEquals(AppSettings.DEFAULT.llmBaseUrl, state.llmBaseUrl)
        assertEquals(AppSettings.DEFAULT.llmModel, state.llmModel)
        assertEquals(AppSettings.DEFAULT.fishBaseUrl, state.fishBaseUrl)
        assertEquals(AppSettings.DEFAULT.fishTtsModel, state.fishModel)
        assertTrue(state.keysValid)
        assertNull(state.llmUrlError)
        assertNull(state.llmModelError)
    }

    @Test
    fun steps_advance_and_go_back_in_order() = runTest {
        val vm = newViewModel()

        vm.goNext()
        assertEquals(OnboardingStep.Keys, vm.state.value.step)
        assertEquals(2, vm.state.value.stepNumber)

        vm.goNext()
        assertEquals(OnboardingStep.Permissions, vm.state.value.step)
        assertEquals(3, vm.state.value.stepNumber)

        vm.goBack()
        assertEquals(OnboardingStep.Keys, vm.state.value.step)

        vm.goBack()
        assertEquals(OnboardingStep.Welcome, vm.state.value.step)

        // 首步继续后退保持不动。
        vm.goBack()
        assertEquals(OnboardingStep.Welcome, vm.state.value.step)
    }

    @Test
    fun next_on_last_step_finishes_and_marks_completed() = runTest {
        val settings = FakeSettingsStore()
        val vm = newViewModel(
            settingsStore = settings,
            initialState = OnboardingUiState(step = OnboardingStep.Permissions),
        )

        vm.goNext()
        advanceUntilIdle()

        assertTrue(vm.state.value.completed)
        assertFalse(vm.state.value.skipped)
        assertTrue(settings.current.onboardingCompleted)
    }

    @Test
    fun skip_completes_without_saving_typed_credentials() = runTest {
        val keyStore = RecordingKeyStore()
        val settings = FakeSettingsStore()
        val vm = newViewModel(keyStore = keyStore, settingsStore = settings)

        vm.updateKey(OnboardingKeyKind.Llm, "sk-typed-but-skipped")
        vm.skip()
        advanceUntilIdle()

        assertTrue(keyStore.saved.isEmpty())
        assertTrue(settings.current.onboardingCompleted)
        assertTrue(vm.state.value.completed)
        assertTrue(vm.state.value.skipped)
        assertFalse(vm.state.value.saving)
    }

    // ------------------------------------------------------------------
    // 字段校验
    // ------------------------------------------------------------------

    @Test
    fun blank_and_invalid_urls_are_reported() {
        val blank = OnboardingUiState(llmBaseUrl = "   ")
        assertEquals(OnboardingFieldError.UrlBlank, blank.llmUrlError)
        assertFalse(blank.keysValid)

        val invalid = OnboardingUiState(fishBaseUrl = "ftp://example.com")
        assertEquals(OnboardingFieldError.UrlInvalid, invalid.fishUrlError)
        assertFalse(invalid.keysValid)

        val garbage = OnboardingUiState(llmBaseUrl = "not a url")
        assertEquals(OnboardingFieldError.UrlInvalid, garbage.llmUrlError)
    }

    @Test
    fun blank_models_are_reported() {
        val blankLlm = OnboardingUiState(llmModel = " ")
        assertEquals(OnboardingFieldError.ModelBlank, blankLlm.llmModelError)
        assertFalse(blankLlm.keysValid)

        val blankFish = OnboardingUiState(fishModel = "")
        assertEquals(OnboardingFieldError.ModelBlank, blankFish.fishModelError)
        assertFalse(blankFish.keysValid)
    }

    @Test
    fun blank_key_is_allowed_for_navigation_but_not_for_testing() = runTest {
        val tester = RecordingTester()
        val vm = newViewModel(llmTester = tester)

        // 空 Key 仍可前进：引导允许“稍后设置”。
        vm.goNext()
        assertEquals(OnboardingStep.Keys, vm.state.value.step)
        assertTrue(vm.state.value.keyFor(OnboardingKeyKind.Llm).isEmpty())

        // 但“测试连接”要求先填 Key，且不会调用 tester。
        vm.testKey(OnboardingKeyKind.Llm)
        assertEquals(
            OnboardingTestState.Failed(OnboardingFieldError.KeyBlank),
            vm.state.value.llmTest,
        )
        assertTrue(tester.calls.isEmpty())

        vm.goNext()
        assertEquals(OnboardingStep.Permissions, vm.state.value.step)
    }

    @Test
    fun invalid_fields_block_next_and_save() = runTest {
        val keyStore = RecordingKeyStore()
        val settings = FakeSettingsStore()
        val vm = newViewModel(
            keyStore = keyStore,
            settingsStore = settings,
            initialState = OnboardingUiState(step = OnboardingStep.Keys, llmBaseUrl = ""),
        )

        vm.goNext()
        assertEquals(OnboardingStep.Keys, vm.state.value.step)
        assertEquals(OnboardingSaveError.InvalidFields, vm.state.value.saveError)

        vm.saveAndContinue()
        advanceUntilIdle()
        assertEquals(OnboardingStep.Keys, vm.state.value.step)
        assertEquals(0, settings.updateCount)
        assertTrue(keyStore.saved.isEmpty())
    }

    // ------------------------------------------------------------------
    // 测试连接（成功 / 失败 / 重试）
    // ------------------------------------------------------------------

    @Test
    fun testing_with_blank_key_reports_field_error_without_calling_tester() = runTest {
        val tester = RecordingTester()
        val vm = newViewModel(llmTester = tester)

        vm.testKey(OnboardingKeyKind.Llm)

        assertEquals(
            OnboardingTestState.Failed(OnboardingFieldError.KeyBlank),
            vm.state.value.llmTest,
        )
        assertTrue(tester.calls.isEmpty())
    }

    @Test
    fun test_connection_ok_uses_trimmed_fields() = runTest {
        val tester = RecordingTester()
        val vm = newViewModel(llmTester = tester)

        vm.updateKey(OnboardingKeyKind.Llm, "  sk-test-123456  ")
        vm.updateBaseUrl(OnboardingKeyKind.Llm, " https://example.com/v1 ")
        vm.updateModel(OnboardingKeyKind.Llm, " qwen-test ")
        vm.testKey(OnboardingKeyKind.Llm)
        advanceUntilIdle()

        assertEquals(OnboardingTestState.Ok, vm.state.value.llmTest)
        assertEquals(
            RecordingTester.Call(
                key = "sk-test-123456",
                baseUrl = "https://example.com/v1",
                model = "qwen-test",
            ),
            tester.calls.single(),
        )
    }

    @Test
    fun fish_test_uses_the_fish_tester() = runTest {
        val llmTester = RecordingTester()
        val fishTester = RecordingTester()
        val vm = newViewModel(llmTester = llmTester, fishTester = fishTester)

        vm.updateKey(OnboardingKeyKind.Fish, "fish-key-123456")
        vm.testKey(OnboardingKeyKind.Fish)
        advanceUntilIdle()

        assertEquals(OnboardingTestState.Ok, vm.state.value.fishTest)
        assertTrue(llmTester.calls.isEmpty())
        assertEquals(1, fishTester.calls.size)
    }

    @Test
    fun starting_another_test_resets_the_in_flight_card() = runTest {
        val vm = newViewModel(llmTester = BlockingTester(), fishTester = RecordingTester())

        vm.updateKey(OnboardingKeyKind.Llm, "sk-llm-123456")
        vm.updateKey(OnboardingKeyKind.Fish, "fish-key-123456")
        vm.testKey(OnboardingKeyKind.Llm)
        assertEquals(OnboardingTestState.Testing, vm.state.value.llmTest)

        vm.testKey(OnboardingKeyKind.Fish)
        assertEquals(OnboardingTestState.Idle, vm.state.value.llmTest)

        advanceUntilIdle()
        assertEquals(OnboardingTestState.Ok, vm.state.value.fishTest)
    }

    @Test
    fun failed_connection_can_be_retried() = runTest {
        val tester = RecordingTester()
        tester.enqueue(Result.failure<Unit>(IllegalStateException("network down")))
        tester.enqueue(Result.success(Unit))
        val vm = newViewModel(llmTester = tester)

        vm.updateKey(OnboardingKeyKind.Llm, "sk-test-123456")
        vm.testKey(OnboardingKeyKind.Llm)
        advanceUntilIdle()
        assertTrue(vm.state.value.llmTest is OnboardingTestState.Failed)

        // 同参数再次测试即重试。
        vm.testKey(OnboardingKeyKind.Llm)
        advanceUntilIdle()
        assertEquals(OnboardingTestState.Ok, vm.state.value.llmTest)
        assertEquals(2, tester.calls.size)
    }

    @Test
    fun editing_a_field_resets_test_state_and_cancels_stale_result() = runTest {
        val tester = RecordingTester()
        val vm = newViewModel(llmTester = tester)

        vm.updateKey(OnboardingKeyKind.Llm, "sk-test-123456")
        vm.testKey(OnboardingKeyKind.Llm)
        advanceUntilIdle()
        assertEquals(OnboardingTestState.Ok, vm.state.value.llmTest)

        vm.updateKey(OnboardingKeyKind.Llm, "sk-test-654321")
        assertEquals(OnboardingTestState.Idle, vm.state.value.llmTest)
    }

    // ------------------------------------------------------------------
    // 保存 / 完成
    // ------------------------------------------------------------------

    @Test
    fun save_writes_keys_and_settings_but_does_not_complete() = runTest {
        val keyStore = RecordingKeyStore()
        val settings = FakeSettingsStore()
        val vm = newViewModel(keyStore = keyStore, settingsStore = settings)

        vm.updateKey(OnboardingKeyKind.Llm, "sk-llm-123456")
        vm.updateKey(OnboardingKeyKind.Fish, "fish-key-123456")
        vm.updateBaseUrl(OnboardingKeyKind.Llm, "https://llm.example.com/v1")
        vm.updateModel(OnboardingKeyKind.Llm, "qwen-max")
        vm.updateBaseUrl(OnboardingKeyKind.Fish, "https://fish.example.com")
        vm.updateModel(OnboardingKeyKind.Fish, "s2-pro")

        vm.save()
        advanceUntilIdle()

        assertEquals("sk-llm-123456", keyStore.saved[SecureKeyStore.LLM_API_KEY])
        assertEquals("fish-key-123456", keyStore.saved[SecureKeyStore.FISH_API_KEY])
        assertEquals("https://llm.example.com/v1", settings.current.llmBaseUrl)
        assertEquals("qwen-max", settings.current.llmModel)
        assertEquals("https://fish.example.com", settings.current.fishBaseUrl)
        assertEquals("s2-pro", settings.current.fishTtsModel)
        assertFalse(settings.current.onboardingCompleted)
        assertEquals(OnboardingStep.Welcome, vm.state.value.step)
        assertFalse(vm.state.value.saving)
    }

    @Test
    fun save_and_continue_advances_to_permissions() = runTest {
        val keyStore = RecordingKeyStore()
        val settings = FakeSettingsStore()
        val vm = newViewModel(
            keyStore = keyStore,
            settingsStore = settings,
            initialState = OnboardingUiState(step = OnboardingStep.Keys),
        )

        vm.updateKey(OnboardingKeyKind.Llm, "sk-llm-123456")
        vm.saveAndContinue()
        advanceUntilIdle()

        assertEquals(OnboardingStep.Permissions, vm.state.value.step)
        assertEquals("sk-llm-123456", keyStore.saved[SecureKeyStore.LLM_API_KEY])
        assertFalse(settings.current.onboardingCompleted)
    }

    @Test
    fun blank_keys_are_not_written_and_finish_still_completes() = runTest {
        val keyStore = RecordingKeyStore()
        val settings = FakeSettingsStore()
        val vm = newViewModel(keyStore = keyStore, settingsStore = settings)

        vm.finish()
        advanceUntilIdle()

        assertTrue(keyStore.saved.isEmpty())
        assertTrue(settings.current.onboardingCompleted)
        assertTrue(vm.state.value.completed)
        assertFalse(vm.state.value.skipped)
        assertFalse(vm.state.value.saving)
    }

    @Test
    fun finish_persists_entered_keys_and_marks_completed() = runTest {
        val keyStore = RecordingKeyStore()
        val settings = FakeSettingsStore()
        val vm = newViewModel(keyStore = keyStore, settingsStore = settings)

        vm.updateKey(OnboardingKeyKind.Llm, "sk-llm-123456")
        vm.updateKey(OnboardingKeyKind.Fish, "fish-key-123456")
        vm.finish()
        advanceUntilIdle()

        assertEquals("sk-llm-123456", keyStore.saved[SecureKeyStore.LLM_API_KEY])
        assertEquals("fish-key-123456", keyStore.saved[SecureKeyStore.FISH_API_KEY])
        assertTrue(settings.current.onboardingCompleted)
        assertTrue(vm.state.value.completed)
    }

    @Test
    fun settings_failure_surfaces_error_and_keeps_onboarding_open() = runTest {
        val vm = newViewModel(settingsStore = FailingSettingsStore())

        vm.finish()
        advanceUntilIdle()

        assertFalse(vm.state.value.completed)
        assertFalse(vm.state.value.saving)
        assertTrue(vm.state.value.saveError is OnboardingSaveError.PersistFailed)
    }

    // ------------------------------------------------------------------
    // 测试替身
    // ------------------------------------------------------------------

    private fun TestScope.newViewModel(
        keyStore: OnboardingKeyStore = RecordingKeyStore(),
        settingsStore: OnboardingSettingsStore = FakeSettingsStore(),
        llmTester: OnboardingConnectionTester = RecordingTester(),
        fishTester: OnboardingConnectionTester = llmTester,
        initialState: OnboardingUiState = OnboardingUiState(),
    ): OnboardingViewModel = OnboardingViewModel(
        keyStore = keyStore,
        settingsStore = settingsStore,
        llmTester = llmTester,
        fishTester = fishTester,
        initialState = initialState,
        externalScope = backgroundScope,
    )

    private class RecordingKeyStore : OnboardingKeyStore {
        val saved: MutableMap<String, String> = linkedMapOf()

        override suspend fun saveKey(name: String, value: String) {
            saved[name] = value
        }
    }

    private class FakeSettingsStore(
        initial: AppSettings = AppSettings.DEFAULT,
    ) : OnboardingSettingsStore {
        var current: AppSettings = initial
            private set

        var updateCount: Int = 0
            private set

        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            current = transform(current)
            updateCount++
        }
    }

    private class FailingSettingsStore : OnboardingSettingsStore {
        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            throw IllegalStateException("settings write failed")
        }
    }

    /** 永不返回的 tester：用于验证切换 provider 时会取消在途测试。 */
    private class BlockingTester : OnboardingConnectionTester {
        override suspend fun test(key: String, baseUrl: String, model: String): Result<Unit> =
            awaitCancellation()
    }

    private class RecordingTester : OnboardingConnectionTester {
        data class Call(val key: String, val baseUrl: String, val model: String)

        val calls: MutableList<Call> = mutableListOf()
        private val results: MutableList<Result<Unit>> = mutableListOf()

        fun enqueue(result: Result<Unit>) {
            results += result
        }

        override suspend fun test(key: String, baseUrl: String, model: String): Result<Unit> {
            calls += Call(key = key, baseUrl = baseUrl, model = model)
            return if (results.isEmpty()) {
                Result.success(Unit)
            } else {
                results.removeAt(0)
            }
        }
    }
}
