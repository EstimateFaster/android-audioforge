package com.estimatefaster.audioforge.ui.components

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.R
import java.util.Locale

/** 状态所属的业务域；只影响文案，不影响颜色语义。 */
enum class StatusKind {
    Parse,
    Audio,
    Task,
}

/**
 * 状态语义色。实际颜色全部从 [MaterialTheme.colorScheme] 取，
 * 不在此处硬编码品牌色，保证动态色/深色模式一致。
 */
enum class StatusTone {
    Neutral,
    Info,
    Running,
    Success,
    Warning,
    Danger,
}

/** [StatusTone] 解析出的容器色与内容色。 */
data class StatusPillColors(
    val container: Color,
    val content: Color,
)

/** 统一状态字符串：去掉空白并转小写（wire 值大小写不敏感）。 */
fun normalizeStatus(status: String): String = status.trim().lowercase(Locale.ROOT)

/** 状态 → 语义色。未知状态按中性处理，避免误报成功/失败。 */
fun statusToneFor(status: String): StatusTone = when (normalizeStatus(status)) {
    "running" -> StatusTone.Running
    "done", "succeeded", "success" -> StatusTone.Success
    "error", "failed", "failure" -> StatusTone.Danger
    "queued", "enqueued" -> StatusTone.Info
    "blocked" -> StatusTone.Warning
    "pending", "idle", "cancelled", "canceled" -> StatusTone.Neutral
    else -> StatusTone.Neutral
}

/**
 * 状态 → 中文文案资源。解析/音频/任务三个域对同一 wire 值的措辞不同
 * （例如 pending：待解析 / 待生成 / 待处理）。
 */
@StringRes
fun statusLabelRes(kind: StatusKind, status: String): Int {
    val normalized = normalizeStatus(status)
    return when (kind) {
        StatusKind.Parse -> when (normalized) {
            "pending" -> R.string.status_parse_pending
            "running" -> R.string.status_parse_running
            "done", "succeeded", "success" -> R.string.status_parse_done
            "error", "failed", "failure" -> R.string.status_parse_error
            else -> R.string.status_unknown
        }

        StatusKind.Audio -> when (normalized) {
            "pending" -> R.string.status_audio_pending
            "running" -> R.string.status_audio_running
            "done", "succeeded", "success" -> R.string.status_audio_done
            "error", "failed", "failure" -> R.string.status_audio_error
            else -> R.string.status_unknown
        }

        StatusKind.Task -> when (normalized) {
            "pending" -> R.string.status_task_pending
            "idle" -> R.string.status_task_idle
            "queued", "enqueued" -> R.string.status_task_queued
            "running" -> R.string.status_task_running
            "done", "succeeded", "success" -> R.string.status_task_done
            "error", "failed", "failure" -> R.string.status_task_error
            "cancelled", "canceled" -> R.string.status_task_cancelled
            "blocked" -> R.string.status_task_blocked
            else -> R.string.status_unknown
        }
    }
}

/**
 * 纯函数形式的颜色解析，便于单元测试验证“颜色来自 colorScheme 而不是硬编码”。
 */
fun resolveStatusPillColors(tone: StatusTone, scheme: ColorScheme): StatusPillColors = when (tone) {
    StatusTone.Neutral -> StatusPillColors(scheme.surfaceVariant, scheme.onSurfaceVariant)
    StatusTone.Info -> StatusPillColors(scheme.primaryContainer, scheme.onPrimaryContainer)
    StatusTone.Running -> StatusPillColors(scheme.primary, scheme.onPrimary)
    StatusTone.Success -> StatusPillColors(scheme.tertiaryContainer, scheme.onTertiaryContainer)
    StatusTone.Warning -> StatusPillColors(scheme.secondaryContainer, scheme.onSecondaryContainer)
    StatusTone.Danger -> StatusPillColors(scheme.errorContainer, scheme.onErrorContainer)
}

/**
 * 按业务域 + wire 状态显示小胶囊；文案由 `strings.xml` 提供。
 *
 * @param onClick 非空时整颗胶囊可点（例如失败状态跳转详情），触控区域自动补足 48dp。
 */
@Composable
fun StatusPill(
    kind: StatusKind,
    status: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    StatusPill(
        text = stringResource(statusLabelRes(kind, status)),
        tone = statusToneFor(status),
        modifier = modifier,
        onClick = onClick,
    )
}

/**
 * 通用小胶囊：圆点 + 文字。running 时圆点呼吸，动画遵循系统“移除动画”设置。
 *
 * @param text 调用方已本地化的文案（不要硬编码在组件内）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusPill(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = resolveStatusPillColors(tone, MaterialTheme.colorScheme)
    val animationsEnabled = rememberSystemAnimationsEnabled()

    val pulseAlpha = if (tone == StatusTone.Running && animationsEnabled) {
        val transition = rememberInfiniteTransition(label = "statusPillPulse")
        val alpha by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "statusPillPulseAlpha",
        )
        alpha
    } else {
        1f
    }

    val interactionModifier = if (onClick != null) {
        Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(percent = 50))
            .clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .then(interactionModifier)
            .semantics(mergeDescendants = true) { },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(colors.container)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(
                modifier = Modifier
                    .size(8.dp)
                    .clearAndSetSemantics { },
            ) {
                drawCircle(color = colors.content.copy(alpha = pulseAlpha))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = colors.content,
            )
        }
    }
}

@Composable
private fun StatusPillSamples() {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusPill(StatusKind.Parse, "running")
        StatusPill(StatusKind.Audio, "done")
        StatusPill(StatusKind.Task, "blocked")
        StatusPill(StatusKind.Task, "error", onClick = {})
        StatusPill(text = stringResource(R.string.status_unknown), tone = StatusTone.Neutral)
    }
}

@Preview(name = "StatusPill Light", showBackground = true)
@Composable
private fun StatusPillLightPreview() {
    ComponentPreview(darkTheme = false) {
        StatusPillSamples()
    }
}

@Preview(
    name = "StatusPill Dark 200%",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun StatusPillDarkPreview() {
    ComponentPreview(darkTheme = true) {
        StatusPillSamples()
    }
}
