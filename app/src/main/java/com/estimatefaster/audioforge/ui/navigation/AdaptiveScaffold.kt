package com.estimatefaster.audioforge.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/**
 * 自适应导航壳（P4-01）：手机底部 `NavigationBar`，宽屏自动切换为 `NavigationRail`。
 *
 * 断点判断交给 [NavigationSuiteScaffold]（内部读取 WindowSizeClass），本轮不重复实现。
 * 迷你播放器固定在导航栏上方；内容区由调用方传入（通常是 NavHost）。
 */
@Composable
fun AdaptiveScaffold(
    selectedDestination: TopLevelDestination?,
    onSelectDestination: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
    miniPlayer: @Composable () -> Unit = { MiniPlayerPlaceholder() },
    content: @Composable () -> Unit,
) {
    NavigationSuiteScaffold(
        modifier = modifier,
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { destination ->
                item(
                    selected = destination == selectedDestination,
                    onClick = { onSelectDestination(destination) },
                    icon = {
                        Icon(
                            imageVector = destination.icon.vector,
                            contentDescription = null,
                        )
                    },
                    label = { Text(text = stringResource(destination.labelRes)) },
                )
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                content()
            }
            miniPlayer()
        }
    }
}
