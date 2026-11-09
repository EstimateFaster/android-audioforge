@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.estimatefaster.audioforge.feature.project

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.data.db.ChapterBrief
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.data.db.ProjectSummary
import com.estimatefaster.audioforge.ui.forge.ForgeCard
import com.estimatefaster.audioforge.ui.forge.ForgeDialog
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
import com.estimatefaster.audioforge.ui.forge.ForgeTopBar
import com.estimatefaster.audioforge.ui.forge.ForgeWavyProgress
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing

/**
 * 项目详情页（P4-05 / 执行计划 §12.4 S04）。
 *
 * 有状态入口：从 [ProjectViewModel.state] 收集状态后委托给无状态版本。
 * Forge Framework 重写只改变呈现，公开回调、加载/空/错误分支保持不变。
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
 * 无状态项目详情页。
 *
 * 结构对齐 `docs/prototype/forge-framework.html` 的项目详情：
 * 顶部 ForgeTopBar（播放全书 / 更多）→ 封面 hero → 整体进度（Wavy）→
 * 告警 → 人物条 → 章节入口 → 底部固定 action dock。
 *
 * 正常态只有「继续生成」一个实心主操作；运行中额外提供次级「停止生成」。
 * 批次执行尚未接入（P3-04）：[batchRunnerAvailable] 为 false 时主操作禁用，
 * 并显示“任务模块接入后可开始”。回调只做 UI 解耦，不直接访问仓库。
 */
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
    val stats = state.chapterStats

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ForgeTopBar(
                title = stringResource(R.string.ux2_project_title),
                subtitle = project?.let {
                    stringResource(R.string.ux2_project_topbar_subtitle, it.title, stats.total)
                },
                navigationIcon = ForgeIcons.Back,
                navigationDescription = stringResource(R.string.action_back),
                onNavigation = onBack,
                actions = {
                    if (project != null) {
                        ForgeIconAction(
                            icon = ForgeIcons.Headphones,
                            contentDescription = stringResource(R.string.ux2_project_play_all),
                            onClick = onOpenPlayer,
                            container = false,
                        )
                        Box {
                            ForgeIconAction(
                                icon = ForgeIcons.More,
                                contentDescription = stringResource(R.string.ux2_project_more),
                                onClick = { menuExpanded = true },
                                container = false,
                            )
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                ProjectMenuItem(
                                    icon = ForgeIcons.List,
                                    label = stringResource(R.string.project_more_chapters),
                                ) {
                                    menuExpanded = false
                                    onOpenChapters()
                                }
                                ProjectMenuItem(
                                    icon = ForgeIcons.Users,
                                    label = stringResource(R.string.project_more_characters),
                                ) {
                                    menuExpanded = false
                                    onOpenCharacters()
                                }
                                ProjectMenuItem(
                                    icon = ForgeIcons.Headphones,
                                    label = stringResource(R.string.project_more_player),
                                ) {
                                    menuExpanded = false
                                    onOpenPlayer()
                                }
                                ProjectMenuItem(
                                    icon = ForgeIcons.Download,
                                    label = stringResource(R.string.project_more_export),
                                ) {
                                    menuExpanded = false
                                    onOpenExport()
                                }
                                ProjectMenuItem(
                                    icon = ForgeIcons.Storage,
                                    label = stringResource(R.string.project_more_storage),
                                ) {
                                    menuExpanded = false
                                    onOpenStorage()
                                }
                                ProjectMenuItem(
                                    icon = ForgeIcons.Info,
                                    label = stringResource(R.string.project_more_about),
                                ) {
                                    menuExpanded = false
                                    onOpenAbout()
                                }
                                ProjectMenuItem(
                                    icon = ForgeIcons.Edit,
                                    label = stringResource(R.string.action_rename),
                                ) {
                                    menuExpanded = false
                                    renameText = project.title
                                    showRenameDialog = true
                                }
                                ProjectMenuItem(
                                    icon = ForgeIcons.Trash,
                                    label = stringResource(R.string.action_delete),
                                    danger = true,
                                ) {
                                    menuExpanded = false
                                    showDeleteDialog = true
                                }
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (state.hasChapters) {
                ContinueDock(
                    stats = stats,
                    batchRunnerAvailable = batchRunnerAvailable,
                    onStartBatch = onStartBatch,
                    onStopBatch = onStopBatch,
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> ForgeLoading(
                label = stringResource(R.string.project_loading),
                modifier = Modifier.padding(padding),
            )

            loadError != null -> ForgeError(
                message = stringResource(R.string.project_load_error, loadError),
                modifier = Modifier.padding(padding),
                onRetry = onRetry,
                retryLabel = stringResource(R.string.action_retry),
            )

            state.isMissing -> ForgeError(
                message = stringResource(R.string.project_missing_message),
                modifier = Modifier.padding(padding),
                onRetry = onRetry,
                retryLabel = stringResource(R.string.action_retry),
            )

            state.isEmpty -> ForgeEmpty(
                icon = ForgeIcons.BookOpen,
                title = stringResource(R.string.ux2_project_empty_title),
                description = stringResource(R.string.ux2_project_empty_description),
                modifier = Modifier.padding(padding),
                actionIcon = ForgeIcons.Back,
                actionLabel = stringResource(R.string.project_empty_action),
                onAction = onBack,
            )

            else -> ProjectContent(
                state = state,
                contentPadding = padding,
                onOpenChapters = onOpenChapters,
                onOpenCharacters = onOpenCharacters,
            )
        }
    }

    if (showRenameDialog && project != null) {
        RenameProjectDialog(
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
        ForgeDialog(
            title = stringResource(R.string.project_delete_title),
            message = stringResource(R.string.project_delete_message, project.title),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                showDeleteDialog = false
                onDelete()
            },
            onDismiss = { showDeleteDialog = false },
            icon = ForgeIcons.Trash,
            danger = true,
        )
    }
}

@Composable
private fun ProjectMenuItem(
    @DrawableRes icon: Int,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        onClick = onClick,
        leadingIcon = {
            ForgeIcon(
                icon = icon,
                contentDescription = null,
                tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun ProjectContent(
    state: ProjectUiState,
    contentPadding: PaddingValues,
    onOpenChapters: () -> Unit,
    onOpenCharacters: () -> Unit,
) {
    val project = state.project ?: return
    val stats = state.chapterStats
    val characterCount = state.characters.size + state.hiddenCharacterCount

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
            ProjectHero(project = project, stats = stats, characterCount = characterCount)
        }
        item(key = "progress") {
            ProgressCard(stats = stats, characterCount = characterCount)
        }
        if (state.warnings.hasWarnings || stats.error > 0) {
            item(key = "warning") {
                WarningCard(
                    warnings = state.warnings,
                    errorCount = stats.error,
                )
            }
        }
        if (state.characters.isNotEmpty()) {
            item(key = "characters") {
                CharacterStrip(
                    characters = state.characters,
                    hiddenCount = state.hiddenCharacterCount,
                    onOpenCharacters = onOpenCharacters,
                )
            }
        }
        item(key = "chapters") {
            ChapterEntry(
                stats = stats,
                onOpenChapters = onOpenChapters,
            )
        }
        item(key = "bottom-space") {
            Spacer(Modifier.height(MaterialTheme.spacing.sm))
        }
    }
}

@Composable
private fun ProjectHero(
    project: ProjectHeaderUi,
    stats: ChapterStatsUi,
    characterCount: Int,
) {
    ForgeCard(contentPadding = PaddingValues(MaterialTheme.spacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            ProjectCover(title = project.title)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
            ) {
                ProjectStatusPill(stats = stats)
                Text(
                    text = project.title,
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                ) {
                    ForgeIcon(
                        icon = ForgeIcons.FileText,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.ux2_project_hero_source,
                            project.sourceName,
                            project.charCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                ) {
                    HeroTag(
                        icon = ForgeIcons.BookOpen,
                        label = stringResource(R.string.ux2_project_tag_chapters, stats.total),
                    )
                    HeroTag(
                        icon = ForgeIcons.Users,
                        label = stringResource(R.string.ux2_project_tag_characters, characterCount),
                    )
                    HeroTag(
                        icon = ForgeIcons.Sparkles,
                        label = stringResource(R.string.ux2_project_tag_multi_voice),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectCover(title: String) {
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
    )
    Box(
        modifier = Modifier
            .size(width = 84.dp, height = 112.dp)
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) {
        ForgeIcon(
            icon = ForgeIcons.BookOpen,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
        Text(
            text = title.take(1),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(MaterialTheme.spacing.sm),
            style = MaterialTheme.typography.displaySmallEmphasized,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.28f),
        )
    }
}

@Composable
private fun HeroTag(
    @DrawableRes icon: Int,
    label: String,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.sm, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
        ) {
            ForgeIcon(
                icon = icon,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ProjectStatusPill(stats: ChapterStatsUi) {
    val visual = when {
        stats.running > 0 -> ProjectStatusVisual(
            icon = ForgeIcons.Loader,
            labelRes = R.string.ux2_project_status_running,
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
        )

        stats.error > 0 -> ProjectStatusVisual(
            icon = ForgeIcons.AlertCircle,
            labelRes = R.string.ux2_project_status_error,
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
        )

        stats.total > 0 && stats.done >= stats.total -> ProjectStatusVisual(
            icon = ForgeIcons.CheckCircle,
            labelRes = R.string.ux2_project_status_done,
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
        )

        else -> ProjectStatusVisual(
            icon = ForgeIcons.Clock,
            labelRes = R.string.ux2_project_status_pending,
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Surface(
        shape = CircleShape,
        color = visual.container,
        contentColor = visual.content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.sm, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
        ) {
            ForgeIcon(
                icon = visual.icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = visual.content,
            )
            Text(
                text = stringResource(visual.labelRes),
                style = MaterialTheme.typography.labelMediumEmphasized,
            )
        }
    }
}

private data class ProjectStatusVisual(
    @DrawableRes val icon: Int,
    @StringRes val labelRes: Int,
    val container: Color,
    val content: Color,
)

@Composable
private fun ProgressCard(
    stats: ChapterStatsUi,
    characterCount: Int,
) {
    ForgeCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.ux2_project_progress_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
            Text(
                text = stringResource(R.string.ux2_project_progress_percent, stats.percent),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        ForgeWavyProgress(progress = stats.progress)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        ) {
            StatCell(
                value = stringResource(
                    R.string.ux2_project_stat_ratio,
                    stats.done,
                    stats.total,
                ),
                label = stringResource(R.string.ux2_project_stat_chapters),
                modifier = Modifier.weight(1f),
            )
            StatCell(
                value = stringResource(
                    R.string.ux2_project_stat_ratio,
                    stats.segmentsDone,
                    stats.segmentsTotal,
                ),
                label = stringResource(R.string.ux2_project_stat_segments),
                modifier = Modifier.weight(1f),
            )
            StatCell(
                value = stringResource(R.string.ux2_project_stat_value, characterCount),
                label = stringResource(R.string.ux2_project_stat_characters),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = value, style = MaterialTheme.typography.titleMediumEmphasized)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WarningCard(
    warnings: WarningSummaryUi,
    errorCount: Int,
) {
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
                    text = stringResource(R.string.project_warning_title),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                if (warnings.hasWarnings) {
                    Text(
                        text = stringResource(
                            R.string.project_warning_summary,
                            warnings.warningCount,
                            warnings.summary,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                if (errorCount > 0) {
                    Text(
                        text = stringResource(R.string.project_error_summary, errorCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun CharacterStrip(
    characters: List<CharacterUi>,
    hiddenCount: Int,
    onOpenCharacters: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        ForgeSectionHeader(
            title = stringResource(R.string.project_characters_title),
            subtitle = stringResource(
                R.string.project_characters_subtitle,
                characters.size + hiddenCount,
            ),
            actionIcon = ForgeIcons.ChevronRight,
            actionDescription = stringResource(R.string.ux2_project_characters_manage),
            onAction = onOpenCharacters,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            contentPadding = PaddingValues(vertical = MaterialTheme.spacing.xs),
        ) {
            items(items = characters, key = { it.roleId }) { character ->
                CharacterCard(name = character.name, onClick = onOpenCharacters)
            }
            if (hiddenCount > 0) {
                item(key = "hidden-characters") {
                    CharacterMoreCard(count = hiddenCount, onClick = onOpenCharacters)
                }
            }
        }
    }
}

@Composable
private fun CharacterCard(
    name: String,
    onClick: () -> Unit,
) {
    ForgeCard(
        modifier = Modifier.width(92.dp),
        onClick = onClick,
        contentPadding = PaddingValues(MaterialTheme.spacing.md),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .size(44.dp)
                .align(Alignment.CenterHorizontally),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ForgeIcon(
                    icon = ForgeIcons.UserRound,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Text(
            text = name,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelMediumEmphasized,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CharacterMoreCard(
    count: Int,
    onClick: () -> Unit,
) {
    ForgeCard(
        modifier = Modifier.width(92.dp),
        onClick = onClick,
        contentPadding = PaddingValues(MaterialTheme.spacing.md),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .size(44.dp)
                .align(Alignment.CenterHorizontally),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ForgeIcon(
                    icon = ForgeIcons.More,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = stringResource(R.string.project_characters_more, count),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChapterEntry(
    stats: ChapterStatsUi,
    onOpenChapters: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        ForgeSectionHeader(
            title = stringResource(R.string.project_more_chapters),
            subtitle = stringResource(
                R.string.ux2_project_chapters_subtitle,
                stats.total,
                stats.pending,
            ),
            actionIcon = ForgeIcons.ChevronRight,
            actionDescription = stringResource(R.string.ux2_project_chapters_manage),
            onAction = onOpenChapters,
        )
        ForgeListRow(
            icon = ForgeIcons.List,
            title = stringResource(R.string.ux2_project_chapter_row_title),
            subtitle = stringResource(
                R.string.ux2_project_chapter_row_summary,
                stats.done,
                stats.running,
                stats.pending,
            ),
            onClick = onOpenChapters,
            trailingIcon = ForgeIcons.ChevronRight,
            trailingDescription = stringResource(R.string.ux2_project_chapters_manage),
            onTrailingClick = onOpenChapters,
        )
    }
}

@Composable
private fun ContinueDock(
    stats: ChapterStatsUi,
    batchRunnerAvailable: Boolean,
    onStartBatch: () -> Unit,
    onStopBatch: () -> Unit,
) {
    val hint = when {
        !batchRunnerAvailable -> stringResource(R.string.project_control_unavailable)
        stats.running > 0 -> stringResource(R.string.ux2_project_continue_running)
        stats.pending <= 0 && stats.total > 0 -> {
            stringResource(R.string.ux2_project_continue_hint_done)
        }
        else -> stringResource(R.string.ux2_project_continue_hint, stats.pending)
    }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.lg,
                    vertical = MaterialTheme.spacing.sm,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
        ) {
            ForgePrimaryAction(
                icon = ForgeIcons.Wand,
                label = stringResource(R.string.ux2_project_continue),
                onClick = onStartBatch,
                modifier = Modifier.fillMaxWidth(),
                enabled = batchRunnerAvailable && stats.running == 0,
            )
            if (stats.running > 0) {
                ForgeSecondaryAction(
                    icon = ForgeIcons.Square,
                    label = stringResource(R.string.ux2_project_stop),
                    onClick = onStopBatch,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = batchRunnerAvailable,
                    danger = true,
                )
            }
            Text(
                text = hint,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RenameProjectDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val trimmed = value.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            ForgeIcon(
                icon = ForgeIcons.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = stringResource(R.string.project_rename_title),
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text(stringResource(R.string.project_rename_label)) },
                    singleLine = true,
                    isError = trimmed.isEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    trimmed.isEmpty() -> Text(
                        text = stringResource(R.string.project_rename_empty_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    trimmed != title -> Text(
                        text = stringResource(R.string.project_rename_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            ForgePrimaryAction(
                icon = ForgeIcons.Check,
                label = stringResource(R.string.action_save),
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty(),
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
// Preview：正常 / 空项目 / 错误态。示例数据只在 Preview 区使用。
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
    chapterCount = 8,
    chapterDoneCount = 5,
    segmentCount = 150,
    segmentDoneCount = 96,
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
    CharacterEntity(id = 2, novelId = 1, roleId = "role_0002", name = "沈砚"),
    CharacterEntity(id = 3, novelId = 1, roleId = "role_0003", name = "苏晚"),
    CharacterEntity(id = 4, novelId = 1, roleId = "role_0004", name = "老船夫"),
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
