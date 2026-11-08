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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.R

/**
 * 错误态：错误图标 + 可读原因 + 可选重试。
 *
 * 失败原因不截断、可换行；重试按钮触控高度 ≥48dp。
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    retryText: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ErrorStateGlyph()
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            val label = retryText?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.action_retry)
            FilledTonalButton(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun ErrorStateGlyph() {
    val container = MaterialTheme.colorScheme.errorContainer
    val content = MaterialTheme.colorScheme.onErrorContainer
    Canvas(
        modifier = Modifier
            .size(72.dp)
            .clearAndSetSemantics { },
    ) {
        drawCircle(color = container, radius = size.minDimension / 2f, center = center)
        val stroke = size.minDimension * 0.06f
        drawLine(
            color = content,
            start = Offset(center.x, size.height * 0.30f),
            end = Offset(center.x, size.height * 0.55f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = content,
            radius = stroke * 0.8f,
            center = Offset(center.x, size.height * 0.68f),
        )
    }
}

@Preview(name = "ErrorState Light", showBackground = true)
@Composable
private fun ErrorStateLightPreview() {
    ComponentPreview(darkTheme = false) {
        ErrorState(
            message = stringResource(R.string.error_state_demo_message),
            retryText = stringResource(R.string.action_retry),
            onRetry = {},
        )
    }
}

@Preview(
    name = "ErrorState Dark 200%",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun ErrorStateDarkPreview() {
    ComponentPreview(darkTheme = true) {
        ErrorState(
            message = stringResource(R.string.error_state_demo_message),
            retryText = stringResource(R.string.action_retry),
            onRetry = {},
        )
    }
}
