@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.estimatefaster.audioforge.feature.project

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.ui.forge.ForgeCard
import com.estimatefaster.audioforge.ui.forge.ForgeChip
import com.estimatefaster.audioforge.ui.forge.ForgeEmpty
import com.estimatefaster.audioforge.ui.forge.ForgeError
import com.estimatefaster.audioforge.ui.forge.ForgeIcon
import com.estimatefaster.audioforge.ui.forge.ForgeIconAction
import com.estimatefaster.audioforge.ui.forge.ForgeIcons
import com.estimatefaster.audioforge.ui.forge.ForgeLoading
import com.estimatefaster.audioforge.ui.forge.ForgeSecondaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSectionHeader
import com.estimatefaster.audioforge.ui.forge.ForgeTopBar
import com.estimatefaster.audioforge.ui.forge.ForgeWavyProgress
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/**
 * 章节列表（P4-07 / 执行计划 §12.4 S04 的章节区）。
 *
 * 有状态入口：从 [ChapterListViewModel] 收集状态后委托给无状态版本。
 * 打开剧本/快速解析/重渲都只是回调，页面不直接访问仓库或任务模块。
 */
@Composable
fun ChapterListScreen(
    viewModel: ChapterListViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenScript: (Long) -> Unit = {},
    onQuickParse: (Long) -> Unit = {},
    onRerender: (Long) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ChapterListScreen(
        state = state,
        modifier = modifier,
        onBack = onBack,
        onQueryChange = viewModel::setQuery,
        onFilterChange = viewModel::setFilter,
        onClearFilters = viewModel::clearFilters,
        onRetry = viewModel::retry,
        onLoadMore = viewModel::loadMore,
        onRetryLoadMore = viewModel::retryLoadMore,
        onOpenScript = onOpenScript,
        onQuickParse = onQuickParse,
        onRerender = onRerender,
    )
}

/**
 * 无状态章节列表。
 *
 * 结构对齐 Forge Framework 的 Chapters 部分：
 * ForgeTopBar（刷新）→ 搜索框 → 状态筛选 chips → 分区标题 → 章节行（数字、状态、
 * 分段进度、打开剧本 / 更多两个图标操作）→ 分页 footer。
 *
 * 行内标题文字不可点击；进入剧本走行内明确的图标入口或「更多 → 打开剧本」。
 * 分页仍是接近底部自动加载，失败后改为手动重试，行为与旧实现一致。
 */
@Composable
fun ChapterListScreen(
    state: ChapterListUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onFilterChange: (ChapterFilter) -> Unit = {},
    onClearFilters: () -> Unit = {},
    onRetry: () -> Unit = {},
    onLoadMore: () -> Unit = {},
    onRetryLoadMore: () -> Unit = {},
    onOpenScript: (Long) -> Unit = {},
    onQuickParse: (Long) -> Unit = {},
    onRerender: (Long) -> Unit = {},
) {
    val loadError = state.loadError

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ForgeTopBar(
                title = stringResource(R.string.ux2_chapter_title),
                subtitle = stringResource(R.string.ux2_chapter_section_loaded, state.loadedCount),
                navigationIcon = ForgeIcons.Back,
                navigationDescription = stringResource(R.string.action_back),
                onNavigation = onBack,
                actions = {
                    ForgeIconAction(
                        icon = ForgeIcons.Refresh,
                        contentDescription = stringResource(R.string.action_refresh),
                        onClick = onRetry,
                        container = false,
                    )
                },
            )
        },
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
                    .padding(
                        start = MaterialTheme.spacing.lg,
                        end = MaterialTheme.spacing.lg,
                        top = MaterialTheme.spacing.sm,
                    ),
                label = { Text(stringResource(R.string.chapter_list_search_label)) },
                placeholder = { Text(stringResource(R.string.chapter_list_search_placeholder)) },
                leadingIcon = {
                    ForgeIcon(
                        icon = ForgeIcons.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        ForgeIconAction(
                            icon = ForgeIcons.Close,
                            contentDescription = stringResource(R.string.chapter_list_search_clear),
                            onClick = { onQueryChange("") },
                            container = false,
                        )
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
            )
            ChapterFilterRow(
                state = state,
                onFilterChange = onFilterChange,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when {
                    state.isInitialLoading -> ForgeLoading(
                        label = stringResource(R.string.chapter_list_loading),
                    )

                    loadError != null -> ForgeError(
                        message = stringResource(R.string.chapter_list_load_error, loadError),
                        onRetry = onRetry,
                        retryLabel = stringResource(R.string.action_retry),
                    )

                    state.isEmpty -> ChapterEmptyState(
                        filtered = state.hasActiveFilters,
                        onBack = onBack,
                        onClearFilters = onClearFilters,
                    )

                    else -> ChapterList(
                        state = state,
                        onLoadMore = onLoadMore,
                        onRetryLoadMore = onRetryLoadMore,
                        onOpenScript = onOpenScript,
                        onQuickParse = onQuickParse,
                        onRerender = onRerender,
                    )
                }
            }
        }
    }
}

private val chapterFilterOrder: List<ChapterFilter> = listOf(
    ChapterFilter.All,
    ChapterFilter.AudioDone,
    ChapterFilter.Parsing,
    ChapterFilter.Incomplete,
    ChapterFilter.ParseFailed,
)

@Composable
private fun ChapterFilterRow(
    state: ChapterListUiState,
    onFilterChange: (ChapterFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(
                horizontal = MaterialTheme.spacing.lg,
                vertical = MaterialTheme.spacing.sm,
            ),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chapterFilterOrder.forEach { filter ->
            val count = state.chapters.count { chapter -> chapter.matchesFilter(filter) }
            ForgeChip(
                label = stringResource(
                    R.string.ux2_chapter_filter_count,
                    stringResource(filter.labelRes()),
                    count,
                ),
                selected = state.filter == filter,
                onClick = { onFilterChange(filter) },
                icon = filter.iconRes(),
            )
        }
    }
}

@Composable
private fun ChapterEmptyState(
    filtered: Boolean,
    onBack: () -> Unit,
    onClearFilters: () -> Unit,
) {
    if (filtered) {
        ForgeEmpty(
            icon = ForgeIcons.Search,
            title = stringResource(R.string.chapter_list_filtered_empty_title),
            description = stringResource(R.string.chapter_list_filtered_empty_description),
            actionIcon = ForgeIcons.Refresh,
            actionLabel = stringResource(R.string.chapter_list_filtered_empty_action),
            onAction = onClearFilters,
        )
    } else {
        ForgeEmpty(
            icon = ForgeIcons.List,
            title = stringResource(R.string.chapter_list_empty_title),
            description = stringResource(R.string.chapter_list_empty_description),
            actionIcon = ForgeIcons.Back,
            actionLabel = stringResource(R.string.chapter_list_empty_action),
            onAction = onBack,
        )
    }
}

@Composable
private fun ChapterList(
    state: ChapterListUiState,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    onOpenScript: (Long) -> Unit,
    onQuickParse: (Long) -> Unit,
    onRerender: (Long) -> Unit,
) {
    val listState = rememberLazyListState()

    // 接近底部（最后 3 行内）自动加载下一页。key 变化时重新监听当前可视位置，
    // 这样首屏不满一屏时会继续补页；失败后等用户点“重试”，不会死循环。
    LaunchedEffect(
        listState,
        state.chapters.size,
        state.hasMore,
        state.loadingMore,
        state.loadMoreError,
    ) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                val threshold = state.chapters.size - 3
                if (
                    threshold >= 0 &&
                    lastVisibleIndex >= threshold &&
                    state.hasMore &&
                    !state.loadingMore &&
                    state.loadMoreError == null
                ) {
                    onLoadMore()
                }
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ForgeSectionHeader(
            title = stringResource(
                if (state.hasActiveFilters) {
                    R.string.ux2_chapter_section_filtered
                } else {
                    R.string.ux2_chapter_section_all
                },
            ),
            subtitle = stringResource(R.string.ux2_chapter_section_loaded, state.loadedCount),
            modifier = Modifier.padding(
                horizontal = MaterialTheme.spacing.lg,
                vertical = MaterialTheme.spacing.sm,
            ),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(
                horizontal = MaterialTheme.spacing.lg,
                vertical = MaterialTheme.spacing.xs,
            ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            items(
                items = state.chapters,
                key = { chapter -> chapter.key },
                contentType = { "chapter" },
            ) { chapter ->
                ChapterRow(
                    chapter = chapter,
                    onOpenScript = onOpenScript,
                    onQuickParse = onQuickParse,
                    onRerender = onRerender,
                )
            }
            item(key = "footer", contentType = "footer") {
                ChapterListFooter(state = state, onRetryLoadMore = onRetryLoadMore)
            }
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: ChapterListItemUi,
    onOpenScript: (Long) -> Unit,
    onQuickParse: (Long) -> Unit,
    onRerender: (Long) -> Unit,
) {
    var menuExpanded by rememberSaveable(chapter.id) { mutableStateOf(false) }
    val percentage = (chapter.segmentProgress * 100f).roundToInt()

    ForgeCard(contentPadding = PaddingValues(MaterialTheme.spacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            ChapterNumberBadge(number = chapter.number)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                ) {
                    Text(
                        text = chapter.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (chapter.hasWarning) {
                        ForgeIcon(
                            icon = ForgeIcons.AlertCircle,
                            contentDescription = stringResource(R.string.chapter_list_item_warning),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                ) {
                    ChapterStatusPill(chapter = chapter)
                    Text(
                        text = if (chapter.hasSegments) {
                            stringResource(
                                R.string.ux2_chapter_segments,
                                chapter.segmentDone,
                                chapter.segmentTotal,
                            )
                        } else {
                            stringResource(R.string.ux2_chapter_no_segments)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (chapter.hasSegments) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                    ) {
                        ForgeWavyProgress(
                            progress = chapter.segmentProgress,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(
                                R.string.ux2_chapter_progress_percent,
                                percentage,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.Top) {
                ForgeIconAction(
                    icon = ForgeIcons.FileText,
                    contentDescription = stringResource(R.string.chapter_list_action_open_script),
                    onClick = { onOpenScript(chapter.id) },
                    container = false,
                )
                Box {
                    ForgeIconAction(
                        icon = ForgeIcons.More,
                        contentDescription = stringResource(
                            R.string.chapter_list_item_more_content_description,
                            chapter.number,
                        ),
                        onClick = { menuExpanded = true },
                        container = false,
                    )
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        ChapterMenuItem(
                            icon = ForgeIcons.Sparkles,
                            label = stringResource(R.string.chapter_list_action_quick_parse),
                        ) {
                            menuExpanded = false
                            onQuickParse(chapter.id)
                        }
                        ChapterMenuItem(
                            icon = ForgeIcons.Refresh,
                            label = stringResource(R.string.chapter_list_action_rerender),
                        ) {
                            menuExpanded = false
                            onRerender(chapter.id)
                        }
                        ChapterMenuItem(
                            icon = ForgeIcons.FileText,
                            label = stringResource(R.string.chapter_list_action_open_script),
                        ) {
                            menuExpanded = false
                            onOpenScript(chapter.id)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterMenuItem(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = {
            ForgeIcon(
                icon = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun ChapterNumberBadge(number: Int) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.size(40.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = number.toString().padStart(2, '0'),
                style = MaterialTheme.typography.labelMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ChapterStatusPill(chapter: ChapterListItemUi) {
    val audioFailed = chapter.audioStatus.isWireStatus("error")
    val audioRunning = chapter.audioStatus.isWireStatus("running")

    val visual = when {
        chapter.isAudioDone -> ChapterStatusVisual(
            icon = ForgeIcons.CheckCircle,
            labelRes = R.string.ux2_status_done,
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
        )

        chapter.isParseFailed || audioFailed -> ChapterStatusVisual(
            icon = ForgeIcons.AlertCircle,
            labelRes = R.string.ux2_status_error,
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
        )

        chapter.isParseRunning || audioRunning -> ChapterStatusVisual(
            icon = ForgeIcons.Loader,
            labelRes = R.string.ux2_status_running,
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
        )

        else -> ChapterStatusVisual(
            icon = ForgeIcons.Clock,
            labelRes = R.string.ux2_status_pending,
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
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.sm, vertical = 3.dp),
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
                style = MaterialTheme.typography.labelSmallEmphasized,
            )
        }
    }
}

private data class ChapterStatusVisual(
    @DrawableRes val icon: Int,
    @StringRes val labelRes: Int,
    val container: Color,
    val content: Color,
)

@Composable
private fun ChapterListFooter(
    state: ChapterListUiState,
    onRetryLoadMore: () -> Unit,
) {
    val loadMoreError = state.loadMoreError
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loadMoreError != null -> ForgeCard(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                ) {
                    ForgeIcon(
                        icon = ForgeIcons.AlertCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = stringResource(R.string.chapter_list_load_more_error, loadMoreError),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                ForgeSecondaryAction(
                    icon = ForgeIcons.Refresh,
                    label = stringResource(R.string.action_retry),
                    onClick = onRetryLoadMore,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            state.loadingMore -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            ) {
                LoadingIndicator(modifier = Modifier.size(20.dp))
                Text(
                    text = stringResource(R.string.chapter_list_loading_more),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.isComplete -> Text(
                text = stringResource(R.string.chapter_list_all_loaded, state.loadedCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.hasMore -> Text(
                text = stringResource(R.string.chapter_list_load_more_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@StringRes
private fun ChapterFilter.labelRes(): Int = when (this) {
    ChapterFilter.All -> R.string.chapter_list_filter_all
    ChapterFilter.Parsing -> R.string.chapter_list_filter_parsing
    ChapterFilter.ParseFailed -> R.string.ux2_chapter_filter_failed
    ChapterFilter.AudioDone -> R.string.ux2_chapter_filter_done
    ChapterFilter.Incomplete -> R.string.chapter_list_filter_incomplete
}

@DrawableRes
private fun ChapterFilter.iconRes(): Int = when (this) {
    ChapterFilter.All -> ForgeIcons.List
    ChapterFilter.Parsing -> ForgeIcons.Loader
    ChapterFilter.ParseFailed -> ForgeIcons.AlertCircle
    ChapterFilter.AudioDone -> ForgeIcons.CheckCircle
    ChapterFilter.Incomplete -> ForgeIcons.Clock
}

private fun String.isWireStatus(expected: String): Boolean =
    trim().equals(expected, ignoreCase = true)

// ---------------------------------------------------------------------------
// Preview：正常列表 / 筛选空结果 / 加载 / 错误。示例数据只在 Preview 区使用。
// ---------------------------------------------------------------------------

private fun previewChapter(
    id: Long,
    number: Int,
    title: String,
    parse: String = "done",
    audio: String = "pending",
    warning: Boolean = false,
    segmentDone: Int = 0,
    segmentTotal: Int = 0,
): ChapterListItemUi = ChapterListItemUi(
    id = id,
    number = number,
    title = title,
    parseStatus = parse,
    audioStatus = audio,
    hasWarning = warning,
    segmentDone = segmentDone,
    segmentTotal = segmentTotal,
)

private fun previewChapters(): List<ChapterListItemUi> = listOf(
    previewChapter(1, 1, "第一章 山雨欲来", audio = "done", segmentDone = 12, segmentTotal = 12),
    previewChapter(2, 2, "第二章 夜访", audio = "running", segmentDone = 5, segmentTotal = 9),
    previewChapter(3, 3, "第三章 旧信", parse = "error", warning = true),
    previewChapter(4, 4, "第四章 渡口", audio = "error", segmentDone = 2, segmentTotal = 7),
    previewChapter(5, 5, "第五章 入城"),
    previewChapter(6, 6, "第六章 灯下", parse = "running"),
    previewChapter(7, 7, "第七章 长夜", audio = "done", segmentDone = 4, segmentTotal = 4),
)

@Preview(name = "ChapterList Normal", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun ChapterListNormalPreview() {
    AudioForgeTheme(dynamicColor = false) {
        ChapterListScreen(
            state = ChapterListUiState(
                loading = false,
                chapters = previewChapters(),
                hasMore = true,
            ),
        )
    }
}

@Preview(
    name = "ChapterList Filtered Empty Dark 200%",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun ChapterListFilteredEmptyPreview() {
    AudioForgeTheme(darkTheme = true, dynamicColor = false) {
        ChapterListScreen(
            state = ChapterListUiState(
                loading = false,
                query = "不存在的标题",
                filter = ChapterFilter.ParseFailed,
            ),
        )
    }
}

@Preview(name = "ChapterList Loading", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun ChapterListLoadingPreview() {
    AudioForgeTheme(dynamicColor = false) {
        ChapterListScreen(state = ChapterListUiState(loading = true))
    }
}

@Preview(name = "ChapterList Error", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun ChapterListErrorPreview() {
    AudioForgeTheme(dynamicColor = false) {
        ChapterListScreen(
            state = ChapterListUiState(
                loading = false,
                loadError = "database is locked",
            ),
        )
    }
}
