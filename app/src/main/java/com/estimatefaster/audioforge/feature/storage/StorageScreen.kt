@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.estimatefaster.audioforge.feature.storage

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.ui.components.EmptyState
import com.estimatefaster.audioforge.ui.components.ErrorBanner
import com.estimatefaster.audioforge.ui.components.ErrorState
import com.estimatefaster.audioforge.ui.components.ExpressiveCard
import com.estimatefaster.audioforge.ui.components.ExpressiveTextButton
import com.estimatefaster.audioforge.ui.components.ExpressiveTonalButton
import com.estimatefaster.audioforge.ui.components.LoadingState
import com.estimatefaster.audioforge.ui.components.SectionHeader
import com.estimatefaster.audioforge.ui.components.StatusPill
import com.estimatefaster.audioforge.ui.components.StatusTone
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing
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
 * 无状态存储页（Expressive 布局）。
 *
 * 布局：
 * - 顶部：返回、标题、刷新；
 * - Hero：primaryContainer 大圆角容器，总占用 displaySmall、可用空间/文件数、
 *   音频/导入/导出三色 Wavy 分区条与图例；
 * - 按项目：每个项目一张 tonal 卡片，已删除项目的残留显示为「未关联项目」；
 * - 保留期：7..365 天滑块 + 数字输入，非法输入就地提示；
 * - 清理：孤儿/过期两个 tonal 行动区，确认弹窗与结果卡沿用同一状态机；
 * - 空态、加载、错误复用共享组件。
 *
 * 所有数字与文案来自 [StorageUiState] 和 `strings.xml`，本文件不做统计或清理。
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
                        style = MaterialTheme.typography.titleLargeEmphasized,
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
    val spacing = MaterialTheme.spacing

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        if (state.isLoading) {
            item(key = "refreshing") {
                LinearWavyProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.xs),
                )
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
                    StorageHero(overview = overview)
                }
                item(key = "projects-header") {
                    SectionHeader(
                        title = stringResource(R.string.storage_projects_title),
                        subtitle = stringResource(
                            R.string.storage_projects_subtitle,
                            overview.projects.size,
                        ),
                    )
                }
                if (overview.projects.isEmpty()) {
                    item(key = "projects-empty") {
                        EmptyProjectsCard()
                    }
                } else {
                    items(
                        items = overview.projects,
                        key = { project -> project.novelId },
                    ) { project ->
                        ProjectUsageCard(project = project)
                    }
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
private fun StorageHero(overview: StorageOverview) {
    val spacing = MaterialTheme.spacing
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraExtraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.storage_overview_title),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                Text(
                    text = stringResource(R.string.storage_file_count, overview.fileCount),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = spacing.sm),
                    textAlign = TextAlign.End,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                Text(
                    text = stringResource(R.string.storage_total_used),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = formatByteSize(overview.totalBytes),
                    style = MaterialTheme.typography.displaySmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.xl),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.storage_free_space),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = formatByteSize(overview.usableBytes),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                    )
                }
                Text(
                    text = stringResource(R.string.storage_overview_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
            }
            SegmentedUsageBar(overview = overview, modifier = Modifier.fillMaxWidth())
            OverviewLegend(overview = overview)
            if (overview.scanFailureCount > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    StatusPill(
                        text = stringResource(R.string.storage_scan_failures_pill),
                        tone = StatusTone.Danger,
                    )
                    Text(
                        text = stringResource(
                            R.string.storage_scan_failures,
                            overview.scanFailureCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SegmentedUsageBar(
    overview: StorageOverview,
    modifier: Modifier = Modifier,
) {
    val shares = overview.areas.map { usage -> overview.shareOf(usage.area) }
    val percents = shares.map { share -> (share * 100f).roundToInt() }
    val description = stringResource(
        R.string.storage_segment_bar_description,
        percents.getOrElse(0) { 0 },
        percents.getOrElse(1) { 0 },
        percents.getOrElse(2) { 0 },
    )
    val scheme = MaterialTheme.colorScheme
    val segmentColors = listOf(
        scheme.primary,
        scheme.secondary,
        scheme.tertiary,
    )
    val trackColor = scheme.onPrimaryContainer.copy(alpha = 0.18f)

    Canvas(
        modifier = modifier
            .height(20.dp)
            .semantics { contentDescription = description },
    ) {
        val stroke = 8.dp.toPx()
        val gap = 4.dp.toPx()
        val centerY = size.height / 2f
        drawLine(
            color = trackColor,
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        val visibleSegments = shares.count { share -> share > 0f }
        val total = shares.sum()
        if (total <= 0f || visibleSegments == 0) return@Canvas

        val available = (size.width - gap * (visibleSegments - 1)).coerceAtLeast(0f)
        var startX = 0f
        shares.forEachIndexed { index, share ->
            if (share <= 0f) return@forEachIndexed
            val width = available * (share / total)
            if (width > 1f) {
                val path = wavyLinePath(
                    startX = startX,
                    endX = startX + width,
                    centerY = centerY,
                    amplitude = 3.dp.toPx(),
                    wavelength = 18.dp.toPx(),
                )
                drawPath(
                    path = path,
                    color = segmentColors.getOrElse(index) { segmentColors.last() },
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            startX += width + gap
        }
    }
}

private fun wavyLinePath(
    startX: Float,
    endX: Float,
    centerY: Float,
    amplitude: Float,
    wavelength: Float,
): Path {
    val path = Path()
    path.moveTo(startX, centerY)
    var x = startX
    var controlAbove = true
    val halfWave = (wavelength / 2f).coerceAtLeast(1f)
    while (x < endX) {
        val next = (x + halfWave).coerceAtMost(endX)
        path.quadraticBezierTo(
            (x + next) / 2f,
            if (controlAbove) centerY - amplitude else centerY + amplitude,
            next,
            centerY,
        )
        x = next
        controlAbove = !controlAbove
    }
    return path
}

@Composable
private fun OverviewLegend(overview: StorageOverview) {
    val spacing = MaterialTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val colors = listOf(scheme.primary, scheme.secondary, scheme.tertiary)
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        overview.areas.forEachIndexed { index, usage ->
            val percent = (overview.shareOf(usage.area) * 100f).roundToInt()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(colors.getOrElse(index) { colors.last() }),
                )
                Text(
                    text = areaLabel(usage.area),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatByteSize(usage.bytes),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = stringResource(R.string.storage_share_percent, percent),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.widthIn(min = 44.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun EmptyProjectsCard() {
    val spacing = MaterialTheme.spacing
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text(
            text = stringResource(R.string.storage_projects_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(spacing.lg),
        )
    }
}

@Composable
private fun ProjectUsageCard(project: StorageProjectUsage) {
    val spacing = MaterialTheme.spacing
    val title = project.title
        ?: stringResource(R.string.storage_project_unknown, project.novelId)
    val meta = if (project.chapterCount > 0) {
        stringResource(R.string.storage_project_chapters, project.chapterCount)
    } else {
        stringResource(R.string.storage_project_no_chapters)
    }
    val badge = title.take(1)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = formatByteSize(project.bytes),
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

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
    ExpressiveCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = stringResource(R.string.storage_retention_title),
            subtitle = stringResource(R.string.storage_retention_subtitle),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.storage_retention_value, state.retentionDays),
                style = MaterialTheme.typography.titleMediumEmphasized,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.storage_retention_range),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.xs),
                )
            }
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
            shape = MaterialTheme.shapes.large,
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

@Composable
private fun CleanupCard(
    state: StorageUiState,
    onRequestCleanup: (StorageCleanupKind) -> Unit,
    onRetryCleanup: () -> Unit,
    onDismissResult: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    ExpressiveCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = stringResource(R.string.storage_cleanup_title),
            subtitle = stringResource(R.string.storage_cleanup_subtitle),
        )
        CleanupActionCard(
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
        CleanupActionCard(
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
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(spacing.md)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    LinearWavyProgressIndicator(modifier = Modifier.weight(1f))
                    Text(
                        text = cleaningLabel(cleaning),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else if (state.hasResult) {
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

@Composable
private fun CleanupActionCard(
    title: String,
    description: String,
    meta: String,
    actionText: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmallEmphasized,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                ExpressiveTonalButton(
                    text = actionText,
                    onClick = onClick,
                    enabled = enabled,
                )
            }
        }
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

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            StatusPill(
                text = stringResource(
                    if (failed) {
                        R.string.storage_cleanup_result_failed_title
                    } else {
                        R.string.storage_cleanup_result_title
                    },
                ),
                tone = if (failed) StatusTone.Danger else StatusTone.Success,
            )
            if (result != null) {
                Text(
                    text = stringResource(
                        R.string.storage_cleanup_result_summary,
                        formatByteSize(result.freedBytes),
                        result.deletedCount,
                    ),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                if (result.deletedNothing) {
                    Text(
                        text = stringResource(R.string.storage_cleanup_result_none_deleted),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (result.failureCount > 0) {
                    Text(
                        text = stringResource(
                            R.string.storage_cleanup_result_failures,
                            result.failureCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (result.skippedReferencedCount > 0) {
                    Text(
                        text = stringResource(
                            R.string.storage_cleanup_result_skipped_referenced,
                            result.skippedReferencedCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (result.skippedTooRecentCount > 0) {
                    Text(
                        text = stringResource(
                            R.string.storage_cleanup_result_skipped_recent,
                            result.skippedTooRecentCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (result.skippedMissingCount > 0) {
                    Text(
                        text = stringResource(
                            R.string.storage_cleanup_result_skipped_missing,
                            result.skippedMissingCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (cleanupError != null) {
                Text(
                    text = stringResource(R.string.storage_cleanup_error, cleanupError),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.xs),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.canRetryCleanup) {
                    ExpressiveTonalButton(
                        text = stringResource(R.string.storage_cleanup_action_retry),
                        onClick = onRetryCleanup,
                        enabled = !state.isBusy,
                    )
                    Spacer(Modifier.width(spacing.sm))
                }
                ExpressiveTextButton(
                    text = stringResource(R.string.storage_cleanup_action_dismiss),
                    onClick = onDismissResult,
                )
            }
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
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(
                    text = stringResource(R.string.storage_cleanup_confirm_action),
                    style = MaterialTheme.typography.labelLargeEmphasized,
                )
            }
        },
        dismissButton = {
            ExpressiveTextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
            )
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

@Preview(name = "Storage Normal", showBackground = true)
@Composable
private fun StorageNormalPreview() {
    AudioForgeTheme(darkTheme = false, dynamicColor = false) {
        StorageScreen(state = previewState())
    }
}

@Preview(name = "Storage Cleanup Confirm", showBackground = true)
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

@Preview(name = "Storage Cleanup Result", showBackground = true)
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
