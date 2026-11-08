package com.estimatefaster.audioforge.feature.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.domain.jobs.BatchParams
import com.estimatefaster.audioforge.ui.components.AdaptiveFieldRow
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
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
 * 布局：标题 → 模式 → 起止范围 → 强制重跑 → 并发 → 估算 → 校验/Key/状态 → 操作。
 * 所有用户文案来自 `strings.xml`；状态中的错误枚举在这里映射成中文。
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
            .padding(horizontal = 24.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.batch_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.batch_sheet_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ModeSelector(mode = state.mode, onModeChange = onModeChange)
        RangeFields(
            state = state,
            onStartChange = onStartChange,
            onEndChange = onEndChange,
            onSelectUnfinished = onSelectUnfinished,
            onSelectAll = onSelectAll,
        )
        ForceSwitch(force = state.force, onForceChange = onForceChange)
        ConcurrencyField(
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

        Button(
            onClick = onStart,
            enabled = state.canStart,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.batch_action_start))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onStop,
                enabled = state.canStop,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.batch_action_stop))
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSelector(
    mode: BatchMode,
    onModeChange: (BatchMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.batch_mode_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            BatchMode.entries.forEachIndexed { index, candidate ->
                SegmentedButton(
                    selected = mode == candidate,
                    onClick = { onModeChange(candidate) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = BatchMode.entries.size,
                    ),
                    label = { Text(stringResource(candidate.labelRes())) },
                )
            }
        }
    }
}

@Composable
private fun RangeFields(
    state: BatchUiState,
    onStartChange: (String) -> Unit,
    onEndChange: (String) -> Unit,
    onSelectUnfinished: () -> Unit,
    onSelectAll: () -> Unit,
) {
    val rangeInvalid = state.validationErrors.any {
        it == BatchValidationError.InvalidStart ||
            it == BatchValidationError.InvalidEnd ||
            it == BatchValidationError.StartAfterEnd
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.batch_range_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        AdaptiveFieldRow(
            {
                OutlinedTextField(
                    value = state.startText,
                    onValueChange = onStartChange,
                    label = { Text(stringResource(R.string.batch_range_start)) },
                    singleLine = true,
                    isError = rangeInvalid,
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onSelectUnfinished) {
                Text(stringResource(R.string.batch_action_select_unfinished))
            }
            TextButton(onClick = onSelectAll) {
                Text(stringResource(R.string.batch_action_select_all))
            }
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
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.batch_force_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
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
private fun ConcurrencyField(
    concurrency: Int,
    onConcurrencyChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.batch_concurrency_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.batch_concurrency_value, concurrency),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.batch_estimate_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (state.hasEstimate) {
                Text(
                    text = stringResource(
                        R.string.batch_estimate_detail,
                        state.selectedChapterCount,
                        state.estimatedMinutes,
                        state.estimatedCostText,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
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
}

@Composable
private fun ValidationCard(state: BatchUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.batch_validation_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            state.validationErrors.distinct().forEach { error ->
                Text(
                    text = validationMessage(error, state.chapterCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun MissingKeysCard(
    missingKeys: List<BatchKeyKind>,
    onOpenSettings: () -> Unit,
) {
    val labels = missingKeys.map { stringResource(it.labelRes()) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.batch_missing_keys_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = stringResource(
                    R.string.batch_missing_keys_message,
                    labels.joinToString(separator = "、"),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.batch_action_open_settings))
            }
        }
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isError -> MaterialTheme.colorScheme.errorContainer
                status is BatchStartStatus.Started -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.batch_status_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
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
}

@Composable
private fun StopStatusCard(status: BatchStopStatus) {
    val isError = status is BatchStopStatus.Failure
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.batch_status_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
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
