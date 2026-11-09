package com.estimatefaster.audioforge.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.ui.forge.ForgeExtendedFab
import com.estimatefaster.audioforge.ui.forge.ForgeIcon
import com.estimatefaster.audioforge.ui.forge.ForgeIcons
import com.estimatefaster.audioforge.ui.theme.spacing

/**
 * 迷你播放器占位（P4-01，Forge Framework v1 版）。
 *
 * 结构照设计稿：封面 + 标题/副标题 + 播放操作；播放操作使用
 * [ForgeExtendedFab]（Lucide `ForgeIcons.Play` + 文案），不再用纯文字冒充按钮。
 *
 * 真正的播放状态在 P2-14/P4-13 接入；此文件刻意不依赖播放模块，
 * [onOpenPlayer] 默认空实现，接线时由壳层传入。
 */
@Composable
fun MiniPlayerPlaceholder(
    modifier: Modifier = Modifier,
    onOpenPlayer: () -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.md, vertical = MaterialTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        ForgeIcon(
                            icon = ForgeIcons.Headphones,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.mini_player_idle),
                        style = MaterialTheme.typography.titleSmallEmphasized,
                    )
                    Text(
                        text = stringResource(R.string.ux_mini_player_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ForgeExtendedFab(
                    icon = ForgeIcons.Play,
                    label = stringResource(R.string.ux_mini_player_play),
                    onClick = onOpenPlayer,
                )
            }
        }
    }
}
