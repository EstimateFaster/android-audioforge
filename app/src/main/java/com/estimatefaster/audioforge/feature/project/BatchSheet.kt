@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.estimatefaster.audioforge.feature.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.domain.jobs.BatchParams
import com.estimatefaster.audioforge.ui.components.AdaptiveFieldRow
import com.estimatefaster.audioforge.ui.components.ExpressiveCard
import com.estimatefaster.audioforge.ui.components.ExpressiveOutlinedButton
import com.estimatefaster.audioforge.ui.components.ExpressivePrimaryButton
import com.estimatefaster.audioforge.ui.components.ExpressiveSectionHeader
import com.estimatefaster.audioforge.ui.components.ExpressiveTextButton
import com.estimatefaster.audioforge.ui.components.ExpressiveTonalButton
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing
import kotlin.math.roundToInt

/**
 * 批次设置 Sheet（P4-06 / 执行计划 §12.4 S05）。
 *
 * 有状态入口：收集 [BatchViewModel.state]，把开始成功事件转成 `onStarted`，把
 * 下滑/取消/返回转成 `onDismiss`。Key 状态由调用方传入，Sheet 只负责展示与拦截。
 *
 * 集成提示：在项目详情页用 `if (showBatch) BatchSheet(viewModel = ...,
 * onDismiss = { showBatch = false }, onStarted = { showBatch = false },
 * hasLlmKey = ..., hasFishKey = ..., onOpenSettings = ...)` 即可。
 *
 * @param onDismiss 用户取消/下滑/返回时关闭 Sheet
 * @param onStarted 批次成功启动后关闭 Sheet（jobId 保留在状态里可查）
 * @param hasLlmKey 调用方当前是否已配置 LLM Key
 * @param hasFishKey 调用方当前是否已配置 Fish Audio Key
 * @param onOpenSettings 无 Key 时“去设置”入口
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchSheet(
    viewModel: BatchViewModel,
    onDismiss: () -> Unit,
    onStarted: () -> Unit,
    hasLlmKey: Boolean,
    hasFishKey: Boolean,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnStarted by rememberUpdatedState(onStarted)

    // 调用方可能在 Sheet 显示期间完成 Key 配置；同步到 ViewModel 后立即解除禁用。
    LaunchedEffect(viewModel, hasLlmKey, hasFishKey) {
        viewModel.updateKeys(hasLlmKey = hasLlmKey, hasFishKey = hasFishKey)
    }

    // 开始成功后只发一次事件；关闭动作交给调用方的 onStarted（避免重复导航）。
    LaunchedEffect(viewModel) {
        viewModel.events.collect {
            currentOnStarted()
        }
    }

    ModalBottomSheet(
        onDismissRequest = currentOnDismiss,
        modifier = modifier,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraExtraLarge,
    ) {
        BatchSheetContent(
            state = state,
            onModeChange = viewModel::setMode,
            onStartChange = viewModel::setStartText,
            onEndChange = viewModel::setEndText,
            onForceChange = viewModel::setForce,
            onConcurrencyChange = viewModel::setConcurrency,
            onSelectUnfinished = viewModel::selectUnfinished,
            onSelectAll = viewModel::selectAll,
            onStart = viewModel::start,
            onStop = viewModel::stop,
            onOpenSettings = onOpenSettings,
            onDismiss = onDismiss,
        )
    }
}

/**
 * 无状态 Sheet 内容；Preview 与后续 UI 测试直接渲染它。
 *
 * Expressive 布局：标题 → 模式卡 → 范围卡（含强制重跑）→ 并发卡 → 预估
 * → 校验/Key/状态卡 → 唯一主按钮 + 次级停止/取消。参数校验仍完全由
 * [BatchUiState] 决定，UI 只负责映射文案。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchSheetContent(
    state: BatchUiState,
    modifier: Modifier = Modifier,
    onModeChange: (BatchMode) -> Unit = {},
    onStartChange: (String) -> Unit = {},
    onEndChange: (String) -> Unit = {},
    onForceChange: (Boolean) -> Unit = {},
    onConcurrencyChange: (Int) -> Unit = {},
    onSelectUnfinished: () -> Unit = {},
    onSelectAll: () -> Unit = {},
    onStart: () -> Unit = {},
    onStop: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.spacing.xl)
            .padding(
                top = MaterialTheme.spacing.sm,
                bottom = MaterialTheme.spacing.xl,
            ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
            Text(
                text = stringResource(R.string.batch_sheet_title),
                style = MaterialTheme.typography.headlineSmallEmphasized,
            )
            Text(
                text = stringResource(R.string.batch_sheet_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ModeCard(
            mode = state.mode,
            onModeChange = onModeChange,
        )
        RangeCard(
            state = state,
            onStartChange = onStartChange,
            onEndChange = onEndChange,
            onForceChange = onForceChange,
            onSelectUnfinished = onSelectUnfinished,
            onSelectAll = onSelectAll,
        )
        ConcurrencyCard(
            concurrency = state.concurrency,
            onConcurrencyChange = onConcurrencyChange,
        )
        EstimateCard(state = state)

        if (state.validationErrors.isNotEmpty()) {
            ValidationCard(state = state)
        }
        if (state.missingKeys.isNotEmpty()) {
            MissingKeysCard(
                missingKeys = state.missingKeys,
                onOpenSettings = onOpenSettings,
            )
        }
        BatchStatusCard(state = state)

        ExpressivePrimaryButton(
            text = stringResource(R.string.batch_action_start),
            onClick = onStart,
            enabled = state.canStart,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
            ExpressiveOutlinedButton(
                text = stringResource(R.string.batch_action_stop),
                onClick = onStop,
                enabled = state.canStop,
                modifier = Modifier.weight(1f),
            )
            ExpressiveTextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = stringResource(R.string.ux_batch_start_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModeCard(
    mode: BatchMode,
    onModeChange: (BatchMode) -> Unit,
) {
    ExpressiveCard {
        ExpressiveSectionHeader(
            title = stringResource(R.string.batch_mode_title),
            subtitle = stringResource(R.string.ux_batch_mode_subtitle),
        )
        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
            BatchMode.entries.forEach { candidate ->
                ModeOption(
                    mode = candidate,
                    selected = mode == candidate,
                    onClick = { onModeChange(candidate) },
                )
            }
        }
    }
}

@Composable
private fun ModeOption(
    mode: BatchMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(
                    horizontal = MaterialTheme.spacing.md,
                    vertical = MaterialTheme.spacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = MaterialTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs),
            ) {
                Text(
                    text = stringResource(mode.labelRes()),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Text(
                    text = stringResource(mode.descriptionRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun RangeCard(
    state: BatchUiState,
    onStartChange: (String) -> Unit,
    onEndChange: (String) -> Unit,
    onForceChange: (Boolean) -> Unit,
    onSelectUnfinished: () -> Unit,
    onSelectAll: () -> Unit,
) {
    val rangeInvalid = state.validationErrors.any {
        it == BatchValidationError.InvalidStart ||
            it == BatchValidationError.InvalidEnd ||
            it == BatchValidationError.StartAfterEnd
    }
    ExpressiveCard {
        ExpressiveSectionHeader(title = stringResource(R.string.batch_range_title))
        AdaptiveFieldRow(
            {
                OutlinedTextField(
                    value = state.startText,
                    onValueChange = onStartChange,
                    label = { Text(stringResource(R.string.batch_range_start)) },
                    singleLine = true,
                    isError = rangeInvalid,
                    shape = MaterialTheme.shapes.large,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            {
                OutlinedTextField(
                    value = state.endText,
                    onValueChange = onEndChange,
                    label = { Text(stringResource(R.string.batch_range_end)) },
                    singleLine = true,
                    isError = rangeInvalid,
                    shape = MaterialTheme.shapes.large,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
            ExpressiveTextButton(
                text = stringResource(R.string.batch_action_select_unfinished),
                onClick = onSelectUnfinished,
                modifier = Modifier.weight(1f),
            )
            ExpressiveTextButton(
                text = stringResource(R.string.batch_action_select_all),
                onClick = onSelectAll,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = stringResource(
                R.string.batch_range_hint,
                state.chapterCount,
                state.chapterCount.coerceAtLeast(1),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.batch_range_default_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ForceSwitch(force = state.force, onForceChange = onForceChange)
    }
}

@Composable
private fun ForceSwitch(
    force: Boolean,
    onForceChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = force,
                role = Role.Switch,
                onValueChange = onForceChange,
            )
            .padding(vertical = MaterialTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.batch_force_title),
                style = MaterialTheme.typography.titleSmallEmphasized,
            )
            Text(
                text = stringResource(R.string.batch_force_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = force,
            onCheckedChange = null,
        )
    }
}

@Composable
private fun ConcurrencyCard(
    concurrency: Int,
    onConcurrencyChange: (Int) -> Unit,
) {
    ExpressiveCard {
        ExpressiveSectionHeader(
            title = stringResource(R.string.batch_concurrency_title),
            actions = {
                Text(
                    text = stringResource(R.string.batch_concurrency_value, concurrency),
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
        )
        val sliderDescription = stringResource(
            R.string.batch_concurrency_content_description,
            concurrency,
        )
        Slider(
            value = concurrency.coerceIn(1, BatchParams.MAX_CONCURRENCY).toFloat(),
            onValueChange = { onConcurrencyChange(it.roundToInt()) },
            modifier = Modifier.semantics { contentDescription = sliderDescription },
            valueRange = 1f..BatchParams.MAX_CONCURRENCY.toFloat(),
            steps = BatchParams.MAX_CONCURRENCY - 2,
        )
        Text(
            text = stringResource(
                R.string.batch_concurrency_hint,
                BatchParams.MAX_CONCURRENCY,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EstimateCard(state: BatchUiState) {
    ExpressiveCard {
        ExpressiveSectionHeader(
            title = stringResource(R.string.batch_estimate_title),
            subtitle = if (state.hasEstimate) {
                stringResource(R.string.ux_batch_selected_count, state.selectedChapterCount)
            } else {
                null
            },
        )
        if (state.hasEstimate) {
            Text(
                text = stringResource(
                    R.string.batch_estimate_detail,
                    state.selectedChapterCount,
                    state.estimatedMinutes,
                    state.estimatedCostText,
                ),
                style = MaterialTheme.typography.bodyLargeEmphasized,
            )
        } else {
            Text(
                text = stringResource(R.string.batch_estimate_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ValidationCard(state: BatchUiState) {
    NoticeCard(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text = stringResource(R.string.batch_validation_title),
            style = MaterialTheme.typography.titleSmallEmphasized,
        )
        state.validationErrors.distinct().forEach { error ->
            Text(
                text = validationMessage(error, state.chapterCount),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MissingKeysCard(
    missingKeys: List<BatchKeyKind>,
    onOpenSettings: () -> Unit,
) {
    val labels = missingKeys.map { stringResource(it.labelRes()) }
    NoticeCard(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = stringResource(R.string.batch_missing_keys_title),
            style = MaterialTheme.typography.titleSmallEmphasized,
        )
        Text(
            text = stringResource(
                R.string.batch_missing_keys_message,
                labels.joinToString(separator = "、"),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        ExpressiveTonalButton(
            text = stringResource(R.string.batch_action_open_settings),
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BatchStatusCard(state: BatchUiState) {
    val stopStatus = state.stopStatus
    val startStatus = state.startStatus
    when {
        stopStatus != null -> StopStatusCard(stopStatus)
        startStatus != null -> StartStatusCard(startStatus)
    }
}

@Composable
private fun StartStatusCard(status: BatchStartStatus) {
    val isError = status is BatchStartStatus.Failure
    val containerColor = when {
        isError -> MaterialTheme.colorScheme.errorContainer
        status is BatchStartStatus.Started -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when {
        isError -> MaterialTheme.colorScheme.onErrorContainer
        status is BatchStartStatus.Started -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    NoticeCard(
        containerColor = containerColor,
        contentColor = contentColor,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text = stringResource(R.string.batch_status_title),
            style = MaterialTheme.typography.titleSmallEmphasized,
        )
        Text(
            text = when (status) {
                BatchStartStatus.Starting -> stringResource(R.string.batch_status_starting)
                is BatchStartStatus.Started -> stringResource(
                    R.string.batch_status_started,
                    status.jobId,
                )
                is BatchStartStatus.AlreadyRunning -> stringResource(
                    R.string.batch_status_already_running,
                    status.jobId,
                )
                is BatchStartStatus.Failure -> stringResource(
                    R.string.batch_status_failure,
                    status.reason,
                )
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun StopStatusCard(status: BatchStopStatus) {
    val isError = status is BatchStopStatus.Failure
    NoticeCard(
        containerColor = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (isError) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text = stringResource(R.string.batch_status_title),
            style = MaterialTheme.typography.titleSmallEmphasized,
        )
        Text(
            text = when (status) {
                BatchStopStatus.Requesting -> stringResource(
                    R.string.batch_status_requesting_stop,
                )
                is BatchStopStatus.Stopping -> stringResource(
                    R.string.batch_status_stopping,
                    status.jobId,
                )
                is BatchStopStatus.NotRunning -> stringResource(
                    R.string.batch_status_not_running,
                )
                is BatchStopStatus.Failure -> stringResource(
                    R.string.batch_status_stop_failure,
                    status.reason,
                )
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun NoticeCard(
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            content = content,
        )
    }
}

@Composable
private fun validationMessage(error: BatchValidationError, chapterCount: Int): String =
    when (error) {
        BatchValidationError.NoChapters -> stringResource(R.string.batch_error_no_chapters)
        BatchValidationError.InvalidStart -> stringResource(
            R.string.batch_error_start,
            chapterCount.coerceAtLeast(1),
        )
        BatchValidationError.InvalidEnd -> stringResource(
            R.string.batch_error_end,
            chapterCount.coerceAtLeast(1),
        )
        BatchValidationError.StartAfterEnd -> stringResource(R.string.batch_error_order)
        BatchValidationError.TooManyChapters -> stringResource(
            R.string.batch_error_too_many,
            BatchParams.DEFAULT_MAX_BATCH,
        )
        BatchValidationError.InvalidConcurrency -> stringResource(
            R.string.batch_error_concurrency,
            BatchParams.MAX_CONCURRENCY,
        )
    }

private fun BatchMode.labelRes(): Int = when (this) {
    BatchMode.Full -> R.string.batch_mode_full
    BatchMode.Parse -> R.string.batch_mode_parse
    BatchMode.Render -> R.string.batch_mode_render
}

private fun BatchMode.descriptionRes(): Int = when (this) {
    BatchMode.Full -> R.string.ux_batch_mode_full_desc
    BatchMode.Parse -> R.string.ux_batch_mode_parse_desc
    BatchMode.Render -> R.string.ux_batch_mode_render_desc
}

private fun BatchKeyKind.labelRes(): Int = when (this) {
    BatchKeyKind.Llm -> R.string.batch_missing_key_llm
    BatchKeyKind.Fish -> R.string.batch_missing_key_fish
}

// ---------------------------------------------------------------------------
// Preview
// ---------------------------------------------------------------------------

private fun previewChapters(): List<BatchChapterUi> = listOf(
    BatchChapterUi(id = 101L, number = 1, parseDone = true, audioDone = true),
    BatchChapterUi(id = 102L, number = 2, parseDone = true, audioDone = false),
    BatchChapterUi(id = 103L, number = 3, parseDone = false, audioDone = false),
    BatchChapterUi(id = 104L, number = 4, parseDone = false, audioDone = false),
    BatchChapterUi(id = 105L, number = 5, parseDone = true, audioDone = false),
)

@Preview(
    name = "BatchSheet Normal",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun BatchSheetNormalPreview() {
    AudioForgeTheme(dynamicColor = false) {
        Surface {
            BatchSheetContent(
                state = BatchUiState.initial(
                    novelId = 1L,
                    chapters = previewChapters(),
                    defaultConcurrency = 5,
                ),
                onForceChange = {},
            )
        }
    }
}

@Preview(
    name = "BatchSheet No Key",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun BatchSheetNoKeyPreview() {
    AudioForgeTheme(dynamicColor = false) {
        Surface {
            BatchSheetContent(
                state = BatchUiState.initial(
                    novelId = 1L,
                    chapters = previewChapters(),
                    hasLlmKey = false,
                    hasFishKey = false,
                ),
            )
        }
    }
}

@Preview(
    name = "BatchSheet Validation Error",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun BatchSheetValidationPreview() {
    AudioForgeTheme(dynamicColor = false) {
        Surface {
            BatchSheetContent(
                state = BatchUiState.initial(
                    novelId = 1L,
                    chapters = previewChapters(),
                ).copy(
                    startText = "4",
                    endText = "2",
                    concurrency = 9,
                ),
            )
        }
    }
}

@Preview(
    name = "BatchSheet Dark 200%",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun BatchSheetDarkPreview() {
    AudioForgeTheme(darkTheme = true, dynamicColor = false) {
        Surface {
            BatchSheetContent(
                state = BatchUiState.initial(
                    novelId = 1L,
                    chapters = previewChapters(),
                    defaultConcurrency = 5,
                ),
            )
        }
    }
}
