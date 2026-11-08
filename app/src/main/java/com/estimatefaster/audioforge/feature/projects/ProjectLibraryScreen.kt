package com.estimatefaster.audioforge.feature.projects

import android.content.res.Configuration
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.data.db.ProjectSummary
import com.estimatefaster.audioforge.ui.components.EmptyState
import com.estimatefaster.audioforge.ui.components.ErrorState
import com.estimatefaster.audioforge.ui.components.ProgressRing
import com.estimatefaster.audioforge.ui.components.StatusKind
import com.estimatefaster.audioforge.ui.components.StatusPill
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme

/** 未选中任何项目的哨兵值；项目 id 由 Room 自增，从 1 开始。 */
private const val NO_PROJECT_ID = -1L

/**
 * 项目库有状态入口（P4-03 / §12.4 S02）。
 *
 * 只负责从 [ProjectLibraryViewModel] 收集状态并把用户操作转成回调；
 * 实际布局在无状态重载里，方便 Preview/测试。
 *
 * 集成说明：`AppNavHost` 的 `Route.Projects` 目前仍是占位页；接入时把
 * `ProjectsPlaceholderScreen` 换成本函数，并在 `AppViewModelFactory` 注册
 * `ProjectLibraryViewModel(container.projectRepository)` 即可。
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
 * 结构：`Large` 标题 → 搜索框 → `LazyColumn` 项目卡；FAB 固定「导入」。
 * 空库/搜索无匹配/加载/错误各自独立，删除确认与重命名用 AlertDialog。
 *
 * 性能：列表只渲染 [ProjectCardUi] 的聚合字段，`items` 使用稳定 key
 * （[ProjectCardUi.stableKey]），项目更新时不会整表重组。
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

    val loadError = state.loadError
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.project_library_title),
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.project_library_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            // Material3 1.5 的扩展 FAB 使用内容槽（RowScope），不再接收 text/icon 参数。
            ExtendedFloatingActionButton(onClick = onImport) {
                Text(stringResource(R.string.project_library_import))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                singleLine = true,
                label = { Text(stringResource(R.string.project_library_search_label)) },
                placeholder = { Text(stringResource(R.string.project_library_search_placeholder)) },
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
            ) {
                when {
                    state.loading -> LibraryLoading()

                    loadError != null -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        ErrorState(
                            message = stringResource(R.string.project_load_error, loadError),
                            onRetry = onRetry,
                        )
                    }

                    state.isEmpty -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            title = stringResource(R.string.project_library_empty_title),
                            description = stringResource(R.string.project_library_empty_description),
                            actionText = stringResource(R.string.project_library_empty_action),
                            onAction = onImport,
                        )
                    }

                    state.isNoSearchResult -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            title = stringResource(R.string.project_library_no_result_title),
                            description = stringResource(
                                R.string.project_library_no_result_description,
                                state.query.trim(),
                            ),
                            actionText = stringResource(R.string.project_library_no_result_action),
                            onAction = { onQueryChange("") },
                        )
                    }

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 20.dp,
                            end = 20.dp,
                            top = 8.dp,
                            // 给 FAB 留出空间，最后一张卡不被遮挡。
                            bottom = 104.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            items = state.projects,
                            key = { card -> card.stableKey },
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

@Composable
private fun LibraryLoading(modifier: Modifier = Modifier) {
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

/**
 * 项目卡：标题/来源/字符数 + 聚合进度 + 音频状态。
 *
 * 整卡可点（打开项目），长按或在「更多」按钮打开重命名/删除菜单；
 * 子按钮会消费点击，不会误触打开项目。
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
    val openLabel = stringResource(R.string.project_library_open)
    val moreLabel = stringResource(R.string.action_more)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClickLabel = openLabel,
                onLongClickLabel = moreLabel,
                role = Role.Button,
                onLongClick = onMenuOpen,
                onClick = onOpen,
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = card.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
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
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(moreLabel)
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

            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressRing(
                    progress = card.progress,
                    label = stringResource(R.string.project_library_progress_label),
                    diameter = 72.dp,
                    strokeWidth = 8.dp,
                )
                Spacer(Modifier.width(16.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.project_library_chapter_stats,
                            card.chapterCount,
                            card.chapterDoneCount,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
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
// Preview：正常（含 2000 章聚合卡）/ 空库 / 无搜索结果 / 深色 200%。
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

@Preview(
    name = "ProjectLibrary Normal",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun ProjectLibraryNormalPreview() {
    AudioForgeTheme(dynamicColor = false) {
        ProjectLibraryScreen(state = previewLibraryState())
    }
}

@Preview(
    name = "ProjectLibrary Empty",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun ProjectLibraryEmptyPreview() {
    AudioForgeTheme(dynamicColor = false) {
        ProjectLibraryScreen(state = buildProjectLibraryUiState(emptyList()))
    }
}

@Preview(
    name = "ProjectLibrary NoResult",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
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
