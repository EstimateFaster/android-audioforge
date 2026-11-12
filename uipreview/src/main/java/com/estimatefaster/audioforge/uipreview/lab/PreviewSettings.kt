package com.estimatefaster.audioforge.uipreview.lab

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 主题模式：跟随系统 / 强制浅色 / 强制深色。 */
enum class ThemeMode { System, Light, Dark }

/** 字号预览档位：只放大 sp，不缩放 dp 与图标。 */
enum class FontScaleOption(val scale: Float) { S100(1f), S130(1.3f), S200(2f) }

/** 页面级状态覆盖，用于逐个屏幕验收三态。 */
enum class ScreenPhase { Normal, Loading, Empty, Error }

/**
 * 设计实验室状态。
 *
 * 这是纯 UI 预览构建的「验收集」：主题、动态取色、字号、规范标注与逐页状态。
 * 不参与任何业务逻辑。
 */
@Stable
class PreviewSettings {
    var themeMode by mutableStateOf(ThemeMode.System)
    var dynamicColor by mutableStateOf(true)
    var fontScale by mutableStateOf(FontScaleOption.S100)
    var showSpecs by mutableStateOf(false)

    private val phases = mutableStateMapOf<String, ScreenPhase>()

    fun phase(key: String): ScreenPhase = phases[key] ?: ScreenPhase.Normal

    fun setPhase(key: String, phase: ScreenPhase) {
        if (phase == ScreenPhase.Normal) phases.remove(key) else phases[key] = phase
    }

    fun reset() {
        themeMode = ThemeMode.System
        dynamicColor = true
        fontScale = FontScaleOption.S100
        showSpecs = false
        phases.clear()
    }
}

val LocalPreviewSettings = compositionLocalOf { PreviewSettings() }
