package com.estimatefaster.audioforge.core.settings

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.estimatefaster.audioforge.core.voice.VoicePoolProvider
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 设置仓库（P2-03 / 执行计划 §7.4）：把 Preferences DataStore 映射为 [AppSettings]。
 *
 * 约定：
 * - **缺失 key 用默认值**；**非法或类型不匹配的存储值逐字段回退默认**，读取不崩溃。
 * - 写入前先跑 [SettingsValidator.validate]；非法时抛 [IllegalArgumentException]，
 *   消息里带字段名与字段错误，DataStore 保持原样。
 * - 读取时只做结构/范围校验；[isValidVoiceCategory]（默认放行）只在**保存前**参与校验，
 *   由 [create] 接到 `VoicePool`，避免把音色池资产问题带进设置流。
 * - 所有 key 严格使用 §7.4 的 snake_case 名称，文件名为 `audioforge_settings`。
 *
 * 构造注入 [DataStore] 便于用临时文件做纯 JVM 测试；App 侧用 [create]。
 *
 * @param isValidVoiceCategory 音色类别（63 类之一）校验；P2-03 保存前使用。
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val isValidVoiceCategory: (String) -> Boolean = { true },
) {

    /**
     * 设置流：每次 DataStore 变更后发出完整 [AppSettings]。
     *
     * 空 DataStore 发出 [AppSettings.DEFAULT]；损坏/IO 错误回退空 Preferences，
     * 单个字段的脏值（越界、非法 URL、错误类型）回退为该字段默认值。
     */
    val settings: Flow<AppSettings> = dataStore.data
        .catch { throwable ->
            // DataStore 文档建议：读文件失败时回退空 Preferences，而不是让消费者崩溃。
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences -> preferences.toAppSettings() }
        .distinctUntilChanged()

    /**
     * 校验并整体写入 [settings]。
     *
     * @throws IllegalArgumentException 任一字段非法；消息包含所有字段错误。
     */
    suspend fun update(settings: AppSettings) {
        validateForWrite(settings)
        dataStore.edit { preferences -> preferences.writeSettings(settings) }
    }

    /**
     * 原子地读取当前设置 → 变换 → 校验 → 写回。
     *
     * [transform] 应保持纯函数；DataStore 写冲突时可能被重放。
     *
     * @throws IllegalArgumentException 变换结果非法；DataStore 保持原样。
     */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { preferences ->
            val next = transform(preferences.toAppSettings())
            validateForWrite(next)
            preferences.writeSettings(next)
        }
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    private fun validateForWrite(settings: AppSettings) {
        val result = SettingsValidator.validate(settings, isValidVoiceCategory)
        require(result.isValid) {
            "设置不合法：" + result.errors.entries.joinToString("；") { (field, message) ->
                "$field: $message"
            }
        }
    }

    // ------------------------------------------------------------------
    // Preferences -> AppSettings（脏值逐字段回退）
    // ------------------------------------------------------------------

    private fun Preferences.toAppSettings(): AppSettings {
        val default = AppSettings.DEFAULT
        val raw = asMap()
        val stored = AppSettings(
            llmBaseUrl = raw.string(LLM_BASE_URL, default.llmBaseUrl),
            llmModel = raw.string(LLM_MODEL, default.llmModel),
            llmConcurrency = raw.int(LLM_CONCURRENCY, default.llmConcurrency),
            fishBaseUrl = raw.string(FISH_BASE_URL, default.fishBaseUrl),
            fishTtsModel = raw.string(FISH_TTS_MODEL, default.fishTtsModel),
            ttsConcurrency = raw.int(TTS_CONCURRENCY, default.ttsConcurrency),
            segmentMaxChars = raw.int(SEGMENT_MAX_CHARS, default.segmentMaxChars),
            narratorSilenceMs = raw.int(NARRATOR_SILENCE_MS, default.narratorSilenceMs),
            paragraphSilenceMs = raw.int(PARAGRAPH_SILENCE_MS, default.paragraphSilenceMs),
            defaultVoiceCategory = raw.string(DEFAULT_VOICE_CATEGORY, default.defaultVoiceCategory),
            defaultSpeed = raw.double(DEFAULT_SPEED, default.defaultSpeed),
            defaultVolume = raw.double(DEFAULT_VOLUME, default.defaultVolume),
            defaultPitch = raw.double(DEFAULT_PITCH, default.defaultPitch),
            minCoverage = raw.double(MIN_COVERAGE, default.minCoverage),
            excludedCharacters = raw.string(EXCLUDED_CHARACTERS, default.excludedCharacters),
            batchSize = raw.int(BATCH_SIZE, default.batchSize),
            wifiOnly = raw.boolean(WIFI_ONLY, default.wifiOnly),
            chargingOnly = raw.boolean(CHARGING_ONLY, default.chargingOnly),
            batteryPauseThreshold = raw.int(BATTERY_PAUSE_THRESHOLD, default.batteryPauseThreshold),
            thermalThrottle = raw.boolean(THERMAL_THROTTLE, default.thermalThrottle),
            themeMode = raw.string(THEME_MODE, default.themeMode),
            dynamicColor = raw.boolean(DYNAMIC_COLOR, default.dynamicColor),
            keepSegmentCacheDays = raw.int(KEEP_SEGMENT_CACHE_DAYS, default.keepSegmentCacheDays),
            notificationsEnabled = raw.boolean(
                NOTIFICATIONS_ENABLED,
                default.notificationsEnabled,
            ),
            onboardingCompleted = raw.boolean(
                ONBOARDING_COMPLETED,
                default.onboardingCompleted,
            ),
        )

        // 读取时不做音色池校验（那需要 Android AssetManager）；只校验范围/URL/theme 等结构规则。
        val result = SettingsValidator.validate(stored)
        return if (result.isValid) stored else stored.withDefaultsFor(result.errors.keys)
    }

    /**
     * 注意：`Preferences.Key` 只按 name 比较，同一 name 可能存了别的类型；
     * 这里统一用具体类型的 `as?` 安全转换，避免 `as T` 在擦除后漏检导致运行时 CCE。
     */
    private fun Map<Preferences.Key<*>, Any>.string(
        key: Preferences.Key<String>,
        fallback: String,
    ): String = this[key] as? String ?: fallback

    private fun Map<Preferences.Key<*>, Any>.int(
        key: Preferences.Key<Int>,
        fallback: Int,
    ): Int = this[key] as? Int ?: fallback

    private fun Map<Preferences.Key<*>, Any>.double(
        key: Preferences.Key<Double>,
        fallback: Double,
    ): Double = this[key] as? Double ?: fallback

    private fun Map<Preferences.Key<*>, Any>.boolean(
        key: Preferences.Key<Boolean>,
        fallback: Boolean,
    ): Boolean = this[key] as? Boolean ?: fallback

    /** 把 [SettingsValidator] 报错的字段逐个换回默认值；未报错字段保持原值。 */
    private fun AppSettings.withDefaultsFor(fields: Set<String>): AppSettings {
        var result = this
        fields.forEach { field -> result = result.withDefaultFor(field) }
        return result
    }

    private fun AppSettings.withDefaultFor(field: String): AppSettings {
        val default = AppSettings.DEFAULT
        return when (field) {
            "llmBaseUrl" -> copy(llmBaseUrl = default.llmBaseUrl)
            "llmModel" -> copy(llmModel = default.llmModel)
            "llmConcurrency" -> copy(llmConcurrency = default.llmConcurrency)
            "fishBaseUrl" -> copy(fishBaseUrl = default.fishBaseUrl)
            "fishTtsModel" -> copy(fishTtsModel = default.fishTtsModel)
            "ttsConcurrency" -> copy(ttsConcurrency = default.ttsConcurrency)
            "segmentMaxChars" -> copy(segmentMaxChars = default.segmentMaxChars)
            "narratorSilenceMs" -> copy(narratorSilenceMs = default.narratorSilenceMs)
            "paragraphSilenceMs" -> copy(paragraphSilenceMs = default.paragraphSilenceMs)
            "defaultVoiceCategory" -> copy(defaultVoiceCategory = default.defaultVoiceCategory)
            "defaultSpeed" -> copy(defaultSpeed = default.defaultSpeed)
            "defaultVolume" -> copy(defaultVolume = default.defaultVolume)
            "defaultPitch" -> copy(defaultPitch = default.defaultPitch)
            "minCoverage" -> copy(minCoverage = default.minCoverage)
            "batchSize" -> copy(batchSize = default.batchSize)
            "batteryPauseThreshold" -> copy(
                batteryPauseThreshold = default.batteryPauseThreshold,
            )
            "themeMode" -> copy(themeMode = default.themeMode)
            "keepSegmentCacheDays" -> copy(keepSegmentCacheDays = default.keepSegmentCacheDays)
            else -> this
        }
    }

    // ------------------------------------------------------------------
    // AppSettings -> Preferences
    // ------------------------------------------------------------------

    private fun MutablePreferences.writeSettings(settings: AppSettings) {
        this[LLM_BASE_URL] = settings.llmBaseUrl
        this[LLM_MODEL] = settings.llmModel
        this[LLM_CONCURRENCY] = settings.llmConcurrency
        this[FISH_BASE_URL] = settings.fishBaseUrl
        this[FISH_TTS_MODEL] = settings.fishTtsModel
        this[TTS_CONCURRENCY] = settings.ttsConcurrency
        this[SEGMENT_MAX_CHARS] = settings.segmentMaxChars
        this[NARRATOR_SILENCE_MS] = settings.narratorSilenceMs
        this[PARAGRAPH_SILENCE_MS] = settings.paragraphSilenceMs
        this[DEFAULT_VOICE_CATEGORY] = settings.defaultVoiceCategory
        this[DEFAULT_SPEED] = settings.defaultSpeed
        this[DEFAULT_VOLUME] = settings.defaultVolume
        this[DEFAULT_PITCH] = settings.defaultPitch
        this[MIN_COVERAGE] = settings.minCoverage
        this[EXCLUDED_CHARACTERS] = settings.excludedCharacters
        this[BATCH_SIZE] = settings.batchSize
        this[WIFI_ONLY] = settings.wifiOnly
        this[CHARGING_ONLY] = settings.chargingOnly
        this[BATTERY_PAUSE_THRESHOLD] = settings.batteryPauseThreshold
        this[THERMAL_THROTTLE] = settings.thermalThrottle
        this[THEME_MODE] = settings.themeMode
        this[DYNAMIC_COLOR] = settings.dynamicColor
        this[KEEP_SEGMENT_CACHE_DAYS] = settings.keepSegmentCacheDays
        this[NOTIFICATIONS_ENABLED] = settings.notificationsEnabled
        this[ONBOARDING_COMPLETED] = settings.onboardingCompleted
    }

    companion object {

        /** Preferences 文件名（不含 `.preferences_pb` 后缀）。 */
        const val DATASTORE_NAME: String = "audioforge_settings"

        // §7.4：key 名是稳定契约，不能改名、不能加前缀。
        private val LLM_BASE_URL = stringPreferencesKey("llm_base_url")
        private val LLM_MODEL = stringPreferencesKey("llm_model")
        private val LLM_CONCURRENCY = intPreferencesKey("llm_concurrency")
        private val FISH_BASE_URL = stringPreferencesKey("fish_base_url")
        private val FISH_TTS_MODEL = stringPreferencesKey("fish_tts_model")
        private val TTS_CONCURRENCY = intPreferencesKey("tts_concurrency")
        private val SEGMENT_MAX_CHARS = intPreferencesKey("segment_max_chars")
        private val NARRATOR_SILENCE_MS = intPreferencesKey("narrator_silence_ms")
        private val PARAGRAPH_SILENCE_MS = intPreferencesKey("paragraph_silence_ms")
        private val DEFAULT_VOICE_CATEGORY = stringPreferencesKey("default_voice_category")
        private val DEFAULT_SPEED = doublePreferencesKey("default_speed")
        private val DEFAULT_VOLUME = doublePreferencesKey("default_volume")
        private val DEFAULT_PITCH = doublePreferencesKey("default_pitch")
        private val MIN_COVERAGE = doublePreferencesKey("min_coverage")
        private val EXCLUDED_CHARACTERS = stringPreferencesKey("excluded_characters")
        private val BATCH_SIZE = intPreferencesKey("batch_size")
        private val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        private val CHARGING_ONLY = booleanPreferencesKey("charging_only")
        private val BATTERY_PAUSE_THRESHOLD = intPreferencesKey("battery_pause_threshold")
        private val THERMAL_THROTTLE = booleanPreferencesKey("thermal_throttle")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEEP_SEGMENT_CACHE_DAYS = intPreferencesKey("keep_segment_cache_days")
        private val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        private val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")

        /**
         * App 侧入口：进程内单例 DataStore，文件 `audioforge_settings.preferences_pb`。
         *
         * 音色类别校验延迟到保存时加载 `voice_pool.json`；资产异常时放行该回调，
         * 其它字段规则仍由 [SettingsValidator] 校验。
         */
        fun create(context: Context): SettingsRepository {
            val appContext = context.applicationContext
            return SettingsRepository(
                dataStore = appContext.settingsDataStore,
                isValidVoiceCategory = { category ->
                    runCatching {
                        VoicePoolProvider.get(appContext.assets).valid(category)
                    }.getOrDefault(true)
                },
            )
        }
    }
}

/**
 * 迁移占位：v1 不执行任何迁移。
 *
 * 未来若 key 改名 / 默认值变化 / 需要把值从旧 key 搬到新 key，在这里登记
 * `DataMigration<Preferences>`（或改用首个读取版本号 + 自定义迁移），
 * 再由 [settingsDataStore] 的 `produceMigrations` 接入。P2-03 不做实际迁移。
 */
private val MIGRATIONS: List<DataMigration<Preferences>> = emptyList()

private val Context.settingsDataStore by preferencesDataStore(
    name = SettingsRepository.DATASTORE_NAME,
    produceMigrations = { MIGRATIONS },
)
