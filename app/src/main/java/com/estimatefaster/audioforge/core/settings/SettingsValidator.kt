package com.estimatefaster.audioforge.core.settings

import java.net.URI

/** 字段级校验结果；[errors] 的 key 是 [AppSettings] 的字段名。 */
data class ValidationResult(val errors: Map<String, String>) {

    val isValid: Boolean get() = errors.isEmpty()

    companion object {
        val VALID: ValidationResult = ValidationResult(emptyMap())
    }
}

/** 角色 tuning 生效后的 TTS 参数；保证落在 §7.4 的合法范围内。 */
data class VoiceParams(val speed: Double, val volume: Double, val pitch: Double)

/**
 * 设置校验与音色参数求值（执行计划 §7.4 / P1-09）。
 *
 * 只做纯 JVM 计算，不依赖 Android，便于单元测试与 UI 即时校验共用。
 */
object SettingsValidator {

    private const val LLM_CONCURRENCY_MIN = 1
    private const val LLM_CONCURRENCY_MAX = 4
    private const val TTS_CONCURRENCY_MIN = 1
    private const val TTS_CONCURRENCY_MAX = 8
    private const val SEGMENT_MAX_CHARS_MIN = 20
    private const val SEGMENT_MAX_CHARS_MAX = 300
    private const val NARRATOR_SILENCE_MIN = 0
    private const val NARRATOR_SILENCE_MAX = 3_000
    private const val PARAGRAPH_SILENCE_MIN = 0
    private const val PARAGRAPH_SILENCE_MAX = 5_000
    private const val BATCH_SIZE_MIN = 1
    private const val BATCH_SIZE_MAX = 20
    private const val BATTERY_THRESHOLD_MIN = 0
    private const val BATTERY_THRESHOLD_MAX = 50

    private val SPEED_RANGE = 0.5..2.0
    private val VOLUME_RANGE = 0.5..2.0
    private val PITCH_RANGE = -12.0..12.0
    private val COVERAGE_RANGE = 0.5..1.0

    private const val DEFAULT_SPEED = 1.0
    private const val DEFAULT_VOLUME = 1.0
    private const val DEFAULT_PITCH = 0.0

    private const val TUNING_SPEED = "speed"
    private const val TUNING_VOLUME = "volume"
    private const val TUNING_PITCH = "pitch"

    /**
     * 校验全部范围；多项非法时一次性返回所有字段错误。
     *
     * @param isValidVoiceCategory 音色类别校验；P1-09 不依赖 VoicePool，默认放行，
     *   P2-03 保存前传入 `VoicePool::valid`。
     */
    fun validate(
        settings: AppSettings,
        isValidVoiceCategory: (String) -> Boolean = { true },
    ): ValidationResult {
        val errors = linkedMapOf<String, String>()

        // LLM
        validateUrl("llmBaseUrl", settings.llmBaseUrl, errors)
        if (settings.llmModel.isBlank()) {
            errors["llmModel"] = "LLM 模型不能为空"
        }
        requireInt(
            errors,
            "llmConcurrency",
            settings.llmConcurrency,
            LLM_CONCURRENCY_MIN,
            LLM_CONCURRENCY_MAX,
            "LLM 并发数需在 1–4 之间",
        )

        // Fish Audio
        validateUrl("fishBaseUrl", settings.fishBaseUrl, errors)
        if (settings.fishTtsModel.isBlank()) {
            errors["fishTtsModel"] = "TTS 模型不能为空"
        }
        requireInt(
            errors,
            "ttsConcurrency",
            settings.ttsConcurrency,
            TTS_CONCURRENCY_MIN,
            TTS_CONCURRENCY_MAX,
            "TTS 并发数需在 1–8 之间",
        )

        // 切分与静音
        requireInt(
            errors,
            "segmentMaxChars",
            settings.segmentMaxChars,
            SEGMENT_MAX_CHARS_MIN,
            SEGMENT_MAX_CHARS_MAX,
            "分段字符上限需在 20–300 之间",
        )
        requireInt(
            errors,
            "narratorSilenceMs",
            settings.narratorSilenceMs,
            NARRATOR_SILENCE_MIN,
            NARRATOR_SILENCE_MAX,
            "旁白静音需在 0–3000 毫秒之间",
        )
        requireInt(
            errors,
            "paragraphSilenceMs",
            settings.paragraphSilenceMs,
            PARAGRAPH_SILENCE_MIN,
            PARAGRAPH_SILENCE_MAX,
            "段落静音需在 0–5000 毫秒之间",
        )

        // 默认音色
        val category = settings.defaultVoiceCategory
        when {
            category.isBlank() -> errors["defaultVoiceCategory"] = "默认音色类别不能为空"
            !isValidVoiceCategory(category) -> errors["defaultVoiceCategory"] = "默认音色类别不合法"
        }
        requireDouble(
            errors,
            "defaultSpeed",
            settings.defaultSpeed,
            SPEED_RANGE,
            "语速需在 0.5–2.0 之间",
        )
        requireDouble(
            errors,
            "defaultVolume",
            settings.defaultVolume,
            VOLUME_RANGE,
            "音量需在 0.5–2.0 之间",
        )
        requireDouble(
            errors,
            "defaultPitch",
            settings.defaultPitch,
            PITCH_RANGE,
            "音调需在 -12–12 之间",
        )

        // 覆盖率
        requireDouble(
            errors,
            "minCoverage",
            settings.minCoverage,
            COVERAGE_RANGE,
            "最低覆盖率需在 0.5–1.0 之间",
        )

        // 批处理与电源
        requireInt(
            errors,
            "batchSize",
            settings.batchSize,
            BATCH_SIZE_MIN,
            BATCH_SIZE_MAX,
            "批次大小需在 1–20 之间",
        )
        requireInt(
            errors,
            "batteryPauseThreshold",
            settings.batteryPauseThreshold,
            BATTERY_THRESHOLD_MIN,
            BATTERY_THRESHOLD_MAX,
            "低电量阈值需在 0–50 之间",
        )

        // 外观
        if (!ThemeMode.isValid(settings.themeMode)) {
            errors["themeMode"] = "主题必须是 system/light/dark"
        }

        // 缓存
        if (settings.keepSegmentCacheDays < 0) {
            errors["keepSegmentCacheDays"] = "缓存保留天数不能为负"
        }

        return ValidationResult(errors)
    }

    /**
     * 求实际 TTS 参数：角色 tuning > 类别建议语速 > 全局默认。
     *
     * tuning 值来自 `character_tuning` 表（key 为 speed/volume/pitch，value 为字符串）。
     * 解析失败、NaN/Infinity 或越界的 tuning 视为未设置，回退到下一优先级；
     * 最终结果再裁剪到合法范围（调用方可能尚未跑过 [validate]）。
     */
    fun effectiveVoiceParams(
        settings: AppSettings,
        characterTuning: Map<String, String> = emptyMap(),
        categorySpeed: Double? = null,
    ): VoiceParams {
        val speed = tuningValue(characterTuning, TUNING_SPEED, SPEED_RANGE)
            ?: categorySpeed?.takeIf { it.isFinite() }
            ?: settings.defaultSpeed
        val volume = tuningValue(characterTuning, TUNING_VOLUME, VOLUME_RANGE)
            ?: settings.defaultVolume
        val pitch = tuningValue(characterTuning, TUNING_PITCH, PITCH_RANGE)
            ?: settings.defaultPitch

        return VoiceParams(
            speed = speed.clampTo(SPEED_RANGE, DEFAULT_SPEED),
            volume = volume.clampTo(VOLUME_RANGE, DEFAULT_VOLUME),
            pitch = pitch.clampTo(PITCH_RANGE, DEFAULT_PITCH),
        )
    }

    private fun validateUrl(field: String, raw: String, errors: MutableMap<String, String>) {
        val uri = runCatching { URI(raw.trim()) }.getOrNull()
        if (uri == null) {
            errors[field] = "地址必须是合法的 http/https URL"
            return
        }
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) {
            errors[field] = "地址必须是合法的 http/https URL"
        }
    }

    private fun requireInt(
        errors: MutableMap<String, String>,
        field: String,
        value: Int,
        min: Int,
        max: Int,
        message: String,
    ) {
        if (value !in min..max) errors[field] = message
    }

    private fun requireDouble(
        errors: MutableMap<String, String>,
        field: String,
        value: Double,
        range: ClosedFloatingPointRange<Double>,
        message: String,
    ) {
        if (!value.isFinite() || value !in range) errors[field] = message
    }

    private fun tuningValue(
        tuning: Map<String, String>,
        key: String,
        range: ClosedFloatingPointRange<Double>,
    ): Double? = tuning[key]
        ?.trim()
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it in range }

    private fun Double.clampTo(range: ClosedFloatingPointRange<Double>, fallback: Double): Double =
        if (isFinite()) coerceIn(range) else fallback
}
