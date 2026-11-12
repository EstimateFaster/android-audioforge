package com.estimatefaster.audioforge.uipreview.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.uipreview.R
import com.estimatefaster.audioforge.uipreview.data.Book
import com.estimatefaster.audioforge.uipreview.data.Chapter
import com.estimatefaster.audioforge.uipreview.data.ChapterStatus
import com.estimatefaster.audioforge.uipreview.data.FakeData
import com.estimatefaster.audioforge.uipreview.data.PlayerState
import com.estimatefaster.audioforge.uipreview.data.formatTime
import com.estimatefaster.audioforge.uipreview.data.formatWordCount
import com.estimatefaster.audioforge.uipreview.design.AppTopBar
import com.estimatefaster.audioforge.uipreview.design.ConfirmDialog
import com.estimatefaster.audioforge.uipreview.design.CoverArt
import com.estimatefaster.audioforge.uipreview.design.EmptyState
import com.estimatefaster.audioforge.uipreview.design.ErrorState
import com.estimatefaster.audioforge.uipreview.design.GroupSurface
import com.estimatefaster.audioforge.uipreview.design.IconAction
import com.estimatefaster.audioforge.uipreview.design.IconBubble
import com.estimatefaster.audioforge.uipreview.design.LoadingState
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
import com.estimatefaster.audioforge.uipreview.nav.RoleAvatar
import com.estimatefaster.audioforge.uipreview.nav.Navigator
import kotlinx.coroutines.launch

@Composable
fun ProjectScreen(navigator: Navigator, player: PlayerState, bookId: Long) {
    val book = remember(bookId) { FakeData.book(bookId) }
    val phase = LocalPreviewSettings.current.phase("project")
    val largeFont = LocalDensity.current.fontScale >= 1.4f
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var menuOpen by remember { mutableStateOf(false) }
    var rangeOpen by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showBatch by remember { mutableStateOf(false) }

    AppScaffold(
        navigator = navigator,
        player = player,
        showNavBar = false,
        showMiniPlayer = true,
        snackbarHostState = snackbarHostState,
        topBar = {
            AppTopBar(
                title = book.title,
                subtitle = stringResource(
                    R.string.project_meta,
                    book.chapters.size,
                    formatWordCount(book.wordCount),
                ),
                scrollBehavior = scrollBehavior,
                onBack = { navigator.pop() },
                actions = {
                    IconAction(
                        icon = Ms.Headphones,
                        description = stringResource(R.string.project_open_player),
                        onClick = { navigator.push(Dest.Player) },
                    )
                    Box {
                        IconAction(
                            icon = Ms.MoreVert,
                            description = stringResource(R.string.action_more),
                            onClick = { menuOpen = true },
                        )
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.project_export)) },
                                onClick = { menuOpen = false; showExport = true },
                                leadingIcon = { Icon(painterResource(Ms.Download), contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.project_open_player)) },
                                onClick = { menuOpen = false; navigator.push(Dest.Player) },
                                leadingIcon = { Icon(painterResource(Ms.Headphones), contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_share)) },
                                onClick = {
                                    menuOpen = false
                                    scope.launch { snackbarHostState.showSnackbar("预览构建：分享面板未接入") }
                                },
                                leadingIcon = { Icon(painterResource(Ms.Share), contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.project_delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = { menuOpen = false; showDelete = true },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(Ms.Delete),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        when (phase) {
            ScreenPhase.Loading -> LoadingState(
                label = "正在读取项目…",
                modifier = Modifier.padding(padding),
            )

            ScreenPhase.Error -> ErrorState(
                title = "项目加载失败",
                body = "本机数据库暂时不可用，项目本身没有损坏。",
                onRetry = {},
                modifier = Modifier.padding(padding),
            )

            ScreenPhase.Empty -> EmptyState(
                icon = Ms.MenuBook,
                title = stringResource(R.string.project_empty_chapters),
                body = stringResource(R.string.project_empty_chapters_body),
                actionLabel = "重新解析",
                actionIcon = Ms.RestartAlt,
                onAction = {},
                modifier = Modifier.padding(padding),
            )

            ScreenPhase.Normal -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            ) {
                item(key = "hero") {
                    ProjectHero(book = book, onPlay = { navigator.push(Dest.Player) })
                }
                item(key = "stats") {
                    StatsGrid(book = book)
                }
                item(key = "action") {
                    ContinueAction(
                        running = book.isGenerating,
                        percent = FakeData.runningTask.percent,
                        largeFont = largeFont,
                        onContinue = { showBatch = true },
                        onRange = { rangeOpen = true },
                        rangeOpen = rangeOpen,
                        onDismissRange = { rangeOpen = false },
                        onPickRange = { rangeOpen = false; showBatch = true },
                    )
                }
                item(key = "chapters-header") {
                    SectionHeader(
                        title = stringResource(R.string.project_chapters),
                        subtitle = stringResource(
                            R.string.project_status_chapter,
                            book.completedChapters,
                            book.chapters.size - book.completedChapters,
                        ),
                        spec = "SectionHeader + ListItem · 64dp",
                        actionIcon = Ms.ChevronRight,
                        actionDescription = stringResource(R.string.action_view_all),
                        onAction = { navigator.push(Dest.Chapters(book.id)) },
                    )
                }
                item(key = "chapters") {
                    GroupSurface {
                        book.chapters.take(4).forEachIndexed { index, chapter ->
                            ChapterRow(
                                chapter = chapter,
                                onClick = { navigator.push(Dest.Script(book.id, chapter.id)) },
                            )
                            if (index != 3) RowDivider(startIndent = 68.dp)
                        }
                    }
                }
                item(key = "chapters-all") {
                    SecondaryButton(
                        text = stringResource(R.string.action_view_all) + " ${book.chapters.size} 章",
                        icon = Ms.FormatListNumbered,
                        onClick = { navigator.push(Dest.Chapters(book.id)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
                item(key = "characters-header") {
                    SectionHeader(
                        title = stringResource(R.string.project_characters),
                        subtitle = "${book.characters.size} 个角色 · 已配音 ${book.characters.count { it.voice != null }}",
                        spec = "列表行 + RoleAvatar",
                    )
                }
                item(key = "characters") {
                    CharactersStrip(book = book)
                }
                item(key = "note") {
                    Row(
                        modifier = Modifier.padding(top = 24.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            painter = painterResource(Ms.Lock),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.project_ai_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showDelete) {
        ConfirmDialog(
            title = stringResource(R.string.project_delete),
            body = "会同时删除《${book.title}》的章节、音频与导出记录。此操作不可撤销。",
            confirmText = stringResource(R.string.action_delete),
            icon = Ms.Delete,
            danger = true,
            onConfirm = {
                showDelete = false
                scope.launch {
                    snackbarHostState.showSnackbar("预览构建：已模拟删除《${book.title}》")
                    navigator.pop()
                }
            },
            onDismiss = { showDelete = false },
        )
    }
    if (showExport) {
        ExportSheet(book = book, onDismiss = { showExport = false })
    }
    if (showBatch) {
        BatchSheet(book = book, onDismiss = { showBatch = false })
    }
}

@Composable
private fun ProjectHero(book: Book, onPlay: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverArt(
                title = book.title,
                modifier = Modifier
                    .width(96.dp)
                    .height(128.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularWavyProgressIndicator(
                            progress = { book.progress },
                            modifier = Modifier.size(76.dp),
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                        Text(
                            text = "${(book.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLargeEmphasized,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.project_progress_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(
                                R.string.project_status_chapter,
                                book.completedChapters,
                                book.chapters.size - book.completedChapters,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        StatusChip(
                            text = if (book.isGenerating) {
                                stringResource(R.string.status_generating)
                            } else {
                                stringResource(R.string.status_paused)
                            },
                            tone = if (book.isGenerating) Tone.Info else Tone.Neutral,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                SecondaryButton(
                    text = stringResource(R.string.project_open_player),
                    icon = Ms.PlayArrow,
                    onClick = onPlay,
                )
            }
        }
    }
}

@Composable
private fun StatsGrid(book: Book) {
    val largeFont = LocalDensity.current.fontScale >= 1.4f
    val done = book.completedChapters
    val total = book.chapters.size
    GroupSurface(modifier = Modifier.padding(top = 12.dp)) {
        if (largeFont) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                StatTile(value = "$done / $total", label = stringResource(R.string.project_stat_chapters))
                StatTile(value = "${book.segmentCount}", label = stringResource(R.string.project_stat_segments))
                StatTile(value = "${book.characters.size}", label = stringResource(R.string.project_stat_characters))
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatTile(value = "$done / $total", label = stringResource(R.string.project_stat_chapters))
                StatTile(value = "${book.segmentCount}", label = stringResource(R.string.project_stat_segments))
                StatTile(value = "${book.characters.size}", label = stringResource(R.string.project_stat_characters))
            }
        }
    }
}

@Composable
private fun ContinueAction(
    running: Boolean,
    percent: Int,
    largeFont: Boolean,
    onContinue: () -> Unit,
    onRange: () -> Unit,
    rangeOpen: Boolean,
    onDismissRange: () -> Unit,
    onPickRange: () -> Unit,
) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        if (running) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(
                    icon = Ms.AutoAwesome,
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                    size = 36.dp,
                    iconSize = 18.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.task_running_eta, FakeData.runningTask.etaLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.labelLargeEmphasized,
                )
            }
            Spacer(Modifier.height(8.dp))
            ProgressBlock(progress = percent / 100f)
            Spacer(Modifier.height(16.dp))
        }
        if (largeFont) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onContinue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = CircleShape,
                ) {
                    Icon(painterResource(Ms.AutoAwesome), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.project_continue), style = MaterialTheme.typography.labelLarge)
                }
                SecondaryButton(
                    text = stringResource(R.string.project_continue_range),
                    icon = Ms.ExpandMore,
                    onClick = onRange,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Box {
                SplitButtonLayout(
                    leadingButton = {
                        Button(
                            onClick = onContinue,
                            shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp, topEnd = 8.dp, bottomEnd = 8.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp),
                        ) {
                            Icon(painterResource(Ms.AutoAwesome), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.project_continue), style = MaterialTheme.typography.labelLarge)
                        }
                    },
                    trailingButton = {
                        SplitButtonDefaults.TrailingButton(onClick = onRange) {
                            Icon(
                                painter = painterResource(Ms.ExpandMore),
                                contentDescription = stringResource(R.string.project_continue_range),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                )
                DropdownMenu(expanded = rangeOpen, onDismissRequest = onDismissRange) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.project_range_rest)) },
                        onClick = onPickRange,
                        leadingIcon = { Icon(painterResource(Ms.PlaylistPlay), contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.project_range_this)) },
                        onClick = onPickRange,
                        leadingIcon = { Icon(painterResource(Ms.Note), contentDescription = null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(chapter: Chapter, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = chapter.number.toString().padStart(2, '0'),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chapter.title,
                style = MaterialTheme.typography.titleSmallEmphasized,
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
            )
        }
        StatusChip(
            text = when (chapter.status) {
                ChapterStatus.Done -> stringResource(R.string.status_done)
                ChapterStatus.Generating -> stringResource(R.string.status_generating)
                ChapterStatus.Failed -> stringResource(R.string.status_failed)
                ChapterStatus.Pending -> stringResource(R.string.status_pending)
            },
            tone = when (chapter.status) {
                ChapterStatus.Done -> Tone.Success
                ChapterStatus.Generating -> Tone.Info
                ChapterStatus.Failed -> Tone.Error
                ChapterStatus.Pending -> Tone.Neutral
            },
        )
    }
}

@Composable
private fun CharactersStrip(book: Book) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(book.characters) { character ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                RoleAvatar(name = character.name, size = 52)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = character.name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
