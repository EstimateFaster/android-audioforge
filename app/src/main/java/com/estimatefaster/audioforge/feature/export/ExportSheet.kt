package com.estimatefaster.audioforge.feature.export

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.ui.components.AdaptiveFieldRow
import com.estimatefaster.audioforge.ui.components.EmptyState
import com.estimatefaster.audioforge.ui.components.ErrorState
import com.estimatefaster.audioforge.ui.components.SectionHeader
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme

/**
 * 导出/分享 Sheet（P5-11 / 执行计划 §12.4 S13、§15 P5-11）。
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
 * 布局按阶段切换：
 * - 选择：范围控件 → 起止章输入（仅起止范围）→ 章节列表（时长/体积）→ 汇总 → 开始；
 * - 导出中：进度条 x/y → 当前章名 → 成功/失败计数 → 取消；
 * - 结果：成功列表 → 失败列表与重试 → 分享/完成。
 *
 * 加载、加载失败与“无已完成章节”空态优先于阶段内容；文案全部来自 `strings.xml`。
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
            .padding(horizontal = 24.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ExportHeader()

        when {
            state.loading -> ExportLoading()
            loadError != null -> ErrorState(
                message = stringResource(R.string.export_load_error, loadError),
                retryText = stringResource(R.string.export_action_retry_load),
                onRetry = onRetryLoad,
            )

            state.isEmpty -> EmptyState(
                title = stringResource(R.string.export_empty_title),
                description = stringResource(R.string.export_empty_description),
                actionText = stringResource(R.string.export_action_retry_load),
                onAction = onRetryLoad,
            )

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

@Composable
private fun ExportHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.export_sheet_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.export_sheet_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExportLoading() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.export_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionSection(
    state: ExportUiState,
    onRangeChange: (ExportRange) -> Unit,
    onStartTextChange: (String) -> Unit,
    onEndTextChange: (String) -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    SectionHeader(
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
        ValidationCard(
            message = selectionMessage(selectionError, state.chapters.size),
        )
    }

    SectionHeader(
        title = stringResource(R.string.export_list_title),
        subtitle = stringResource(
            R.string.export_list_subtitle,
            state.chapters.size,
            state.completedChapters.size,
        ),
    )
    val selectedIds = state.selectedChapters.map { it.chapterIdx }.toSet()
    state.chapters.take(MAX_LIST_ROWS).forEach { chapter ->
        ChapterRow(
            chapter = chapter,
            selected = chapter.chapterIdx in selectedIds,
        )
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

    Button(
        onClick = onStart,
        enabled = state.canStart,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Text(
            text = if (state.selectedCount > 0) {
                stringResource(R.string.export_action_start_count, state.selectedCount)
            } else {
                stringResource(R.string.export_action_start)
            },
        )
    }
    TextButton(
        onClick = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Text(stringResource(R.string.export_action_close))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeSelector(
    range: ExportRange,
    onRangeChange: (ExportRange) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        ExportRange.entries.forEachIndexed { index, candidate ->
            SegmentedButton(
                selected = range == candidate,
                onClick = { onRangeChange(candidate) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = ExportRange.entries.size,
                ),
                label = { Text(stringResource(candidate.labelRes())) },
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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AdaptiveFieldRow(
            {
                OutlinedTextField(
                    value = state.startText,
                    onValueChange = onStartTextChange,
                    label = { Text(stringResource(R.string.export_range_start)) },
                    singleLine = true,
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                if (!chapter.audioDone) {
                    stateDescription = notReadyLabel
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(
            checked = selected,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chapter.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.export_chapter_meta, chapter.number, duration, size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!chapter.audioDone) {
            Text(
                text = notReadyLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ExportSummaryCard(state: ExportUiState) {
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
                text = stringResource(R.string.export_summary_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
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

@Composable
private fun ValidationCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun ExportingSection(
    state: ExportUiState,
    onCancel: () -> Unit,
) {
    SectionHeader(
        title = stringResource(R.string.export_progress_title),
        subtitle = stringResource(
            R.string.export_progress_count,
            state.completed,
            state.total,
        ),
    )
    LinearProgressIndicator(
        progress = { state.progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
    val currentTitle = state.currentChapterTitle
    if (currentTitle != null) {
        Text(
            text = stringResource(R.string.export_progress_current, currentTitle),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(R.string.export_progress_succeeded, state.succeeded.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.export_progress_failed, state.failures.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        text = stringResource(R.string.export_cancel_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(
        onClick = onCancel,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Text(stringResource(R.string.export_action_cancel_export))
    }
}

@Composable
private fun ResultSection(
    state: ExportUiState,
    onRetryFailed: () -> Unit,
    onShare: (List<Uri>) -> Unit,
    onDismiss: () -> Unit,
) {
    SectionHeader(
        title = stringResource(R.string.export_result_title),
        subtitle = stringResource(
            R.string.export_result_summary,
            state.succeeded.size,
            state.failures.size,
        ),
    )

    if (state.cancelled) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Text(
                text = stringResource(R.string.export_result_cancelled),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }

    Text(
        text = stringResource(R.string.export_result_success_title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    if (state.succeeded.isEmpty()) {
        Text(
            text = stringResource(R.string.export_result_no_success),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        state.succeeded.take(MAX_LIST_ROWS).forEach { file ->
            ExportedFileRow(file)
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
        HorizontalDivider()
        Text(
            text = stringResource(R.string.export_result_failure_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        state.failures.take(MAX_LIST_ROWS).forEach { failure ->
            Text(
                text = stringResource(
                    R.string.export_result_failure_line,
                    failure.title,
                    failure.message,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedButton(
            onClick = onRetryFailed,
            enabled = state.canRetryFailed,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.export_action_retry_failed))
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.export_action_done))
        }
        Button(
            onClick = { onShare(state.shareUris.map { uri -> Uri.parse(uri) }) },
            enabled = state.canShare,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.export_action_share))
        }
    }
    Text(
        text = stringResource(R.string.export_result_share_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ExportedFileRow(file: ExportedFile) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
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
