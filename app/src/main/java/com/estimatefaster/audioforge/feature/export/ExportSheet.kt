@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.estimatefaster.audioforge.feature.export

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.ui.components.AdaptiveFieldRow
import com.estimatefaster.audioforge.ui.forge.ForgeCard
import com.estimatefaster.audioforge.ui.forge.ForgeChip
import com.estimatefaster.audioforge.ui.forge.ForgeEmpty
import com.estimatefaster.audioforge.ui.forge.ForgeError
import com.estimatefaster.audioforge.ui.forge.ForgeIcon
import com.estimatefaster.audioforge.ui.forge.ForgeIcons
import com.estimatefaster.audioforge.ui.forge.ForgeLoading
import com.estimatefaster.audioforge.ui.forge.ForgePrimaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSecondaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSectionHeader
import com.estimatefaster.audioforge.ui.forge.ForgeSheetHeader
import com.estimatefaster.audioforge.ui.forge.ForgeWavyProgress
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing
import kotlin.math.roundToInt

/**
 * 导出/分享 Sheet（P5-11 / 执行计划 §12.4 S13、§15 P5-11，UX v2 重设计）。
 *
 * 有状态入口：收集 [ExportViewModel.state] 后委托给 [ExportSheetContent]。导出中禁止
 * 下滑/返回关闭（[ExportViewModel.cancel] 是唯一出口，避免半途丢进度）；完成后把成功
 * 文件的 Uri 列表交给宿主 `onShare`。
 *
 * 集成提示：宿主用 `ExportManager.shareIntent` 分享单章，或多章时构造
 * `Intent.ACTION_SEND_MULTIPLE` + `EXTRA_STREAM` + `FLAG_GRANT_READ_URI_PERMISSION`。
 *
 * @param onDismiss 完成/取消/下滑时关闭 Sheet
 * @param onShare 分享成功导出的 Uri 列表（非空；按章节顺序）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    viewModel: ExportViewModel,
    onDismiss: () -> Unit,
    onShare: (List<Uri>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exporting = rememberUpdatedState(state.isExporting)
    // 回调里读最新的导出态；lambda 本身保持稳定，避免 bottom sheet 状态被重建。
    val confirmDismiss = remember(exporting) {
        { target: SheetValue -> !(exporting.value && target == SheetValue.Hidden) }
    }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = confirmDismiss,
    )
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnShare by rememberUpdatedState(onShare)

    ModalBottomSheet(
        onDismissRequest = { if (!exporting.value) currentOnDismiss() },
        modifier = modifier,
        sheetState = sheetState,
    ) {
        ExportSheetContent(
            state = state,
            onRangeChange = viewModel::setRange,
            onStartTextChange = viewModel::setStartText,
            onEndTextChange = viewModel::setEndText,
            onStart = viewModel::start,
            onCancel = viewModel::cancel,
            onRetryFailed = viewModel::retryFailed,
            onRetryLoad = viewModel::load,
            onShare = { uris -> currentOnShare(uris) },
            onDismiss = { if (!exporting.value) currentOnDismiss() },
        )
    }
}

/**
 * 无状态 Sheet 内容；Preview 与 UI 测试直接渲染它。
 *
 * 布局对应 `docs/prototype/forge-framework.html` 的 Export Sheet：
 * - 选择：导出范围 chips → 起止章输入（仅起止范围）→ 章节列表 → 预估 → 开始导出；
 * - 导出中：Wavy 进度 → 百分比 / 当前章 / 成功失败计数 → 取消；
 * - 结果：成功文件 / 失败列表与重试 → 完成 / 分享。
 *
 * 加载、加载失败与“无已完成章节”空态优先于阶段内容；文案全部来自字符串资源。
 * 所有按钮都是「Lucide 图标 + 文案」或纯图标操作，没有无图标可点击文字。
 */
@Composable
fun ExportSheetContent(
    state: ExportUiState,
    modifier: Modifier = Modifier,
    onRangeChange: (ExportRange) -> Unit = {},
    onStartTextChange: (String) -> Unit = {},
    onEndTextChange: (String) -> Unit = {},
    onStart: () -> Unit = {},
    onCancel: () -> Unit = {},
    onRetryFailed: () -> Unit = {},
    onRetryLoad: () -> Unit = {},
    onShare: (List<Uri>) -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    val loadError = state.loadError
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.spacing.xl)
            .padding(top = MaterialTheme.spacing.sm, bottom = MaterialTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg),
    ) {
        ForgeSheetHeader(
            icon = ForgeIcons.Download,
            title = stringResource(R.string.export_sheet_title),
            subtitle = stringResource(R.string.export_sheet_subtitle),
            onClose = onDismiss,
        )

        when {
            state.loading -> SheetStateBox {
                ForgeLoading(label = stringResource(R.string.export_loading))
            }

            loadError != null -> SheetStateBox {
                ForgeError(
                    message = stringResource(R.string.export_load_error, loadError),
                    onRetry = onRetryLoad,
                    retryLabel = stringResource(R.string.export_action_retry_load),
                )
            }

            state.isEmpty -> SheetStateBox {
                ForgeEmpty(
                    icon = ForgeIcons.Inbox,
                    title = stringResource(R.string.export_empty_title),
                    description = stringResource(R.string.export_empty_description),
                    actionIcon = ForgeIcons.Refresh,
                    actionLabel = stringResource(R.string.export_action_retry_load),
                    onAction = onRetryLoad,
                )
            }

            else -> when (state.phase) {
                ExportPhase.Selection -> SelectionSection(
                    state = state,
                    onRangeChange = onRangeChange,
                    onStartTextChange = onStartTextChange,
                    onEndTextChange = onEndTextChange,
                    onStart = onStart,
                    onDismiss = onDismiss,
                )

                ExportPhase.Exporting -> ExportingSection(
                    state = state,
                    onCancel = onCancel,
                )

                ExportPhase.Result -> ResultSection(
                    state = state,
                    onRetryFailed = onRetryFailed,
                    onShare = onShare,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

/** 空/载入/错误态在可滚动 Sheet 里给一个稳定的最小高度，避免居中内容塌陷。 */
@Composable
private fun SheetStateBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 280.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun SelectionSection(
    state: ExportUiState,
    onRangeChange: (ExportRange) -> Unit,
    onStartTextChange: (String) -> Unit,
    onEndTextChange: (String) -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    ForgeSectionHeader(
        title = stringResource(R.string.export_range_title),
        subtitle = stringResource(R.string.export_range_subtitle),
    )
    RangeSelector(range = state.range, onRangeChange = onRangeChange)

    if (state.range == ExportRange.ChapterRange) {
        RangeFields(
            state = state,
            onStartTextChange = onStartTextChange,
            onEndTextChange = onEndTextChange,
        )
    }

    val selectionError = state.selectionError
    if (
        selectionError != null &&
        selectionError != ExportValidationError.NoChapters &&
        selectionError != ExportValidationError.NoCompleted
    ) {
        ValidationCard(message = selectionMessage(selectionError, state.chapters.size))
    }

    ForgeSectionHeader(
        title = stringResource(R.string.export_list_title),
        subtitle = stringResource(
            R.string.export_list_subtitle,
            state.chapters.size,
            state.completedChapters.size,
        ),
    )
    val selectedIds = state.selectedChapters.map { it.chapterIdx }.toSet()
    ForgeCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(MaterialTheme.spacing.sm),
    ) {
        state.chapters.take(MAX_LIST_ROWS).forEach { chapter ->
            ChapterRow(
                chapter = chapter,
                selected = chapter.chapterIdx in selectedIds,
            )
        }
    }
    if (state.chapters.size > MAX_LIST_ROWS) {
        Text(
            text = stringResource(
                R.string.export_list_truncated,
                MAX_LIST_ROWS,
                state.chapters.size,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    ExportSummaryCard(state)

    val startLabel = if (state.selectedCount > 0) {
        stringResource(R.string.export_action_start_count, state.selectedCount)
    } else {
        stringResource(R.string.export_action_start)
    }
    ForgePrimaryAction(
        icon = ForgeIcons.Download,
        label = startLabel,
        onClick = onStart,
        modifier = Modifier.fillMaxWidth(),
        enabled = state.canStart,
    )
    ForgeSecondaryAction(
        icon = ForgeIcons.Close,
        label = stringResource(R.string.export_action_close),
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RangeSelector(
    range: ExportRange,
    onRangeChange: (ExportRange) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        ExportRange.entries.forEach { candidate ->
            ForgeChip(
                label = stringResource(candidate.labelRes()),
                selected = range == candidate,
                onClick = { onRangeChange(candidate) },
                icon = candidate.iconRes(),
            )
        }
    }
}

@Composable
private fun RangeFields(
    state: ExportUiState,
    onStartTextChange: (String) -> Unit,
    onEndTextChange: (String) -> Unit,
) {
    val error = state.selectionError
    val startInvalid = error == ExportValidationError.InvalidStart ||
        error == ExportValidationError.StartAfterEnd
    val endInvalid = error == ExportValidationError.InvalidEnd ||
        error == ExportValidationError.StartAfterEnd

    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        AdaptiveFieldRow(
            {
                OutlinedTextField(
                    value = state.startText,
                    onValueChange = onStartTextChange,
                    label = { Text(stringResource(R.string.export_range_start)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    isError = startInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            {
                OutlinedTextField(
                    value = state.endText,
                    onValueChange = onEndTextChange,
                    label = { Text(stringResource(R.string.export_range_end)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    isError = endInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        Text(
            text = stringResource(R.string.export_range_hint, state.chapters.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChapterRow(
    chapter: ExportChapterUi,
    selected: Boolean,
) {
    val duration = if (chapter.durationMs > 0L) {
        formatExportDuration(chapter.durationMs)
    } else {
        stringResource(R.string.export_value_unknown)
    }
    val size = if (chapter.sizeBytes > 0L) {
        formatExportFileSize(chapter.sizeBytes)
    } else {
        stringResource(R.string.export_value_unknown)
    }
    val notReadyLabel = stringResource(R.string.export_chapter_not_ready)
    val selectedLabel = stringResource(R.string.ux_v2_export_chapter_selected)
    val leadingColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val leadingTint = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = MaterialTheme.spacing.sm,
                vertical = MaterialTheme.spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = MaterialTheme.shapes.medium,
            color = leadingColor,
            contentColor = leadingTint,
        ) {
            Box(contentAlignment = Alignment.Center) {
                ForgeIcon(
                    icon = if (chapter.audioDone) ForgeIcons.FileText else ForgeIcons.Clock,
                    contentDescription = null,
                    tint = leadingTint,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chapter.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.export_chapter_meta,
                    chapter.number,
                    duration,
                    size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!chapter.audioDone) {
            ForgeIcon(
                icon = ForgeIcons.AlertCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = notReadyLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (selected) {
            ForgeIcon(
                icon = ForgeIcons.CheckCircle,
                contentDescription = selectedLabel,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ExportSummaryCard(state: ExportUiState) {
    ForgeCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    ForgeIcon(
                        icon = ForgeIcons.Storage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs),
            ) {
                Text(
                    text = stringResource(R.string.export_summary_title),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Text(
                    text = if (state.selectedCount > 0) {
                        stringResource(
                            R.string.export_summary,
                            state.selectedCount,
                            formatExportDuration(state.selectedDurationMs),
                            formatExportFileSize(state.selectedSizeBytes),
                        )
                    } else {
                        stringResource(R.string.export_summary_empty)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ValidationCard(message: String) {
    ForgeCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentPadding = PaddingValues(MaterialTheme.spacing.md),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ForgeIcon(
                icon = ForgeIcons.AlertCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun ExportingSection(
    state: ExportUiState,
    onCancel: () -> Unit,
) {
    ForgeSectionHeader(
        title = stringResource(R.string.export_progress_title),
        subtitle = stringResource(
            R.string.export_progress_count,
            state.completed,
            state.total,
        ),
    )
    val percentLabel = stringResource(
        R.string.ux_v2_export_progress_percent,
        (state.progress * 100).roundToInt(),
    )
    ForgeCard(modifier = Modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            Text(
                text = percentLabel,
                style = MaterialTheme.typography.headlineSmallEmphasized,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CountLabel(
                    icon = ForgeIcons.CheckCircle,
                    text = stringResource(R.string.export_progress_succeeded, state.succeeded.size),
                    tint = MaterialTheme.colorScheme.primary,
                )
                CountLabel(
                    icon = ForgeIcons.AlertCircle,
                    text = stringResource(R.string.export_progress_failed, state.failures.size),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        ForgeWavyProgress(
            progress = state.progress,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = percentLabel
                    liveRegion = LiveRegionMode.Polite
                },
        )
        val currentTitle = state.currentChapterTitle
        if (currentTitle != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ForgeIcon(
                    icon = ForgeIcons.AudioLines,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.export_progress_current, currentTitle),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            ForgeIcon(
                icon = ForgeIcons.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.export_cancel_note),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    ForgeSecondaryAction(
        icon = ForgeIcons.Close,
        label = stringResource(R.string.export_action_cancel_export),
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth(),
        danger = true,
    )
}

@Composable
private fun CountLabel(
    @DrawableRes icon: Int,
    text: String,
    tint: androidx.compose.ui.graphics.Color,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ForgeIcon(icon = icon, contentDescription = null, tint = tint)
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
    }
}

@Composable
private fun ResultSection(
    state: ExportUiState,
    onRetryFailed: () -> Unit,
    onShare: (List<Uri>) -> Unit,
    onDismiss: () -> Unit,
) {
    ForgeSectionHeader(
        title = stringResource(R.string.export_result_title),
        subtitle = stringResource(
            R.string.export_result_summary,
            state.succeeded.size,
            state.failures.size,
        ),
    )

    if (state.cancelled) {
        ForgeCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentPadding = PaddingValues(MaterialTheme.spacing.md),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ForgeIcon(
                    icon = ForgeIcons.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = stringResource(R.string.export_result_cancelled),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ForgeIcon(
            icon = ForgeIcons.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.export_result_success_title),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmallEmphasized,
        )
        Text(
            text = stringResource(R.string.ux_v2_export_result_files_count, state.succeeded.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (state.succeeded.isEmpty()) {
        Text(
            text = stringResource(R.string.export_result_no_success),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        ForgeCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(MaterialTheme.spacing.sm),
        ) {
            state.succeeded.take(MAX_LIST_ROWS).forEach { file ->
                ExportedFileRow(file = file)
            }
        }
        if (state.succeeded.size > MAX_LIST_ROWS) {
            Text(
                text = stringResource(
                    R.string.export_list_truncated,
                    MAX_LIST_ROWS,
                    state.succeeded.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (state.failures.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ForgeIcon(
                icon = ForgeIcons.AlertCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.export_result_failure_title),
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.error,
            )
        }
        ForgeCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentPadding = PaddingValues(MaterialTheme.spacing.sm),
        ) {
            state.failures.take(MAX_LIST_ROWS).forEach { failure ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MaterialTheme.spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                    verticalAlignment = Alignment.Top,
                ) {
                    ForgeIcon(
                        icon = ForgeIcons.AlertCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = stringResource(
                            R.string.export_result_failure_line,
                            failure.title,
                            failure.message,
                        ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
        ForgeSecondaryAction(
            icon = ForgeIcons.Refresh,
            label = stringResource(R.string.export_action_retry_failed),
            onClick = onRetryFailed,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canRetryFailed,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
    ) {
        ForgeSecondaryAction(
            icon = ForgeIcons.Check,
            label = stringResource(R.string.export_action_done),
            onClick = onDismiss,
            modifier = Modifier.weight(1f),
        )
        ForgePrimaryAction(
            icon = ForgeIcons.Share,
            label = stringResource(R.string.export_action_share),
            onClick = { onShare(state.shareUris.map { uri -> Uri.parse(uri) }) },
            modifier = Modifier.weight(1f),
            enabled = state.canShare,
        )
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        ForgeIcon(
            icon = ForgeIcons.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.export_result_share_hint),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExportedFileRow(file: ExportedFile) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = MaterialTheme.spacing.sm,
                vertical = MaterialTheme.spacing.sm,
            ),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ForgeIcon(
            icon = ForgeIcons.FileText,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = file.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun selectionMessage(error: ExportValidationError, chapterCount: Int): String =
    when (error) {
        ExportValidationError.NoChapters ->
            stringResource(R.string.export_error_no_chapters)

        ExportValidationError.NoCompleted ->
            stringResource(R.string.export_error_no_completed)

        ExportValidationError.CurrentChapterNotCompleted ->
            stringResource(R.string.export_range_current_missing)

        ExportValidationError.InvalidStart -> stringResource(
            R.string.export_error_start,
            chapterCount.coerceAtLeast(1),
        )

        ExportValidationError.InvalidEnd -> stringResource(
            R.string.export_error_end,
            chapterCount.coerceAtLeast(1),
        )

        ExportValidationError.StartAfterEnd ->
            stringResource(R.string.export_error_order)

        ExportValidationError.NoCompletedInRange ->
            stringResource(R.string.export_range_empty)
    }

private fun ExportRange.labelRes(): Int = when (this) {
    ExportRange.AllCompleted -> R.string.export_range_all
    ExportRange.CurrentChapter -> R.string.export_range_current
    ExportRange.ChapterRange -> R.string.export_range_custom
}

@DrawableRes
private fun ExportRange.iconRes(): Int = when (this) {
    ExportRange.AllCompleted -> ForgeIcons.BookOpen
    ExportRange.CurrentChapter -> ForgeIcons.FileText
    ExportRange.ChapterRange -> ForgeIcons.List
}

private const val MAX_LIST_ROWS = 50

// ---------------------------------------------------------------------------
// Preview
// ---------------------------------------------------------------------------

private fun previewChapters(): List<ExportChapterUi> = listOf(
    ExportChapterUi(
        chapterIdx = 0,
        title = "第一章 山雨欲来",
        audioDone = true,
        durationMs = 754_000L,
        sizeBytes = 6_100_000L,
    ),
    ExportChapterUi(
        chapterIdx = 1,
        title = "第二章 旧城灯火",
        audioDone = true,
        durationMs = 621_000L,
        sizeBytes = 5_020_000L,
    ),
    ExportChapterUi(
        chapterIdx = 2,
        title = "第三章 长夜",
        audioDone = true,
        durationMs = 903_000L,
        sizeBytes = 7_300_000L,
    ),
    ExportChapterUi(
        chapterIdx = 3,
        title = "第四章 归途",
        audioDone = false,
    ),
)

private fun previewSucceeded(): List<ExportedFile> = listOf(
    ExportedFile(
        chapterIdx = 0,
        title = "第一章 山雨欲来",
        displayName = "雾城旧事-第001章.m4a",
        uri = "content://media/external/audio/media/101",
    ),
    ExportedFile(
        chapterIdx = 1,
        title = "第二章 旧城灯火",
        displayName = "雾城旧事-第002章.m4a",
        uri = "content://media/external/audio/media/102",
    ),
)

@Preview(
    name = "ExportSheet Selection",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun ExportSheetSelectionPreview() {
    AudioForgeTheme(dynamicColor = false) {
        Surface {
            ExportSheetContent(
                state = ExportUiState(
                    loading = false,
                    chapters = previewChapters(),
                    currentChapterIdx = 1,
                ),
            )
        }
    }
}

@Preview(
    name = "ExportSheet Exporting",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun ExportSheetExportingPreview() {
    AudioForgeTheme(dynamicColor = false) {
        Surface {
            ExportSheetContent(
                state = ExportUiState(
                    loading = false,
                    chapters = previewChapters(),
                    currentChapterIdx = 1,
                    phase = ExportPhase.Exporting,
                    total = 3,
                    completed = 2,
                    currentChapterTitle = "第三章 长夜",
                    succeeded = previewSucceeded(),
                ),
            )
        }
    }
}

@Preview(
    name = "ExportSheet Result",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun ExportSheetResultPreview() {
    AudioForgeTheme(dynamicColor = false) {
        Surface {
            ExportSheetContent(
                state = ExportUiState(
                    loading = false,
                    chapters = previewChapters(),
                    currentChapterIdx = 1,
                    phase = ExportPhase.Result,
                    total = 3,
                    completed = 3,
                    succeeded = previewSucceeded(),
                    failures = listOf(
                        ExportFailure(
                            chapterIdx = 2,
                            title = "第三章 长夜",
                            message = "章节音频不存在：audio/1/chapter_003.m4a",
                        ),
                    ),
                ),
            )
        }
    }
}

@Preview(
    name = "ExportSheet Empty",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun ExportSheetEmptyPreview() {
    AudioForgeTheme(dynamicColor = false) {
        Surface {
            ExportSheetContent(
                state = ExportUiState(
                    loading = false,
                    chapters = listOf(
                        ExportChapterUi(chapterIdx = 0, title = "第一章 山雨欲来"),
                    ),
                ),
            )
        }
    }
}
