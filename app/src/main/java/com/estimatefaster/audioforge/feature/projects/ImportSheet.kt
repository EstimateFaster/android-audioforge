package com.estimatefaster.audioforge.feature.projects

import android.content.res.Configuration
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme

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
 * 集成提示：`ProjectLibraryScreen` 的 FAB `onImport` 目前只是回调；接入时在该页
 * 用 `if (showImport) ImportSheet(viewModel, onDismiss = { showImport = false },
 * onImported = onOpenProject, onOpenExisting = onOpenProject)` 即可。
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
 * - `Idle`：选择文件入口；
 * - `Selected`：文件卡 + 导入；
 * - `Importing`：文件卡 + 进度条/百分比 + 取消；
 * - `NeedsEncoding`：候选编码按钮，选中后重开源流重试；
 * - `Duplicate`：AlertDialog「打开已有项目 / 取消」；
 * - `Error`：可读原因 + 重试（仅用例失败/源打不开）+ 重新选择；
 * - `Success`：成功占位（正常路径会立即通过事件关闭）。
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.import_sheet_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.import_sheet_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.selection?.let { selection -> SelectedFileCard(selection = selection) }

        when (state.phase) {
            ImportPhase.Idle -> {
                Text(
                    text = stringResource(R.string.import_file_type_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onPickFile,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.import_pick_file))
                }
            }

            ImportPhase.Selected -> {
                Text(
                    text = stringResource(R.string.import_file_type_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onStartImport,
                    enabled = state.canStartImport,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.import_action_start))
                }
                TextButton(
                    onClick = onPickFile,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.import_pick_again))
                }
            }

            ImportPhase.Importing -> {
                ImportProgress(state = state)
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.import_action_cancel))
                }
            }

            ImportPhase.NeedsEncoding -> {
                EncodingChooser(state = state, onChooseEncoding = onChooseEncoding)
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.import_action_cancel))
                }
            }

            ImportPhase.Duplicate -> {
                // 具体动作在下方 AlertDialog 里；这里保留说明，避免用户只看到弹窗。
                Text(
                    text = stringResource(R.string.import_duplicate_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ImportPhase.Error -> {
                ImportErrorPanel(
                    state = state,
                    onRetry = onRetry,
                    onPickFile = onPickFile,
                )
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.import_action_cancel))
                }
            }

            ImportPhase.Success -> {
                Text(
                    text = stringResource(R.string.import_success),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    if (state.phase == ImportPhase.Duplicate) {
        DuplicateDialog(
            duplicate = state.duplicate,
            onOpenExisting = onOpenExisting,
            onDismiss = onDismissDuplicate,
        )
    }
}

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

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.import_selected_file),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = selection.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
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
}

@Composable
private fun ImportProgress(
    state: ImportUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.import_importing_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
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
        val progressDescription = stringResource(R.string.import_importing_title)
        if (state.isProgressDeterminate) {
            LinearProgressIndicator(
                progress = { state.safeProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 4.dp)
                    .semantics { contentDescription = progressDescription },
            )
        } else {
            // SAF 未提供 SIZE：用不确定进度，绝不伪造百分比。
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 4.dp)
                    .semantics { contentDescription = progressDescription },
            )
        }
    }
}

@Composable
private fun EncodingChooser(
    state: ImportUiState,
    onChooseEncoding: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.import_encoding_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.import_encoding_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.encodingCandidates.forEach { encoding ->
            OutlinedButton(
                onClick = { onChooseEncoding(encoding) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.import_encoding_action, encoding))
            }
        }
    }
}

@Composable
private fun ImportErrorPanel(
    state: ImportUiState,
    onRetry: () -> Unit,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.import_error_title),
            modifier = Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = state.error.errorMessage(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (state.canRetry) {
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.action_retry))
            }
        }
        OutlinedButton(
            onClick = onPickFile,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.import_action_reselect))
        }
    }
}

@Composable
private fun DuplicateDialog(
    duplicate: ImportDuplicate?,
    onOpenExisting: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_duplicate_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(
                        R.string.import_duplicate_message,
                        duplicate?.title.orEmpty(),
                    ),
                )
                Text(
                    text = stringResource(R.string.import_duplicate_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenExisting) {
                Text(stringResource(R.string.import_action_open_existing))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
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
// Preview：已选择 / 导入中 / 错误 / 编码重试 / 重名（至少 3 个场景）。
// ---------------------------------------------------------------------------

private fun previewSelection(
    displayName: String = "长夜将明.txt",
    sizeBytes: Long? = 1_234_567L,
): ImportFileSelection = ImportFileSelection(
    uri = "content://preview/novel",
    displayName = displayName,
    sizeBytes = sizeBytes,
)

@Preview(
    name = "ImportSheet Idle",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun ImportSheetIdlePreview() {
    AudioForgeTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            ImportSheetContent(state = ImportUiState())
        }
    }
}

@Preview(
    name = "ImportSheet Selected",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun ImportSheetSelectedPreview() {
    AudioForgeTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            ImportSheetContent(
                state = ImportUiState(
                    phase = ImportPhase.Selected,
                    selection = previewSelection(),
                ),
            )
        }
    }
}

@Preview(
    name = "ImportSheet Importing",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun ImportSheetImportingPreview() {
    AudioForgeTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            ImportSheetContent(
                state = ImportUiState(
                    phase = ImportPhase.Importing,
                    selection = previewSelection(),
                    progress = 0.42f,
                ),
            )
        }
    }
}

@Preview(
    name = "ImportSheet NeedsEncoding",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun ImportSheetNeedsEncodingPreview() {
    AudioForgeTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            ImportSheetContent(
                state = ImportUiState(
                    phase = ImportPhase.NeedsEncoding,
                    selection = previewSelection(displayName = "低中文.txt"),
                    encodingCandidates = listOf("GB18030", "Big5", "UTF-8", "UTF-16LE"),
                ),
            )
        }
    }
}

@Preview(
    name = "ImportSheet Error",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun ImportSheetErrorPreview() {
    AudioForgeTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.surface) {
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
}

@Preview(
    name = "ImportSheet Duplicate",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun ImportSheetDuplicatePreview() {
    AudioForgeTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            ImportSheetContent(
                state = ImportUiState(
                    phase = ImportPhase.Duplicate,
                    selection = previewSelection(),
                    duplicate = ImportDuplicate(existingNovelId = 42L, title = "长夜将明"),
                ),
            )
        }
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
        Surface(color = MaterialTheme.colorScheme.surface) {
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
