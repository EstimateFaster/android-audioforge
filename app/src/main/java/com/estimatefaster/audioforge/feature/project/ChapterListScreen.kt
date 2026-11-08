package com.estimatefaster.audioforge.feature.project

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.ui.components.EmptyState
import com.estimatefaster.audioforge.ui.components.ErrorBanner
import com.estimatefaster.audioforge.ui.components.ErrorState
import com.estimatefaster.audioforge.ui.components.StatusKind
import com.estimatefaster.audioforge.ui.components.StatusPill
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import kotlinx.coroutines.flow.distinctUntilChanged

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
 * - 顶部：返回 + 标题“章节”；
 * - 搜索框（标题）+ 状态筛选（全部/解析中/解析失败/音频完成/未完成）；
 * - `LazyColumn` 稳定 key（章节主键），接近底部由 `LaunchedEffect` 触发加载更多；
 * - 空态/首屏错误/加载态复用 P4-02 的 [EmptyState]/[ErrorState]。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
                        text = stringResource(R.string.chapter_list_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
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
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp),
                label = { Text(stringResource(R.string.chapter_list_search_label)) },
                placeholder = { Text(stringResource(R.string.chapter_list_search_placeholder)) },
                singleLine = true,
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        TextButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.chapter_list_search_clear))
                        }
                    }
                },
            )
            ChapterFilterRow(
                selected = state.filter,
                onFilterChange = onFilterChange,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when {
                    state.isInitialLoading -> ChapterListLoading(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                    )

                    loadError != null -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ErrorState(
                            message = stringResource(R.string.chapter_list_load_error, loadError),
                            onRetry = onRetry,
                        )
                    }

                    state.isEmpty -> ChapterListEmpty(
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

@Composable
private fun ChapterFilterRow(
    selected: ChapterFilter,
    onFilterChange: (ChapterFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChapterFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onFilterChange(filter) },
                label = { Text(stringResource(filter.labelRes())) },
            )
        }
    }
}

@Composable
private fun ChapterListEmpty(
    filtered: Boolean,
    onBack: () -> Unit,
    onClearFilters: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            title = stringResource(
                if (filtered) {
                    R.string.chapter_list_filtered_empty_title
                } else {
                    R.string.chapter_list_empty_title
                },
            ),
            description = stringResource(
                if (filtered) {
                    R.string.chapter_list_filtered_empty_description
                } else {
                    R.string.chapter_list_empty_description
                },
            ),
            actionText = stringResource(
                if (filtered) {
                    R.string.chapter_list_filtered_empty_action
                } else {
                    R.string.chapter_list_empty_action
                },
            ),
            onAction = if (filtered) onClearFilters else onBack,
        )
    }
}

@Composable
private fun ChapterListLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.chapter_list_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 4.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
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

@Composable
private fun ChapterRow(
    chapter: ChapterListItemUi,
    onOpenScript: (Long) -> Unit,
    onQuickParse: (Long) -> Unit,
    onRerender: (Long) -> Unit,
) {
    var menuExpanded by rememberSaveable(chapter.id) { mutableStateOf(false) }
    val cardShape = MaterialTheme.shapes.medium
    val openScriptLabel = stringResource(R.string.chapter_list_action_open_script)
    val moreLabel = stringResource(R.string.action_more)
    val moreDescription = stringResource(
        R.string.chapter_list_item_more_content_description,
        chapter.number,
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .combinedClickable(
                onClickLabel = openScriptLabel,
                onLongClickLabel = moreLabel,
                onLongClick = { menuExpanded = true },
                onClick = { onOpenScript(chapter.id) },
            ),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            ChapterNumberBadge(number = chapter.number)
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = chapter.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (chapter.hasWarning) {
                        Spacer(Modifier.width(6.dp))
                        WarningIcon()
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill(kind = StatusKind.Parse, status = chapter.parseStatus)
                    StatusPill(kind = StatusKind.Audio, status = chapter.audioStatus)
                }
                if (chapter.hasSegments) {
                    Text(
                        text = stringResource(
                            R.string.chapter_list_item_segments,
                            chapter.segmentDone,
                            chapter.segmentTotal,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { chapter.segmentProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                    )
                }
            }
            Box {
                TextButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = moreDescription },
                ) {
                    Text(moreLabel)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chapter_list_action_quick_parse)) },
                        onClick = {
                            menuExpanded = false
                            onQuickParse(chapter.id)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chapter_list_action_rerender)) },
                        onClick = {
                            menuExpanded = false
                            onRerender(chapter.id)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chapter_list_action_open_script)) },
                        onClick = {
                            menuExpanded = false
                            onOpenScript(chapter.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterNumberBadge(number: Int) {
    Box(
        modifier = Modifier
            .sizeIn(minWidth = 36.dp, minHeight = 36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 解析告警图标：琥珀三角 + 感叹号，不依赖 material-icons-extended。 */
@Composable
private fun WarningIcon() {
    val description = stringResource(R.string.chapter_list_item_warning)
    val fill = MaterialTheme.colorScheme.secondary
    val mark = MaterialTheme.colorScheme.onSecondary
    Canvas(
        modifier = Modifier
            .size(18.dp)
            .semantics { contentDescription = description },
    ) {
        val path = Path().apply {
            moveTo(size.width / 2f, size.height * 0.06f)
            lineTo(size.width * 0.98f, size.height * 0.92f)
            lineTo(size.width * 0.02f, size.height * 0.92f)
            close()
        }
        drawPath(path = path, color = fill)
        val stroke = size.width * 0.075f
        drawLine(
            color = mark,
            start = Offset(size.width / 2f, size.height * 0.36f),
            end = Offset(size.width / 2f, size.height * 0.66f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = mark,
            radius = stroke * 0.7f,
            center = Offset(size.width / 2f, size.height * 0.79f),
        )
    }
}

@Composable
private fun ChapterListFooter(
    state: ChapterListUiState,
    onRetryLoadMore: () -> Unit,
) {
    val loadMoreError = state.loadMoreError
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loadMoreError != null) {
            ErrorBanner(
                message = stringResource(R.string.chapter_list_load_more_error, loadMoreError),
                onRetry = onRetryLoadMore,
            )
        } else if (state.loadingMore) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = stringResource(R.string.chapter_list_loading_more),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (state.isComplete) {
            Text(
                text = stringResource(R.string.chapter_list_all_loaded, state.loadedCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (state.hasMore) {
            Text(
                text = stringResource(R.string.chapter_list_load_more_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@StringRes
private fun ChapterFilter.labelRes(): Int = when (this) {
    ChapterFilter.All -> R.string.chapter_list_filter_all
    ChapterFilter.Parsing -> R.string.chapter_list_filter_parsing
    ChapterFilter.ParseFailed -> R.string.chapter_list_filter_parse_failed
    ChapterFilter.AudioDone -> R.string.chapter_list_filter_audio_done
    ChapterFilter.Incomplete -> R.string.chapter_list_filter_incomplete
}

// ---------------------------------------------------------------------------
// Preview：正常列表 / 筛选空结果 / 加载 / 错误。所有文案来自 strings.xml。
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
