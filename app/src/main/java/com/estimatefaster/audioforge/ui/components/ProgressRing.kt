package com.estimatefaster.audioforge.ui.components

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.R
import kotlin.math.roundToInt

/** 把任意浮点进度收敛到 0..1；NaN/±Infinity 也安全。 */
fun clampProgress(progress: Float): Float = when {
    progress.isNaN() -> 0f
    progress < 0f -> 0f
    progress > 1f -> 1f
    else -> progress
}

/** 进度百分比整数（四舍五入），用于圆环中心文案。 */
fun progressPercent(progress: Float): Int = (clampProgress(progress) * 100f).roundToInt()

/**
 * 环形进度：Canvas 绘制轨道 + 进度弧，中心显示百分比。
 *
 * 无障碍：整个圆环是一个 `progressBarRangeInfo` 节点，百分比文字不重复朗读；
 * `label` 会作为 contentDescription，未提供时使用“进度”。
 *
 * @param progress 0..1，越界自动 clamp。
 * @param label 可选的说明文字（例如“项目进度”），同时用作无障碍描述。
 */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    label: String? = null,
    diameter: Dp = 120.dp,
    strokeWidth: Dp = 10.dp,
) {
    val clamped = clampProgress(progress)
    val animatedProgress by animateFloatAsState(
        targetValue = clamped,
        label = "progressRing",
    )
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val strokePx = with(density) { strokeWidth.toPx() }
    val resolvedLabel = label?.takeIf { it.isNotBlank() }
    val description = resolvedLabel ?: stringResource(R.string.progress_default_description)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(diameter)
                .clearAndSetSemantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f)
                    contentDescription = description
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val stroke = strokePx.coerceIn(0f, size.minDimension / 2f)
                if (stroke <= 0f) return@Canvas
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)

                drawArc(
                    color = scheme.surfaceVariant,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                if (animatedProgress > 0f) {
                    drawArc(
                        color = scheme.primary,
                        startAngle = -90f,
                        sweepAngle = 360f * animatedProgress,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Text(
                text = stringResource(R.string.progress_percent, progressPercent(clamped)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (resolvedLabel != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = resolvedLabel,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

@Composable
private fun ProgressRingSamples() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProgressRing(
            progress = 0.42f,
            label = stringResource(R.string.progress_demo_label),
        )
        ProgressRing(progress = 1f)
    }
}

@Preview(name = "ProgressRing Light", showBackground = true)
@Composable
private fun ProgressRingLightPreview() {
    ComponentPreview(darkTheme = false) {
        ProgressRingSamples()
    }
}

@Preview(
    name = "ProgressRing Dark 200%",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun ProgressRingDarkPreview() {
    ComponentPreview(darkTheme = true) {
        ProgressRingSamples()
    }
}
