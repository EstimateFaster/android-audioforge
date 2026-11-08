package com.estimatefaster.audioforge.feature.storage

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.ui.components.EmptyState
import com.estimatefaster.audioforge.ui.components.ErrorBanner
import com.estimatefaster.audioforge.ui.components.ErrorState
import com.estimatefaster.audioforge.ui.components.LoadingState
import com.estimatefaster.audioforge.ui.components.SectionHeader
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import kotlin.math.roundToInt

/**
 * 存储页（P5-09 / 执行计划 §12.4 S14、§15 P5-09）。
 *
 * 有状态入口：收集 [StorageViewModel.state] 后委托给无状态版本；页面不直接访问
 * 文件系统、DB 或 `StorageMaintenance`，所有扫描/清理都通过 ViewModel。
 */
@Composable
fun StorageScreen(
    viewModel: StorageViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StorageScreen(
        state = state,
        modifier = modifier,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onRetentionInputChange = viewModel::onRetentionInputChange,
        onRetentionSliderChange = viewModel::onRetentionSliderChange,
        onRetentionCommit = viewModel::onRetentionCommit,
        onRequestCleanup = viewModel::requestCleanup,
        onCancelCleanup = viewModel::cancelCleanup,
        onConfirmCleanup = viewModel::confirmCleanup,
        onRetryCleanup = viewModel::retryCleanup,
        onDismissResult = viewModel::dismissCleanupResult,
    )
}

/**
 * 无状态存储页。
 *
 * 布局：
 * - 顶部：返回、标题、刷新；
 * - 空间概览卡：总占用、可用空间、音频/导入/导出分区、文件数；
 * - 按项目卡：项目名、占用、章节数；已删除项目的残留以「未关联项目」展示；
 * - 保留期卡：7..365 天滑块 + 数字输入，非法输入就地提示；
 * - 清理卡：清理孤儿 / 清理过期，确认后显示进度与释放字节/删除数/失败数；
 * - 空态、加载、错误复用 P4-02 组件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    state: StorageUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onRetentionInputChange: (String) -> Unit = {},
    onRetentionSliderChange: (Int) -> Unit = {},
    onRetentionCommit: () -> Unit = {},
    onRequestCleanup: (StorageCleanupKind) -> Unit = {},
    onCancelCleanup: () -> Unit = {},
    onConfirmCleanup: () -> Unit = {},
    onRetryCleanup: () -> Unit = {},
    onDismissResult: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.action_back))
                    }
                },
                title = {
                    Text(
                        text = stringResource(R.string.storage_title),
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    TextButton(
                        onClick = onRefresh,
                        enabled = !state.isBusy,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.action_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        when {
            state.loading && state.overview == null -> LoadingState(
                message = stringResource(R.string.storage_loading),
                modifier = Modifier.padding(padding),
            )

            state.loadError != null && state.overview == null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                ErrorState(
                    message = stringResource(R.string.storage_load_error, state.loadError.orEmpty()),
                    onRetry = onRefresh,
                )
            }

            else -> StorageContent(
                state = state,
                contentPadding = padding,
                onRefresh = onRefresh,
                onRetentionInputChange = onRetentionInputChange,
                onRetentionSliderChange = onRetentionSliderChange,
                onRetentionCommit = onRetentionCommit,
                onRequestCleanup = onRequestCleanup,
                onRetryCleanup = onRetryCleanup,
                onDismissResult = onDismissResult,
            )
        }
    }

    val pendingCleanup = state.pendingCleanup
    if (pendingCleanup != null) {
        CleanupConfirmDialog(
            kind = pendingCleanup,
            retentionDays = state.retentionDays,
            onDismiss = onCancelCleanup,
            onConfirm = onConfirmCleanup,
        )
    }
}

@Composable
private fun StorageContent(
    state: StorageUiState,
    contentPadding: PaddingValues,
    onRefresh: () -> Unit,
    onRetentionInputChange: (String) -> Unit,
    onRetentionSliderChange: (Int) -> Unit,
    onRetentionCommit: () -> Unit,
    onRequestCleanup: (StorageCleanupKind) -> Unit,
    onRetryCleanup: () -> Unit,
    onDismissResult: () -> Unit,
) {
    val overview = state.overview

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (state.isLoading) {
            item(key = "refreshing") {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        if (state.loadError != null) {
            item(key = "load-error") {
                ErrorBanner(
                    message = stringResource(
                        R.string.storage_load_error,
                        state.loadError.orEmpty(),
                    ),
                    onRetry = onRefresh,
                )
            }
        }
        if (overview != null) {
            if (state.isEmpty) {
                item(key = "empty") {
                    EmptyState(
                        title = stringResource(R.string.storage_empty_title),
                        description = stringResource(R.string.storage_empty_description),
                        actionText = stringResource(R.string.storage_empty_action),
                        onAction = onRefresh,
                    )
                }
            } else {
                item(key = "overview") {
                    OverviewCard(overview = overview)
                }
                item(key = "projects") {
                    ProjectsCard(projects = overview.projects)
                }
            }
        }
        item(key = "retention") {
            RetentionCard(
                state = state,
                onInputChange = onRetentionInputChange,
                onSliderChange = onRetentionSliderChange,
                onCommit = onRetentionCommit,
            )
        }
        if (overview != null && (!state.isEmpty || state.hasResult)) {
            item(key = "cleanup") {
                CleanupCard(
                    state = state,
                    onRequestCleanup = onRequestCleanup,
                    onRetryCleanup = onRetryCleanup,
                    onDismissResult = onDismissResult,
                )
            }
        }
    }
}

@Composable
private fun OverviewCard(overview: StorageOverview) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader(
                title = stringResource(R.string.storage_overview_title),
                subtitle = stringResource(R.string.storage_overview_subtitle),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.storage_total_used),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatByteSize(overview.totalBytes),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.storage_free_space),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatByteSize(overview.usableBytes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            HorizontalDivider()
            overview.areas.forEach { usage ->
                AreaRow(
                    label = areaLabel(usage.area),
                    bytes = usage.bytes,
                    share = overview.shareOf(usage.area),
                )
            }
            Text(
                text = stringResource(R.string.storage_file_count, overview.fileCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (overview.scanFailureCount > 0) {
                Text(
                    text = stringResource(
                        R.string.storage_scan_failures,
                        overview.scanFailureCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AreaRow(
    label: String,
    bytes: Long,
    share: Float,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatByteSize(bytes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val shareDescription = stringResource(
            R.string.storage_area_progress_content_description,
            label,
            (share.coerceIn(0f, 1f) * 100f).roundToInt(),
        )
        LinearProgressIndicator(
            progress = { share },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .semantics { contentDescription = shareDescription },
        )
    }
}

@Composable
private fun ProjectsCard(projects: List<StorageProjectUsage>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionHeader(
                title = stringResource(R.string.storage_projects_title),
                subtitle = stringResource(R.string.storage_projects_subtitle, projects.size),
            )
            if (projects.isEmpty()) {
                Text(
                    text = stringResource(R.string.storage_projects_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                projects.forEachIndexed { index, project ->
                    if (index > 0) HorizontalDivider()
                    ProjectRow(project = project)
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(project: StorageProjectUsage) {
    val title = project.title
        ?: stringResource(R.string.storage_project_unknown, project.novelId)
    val meta = if (project.chapterCount > 0) {
        stringResource(R.string.storage_project_chapters, project.chapterCount)
    } else {
        stringResource(R.string.storage_project_no_chapters)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = formatByteSize(project.bytes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun RetentionCard(
    state: StorageUiState,
    onInputChange: (String) -> Unit,
    onSliderChange: (Int) -> Unit,
    onCommit: () -> Unit,
) {
    val errorText = retentionErrorText(state.retentionError)
    val sliderDescription = stringResource(
        R.string.storage_retention_slider_description,
        state.retentionDays,
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionHeader(
                title = stringResource(R.string.storage_retention_title),
                subtitle = stringResource(R.string.storage_retention_subtitle),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        R.string.storage_retention_value,
                        state.retentionDays,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.storage_retention_range),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = state.retentionDays.toFloat(),
                onValueChange = { value -> onSliderChange(value.roundToInt()) },
                onValueChangeFinished = onCommit,
                valueRange = MIN_RETENTION_DAYS.toFloat()..MAX_RETENTION_DAYS.toFloat(),
                enabled = !state.isBusy,
                modifier = Modifier.semantics {
                    contentDescription = sliderDescription
                },
            )
            OutlinedTextField(
                value = state.retentionInput,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isBusy,
                label = { Text(stringResource(R.string.storage_retention_label)) },
                singleLine = true,
                isError = errorText != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = {
                    Text(
                        text = errorText ?: stringResource(R.string.storage_retention_hint),
                        color = if (errorText != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun CleanupCard(
    state: StorageUiState,
    onRequestCleanup: (StorageCleanupKind) -> Unit,
    onRetryCleanup: () -> Unit,
    onDismissResult: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionHeader(
                title = stringResource(R.string.storage_cleanup_title),
                subtitle = stringResource(R.string.storage_cleanup_subtitle),
            )
            CleanupActionRow(
                title = stringResource(R.string.storage_cleanup_orphans_title),
                description = stringResource(R.string.storage_cleanup_orphans_desc),
                meta = stringResource(
                    R.string.storage_cleanup_meta,
                    state.overview?.orphanCount ?: 0,
                    formatByteSize(state.overview?.orphanBytes ?: 0L),
                ),
                actionText = stringResource(R.string.storage_action_clean_orphans),
                enabled = state.canCleanOrphans && !state.isBusy,
                onClick = { onRequestCleanup(StorageCleanupKind.ORPHANS) },
            )
            HorizontalDivider()
            CleanupActionRow(
                title = stringResource(R.string.storage_cleanup_expired_title),
                description = stringResource(
                    R.string.storage_cleanup_expired_desc,
                    state.retentionDays,
                ),
                meta = stringResource(
                    R.string.storage_cleanup_meta,
                    state.overview?.expiredCount ?: 0,
                    formatByteSize(state.overview?.expiredBytes ?: 0L),
                ),
                actionText = stringResource(R.string.storage_action_clean_expired),
                enabled = state.canCleanExpired && !state.isBusy,
                onClick = { onRequestCleanup(StorageCleanupKind.EXPIRED) },
            )
            Text(
                text = stringResource(R.string.storage_expired_protected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val cleaning = state.cleaning
            if (cleaning != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LinearProgressIndicator(modifier = Modifier.weight(1f))
                    Text(
                        text = cleaningLabel(cleaning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (state.hasResult) {
                HorizontalDivider()
                CleanupResultSection(
                    state = state,
                    onRetryCleanup = onRetryCleanup,
                    onDismissResult = onDismissResult,
                )
            } else {
                Text(
                    text = stringResource(R.string.storage_cleanup_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CleanupActionRow(
    title: String,
    description: String,
    meta: String,
    actionText: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = meta,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(12.dp))
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(actionText)
        }
    }
}

@Composable
private fun CleanupResultSection(
    state: StorageUiState,
    onRetryCleanup: () -> Unit,
    onDismissResult: () -> Unit,
) {
    val result = state.lastCleanup
    val cleanupError = state.cleanupError
    val failed = result?.hasFailures == true || cleanupError != null
    Text(
        text = stringResource(
            if (failed) {
                R.string.storage_cleanup_result_failed_title
            } else {
                R.string.storage_cleanup_result_title
            },
        ),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
    if (result != null) {
        Text(
            text = stringResource(
                R.string.storage_cleanup_result_summary,
                formatByteSize(result.freedBytes),
                result.deletedCount,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (result.deletedNothing) {
            Text(
                text = stringResource(R.string.storage_cleanup_result_none_deleted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (result.failureCount > 0) {
            Text(
                text = stringResource(
                    R.string.storage_cleanup_result_failures,
                    result.failureCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (result.skippedReferencedCount > 0) {
            Text(
                text = stringResource(
                    R.string.storage_cleanup_result_skipped_referenced,
                    result.skippedReferencedCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (result.skippedTooRecentCount > 0) {
            Text(
                text = stringResource(
                    R.string.storage_cleanup_result_skipped_recent,
                    result.skippedTooRecentCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (result.skippedMissingCount > 0) {
            Text(
                text = stringResource(
                    R.string.storage_cleanup_result_skipped_missing,
                    result.skippedMissingCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (cleanupError != null) {
        Text(
            text = stringResource(R.string.storage_cleanup_error, cleanupError),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        if (state.canRetryCleanup) {
            FilledTonalButton(
                onClick = onRetryCleanup,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.storage_cleanup_action_retry))
            }
            Spacer(Modifier.width(8.dp))
        }
        TextButton(
            onClick = onDismissResult,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.storage_cleanup_action_dismiss))
        }
    }
}

@Composable
private fun CleanupConfirmDialog(
    kind: StorageCleanupKind,
    retentionDays: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    when (kind) {
                        StorageCleanupKind.ORPHANS ->
                            R.string.storage_cleanup_confirm_title_orphans

                        StorageCleanupKind.EXPIRED ->
                            R.string.storage_cleanup_confirm_title_expired
                    },
                ),
            )
        },
        text = {
            Text(
                text = when (kind) {
                    StorageCleanupKind.ORPHANS -> stringResource(
                        R.string.storage_cleanup_confirm_message_orphans,
                    )

                    StorageCleanupKind.EXPIRED -> stringResource(
                        R.string.storage_cleanup_confirm_message_expired,
                        retentionDays,
                    )
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.storage_cleanup_confirm_action))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun areaLabel(area: StorageArea): String = when (area) {
    StorageArea.AUDIO -> stringResource(R.string.storage_area_audio)
    StorageArea.IMPORTS -> stringResource(R.string.storage_area_imports)
    StorageArea.EXPORTS -> stringResource(R.string.storage_area_exports)
}

@Composable
private fun cleaningLabel(kind: StorageCleanupKind): String = when (kind) {
    StorageCleanupKind.ORPHANS -> stringResource(R.string.storage_cleaning_orphans)
    StorageCleanupKind.EXPIRED -> stringResource(R.string.storage_cleaning_expired)
}

@Composable
private fun retentionErrorText(error: RetentionError?): String? = when (error) {
    null -> null
    RetentionError.Missing -> stringResource(R.string.storage_retention_error_blank)
    RetentionError.NotAnInteger -> stringResource(R.string.storage_retention_error_number)
    RetentionError.OutOfRange -> stringResource(R.string.storage_retention_error_range)
}

// ---------------------------------------------------------------------------
// Preview：正常 / 清理确认 / 空态 / 清理结果。文案全部来自 strings.xml。
// ---------------------------------------------------------------------------

private fun previewOverview(): StorageOverview = StorageOverview(
    totalBytes = 3_150_000_000L,
    usableBytes = 12_000_000_000L,
    audioBytes = 2_600_000_000L,
    importsBytes = 150_000_000L,
    exportsBytes = 400_000_000L,
    fileCount = 1_842,
    projects = listOf(
        StorageProjectUsage(1L, "长夜将明", 1_900_000_000L, 128),
        StorageProjectUsage(2L, "白夜行", 620_000_000L, 48),
        StorageProjectUsage(3L, "短篇集", 80_000_000L, 9),
        StorageProjectUsage(9L, null, 12_000_000L, 0),
    ),
    orphanCount = 37,
    orphanBytes = 240_000_000L,
    expiredCount = 11,
    expiredBytes = 310_000_000L,
    scanFailureCount = 1,
    scannedAt = 1_760_000_000_000L,
)

private fun previewState(): StorageUiState = StorageUiState(
    loading = false,
    overview = previewOverview(),
    retentionInput = "30",
    retentionDays = 30,
)

@Preview(name = "Storage Normal", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun StorageNormalPreview() {
    AudioForgeTheme(darkTheme = false, dynamicColor = false) {
        StorageScreen(state = previewState())
    }
}

@Preview(
    name = "Storage Cleanup Confirm",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun StorageCleanupConfirmPreview() {
    AudioForgeTheme(darkTheme = false, dynamicColor = false) {
        StorageScreen(
            state = previewState().copy(
                pendingCleanup = StorageCleanupKind.EXPIRED,
            ),
        )
    }
}

@Preview(
    name = "Storage Empty Dark 200%",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun StorageEmptyPreview() {
    AudioForgeTheme(darkTheme = true, dynamicColor = false) {
        StorageScreen(
            state = StorageUiState(
                loading = false,
                overview = StorageOverview(
                    totalBytes = 0L,
                    usableBytes = 12_000_000_000L,
                    audioBytes = 0L,
                    importsBytes = 0L,
                    exportsBytes = 0L,
                    fileCount = 0,
                    projects = emptyList(),
                    orphanCount = 0,
                    orphanBytes = 0L,
                    expiredCount = 0,
                    expiredBytes = 0L,
                    scanFailureCount = 0,
                    scannedAt = 1_760_000_000_000L,
                ),
            ),
        )
    }
}

@Preview(name = "Storage Cleanup Result", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun StorageCleanupResultPreview() {
    AudioForgeTheme(darkTheme = false, dynamicColor = false) {
        StorageScreen(
            state = previewState().copy(
                lastCleanupKind = StorageCleanupKind.ORPHANS,
                lastCleanup = StorageCleanupResult(
                    kind = StorageCleanupKind.ORPHANS,
                    freedBytes = 240_000_000L,
                    deletedCount = 31,
                    failureCount = 2,
                    skippedReferencedCount = 4,
                    skippedTooRecentCount = 6,
                    skippedMissingCount = 0,
                ),
            ),
        )
    }
}
