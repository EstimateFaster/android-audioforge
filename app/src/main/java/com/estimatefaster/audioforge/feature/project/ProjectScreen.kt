package com.estimatefaster.audioforge.feature.project

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import com.estimatefaster.audioforge.ui.components.ProgressRing
import com.estimatefaster.audioforge.ui.components.RoleAvatar
import com.estimatefaster.audioforge.ui.components.SectionHeader
import com.estimatefaster.audioforge.ui.components.StatusKind
import com.estimatefaster.audioforge.ui.components.StatusPill
import com.estimatefaster.audioforge.ui.components.StatusTone
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme

/**
 * 项目详情页（P4-05 / 执行计划 §12.4 S04）。
 *
 * 有状态入口：从 [ProjectViewModel.state] 收集状态后委托给无状态版本，
 * 方便 Preview 与后续导航层复用。
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
 * - 顶部：书名、来源、字符数、更多（重命名/删除）；
 * - 摘要/进度/控制/告警/人物条；
 * - 加载/错误/空态复用 P4-02 组件。
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
                    Column {
                        Text(
                            text = project?.title
                                ?: stringResource(R.string.project_screen_title),
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (project != null) {
                            Text(
                                text = stringResource(
                                    R.string.project_header_meta,
                                    project.sourceName,
                                    project.charCount,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    if (project != null) {
                        Box {
                            TextButton(
                                onClick = { menuExpanded = true },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.action_more))
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.project_more_chapters)) },
                                    onClick = {
                                        menuExpanded = false
                                        onOpenChapters()
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(R.string.project_more_characters))
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onOpenCharacters()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.project_more_player)) },
                                    onClick = {
                                        menuExpanded = false
                                        onOpenPlayer()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.project_more_export)) },
                                    onClick = {
                                        menuExpanded = false
                                        onOpenExport()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.project_more_storage)) },
                                    onClick = {
                                        menuExpanded = false
                                        onOpenStorage()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.project_more_about)) },
                                    onClick = {
                                        menuExpanded = false
                                        onOpenAbout()
                                    },
                                )
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
            state.loading -> LoadingContent(modifier = Modifier.padding(padding))

            loadError != null -> CenteredError(
                message = stringResource(R.string.project_load_error, loadError),
                onRetry = onRetry,
                modifier = Modifier.padding(padding),
            )

            state.isMissing -> CenteredError(
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
                contentPadding = padding,
                batchRunnerAvailable = batchRunnerAvailable,
                onStartBatch = onStartBatch,
                onStopBatch = onStopBatch,
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
private fun LoadingContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.project_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    contentPadding: PaddingValues,
    batchRunnerAvailable: Boolean,
    onStartBatch: () -> Unit,
    onStopBatch: () -> Unit,
) {
    val stats = state.chapterStats
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "summary") {
            SummaryRow(stats = stats)
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
                WarningBanner(warnings = state.warnings, errorCount = stats.error)
            }
        }
        if (state.characters.isNotEmpty()) {
            item(key = "characters") {
                CharacterStrip(
                    characters = state.characters,
                    hiddenCount = state.hiddenCharacterCount,
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(stats: ChapterStatsUi) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
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
}

@Composable
private fun ProgressCard(stats: ChapterStatsUi) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionHeader(title = stringResource(R.string.project_progress_title))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressRing(
                    progress = stats.progress,
                    label = stringResource(R.string.project_progress_ring_label),
                    diameter = 104.dp,
                    strokeWidth = 9.dp,
                )
                Spacer(Modifier.width(18.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.project_progress_chapters,
                            stats.done,
                            stats.total,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
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
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionHeader(title = stringResource(R.string.project_control_title))
            Text(
                text = stringResource(R.string.project_control_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = onStartBatch,
                    enabled = batchRunnerAvailable && !running,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.project_action_start))
                }
                OutlinedButton(
                    onClick = onStopBatch,
                    enabled = batchRunnerAvailable && running,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.project_action_stop))
                }
            }
            if (!batchRunnerAvailable) {
                Text(
                    text = stringResource(R.string.project_control_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (running) {
                Text(
                    text = stringResource(R.string.project_control_running),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WarningBanner(
    warnings: WarningSummaryUi,
    errorCount: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.project_warning_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (warnings.hasWarnings) {
                Text(
                    text = stringResource(
                        R.string.project_warning_summary,
                        warnings.warningCount,
                        warnings.summary,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (errorCount > 0) {
                Text(
                    text = stringResource(R.string.project_error_summary, errorCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun CharacterStrip(
    characters: List<CharacterUi>,
    hiddenCount: Int,
) {
    Column {
        SectionHeader(
            title = stringResource(R.string.project_characters_title),
            subtitle = stringResource(
                R.string.project_characters_subtitle,
                characters.size + hiddenCount,
            ),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
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
        Spacer(Modifier.height(4.dp))
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
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(4.dp))
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
        title = { Text(stringResource(R.string.project_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text(stringResource(R.string.project_rename_label)) },
                    singleLine = true,
                    isError = trimmed.isEmpty(),
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
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
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
        title = { Text(stringResource(R.string.project_delete_title)) },
        text = { Text(stringResource(R.string.project_delete_message, title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Preview：正常 / 空项目 / 错误态。所有文案来自 strings.xml。
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
