@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.estimatefaster.audioforge.feature.project

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.data.db.ChapterBrief
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.data.db.ProjectSummary
import com.estimatefaster.audioforge.ui.components.EmptyState
import com.estimatefaster.audioforge.ui.components.ErrorState
import com.estimatefaster.audioforge.ui.components.ExpressiveCard
import com.estimatefaster.audioforge.ui.components.ExpressiveLoading
import com.estimatefaster.audioforge.ui.components.ExpressiveOutlinedButton
import com.estimatefaster.audioforge.ui.components.ExpressivePrimaryButton
import com.estimatefaster.audioforge.ui.components.ExpressiveTonalButton
import com.estimatefaster.audioforge.ui.components.ExpressiveWavyProgress
import com.estimatefaster.audioforge.ui.components.ProgressRing
import com.estimatefaster.audioforge.ui.components.RoleAvatar
import com.estimatefaster.audioforge.ui.components.SectionHeader
import com.estimatefaster.audioforge.ui.components.StatusKind
import com.estimatefaster.audioforge.ui.components.StatusPill
import com.estimatefaster.audioforge.ui.components.StatusTone
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing
import kotlin.math.roundToInt

/**
 * 项目详情页（P4-05 / 执行计划 §12.4 S04）。
 *
 * 有状态入口：从 [ProjectViewModel.state] 收集状态后委托给无状态版本，
 * 方便 Preview 与后续导航层复用。Expressive 改版只调整呈现，状态机与回调不变。
 */
@Composable
fun ProjectScreen(
    viewModel: ProjectViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onStartBatch: () -> Unit = {},
    onStopBatch: () -> Unit = {},
    onRename: (String) -> Unit = {},
    onDelete: () -> Unit = {},
    batchRunnerAvailable: Boolean = false,
    onOpenChapters: () -> Unit = {},
    onOpenCharacters: () -> Unit = {},
    onOpenPlayer: () -> Unit = {},
    onOpenExport: () -> Unit = {},
    onOpenStorage: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProjectScreen(
        state = state,
        modifier = modifier,
        onBack = onBack,
        onRetry = viewModel::retry,
        onStartBatch = onStartBatch,
        onStopBatch = onStopBatch,
        onRename = onRename,
        onDelete = onDelete,
        batchRunnerAvailable = batchRunnerAvailable,
        onOpenChapters = onOpenChapters,
        onOpenCharacters = onOpenCharacters,
        onOpenPlayer = onOpenPlayer,
        onOpenExport = onOpenExport,
        onOpenStorage = onOpenStorage,
        onOpenAbout = onOpenAbout,
    )
}

/**
 * 无状态项目详情页的 Expressive 布局：
 * - primaryContainer 大圆角 hero 承载书名与来源；
 * - 进度卡用 ProgressRing + Wavy 同时表达章节/片段两级进度；
 * - 控制卡只保留一个高饱和“开始生成”主按钮，停止为次级操作；
 * - 告警、人物、更多入口统一使用 [ExpressiveCard] 与主题间距。
 *
 * 批次执行尚未接入（P3-04）：[batchRunnerAvailable] 为 false 时开始/停止禁用，
 * 并显示“任务模块接入后可开始”。回调只做 UI 解耦，不直接访问仓库。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    state: ProjectUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onRetry: () -> Unit = {},
    onStartBatch: () -> Unit = {},
    onStopBatch: () -> Unit = {},
    onRename: (String) -> Unit = {},
    onDelete: () -> Unit = {},
    batchRunnerAvailable: Boolean = false,
    onOpenChapters: () -> Unit = {},
    onOpenCharacters: () -> Unit = {},
    onOpenPlayer: () -> Unit = {},
    onOpenExport: () -> Unit = {},
    onOpenStorage: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var renameText by rememberSaveable { mutableStateOf("") }

    val project = state.project
    val loadError = state.loadError

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
                        text = stringResource(R.string.project_screen_title),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                actions = {
                    if (project != null) {
                        Box {
                            TextButton(
                                onClick = { menuExpanded = true },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.ux_project_manage_menu))
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_rename)) },
                                    onClick = {
                                        menuExpanded = false
                                        renameText = project.title
                                        showRenameDialog = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = stringResource(R.string.action_delete),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        showDeleteDialog = true
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                ExpressiveLoading(label = stringResource(R.string.project_loading))
            }

            loadError != null -> CenteredError(
                message = stringResource(R.string.project_load_error, loadError),
                onRetry = onRetry,
                modifier = Modifier.padding(padding),
            )

            project == null -> CenteredError(
                message = stringResource(R.string.project_missing_message),
                onRetry = onRetry,
                modifier = Modifier.padding(padding),
            )

            state.isEmpty -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    title = stringResource(R.string.project_empty_title),
                    description = stringResource(R.string.project_empty_description),
                    actionText = stringResource(R.string.project_empty_action),
                    onAction = onBack,
                )
            }

            else -> ProjectContent(
                state = state,
                project = project,
                contentPadding = padding,
                batchRunnerAvailable = batchRunnerAvailable,
                onStartBatch = onStartBatch,
                onStopBatch = onStopBatch,
                onOpenChapters = onOpenChapters,
                onOpenCharacters = onOpenCharacters,
                onOpenPlayer = onOpenPlayer,
                onOpenExport = onOpenExport,
                onOpenStorage = onOpenStorage,
                onOpenAbout = onOpenAbout,
            )
        }
    }

    if (showRenameDialog && project != null) {
        RenameDialog(
            title = project.title,
            value = renameText,
            onValueChange = { renameText = it },
            onDismiss = { showRenameDialog = false },
            onConfirm = { newTitle ->
                showRenameDialog = false
                onRename(newTitle)
            },
        )
    }

    if (showDeleteDialog && project != null) {
        DeleteDialog(
            title = project.title,
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                showDeleteDialog = false
                onDelete()
            },
        )
    }
}

@Composable
private fun CenteredError(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        ErrorState(message = message, onRetry = onRetry)
    }
}

@Composable
private fun ProjectContent(
    state: ProjectUiState,
    project: ProjectHeaderUi,
    contentPadding: PaddingValues,
    batchRunnerAvailable: Boolean,
    onStartBatch: () -> Unit,
    onStopBatch: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val stats = state.chapterStats
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(
            horizontal = MaterialTheme.spacing.lg,
            vertical = MaterialTheme.spacing.md,
        ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
    ) {
        item(key = "hero") {
            ProjectHero(
                project = project,
                stats = stats,
                onOpenChapters = onOpenChapters,
            )
        }
        item(key = "progress") {
            ProgressCard(stats = stats)
        }
        item(key = "control") {
            ControlCard(
                batchRunnerAvailable = batchRunnerAvailable,
                running = stats.running > 0,
                onStartBatch = onStartBatch,
                onStopBatch = onStopBatch,
            )
        }
        if (state.warnings.hasWarnings || stats.error > 0) {
            item(key = "warnings") {
                WarningCard(
                    warnings = state.warnings,
                    errorCount = stats.error,
                    onOpenChapters = onOpenChapters,
                )
            }
        }
        if (state.characters.isNotEmpty()) {
            item(key = "characters") {
                CharacterCard(
                    characters = state.characters,
                    hiddenCount = state.hiddenCharacterCount,
                    onOpenCharacters = onOpenCharacters,
                )
            }
        }
        item(key = "more") {
            MoreCard(
                onOpenChapters = onOpenChapters,
                onOpenCharacters = onOpenCharacters,
                onOpenPlayer = onOpenPlayer,
                onOpenExport = onOpenExport,
                onOpenStorage = onOpenStorage,
                onOpenAbout = onOpenAbout,
            )
        }
    }
}

@Composable
private fun ProjectHero(
    project: ProjectHeaderUi,
    stats: ChapterStatsUi,
    onOpenChapters: () -> Unit,
) {
    val content = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraExtraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = content,
    ) {
        Box {
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .clearAndSetSemantics { },
            ) {
                drawCircle(
                    color = content.copy(alpha = 0.08f),
                    radius = size.minDimension * 0.55f,
                    center = Offset(size.width * 0.92f, size.height * 0.08f),
                )
                drawCircle(
                    color = content.copy(alpha = 0.05f),
                    radius = size.minDimension * 0.35f,
                    center = Offset(size.width * 0.72f, size.height * 0.98f),
                )
            }
            Column(
                modifier = Modifier.padding(MaterialTheme.spacing.xl),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            ) {
                Text(
                    text = stringResource(R.string.ux_project_hero_label),
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    color = content.copy(alpha = 0.8f),
                )
                Text(
                    text = project.title,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.project_header_meta,
                        project.sourceName,
                        project.charCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = content.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(top = MaterialTheme.spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                ) {
                    StatusPill(
                        text = stringResource(R.string.project_chapter_total, stats.total),
                        tone = StatusTone.Neutral,
                    )
                    StatusPill(
                        kind = StatusKind.Parse,
                        status = stats.parseAggregateStatus(),
                    )
                    StatusPill(
                        kind = StatusKind.Audio,
                        status = stats.audioAggregateStatus(),
                    )
                }
                if (stats.total > 0) {
                    ExpressiveTonalButton(
                        text = stringResource(R.string.ux_project_hero_open_chapters),
                        onClick = onOpenChapters,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(stats: ChapterStatsUi) {
    val segmentProgress = if (stats.segmentsTotal <= 0) {
        0f
    } else {
        (stats.segmentsDone.toFloat() / stats.segmentsTotal).coerceIn(0f, 1f)
    }
    val segmentPercent = (segmentProgress * 100f).roundToInt()
    val segmentDescription = stringResource(
        R.string.ux_project_progress_wavy_content_description,
        segmentPercent,
    )

    ExpressiveCard {
        SectionHeader(title = stringResource(R.string.project_progress_title))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg),
        ) {
            ProgressRing(
                progress = stats.progress,
                label = stringResource(R.string.project_progress_ring_label),
                diameter = 104.dp,
                strokeWidth = 9.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            ) {
                Text(
                    text = stringResource(
                        R.string.project_progress_chapters,
                        stats.done,
                        stats.total,
                    ),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                Text(
                    text = stringResource(
                        R.string.project_progress_segments,
                        stats.segmentsDone,
                        stats.segmentsTotal,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                ) {
                    StatusPill(
                        text = stringResource(
                            R.string.project_chapter_stat_done,
                            stats.done,
                        ),
                        tone = StatusTone.Success,
                    )
                    StatusPill(
                        text = stringResource(
                            R.string.project_chapter_stat_running,
                            stats.running,
                        ),
                        tone = StatusTone.Running,
                    )
                    StatusPill(
                        text = stringResource(
                            R.string.project_chapter_stat_error,
                            stats.error,
                        ),
                        tone = StatusTone.Danger,
                    )
                    if (stats.pending > 0) {
                        StatusPill(
                            text = stringResource(
                                R.string.project_chapter_stat_pending,
                                stats.pending,
                            ),
                            tone = StatusTone.Neutral,
                        )
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
            Text(
                text = stringResource(R.string.ux_project_progress_wavy_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ExpressiveWavyProgress(
                progress = segmentProgress,
                modifier = Modifier.semantics { contentDescription = segmentDescription },
            )
        }
    }
}

@Composable
private fun ControlCard(
    batchRunnerAvailable: Boolean,
    running: Boolean,
    onStartBatch: () -> Unit,
    onStopBatch: () -> Unit,
) {
    ExpressiveCard {
        SectionHeader(title = stringResource(R.string.project_control_title))
        Text(
            text = stringResource(R.string.ux_project_control_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExpressivePrimaryButton(
            text = stringResource(R.string.batch_action_start),
            onClick = onStartBatch,
            enabled = batchRunnerAvailable && !running,
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            !batchRunnerAvailable -> Text(
                text = stringResource(R.string.project_control_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            running -> {
                Text(
                    text = stringResource(R.string.project_control_running),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ExpressiveOutlinedButton(
                    text = stringResource(R.string.batch_action_stop),
                    onClick = onStopBatch,
                    enabled = batchRunnerAvailable,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            else -> Text(
                text = stringResource(R.string.ux_project_control_ready),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WarningCard(
    warnings: WarningSummaryUi,
    errorCount: Int,
    onOpenChapters: () -> Unit,
) {
    ExpressiveCard(onClick = onOpenChapters) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "!",
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
            ) {
                Text(
                    text = stringResource(R.string.project_warning_title),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                if (warnings.hasWarnings) {
                    Text(
                        text = stringResource(
                            R.string.project_warning_summary,
                            warnings.warningCount,
                            warnings.summary,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (errorCount > 0) {
                    Text(
                        text = stringResource(R.string.project_error_summary, errorCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CharacterCard(
    characters: List<CharacterUi>,
    hiddenCount: Int,
    onOpenCharacters: () -> Unit,
) {
    ExpressiveCard {
        SectionHeader(
            title = stringResource(R.string.project_characters_title),
            subtitle = stringResource(
                R.string.project_characters_subtitle,
                characters.size + hiddenCount,
            ),
            actionText = stringResource(R.string.action_view_all),
            onAction = onOpenCharacters,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
            contentPadding = PaddingValues(vertical = MaterialTheme.spacing.xs),
        ) {
            items(items = characters, key = { it.roleId }) { character ->
                CharacterItem(character = character)
            }
            if (hiddenCount > 0) {
                item(key = "hidden-characters") {
                    MoreCharactersBadge(count = hiddenCount)
                }
            }
        }
    }
}

@Composable
private fun CharacterItem(character: CharacterUi) {
    Column(
        modifier = Modifier
            .widthIn(min = 64.dp, max = 88.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 头像旁已有可见名字，装饰化以避免同一个人物被读两遍。
        RoleAvatar(name = character.name, decorative = true)
        Spacer(Modifier.height(MaterialTheme.spacing.xs))
        Text(
            text = character.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MoreCharactersBadge(count: Int) {
    val description = stringResource(R.string.project_characters_more_content_description, count)
    Column(
        modifier = Modifier.widthIn(min = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clearAndSetSemantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.project_characters_more_badge, count),
                style = MaterialTheme.typography.labelLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(MaterialTheme.spacing.xs))
        Text(
            text = stringResource(R.string.project_characters_more, count),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MoreCard(
    onOpenChapters: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    ExpressiveCard {
        SectionHeader(
            title = stringResource(R.string.ux_project_more_title),
            subtitle = stringResource(R.string.ux_project_more_subtitle),
        )
        MoreActionRow(
            label = stringResource(R.string.project_more_chapters),
            onClick = onOpenChapters,
        )
        MoreActionRow(
            label = stringResource(R.string.project_more_characters),
            onClick = onOpenCharacters,
        )
        MoreActionRow(
            label = stringResource(R.string.project_more_player),
            onClick = onOpenPlayer,
        )
        MoreActionRow(
            label = stringResource(R.string.project_more_export),
            onClick = onOpenExport,
        )
        MoreActionRow(
            label = stringResource(R.string.project_more_storage),
            onClick = onOpenStorage,
        )
        MoreActionRow(
            label = stringResource(R.string.project_more_about),
            onClick = onOpenAbout,
            showDivider = false,
        )
    }
}

@Composable
private fun MoreActionRow(
    label: String,
    onClick: () -> Unit,
    showDivider: Boolean = true,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.large)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(
                    horizontal = MaterialTheme.spacing.sm,
                    vertical = MaterialTheme.spacing.md,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLargeEmphasized,
            )
            Text(
                text = "›",
                modifier = Modifier.clearAndSetSemantics { },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (showDivider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun RenameDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val trimmed = value.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.project_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text(stringResource(R.string.project_rename_label)) },
                    singleLine = true,
                    isError = trimmed.isEmpty(),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (trimmed.isEmpty()) {
                    Text(
                        text = stringResource(R.string.project_rename_empty_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (trimmed != title) {
                    Text(
                        text = stringResource(R.string.project_rename_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.action_save))
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
private fun DeleteDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.project_delete_title)) },
        text = { Text(stringResource(R.string.project_delete_message, title)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
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

// ---------------------------------------------------------------------------
// Preview：正常 / 空项目 / 错误态 / 深色 200%。所有文案来自 strings.xml。
// ---------------------------------------------------------------------------

private fun previewProject(
    id: Long = 1L,
    title: String = "长夜将明",
    charCount: Int = 156_000,
): ProjectSummary = ProjectSummary(
    id = id,
    title = title,
    sourceName = "$title.txt",
    charCount = charCount,
    createdAt = 0L,
    lastOpenedAt = null,
    chapterCount = 0,
    chapterDoneCount = 0,
    segmentCount = 0,
    segmentDoneCount = 0,
)

private fun previewChapter(
    id: Long,
    parseStatus: String = "pending",
    audioStatus: String = "pending",
    parseWarn: String? = null,
    segCount: Int = 0,
    segDone: Int = 0,
): ChapterBrief = ChapterBrief(
    id = id,
    idx = (id - 1).toInt(),
    title = "第${id}章",
    parseStatus = parseStatus,
    audioStatus = audioStatus,
    parseWarn = parseWarn,
    segCount = segCount,
    segDone = segDone,
)

private fun previewCharacters(): List<CharacterEntity> = listOf(
    CharacterEntity(id = 1, novelId = 1, roleId = "narrator", name = "旁白"),
    CharacterEntity(id = 2, novelId = 1, roleId = "role_0002", name = "林黛玉"),
    CharacterEntity(id = 3, novelId = 1, roleId = "role_0003", name = "贾宝玉"),
)

private fun previewNormalState(): ProjectUiState = buildProjectUiState(
    novelId = 1L,
    projects = listOf(previewProject()),
    chapters = listOf(
        previewChapter(1, parseStatus = "done", audioStatus = "done", segCount = 12, segDone = 12),
        previewChapter(2, parseStatus = "done", audioStatus = "done", segCount = 9, segDone = 9),
        previewChapter(3, parseStatus = "done", audioStatus = "running", segCount = 14, segDone = 8),
        previewChapter(
            4,
            parseStatus = "error",
            audioStatus = "pending",
            parseWarn = "覆盖率 42%，低于阈值",
        ),
        previewChapter(5, parseStatus = "done", audioStatus = "pending"),
    ),
    characters = previewCharacters(),
)

private fun previewEmptyState(): ProjectUiState = buildProjectUiState(
    novelId = 1L,
    projects = listOf(previewProject(title = "空项目", charCount = 0)),
    chapters = emptyList(),
    characters = emptyList(),
)

@Preview(
    name = "ProjectScreen Normal",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun ProjectScreenNormalPreview() {
    AudioForgeTheme(dynamicColor = false) {
        ProjectScreen(
            state = previewNormalState(),
            batchRunnerAvailable = true,
        )
    }
}

@Preview(
    name = "ProjectScreen Empty",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun ProjectScreenEmptyPreview() {
    AudioForgeTheme(dynamicColor = false) {
        ProjectScreen(state = previewEmptyState())
    }
}

@Preview(
    name = "ProjectScreen Error",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun ProjectScreenErrorPreview() {
    AudioForgeTheme(dynamicColor = false) {
        ProjectScreen(
            state = ProjectUiState(loading = false, loadError = "database is locked"),
        )
    }
}

@Preview(
    name = "ProjectScreen Dark 200%",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun ProjectScreenDarkPreview() {
    AudioForgeTheme(darkTheme = true, dynamicColor = false) {
        ProjectScreen(
            state = previewNormalState(),
            batchRunnerAvailable = true,
        )
    }
}
