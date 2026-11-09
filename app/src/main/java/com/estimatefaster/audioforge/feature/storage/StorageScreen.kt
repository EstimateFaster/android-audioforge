package com.estimatefaster.audioforge.feature.storage

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.ui.forge.ForgeCard
import com.estimatefaster.audioforge.ui.forge.ForgeEmpty
import com.estimatefaster.audioforge.ui.forge.ForgeError
import com.estimatefaster.audioforge.ui.forge.ForgeIcon
import com.estimatefaster.audioforge.ui.forge.ForgeIconAction
import com.estimatefaster.audioforge.ui.forge.ForgeIcons
import com.estimatefaster.audioforge.ui.forge.ForgeListRow
import com.estimatefaster.audioforge.ui.forge.ForgeLoading
import com.estimatefaster.audioforge.ui.forge.ForgePrimaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSecondaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSectionHeader
import com.estimatefaster.audioforge.ui.forge.ForgeSegmented
import com.estimatefaster.audioforge.ui.forge.ForgeTopBar
import com.estimatefaster.audioforge.ui.forge.ForgeWavyProgress
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing
import kotlin.math.roundToInt

/**
 * 存储页（P5-09 / 执行计划 §12.4 S14、§15 P5-09），Forge Framework v1 重做。
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
 * 无状态存储页（Forge Framework v1）。
 *
 * 布局严格对齐 `docs/prototype/forge-framework.html` 的 Storage 页：
 * - ForgeTopBar：返回图标、标题、占用副标题、刷新图标操作；
 * - Hero 卡：总占用 / 容量、Wavy 进度、三色分区条与图例、文件数、扫描失败提示；
 * - 项目占用：ForgeListRow（图标 + 标题 + 章节数 + 占用），按状态层顺序展示；
 * - 保留期：7/30/90 快捷 chip、滑块、数字输入、应用按钮；
 * - 清理：孤儿 / 过期两条「图标 + 文案」次操作，确认弹窗后才执行；
 *   清理中显示进度行，结果卡展示释放量/删除数/失败数并可重试或关闭。
 *
 * 所有数字与文案来自 [StorageUiState] 与 `strings.xml` / `strings_ux_v2_storage_about.xml`，
 * 本文件不做统计、不做清理，也不直接读写文件。
 */
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
    val overview = state.overview

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ForgeTopBar(
                title = stringResource(R.string.storage_title),
                subtitle = if (overview == null) {
                    stringResource(R.string.storage_overview_subtitle)
                } else {
                    stringResource(
                        R.string.storage_v2_topbar_subtitle,
                        formatByteSize(overview.totalBytes),
                        formatByteSize(overview.totalBytes + overview.usableBytes),
                    )
                },
                navigationIcon = ForgeIcons.Back,
                navigationDescription = stringResource(R.string.action_back),
                onNavigation = onBack,
                actions = {
                    ForgeIconAction(
                        icon = ForgeIcons.Refresh,
                        contentDescription = stringResource(R.string.storage_v2_refresh_action),
                        onClick = onRefresh,
                    )
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                overview == null && state.loading -> ForgeLoading(
                    label = stringResource(R.string.storage_loading),
                )

                overview == null && state.loadError != null -> ForgeError(
                    message = stringResource(
                        R.string.storage_load_error,
                        state.loadError.orEmpty(),
                    ),
                    onRetry = onRefresh,
                    retryLabel = stringResource(R.string.action_retry),
                )

                overview == null || state.isEmpty -> ForgeEmpty(
                    icon = ForgeIcons.Storage,
                    title = stringResource(R.string.storage_empty_title),
                    description = stringResource(R.string.storage_v2_empty_description),
                    actionIcon = ForgeIcons.Refresh,
                    actionLabel = stringResource(R.string.storage_empty_action),
                    onAction = onRefresh,
                )

                else -> StorageContent(
                    state = state,
                    overview = overview,
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
    overview: StorageOverview,
    onRefresh: () -> Unit,
    onRetentionInputChange: (String) -> Unit,
    onRetentionSliderChange: (Int) -> Unit,
    onRetentionCommit: () -> Unit,
    onRequestCleanup: (StorageCleanupKind) -> Unit,
    onRetryCleanup: () -> Unit,
    onDismissResult: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val refreshingDescription = stringResource(R.string.storage_loading)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        if (state.isLoading) {
            item(key = "refreshing") {
                LinearWavyProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = refreshingDescription },
                )
            }
        }
        if (state.loadError != null) {
            item(key = "load-error") {
                LoadErrorCard(
                    message = stringResource(
                        R.string.storage_load_error,
                        state.loadError.orEmpty(),
                    ),
                    onRetry = onRefresh,
                )
            }
        }

        item(key = "hero") {
            StorageHeroCard(overview = overview)
        }

        item(key = "projects-header") {
            ForgeSectionHeader(
                title = stringResource(R.string.storage_projects_title),
                subtitle = stringResource(
                    R.string.storage_projects_subtitle,
                    overview.projects.size,
                ),
                actionIcon = ForgeIcons.Refresh,
                actionDescription = stringResource(R.string.storage_v2_projects_refresh),
                onAction = onRefresh,
            )
        }
        if (overview.projects.isEmpty()) {
            item(key = "projects-empty") {
                NoteRow(
                    icon = ForgeIcons.Info,
                    text = stringResource(R.string.storage_projects_empty),
                )
            }
        } else {
            items(
                items = overview.projects,
                key = { project -> "project-${project.novelId}" },
            ) { project ->
                ProjectUsageRow(project = project)
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

// ---------------------------------------------------------------------------
// Hero：总占用、Wavy 进度、三色分区条与图例
// ---------------------------------------------------------------------------

@Composable
private fun StorageHeroCard(overview: StorageOverview) {
    val spacing = MaterialTheme.spacing
    val capacity = overview.totalBytes + overview.usableBytes
    val usedRatio = if (capacity <= 0L) {
        0f
    } else {
        (overview.totalBytes.toDouble() / capacity.toDouble()).toFloat().coerceIn(0f, 1f)
    }
    val usedPercent = (usedRatio * 100f).roundToInt()

    ForgeCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.storage_total_used),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatByteSize(overview.totalBytes),
                    style = MaterialTheme.typography.displaySmallEmphasized,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stringResource(R.string.storage_free_space),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatByteSize(overview.usableBytes),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
            }
        }

        Text(
            text = stringResource(R.string.storage_v2_hero_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ForgeWavyProgress(progress = usedRatio)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.storage_v2_used_percent, usedPercent),
                style = MaterialTheme.typography.labelMediumEmphasized,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.storage_file_count, overview.fileCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        PartitionBar(overview = overview)

        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            overview.areas.forEach { usage ->
                PartitionLegendRow(
                    usage = usage,
                    share = overview.shareOf(usage.area),
                )
            }
        }

        if (overview.scanFailureCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                ForgeIcon(
                    icon = ForgeIcons.TriangleAlert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
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

/** 音频 / 导入 / 导出的三色分区条；无占用时只显示轨道。 */
@Composable
private fun PartitionBar(overview: StorageOverview) {
    val shares = overview.areas.map { usage -> overview.shareOf(usage.area) }
    val description = stringResource(
        R.string.storage_v2_partition_bar_description,
        (shares.getOrElse(0) { 0f } * 100f).roundToInt(),
        (shares.getOrElse(1) { 0f } * 100f).roundToInt(),
        (shares.getOrElse(2) { 0f } * 100f).roundToInt(),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .semantics { contentDescription = description },
    ) {
        if (overview.totalBytes > 0L) {
            overview.areas.forEach { usage ->
                val share = overview.shareOf(usage.area)
                if (share > 0f) {
                    Box(
                        modifier = Modifier
                            .weight(share)
                            .fillMaxHeight()
                            .background(areaColor(usage.area)),
                    )
                }
            }
        }
    }
}

@Composable
private fun PartitionLegendRow(usage: StorageAreaUsage, share: Float) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(areaColor(usage.area)),
        )
        ForgeIcon(
            icon = areaIcon(usage.area),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = areaLabel(usage.area),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(
                R.string.storage_v2_partition_size_share,
                formatByteSize(usage.bytes),
                (share * 100f).roundToInt(),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// 项目占用
// ---------------------------------------------------------------------------

@Composable
private fun ProjectUsageRow(project: StorageProjectUsage) {
    val title = project.title
        ?: stringResource(R.string.storage_project_unknown, project.novelId)
    val subtitle = if (project.chapterCount > 0) {
        stringResource(R.string.storage_project_chapters, project.chapterCount)
    } else {
        stringResource(R.string.storage_project_no_chapters)
    }
    ForgeListRow(
        icon = if (project.title != null) ForgeIcons.BookOpen else ForgeIcons.FolderOpen,
        title = title,
        subtitle = subtitle,
        trailingText = formatByteSize(project.bytes),
    )
}

// ---------------------------------------------------------------------------
// 保留期：快捷 chip + 滑块 + 数字输入 + 应用
// ---------------------------------------------------------------------------

@Composable
private fun RetentionCard(
    state: StorageUiState,
    onInputChange: (String) -> Unit,
    onSliderChange: (Int) -> Unit,
    onCommit: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val errorText = retentionErrorText(state.retentionError)
    val sliderDescription = stringResource(
        R.string.storage_retention_slider_description,
        state.retentionDays,
    )
    val quickOptions = QUICK_RETENTION_DAYS.map { days ->
        ForgeIcons.Clock to stringResource(R.string.storage_v2_retention_day_option, days)
    }

    ForgeCard {
        ForgeSectionHeader(
            title = stringResource(R.string.storage_retention_title),
            subtitle = stringResource(R.string.storage_v2_retention_section_subtitle),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.storage_retention_value, state.retentionDays),
                style = MaterialTheme.typography.titleMediumEmphasized,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.storage_retention_range),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ForgeSegmented(
            options = quickOptions,
            selectedIndex = QUICK_RETENTION_DAYS.indexOf(state.retentionDays),
            onSelect = { index ->
                QUICK_RETENTION_DAYS.getOrNull(index)?.let { days ->
                    onSliderChange(days)
                    onCommit()
                }
            },
        )
        Slider(
            value = state.retentionDays.toFloat(),
            onValueChange = { value -> onSliderChange(value.roundToInt()) },
            onValueChangeFinished = onCommit,
            valueRange = MIN_RETENTION_DAYS.toFloat()..MAX_RETENTION_DAYS.toFloat(),
            enabled = !state.isBusy,
            modifier = Modifier.semantics { contentDescription = sliderDescription },
        )
        OutlinedTextField(
            value = state.retentionInput,
            onValueChange = onInputChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isBusy,
            singleLine = true,
            label = { Text(stringResource(R.string.storage_retention_label)) },
            isError = errorText != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = {
                Text(
                    text = errorText ?: stringResource(R.string.storage_v2_retention_hint),
                    color = if (errorText != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
        )
        ForgePrimaryAction(
            icon = ForgeIcons.Check,
            label = stringResource(R.string.storage_v2_retention_apply),
            onClick = onCommit,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isBusy && state.retentionError == null,
        )
    }
}

// ---------------------------------------------------------------------------
// 清理：确认 → 进度 → 结果
// ---------------------------------------------------------------------------

@Composable
private fun CleanupCard(
    state: StorageUiState,
    onRequestCleanup: (StorageCleanupKind) -> Unit,
    onRetryCleanup: () -> Unit,
    onDismissResult: () -> Unit,
) {
    val overview = state.overview

    ForgeCard {
        ForgeSectionHeader(
            title = stringResource(R.string.storage_cleanup_title),
            subtitle = stringResource(R.string.storage_v2_cleanup_section_subtitle),
        )

        CleanupBlock(
            icon = ForgeIcons.Trash,
            title = stringResource(R.string.storage_cleanup_orphans_title),
            description = stringResource(R.string.storage_cleanup_orphans_desc),
            meta = stringResource(
                R.string.storage_cleanup_meta,
                overview?.orphanCount ?: 0,
                formatByteSize(overview?.orphanBytes ?: 0L),
            ),
            actionLabel = stringResource(R.string.storage_action_clean_orphans),
            enabled = state.canCleanOrphans,
            danger = false,
            onClick = { onRequestCleanup(StorageCleanupKind.ORPHANS) },
        )

        HorizontalDivider()

        CleanupBlock(
            icon = ForgeIcons.Clock,
            title = stringResource(R.string.storage_cleanup_expired_title),
            description = stringResource(
                R.string.storage_cleanup_expired_desc,
                state.retentionDays,
            ),
            meta = stringResource(
                R.string.storage_cleanup_meta,
                overview?.expiredCount ?: 0,
                formatByteSize(overview?.expiredBytes ?: 0L),
            ),
            actionLabel = stringResource(R.string.storage_action_clean_expired),
            enabled = state.canCleanExpired,
            danger = true,
            onClick = { onRequestCleanup(StorageCleanupKind.EXPIRED) },
        )

        NoteRow(
            icon = ForgeIcons.Lock,
            text = stringResource(R.string.storage_expired_protected),
        )

        val cleaning = state.cleaning
        when {
            cleaning != null -> CleaningRow(kind = cleaning)
            state.hasResult -> CleanupResultSection(
                state = state,
                onRetryCleanup = onRetryCleanup,
                onDismissResult = onDismissResult,
            )

            else -> NoteRow(
                icon = ForgeIcons.Info,
                text = stringResource(R.string.storage_cleanup_hint),
            )
        }
    }
}

@Composable
private fun CleanupBlock(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    meta: String,
    actionLabel: String,
    enabled: Boolean,
    danger: Boolean,
    onClick: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            IconBubble(icon = icon, danger = danger)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (danger) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
        ForgeSecondaryAction(
            icon = icon,
            label = actionLabel,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            danger = danger,
        )
    }
}

@Composable
private fun CleaningRow(kind: StorageCleanupKind) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
    ) {
        LoadingIndicator(modifier = Modifier.size(20.dp))
        Text(
            text = cleaningLabel(kind),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CleanupResultSection(
    state: StorageUiState,
    onRetryCleanup: () -> Unit,
    onDismissResult: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val result = state.lastCleanup
    val cleanupError = state.cleanupError
    val failed = result?.hasFailures == true || cleanupError != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        HorizontalDivider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            ForgeIcon(
                icon = if (failed) ForgeIcons.AlertCircle else ForgeIcons.CheckCircle,
                contentDescription = null,
                tint = if (failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Text(
                text = stringResource(
                    if (failed) {
                        R.string.storage_cleanup_result_failed_title
                    } else {
                        R.string.storage_cleanup_result_title
                    },
                ),
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = if (failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
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
                ResultNote(
                    text = stringResource(R.string.storage_cleanup_result_none_deleted),
                    error = false,
                )
            }
            if (result.failureCount > 0) {
                ResultNote(
                    text = stringResource(
                        R.string.storage_cleanup_result_failures,
                        result.failureCount,
                    ),
                    error = true,
                )
            }
            if (result.skippedReferencedCount > 0) {
                ResultNote(
                    text = stringResource(
                        R.string.storage_cleanup_result_skipped_referenced,
                        result.skippedReferencedCount,
                    ),
                    error = false,
                )
            }
            if (result.skippedTooRecentCount > 0) {
                ResultNote(
                    text = stringResource(
                        R.string.storage_cleanup_result_skipped_recent,
                        result.skippedTooRecentCount,
                    ),
                    error = false,
                )
            }
            if (result.skippedMissingCount > 0) {
                ResultNote(
                    text = stringResource(
                        R.string.storage_cleanup_result_skipped_missing,
                        result.skippedMissingCount,
                    ),
                    error = false,
                )
            }
        }
        if (cleanupError != null) {
            ResultNote(
                text = stringResource(R.string.storage_cleanup_error, cleanupError),
                error = true,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            if (state.canRetryCleanup) {
                ForgeSecondaryAction(
                    icon = ForgeIcons.Refresh,
                    label = stringResource(R.string.storage_cleanup_action_retry),
                    onClick = onRetryCleanup,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ForgeSecondaryAction(
                icon = ForgeIcons.Close,
                label = stringResource(R.string.storage_cleanup_action_dismiss),
                onClick = onDismissResult,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ResultNote(text: String, error: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
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
        icon = {
            ForgeIcon(
                icon = if (kind == StorageCleanupKind.ORPHANS) {
                    ForgeIcons.Trash
                } else {
                    ForgeIcons.Clock
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
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
                style = MaterialTheme.typography.titleLargeEmphasized,
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
            ForgeSecondaryAction(
                icon = ForgeIcons.Trash,
                label = stringResource(R.string.storage_cleanup_confirm_action),
                onClick = onConfirm,
                danger = true,
            )
        },
        dismissButton = {
            ForgeSecondaryAction(
                icon = ForgeIcons.Close,
                label = stringResource(R.string.action_cancel),
                onClick = onDismiss,
            )
        },
    )
}

// ---------------------------------------------------------------------------
// 共享小件
// ---------------------------------------------------------------------------

@Composable
private fun LoadErrorCard(message: String, onRetry: () -> Unit) {
    val spacing = MaterialTheme.spacing
    ForgeCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            ForgeIcon(
                icon = ForgeIcons.TriangleAlert,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
        }
        ForgeSecondaryAction(
            icon = ForgeIcons.Refresh,
            label = stringResource(R.string.action_retry),
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NoteRow(@DrawableRes icon: Int, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        ForgeIcon(
            icon = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun IconBubble(@DrawableRes icon: Int, danger: Boolean) {
    val containerColor = if (danger) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (danger) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        modifier = Modifier.size(44.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ForgeIcon(icon = icon, contentDescription = null, tint = contentColor)
        }
    }
}

// ---------------------------------------------------------------------------
// 文案映射
// ---------------------------------------------------------------------------

@Composable
private fun areaLabel(area: StorageArea): String = when (area) {
    StorageArea.AUDIO -> stringResource(R.string.storage_area_audio)
    StorageArea.IMPORTS -> stringResource(R.string.storage_area_imports)
    StorageArea.EXPORTS -> stringResource(R.string.storage_area_exports)
}

@DrawableRes
private fun areaIcon(area: StorageArea): Int = when (area) {
    StorageArea.AUDIO -> ForgeIcons.AudioLines
    StorageArea.IMPORTS -> ForgeIcons.FolderOpen
    StorageArea.EXPORTS -> ForgeIcons.FileText
}

@Composable
private fun areaColor(area: StorageArea): Color = when (area) {
    StorageArea.AUDIO -> MaterialTheme.colorScheme.primary
    StorageArea.IMPORTS -> MaterialTheme.colorScheme.secondary
    StorageArea.EXPORTS -> MaterialTheme.colorScheme.tertiary
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

/** 保留期快捷选项（天）；选中项与 [StorageUiState.retentionDays] 精确匹配。 */
private val QUICK_RETENTION_DAYS: List<Int> = listOf(7, 30, 90)

// ---------------------------------------------------------------------------
// Preview：正常 / 清理确认 / 空态 / 清理结果。文案全部来自 strings。
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

@Preview(name = "Storage Forge Normal", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun StorageNormalPreview() {
    AudioForgeTheme(darkTheme = false, dynamicColor = false) {
        StorageScreen(state = previewState())
    }
}

@Preview(name = "Storage Forge Cleanup Confirm", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun StorageCleanupConfirmPreview() {
    AudioForgeTheme(darkTheme = false, dynamicColor = false) {
        StorageScreen(
            state = previewState().copy(pendingCleanup = StorageCleanupKind.EXPIRED),
        )
    }
}

@Preview(
    name = "Storage Forge Empty Dark 200%",
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

@Preview(name = "Storage Forge Cleanup Result", showBackground = true, backgroundColor = 0xFFF7F8FC)
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
