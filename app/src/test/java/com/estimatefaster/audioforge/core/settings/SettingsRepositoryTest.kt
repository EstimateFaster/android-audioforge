package com.estimatefaster.audioforge.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P2-03：设置读写的默认值、完整往返、transform、保存前校验、进程重启（同一文件重开）
 * 与非法/错误类型的原始值回退。
 *
 * 纯 JVM：用 [PreferenceDataStoreFactory] + [TemporaryFolder]，不依赖 Robolectric。
 */
class SettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dataStoreFile: File
    private lateinit var storeScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        dataStoreFile = File(tempFolder.root, "${SettingsRepository.DATASTORE_NAME}.preferences_pb")
        openStore()
    }

    @After
    fun tearDown() {
        if (::storeScope.isInitialized) runBlocking { closeStore() }
    }

    @Test
    fun datastore_file_name_matches_the_plan() {
        assertEquals("audioforge_settings", SettingsRepository.DATASTORE_NAME)
    }

    @Test
    fun empty_datastore_reads_the_plan_defaults() = runTest {
        assertEquals(AppSettings.DEFAULT, repository.settings.first())
        assertTrue(dataStore.data.first().asMap().isEmpty())
    }

    @Test
    fun update_then_read_round_trips_every_field() = runTest {
        val custom = AppSettings(
            llmBaseUrl = "http://127.0.0.1:8080/v1",
            llmModel = "qwen-max",
            llmConcurrency = 3,
            fishBaseUrl = "https://fish.example.com/v1",
            fishTtsModel = "s2-pro",
            ttsConcurrency = 8,
            segmentMaxChars = 200,
            narratorSilenceMs = 0,
            paragraphSilenceMs = 5_000,
            defaultVoiceCategory = "female_young",
            defaultSpeed = 1.5,
            defaultVolume = 0.8,
            defaultPitch = -3.0,
            minCoverage = 0.8,
            excludedCharacters = "，。！？",
            batchSize = 10,
            wifiOnly = false,
            chargingOnly = true,
            batteryPauseThreshold = 30,
            thermalThrottle = false,
            themeMode = "dark",
            dynamicColor = false,
            keepSegmentCacheDays = 0,
            notificationsEnabled = false,
            onboardingCompleted = true,
        )
        val validation = SettingsValidator.validate(custom)
        assertTrue(validation.errors.toString(), validation.isValid)

        repository.update(custom)

        assertEquals(custom, repository.settings.first())
    }

    @Test
    fun update_persists_exactly_the_planned_snake_case_keys() = runTest {
        repository.update(AppSettings.DEFAULT)

        val keys = dataStore.data.first().asMap().keys.map { it.name }.toSet()
        assertEquals(EXPECTED_KEYS, keys)
    }

    @Test
    fun update_transform_only_changes_the_target_field() = runTest {
        repository.update { it.copy(batchSize = 7) }
        assertEquals(AppSettings.DEFAULT.copy(batchSize = 7), repository.settings.first())

        repository.update { it.copy(wifiOnly = false) }
        assertEquals(
            AppSettings.DEFAULT.copy(batchSize = 7, wifiOnly = false),
            repository.settings.first(),
        )
    }

    @Test
    fun invalid_updates_throw_and_leave_the_store_untouched() = runTest {
        val cases = listOf(
            "llmConcurrency" to AppSettings(llmConcurrency = 0),
            "segmentMaxChars" to AppSettings(segmentMaxChars = 10),
            "llmBaseUrl" to AppSettings(llmBaseUrl = "api.example.com"),
            "ttsConcurrency" to AppSettings(ttsConcurrency = 9),
            "themeMode" to AppSettings(themeMode = "auto"),
        )

        cases.forEach { (field, invalid) ->
            val error = expectIae { repository.update(invalid) }
            assertTrue(
                "错误消息缺少字段 $field：${error.message}",
                error.message.orEmpty().contains(field),
            )
        }

        assertEquals(AppSettings.DEFAULT, repository.settings.first())
        assertTrue(dataStore.data.first().asMap().isEmpty())
    }

    @Test
    fun invalid_update_message_lists_all_field_errors() = runTest {
        val error = expectIae {
            repository.update(
                AppSettings(
                    llmBaseUrl = "ftp://example.com",
                    llmConcurrency = 0,
                    segmentMaxChars = 10,
                ),
            )
        }

        listOf("llmBaseUrl", "llmConcurrency", "segmentMaxChars").forEach { field ->
            assertTrue(
                "错误消息缺少字段 $field：${error.message}",
                error.message.orEmpty().contains(field),
            )
        }
    }

    @Test
    fun update_transform_is_validated_before_writing() = runTest {
        val error = expectIae { repository.update { it.copy(ttsConcurrency = 0) } }
        assertTrue(error.message.orEmpty().contains("ttsConcurrency"))
        assertEquals(AppSettings.DEFAULT, repository.settings.first())
        assertTrue(dataStore.data.first().asMap().isEmpty())
    }

    @Test
    fun save_validation_uses_the_voice_category_checker() = runTest {
        val strictRepository = SettingsRepository(dataStore) { category ->
            category == "narrator_male"
        }

        val error = expectIae {
            strictRepository.update(AppSettings(defaultVoiceCategory = "bogus"))
        }
        assertTrue(error.message.orEmpty().contains("defaultVoiceCategory"))
        assertEquals(AppSettings.DEFAULT, repository.settings.first())
    }

    @Test
    fun invalid_raw_values_fall_back_to_defaults() = runTest {
        // 绕过 update 直接写非法值，模拟旧版本/外部写入的脏数据。
        dataStore.edit { preferences ->
            preferences[intPreferencesKey("llm_concurrency")] = 0
            preferences[intPreferencesKey("segment_max_chars")] = 10
            preferences[stringPreferencesKey("llm_base_url")] = "api.example.com"
            preferences[doublePreferencesKey("default_speed")] = 9.0
            preferences[stringPreferencesKey("theme_mode")] = "sepia"
            preferences[stringPreferencesKey("llm_model")] = "   "
        }

        assertEquals(AppSettings.DEFAULT, repository.settings.first())
    }

    @Test
    fun invalid_raw_value_resets_only_that_field() = runTest {
        dataStore.edit { preferences ->
            preferences[intPreferencesKey("llm_concurrency")] = 0
            preferences[intPreferencesKey("batch_size")] = 9
            preferences[booleanPreferencesKey("onboarding_completed")] = true
        }

        assertEquals(
            AppSettings.DEFAULT.copy(batchSize = 9, onboardingCompleted = true),
            repository.settings.first(),
        )
    }

    @Test
    fun wrongly_typed_raw_values_fall_back_without_crashing() = runTest {
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("batch_size")] = "five"
            preferences[intPreferencesKey("wifi_only")] = 1
            preferences[intPreferencesKey("default_speed")] = 42
            preferences[booleanPreferencesKey("llm_model")] = false
        }

        assertEquals(AppSettings.DEFAULT, repository.settings.first())
    }

    @Test
    fun settings_survive_reopening_the_same_file() = runTest {
        val saved = AppSettings(llmModel = "qwen-max", batchSize = 9, themeMode = "dark")
        repository.update(saved)

        // 关闭第一个 DataStore（模拟进程退出），再对同一文件新建 repository。
        closeStore()
        openStore()

        assertEquals(saved, repository.settings.first())
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun openStore() {
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { dataStoreFile },
        )
        repository = SettingsRepository(dataStore)
    }

    private suspend fun closeStore() {
        // 等 Job 完成，确保 DataStore 释放对同一文件的 active file 占用。
        storeScope.coroutineContext.job.cancelAndJoin()
    }

    private suspend fun expectIae(block: suspend () -> Unit): IllegalArgumentException {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return e
        }
        fail("应当抛出 IllegalArgumentException")
        throw AssertionError("unreachable")
    }

    private companion object {
        /** §7.4 的稳定 key 契约（snake_case）。 */
        val EXPECTED_KEYS: Set<String> = setOf(
            "llm_base_url",
            "llm_model",
            "llm_concurrency",
            "fish_base_url",
            "fish_tts_model",
            "tts_concurrency",
            "segment_max_chars",
            "narrator_silence_ms",
            "paragraph_silence_ms",
            "default_voice_category",
            "default_speed",
            "default_volume",
            "default_pitch",
            "min_coverage",
            "excluded_characters",
            "batch_size",
            "wifi_only",
            "charging_only",
            "battery_pause_threshold",
            "thermal_throttle",
            "theme_mode",
            "dynamic_color",
            "keep_segment_cache_days",
            "notifications_enabled",
            "onboarding_completed",
        )
    }
}
