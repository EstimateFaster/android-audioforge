package com.estimatefaster.audioforge.uipreview.screens

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.uipreview.R
import com.estimatefaster.audioforge.uipreview.data.Chapter
import com.estimatefaster.audioforge.uipreview.data.ChapterStatus
import com.estimatefaster.audioforge.uipreview.data.FakeData
import com.estimatefaster.audioforge.uipreview.data.PlayerState
import com.estimatefaster.audioforge.uipreview.data.formatTime
import com.estimatefaster.audioforge.uipreview.design.AppTopBar
import com.estimatefaster.audioforge.uipreview.design.EmptyState
import com.estimatefaster.audioforge.uipreview.design.ErrorState
import com.estimatefaster.audioforge.uipreview.design.FilterChipRow
import com.estimatefaster.audioforge.uipreview.design.IconAction
import com.estimatefaster.audioforge.uipreview.design.InlineSearchField
import com.estimatefaster.audioforge.uipreview.design.LoadingState
import com.estimatefaster.audioforge.uipreview.design.RowDivider
import com.estimatefaster.audioforge.uipreview.design.Tone
import com.estimatefaster.audioforge.uipreview.design.ToneColors
import com.estimatefaster.audioforge.uipreview.icons.Ms
import com.estimatefaster.audioforge.uipreview.lab.LocalPreviewSettings
import com.estimatefaster.audioforge.uipreview.lab.ScreenPhase
import com.estimatefaster.audioforge.uipreview.nav.AppScaffold
import com.estimatefaster.audioforge.uipreview.nav.Dest
import com.estimatefaster.audioforge.uipreview.nav.Navigator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChaptersScreen(navigator: Navigator, player: PlayerState, bookId: Long) {
    val book = remember(bookId) { FakeData.book(bookId) }
    val phase = LocalPreviewSettings.current.phase("chapters")
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    var filterId by rememberSaveable { mutableStateOf("all") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    val searchFocus = remember { FocusRequester() }

    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            delay(80)
            searchFocus.requestFocus()
        }
    }

    val filters = listOf(
        "all" to "全部 ${book.chapters.size}",
        "done" to "已生成 ${book.chapters.count { it.status == ChapterStatus.Done }}",
        "generating" to "生成中 ${book.chapters.count { it.status == ChapterStatus.Generating }}",
        "pending" to "待生成 ${book.chapters.count { it.status == ChapterStatus.Pending }}",
        "failed" to "失败 ${book.chapters.count { it.status == ChapterStatus.Failed }}",
    )

    val chapters = remember(query, filterId) {
        book.chapters.filter { chapter ->
            val matchesQuery = query.isBlank() || chapter.title.contains(query, ignoreCase = true)
            val matchesFilter = when (filterId) {
                "done" -> chapter.status == ChapterStatus.Done
                "generating" -> chapter.status == ChapterStatus.Generating
                "pending" -> chapter.status == ChapterStatus.Pending
                "failed" -> chapter.status == ChapterStatus.Failed
                else -> true
            }
            matchesQuery && matchesFilter
        }
    }

    val selecting = selection.isNotEmpty()

    AppScaffold(
        navigator = navigator,
        player = player,
        showNavBar = false,
        showMiniPlayer = !selecting,
        snackbarHostState = snackbarHostState,
        bottomContent = if (selecting) {
            {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    HorizontalFloatingToolbar(
                        expanded = true,
                        content = {
                            IconButton(onClick = { selection = emptySet() }) {
                                Icon(painterResource(Ms.Close), contentDescription = stringResource(R.string.action_cancel))
                            }
                            Text(
                                text = stringResource(R.string.chapters_selected, selection.size),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                            IconButton(onClick = { selection = chapters.map { it.id }.toSet() }) {
                                Icon(painterResource(Ms.DoneAll), contentDescription = stringResource(R.string.chapters_select_all))
                            }
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("预览构建：已提交 ${selection.size} 章的批量生成")
                                    }
                                    selection = emptySet()
                                },
                            ) {
                                Icon(painterResource(Ms.AutoAwesome), contentDescription = stringResource(R.string.chapters_batch))
                            }
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("预览构建：导出 ${selection.size} 章")
                                    }
                                    selection = emptySet()
                                },
                            ) {
                                Icon(painterResource(Ms.Download), contentDescription = stringResource(R.string.chapters_export))
                            }
                        },
                    )
                }
            }
        } else {
            null
        },
        topBar = {
            AppTopBar(
                title = stringResource(R.string.chapters_title),
                subtitle = stringResource(R.string.chapters_subtitle, book.chapters.size, book.completedChapters),
                scrollBehavior = scrollBehavior,
                onBack = { navigator.pop() },
                actions = {
                    IconAction(
                        icon = if (searchOpen) Ms.Close else Ms.Search,
                        description = stringResource(R.string.action_search),
                        onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) query = ""
                        },
                    )
                    IconAction(
                        icon = Ms.DoneAll,
                        description = stringResource(R.string.chapters_select_mode),
                        onClick = {
                            selection = if (selecting) emptySet() else setOf(chapters.firstOrNull()?.id ?: 0L)
                        },
                        tint = if (selecting) MaterialTheme.colorScheme.primary else null,
                    )
                },
            )
        },
    ) { padding ->
        when (phase) {
            ScreenPhase.Loading -> LoadingState(
                label = "正在加载章节…",
                modifier = Modifier.padding(padding),
            )

            ScreenPhase.Error -> ErrorState(
                title = "加载章节失败",
                body = "章节仍在本机，请重试或回到项目页刷新。",
                onRetry = {},
                modifier = Modifier.padding(padding),
            )

            ScreenPhase.Empty -> EmptyState(
                icon = Ms.FormatListNumbered,
                title = stringResource(R.string.chapters_empty_title),
                body = stringResource(R.string.chapters_empty_body),
                actionLabel = "重新解析",
                actionIcon = Ms.Refresh,
                onAction = {},
                modifier = Modifier.padding(padding),
            )

            ScreenPhase.Normal -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                if (searchOpen) {
                    InlineSearchField(
                        value = query,
                        onValueChange = { query = it },
                        hint = stringResource(R.string.chapters_search_hint),
                        focusRequester = searchFocus,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                FilterChipRow(
                    options = filters,
                    selectedId = filterId,
                    onSelect = { filterId = it },
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                if (chapters.isEmpty()) {
                    EmptyState(
                        icon = Ms.SearchOff,
                        title = stringResource(R.string.chapters_no_result),
                        body = stringResource(R.string.chapters_no_result_body),
                        actionLabel = stringResource(R.string.library_clear_filter),
                        actionIcon = Ms.FilterAltOff,
                        onAction = {
                            query = ""
                            filterId = "all"
                        },
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(chapters, key = { it.id }) { chapter ->
                            ChapterItem(
                                chapter = chapter,
                                selected = chapter.id in selection,
                                onClick = { navigator.push(Dest.Script(book.id, chapter.id)) },
                                onLongClick = { selection = selection + chapter.id },
                            )
                            RowDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterItem(
    chapter: Chapter,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val (container, content) = ToneColors(
        when (chapter.status) {
            ChapterStatus.Done -> Tone.Success
            ChapterStatus.Generating -> Tone.Info
            ChapterStatus.Failed -> Tone.Error
            ChapterStatus.Pending -> Tone.Neutral
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(Ms.CheckCircle),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            } else {
                Text(
                    text = chapter.number.toString().padStart(2, '0'),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chapter.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (chapter.segments.isEmpty()) {
                    "尚未解析"
                } else {
                    "${chapter.segments.size} 段 · ${formatTime(chapter.durationSec)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            text = when (chapter.status) {
                ChapterStatus.Done -> stringResource(R.string.status_done)
                ChapterStatus.Generating -> stringResource(R.string.status_generating)
                ChapterStatus.Failed -> stringResource(R.string.status_failed)
                ChapterStatus.Pending -> stringResource(R.string.status_pending)
            },
            style = MaterialTheme.typography.labelMedium,
            color = content,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
