@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.estimatefaster.audioforge.feature.projects

import android.content.res.Configuration
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.ui.forge.ForgeCard
import com.estimatefaster.audioforge.ui.forge.ForgeChip
import com.estimatefaster.audioforge.ui.forge.ForgeError
import com.estimatefaster.audioforge.ui.forge.ForgeIcon
import com.estimatefaster.audioforge.ui.forge.ForgeIcons
import com.estimatefaster.audioforge.ui.forge.ForgeListRow
import com.estimatefaster.audioforge.ui.forge.ForgePrimaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSecondaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSheetHeader
import com.estimatefaster.audioforge.ui.forge.ForgeWavyProgress
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing

/**
 * 导入 Sheet 的有状态入口（Forge Framework v1 · Import）。
 *
 * 保留原状态机与全部回调：
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
 * 无状态 Sheet 内容；Preview 与 UI 测试直接渲染它。
 *
 * 对齐 `forge-framework.html` 的 Import Sheet：Sheet 头（Lucide 图标 + 标题 +
 * 关闭）→ 选择文件 → 分阶段内容（导入 / 编码 / 重名 / 错误 / 成功）→ 本机导入说明。
 * 所有操作均为 Lucide icon + 文案或纯图标；正文与说明文字不可点击。
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg),
    ) {
        ForgeSheetHeader(
            icon = ForgeIcons.Upload,
            title = stringResource(R.string.ux2_import_title),
            subtitle = stringResource(R.string.ux2_import_subtitle),
            onClose = onCancel,
        )

        ImportSectionHeader(
            icon = ForgeIcons.FileText,
            title = stringResource(R.string.ux2_import_step_file),
        )

        val selection = state.selection
        if (selection == null) {
            PickFileCard(onPickFile = onPickFile)
        } else {
            SelectedFileCard(
                selection = selection,
                canReselect = state.phase != ImportPhase.Importing,
                onReselect = onPickFile,
            )
        }

        when (state.phase) {
            ImportPhase.Idle -> Unit

            ImportPhase.Selected -> {
                ForgePrimaryAction(
                    icon = ForgeIcons.Upload,
                    label = stringResource(R.string.ux2_import_start),
                    onClick = onStartImport,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.canStartImport,
                )
                ForgeSecondaryAction(
                    icon = ForgeIcons.Refresh,
                    label = stringResource(R.string.ux2_import_reselect),
                    onClick = onPickFile,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ImportPhase.Importing -> {
                ImportProgress(state = state)
                ForgeSecondaryAction(
                    icon = ForgeIcons.Close,
                    label = stringResource(R.string.ux2_import_cancel),
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ImportPhase.NeedsEncoding -> {
                ImportSectionHeader(
                    icon = ForgeIcons.Globe,
                    title = stringResource(R.string.ux2_import_step_encoding),
                )
                Text(
                    text = stringResource(R.string.ux2_import_encoding_title),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Text(
                    text = stringResource(R.string.ux2_import_encoding_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.encodingCandidates.isEmpty()) {
                    Text(
                        text = stringResource(R.string.ux2_import_error_unknown),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    state.encodingCandidates.forEach { encoding ->
                        ForgeChip(
                            label = stringResource(R.string.ux2_import_encoding_action, encoding),
                            selected = state.selectedEncoding == encoding,
                            onClick = { onChooseEncoding(encoding) },
                            icon = if (encoding.startsWith("UTF", ignoreCase = true)) {
                                ForgeIcons.Globe
                            } else {
                                ForgeIcons.FileText
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                ForgeSecondaryAction(
                    icon = ForgeIcons.Close,
                    label = stringResource(R.string.ux2_import_cancel),
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ImportPhase.Duplicate -> {
                Text(
                    text = stringResource(R.string.ux2_import_duplicate_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ForgeSecondaryAction(
                    icon = ForgeIcons.Close,
                    label = stringResource(R.string.ux2_import_cancel),
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ImportPhase.Error -> {
                ForgeError(
                    message = stringResource(
                        R.string.ux2_import_error_message,
                        state.error.errorMessage(),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 300.dp),
                    onRetry = if (state.canRetry) onRetry else null,
                    retryLabel = stringResource(R.string.ux2_import_retry),
                )
                ForgeSecondaryAction(
                    icon = ForgeIcons.Upload,
                    label = stringResource(R.string.ux2_import_reselect),
                    onClick = onPickFile,
                    modifier = Modifier.fillMaxWidth(),
                )
                ForgeSecondaryAction(
                    icon = ForgeIcons.Close,
                    label = stringResource(R.string.ux2_import_cancel),
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ImportPhase.Success -> {
                ForgeListRow(
                    icon = ForgeIcons.CheckCircle,
                    title = stringResource(R.string.ux2_import_success),
                    subtitle = stringResource(R.string.ux2_import_success_hint),
                )
            }
        }

        Text(
            text = stringResource(R.string.ux2_import_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (state.phase == ImportPhase.Duplicate) {
        DuplicateDialog(
            duplicate = state.duplicate,
            onOpenExisting = onOpenExisting,
            onDismiss = onDismissDuplicate,
        )
    }
}

// ---------------------------------------------------------------------------
// 选择文件
// ---------------------------------------------------------------------------

@Composable
private fun PickFileCard(
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ForgeCard(
        modifier = modifier,
        onClick = onPickFile,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(MaterialTheme.spacing.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ForgeIcon(
                        icon = ForgeIcons.Upload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.ux2_import_pick_title),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Text(
                    text = stringResource(R.string.ux2_import_pick_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ForgeIcon(
                icon = ForgeIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SelectedFileCard(
    selection: ImportFileSelection,
    canReselect: Boolean,
    onReselect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sizeText = selection.sizeBytes
        ?.takeIf { bytes -> bytes >= 0L }
        ?.let { bytes -> Formatter.formatFileSize(context, bytes) }
        ?: stringResource(R.string.ux2_import_size_unknown)

    ForgeListRow(
        icon = ForgeIcons.FileText,
        title = selection.displayName,
        subtitle = stringResource(R.string.ux2_import_file_size, sizeText),
        trailingText = stringResource(R.string.ux2_import_file_desc),
        trailingIcon = if (canReselect) ForgeIcons.Refresh else null,
        trailingDescription = stringResource(R.string.ux2_import_file_reselect),
        onTrailingClick = if (canReselect) onReselect else null,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// 导入进度 / 编码 / 错误 / 重名
// ---------------------------------------------------------------------------

@Composable
private fun ImportProgress(
    state: ImportUiState,
    modifier: Modifier = Modifier,
) {
    val progressDescription = stringResource(R.string.ux2_import_importing)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = progressDescription,
                style = MaterialTheme.typography.titleSmallEmphasized,
                modifier = Modifier.weight(1f),
            )
            if (state.isProgressDeterminate) {
                Text(
                    text = stringResource(R.string.ux2_import_progress_percent, state.percent),
                    // 百分比只做视觉提示；进度条自身会向 TalkBack 播报数值。
                    modifier = Modifier.clearAndSetSemantics { },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.isProgressDeterminate) {
            ForgeWavyProgress(
                progress = state.safeProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = progressDescription },
            )
        } else {
            // SAF 未提供 SIZE：不伪造百分比，用加载指示 + 文案说明。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            ) {
                LoadingIndicator(modifier = Modifier.size(20.dp))
                Text(
                    text = stringResource(R.string.ux2_import_progress_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ImportSectionHeader(
    @DrawableRes icon: Int,
    title: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmallEmphasized,
            modifier = Modifier.semantics { heading() },
        )
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
        icon = { ForgeIcon(ForgeIcons.Library, contentDescription = null) },
        title = {
            Text(
                text = stringResource(R.string.ux2_import_duplicate_title),
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                Text(
                    text = stringResource(
                        R.string.ux2_import_duplicate_message,
                        duplicate?.title.orEmpty(),
                    ),
                )
                Text(
                    text = stringResource(R.string.ux2_import_duplicate_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            ForgePrimaryAction(
                icon = ForgeIcons.BookOpen,
                label = stringResource(R.string.ux2_import_duplicate_open),
                onClick = onOpenExisting,
            )
        },
        dismissButton = {
            ForgeSecondaryAction(
                icon = ForgeIcons.Close,
                label = stringResource(R.string.ux2_import_duplicate_dismiss),
                onClick = onDismiss,
            )
        },
    )
}

/** 状态里的类型化错误 → 用户文案；用例返回的 message 已可读，直接展示。 */
@Composable
private fun ImportError?.errorMessage(): String = when (this) {
    is ImportError.Validation -> stringResource(reason.messageRes())
    ImportError.SourceUnavailable -> stringResource(R.string.ux2_import_error_unreadable)
    is ImportError.ImportFailed -> message
    null -> stringResource(R.string.ux2_import_error_unknown)
}

private fun ImportValidationError.messageRes(): Int = when (this) {
    ImportValidationError.NotTxt -> R.string.ux2_import_error_not_txt
    ImportValidationError.EmptyFile -> R.string.ux2_import_error_empty
    ImportValidationError.TooLarge -> R.string.ux2_import_error_too_large
    ImportValidationError.InvalidSize -> R.string.ux2_import_error_invalid_size
}

// ---------------------------------------------------------------------------
// Preview：空 / 已选 / 导入中 / 编码 / 错误 / 重名 / 深色 200%。
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
    name = "Import Idle",
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
    name = "Import Selected",
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
    name = "Import Importing",
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
    name = "Import NeedsEncoding",
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
    name = "Import Error",
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
                        stringResource(R.string.ux2_import_error_unreadable),
                    ),
                ),
            )
        }
    }
}

@Preview(
    name = "Import Duplicate",
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
    name = "Import Dark 200%",
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
