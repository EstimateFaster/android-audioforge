package com.estimatefaster.audioforge.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.R

/**
 * 行内错误条：错误容器色 + 可读原因 + 可选重试/关闭。
 *
 * 用于页面已有内容时的局部失败（保存、刷新、加载更多）；整页失败仍用
 * [ErrorState]，避免同一屏出现两个抢焦点的错误层级。原因文案带 Polite liveRegion，
 * TalkBack 会在错误出现时播报；重试与关闭按钮的触控高度均不小于 48dp。
 */
@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    retryText: String? = null,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = message,
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (onRetry != null) {
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(
                        text = retryText?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.action_retry),
                    )
                }
            }
            if (onDismiss != null) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(stringResource(R.string.action_dismiss))
                }
            }
        }
    }
}

@Preview(name = "ErrorBanner Light", showBackground = true)
@Composable
private fun ErrorBannerLightPreview() {
    ComponentPreview(darkTheme = false) {
        ErrorBanner(
            message = stringResource(R.string.error_state_demo_message),
            onRetry = {},
            onDismiss = {},
        )
    }
}

@Preview(
    name = "ErrorBanner Dark 200%",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun ErrorBannerDarkPreview() {
    ComponentPreview(darkTheme = true) {
        ErrorBanner(
            message = stringResource(R.string.error_state_demo_message),
            retryText = stringResource(R.string.action_retry),
            onRetry = {},
        )
    }
}
