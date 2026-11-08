package com.estimatefaster.audioforge.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = ForgeIndigo,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFE0E7FF),
    onPrimaryContainer = ForgeIndigoDark,
    secondary = ForgeAmber,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFFEF3C7),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF78350F),
    background = ForgePaper,
    onBackground = ForgeInk,
    surface = androidx.compose.ui.graphics.Color.White,
    onSurface = ForgeInk,
    onSurfaceVariant = ForgeInkSoft,
    outline = ForgeLine,
)

private val DarkColors = darkColorScheme(
    primary = ForgeIndigoLight,
    onPrimary = androidx.compose.ui.graphics.Color(0xFF101223),
    primaryContainer = ForgeIndigoDark,
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFE0E7FF),
    secondary = ForgeAmberLight,
    onSecondary = androidx.compose.ui.graphics.Color(0xFF101223),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF78350F),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFFEF3C7),
    background = androidx.compose.ui.graphics.Color(0xFF0F1220),
    onBackground = androidx.compose.ui.graphics.Color(0xFFE8EAF2),
    surface = androidx.compose.ui.graphics.Color(0xFF161A2B),
    onSurface = androidx.compose.ui.graphics.Color(0xFFE8EAF2),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFAAB2C8),
    outline = androidx.compose.ui.graphics.Color(0xFF2C3348),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AudioForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(LocalContext.current)
            else dynamicLightColorScheme(LocalContext.current)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = Shapes(),
        typography = AudioForgeTypography,
        content = content,
    )
}
