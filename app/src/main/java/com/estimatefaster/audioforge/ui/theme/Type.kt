package com.estimatefaster.audioforge.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Expressive 字阶：display/headline 使用更紧的行高与更大字重，
 * title/body/label 保持中文可读性；`*Emphasized` 通过加粗做层级强调。
 */
private fun forgeStyle(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    tracking: Double = 0.0,
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    letterSpacing = tracking.sp,
)

private val displayLarge = forgeStyle(57, 62, FontWeight.SemiBold, -0.25)
private val displayMedium = forgeStyle(45, 50, FontWeight.SemiBold, -0.2)
private val displaySmall = forgeStyle(36, 42, FontWeight.SemiBold, -0.1)
private val headlineLarge = forgeStyle(32, 38, FontWeight.SemiBold)
private val headlineMedium = forgeStyle(28, 34, FontWeight.SemiBold)
private val headlineSmall = forgeStyle(24, 30, FontWeight.SemiBold)
private val titleLarge = forgeStyle(22, 28, FontWeight.SemiBold)
private val titleMedium = forgeStyle(16, 22, FontWeight.SemiBold, 0.1)
private val titleSmall = forgeStyle(14, 20, FontWeight.Medium, 0.1)
private val bodyLarge = forgeStyle(16, 24)
private val bodyMedium = forgeStyle(14, 21)
private val bodySmall = forgeStyle(12, 18)
private val labelLarge = forgeStyle(14, 20, FontWeight.Medium, 0.1)
private val labelMedium = forgeStyle(12, 16, FontWeight.Medium, 0.4)
private val labelSmall = forgeStyle(11, 15, FontWeight.Medium, 0.4)

val ForgeTypography: Typography = Typography(
    displayLarge = displayLarge,
    displayMedium = displayMedium,
    displaySmall = displaySmall,
    headlineLarge = headlineLarge,
    headlineMedium = headlineMedium,
    headlineSmall = headlineSmall,
    titleLarge = titleLarge,
    titleMedium = titleMedium,
    titleSmall = titleSmall,
    bodyLarge = bodyLarge,
    bodyMedium = bodyMedium,
    bodySmall = bodySmall,
    labelLarge = labelLarge,
    labelMedium = labelMedium,
    labelSmall = labelSmall,
    displayLargeEmphasized = displayLarge.copy(fontWeight = FontWeight.Bold),
    displayMediumEmphasized = displayMedium.copy(fontWeight = FontWeight.Bold),
    displaySmallEmphasized = displaySmall.copy(fontWeight = FontWeight.Bold),
    headlineLargeEmphasized = headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineMediumEmphasized = headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmallEmphasized = headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLargeEmphasized = titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMediumEmphasized = titleMedium.copy(fontWeight = FontWeight.Bold),
    titleSmallEmphasized = titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLargeEmphasized = bodyLarge.copy(fontWeight = FontWeight.Medium),
    bodyMediumEmphasized = bodyMedium.copy(fontWeight = FontWeight.Medium),
    bodySmallEmphasized = bodySmall.copy(fontWeight = FontWeight.Medium),
    labelLargeEmphasized = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMediumEmphasized = labelMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmallEmphasized = labelSmall.copy(fontWeight = FontWeight.SemiBold),
)
