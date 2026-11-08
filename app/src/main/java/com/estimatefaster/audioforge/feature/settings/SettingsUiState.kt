package com.estimatefaster.audioforge.feature.settings

import com.estimatefaster.audioforge.core.settings.AppSettings
import com.estimatefaster.audioforge.core.settings.SecureKeyStore
import com.estimatefaster.audioforge.core.settings.SettingsValidator

/**
 * 设置页可编辑的字段。
 *
 * [validatorKey] 与 [SettingsValidator.validate] 返回的错误 key 完全一致，
 * 这样 UI 的错误映射不需要再维护一份字符串常量。
 */
enum class SettingsField(val validatorKey: String) {
    LLM_BASE_URL("llmBaseUrl"),
    LLM_MODEL("llmModel"),
    LLM_CONCURRENCY("llmConcurrency"),
    FISH_BASE_URL("fishBaseUrl"),
    FISH_TTS_MODEL("fishTtsModel"),
    TTS_CONCURRENCY("ttsConcurrency"),
    SEGMENT_MAX_CHARS("segmentMaxChars"),
    NARRATOR_SILENCE_MS("narratorSilenceMs"),
    PARAGRAPH_SILENCE_MS("paragraphSilenceMs"),
    DEFAULT_VOICE_CATEGORY("defaultVoiceCategory"),
    DEFAULT_SPEED("defaultSpeed"),
    DEFAULT_VOLUME("defaultVolume"),
    DEFAULT_PITCH("defaultPitch"),
    MIN_COVERAGE("minCoverage"),
    BATCH_SIZE("batchSize"),
    WIFI_ONLY("wifiOnly"),
    CHARGING_ONLY("chargingOnly"),
    BATTERY_PAUSE_THRESHOLD("batteryPauseThreshold"),
    THERMAL_THROTTLE("thermalThrottle"),
    THEME_MODE("themeMode"),
    DYNAMIC_COLOR("dynamicColor"),
    KEEP_SEGMENT_CACHE_DAYS("keepSegmentCacheDays"),
    NOTIFICATIONS_ENABLED("notificationsEnabled"),
}

/**
 * 输入框的“格式”错误：值根本没法解析成 Int/Double。
 *
 * 范围/URL/非空等语义错误仍由 [SettingsValidator] 返回中文文案（core 的既有契约），
 * 这里只补充解析失败的情况；UI 用 `strings.xml` 的文案展示这些枚举。
 */
enum class FieldFormatError {
    /** 空输入。 */
    Missing,

    /** 不是整数。 */
    NotAnInteger,

    /** 不是数字。 */
    NotANumber,

    /** 枚举/选项值不在允许集合内（例如主题 wire 值）。 */
    InvalidOption,
}

/**
 * API Key 在 UI 侧的唯一形态：**只保留是否已设置 + 末 4 位**。
 *
 * `SecureKeyStore.get` 读到的明文在 ViewModel 里立即折算成本类型并丢弃，
 * 不会进入 [SettingsUiState]、不会写日志、也不会回显到输入框。
 */
data class SecureKeyUi(
    val configured: Boolean = false,
    val last4: String = "",
) {
    /**
     * 掩码文本。真实界面用 `strings.xml` 的模板（见 [MASK_PREFIX]），
     * 这里保留一个纯 JVM 可断言的版本。
     */
    val masked: String
        get() = if (configured) MASK_PREFIX + last4 else ""

    companion object {
        /** 掩码前缀；只用于展示，不表示密钥长度。 */
        const val MASK_PREFIX: String = "••••"

        /** 最多显示的末尾字符数。 */
        const val VISIBLE_SUFFIX: Int = 4

        val UNSET: SecureKeyUi = SecureKeyUi()

        /**
         * 从明文构造展示状态。
         *
         * - `null`/空白 → [UNSET]（界面显示“未设置”）；
         * - 长度 ≤ [VISIBLE_SUFFIX] 的 Key 一律不显示任何字符，避免短 Key 被整段泄露。
         */
        fun from(plaintext: String?): SecureKeyUi {
            val value = plaintext?.trim().orEmpty()
            if (value.isEmpty()) return UNSET
            val suffix = if (value.length > VISIBLE_SUFFIX) value.takeLast(VISIBLE_SUFFIX) else ""
            return SecureKeyUi(configured = true, last4 = suffix)
        }
    }
}

/** 两个 API Key 的展示状态。 */
data class SecureKeysUi(
    val llm: SecureKeyUi = SecureKeyUi.UNSET,
    val fish: SecureKeyUi = SecureKeyUi.UNSET,
) {
    fun forTarget(target: SettingsKeyTarget): SecureKeyUi = when (target) {
        SettingsKeyTarget.LLM -> llm
        SettingsKeyTarget.FISH -> fish
    }

    fun with(target: SettingsKeyTarget, key: SecureKeyUi): SecureKeysUi = when (target) {
        SettingsKeyTarget.LLM -> copy(llm = key)
        SettingsKeyTarget.FISH -> copy(fish = key)
    }
}

/**
 * Key 的写入目标。
 *
 * [keyName] 直接复用 [SecureKeyStore] 的常量（`const val` 编译期内联），
 * 避免 UI 层再写一份 key 名导致 AAD 不一致。
 */
enum class SettingsKeyTarget(val keyName: String) {
    LLM(SecureKeyStore.LLM_API_KEY),
    FISH(SecureKeyStore.FISH_API_KEY),
}

/**
 * 编辑草稿：所有可编辑字段都以字符串保存，允许用户输入到一半（例如 `"-"`、`"1."`、空串）。
 *
 * 布尔/主题枚举也保留原始形式，[toSettings] 才折算成 [AppSettings]；
 * 这样“非法输入”能停在草稿里被校验，而不是输入过程中被静默纠正。
 *
 * 未暴露在设置页的字段（`excludedCharacters`、`onboardingCompleted`）始终沿用
 * 调用方传入的基线，避免保存设置时把它们重置。
 */
data class SettingsDraft(
    // API
    val llmBaseUrl: String,
    val llmModel: String,
    val fishBaseUrl: String,
    val fishTtsModel: String,

    // 生成
    val llmConcurrency: String,
    val ttsConcurrency: String,
    val segmentMaxChars: String,
    val minCoverage: String,
    val batchSize: String,
    val narratorSilenceMs: String,
    val paragraphSilenceMs: String,
    val defaultVoiceCategory: String,
    val defaultSpeed: String,
    val defaultVolume: String,
    val defaultPitch: String,

    // 网络 / 设备
    val wifiOnly: Boolean,
    val chargingOnly: Boolean,
    val batteryPauseThreshold: String,
    val thermalThrottle: Boolean,
    val notificationsEnabled: Boolean,

    // 外观
    val themeMode: String,
    val dynamicColor: Boolean,

    // 存储
    val keepSegmentCacheDays: String,
) {

    /** 纯字符串字段的编辑入口；布尔字段用 [withToggle]。非法字段名会被忽略。 */
    fun withField(field: SettingsField, value: String): SettingsDraft = when (field) {
        SettingsField.LLM_BASE_URL -> copy(llmBaseUrl = value)
        SettingsField.LLM_MODEL -> copy(llmModel = value)
        SettingsField.LLM_CONCURRENCY -> copy(llmConcurrency = value)
        SettingsField.FISH_BASE_URL -> copy(fishBaseUrl = value)
        SettingsField.FISH_TTS_MODEL -> copy(fishTtsModel = value)
        SettingsField.TTS_CONCURRENCY -> copy(ttsConcurrency = value)
        SettingsField.SEGMENT_MAX_CHARS -> copy(segmentMaxChars = value)
        SettingsField.MIN_COVERAGE -> copy(minCoverage = value)
        SettingsField.BATCH_SIZE -> copy(batchSize = value)
        SettingsField.NARRATOR_SILENCE_MS -> copy(narratorSilenceMs = value)
        SettingsField.PARAGRAPH_SILENCE_MS -> copy(paragraphSilenceMs = value)
        SettingsField.DEFAULT_VOICE_CATEGORY -> copy(defaultVoiceCategory = value)
        SettingsField.DEFAULT_SPEED -> copy(defaultSpeed = value)
        SettingsField.DEFAULT_VOLUME -> copy(defaultVolume = value)
        SettingsField.DEFAULT_PITCH -> copy(defaultPitch = value)
        SettingsField.BATTERY_PAUSE_THRESHOLD -> copy(batteryPauseThreshold = value)
        SettingsField.KEEP_SEGMENT_CACHE_DAYS -> copy(keepSegmentCacheDays = value)
        SettingsField.THEME_MODE -> copy(themeMode = value)
        SettingsField.WIFI_ONLY,
        SettingsField.CHARGING_ONLY,
        SettingsField.THERMAL_THROTTLE,
        SettingsField.NOTIFICATIONS_ENABLED,
        SettingsField.DYNAMIC_COLOR -> this
    }

    /** 布尔字段的编辑入口；对非布尔字段是 no-op。 */
    fun withToggle(field: SettingsField, value: Boolean): SettingsDraft = when (field) {
        SettingsField.WIFI_ONLY -> copy(wifiOnly = value)
        SettingsField.CHARGING_ONLY -> copy(chargingOnly = value)
        SettingsField.THERMAL_THROTTLE -> copy(thermalThrottle = value)
        SettingsField.NOTIFICATIONS_ENABLED -> copy(notificationsEnabled = value)
        SettingsField.DYNAMIC_COLOR -> copy(dynamicColor = value)
        else -> this
    }

    /**
     * 解析草稿：无法解析的字段会记入 [DraftParse.formatErrors] 并回退到 [baseline] 的值，
     * 其余字段原样保留（即使越界），交给 [SettingsValidator] 报告范围错误。
     */
    fun parse(baseline: AppSettings): DraftParse {
        val formatErrors = linkedMapOf<String, FieldFormatError>()
        val parsed = AppSettings(
            llmBaseUrl = llmBaseUrl.trim(),
            llmModel = llmModel.trim(),
            llmConcurrency = intValue(
                SettingsField.LLM_CONCURRENCY,
                llmConcurrency,
                baseline.llmConcurrency,
                formatErrors,
            ),
            fishBaseUrl = fishBaseUrl.trim(),
            fishTtsModel = fishTtsModel.trim(),
            ttsConcurrency = intValue(
                SettingsField.TTS_CONCURRENCY,
                ttsConcurrency,
                baseline.ttsConcurrency,
                formatErrors,
            ),
            segmentMaxChars = intValue(
                SettingsField.SEGMENT_MAX_CHARS,
                segmentMaxChars,
                baseline.segmentMaxChars,
                formatErrors,
            ),
            narratorSilenceMs = intValue(
                SettingsField.NARRATOR_SILENCE_MS,
                narratorSilenceMs,
                baseline.narratorSilenceMs,
                formatErrors,
            ),
            paragraphSilenceMs = intValue(
                SettingsField.PARAGRAPH_SILENCE_MS,
                paragraphSilenceMs,
                baseline.paragraphSilenceMs,
                formatErrors,
            ),
            defaultVoiceCategory = defaultVoiceCategory.trim(),
            defaultSpeed = doubleValue(
                SettingsField.DEFAULT_SPEED,
                defaultSpeed,
                baseline.defaultSpeed,
                formatErrors,
            ),
            defaultVolume = doubleValue(
                SettingsField.DEFAULT_VOLUME,
                defaultVolume,
                baseline.defaultVolume,
                formatErrors,
            ),
            defaultPitch = doubleValue(
                SettingsField.DEFAULT_PITCH,
                defaultPitch,
                baseline.defaultPitch,
                formatErrors,
            ),
            minCoverage = doubleValue(
                SettingsField.MIN_COVERAGE,
                minCoverage,
                baseline.minCoverage,
                formatErrors,
            ),
            excludedCharacters = baseline.excludedCharacters,
            batchSize = intValue(
                SettingsField.BATCH_SIZE,
                batchSize,
                baseline.batchSize,
                formatErrors,
            ),
            wifiOnly = wifiOnly,
            chargingOnly = chargingOnly,
            batteryPauseThreshold = intValue(
                SettingsField.BATTERY_PAUSE_THRESHOLD,
                batteryPauseThreshold,
                baseline.batteryPauseThreshold,
                formatErrors,
            ),
            thermalThrottle = thermalThrottle,
            themeMode = themeMode.trim(),
            dynamicColor = dynamicColor,
            keepSegmentCacheDays = intValue(
                SettingsField.KEEP_SEGMENT_CACHE_DAYS,
                keepSegmentCacheDays,
                baseline.keepSegmentCacheDays,
                formatErrors,
            ),
            notificationsEnabled = notificationsEnabled,
            onboardingCompleted = baseline.onboardingCompleted,
        )
        return DraftParse(settings = parsed, formatErrors = formatErrors)
    }

    /** 合并为可提交的 [AppSettings]；解析失败字段使用 [baseline] 的值。 */
    fun toSettings(baseline: AppSettings): AppSettings = parse(baseline).settings

    companion object {
        /** §7.4 默认设置对应的草稿。 */
        val DEFAULT: SettingsDraft = from(AppSettings.DEFAULT)

        /** 从持久化设置生成草稿（保存后也用它把输入框规范化为持久值）。 */
        fun from(settings: AppSettings): SettingsDraft = SettingsDraft(
            llmBaseUrl = settings.llmBaseUrl,
            llmModel = settings.llmModel,
            fishBaseUrl = settings.fishBaseUrl,
            fishTtsModel = settings.fishTtsModel,
            llmConcurrency = settings.llmConcurrency.toString(),
            ttsConcurrency = settings.ttsConcurrency.toString(),
            segmentMaxChars = settings.segmentMaxChars.toString(),
            minCoverage = settings.minCoverage.toString(),
            batchSize = settings.batchSize.toString(),
            narratorSilenceMs = settings.narratorSilenceMs.toString(),
            paragraphSilenceMs = settings.paragraphSilenceMs.toString(),
            defaultVoiceCategory = settings.defaultVoiceCategory,
            defaultSpeed = settings.defaultSpeed.toString(),
            defaultVolume = settings.defaultVolume.toString(),
            defaultPitch = settings.defaultPitch.toString(),
            wifiOnly = settings.wifiOnly,
            chargingOnly = settings.chargingOnly,
            batteryPauseThreshold = settings.batteryPauseThreshold.toString(),
            thermalThrottle = settings.thermalThrottle,
            notificationsEnabled = settings.notificationsEnabled,
            themeMode = settings.themeMode,
            dynamicColor = settings.dynamicColor,
            keepSegmentCacheDays = settings.keepSegmentCacheDays.toString(),
        )
    }
}

/** 草稿解析结果：[formatErrors] 的 key 是 [SettingsField.validatorKey]。 */
data class DraftParse(
    val settings: AppSettings,
    val formatErrors: Map<String, FieldFormatError>,
)

/**
 * 设置页的不可变 UI 状态。
 *
 * [errors] / [formatErrors] / [isDirty] / [canSave] 都是派生属性，避免 UI 与
 * 状态机各自维护一份校验结果而出现“按钮可点但保存失败”。
 */
data class SettingsUiState(
    val loading: Boolean = false,
    val settings: AppSettings = AppSettings.DEFAULT,
    val draft: SettingsDraft = SettingsDraft.DEFAULT,
    val keys: SecureKeysUi = SecureKeysUi(),
    val saving: Boolean = false,
    val keyBusy: Boolean = false,
    val saved: Boolean = false,
    val loadError: String? = null,
    val saveError: String? = null,
    val keyError: String? = null,
) {

    /** 草稿文本与持久化设置有任何差异（包括半截输入）。 */
    val isDirty: Boolean
        get() = draft != SettingsDraft.from(settings)

    /** 解析失败字段；key 是 [SettingsField.validatorKey]。 */
    val formatErrors: Map<String, FieldFormatError>
        get() = draft.parse(settings).formatErrors

    /**
     * 范围/URL/空值错误，来自 [SettingsValidator]（中文文案，core 既有契约）。
     *
     * 解析失败的字段不重复报范围错误，否则空输入会同时出现“请输入整数”和“范围错误”。
     */
    val errors: Map<String, String>
        get() {
            val parsed = draft.parse(settings)
            return SettingsValidator.validate(parsed.settings).errors
                .filterKeys { field -> field !in parsed.formatErrors }
        }

    val hasErrors: Boolean
        get() = errors.isNotEmpty() || formatErrors.isNotEmpty()

    /** 仅当有改动、无错误且不在保存中时允许保存；首次进入页面时保存按钮禁用。 */
    val canSave: Boolean
        get() = !loading && loadError == null && !saving && isDirty && !hasErrors

    fun formatError(field: SettingsField): FieldFormatError? = formatErrors[field.validatorKey]

    fun error(field: SettingsField): String? = errors[field.validatorKey]

    companion object {
        /** 加载完成、无改动的状态；Preview 与测试用它构造初始值。 */
        fun from(
            settings: AppSettings = AppSettings.DEFAULT,
            keys: SecureKeysUi = SecureKeysUi(),
        ): SettingsUiState = SettingsUiState(
            loading = false,
            settings = settings,
            draft = SettingsDraft.from(settings),
            keys = keys,
        )
    }
}

// ---------------------------------------------------------------------------
// 状态迁移：纯函数，ViewModel 只负责调度，便于 JVM 测试保存语义。
// ---------------------------------------------------------------------------

/**
 * 合并持久化设置。
 *
 * - 用户没有未保存修改时（首次加载/刚保存完）：采用新设置并重置草稿；
 * - 用户正在编辑时：保留草稿，只把 [SettingsUiState.settings] 换成新基线，
 *   避免外部/后台写入清掉用户输入。
 */
fun SettingsUiState.withSettings(settings: AppSettings): SettingsUiState {
    val keepDraft = isDirty
    return copy(
        loading = false,
        loadError = null,
        settings = settings,
        draft = if (keepDraft) draft else SettingsDraft.from(settings),
    )
}

/** 用户编辑：清掉“已保存”提示与上次保存错误。 */
fun SettingsUiState.withDraft(next: SettingsDraft): SettingsUiState = copy(
    draft = next,
    saved = false,
    saveError = null,
)

/** 开始保存：校验已由 [SettingsUiState.canSave] 保证。 */
fun SettingsUiState.markSaving(): SettingsUiState = copy(
    saving = true,
    saved = false,
    saveError = null,
)

/** 保存成功：以持久化值为新基线，输入框规范化为持久值。 */
fun SettingsUiState.markSaved(savedSettings: AppSettings): SettingsUiState = copy(
    settings = savedSettings,
    draft = SettingsDraft.from(savedSettings),
    saving = false,
    saved = true,
    saveError = null,
)

/** 保存失败（仓库校验/IO）：保留草稿，让用户修正后重试。 */
fun SettingsUiState.markSaveFailed(message: String): SettingsUiState = copy(
    saving = false,
    saved = false,
    saveError = message,
)

/** 数据流读取失败：保留已有草稿，只标记错误。 */
fun SettingsUiState.withLoadError(message: String): SettingsUiState = copy(
    loading = false,
    loadError = message,
)

/** Key 读写开始/结束。 */
fun SettingsUiState.withKeyBusy(busy: Boolean): SettingsUiState = copy(
    keyBusy = busy,
    keyError = if (busy) null else keyError,
)

fun SettingsUiState.withKeys(nextKeys: SecureKeysUi): SettingsUiState = copy(
    keys = nextKeys,
    keyBusy = false,
    keyError = null,
)

fun SettingsUiState.withKeyError(message: String): SettingsUiState = copy(
    keyBusy = false,
    keyError = message,
)

// ---------------------------------------------------------------------------
// 解析辅助
// ---------------------------------------------------------------------------

private fun intValue(
    field: SettingsField,
    raw: String,
    fallback: Int,
    errors: MutableMap<String, FieldFormatError>,
): Int {
    val text = raw.trim()
    if (text.isEmpty()) {
        errors[field.validatorKey] = FieldFormatError.Missing
        return fallback
    }
    val parsed = text.toIntOrNull()
    if (parsed == null) {
        errors[field.validatorKey] = FieldFormatError.NotAnInteger
        return fallback
    }
    return parsed
}

private fun doubleValue(
    field: SettingsField,
    raw: String,
    fallback: Double,
    errors: MutableMap<String, FieldFormatError>,
): Double {
    val text = raw.trim()
    if (text.isEmpty()) {
        errors[field.validatorKey] = FieldFormatError.Missing
        return fallback
    }
    val parsed = text.toDoubleOrNull()
    if (parsed == null) {
        errors[field.validatorKey] = FieldFormatError.NotANumber
        return fallback
    }
    return parsed
}
