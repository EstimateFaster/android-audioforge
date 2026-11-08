package com.estimatefaster.audioforge.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.R

/**
 * 空态：插画 + 标题 + 说明 + 可选主行动。
 *
 * 文案均由调用方/`strings.xml` 提供；插画是装饰性 Canvas，不参与 TalkBack。
 */
@Composable
fun EmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EmptyStateGlyph()
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionText != null && onAction != null && actionText.isNotBlank()) {
            FilledTonalButton(
                onClick = onAction,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(actionText)
            }
        }
    }
}

@Composable
private fun EmptyStateGlyph() {
    val container = MaterialTheme.colorScheme.primaryContainer
    val content = MaterialTheme.colorScheme.onPrimaryContainer
    Canvas(
        modifier = Modifier
            .size(72.dp)
            .clearAndSetSemantics { },
    ) {
        drawCircle(color = container, radius = size.minDimension / 2f, center = center)
        val stroke = size.minDimension * 0.045f
        val startX = size.width * 0.30f
        val fullEndX = size.width * 0.70f
        val lineYs = listOf(0.40f, 0.50f, 0.60f)
        lineYs.forEachIndexed { index, y ->
            val endX = if (index == lineYs.lastIndex) size.width * 0.58f else fullEndX
            drawLine(
                color = content,
                start = Offset(startX, size.height * y),
                end = Offset(endX, size.height * y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Preview(name = "EmptyState Light", showBackground = true)
@Composable
private fun EmptyStateLightPreview() {
    ComponentPreview(darkTheme = false) {
        EmptyState(
            title = stringResource(R.string.project_library_empty_title),
            description = stringResource(R.string.project_library_empty_description),
            actionText = stringResource(R.string.action_import_novel),
            onAction = {},
        )
    }
}

@Preview(
    name = "EmptyState Dark 200%",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun EmptyStateDarkPreview() {
    ComponentPreview(darkTheme = true) {
        EmptyState(
            title = stringResource(R.string.project_library_empty_title),
            description = stringResource(R.string.project_library_empty_description),
            actionText = stringResource(R.string.action_import_novel),
            onAction = {},
        )
    }
}
