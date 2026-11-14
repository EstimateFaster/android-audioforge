package com.estimatefaster.audioforge.uipreview.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.uipreview.R
import com.estimatefaster.audioforge.uipreview.data.Book
import com.estimatefaster.audioforge.uipreview.data.FakeData
import com.estimatefaster.audioforge.uipreview.data.PlayerState
import com.estimatefaster.audioforge.uipreview.data.formatDuration
import com.estimatefaster.audioforge.uipreview.design.AppTopBar
import com.estimatefaster.audioforge.uipreview.design.CoverArt
import com.estimatefaster.audioforge.uipreview.design.EmptyState
import com.estimatefaster.audioforge.uipreview.design.ErrorState
import com.estimatefaster.audioforge.uipreview.design.FadeThrough
import com.estimatefaster.audioforge.uipreview.design.FilterChipRow
import com.estimatefaster.audioforge.uipreview.design.IconAction
import com.estimatefaster.audioforge.uipreview.design.InlineSearchField
import com.estimatefaster.audioforge.uipreview.design.ListRow
import com.estimatefaster.audioforge.uipreview.design.LoadingState
import com.estimatefaster.audioforge.uipreview.design.ProgressLine
import com.estimatefaster.audioforge.uipreview.design.QuietCard
import com.estimatefaster.audioforge.uipreview.design.RowDivider
import com.estimatefaster.audioforge.uipreview.design.SectionLabel
import com.estimatefaster.audioforge.uipreview.design.StatusDot
import com.estimatefaster.audioforge.uipreview.design.Tone
import com.estimatefaster.audioforge.uipreview.icons.Ms
import com.estimatefaster.audioforge.uipreview.lab.LocalPreviewSettings
import com.estimatefaster.audioforge.uipreview.lab.ScreenPhase
import com.estimatefaster.audioforge.uipreview.nav.AppScaffold
import com.estimatefaster.audioforge.uipreview.nav.Dest
import com.estimatefaster.audioforge.uipreview.nav.Navigator
import com.estimatefaster.audioforge.uipreview.sheets.ImportSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    navigator: Navigator,
    player: PlayerState,
    initialSearch: Boolean = false,
) {
    val phase = LocalPreviewSettings.current.phase("library")
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    var filterId by rememberSaveable { mutableStateOf("all") }
    var searchOpen by rememberSaveable { mutableStateOf(initialSearch) }
    var sortOpen by remember { mutableStateOf(false) }
    var sortId by rememberSaveable { mutableStateOf("recent") }
    var showImport by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }

    BackHandler(enabled = searchOpen) {
        searchOpen = false
        query = ""
        filterId = "all"
    }

    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            delay(80)
            searchFocus.requestFocus()
        }
    }

    val filters = listOf(
        "all" to stringResource(R.string.library_filter_all),
        "generating" to stringResource(R.string.library_filter_generating),
        "done" to stringResource(R.string.library_filter_done),
        "pending" to stringResource(R.string.library_filter_pending),
    )

    val books = remember(query, filterId, sortId) {
        FakeData.books
            .filter { book ->
                val matchesQuery = query.isBlank() || book.title.contains(query, ignoreCase = true)
                val matchesFilter = when (filterId) {
                    "generating" -> book.isGenerating
                    "done" -> book.progress >= 1f
                    "pending" -> book.completedChapters == 0
                    else -> true
                }
                matchesQuery && matchesFilter
            }
            .let { list ->
                when (sortId) {
                    "title" -> list.sortedBy { it.title }
                    "progress" -> list.sortedByDescending { it.progress }
                    else -> list
                }
            }
    }

    val continueBook = FakeData.books.first()
    val totalDuration = FakeData.books.sumOf { it.totalDurationSec }

    AppScaffold(
        navigator = navigator,
        player = player,
        showNavBar = true,
        showMiniPlayer = true,
        snackbarHostState = snackbarHostState,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.library_title),
                subtitle = stringResource(
                    R.string.library_subtitle,
                    FakeData.books.size,
                    formatDuration(totalDuration),
                ),
                scrollBehavior = scrollBehavior,
                actions = {
                    IconAction(
                        icon = if (searchOpen) Ms.Close else Ms.Search,
                        description = stringResource(R.string.action_search),
                        onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) {
                                query = ""
                                filterId = "all"
                            }
                        },
                    )
                    IconAction(
                        icon = Ms.UploadFile,
                        description = stringResource(R.string.action_import),
                        onClick = { showImport = true },
                    )
                    Box {
                        IconAction(
                            icon = Ms.Sort,
                            description = stringResource(R.string.library_sort_recent),
                            onClick = { sortOpen = true },
                        )
                        DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                            listOf(
                                "recent" to stringResource(R.string.library_sort_recent),
                                "title" to stringResource(R.string.library_sort_title),
                                "progress" to stringResource(R.string.library_sort_progress),
                            ).forEach { (id, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = { sortId = id; sortOpen = false },
                                    trailingIcon = {
                                        if (sortId == id) {
                                            Icon(painterResource(Ms.Check), contentDescription = null)
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        FadeThrough(
            target = phase,
            modifier = Modifier.fillMaxSize(),
            label = "library-phase",
        ) { state ->
            when (state) {
                ScreenPhase.Loading -> LoadingState(
                    label = stringResource(R.string.library_loading),
                    modifier = Modifier.padding(padding),
                )

                ScreenPhase.Error -> ErrorState(
                    title = stringResource(R.string.library_error),
                    body = stringResource(R.string.library_error_body),
                    onRetry = {},
                    modifier = Modifier.padding(padding),
                )

                ScreenPhase.Empty -> EmptyState(
                    icon = Ms.LibraryBooks,
                    title = stringResource(R.string.library_empty_title),
                    body = stringResource(R.string.library_empty_body),
                    actionLabel = stringResource(R.string.library_empty_action),
                    actionIcon = Ms.UploadFile,
                    onAction = { showImport = true },
                    secondaryLabel = stringResource(R.string.library_empty_demo),
                    onSecondary = {},
                    modifier = Modifier.padding(padding),
                )

                ScreenPhase.Normal -> LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    // 搜索态：搜索框紧贴标题下方，其余内容让位
                    if (searchOpen) {
                        item(key = "search") {
                            InlineSearchField(
                                value = query,
                                onValueChange = { query = it },
                                hint = stringResource(R.string.library_search_hint),
                                focusRequester = searchFocus,
                                modifier = Modifier
                                    .padding(start = 20.dp, end = 20.dp, top = 4.dp)
                                    .animateItem(),
                            )
                        }
                        item(key = "filters") {
                            FilterChipRow(
                                options = filters,
                                selectedId = filterId,
                                onSelect = { filterId = it },
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                modifier = Modifier
                                    .padding(top = 10.dp)
                                    .animateItem(),
                            )
                        }
                        item(key = "label") {
                            SectionLabel(
                                text = stringResource(R.string.library_search_results),
                                trailing = "${books.size}",
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .animateItem(),
                            )
                        }
                    } else {
                        // 有播放会话时由迷你播放器承接，不再重复一张「继续收听」卡
                        if (!player.hasSession) {
                            item(key = "continue") {
                                ContinueCard(
                                    book = continueBook,
                                    player = player,
                                    onContinue = { navigator.push(Dest.Player) },
                                    onOpen = { navigator.push(Dest.Project(continueBook.id)) },
                                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
                                )
                            }
                        }
                        item(key = "task") {
                            RunningTaskRow(
                                onOpen = { navigator.push(Dest.Tasks) },
                                onStop = {
                                    scope.launch { snackbarHostState.showSnackbar("预览构建：已请求停止生成") }
                                },
                            )
                        }
                        item(key = "label") {
                            SectionLabel(
                                text = stringResource(R.string.library_section),
                                trailing = "${books.size}",
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    if (books.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(
                                icon = Ms.SearchOff,
                                title = stringResource(R.string.library_no_result),
                                body = stringResource(R.string.library_no_result_body),
                                actionLabel = stringResource(R.string.library_clear_filter),
                                actionIcon = Ms.FilterAltOff,
                                onAction = {
                                    query = ""
                                    filterId = "all"
                                },
                            )
                        }
                    } else {
                        items(books, key = { it.id }) { book ->
                            Column(modifier = Modifier.animateItem()) {
                                BookRow(book = book, onClick = { navigator.push(Dest.Project(book.id)) })
                                RowDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    if (showImport) {
        ImportSheet(
            onDismiss = { showImport = false },
            onDone = {
                showImport = false
                scope.launch { snackbarHostState.showSnackbar("预览构建：已导入《山海食话》") }
            },
        )
    }
}

@Composable
private fun ContinueCard(
    book: Book,
    player: PlayerState,
    onContinue: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    QuietCard(modifier = modifier, onClick = onOpen) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverArt(
                title = book.title,
                modifier = Modifier
                    .width(56.dp)
                    .height(76.dp),
                textStyle = MaterialTheme.typography.titleLargeEmphasized,
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.library_continue),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "第 ${player.chapter.number} 章 · ${player.chapter.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.height(10.dp))
                ProgressLine(progress = book.progress, height = 14.dp)
            }
            Spacer(Modifier.width(12.dp))
            FilledTonalIconButton(
                onClick = onContinue,
                modifier = Modifier.size(52.dp),
            ) {
                Icon(
                    painter = painterResource(Ms.PlayArrow),
                    contentDescription = stringResource(R.string.library_continue),
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

@Composable
private fun RunningTaskRow(
    onOpen: () -> Unit,
    onStop: () -> Unit,
) {
    val task = FakeData.runningTask
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(Tone.Info)
        Spacer(Modifier.width(10.dp))
        Text(
            text = "${stringResource(R.string.task_running)} · 第 ${task.chapterIndex}/${task.chapterCount} 章",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${task.percent}%",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            painter = painterResource(Ms.ChevronRight),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier
                .padding(start = 6.dp, end = 14.dp)
                .size(18.dp),
        )
    }
}

@Composable
private fun BookRow(book: Book, onClick: () -> Unit) {
    ListRow(
        title = book.title,
        subtitle = "${book.chapters.size} 章 · ${book.completedChapters} 章已生成",
        onClick = onClick,
        minHeight = 84.dp,
        leading = {
            CoverArt(
                title = book.title,
                modifier = Modifier
                    .width(44.dp)
                    .height(58.dp),
                textStyle = MaterialTheme.typography.titleMediumEmphasized,
            )
        },
        trailingContent = {
            Text(
                text = "${(book.progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
