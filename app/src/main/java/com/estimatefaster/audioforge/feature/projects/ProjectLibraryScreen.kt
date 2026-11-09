@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.estimatefaster.audioforge.feature.projects

import android.content.res.Configuration
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.data.db.ProjectSummary
import com.estimatefaster.audioforge.ui.forge.ForgeCard
import com.estimatefaster.audioforge.ui.forge.ForgeChip
import com.estimatefaster.audioforge.ui.forge.ForgeEmpty
import com.estimatefaster.audioforge.ui.forge.ForgeError
import com.estimatefaster.audioforge.ui.forge.ForgeExtendedFab
import com.estimatefaster.audioforge.ui.forge.ForgeIcon
import com.estimatefaster.audioforge.ui.forge.ForgeIconAction
import com.estimatefaster.audioforge.ui.forge.ForgeIcons
import com.estimatefaster.audioforge.ui.forge.ForgeLoading
import com.estimatefaster.audioforge.ui.forge.ForgePrimaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSecondaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSectionHeader
import com.estimatefaster.audioforge.ui.forge.ForgeTopBar
import com.estimatefaster.audioforge.ui.forge.ForgeWavyProgress
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing
import kotlin.math.roundToInt

/** 未选中任何项目的哨兵值；项目 id 由 Room 自增，从 1 开始。 */
private const val NO_PROJECT_ID = -1L

/** 书架网格里搜索框的 item 下标：0 = 总览卡，1 = 搜索。 */
private const val SEARCH_ITEM_INDEX = 1

/**
 * 书架（Forge Framework v1 · Projects）有状态入口。
 *
 * 只负责从 [ProjectLibraryViewModel] 收集状态并把用户操作转成回调；
 * 布局在无状态重载里，方便 Preview/测试。
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
 * 无状态书架。
 *
 * 结构严格对齐 `forge-framework.html` 的 Projects：
 * `ForgeTopBar`（搜索/更多）→ 总览卡 → 搜索 → 状态 chips → 全部项目 →
 * 2 列项目卡（200% 字号降为 1 列）→ 导入 FAB。
 * 空库 / 搜索或筛选无匹配 / 加载 / 错误分别渲染对应的 Forge 三态。
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
    var shelfMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var filterName by rememberSaveable { mutableStateOf(ProjectFilter.All.name) }

    val filter = remember(filterName) { ProjectFilter.fromName(filterName) }
    val visibleProjects = remember(state.projects, filter) {
        state.projects.filter(filter::matches)
    }
    val totals = remember(state.allProjects) { state.allProjects.toLibraryTotals() }

    val snackbarHostState = remember { SnackbarHostState() }
    val actionErrorMessage = state.actionError?.let { message ->
        stringResource(R.string.ux2_projects_action_failed, message)
    }
    LaunchedEffect(actionErrorMessage) {
        if (actionErrorMessage != null) {
            snackbarHostState.showSnackbar(actionErrorMessage)
            onActionErrorShown()
        }
    }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val gridState = rememberLazyGridState()
    var searchFocusRequested by remember { mutableStateOf(false) }
    val columns = if (LocalDensity.current.fontScale >= 1.5f) 1 else 2

    // 搜索框在 LazyVerticalGrid 里可能被滚出组合；先滚回搜索 item，再等一帧请求焦点，
    // 避免 FocusRequester 未挂载时抛出异常。
    LaunchedEffect(searchFocusRequested) {
        if (!searchFocusRequested) return@LaunchedEffect
        gridState.scrollToItem(SEARCH_ITEM_INDEX)
        withFrameNanos { }
        focusRequester.requestFocus()
        keyboardController?.show()
        searchFocusRequested = false
    }

    val loadError = state.loadError
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            val canSearch = !state.loading && loadError == null && state.allProjects.isNotEmpty()
            ForgeTopBar(
                title = stringResource(R.string.ux2_projects_title),
                subtitle = stringResource(R.string.ux2_projects_subtitle),
                actions = {
                    if (canSearch) {
                        ForgeIconAction(
                            icon = ForgeIcons.Search,
                            contentDescription = stringResource(R.string.ux2_projects_search_focus),
                            onClick = {
                                searchFocusRequested = true
                            },
                            container = false,
                        )
                    }
                    Box {
                        ForgeIconAction(
                            icon = ForgeIcons.More,
                            contentDescription = stringResource(R.string.ux2_projects_more),
                            onClick = { shelfMenuExpanded = true },
                            container = false,
                        )
                        DropdownMenu(
                            expanded = shelfMenuExpanded,
                            onDismissRequest = { shelfMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ux2_projects_menu_import)) },
                                onClick = {
                                    shelfMenuExpanded = false
                                    onImport()
                                },
                                leadingIcon = { ForgeIcon(ForgeIcons.Upload, contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ux2_projects_menu_refresh)) },
                                onClick = {
                                    shelfMenuExpanded = false
                                    onRetry()
                                },
                                leadingIcon = { ForgeIcon(ForgeIcons.Refresh, contentDescription = null) },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!state.loading && loadError == null && state.allProjects.isNotEmpty()) {
                ForgeExtendedFab(
                    icon = ForgeIcons.Plus,
                    label = stringResource(R.string.ux2_projects_import),
                    onClick = onImport,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)

        when {
            state.loading -> ForgeLoading(
                label = stringResource(R.string.ux2_projects_loading),
                modifier = contentModifier,
            )

            loadError != null -> ForgeError(
                message = stringResource(R.string.ux2_projects_load_error, loadError),
                modifier = contentModifier,
                onRetry = onRetry,
                retryLabel = stringResource(R.string.ux2_projects_retry),
            )

            state.allProjects.isEmpty() -> ForgeEmpty(
                icon = ForgeIcons.Library,
                title = stringResource(R.string.ux2_projects_empty_title),
                description = stringResource(R.string.ux2_projects_empty_desc),
                modifier = contentModifier,
                actionIcon = ForgeIcons.Upload,
                actionLabel = stringResource(R.string.ux2_projects_empty_action),
                onAction = onImport,
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = contentModifier,
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 8.dp,
                    // 给扩展 FAB 留出空间，最后一行不被遮挡。
                    bottom = 104.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LibraryStats(totals = totals)
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    LibrarySearchField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        focusRequester = focusRequester,
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    LibraryFilters(
                        selected = filter,
                        onSelect = { selected ->
                            filterName = selected.name
                        },
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    ForgeSectionHeader(
                        title = stringResource(R.string.ux2_projects_all_title),
                        subtitle = stringResource(
                            R.string.ux2_projects_all_count,
                            visibleProjects.size,
                            state.allProjects.size,
                        ),
                    )
                }

                if (visibleProjects.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                            ForgeEmpty(
                                icon = ForgeIcons.Search,
                                title = stringResource(R.string.ux2_projects_no_result_title),
                                description = stringResource(R.string.ux2_projects_no_result_desc),
                                actionIcon = ForgeIcons.Refresh,
                                actionLabel = stringResource(R.string.ux2_projects_no_result_action),
                                onAction = {
                                    filterName = ProjectFilter.All.name
                                    onQueryChange("")
                                },
                            )
                        }
                    }
                } else {
                    items(
                        items = visibleProjects,
                        key = { card -> card.stableKey },
                    ) { card ->
                        ProjectLibraryCard(
                            card = card,
                            coverHeight = if (columns == 1) 160.dp else 112.dp,
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

// ---------------------------------------------------------------------------
// 总览 / 搜索 / 筛选
// ---------------------------------------------------------------------------

private data class LibraryTotals(
    val projects: Int,
    val chapters: Int,
    val doneChapters: Int,
    val segments: Int,
    val doneSegments: Int,
) {
    val progress: Float
        get() = if (chapters <= 0) 0f else doneChapters.toFloat() / chapters
}

private fun List<ProjectCardUi>.toLibraryTotals(): LibraryTotals = LibraryTotals(
    projects = size,
    chapters = sumOf { card -> card.chapterCount },
    doneChapters = sumOf { card -> card.chapterDoneCount },
    segments = sumOf { card -> card.segmentCount },
    doneSegments = sumOf { card -> card.segmentDoneCount },
)

@Composable
private fun LibraryStats(
    totals: LibraryTotals,
    modifier: Modifier = Modifier,
) {
    ForgeCard(
        modifier = modifier,
        contentPadding = PaddingValues(MaterialTheme.spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.ux2_projects_stats_title),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                Text(
                    text = stringResource(
                        R.string.ux2_projects_stats_subtitle,
                        totals.projects,
                        totals.doneChapters,
                        totals.chapters,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(
                    R.string.ux2_projects_percent,
                    (totals.progress * 100f).roundToInt(),
                ),
                style = MaterialTheme.typography.titleLargeEmphasized,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        ForgeWavyProgress(progress = totals.progress)
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
            StatItem(
                icon = ForgeIcons.Library,
                value = totals.projects.toString(),
                label = stringResource(R.string.ux2_projects_stats_projects),
                modifier = Modifier.weight(1f),
            )
            StatItem(
                icon = ForgeIcons.FileText,
                value = totals.chapters.toString(),
                label = stringResource(R.string.ux2_projects_stats_chapters),
                modifier = Modifier.weight(1f),
            )
            StatItem(
                icon = ForgeIcons.AudioLines,
                value = stringResource(
                    R.string.ux2_projects_stats_segment_value,
                    totals.doneSegments,
                    totals.segments,
                ),
                label = stringResource(R.string.ux2_projects_stats_segments),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatItem(
    icon: Int,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(36.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Column {
            Text(text = value, style = MaterialTheme.typography.titleSmallEmphasized)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LibrarySearchField(
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        singleLine = true,
        shape = MaterialTheme.shapes.large,
        placeholder = { Text(stringResource(R.string.ux2_projects_search_placeholder)) },
        leadingIcon = { ForgeIcon(ForgeIcons.Search, contentDescription = null) },
        trailingIcon = if (value.isNotEmpty()) {
            {
                ForgeIconAction(
                    icon = ForgeIcons.Close,
                    contentDescription = stringResource(R.string.ux2_projects_search_clear),
                    onClick = { onValueChange("") },
                    container = false,
                )
            }
        } else {
            null
        },
    )
}

@Composable
private fun LibraryFilters(
    selected: ProjectFilter,
    onSelect: (ProjectFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
    ) {
        ProjectFilter.entries.forEach { filter ->
            ForgeChip(
                label = stringResource(filter.labelRes),
                selected = selected == filter,
                onClick = { onSelect(filter) },
                icon = filter.iconRes,
            )
        }
    }
}

private enum class ProjectFilter {
    All,
    Running,
    Done,
    Pending,
    ;

    fun matches(card: ProjectCardUi): Boolean = when (this) {
        All -> true
        Running -> card.audioStatus == "running"
        Done -> card.audioStatus == "done"
        Pending -> card.audioStatus == "pending"
    }

    val labelRes: Int
        get() = when (this) {
            All -> R.string.ux2_projects_filter_all
            Running -> R.string.ux2_projects_filter_running
            Done -> R.string.ux2_projects_filter_done
            Pending -> R.string.ux2_projects_filter_pending
        }

    val iconRes: Int
        get() = when (this) {
            All -> ForgeIcons.Library
            Running -> ForgeIcons.Loader
            Done -> ForgeIcons.CheckCircle
            Pending -> ForgeIcons.Clock
        }

    companion object {
        fun fromName(name: String): ProjectFilter =
            entries.firstOrNull { filter -> filter.name == name } ?: All
    }
}

// ---------------------------------------------------------------------------
// 项目卡
// ---------------------------------------------------------------------------

@Composable
private fun ProjectLibraryCard(
    card: ProjectCardUi,
    coverHeight: Dp,
    menuExpanded: Boolean,
    onOpen: () -> Unit,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val moreDescription = stringResource(R.string.ux2_projects_card_more, card.title)
    val openDescription = stringResource(R.string.ux2_projects_card_open, card.title)
    val sourceName = card.sourceName.ifBlank { stringResource(R.string.ux2_projects_source_fallback) }

    ForgeCard(
        modifier = modifier,
        onClick = onOpen,
        contentPadding = PaddingValues(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(coverHeight)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                    ),
                    shape = MaterialTheme.shapes.large,
                ),
        ) {
            Icon(
                painter = painterResource(ForgeIcons.BookOpen),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(36.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                ForgeIconAction(
                    icon = ForgeIcons.More,
                    contentDescription = moreDescription,
                    onClick = onMenuOpen,
                    container = false,
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = onMenuDismiss,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ux2_projects_menu_rename)) },
                        onClick = onRename,
                        leadingIcon = { ForgeIcon(ForgeIcons.Edit, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.ux2_projects_menu_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = onDelete,
                        leadingIcon = {
                            ForgeIcon(
                                icon = ForgeIcons.Trash,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                    )
                }
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = card.title,
                style = MaterialTheme.typography.titleSmallEmphasized,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.ux2_projects_card_meta,
                    sourceName,
                    card.charCountText,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                ForgeWavyProgress(progress = card.progress, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(MaterialTheme.spacing.xs))
                Text(
                    text = stringResource(R.string.ux2_projects_percent, card.percent),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProjectStatus(status = card.audioStatus)
                Spacer(Modifier.weight(1f))
                ForgeIconAction(
                    icon = ForgeIcons.ChevronRight,
                    contentDescription = openDescription,
                    onClick = onOpen,
                    container = false,
                )
            }
        }
    }
}

@Composable
private fun ProjectStatus(
    status: String,
    modifier: Modifier = Modifier,
) {
    val icon: Int
    val label: String
    val containerColor: androidx.compose.ui.graphics.Color
    val contentColor: androidx.compose.ui.graphics.Color
    when (status) {
        "done" -> {
            icon = ForgeIcons.CheckCircle
            label = stringResource(R.string.ux2_projects_status_done)
            containerColor = MaterialTheme.colorScheme.secondaryContainer
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        }

        "running" -> {
            icon = ForgeIcons.Loader
            label = stringResource(R.string.ux2_projects_status_running)
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        }

        else -> {
            icon = ForgeIcons.Clock
            label = stringResource(R.string.ux2_projects_status_pending)
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = contentColor,
            )
            Text(text = label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------------------
// 重命名 / 删除（所有操作均为 Lucide icon + 文案，或纯图标）
// ---------------------------------------------------------------------------

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
        icon = { ForgeIcon(ForgeIcons.Edit, contentDescription = null) },
        title = {
            Text(
                text = stringResource(R.string.ux2_projects_rename_title),
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text(stringResource(R.string.ux2_projects_rename_label)) },
                    singleLine = true,
                    isError = trimmed.isEmpty(),
                    shape = MaterialTheme.shapes.large,
                    leadingIcon = { ForgeIcon(ForgeIcons.Edit, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = if (trimmed.isEmpty()) {
                        stringResource(R.string.ux2_projects_rename_empty)
                    } else {
                        stringResource(R.string.ux2_projects_rename_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (trimmed.isEmpty()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        confirmButton = {
            ForgePrimaryAction(
                icon = ForgeIcons.Check,
                label = stringResource(R.string.ux2_projects_save),
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty(),
            )
        },
        dismissButton = {
            ForgeSecondaryAction(
                icon = ForgeIcons.Close,
                label = stringResource(R.string.ux2_projects_cancel),
                onClick = onDismiss,
            )
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
        icon = {
            ForgeIcon(
                icon = ForgeIcons.Trash,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text(
                text = stringResource(R.string.ux2_projects_delete_title),
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
        },
        text = { Text(stringResource(R.string.ux2_projects_delete_message, title)) },
        confirmButton = {
            ForgeSecondaryAction(
                icon = ForgeIcons.Trash,
                label = stringResource(R.string.ux2_projects_delete),
                onClick = onConfirm,
                danger = true,
            )
        },
        dismissButton = {
            ForgeSecondaryAction(
                icon = ForgeIcons.Close,
                label = stringResource(R.string.ux2_projects_cancel),
                onClick = onDismiss,
            )
        },
    )
}

// ---------------------------------------------------------------------------
// Preview：正常（含 2000 章聚合卡）/ 空库 / 无结果 / 深色 200%。
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
    name = "Projects Normal",
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
    name = "Projects Empty",
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
    name = "Projects NoResult",
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
    name = "Projects Dark 200%",
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
