package com.estimatefaster.audioforge.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.ui.forge.ForgeIcon
import com.estimatefaster.audioforge.ui.forge.ForgeIcons

/**
 * Forge Framework v1 自适应导航壳。
 *
 * 固定 3 个主目的地（书架 / 任务 / 设置），全部操作为 Lucide 图标 + 文案；
 * 选中态使用 primaryContainer 的 tonal indicator。
 *
 * - 宽 < 600dp：内容 + 迷你播放器 + [NavigationBar]（手机底栏）
 * - 宽 ≥ 600dp：左侧 [NavigationRail] + 右侧内容与迷你播放器
 *
 * 设计稿没有给 Rail 单独画样式，按同一套 NavigationBar 的图标/指示器规则实现，
 * 断点与 Material 3 的 compact/medium 分界一致。内容区不额外加内边距，
 * 由各页面自己的 Scaffold/TopAppBar 处理系统栏。
 */
@Composable
fun AdaptiveScaffold(
    selectedDestination: TopLevelDestination?,
    onSelectDestination: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
    miniPlayer: @Composable () -> Unit = { MiniPlayerPlaceholder() },
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (maxWidth >= NavigationRailBreakpoint) {
            Row(modifier = Modifier.fillMaxSize()) {
                ForgeNavigationRail(
                    selectedDestination = selectedDestination,
                    onSelectDestination = onSelectDestination,
                )
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        content()
                    }
                    miniPlayer()
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    content()
                }
                miniPlayer()
                ForgeNavigationBar(
                    selectedDestination = selectedDestination,
                    onSelectDestination = onSelectDestination,
                )
            }
        }
    }
}

/** 手机底栏：3 个 tab、Lucide 图标、primaryContainer tonal indicator。 */
@Composable
private fun ForgeNavigationBar(
    selectedDestination: TopLevelDestination?,
    onSelectDestination: (TopLevelDestination) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
    ) {
        TopLevelDestination.entries.forEach { destination ->
            val selected = destination == selectedDestination
            NavigationBarItem(
                selected = selected,
                onClick = { onSelectDestination(destination) },
                icon = {
                    ForgeIcon(
                        icon = destination.uxIcon(),
                        contentDescription = null,
                        tint = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                label = {
                    Text(
                        text = stringResource(destination.uxLabelRes()),
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

/** 宽屏 Rail：图标与指示器规则同底栏，文案始终显示。 */
@Composable
private fun ForgeNavigationRail(
    selectedDestination: TopLevelDestination?,
    onSelectDestination: (TopLevelDestination) -> Unit,
) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        header = {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    ForgeIcon(
                        icon = ForgeIcons.Sparkles,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        },
    ) {
        TopLevelDestination.entries.forEach { destination ->
            val selected = destination == selectedDestination
            NavigationRailItem(
                selected = selected,
                onClick = { onSelectDestination(destination) },
                icon = {
                    ForgeIcon(
                        icon = destination.uxIcon(),
                        contentDescription = null,
                        tint = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                label = {
                    Text(
                        text = stringResource(destination.uxLabelRes()),
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                alwaysShowLabel = true,
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

/**
 * 导航文案以设计稿 3 tabs 为准（书架 / 任务 / 设置）。
 * [TopLevelDestination.labelRes] 保留给测试与其它入口，这里只覆盖导航壳展示。
 */
@StringRes
private fun TopLevelDestination.uxLabelRes(): Int = when (this) {
    TopLevelDestination.Projects -> R.string.ux_nav_tab_library
    TopLevelDestination.Tasks -> R.string.ux_nav_tab_tasks
    TopLevelDestination.Settings -> R.string.ux_nav_tab_settings
}

/** 图标全部取自 [ForgeIcons]（Lucide）：书架 library / 任务 gauge / 设置 settings。 */
@DrawableRes
private fun TopLevelDestination.uxIcon(): Int = when (this) {
    TopLevelDestination.Projects -> ForgeIcons.Library
    TopLevelDestination.Tasks -> ForgeIcons.Gauge
    TopLevelDestination.Settings -> ForgeIcons.Settings
}

private val NavigationRailBreakpoint = 600.dp
