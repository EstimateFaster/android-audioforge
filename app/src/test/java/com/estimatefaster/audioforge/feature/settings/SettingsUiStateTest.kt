package com.estimatefaster.audioforge.feature.settings

import com.estimatefaster.audioforge.core.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4-09：设置页 UI 状态的纯 JVM 测试。
 *
 * 不依赖 Android/Robolectric，也不启动协程：只验证草稿解析、字段错误映射、
 * 保存按钮门槛、Key 掩码与保存状态迁移。ViewModel 的调度逻辑复用这些纯函数。
 */
class SettingsUiStateTest {

    // ------------------------------------------------------------------
    // 默认值展示
    // ------------------------------------------------------------------

    @Test
    fun defaults_are_shown_as_editable_text() {
        val state = SettingsUiState.from(AppSettings.DEFAULT)
        val draft = state.draft

        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", draft.llmBaseUrl)
        assertEquals("qwen3.7-flash", draft.llmModel)
        assertEquals("https://api.fish.audio", draft.fishBaseUrl)
        assertEquals("s2.1-pro-free", draft.fishTtsModel)
        assertEquals("1", draft.llmConcurrency)
        assertEquals("4", draft.ttsConcurrency)
        assertEquals("120", draft.segmentMaxChars)
        assertEquals("0.97", draft.minCoverage)
        assertEquals("5", draft.batchSize)
        assertEquals("350", draft.narratorSilenceMs)
        assertEquals("700", draft.paragraphSilenceMs)
        assertEquals("narrator_male", draft.defaultVoiceCategory)
        assertEquals("1.0", draft.defaultSpeed)
        assertEquals("1.0", draft.defaultVolume)
        assertEquals("0.0", draft.defaultPitch)
        assertEquals("15", draft.batteryPauseThreshold)
        assertEquals("7", draft.keepSegmentCacheDays)
        assertEquals(ThemeWire.SYSTEM, draft.themeMode)
        assertTrue(draft.wifiOnly)
        assertFalse(draft.chargingOnly)
        assertTrue(draft.thermalThrottle)
        assertTrue(draft.notificationsEnabled)
        assertTrue(draft.dynamicColor)

        assertEquals(AppSettings.DEFAULT, draft.toSettings(AppSettings.DEFAULT))
        assertFalse("初始状态没有改动，保存按钮禁用", state.isDirty)
        assertFalse(state.canSave)
        assertTrue(state.errors.isEmpty())
        assertTrue(state.formatErrors.isEmpty())
    }

    // ------------------------------------------------------------------
    // 边界与范围错误
    // ------------------------------------------------------------------

    @Test
    fun boundary_values_are_valid_and_enable_save() {
        val upper = SettingsDraft.DEFAULT.copy(
            llmConcurrency = "4",
            ttsConcurrency = "8",
            segmentMaxChars = "300",
            minCoverage = "1.0",
            batchSize = "20",
            narratorSilenceMs = "3000",
            paragraphSilenceMs = "5000",
            defaultSpeed = "2.0",
            defaultVolume = "0.5",
            defaultPitch = "-12",
            batteryPauseThreshold = "50",
            keepSegmentCacheDays = "0",
            defaultVoiceCategory = "narrator_female",
        )
        val upperState = SettingsUiState.from(AppSettings.DEFAULT).withDraft(upper)
        assertTrue(upperState.formatErrors.toString(), upperState.formatErrors.isEmpty())
        assertTrue(upperState.errors.toString(), upperState.errors.isEmpty())
        assertTrue(upperState.isDirty)
        assertTrue(upperState.canSave)

        val lower = SettingsDraft.DEFAULT.copy(
            llmConcurrency = "1",
            ttsConcurrency = "1",
            segmentMaxChars = "20",
            minCoverage = "0.5",
            batchSize = "1",
            narratorSilenceMs = "0",
            paragraphSilenceMs = "0",
            defaultSpeed = "0.5",
            defaultVolume = "2.0",
            defaultPitch = "12",
            batteryPauseThreshold = "0",
            keepSegmentCacheDays = "0",
        )
        val lowerState = SettingsUiState.from(AppSettings.DEFAULT).withDraft(lower)
        assertTrue(lowerState.errors.toString(), lowerState.errors.isEmpty())
        assertTrue(lowerState.canSave)
    }

    @Test
    fun out_of_range_values_map_to_errors_and_disable_save() {
        val draft = SettingsDraft.DEFAULT.copy(
            llmConcurrency = "5",
            ttsConcurrency = "9",
            segmentMaxChars = "19",
            minCoverage = "1.5",
            batchSize = "21",
            narratorSilenceMs = "3001",
            paragraphSilenceMs = "-1",
            defaultSpeed = "0.4",
            defaultVolume = "2.5",
            defaultPitch = "13",
            batteryPauseThreshold = "51",
            keepSegmentCacheDays = "-1",
            themeMode = "sepia",
            llmBaseUrl = "ftp://invalid.example",
            llmModel = "",
            fishBaseUrl = "not a url",
            fishTtsModel = "",
            defaultVoiceCategory = "",
        )
        val state = SettingsUiState.from(AppSettings.DEFAULT).withDraft(draft)

        val expectedFields = setOf(
            SettingsField.LLM_CONCURRENCY.validatorKey,
            SettingsField.TTS_CONCURRENCY.validatorKey,
            SettingsField.SEGMENT_MAX_CHARS.validatorKey,
            SettingsField.MIN_COVERAGE.validatorKey,
            SettingsField.BATCH_SIZE.validatorKey,
            SettingsField.NARRATOR_SILENCE_MS.validatorKey,
            SettingsField.PARAGRAPH_SILENCE_MS.validatorKey,
            SettingsField.DEFAULT_SPEED.validatorKey,
            SettingsField.DEFAULT_VOLUME.validatorKey,
            SettingsField.DEFAULT_PITCH.validatorKey,
            SettingsField.BATTERY_PAUSE_THRESHOLD.validatorKey,
            SettingsField.KEEP_SEGMENT_CACHE_DAYS.validatorKey,
            SettingsField.THEME_MODE.validatorKey,
            SettingsField.LLM_BASE_URL.validatorKey,
            SettingsField.LLM_MODEL.validatorKey,
            SettingsField.FISH_BASE_URL.validatorKey,
            SettingsField.FISH_TTS_MODEL.validatorKey,
            SettingsField.DEFAULT_VOICE_CATEGORY.validatorKey,
        )
        assertEquals(expectedFields, state.errors.keys)
        assertTrue(state.formatErrors.isEmpty())
        assertTrue(state.hasErrors)
        assertFalse("有字段错误时保存按钮必须禁用", state.canSave)
        assertTrue(state.isDirty)
    }

    @Test
    fun unparseable_values_are_format_errors_not_range_errors() {
        val draft = SettingsDraft.DEFAULT.copy(
            segmentMaxChars = "abc",
            llmConcurrency = "",
            minCoverage = "",
            defaultSpeed = "1,5",
        )
        val state = SettingsUiState.from(AppSettings.DEFAULT).withDraft(draft)

        assertEquals(
            FieldFormatError.NotAnInteger,
            state.formatErrors[SettingsField.SEGMENT_MAX_CHARS.validatorKey],
        )
        assertEquals(
            FieldFormatError.Missing,
            state.formatErrors[SettingsField.LLM_CONCURRENCY.validatorKey],
        )
        assertEquals(
            FieldFormatError.Missing,
            state.formatErrors[SettingsField.MIN_COVERAGE.validatorKey],
        )
        assertEquals(
            FieldFormatError.NotANumber,
            state.formatErrors[SettingsField.DEFAULT_SPEED.validatorKey],
        )
        assertTrue(
            "解析失败的字段不应再报一次范围错误",
            state.errors.isEmpty(),
        )
        assertFalse(state.canSave)

        // 解析失败字段回退基线值，避免写入 0 之类的危险默认。
        val merged = draft.toSettings(AppSettings.DEFAULT)
        assertEquals(120, merged.segmentMaxChars)
        assertEquals(1, merged.llmConcurrency)
        assertEquals(0.97, merged.minCoverage, 1e-9)
        assertEquals(1.0, merged.defaultSpeed, 1e-9)
    }

    // ------------------------------------------------------------------
    // 草稿与 settings 合并语义
    // ------------------------------------------------------------------

    @Test
    fun dirty_draft_is_kept_when_settings_change_externally() {
        val initial = SettingsUiState.from(AppSettings.DEFAULT)
        val edited = initial.withDraft(initial.draft.copy(segmentMaxChars = "150"))
        assertTrue(edited.isDirty)

        val external = AppSettings.DEFAULT.copy(llmModel = "qwen-next", ttsConcurrency = 6)
        val merged = edited.withSettings(external)

        assertEquals("qwen-next", merged.settings.llmModel)
        assertEquals(6, merged.settings.ttsConcurrency)
        assertEquals("用户输入不能被外部写入清掉", "150", merged.draft.segmentMaxChars)
        assertEquals("未编辑字段也保持草稿快照", "qwen3.7-flash", merged.draft.llmModel)
        assertTrue(merged.isDirty)
    }

    @Test
    fun clean_state_adopts_external_settings_and_stops_loading() {
        val next = AppSettings.DEFAULT.copy(llmModel = "qwen-next", segmentMaxChars = 200)
        val state = SettingsUiState(loading = true).withSettings(next)

        assertFalse(state.loading)
        assertNull(state.loadError)
        assertEquals("qwen-next", state.draft.llmModel)
        assertEquals("200", state.draft.segmentMaxChars)
        assertFalse(state.isDirty)
        assertFalse(state.canSave)
    }

    @Test
    fun merge_preserves_fields_not_exposed_on_the_settings_screen() {
        val baseline = AppSettings.DEFAULT.copy(
            excludedCharacters = "的了吗",
            onboardingCompleted = true,
        )
        val merged = SettingsDraft.DEFAULT
            .copy(segmentMaxChars = "200")
            .toSettings(baseline)

        assertEquals(200, merged.segmentMaxChars)
        assertEquals("排除字符不走设置页，不能被重置", "的了吗", merged.excludedCharacters)
        assertTrue(merged.onboardingCompleted)
    }

    @Test
    fun with_field_and_toggle_only_touch_the_target_field() {
        val draft = SettingsDraft.DEFAULT
            .withField(SettingsField.SEGMENT_MAX_CHARS, "150")
            .withToggle(SettingsField.WIFI_ONLY, false)

        assertEquals("150", draft.segmentMaxChars)
        assertFalse(draft.wifiOnly)
        assertEquals(SettingsDraft.DEFAULT.llmModel, draft.llmModel)
        assertEquals(SettingsDraft.DEFAULT.batchSize, draft.batchSize)
        // 对非布尔/非文本字段是 no-op，避免 UI 误用时抛异常。
        assertEquals(draft, draft.withToggle(SettingsField.BATCH_SIZE, true))
        assertEquals(draft, draft.withField(SettingsField.WIFI_ONLY, "true"))
    }

    // ------------------------------------------------------------------
    // Key 掩码
    // ------------------------------------------------------------------

    @Test
    fun key_mask_keeps_only_last_four_characters() {
        val key = SecureKeyUi.from("sk-test-abcdef1234")

        assertTrue(key.configured)
        assertEquals("1234", key.last4)
        assertEquals("••••1234", key.masked)
        assertFalse("完整 Key 不能出现在 UI 状态里", key.masked.contains("sk-test"))
    }

    @Test
    fun unset_or_blank_key_reports_not_configured() {
        assertFalse(SecureKeyUi.from(null).configured)
        assertFalse(SecureKeyUi.from("").configured)
        assertFalse(SecureKeyUi.from("   ").configured)
        assertEquals(SecureKeyUi.UNSET, SecureKeyUi.from(null))
        assertEquals("", SecureKeyUi.from(null).last4)
        assertEquals("", SecureKeyUi.from(null).masked)
    }

    @Test
    fun short_key_hides_every_character() {
        val short = SecureKeyUi.from("abc")
        assertTrue(short.configured)
        assertEquals("", short.last4)
        assertEquals("••••", short.masked)
        assertFalse(short.masked.contains("abc"))

        val exactFour = SecureKeyUi.from("wxyz")
        assertEquals("wxyz 长度不超过 4 时整段隐藏", "", exactFour.last4)
    }

    @Test
    fun key_target_names_match_secure_store_contract() {
        assertEquals("llm_api_key", SettingsKeyTarget.LLM.keyName)
        assertEquals("fish_api_key", SettingsKeyTarget.FISH.keyName)
    }

    @Test
    fun keys_state_tracks_target_and_busy_flags() {
        val state = SettingsUiState.from(AppSettings.DEFAULT)
            .withKeyBusy(true)
            .withKeys(
                SecureKeysUi(
                    llm = SecureKeyUi.from("sk-abcdefgh"),
                    fish = SecureKeyUi.UNSET,
                ),
            )

        assertFalse(state.keyBusy)
        assertNull(state.keyError)
        assertEquals("efgh", state.keys.forTarget(SettingsKeyTarget.LLM).last4)
        assertFalse(state.keys.forTarget(SettingsKeyTarget.FISH).configured)

        val failed = SettingsUiState.from(AppSettings.DEFAULT)
            .withKeyBusy(true)
            .withKeyError("Keystore 不可用")
        assertFalse(failed.keyBusy)
        assertEquals("Keystore 不可用", failed.keyError)
    }

    // ------------------------------------------------------------------
    // 保存成功 / 失败状态
    // ------------------------------------------------------------------

    @Test
    fun save_success_updates_baseline_and_clears_dirty() {
        val initial = SettingsUiState.from(AppSettings.DEFAULT)
        val edited = initial.withDraft(
            initial.draft.copy(segmentMaxChars = "200", minCoverage = "0.95"),
        )
        assertTrue(edited.canSave)

        val saving = edited.markSaving()
        assertTrue(saving.saving)
        assertFalse(saving.saved)
        assertFalse("保存中不能重复点保存", saving.canSave)

        val target = saving.draft.toSettings(saving.settings)
        val saved = saving.markSaved(target)

        assertFalse(saved.saving)
        assertTrue(saved.saved)
        assertNull(saved.saveError)
        assertFalse(saved.isDirty)
        assertFalse(saved.canSave)
        assertEquals(200, saved.settings.segmentMaxChars)
        assertEquals(0.95, saved.settings.minCoverage, 1e-9)
        assertEquals("200", saved.draft.segmentMaxChars)
        assertEquals("0.95", saved.draft.minCoverage)

        // 仓库回写同一个值时不能把“已保存”提示或草稿弄丢。
        val echoed = saved.withSettings(target)
        assertTrue(echoed.saved)
        assertFalse(echoed.isDirty)
        assertEquals("200", echoed.draft.segmentMaxChars)
    }

    @Test
    fun save_failure_keeps_draft_and_allows_retry() {
        val initial = SettingsUiState.from(AppSettings.DEFAULT)
        val edited = initial.withDraft(initial.draft.copy(segmentMaxChars = "200"))
        val failed = edited.markSaving().markSaveFailed("设置不合法：voice category")

        assertFalse(failed.saving)
        assertFalse(failed.saved)
        assertEquals("设置不合法：voice category", failed.saveError)
        assertEquals("保存失败不能丢用户输入", "200", failed.draft.segmentMaxChars)
        assertTrue("修正/重试后仍可保存", failed.canSave)
    }

    @Test
    fun editing_after_save_clears_saved_hint() {
        val initial = SettingsUiState.from(AppSettings.DEFAULT)
        val saved = initial
            .withDraft(initial.draft.copy(segmentMaxChars = "200"))
            .markSaving()
            .markSaved(AppSettings.DEFAULT.copy(segmentMaxChars = 200))

        val edited = saved.withDraft(saved.draft.copy(segmentMaxChars = "180"))
        assertFalse(edited.saved)
        assertNull(edited.saveError)
        assertTrue(edited.isDirty)
        assertTrue(edited.canSave)
    }

    @Test
    fun load_error_blocks_save_until_retry() {
        val state = SettingsUiState.from(AppSettings.DEFAULT)
            .withDraft(SettingsDraft.DEFAULT.copy(segmentMaxChars = "200"))
            .withLoadError("DataStore IO")

        assertFalse(state.loading)
        assertEquals("DataStore IO", state.loadError)
        assertFalse(state.canSave)
    }

    /** 主题 wire 值（避免测试里再引 [com.estimatefaster.audioforge.core.settings.ThemeMode]）。 */
    private object ThemeWire {
        const val SYSTEM = "system"
    }
}
