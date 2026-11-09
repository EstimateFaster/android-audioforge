package com.estimatefaster.audioforge.ui.navigation

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.ui.components.ExpressivePill
import com.estimatefaster.audioforge.ui.theme.spacing

/**
 * 迷你播放器占位（P4-01 / Expressive 重做）。
 *
 * 只显示「未在播放」的 tonal pill 与播放图标，不依赖播放模块；真正的播放状态在
 * P2-14/P4-13 接入。外壳负责把它放在导航栏上方。
 */
@Composable
fun MiniPlayerPlaceholder(modifier: Modifier = Modifier) {
    ExpressivePill(modifier = modifier) {
        Icon(
            imageVector = MiniPlayerPlayGlyph,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(MaterialTheme.spacing.xl),
        )
        Text(
            text = stringResource(R.string.mini_player_idle),
            style = MaterialTheme.typography.labelMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 本地绘制的播放三角，避免为一个图标引入 material-icons-extended。 */
private val MiniPlayerPlayGlyph: ImageVector = ImageVector.Builder(
    name = "mini_player_play",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(8f, 5f)
        lineTo(19f, 12f)
        lineTo(8f, 19f)
        close()
    }
}.build()
