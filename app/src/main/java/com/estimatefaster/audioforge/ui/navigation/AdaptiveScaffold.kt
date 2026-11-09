package com.estimatefaster.audioforge.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.estimatefaster.audioforge.ui.theme.spacing

/**
 * 自适应导航壳（P4-01 / Expressive 重做）。
 *
 * 保留 [NavigationSuiteScaffold] 的断点判断：窄屏底部 `NavigationBar`，宽屏左侧 `NavigationRail`，
 * 不在这里重复实现 WindowSizeClass。视觉上：
 * - 导航容器使用 tonal 表面（`surfaceContainerHigh`），与内容背景形成柔和层级；
 * - 选中态指示器用 `secondaryContainer`，图标/文字用 `onSecondaryContainer`；
 * - 选中文字走 expressive 的 `labelMediumEmphasized`，未选中保持 `labelMedium`。
 *
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
    val spacing = MaterialTheme.spacing
    val itemColors = NavigationSuiteDefaults.itemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        navigationRailItemColors = NavigationRailItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
    val suiteColors = NavigationSuiteDefaults.colors(
        navigationBarContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        navigationBarContentColor = MaterialTheme.colorScheme.onSurface,
        navigationRailContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        navigationRailContentColor = MaterialTheme.colorScheme.onSurface,
    )

    NavigationSuiteScaffold(
        modifier = modifier,
        navigationSuiteColors = suiteColors,
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { destination ->
                val selected = destination == selectedDestination
                item(
                    selected = selected,
                    onClick = { onSelectDestination(destination) },
                    colors = itemColors,
                    icon = {
                        Icon(
                            imageVector = destination.icon.vector,
                            contentDescription = null,
                        )
                    },
                    label = {
                        Text(
                            text = stringResource(destination.labelRes),
                            style = if (selected) {
                                MaterialTheme.typography.labelMediumEmphasized
                            } else {
                                MaterialTheme.typography.labelMedium
                            },
                        )
                    },
                )
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                content()
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.lg, vertical = spacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                miniPlayer()
            }
        }
    }
}
