package com.estimatefaster.audioforge.core.settings

/**
 * 主题模式。`wire` 是 DataStore / 设置接口的稳定值（执行计划 §7.4），不能翻译。
 */
enum class ThemeMode(val wire: String) {
    System("system"),
    Light("light"),
    Dark("dark"),
    ;

    companion object {
        /** 未知值回退 [ThemeMode.System]；保存前应先跑 [SettingsValidator]。 */
        fun fromWire(value: String): ThemeMode = entries.firstOrNull { it.wire == value } ?: ThemeMode.System

        fun isValid(value: String): Boolean = entries.any { it.wire == value }
    }
}

/**
 * 应用设置。Key / 默认值 / 范围来自执行计划 §7.4（DataStore 前缀见 P2-03）。
 *
 * 默认值与 Web 版一致；唯一差异是 Android 允许的并发上限（LLM 1–4、TTS 1–8）。
 *
 * `themeMode` 有意保留 wire 字符串而不是 [ThemeMode]：从 DataStore 读到的脏值要能被
 * [SettingsValidator] 报成字段错误，而不是在读设置时就静默纠正。
 */
data class AppSettings(
    // LLM
    val llmBaseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    val llmModel: String = "qwen3.7-flash",
    val llmConcurrency: Int = 1,

    // Fish Audio TTS
    val fishBaseUrl: String = "https://api.fish.audio",
    val fishTtsModel: String = "s2.1-pro-free",
    val ttsConcurrency: Int = 4,

    // 脚本切分与拼接
    val segmentMaxChars: Int = 120,
    val narratorSilenceMs: Int = 350,
    val paragraphSilenceMs: Int = 700,

    // 默认音色与参数（角色 tuning 优先于这些默认值）
    val defaultVoiceCategory: String = "narrator_male",
    val defaultSpeed: Double = 1.0,
    val defaultVolume: Double = 1.0,
    val defaultPitch: Double = 0.0,

    // 覆盖率与排除字符（空格分隔）
    val minCoverage: Double = 0.97,
    val excludedCharacters: String = "",

    // 批处理与电源策略
    val batchSize: Int = 5,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val batteryPauseThreshold: Int = 15,
    val thermalThrottle: Boolean = true,

    // 外观
    val themeMode: String = ThemeMode.System.wire,
    val dynamicColor: Boolean = true,

    // 缓存与通知
    val keepSegmentCacheDays: Int = 7,
    val notificationsEnabled: Boolean = true,
    val onboardingCompleted: Boolean = false,
) {

    /** 强类型主题；脏 wire 回退 [ThemeMode.System]，需要字段错误时用 [SettingsValidator]。 */
    val theme: ThemeMode get() = ThemeMode.fromWire(themeMode)

    companion object {
        /** §7.4 的默认设置。 */
        val DEFAULT: AppSettings = AppSettings()
    }
}
