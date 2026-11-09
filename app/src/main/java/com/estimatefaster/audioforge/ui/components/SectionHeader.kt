@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.estimatefaster.audioforge.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.R

/**
 * 分组标题：标题（heading 语义）+ 可选说明 + 可选右侧文字按钮。
 *
 * 标题允许换行，不与右侧按钮争抢宽度；按钮触控高度 ≥48dp。
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val resolvedSubtitle = subtitle?.takeIf { it.isNotBlank() }
    val resolvedActionText = actionText?.takeIf { it.isNotBlank() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLargeEmphasized,
                modifier = Modifier.semantics { heading() },
            )
            if (resolvedSubtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = resolvedSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (resolvedActionText != null && onAction != null) {
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onAction,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(resolvedActionText)
            }
        }
    }
}

@Preview(name = "SectionHeader Light", showBackground = true)
@Composable
private fun SectionHeaderLightPreview() {
    ComponentPreview(darkTheme = false) {
        SectionHeader(
            title = stringResource(R.string.section_demo_title),
            subtitle = stringResource(R.string.section_demo_subtitle),
            actionText = stringResource(R.string.action_view_all),
            onAction = {},
        )
    }
}

@Preview(
    name = "SectionHeader Dark 200%",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun SectionHeaderDarkPreview() {
    ComponentPreview(darkTheme = true) {
        SectionHeader(
            title = stringResource(R.string.section_demo_title),
            subtitle = stringResource(R.string.section_demo_subtitle),
            actionText = stringResource(R.string.action_view_all),
            onAction = {},
        )
    }
}
