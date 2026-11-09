package com.estimatefaster.audioforge.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * Material 3 Expressive 静态配色：由单一品牌 seed 推导全部 tonal role。
 *
 * `Color.kt` 是唯一允许出现颜色字面量的文件；feature/组件只能读取
 * `MaterialTheme.colorScheme` 的语义 role。seed 变化会整体平移色相，
 * secondary/tertiary 由 seed 的 analogous 色相派生，中性色使用低饱和同色相。
 */
internal val ForgeSeed = Color(0xFF4F46E5)

/** 品牌强调色（录音/生成完成等少量强调，不作为大面积主色）。 */
internal val ForgeAccent = Color(0xFFD97706)

private class Palette(private val hue: Float) {
    fun at(tone: Int, saturation: Float = 0.52f): Color =
        hsl(hue, saturation.coerceIn(0f, 1f), (tone / 100f).coerceIn(0f, 1f))

    fun neutral(tone: Int): Color = at(tone, saturation = 0.06f)
}

private fun seedPalette(seed: Color): Palette = Palette(hueOf(seed))
private fun secondaryPalette(seed: Color): Palette = Palette((hueOf(seed) + 42f) % 360f)
private fun tertiaryPalette(seed: Color): Palette = Palette((hueOf(seed) + 88f) % 360f)
private val ErrorPalette = Palette(25f)

internal val ForgeLightScheme: ColorScheme = run {
    val primary = seedPalette(ForgeSeed)
    val secondary = secondaryPalette(ForgeSeed)
    val tertiary = tertiaryPalette(ForgeSeed)
    val neutral = primary
    lightColorScheme(
        primary = primary.at(40),
        onPrimary = primary.at(100),
        primaryContainer = primary.at(90),
        onPrimaryContainer = primary.at(10),
        inversePrimary = primary.at(80),
        secondary = secondary.at(40),
        onSecondary = secondary.at(100),
        secondaryContainer = secondary.at(90),
        onSecondaryContainer = secondary.at(10),
        tertiary = tertiary.at(40),
        onTertiary = tertiary.at(100),
        tertiaryContainer = tertiary.at(90),
        onTertiaryContainer = tertiary.at(10),
        background = neutral.neutral(99),
        onBackground = neutral.neutral(10),
        surface = neutral.neutral(99),
        onSurface = neutral.neutral(10),
        surfaceVariant = neutral.neutral(90),
        onSurfaceVariant = neutral.neutral(30),
        surfaceTint = primary.at(40),
        inverseSurface = neutral.neutral(20),
        inverseOnSurface = neutral.neutral(95),
        error = ErrorPalette.at(40, 0.72f),
        onError = ErrorPalette.at(100, 0.72f),
        errorContainer = ErrorPalette.at(90, 0.72f),
        onErrorContainer = ErrorPalette.at(10, 0.72f),
        outline = neutral.neutral(50),
        outlineVariant = neutral.neutral(80),
        scrim = Color.Black,
        surfaceBright = neutral.neutral(99),
        surfaceDim = neutral.neutral(87),
        surfaceContainer = neutral.neutral(94),
        surfaceContainerHigh = neutral.neutral(92),
        surfaceContainerHighest = neutral.neutral(90),
        surfaceContainerLow = neutral.neutral(96),
        surfaceContainerLowest = neutral.neutral(100),
        primaryFixed = primary.at(90),
        primaryFixedDim = primary.at(80),
        onPrimaryFixed = primary.at(10),
        onPrimaryFixedVariant = primary.at(30),
        secondaryFixed = secondary.at(90),
        secondaryFixedDim = secondary.at(80),
        onSecondaryFixed = secondary.at(10),
        onSecondaryFixedVariant = secondary.at(30),
        tertiaryFixed = tertiary.at(90),
        tertiaryFixedDim = tertiary.at(80),
        onTertiaryFixed = tertiary.at(10),
        onTertiaryFixedVariant = tertiary.at(30),
    )
}

internal val ForgeDarkScheme: ColorScheme = run {
    val primary = seedPalette(ForgeSeed)
    val secondary = secondaryPalette(ForgeSeed)
    val tertiary = tertiaryPalette(ForgeSeed)
    val neutral = primary
    darkColorScheme(
        primary = primary.at(80),
        onPrimary = primary.at(20),
        primaryContainer = primary.at(30),
        onPrimaryContainer = primary.at(90),
        inversePrimary = primary.at(40),
        secondary = secondary.at(80),
        onSecondary = secondary.at(20),
        secondaryContainer = secondary.at(30),
        onSecondaryContainer = secondary.at(90),
        tertiary = tertiary.at(80),
        onTertiary = tertiary.at(20),
        tertiaryContainer = tertiary.at(30),
        onTertiaryContainer = tertiary.at(90),
        background = neutral.neutral(10),
        onBackground = neutral.neutral(90),
        surface = neutral.neutral(10),
        onSurface = neutral.neutral(90),
        surfaceVariant = neutral.neutral(30),
        onSurfaceVariant = neutral.neutral(80),
        surfaceTint = primary.at(80),
        inverseSurface = neutral.neutral(90),
        inverseOnSurface = neutral.neutral(20),
        error = ErrorPalette.at(80, 0.72f),
        onError = ErrorPalette.at(20, 0.72f),
        errorContainer = ErrorPalette.at(30, 0.72f),
        onErrorContainer = ErrorPalette.at(90, 0.72f),
        outline = neutral.neutral(60),
        outlineVariant = neutral.neutral(30),
        scrim = Color.Black,
        surfaceBright = neutral.neutral(24),
        surfaceDim = neutral.neutral(6),
        surfaceContainer = neutral.neutral(12),
        surfaceContainerHigh = neutral.neutral(17),
        surfaceContainerHighest = neutral.neutral(22),
        surfaceContainerLow = neutral.neutral(10),
        surfaceContainerLowest = neutral.neutral(6),
        primaryFixed = primary.at(90),
        primaryFixedDim = primary.at(80),
        onPrimaryFixed = primary.at(10),
        onPrimaryFixedVariant = primary.at(30),
        secondaryFixed = secondary.at(90),
        secondaryFixedDim = secondary.at(80),
        onSecondaryFixed = secondary.at(10),
        onSecondaryFixedVariant = secondary.at(30),
        tertiaryFixed = tertiary.at(90),
        tertiaryFixedDim = tertiary.at(80),
        onTertiaryFixed = tertiary.at(10),
        onTertiaryFixedVariant = tertiary.at(30),
    )
}

private fun hueOf(color: Color): Float {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    if (delta == 0f) return 0f
    val hue = when (max) {
        r -> 60f * (((g - b) / delta) % 6f)
        g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    return (hue + 360f) % 360f
}

private fun hsl(hue: Float, saturation: Float, lightness: Float): Color {
    val c = (1f - abs(2f * lightness - 1f)) * saturation
    val x = c * (1f - abs((hue / 60f) % 2f - 1f))
    val m = lightness - c / 2f
    val (r, g, b) = when {
        hue < 60f -> Triple(c, x, 0f)
        hue < 120f -> Triple(x, c, 0f)
        hue < 180f -> Triple(0f, c, x)
        hue < 240f -> Triple(0f, x, c)
        hue < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        red = (r + m).coerceIn(0f, 1f),
        green = (g + m).coerceIn(0f, 1f),
        blue = (b + m).coerceIn(0f, 1f),
    )
}
