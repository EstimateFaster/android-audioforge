@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.estimatefaster.audioforge.feature.project

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.domain.jobs.BatchParams
import com.estimatefaster.audioforge.ui.forge.ForgeCard
import com.estimatefaster.audioforge.ui.forge.ForgeChip
import com.estimatefaster.audioforge.ui.forge.ForgeIcon
import com.estimatefaster.audioforge.ui.forge.ForgeIconAction
import com.estimatefaster.audioforge.ui.forge.ForgeIcons
import com.estimatefaster.audioforge.ui.forge.ForgePrimaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSecondaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSheetHeader
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing

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
 * 结构对齐 Forge Framework 的批量生成 Sheet：
 * SheetHeader → 生成范围（chips + 起止章）→ 处理模式（chips）→ 选项（强制重跑 /
 * 并发 stepper）→ 估算 → 校验 / Key / 状态 → 唯一主操作 + 次级停止。
 *
 * 校验、Key 拦截与开始/停止状态机仍由 [BatchUiState] 的派生属性决定，
 * 这里只把枚举映射成新文案，不复制业务判断。
 */
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
            .padding(horizontal = MaterialTheme.spacing.lg)
            .padding(top = MaterialTheme.spacing.xs, bottom = MaterialTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg),
    ) {
        ForgeSheetHeader(
            icon = ForgeIcons.Sparkles,
            title = stringResource(R.string.ux2_batch_title),
            onClose = onDismiss,
            subtitle = stringResource(R.string.ux2_batch_subtitle),
        )

        BatchRangeSection(
            state = state,
            onStartChange = onStartChange,
            onEndChange = onEndChange,
            onSelectUnfinished = onSelectUnfinished,
            onSelectAll = onSelectAll,
        )

        BatchModeSection(
            mode = state.mode,
            onModeChange = onModeChange,
        )

        BatchOptionsSection(
            state = state,
            onForceChange = onForceChange,
            onConcurrencyChange = onConcurrencyChange,
        )

        BatchEstimateCard(state = state)

        if (state.validationErrors.isNotEmpty()) {
            BatchValidationCard(state = state)
        }
        if (state.missingKeys.isNotEmpty()) {
            BatchMissingKeysCard(
                missingKeys = state.missingKeys,
                onOpenSettings = onOpenSettings,
            )
        }
        BatchStatusCard(state = state)

        ForgePrimaryAction(
            icon = ForgeIcons.Sparkles,
            label = stringResource(R.string.ux2_batch_action_start),
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canStart,
            loading = state.starting,
        )
        ForgeSecondaryAction(
            icon = ForgeIcons.Square,
            label = stringResource(R.string.batch_action_stop),
            onClick = onStop,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canStop,
        )
        Text(
            text = stringResource(R.string.ux2_batch_note),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BatchRangeSection(
    state: BatchUiState,
    onStartChange: (String) -> Unit,
    onEndChange: (String) -> Unit,
    onSelectUnfinished: () -> Unit,
    onSelectAll: () -> Unit,
) {
    val allSelected = state.chapterCount > 0 &&
        state.startChapter == 1 &&
        state.endChapter == state.chapterCount
    val unfinished = state.chapters.filterNot { it.isComplete }
    val unfinishedFirst = unfinished.minOfOrNull { it.number }
    val unfinishedLast = unfinished.maxOfOrNull { it.number }
    val unfinishedSelected = !allSelected &&
        unfinishedFirst != null &&
        unfinishedLast != null &&
        state.startChapter == unfinishedFirst &&
        state.endChapter == unfinishedLast
    val rangeInvalid = state.validationErrors.any {
        it == BatchValidationError.InvalidStart ||
            it == BatchValidationError.InvalidEnd ||
            it == BatchValidationError.StartAfterEnd
    }

    BatchSection(
        icon = ForgeIcons.List,
        title = stringResource(R.string.ux2_batch_section_range),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            ForgeChip(
                label = stringResource(R.string.ux2_batch_range_all),
                selected = allSelected,
                onClick = onSelectAll,
                icon = ForgeIcons.List,
            )
            ForgeChip(
                label = stringResource(R.string.ux2_batch_range_unfinished),
                selected = unfinishedSelected,
                onClick = onSelectUnfinished,
                icon = ForgeIcons.Clock,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            OutlinedTextField(
                value = state.startText,
                onValueChange = onStartChange,
                label = { Text(stringResource(R.string.batch_range_start)) },
                singleLine = true,
                isError = rangeInvalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.endText,
                onValueChange = onEndChange,
                label = { Text(stringResource(R.string.batch_range_end)) },
                singleLine = true,
                isError = rangeInvalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = MaterialTheme.shapes.large,
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
            text = stringResource(R.string.ux2_batch_selected_count, state.selectedChapterCount),
            style = MaterialTheme.typography.labelMediumEmphasized,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun BatchModeSection(
    mode: BatchMode,
    onModeChange: (BatchMode) -> Unit,
) {
    BatchSection(
        icon = ForgeIcons.Sparkles,
        title = stringResource(R.string.ux2_batch_section_mode),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            BatchMode.entries.forEach { candidate ->
                ForgeChip(
                    label = stringResource(candidate.labelRes()),
                    selected = mode == candidate,
                    onClick = { onModeChange(candidate) },
                    icon = candidate.iconRes(),
                )
            }
        }
        Text(
            text = stringResource(mode.hintRes()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BatchOptionsSection(
    state: BatchUiState,
    onForceChange: (Boolean) -> Unit,
    onConcurrencyChange: (Int) -> Unit,
) {
    BatchSection(
        icon = ForgeIcons.Sliders,
        title = stringResource(R.string.ux2_batch_section_options),
    ) {
        ForgeCard(contentPadding = PaddingValues(MaterialTheme.spacing.md)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(
                        value = state.force,
                        role = Role.Switch,
                        onValueChange = onForceChange,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
            ) {
                OptionIcon(icon = ForgeIcons.Refresh)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.ux2_batch_force_title),
                        style = MaterialTheme.typography.titleSmallEmphasized,
                    )
                    Text(
                        text = stringResource(R.string.ux2_batch_force_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.force, onCheckedChange = null)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.spacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
            ) {
                OptionIcon(icon = ForgeIcons.Gauge)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.ux2_batch_concurrency_title),
                        style = MaterialTheme.typography.titleSmallEmphasized,
                    )
                    Text(
                        text = stringResource(R.string.ux2_batch_concurrency_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                ForgeIconAction(
                    icon = ForgeIcons.Minus,
                    contentDescription = stringResource(R.string.ux2_batch_concurrency_decrease),
                    onClick = {
                        if (state.concurrency > 1) {
                            onConcurrencyChange(state.concurrency - 1)
                        }
                    },
                )
                Text(
                    text = stringResource(R.string.batch_concurrency_value, state.concurrency),
                    modifier = Modifier.widthIn(min = 48.dp),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    textAlign = TextAlign.Center,
                )
                ForgeIconAction(
                    icon = ForgeIcons.Plus,
                    contentDescription = stringResource(R.string.ux2_batch_concurrency_increase),
                    onClick = {
                        if (state.concurrency < BatchParams.MAX_CONCURRENCY) {
                            onConcurrencyChange(state.concurrency + 1)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun OptionIcon(@DrawableRes icon: Int) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.size(40.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ForgeIcon(
                icon = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun BatchEstimateCard(state: BatchUiState) {
    ForgeCard(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            ForgeIcon(
                icon = ForgeIcons.Clock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.batch_estimate_title),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                if (state.hasEstimate) {
                    Text(
                        text = stringResource(
                            R.string.batch_estimate_detail,
                            state.selectedChapterCount,
                            state.estimatedMinutes,
                            state.estimatedCostText,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.ux2_batch_estimate_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.batch_estimate_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BatchValidationCard(state: BatchUiState) {
    ForgeCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            ForgeIcon(
                icon = ForgeIcons.AlertCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.batch_validation_title),
                    style = MaterialTheme.typography.titleSmallEmphasized,
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
}

@Composable
private fun BatchMissingKeysCard(
    missingKeys: List<BatchKeyKind>,
    onOpenSettings: () -> Unit,
) {
    val separator = stringResource(R.string.ux2_batch_missing_keys_separator)
    val labels = missingKeys.map { stringResource(it.labelRes()) }
    ForgeCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            ForgeIcon(
                icon = ForgeIcons.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
            ) {
                Text(
                    text = stringResource(R.string.batch_missing_keys_title),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = stringResource(
                        R.string.batch_missing_keys_message,
                        labels.joinToString(separator = separator),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                ForgeSecondaryAction(
                    icon = ForgeIcons.Settings,
                    label = stringResource(R.string.batch_action_open_settings),
                    onClick = onOpenSettings,
                )
            }
        }
    }
}

@Composable
private fun BatchStatusCard(state: BatchUiState) {
    val stopStatus = state.stopStatus
    val startStatus = state.startStatus
    when {
        stopStatus != null -> BatchStopStatusCard(status = stopStatus)
        startStatus != null -> BatchStartStatusCard(status = startStatus)
    }
}

@Composable
private fun BatchStartStatusCard(status: BatchStartStatus) {
    val isError = status is BatchStartStatus.Failure
    val icon = when (status) {
        BatchStartStatus.Starting -> ForgeIcons.Loader
        is BatchStartStatus.Started -> ForgeIcons.CheckCircle
        is BatchStartStatus.AlreadyRunning -> ForgeIcons.AlertCircle
        is BatchStartStatus.Failure -> ForgeIcons.ErrorCircle
    }
    val container = when {
        isError -> MaterialTheme.colorScheme.errorContainer
        status is BatchStartStatus.Started -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = when {
        isError -> MaterialTheme.colorScheme.onErrorContainer
        status is BatchStartStatus.Started -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    BatchStatusRow(
        icon = icon,
        container = container,
        content = content,
        message = when (status) {
            BatchStartStatus.Starting -> stringResource(R.string.batch_status_starting)
            is BatchStartStatus.Started -> stringResource(R.string.batch_status_started, status.jobId)
            is BatchStartStatus.AlreadyRunning -> {
                stringResource(R.string.batch_status_already_running, status.jobId)
            }
            is BatchStartStatus.Failure -> stringResource(R.string.batch_status_failure, status.reason)
        },
    )
}

@Composable
private fun BatchStopStatusCard(status: BatchStopStatus) {
    val isError = status is BatchStopStatus.Failure
    val icon = when (status) {
        BatchStopStatus.Requesting -> ForgeIcons.Loader
        is BatchStopStatus.Stopping -> ForgeIcons.Square
        is BatchStopStatus.NotRunning -> ForgeIcons.Info
        is BatchStopStatus.Failure -> ForgeIcons.ErrorCircle
    }
    val container = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val content = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    BatchStatusRow(
        icon = icon,
        container = container,
        content = content,
        message = when (status) {
            BatchStopStatus.Requesting -> stringResource(R.string.batch_status_requesting_stop)
            is BatchStopStatus.Stopping -> stringResource(R.string.batch_status_stopping, status.jobId)
            is BatchStopStatus.NotRunning -> stringResource(R.string.batch_status_not_running)
            is BatchStopStatus.Failure -> {
                stringResource(R.string.batch_status_stop_failure, status.reason)
            }
        },
    )
}

@Composable
private fun BatchStatusRow(
    @DrawableRes icon: Int,
    container: Color,
    content: Color,
    message: String,
) {
    ForgeCard(containerColor = container) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            ForgeIcon(
                icon = icon,
                contentDescription = null,
                tint = content,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.batch_status_title),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = content,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = content,
                )
            }
        }
    }
}

@Composable
private fun BatchSection(
    @DrawableRes icon: Int,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
        ) {
            ForgeIcon(
                icon = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmallEmphasized,
            )
        }
        content()
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

@StringRes
private fun BatchMode.labelRes(): Int = when (this) {
    BatchMode.Full -> R.string.ux2_batch_mode_full
    BatchMode.Parse -> R.string.ux2_batch_mode_parse
    BatchMode.Render -> R.string.ux2_batch_mode_render
}

@DrawableRes
private fun BatchMode.iconRes(): Int = when (this) {
    BatchMode.Full -> ForgeIcons.Sparkles
    BatchMode.Parse -> ForgeIcons.FileText
    BatchMode.Render -> ForgeIcons.AudioLines
}

@StringRes
private fun BatchMode.hintRes(): Int = when (this) {
    BatchMode.Full -> R.string.ux2_batch_mode_full_hint
    BatchMode.Parse -> R.string.ux2_batch_mode_parse_hint
    BatchMode.Render -> R.string.ux2_batch_mode_render_hint
}

private fun BatchKeyKind.labelRes(): Int = when (this) {
    BatchKeyKind.Llm -> R.string.batch_missing_key_llm
    BatchKeyKind.Fish -> R.string.batch_missing_key_fish
}

// ---------------------------------------------------------------------------
// Preview：正常 / 缺 Key / 校验错误。示例数据只在 Preview 区使用。
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
