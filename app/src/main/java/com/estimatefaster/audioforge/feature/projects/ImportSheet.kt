@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.estimatefaster.audioforge.feature.projects

import android.content.res.Configuration
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.ui.components.ExpressiveCard
import com.estimatefaster.audioforge.ui.components.ExpressiveOutlinedButton
import com.estimatefaster.audioforge.ui.components.ExpressivePrimaryButton
import com.estimatefaster.audioforge.ui.components.ExpressiveTextButton
import com.estimatefaster.audioforge.ui.components.ExpressiveTonalButton
import com.estimatefaster.audioforge.ui.components.ExpressiveWavyProgress
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing

/**
 * 导入 Sheet 的有状态入口（P4-04 / 执行计划 §12.4 S03）。
 *
 * 只负责：
 * 1. 创建 SAF `OpenDocument("text/plain")` launcher，把选中的 uri 交给
 *    [ImportViewModel.onFilePicked]；
 * 2. 收集 [ImportViewModel.events]：成功 → `onImported(novelId)`；重名选「打开已有」
 *    → `onOpenExisting(id)`，两者都会关闭 Sheet；
 * 3. 关闭（下滑/取消/成功）时取消在途导入，用例负责清理半成品文件。
 *
 * @param onDismiss 关闭 Sheet（由调用方控制可见性）
 * @param onImported 导入成功后进入新项目
 * @param onOpenExisting 重名时打开已有项目
 */
@Composable
fun ImportSheet(
    viewModel: ImportViewModel,
    onDismiss: () -> Unit,
    onImported: (Long) -> Unit,
    onOpenExisting: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        // 用户点系统返回/取消时 uri 为 null：保留当前选择，不做任何状态变更。
        if (uri != null) viewModel.onFilePicked(uri.toString())
    }

    // 回调可能在重组之间变化；用 rememberUpdatedState 让长生命周期的事件收集器
    // 始终调用最新实例。
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnImported by rememberUpdatedState(onImported)
    val currentOnOpenExisting by rememberUpdatedState(onOpenExisting)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ImportEvent.Imported -> currentOnImported(event.novelId)
                is ImportEvent.OpenExisting -> currentOnOpenExisting(event.novelId)
            }
            viewModel.cancel()
            currentOnDismiss()
        }
    }

    val closeSheet: () -> Unit = {
        viewModel.cancel()
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = closeSheet,
        modifier = modifier,
        sheetState = sheetState,
        // Expressive 容器：超大圆角 + tonal 表面 + 默认拖拽条。
        shape = MaterialTheme.shapes.extraExtraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        ImportSheetContent(
            state = state,
            onPickFile = { launcher.launch(arrayOf("text/plain")) },
            onStartImport = viewModel::startImport,
            onRetry = viewModel::retry,
            onChooseEncoding = viewModel::chooseEncoding,
            onOpenExisting = viewModel::openExistingProject,
            onDismissDuplicate = viewModel::dismissDuplicate,
            onCancel = closeSheet,
        )
    }
}

/**
 * 无状态 Sheet 内容；Preview 与将来 UI 测试都直接渲染它。
 *
 * 状态 → 布局：
 * - `Idle`：说明 + 选择文件；
 * - `Selected`：文件卡 + 导入 / 换文件；
 * - `Importing`：进度卡 + 取消；
 * - `NeedsEncoding`：候选编码卡片，选中后重开源流重试；
 * - `Duplicate`：重名卡片，「打开已有项目」是唯一主行动；
 * - `Error`：错误卡片 + 重试或重新选择；
 * - `Success`：成功卡片（正常路径会立即通过事件关闭）。
 *
 * 内容可纵向滚动，200% 字体下所有状态和按钮都可达。
 */
@Composable
fun ImportSheetContent(
    state: ImportUiState,
    modifier: Modifier = Modifier,
    onPickFile: () -> Unit = {},
    onStartImport: () -> Unit = {},
    onRetry: () -> Unit = {},
    onChooseEncoding: (String) -> Unit = {},
    onOpenExisting: () -> Unit = {},
    onDismissDuplicate: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.xl)
            .padding(top = spacing.sm, bottom = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                text = stringResource(R.string.import_sheet_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmallEmphasized,
            )
            Text(
                text = stringResource(R.string.import_sheet_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.selection?.let { selection -> SelectedFileCard(selection = selection) }

        when (state.phase) {
            ImportPhase.Idle -> IdleStage(onPickFile = onPickFile)

            ImportPhase.Selected -> SelectedStage(
                canStartImport = state.canStartImport,
                onStartImport = onStartImport,
                onPickFile = onPickFile,
            )

            ImportPhase.Importing -> {
                ImportProgress(state = state)
                CancelAction(onCancel = onCancel)
            }

            ImportPhase.NeedsEncoding -> {
                EncodingChooser(
                    state = state,
                    onChooseEncoding = onChooseEncoding,
                )
                CancelAction(onCancel = onCancel)
            }

            ImportPhase.Duplicate -> DuplicateStage(
                duplicate = state.duplicate,
                onOpenExisting = onOpenExisting,
                onDismissDuplicate = onDismissDuplicate,
            )

            ImportPhase.Error -> ErrorStage(
                state = state,
                onRetry = onRetry,
                onPickFile = onPickFile,
                onCancel = onCancel,
            )

            ImportPhase.Success -> SuccessStage()
        }
    }
}

/** Idle：说明 + 唯一主按钮。 */
@Composable
private fun IdleStage(
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ExpressiveCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.import_file_type_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExpressivePrimaryButton(
            text = stringResource(R.string.import_pick_file),
            onClick = onPickFile,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Selected：确认前校验通过，主行动是「导入」，换文件降级为 tonal。 */
@Composable
private fun SelectedStage(
    canStartImport: Boolean,
    onStartImport: () -> Unit,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ExpressiveCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.import_file_type_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExpressivePrimaryButton(
            text = stringResource(R.string.import_action_start),
            onClick = onStartImport,
            modifier = Modifier.fillMaxWidth(),
            enabled = canStartImport,
        )
        ExpressiveTonalButton(
            text = stringResource(R.string.import_pick_again),
            onClick = onPickFile,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 已选文件摘要；大小未知时显示「未知」，不伪造数字。 */
@Composable
private fun SelectedFileCard(
    selection: ImportFileSelection,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sizeText = selection.sizeBytes
        ?.takeIf { it >= 0L }
        ?.let { bytes -> Formatter.formatFileSize(context, bytes) }
        ?: stringResource(R.string.import_size_unknown)

    ExpressiveCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.import_selected_file),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = selection.displayName,
            style = MaterialTheme.typography.titleLargeEmphasized,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.import_file_size, sizeText),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 导入中：百分比 + 波浪进度条；SAF 无大小时退回不确定进度。 */
@Composable
private fun ImportProgress(
    state: ImportUiState,
    modifier: Modifier = Modifier,
) {
    val progressDescription = stringResource(R.string.import_importing_title)
    ExpressiveCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.import_importing_title),
                style = MaterialTheme.typography.titleMediumEmphasized,
                modifier = Modifier.weight(1f),
            )
            if (state.isProgressDeterminate) {
                Text(
                    text = stringResource(R.string.progress_percent, state.percent),
                    // 百分比只做视觉提示；进度条自身会向 TalkBack 播报数值。
                    modifier = Modifier.clearAndSetSemantics { },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.isProgressDeterminate) {
            ExpressiveWavyProgress(
                progress = state.safeProgress,
                modifier = Modifier.semantics {
                    contentDescription = progressDescription
                },
            )
        } else {
            // SAF 未提供 SIZE：用不确定进度，绝不伪造百分比。
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = progressDescription },
            )
        }
    }
}

/** 编码候选：每个候选一个 tonal 按钮，主行动留给后续的「重试」。 */
@Composable
private fun EncodingChooser(
    state: ImportUiState,
    onChooseEncoding: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ExpressiveCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.import_encoding_title),
            style = MaterialTheme.typography.titleMediumEmphasized,
        )
        Text(
            text = stringResource(R.string.import_encoding_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.encodingCandidates.forEach { encoding ->
            ExpressiveTonalButton(
                text = stringResource(R.string.import_encoding_action, encoding),
                onClick = { onChooseEncoding(encoding) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 重名：在 Sheet 内用卡片确认，避免叠一层 Dialog；「打开已有项目」唯一突出。 */
@Composable
private fun DuplicateStage(
    duplicate: ImportDuplicate?,
    onOpenExisting: () -> Unit,
    onDismissDuplicate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ExpressiveCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.import_duplicate_title),
            style = MaterialTheme.typography.titleMediumEmphasized,
        )
        Text(
            text = stringResource(
                R.string.import_duplicate_message,
                duplicate?.title.orEmpty(),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.import_duplicate_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExpressivePrimaryButton(
            text = stringResource(R.string.import_action_open_existing),
            onClick = onOpenExisting,
            modifier = Modifier.fillMaxWidth(),
        )
        ExpressiveTextButton(
            text = stringResource(R.string.action_cancel),
            onClick = onDismissDuplicate,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 错误：原因卡片 + 一个主按钮（可重试时重试，否则重新选择）+ 取消。 */
@Composable
private fun ErrorStage(
    state: ImportUiState,
    onRetry: () -> Unit,
    onPickFile: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ExpressiveCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.import_error_title),
            modifier = Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
            },
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = state.error.errorMessage(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    if (state.canRetry) {
        ExpressivePrimaryButton(
            text = stringResource(R.string.action_retry),
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        )
        ExpressiveOutlinedButton(
            text = stringResource(R.string.import_action_reselect),
            onClick = onPickFile,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        ExpressivePrimaryButton(
            text = stringResource(R.string.import_action_reselect),
            onClick = onPickFile,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    CancelAction(onCancel = onCancel)
}

@Composable
private fun SuccessStage(modifier: Modifier = Modifier) {
    ExpressiveCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.import_success),
            style = MaterialTheme.typography.titleMediumEmphasized,
        )
    }
}

@Composable
private fun CancelAction(onCancel: () -> Unit) {
    ExpressiveTextButton(
        text = stringResource(R.string.import_action_cancel),
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 状态里的类型化错误 → 用户文案；用例返回的 message 已可读，直接展示。 */
@Composable
private fun ImportError?.errorMessage(): String = when (this) {
    is ImportError.Validation -> stringResource(reason.messageRes())
    ImportError.SourceUnavailable -> stringResource(R.string.import_error_unreadable)
    is ImportError.ImportFailed -> message
    null -> stringResource(R.string.import_error_title)
}

private fun ImportValidationError.messageRes(): Int = when (this) {
    ImportValidationError.NotTxt -> R.string.import_error_not_txt
    ImportValidationError.EmptyFile -> R.string.import_error_empty
    ImportValidationError.TooLarge -> R.string.import_error_too_large
    ImportValidationError.InvalidSize -> R.string.import_error_invalid_size
}

// ---------------------------------------------------------------------------
// Preview：Idle / Selected / Importing / NeedsEncoding / Error / Duplicate / 深色 200%。
// ---------------------------------------------------------------------------

private fun previewSelection(
    displayName: String = "长夜将明.txt",
    sizeBytes: Long? = 1_234_567L,
): ImportFileSelection = ImportFileSelection(
    uri = "content://preview/novel",
    displayName = displayName,
    sizeBytes = sizeBytes,
)

@Composable
private fun ImportSheetPreviewSurface(content: @Composable () -> Unit) {
    AudioForgeTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            content()
        }
    }
}

@Preview(name = "ImportSheet Idle", showBackground = true)
@Composable
private fun ImportSheetIdlePreview() {
    ImportSheetPreviewSurface {
        ImportSheetContent(state = ImportUiState())
    }
}

@Preview(name = "ImportSheet Selected", showBackground = true)
@Composable
private fun ImportSheetSelectedPreview() {
    ImportSheetPreviewSurface {
        ImportSheetContent(
            state = ImportUiState(
                phase = ImportPhase.Selected,
                selection = previewSelection(),
            ),
        )
    }
}

@Preview(name = "ImportSheet Importing", showBackground = true)
@Composable
private fun ImportSheetImportingPreview() {
    ImportSheetPreviewSurface {
        ImportSheetContent(
            state = ImportUiState(
                phase = ImportPhase.Importing,
                selection = previewSelection(),
                progress = 0.42f,
            ),
        )
    }
}

@Preview(name = "ImportSheet NeedsEncoding", showBackground = true)
@Composable
private fun ImportSheetNeedsEncodingPreview() {
    ImportSheetPreviewSurface {
        ImportSheetContent(
            state = ImportUiState(
                phase = ImportPhase.NeedsEncoding,
                selection = previewSelection(displayName = "低中文.txt"),
                encodingCandidates = listOf("GB18030", "Big5", "UTF-8", "UTF-16LE"),
            ),
        )
    }
}

@Preview(name = "ImportSheet Error", showBackground = true)
@Composable
private fun ImportSheetErrorPreview() {
    ImportSheetPreviewSurface {
        ImportSheetContent(
            state = ImportUiState(
                phase = ImportPhase.Error,
                selection = previewSelection(),
                error = ImportError.ImportFailed(
                    stringResource(R.string.import_error_preview),
                ),
            ),
        )
    }
}

@Preview(name = "ImportSheet Duplicate", showBackground = true)
@Composable
private fun ImportSheetDuplicatePreview() {
    ImportSheetPreviewSurface {
        ImportSheetContent(
            state = ImportUiState(
                phase = ImportPhase.Duplicate,
                selection = previewSelection(),
                duplicate = ImportDuplicate(existingNovelId = 42L, title = "长夜将明"),
            ),
        )
    }
}

@Preview(
    name = "ImportSheet Dark 200%",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun ImportSheetDarkPreview() {
    AudioForgeTheme(darkTheme = true, dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            ImportSheetContent(
                state = ImportUiState(
                    phase = ImportPhase.Importing,
                    selection = previewSelection(),
                    progress = 0.75f,
                ),
            )
        }
    }
}
