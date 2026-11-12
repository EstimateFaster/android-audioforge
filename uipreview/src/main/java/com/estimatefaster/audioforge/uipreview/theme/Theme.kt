package com.estimatefaster.audioforge.uipreview.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.estimatefaster.audioforge.uipreview.lab.LocalPreviewSettings
import com.estimatefaster.audioforge.uipreview.lab.ThemeMode

/**
 * 预览主题。
 *
 * - 颜色：动态取色优先（Android 12+），关闭后回退 [PaperLight] / [GraphiteDark]；
 * - 形状：[ExpressiveShapes]；
 * - 动效：[MotionScheme.expressive]；
 * - 字号：通过 LocalDensity 的 fontScale 叠加实验室档位，只放大文字。
 */
@Composable
fun UiPreviewTheme(content: @Composable () -> Unit) {
    val settings = LocalPreviewSettings.current
    val dark = when (settings.themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val context = LocalContext.current
    val scheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> GraphiteDark
        else -> PaperLight
    }

    val base = LocalDensity.current
    val scaledDensity = Density(base.density, base.fontScale * settings.fontScale.scale)

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            shapes = ExpressiveShapes,
            content = content,
        )
    }
}
