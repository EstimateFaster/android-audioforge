package com.estimatefaster.audioforge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 统一加载态：不确定进度环 + 状态文案。
 *
 * 整页加载默认占满可用空间并居中，避免首帧白屏；状态文案是 Polite liveRegion，
 * TalkBack 会播报一次“加载中”，不会持续重复。
 */
@Composable
fun LoadingState(
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.heightIn(min = 12.dp))
        Text(
            text = message,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 一次只展示一种整页状态：加载、错误、空态或正文。
 *
 * 顺序固定为 加载 → 错误 → 空态 → 正文，调用方不需要在每屏重复同样的
 * `when` 分支，也不会同时出现 [ErrorState] 与 [EmptyState]。
 */
sealed interface ScreenState {

    /** 首屏加载中。 */
    data class Loading(val message: String) : ScreenState

    /** 加载完成但没有任何内容；[onAction] 为空时只展示说明。 */
    data class Empty(
        val title: String,
        val description: String,
        val actionText: String? = null,
        val onAction: (() -> Unit)? = null,
    ) : ScreenState

    /** 首屏失败；[onRetry] 为空时只展示原因。 */
    data class Error(
        val message: String,
        val onRetry: (() -> Unit)? = null,
    ) : ScreenState

    /** 有内容可展示。 */
    data object Content : ScreenState
}

/**
 * [ScreenState] 的渲染入口；[content] 只在 [ScreenState.Content] 时执行。
 */
@Composable
fun ScreenState(
    state: ScreenState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    when (state) {
        is ScreenState.Loading -> LoadingState(message = state.message, modifier = modifier)

        is ScreenState.Error -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            ErrorState(
                message = state.message,
                onRetry = state.onRetry,
            )
        }

        is ScreenState.Empty -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                title = state.title,
                description = state.description,
                actionText = state.actionText,
                onAction = state.onAction,
            )
        }

        ScreenState.Content -> content()
    }
}
