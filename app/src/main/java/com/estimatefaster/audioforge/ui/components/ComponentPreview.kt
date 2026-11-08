package com.estimatefaster.audioforge.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme

/**
 * 设计系统预览的统一外壳：固定非动态色，便于分别检查浅色/深色。
 * 只用于 `@Preview`，不参与正式界面。
 */
@Composable
internal fun ComponentPreview(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    AudioForgeTheme(darkTheme = darkTheme, dynamicColor = false) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                content()
            }
        }
    }
}
