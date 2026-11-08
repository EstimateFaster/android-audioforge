package com.estimatefaster.audioforge.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-09：§7.4 默认值、每个范围边界/非法值、URL/model/theme、effectiveVoiceParams 优先级。 */
class SettingsValidatorTest {

    private val intRanges: Map<String, IntRange> = linkedMapOf(
        "llmConcurrency" to 1..4,
        "ttsConcurrency" to 1..8,
        "segmentMaxChars" to 20..300,
        "narratorSilenceMs" to 0..3000,
        "paragraphSilenceMs" to 0..5000,
        "batchSize" to 1..20,
        "batteryPauseThreshold" to 0..50,
    )

    private val doubleRanges: Map<String, ClosedFloatingPointRange<Double>> = linkedMapOf(
        "defaultSpeed" to 0.5..2.0,
        "defaultVolume" to 0.5..2.0,
        "defaultPitch" to -12.0..12.0,
        "minCoverage" to 0.5..1.0,
    )

    @Test
    fun validation_result_flags_any_error() {
        assertTrue(ValidationResult(emptyMap()).isValid)
        assertTrue(ValidationResult.VALID.isValid)
        val result = ValidationResult(mapOf("batchSize" to "非法"))
        assertFalse(result.isValid)
        assertEquals("非法", result.errors["batchSize"])
    }

    @Test
    fun default_settings_match_the_plan_table() {
        val settings = AppSettings()
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", settings.llmBaseUrl)
        assertEquals("qwen3.7-flash", settings.llmModel)
        assertEquals(1, settings.llmConcurrency)
        assertEquals("https://api.fish.audio", settings.fishBaseUrl)
        assertEquals("s2.1-pro-free", settings.fishTtsModel)
        assertEquals(4, settings.ttsConcurrency)
        assertEquals(120, settings.segmentMaxChars)
        assertEquals(350, settings.narratorSilenceMs)
        assertEquals(700, settings.paragraphSilenceMs)
        assertEquals("narrator_male", settings.defaultVoiceCategory)
        assertEquals(1.0, settings.defaultSpeed, 1e-9)
        assertEquals(1.0, settings.defaultVolume, 1e-9)
        assertEquals(0.0, settings.defaultPitch, 1e-9)
        assertEquals(0.97, settings.minCoverage, 1e-9)
        assertEquals("", settings.excludedCharacters)
        assertEquals(5, settings.batchSize)
        assertTrue(settings.wifiOnly)
        assertFalse(settings.chargingOnly)
        assertEquals(15, settings.batteryPauseThreshold)
        assertTrue(settings.thermalThrottle)
        assertEquals("system", settings.themeMode)
        assertEquals(ThemeMode.System, settings.theme)
        assertTrue(settings.dynamicColor)
        assertEquals(7, settings.keepSegmentCacheDays)
        assertTrue(settings.notificationsEnabled)
        assertFalse(settings.onboardingCompleted)
        assertEquals(settings, AppSettings.DEFAULT)
    }

    @Test
    fun default_settings_are_valid() {
        val result = SettingsValidator.validate(AppSettings())
        assertTrue(result.errors.toString(), result.isValid)
        assertEquals(emptyMap<String, String>(), result.errors)
    }

    @Test
    fun int_fields_accept_inclusive_boundaries() {
        intRanges.forEach { (field, range) ->
            listOf(range.first, range.last).forEach { value ->
                val result = SettingsValidator.validate(withInt(field, value))
                assertTrue("$field=$value 应合法，实际错误 ${result.errors}", result.isValid)
            }
        }
    }

    @Test
    fun int_fields_reject_out_of_range_values() {
        intRanges.forEach { (field, range) ->
            listOf(range.first - 1, range.last + 1).forEach { value ->
                assertOnlyError(field, withInt(field, value))
            }
        }
    }

    @Test
    fun keep_segment_cache_days_allows_zero_and_rejects_negative() {
        assertTrue(SettingsValidator.validate(AppSettings().copy(keepSegmentCacheDays = 0)).isValid)
        assertTrue(SettingsValidator.validate(AppSettings().copy(keepSegmentCacheDays = 7)).isValid)
        assertTrue(SettingsValidator.validate(AppSettings().copy(keepSegmentCacheDays = Int.MAX_VALUE)).isValid)
        assertOnlyError("keepSegmentCacheDays", AppSettings().copy(keepSegmentCacheDays = -1))
    }

    @Test
    fun double_fields_accept_inclusive_boundaries() {
        doubleRanges.forEach { (field, range) ->
            listOf(range.start, range.endInclusive).forEach { value ->
                val result = SettingsValidator.validate(withDouble(field, value))
                assertTrue("$field=$value 应合法，实际错误 ${result.errors}", result.isValid)
            }
        }
    }

    @Test
    fun double_fields_reject_out_of_range_values() {
        doubleRanges.forEach { (field, range) ->
            listOf(range.start - 0.01, range.endInclusive + 0.01).forEach { value ->
                assertOnlyError(field, withDouble(field, value))
            }
        }
    }

    @Test
    fun non_finite_doubles_are_rejected() {
        doubleRanges.keys.forEach { field ->
            listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { value ->
                assertOnlyError(field, withDouble(field, value))
            }
        }
    }

    @Test
    fun urls_accept_http_and_https() {
        assertTrue(SettingsValidator.validate(AppSettings().copy(llmBaseUrl = "http://127.0.0.1:8080/v1")).isValid)
        assertTrue(SettingsValidator.validate(AppSettings().copy(fishBaseUrl = "https://api.fish.audio/v1")).isValid)
        assertTrue(SettingsValidator.validate(AppSettings().copy(fishBaseUrl = "http://api.fish.audio")).isValid)
    }

    @Test
    fun invalid_urls_are_rejected_per_field() {
        val invalid = listOf(
            "",
            "   ",
            "api.fish.audio",
            "ftp://api.fish.audio",
            "https://",
            "not a url",
        )
        invalid.forEach { value ->
            assertOnlyError("llmBaseUrl", AppSettings().copy(llmBaseUrl = value))
            assertOnlyError("fishBaseUrl", AppSettings().copy(fishBaseUrl = value))
        }
    }

    @Test
    fun models_must_not_be_blank() {
        assertOnlyError("llmModel", AppSettings().copy(llmModel = ""))
        assertOnlyError("llmModel", AppSettings().copy(llmModel = "   "))
        assertOnlyError("fishTtsModel", AppSettings().copy(fishTtsModel = ""))
        assertOnlyError("fishTtsModel", AppSettings().copy(fishTtsModel = "\t"))
    }

    @Test
    fun voice_category_uses_the_callback() {
        val known = setOf("narrator_male", "female_young")
        // 不传回调时默认放行，P1 不依赖 VoicePool
        assertTrue(SettingsValidator.validate(AppSettings()).isValid)
        assertTrue(
            SettingsValidator.validate(
                AppSettings().copy(defaultVoiceCategory = "female_young"),
            ) { it in known }.isValid,
        )
        assertOnlyError(
            "defaultVoiceCategory",
            AppSettings().copy(defaultVoiceCategory = "nope"),
        ) { it in known }
        // 空类别即使回调全部放行也应报错
        assertOnlyError("defaultVoiceCategory", AppSettings().copy(defaultVoiceCategory = "  "))
    }

    @Test
    fun theme_mode_must_be_a_known_wire_value() {
        listOf("system", "light", "dark").forEach { wire ->
            assertTrue("$wire 应合法", SettingsValidator.validate(AppSettings().copy(themeMode = wire)).isValid)
        }
        listOf("", "auto", "sepia", "System", "DARK").forEach { wire ->
            assertOnlyError("themeMode", AppSettings().copy(themeMode = wire))
        }
        assertEquals(ThemeMode.Light, ThemeMode.fromWire("light"))
        assertEquals(ThemeMode.Dark, ThemeMode.fromWire("dark"))
        assertEquals(ThemeMode.System, ThemeMode.fromWire("nope"))
        assertFalse(ThemeMode.isValid("nope"))
        assertEquals(ThemeMode.Dark, AppSettings(themeMode = "dark").theme)
    }

    @Test
    fun all_errors_accumulate_with_field_name_keys() {
        val bad = AppSettings(
            llmBaseUrl = "ftp://llm.example.com",
            llmModel = " ",
            llmConcurrency = 5,
            fishBaseUrl = "api.fish.audio",
            fishTtsModel = "",
            ttsConcurrency = 0,
            segmentMaxChars = 19,
            narratorSilenceMs = 3001,
            paragraphSilenceMs = -1,
            defaultVoiceCategory = "",
            defaultSpeed = 0.49,
            defaultVolume = 2.01,
            defaultPitch = -12.01,
            minCoverage = 1.01,
            batchSize = 21,
            batteryPauseThreshold = 51,
            themeMode = "auto",
            keepSegmentCacheDays = -1,
        )
        val result = SettingsValidator.validate(bad)
        assertFalse(result.isValid)
        assertEquals(
            setOf(
                "llmBaseUrl",
                "llmModel",
                "llmConcurrency",
                "fishBaseUrl",
                "fishTtsModel",
                "ttsConcurrency",
                "segmentMaxChars",
                "narratorSilenceMs",
                "paragraphSilenceMs",
                "defaultVoiceCategory",
                "defaultSpeed",
                "defaultVolume",
                "defaultPitch",
                "minCoverage",
                "batchSize",
                "batteryPauseThreshold",
                "themeMode",
                "keepSegmentCacheDays",
            ),
            result.errors.keys,
        )
        assertTrue(result.errors.values.all { it.isNotBlank() })
    }

    @Test
    fun effective_voice_params_use_global_defaults() {
        val params = SettingsValidator.effectiveVoiceParams(AppSettings())
        assertEquals(VoiceParams(1.0, 1.0, 0.0), params)
    }

    @Test
    fun category_speed_beats_global_but_loses_to_character_tuning() {
        val settings = AppSettings(defaultSpeed = 1.0, defaultVolume = 0.8, defaultPitch = 2.0)

        val categoryOnly = SettingsValidator.effectiveVoiceParams(settings, categorySpeed = 1.15)
        assertEquals(1.15, categoryOnly.speed, 1e-9)
        assertEquals(0.8, categoryOnly.volume, 1e-9)
        assertEquals(2.0, categoryOnly.pitch, 1e-9)

        val tuned = SettingsValidator.effectiveVoiceParams(
            settings,
            characterTuning = mapOf("speed" to "1.3", "volume" to "1.1", "pitch" to "-3"),
            categorySpeed = 1.15,
        )
        assertEquals(1.3, tuned.speed, 1e-9)
        assertEquals(1.1, tuned.volume, 1e-9)
        assertEquals(-3.0, tuned.pitch, 1e-9)
    }

    @Test
    fun invalid_tuning_falls_back_to_next_priority() {
        val settings = AppSettings(defaultSpeed = 0.9, defaultVolume = 0.7, defaultPitch = 1.0)

        listOf("abc", "", "  ", "NaN", "Infinity", "-Infinity", "0.1", "3.0").forEach { rawSpeed ->
            val params = SettingsValidator.effectiveVoiceParams(
                settings,
                characterTuning = mapOf("speed" to rawSpeed),
                categorySpeed = 1.15,
            )
            assertEquals("speed tuning=$rawSpeed 应回退到类别语速", 1.15, params.speed, 1e-9)
        }

        listOf("abc", "-1", "0.1", "3.0").forEach { rawVolume ->
            val params = SettingsValidator.effectiveVoiceParams(settings, mapOf("volume" to rawVolume))
            assertEquals("volume tuning=$rawVolume 应回退到全局默认", 0.7, params.volume, 1e-9)
        }

        listOf("abc", "-13", "13").forEach { rawPitch ->
            val params = SettingsValidator.effectiveVoiceParams(settings, mapOf("pitch" to rawPitch))
            assertEquals("pitch tuning=$rawPitch 应回退到全局默认", 1.0, params.pitch, 1e-9)
        }
    }

    @Test
    fun invalid_tuning_without_category_speed_falls_back_to_global() {
        val settings = AppSettings(defaultSpeed = 0.9)
        val params = SettingsValidator.effectiveVoiceParams(
            settings,
            characterTuning = mapOf("speed" to "9.0"),
        )
        assertEquals(0.9, params.speed, 1e-9)

        val nanCategory = SettingsValidator.effectiveVoiceParams(settings, categorySpeed = Double.NaN)
        assertEquals(0.9, nanCategory.speed, 1e-9)
    }

    @Test
    fun effective_voice_params_are_clamped_to_legal_ranges() {
        // 调用方可能在 validate 之前使用非法设置，也不能产出越界参数
        val params = SettingsValidator.effectiveVoiceParams(
            AppSettings(defaultSpeed = 5.0, defaultVolume = 0.1, defaultPitch = 99.0),
            categorySpeed = 4.0,
        )
        assertEquals(2.0, params.speed, 1e-9)
        assertEquals(0.5, params.volume, 1e-9)
        assertEquals(12.0, params.pitch, 1e-9)

        val low = SettingsValidator.effectiveVoiceParams(
            AppSettings(defaultSpeed = 0.1, defaultVolume = 5.0, defaultPitch = -99.0),
        )
        assertEquals(0.5, low.speed, 1e-9)
        assertEquals(2.0, low.volume, 1e-9)
        assertEquals(-12.0, low.pitch, 1e-9)
    }

    @Test
    fun non_finite_defaults_fall_back_to_safe_values() {
        val params = SettingsValidator.effectiveVoiceParams(
            AppSettings(
                defaultSpeed = Double.NaN,
                defaultVolume = Double.POSITIVE_INFINITY,
                defaultPitch = Double.NEGATIVE_INFINITY,
            ),
        )
        assertEquals(VoiceParams(1.0, 1.0, 0.0), params)
    }

    private fun assertOnlyError(
        field: String,
        settings: AppSettings,
        isValidVoiceCategory: (String) -> Boolean = { true },
    ) {
        val result = SettingsValidator.validate(settings, isValidVoiceCategory)
        assertFalse("$field 应报错", result.isValid)
        assertEquals("$field 错误 key 不对：${result.errors}", setOf(field), result.errors.keys)
    }

    private fun withInt(field: String, value: Int): AppSettings = when (field) {
        "llmConcurrency" -> AppSettings().copy(llmConcurrency = value)
        "ttsConcurrency" -> AppSettings().copy(ttsConcurrency = value)
        "segmentMaxChars" -> AppSettings().copy(segmentMaxChars = value)
        "narratorSilenceMs" -> AppSettings().copy(narratorSilenceMs = value)
        "paragraphSilenceMs" -> AppSettings().copy(paragraphSilenceMs = value)
        "batchSize" -> AppSettings().copy(batchSize = value)
        "batteryPauseThreshold" -> AppSettings().copy(batteryPauseThreshold = value)
        else -> throw IllegalArgumentException("未知 Int 字段 $field")
    }

    private fun withDouble(field: String, value: Double): AppSettings = when (field) {
        "defaultSpeed" -> AppSettings().copy(defaultSpeed = value)
        "defaultVolume" -> AppSettings().copy(defaultVolume = value)
        "defaultPitch" -> AppSettings().copy(defaultPitch = value)
        "minCoverage" -> AppSettings().copy(minCoverage = value)
        else -> throw IllegalArgumentException("未知 Double 字段 $field")
    }
}
