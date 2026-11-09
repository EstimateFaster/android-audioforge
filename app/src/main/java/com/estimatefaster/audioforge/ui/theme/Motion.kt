package com.estimatefaster.audioforge.ui.theme

import androidx.compose.material3.MotionScheme

/**
 * 统一动效规格：组件只引用这里的 duration/easing，不写魔法数字。
 * `MotionScheme.expressive()` 负责 M3 组件的官方 spatial/effects 动效。
 */
object ForgeMotion {
    val scheme: MotionScheme = MotionScheme.expressive()

    const val DURATION_SHORT_MS: Int = 150
    const val DURATION_MEDIUM_MS: Int = 300
    const val DURATION_LONG_MS: Int = 500
    const val DURATION_EXTRA_LONG_MS: Int = 700

    const val EASING_STANDARD: Float = 0.2f
    const val EASING_EMPHASIZED: Float = 0.05f
}
