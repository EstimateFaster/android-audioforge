package com.estimatefaster.audioforge.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** 屏幕/组件统一读取间距的唯一入口。 */
val LocalForgeSpacing = staticCompositionLocalOf { ForgeSpacing() }

val MaterialTheme.spacing: ForgeSpacing
    @Composable
    @ReadOnlyComposable
    get() = LocalForgeSpacing.current

/**
 * Material 3 Expressive 主题包装。
 *
 * 对外签名保持不变；内部：
 * - Android 12+ 可按设置启用动态取色，否则回退到 seed 推导的静态 light/dark scheme；
 * - 显式使用 [ForgeMotion.scheme]（MotionScheme.expressive()）；
 * - 注入完整 Shapes/Typography 与项目间距。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AudioForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> ForgeDarkScheme
        else -> ForgeLightScheme
    }

    CompositionLocalProvider(LocalForgeSpacing provides ForgeSpacing()) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = ForgeMotion.scheme,
            shapes = ForgeShapes,
            typography = ForgeTypography,
            content = content,
        )
    }
}
