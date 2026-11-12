package com.estimatefaster.audioforge.uipreview.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.uipreview.R
import com.estimatefaster.audioforge.uipreview.data.Book
import com.estimatefaster.audioforge.uipreview.data.ChapterStatus
import com.estimatefaster.audioforge.uipreview.data.FakeData
import com.estimatefaster.audioforge.uipreview.data.PlayerState
import com.estimatefaster.audioforge.uipreview.data.formatDuration
import com.estimatefaster.audioforge.uipreview.data.formatWordCount
import com.estimatefaster.audioforge.uipreview.design.AppTopBar
import com.estimatefaster.audioforge.uipreview.design.CoverArt
import com.estimatefaster.audioforge.uipreview.design.EmptyState
import com.estimatefaster.audioforge.uipreview.design.ErrorState
import com.estimatefaster.audioforge.uipreview.design.FilterChipRow
import com.estimatefaster.audioforge.uipreview.design.GroupSurface
import com.estimatefaster.audioforge.uipreview.design.IconAction
import com.estimatefaster.audioforge.uipreview.design.IconBubble
import com.estimatefaster.audioforge.uipreview.design.InlineSearchField
import com.estimatefaster.audioforge.uipreview.design.LoadingState
import com.estimatefaster.audioforge.uipreview.design.PrimaryButton
import com.estimatefaster.audioforge.uipreview.design.ProgressBlock
import com.estimatefaster.audioforge.uipreview.design.RowDivider
import com.estimatefaster.audioforge.uipreview.design.SecondaryButton
import com.estimatefaster.audioforge.uipreview.design.SectionHeader
import com.estimatefaster.audioforge.uipreview.design.StatTile
import com.estimatefaster.audioforge.uipreview.design.StatusChip
import com.estimatefaster.audioforge.uipreview.design.Tone
import com.estimatefaster.audioforge.uipreview.icons.Ms
import com.estimatefaster.audioforge.uipreview.lab.LocalPreviewSettings
import com.estimatefaster.audioforge.uipreview.lab.ScreenPhase
import com.estimatefaster.audioforge.uipreview.nav.AppScaffold
import com.estimatefaster.audioforge.uipreview.nav.Dest
import com.estimatefaster.audioforge.uipreview.nav.Navigator
import com.estimatefaster.audioforge.uipreview.sheets.ImportSheet
import com.estimatefaster.audioforge.uipreview.sheets.TasksSheet
import com.estimatefaster.audioforge.uipreview.theme.CoverShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class LibraryFilter(val id: String, val label: String)

@Composable
fun LibraryScreen(navigator: Navigator, player: PlayerState) {
    val phase = LocalPreviewSettings.current.phase("library")
    val largeFont = LocalDensity.current.fontScale >= 1.4f
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    var filterId by rememberSaveable { mutableStateOf("all") }
    var grid by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var sortId by rememberSaveable { mutableStateOf("recent") }
    var fabOpen by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var showTasks by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }

    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            delay(80)
            searchFocus.requestFocus()
        }
    }

    val filters = listOf(
        LibraryFilter("all", stringResource(R.string.library_filter_all)),
        LibraryFilter("generating", stringResource(R.string.library_filter_generating)),
        LibraryFilter("done", stringResource(R.string.library_filter_done)),
        LibraryFilter("pending", stringResource(R.string.library_filter_pending)),
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
                            if (!searchOpen) query = ""
                        },
                    )
                    Box {
                        IconAction(
                            icon = Ms.Sort,
                            description = stringResource(R.string.library_sort),
                            onClick = { sortOpen = true },
                        )
                        DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                            SortItem(stringResource(R.string.library_sort_recent), Ms.History, sortId == "recent") {
                                sortId = "recent"; sortOpen = false
                            }
                            SortItem(stringResource(R.string.library_sort_title), Ms.Sort, sortId == "title") {
                                sortId = "title"; sortOpen = false
                            }
                            SortItem(stringResource(R.string.library_sort_progress), Ms.Insights, sortId == "progress") {
                                sortId = "progress"; sortOpen = false
                            }
                        }
                    }
                    IconAction(
                        icon = if (grid) Ms.ViewList else Ms.GridView,
                        description = stringResource(if (grid) R.string.library_view_list else R.string.library_view_grid),
                        onClick = { grid = !grid },
                    )
                },
            )
        },
        floatingActionButton = {
            if (phase == ScreenPhase.Normal) {
                FloatingActionButtonMenu(
                    expanded = fabOpen,
                    button = {
                        FloatingActionButton(
                            onClick = { fabOpen = !fabOpen },
                            shape = CircleShape,
                        ) {
                            Icon(
                                painter = painterResource(if (fabOpen) Ms.Close else Ms.Add),
                                contentDescription = stringResource(R.string.action_import),
                            )
                        }
                    },
                ) {
                    FloatingActionButtonMenuItem(
                        onClick = { fabOpen = false; showImport = true },
                        text = { Text(stringResource(R.string.library_fab_local)) },
                        icon = { Icon(painterResource(Ms.UploadFile), contentDescription = null) },
                    )
                    FloatingActionButtonMenuItem(
                        onClick = {
                            fabOpen = false
                            scope.launch { snackbarHostState.showSnackbar("已从剪贴板读取 1 段文本") }
                        },
                        text = { Text(stringResource(R.string.library_fab_clipboard)) },
                        icon = { Icon(painterResource(Ms.ContentPaste), contentDescription = null) },
                    )
                    FloatingActionButtonMenuItem(
                        onClick = { fabOpen = false },
                        text = { Text(stringResource(R.string.library_fab_demo)) },
                        icon = { Icon(painterResource(Ms.AutoStories), contentDescription = null) },
                    )
                }
            }
        },
    ) { padding ->
        when (phase) {
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
                contentPadding = PaddingValues(bottom = 120.dp),
            ) {
                item(key = "hero") {
                    ContinueHero(
                        book = continueBook,
                        progress = continueBook.progress,
                        chapterTitle = "皇觉寺",
                        onContinue = { navigator.push(Dest.Player) },
                        onOpen = { navigator.push(Dest.Project(continueBook.id)) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                item(key = "task") {
                    RunningTaskCard(
                        percent = FakeData.runningTask.percent,
                        onStop = {},
                        onOpen = { showTasks = true },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                if (searchOpen) {
                    item(key = "search") {
                        InlineSearchField(
                            value = query,
                            onValueChange = { query = it },
                            hint = stringResource(R.string.library_search_hint),
                            focusRequester = searchFocus,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                item(key = "filters") {
                    FilterChipRow(
                        options = filters.map { it.id to it.label },
                        selectedId = filterId,
                        onSelect = { filterId = it },
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                item(key = "library-header") {
                    SectionHeader(
                        title = stringResource(R.string.library_filter_all) + "项目",
                        subtitle = "${books.size} / ${FakeData.books.size}",
                        spec = "SectionHeader · titleLargeEmphasized",
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                if (books.isEmpty()) {
                    item(key = "no-result") {
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
                } else if (grid) {
                    items(books.chunked(2), key = { row -> "grid-${row.first().id}" }) { row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { book ->
                                BookCard(
                                    book = book,
                                    onClick = { navigator.push(Dest.Project(book.id)) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                } else {
                    item(key = "list") {
                        GroupSurface(modifier = Modifier.padding(horizontal = 16.dp)) {
                            books.forEachIndexed { index, book ->
                                BookRow(book = book, onClick = { navigator.push(Dest.Project(book.id)) })
                                if (index != books.lastIndex) RowDivider()
                            }
                        }
                    }
                }
                item(key = "stats") {
                    StatsRow(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
    }

    if (showImport) {
        ImportSheet(
            onDismiss = { showImport = false },
            onDone = { showImport = false },
        )
    }
    if (showTasks) {
        TasksSheet(onDismiss = { showTasks = false }, player = player)
    }
}

@Composable
private fun SortItem(label: String, icon: Int, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = { Icon(painterResource(icon), contentDescription = null) },
        trailingIcon = {
            if (selected) {
                Icon(
                    painter = painterResource(Ms.Check),
                    contentDescription = stringResource(R.string.lab_current),
                )
            }
        },
    )
}

@Composable
private fun ContinueHero(
    book: Book,
    progress: Float,
    chapterTitle: String,
    onContinue: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val largeFont = LocalDensity.current.fontScale >= 1.4f
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverArt(
                    title = book.title,
                    modifier = Modifier
                        .width(84.dp)
                        .height(112.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.library_resume_prefix),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "第 4 章 · $chapterTitle",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(8.dp))
                    StatusChip(
                        text = stringResource(R.string.library_item_progress, book.completedChapters, book.chapters.size),
                        tone = Tone.Success,
                        icon = Ms.CheckCircle,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            ProgressBlock(
                progress = progress,
                startLabel = "${(progress * 100).toInt()}%",
                endLabel = "剩 ${book.chapters.size - book.completedChapters} 章",
            )
            Spacer(Modifier.height(16.dp))
            if (largeFont) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        text = stringResource(R.string.library_continue),
                        icon = Ms.Headphones,
                        onClick = onContinue,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SecondaryButton(
                        text = stringResource(R.string.project_title),
                        icon = Ms.MenuBook,
                        onClick = onOpen,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(
                        text = stringResource(R.string.library_continue),
                        icon = Ms.Headphones,
                        onClick = onContinue,
                    )
                    Spacer(Modifier.width(8.dp))
                    SecondaryButton(
                        text = stringResource(R.string.action_open),
                        icon = Ms.MenuBook,
                        onClick = onOpen,
                    )
                }
            }
        }
    }
}

@Composable
private fun RunningTaskCard(
    percent: Int,
    onStop: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val task = FakeData.runningTask
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(
                    icon = Ms.GraphicEq,
                    container = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f),
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.task_running) + " · " +
                            stringResource(R.string.task_running_chapters, task.chapterIndex, task.chapterCount),
                        style = MaterialTheme.typography.titleSmallEmphasized,
                    )
                    Text(
                        text = task.bookTitle,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconAction(
                    icon = Ms.StopCircle,
                    description = stringResource(R.string.task_stop),
                    onClick = onStop,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            LinearWavyProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = stringResource(R.string.task_running_eta, task.etaLabel),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun BookRow(book: Book, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            title = book.title,
            modifier = Modifier
                .width(56.dp)
                .height(76.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleMediumEmphasized,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.library_item_meta,
                    book.sourceName,
                    formatWordCount(book.wordCount),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearWavyProgressIndicator(
                    progress = { book.progress },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "${(book.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        StatusChip(
            text = when {
                book.isGenerating -> stringResource(R.string.status_generating)
                book.progress >= 1f -> stringResource(R.string.status_done)
                book.completedChapters == 0 -> stringResource(R.string.status_pending)
                else -> stringResource(R.string.status_paused)
            },
            tone = when {
                book.isGenerating -> Tone.Info
                book.progress >= 1f -> Tone.Success
                else -> Tone.Neutral
            },
        )
    }
}

@Composable
private fun BookCard(book: Book, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Box {
            CoverArt(
                title = book.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.78f),
            )
            StatusChip(
                text = when {
                    book.isGenerating -> stringResource(R.string.status_generating)
                    book.progress >= 1f -> stringResource(R.string.status_done)
                    else -> stringResource(R.string.status_pending)
                },
                tone = when {
                    book.isGenerating -> Tone.Info
                    book.progress >= 1f -> Tone.Success
                    else -> Tone.Neutral
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = book.title,
            style = MaterialTheme.typography.titleSmallEmphasized,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${(book.progress * 100).toInt()}% · ${book.completedChapters}/${book.chapters.size} 章",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun StatsRow(modifier: Modifier = Modifier) {
    val largeFont = LocalDensity.current.fontScale >= 1.4f
    GroupSurface(modifier = modifier) {
        if (largeFont) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                StatTile(value = "${FakeData.books.size}", label = stringResource(R.string.settings_group_data))
                StatTile(value = formatDuration(FakeData.books.sumOf { it.totalDurationSec }), label = "总时长")
                StatTile(value = FakeData.storage.usedLabel, label = "本机占用")
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatTile(value = "${FakeData.books.size}", label = "项目")
                StatTile(
                    value = FakeData.books.sumOf { it.chapters.count { c -> c.status == ChapterStatus.Done } }.toString(),
                    label = "已完成章节",
                )
                StatTile(value = formatDuration(FakeData.books.sumOf { it.totalDurationSec }), label = "总时长")
                StatTile(value = FakeData.storage.usedLabel, label = "本机占用")
            }
        }
    }
}
