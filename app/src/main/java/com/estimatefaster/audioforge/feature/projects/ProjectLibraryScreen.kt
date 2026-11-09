@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.estimatefaster.audioforge.feature.projects

import android.content.res.Configuration
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.data.db.ProjectSummary
import com.estimatefaster.audioforge.ui.components.ExpressiveCard
import com.estimatefaster.audioforge.ui.components.ExpressiveFab
import com.estimatefaster.audioforge.ui.components.ProgressRing
import com.estimatefaster.audioforge.ui.components.ScreenState
import com.estimatefaster.audioforge.ui.components.StatusKind
import com.estimatefaster.audioforge.ui.components.StatusPill
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing

/** 未选中任何项目的哨兵值；项目 id 由 Room 自增，从 1 开始。 */
private const val NO_PROJECT_ID = -1L

/**
 * 项目库的聚合统计，只用于 hero 区展示。
 *
 * 章节数按全部项目求和，而不是搜索过滤后的结果：搜索只影响列表，
 * 不应让顶部统计跟着跳动。
 */
private data class LibraryStats(
    val projectCount: Int,
    val chapterTotal: Long,
    val chapterDone: Long,
)

/** 本地绘制的「新增/导入」加号图标；项目不引入 material-icons-extended。 */
private val ImportAddIcon: ImageVector = ImageVector.Builder(
    name = "ux_projects_import_add",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(11f, 5f)
        lineTo(13f, 5f)
        lineTo(13f, 11f)
        lineTo(19f, 11f)
        lineTo(19f, 13f)
        lineTo(13f, 13f)
        lineTo(13f, 19f)
        lineTo(11f, 19f)
        lineTo(11f, 13f)
        lineTo(5f, 13f)
        lineTo(5f, 11f)
        lineTo(11f, 11f)
        close()
    }
}.build()

/**
 * 项目库有状态入口（P4-03 / 执行计划 §12.4 S02）。
 *
 * 只负责从 [ProjectLibraryViewModel] 收集状态并把用户操作转成回调；
 * 实际布局在无状态重载里，方便 Preview/测试。
 */
@Composable
fun ProjectLibraryScreen(
    viewModel: ProjectLibraryViewModel,
    modifier: Modifier = Modifier,
    onOpenProject: (Long) -> Unit = {},
    onImport: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProjectLibraryScreen(
        state = state,
        modifier = modifier,
        onOpenProject = onOpenProject,
        onImport = onImport,
        onQueryChange = viewModel::onQueryChange,
        onRetry = viewModel::retry,
        onRename = viewModel::rename,
        onDelete = viewModel::delete,
        onActionErrorShown = viewModel::consumeActionError,
    )
}

/**
 * 无状态项目库。
 *
 * 结构：primaryContainer hero（标题 + 统计）→ 搜索框 → 列表/统一状态；FAB 固定「导入」。
 * 空库、搜索无匹配、加载、错误都交给 [ScreenState]，视觉与其它屏幕一致。
 *
 * 性能：列表只渲染 [ProjectCardUi] 的聚合字段，`items` 使用稳定 key
 * （[ProjectCardUi.stableKey]）与固定 contentType，2000 个项目也只组合可见项。
 */
@Composable
fun ProjectLibraryScreen(
    state: ProjectLibraryUiState,
    modifier: Modifier = Modifier,
    onOpenProject: (Long) -> Unit = {},
    onImport: () -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onRetry: () -> Unit = {},
    onRename: (Long, String) -> Unit = { _, _ -> },
    onDelete: (Long) -> Unit = {},
    onActionErrorShown: () -> Unit = {},
) {
    var menuProjectId by rememberSaveable { mutableStateOf(NO_PROJECT_ID) }
    var renameTargetId by rememberSaveable { mutableStateOf(NO_PROJECT_ID) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var deleteTargetId by rememberSaveable { mutableStateOf(NO_PROJECT_ID) }

    val spacing = MaterialTheme.spacing

    val snackbarHostState = remember { SnackbarHostState() }
    val actionErrorMessage = state.actionError?.let { message ->
        stringResource(R.string.action_failed, message)
    }
    LaunchedEffect(actionErrorMessage) {
        if (actionErrorMessage != null) {
            snackbarHostState.showSnackbar(actionErrorMessage)
            onActionErrorShown()
        }
    }

    val stats = remember(state.allProjects) {
        LibraryStats(
            projectCount = state.allProjects.size,
            chapterTotal = state.allProjects.sumOf { card -> card.chapterCount.toLong() },
            chapterDone = state.allProjects.sumOf { card -> card.chapterDoneCount.toLong() },
        )
    }

    val loadError = state.loadError
    val importLabel = stringResource(R.string.project_library_import)
    val screenState = when {
        state.loading -> ScreenState.Loading(stringResource(R.string.project_loading))

        loadError != null -> ScreenState.Error(
            message = stringResource(R.string.project_load_error, loadError),
            onRetry = onRetry,
        )

        state.isEmpty -> ScreenState.Empty(
            title = stringResource(R.string.project_library_empty_title),
            description = stringResource(R.string.project_library_empty_description),
            actionText = stringResource(R.string.project_library_empty_action),
            onAction = onImport,
        )

        state.isNoSearchResult -> ScreenState.Empty(
            title = stringResource(R.string.project_library_no_result_title),
            description = stringResource(
                R.string.project_library_no_result_description,
                state.query.trim(),
            ),
            actionText = stringResource(R.string.project_library_no_result_action),
            onAction = { onQueryChange("") },
        )

        else -> ScreenState.Content
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExpressiveFab(
                onClick = onImport,
                icon = ImportAddIcon,
                contentDescription = stringResource(R.string.action_import_novel),
                // 图标 FAB 没有可见文案；把「导入」同时暴露为语义文本，
                // 保留旧版扩展 FAB 的文案定位能力，读屏仍以 contentDescription 为准。
                modifier = Modifier.semantics {
                    text = AnnotatedString(importLabel)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LibraryHero(
                stats = stats,
                modifier = Modifier.padding(
                    start = spacing.xl,
                    end = spacing.xl,
                    top = spacing.lg,
                ),
            )
            LibrarySearchField(
                query = state.query,
                onQueryChange = onQueryChange,
                modifier = Modifier.padding(
                    horizontal = spacing.xl,
                    vertical = spacing.lg,
                ),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                ScreenState(
                    state = screenState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = spacing.xl,
                            end = spacing.xl,
                            top = spacing.sm,
                            // 给 FAB 留出空间，最后一张卡不被遮挡。
                            bottom = spacing.huge * 2f + spacing.sm,
                        ),
                        verticalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        items(
                            items = state.projects,
                            key = { card -> card.stableKey },
                            contentType = { "project_card" },
                        ) { card ->
                            ProjectLibraryCard(
                                card = card,
                                menuExpanded = menuProjectId == card.id,
                                onOpen = { onOpenProject(card.id) },
                                onMenuOpen = { menuProjectId = card.id },
                                onMenuDismiss = {
                                    if (menuProjectId == card.id) {
                                        menuProjectId = NO_PROJECT_ID
                                    }
                                },
                                onRename = {
                                    menuProjectId = NO_PROJECT_ID
                                    renameText = card.title
                                    renameTargetId = card.id
                                },
                                onDelete = {
                                    menuProjectId = NO_PROJECT_ID
                                    deleteTargetId = card.id
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    val renameTarget = state.allProjects.firstOrNull { it.id == renameTargetId }
    if (renameTarget != null) {
        RenameProjectDialog(
            value = renameText,
            onValueChange = { renameText = it },
            onDismiss = { renameTargetId = NO_PROJECT_ID },
            onConfirm = { newTitle ->
                val targetId = renameTarget.id
                renameTargetId = NO_PROJECT_ID
                onRename(targetId, newTitle)
            },
        )
    }

    val deleteTarget = state.allProjects.firstOrNull { it.id == deleteTargetId }
    if (deleteTarget != null) {
        DeleteProjectDialog(
            title = deleteTarget.title,
            onDismiss = { deleteTargetId = NO_PROJECT_ID },
            onConfirm = {
                val targetId = deleteTarget.id
                deleteTargetId = NO_PROJECT_ID
                onDelete(targetId)
            },
        )
    }
}

/**
 * 项目库 hero：primaryContainer 大圆角容器，标题用 displaySmallEmphasized，
 * 统计数字用 titleLargeEmphasized；视觉权重高于列表，但不参与滚动。
 *
 * 统计用 FlowRow：正常字体一行三项，200% 字体自动换行，数字不会被截断。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryHero(
    stats: LibraryStats,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraExtraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            Text(
                text = stringResource(R.string.project_library_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.displaySmallEmphasized,
                color = contentColor,
            )
            Text(
                text = stringResource(R.string.project_library_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.xl),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                HeroStat(
                    value = stats.projectCount.toString(),
                    label = stringResource(R.string.ux_projects_hero_stat_projects),
                )
                HeroStat(
                    value = stats.chapterDone.toString(),
                    label = stringResource(R.string.ux_projects_hero_stat_chapters_done),
                )
                HeroStat(
                    value = stats.chapterTotal.toString(),
                    label = stringResource(R.string.ux_projects_hero_stat_chapters_total),
                )
            }
        }
    }
}

/** hero 里的单个统计；合并语义后 TalkBack 一次读出「数字 + 含义」。 */
@Composable
private fun HeroStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 搜索框：加大圆角与正文排版，聚焦/错误态沿用 M3 默认语义色。 */
@Composable
private fun LibrarySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        label = { Text(stringResource(R.string.project_library_search_label)) },
        placeholder = { Text(stringResource(R.string.project_library_search_placeholder)) },
        textStyle = MaterialTheme.typography.bodyLarge,
    )
}

/**
 * 项目卡：ExpressiveCard + 整卡点击（长按打开更多菜单）。
 *
 * 标题/来源/字数在头部，进度环、章节/片段统计和音频状态在底部；
 * 点击事件放在卡片内容层，ripple 会被 Surface 的大圆角裁切。
 */
@Composable
private fun ProjectLibraryCard(
    card: ProjectCardUi,
    menuExpanded: Boolean,
    onOpen: () -> Unit,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val openLabel = stringResource(R.string.project_library_open)
    val moreLabel = stringResource(R.string.action_more)

    ExpressiveCard(
        modifier = modifier,
        contentPadding = PaddingValues(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = openLabel,
                    onLongClickLabel = moreLabel,
                    role = Role.Button,
                    onLongClick = onMenuOpen,
                    onClick = onOpen,
                )
                .padding(spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing.xxs),
                ) {
                    Text(
                        text = card.title,
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.project_library_source, card.sourceName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            R.string.project_library_char_count,
                            card.charCountText,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    TextButton(
                        onClick = onMenuOpen,
                        modifier = Modifier.heightIn(min = spacing.huge),
                    ) {
                        Text(
                            text = moreLabel,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = onMenuDismiss,
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_rename)) },
                            onClick = onRename,
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.action_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = onDelete,
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                ProgressRing(
                    progress = card.progress,
                    label = stringResource(R.string.project_library_progress_label),
                    diameter = spacing.xxl * 2f,
                    strokeWidth = spacing.sm,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Text(
                        text = stringResource(
                            R.string.project_library_chapter_stats,
                            card.chapterCount,
                            card.chapterDoneCount,
                        ),
                        style = MaterialTheme.typography.titleSmallEmphasized,
                    )
                    Text(
                        text = stringResource(
                            R.string.project_library_segment_stats,
                            card.segmentDoneCount,
                            card.segmentCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StatusPill(kind = StatusKind.Audio, status = card.audioStatus)
                }
            }
        }
    }
}

@Composable
private fun RenameProjectDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val trimmed = value.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (trimmed.isEmpty()) {
                    Text(
                        text = stringResource(R.string.project_rename_empty_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
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
private fun DeleteProjectDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = stringResource(R.string.project_delete_title),
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
        },
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
// Preview：正常 / 空库 / 无搜索结果 / 深色 200%。
// ---------------------------------------------------------------------------

private fun previewSummary(
    id: Long,
    title: String,
    sourceName: String = "$title.txt",
    charCount: Int = 156_000,
    createdAt: Long = 0L,
    lastOpenedAt: Long? = null,
    chapterCount: Int = 120,
    chapterDoneCount: Int = 42,
    segmentCount: Int = 1_200,
    segmentDoneCount: Int = 420,
): ProjectSummary = ProjectSummary(
    id = id,
    title = title,
    sourceName = sourceName,
    charCount = charCount,
    createdAt = createdAt,
    lastOpenedAt = lastOpenedAt,
    chapterCount = chapterCount,
    chapterDoneCount = chapterDoneCount,
    segmentCount = segmentCount,
    segmentDoneCount = segmentDoneCount,
)

private fun previewLibraryState(): ProjectLibraryUiState = buildProjectLibraryUiState(
    summaries = listOf(
        previewSummary(
            id = 1L,
            title = "长夜将明",
            lastOpenedAt = 300L,
            chapterCount = 2_000,
            chapterDoneCount = 1_500,
            segmentCount = 12_000,
            segmentDoneCount = 9_000,
        ),
        previewSummary(
            id = 2L,
            title = "Nightfall",
            sourceName = "nightfall.txt",
            charCount = 12_345,
            lastOpenedAt = 200L,
            chapterCount = 48,
            chapterDoneCount = 48,
            segmentCount = 300,
            segmentDoneCount = 300,
        ),
        previewSummary(
            id = 3L,
            title = "未完待续",
            lastOpenedAt = null,
            createdAt = 100L,
            chapterCount = 0,
            chapterDoneCount = 0,
            segmentCount = 0,
            segmentDoneCount = 0,
        ),
    ),
)

@Preview(name = "ProjectLibrary Normal", showBackground = true)
@Composable
private fun ProjectLibraryNormalPreview() {
    AudioForgeTheme(dynamicColor = false) {
        ProjectLibraryScreen(state = previewLibraryState())
    }
}

@Preview(name = "ProjectLibrary Empty", showBackground = true)
@Composable
private fun ProjectLibraryEmptyPreview() {
    AudioForgeTheme(dynamicColor = false) {
        ProjectLibraryScreen(state = buildProjectLibraryUiState(emptyList()))
    }
}

@Preview(name = "ProjectLibrary NoResult", showBackground = true)
@Composable
private fun ProjectLibraryNoResultPreview() {
    AudioForgeTheme(dynamicColor = false) {
        ProjectLibraryScreen(
            state = buildProjectLibraryUiState(
                summaries = listOf(previewSummary(id = 1L, title = "长夜将明")),
                query = "不存在",
            ),
        )
    }
}

@Preview(
    name = "ProjectLibrary Dark 200%",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun ProjectLibraryDarkPreview() {
    AudioForgeTheme(darkTheme = true, dynamicColor = false) {
        ProjectLibraryScreen(state = previewLibraryState())
    }
}
