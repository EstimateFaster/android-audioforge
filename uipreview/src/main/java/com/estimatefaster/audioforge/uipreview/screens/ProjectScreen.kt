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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import com.estimatefaster.audioforge.uipreview.design.FadeThrough
import com.estimatefaster.audioforge.uipreview.design.IconAction
import com.estimatefaster.audioforge.uipreview.design.ListRow
import com.estimatefaster.audioforge.uipreview.design.LoadingState
import com.estimatefaster.audioforge.uipreview.design.PrimaryButton
import com.estimatefaster.audioforge.uipreview.design.ProgressLine
import com.estimatefaster.audioforge.uipreview.design.RowDivider
import com.estimatefaster.audioforge.uipreview.design.SectionLabel
import com.estimatefaster.audioforge.uipreview.design.StatusChip
import com.estimatefaster.audioforge.uipreview.design.Tone
import com.estimatefaster.audioforge.uipreview.icons.Ms
import com.estimatefaster.audioforge.uipreview.lab.LocalPreviewSettings
import com.estimatefaster.audioforge.uipreview.lab.ScreenPhase
import com.estimatefaster.audioforge.uipreview.nav.AppScaffold
import com.estimatefaster.audioforge.uipreview.nav.Dest
import com.estimatefaster.audioforge.uipreview.nav.Navigator
import com.estimatefaster.audioforge.uipreview.nav.RoleAvatar
import com.estimatefaster.audioforge.uipreview.sheets.BatchSheet
import com.estimatefaster.audioforge.uipreview.sheets.ExportSheet
import kotlinx.coroutines.launch

@Composable
fun ProjectScreen(navigator: Navigator, player: PlayerState, bookId: Long) {
    val book = remember(bookId) { FakeData.book(bookId) }
    val phase = LocalPreviewSettings.current.phase("project")
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var menuOpen by remember { mutableStateOf(false) }
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
                                        painterResource(Ms.Delete),
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
        FadeThrough(
            target = phase,
            modifier = Modifier.fillMaxSize(),
            label = "project-phase",
        ) { state ->
            when (state) {
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
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    item(key = "header") {
                        ProjectHeader(book = book)
                    }
                    item(key = "action") {
                        PrimaryButton(
                            text = if (book.isGenerating) {
                                "继续生成 · 第 ${FakeData.runningTask.chapterIndex} 章"
                            } else {
                                stringResource(R.string.project_continue)
                            },
                            icon = Ms.AutoAwesome,
                            onClick = { showBatch = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                                .padding(top = 20.dp),
                        )
                    }
                    item(key = "chapters-label") {
                        SectionLabel(
                            text = stringResource(R.string.project_chapters),
                            trailing = "${book.completedChapters} / ${book.chapters.size}",
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(book.chapters.take(4), key = { it.id }) { chapter ->
                        ChapterRow(chapter = chapter, onClick = { navigator.push(Dest.Script(book.id, chapter.id)) })
                        RowDivider()
                    }
                    item(key = "chapters-all") {
                        ListRow(
                            title = "查看全部 ${book.chapters.size} 章",
                            icon = Ms.FormatListNumbered,
                            onClick = { navigator.push(Dest.Chapters(book.id)) },
                        )
                    }
                    item(key = "characters-label") {
                        SectionLabel(
                            text = stringResource(R.string.project_characters),
                            trailing = "${book.characters.size} 位",
                            modifier = Modifier.padding(top = 20.dp),
                        )
                    }
                    item(key = "characters") {
                        CharactersStrip(
                            book = book,
                            onManage = { navigator.push(Dest.Characters(book.id)) },
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
private fun ProjectHeader(book: Book) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            title = book.title,
            modifier = Modifier
                .width(76.dp)
                .height(100.dp),
            textStyle = MaterialTheme.typography.titleLargeEmphasized,
        )
        Spacer(Modifier.width(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.author,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "已生成 ${book.completedChapters} / ${book.chapters.size} 章",
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
            Spacer(Modifier.height(10.dp))
            ProgressLine(progress = book.progress, height = 16.dp)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(
                    text = if (book.isGenerating) {
                        stringResource(R.string.status_generating)
                    } else {
                        stringResource(R.string.status_paused)
                    },
                    tone = if (book.isGenerating) Tone.Info else Tone.Neutral,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "剩 ${book.chapters.size - book.completedChapters} 章",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChapterRow(chapter: Chapter, onClick: () -> Unit) {
    ListRow(
        title = chapter.title,
        subtitle = if (chapter.segments.isEmpty()) {
            "尚未解析"
        } else {
            "${chapter.segments.size} 段 · ${formatTime(chapter.durationSec)}"
        },
        onClick = onClick,
        leading = {
            Box(
                modifier = Modifier.size(36.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = chapter.number.toString().padStart(2, '0'),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingText = when (chapter.status) {
            ChapterStatus.Done -> stringResource(R.string.status_done)
            ChapterStatus.Generating -> stringResource(R.string.status_generating)
            ChapterStatus.Failed -> stringResource(R.string.status_failed)
            else -> stringResource(R.string.status_pending)
        },
    )
}

@Composable
private fun CharactersStrip(book: Book, onManage: () -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 8.dp),
    ) {
        items(book.characters) { character ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(56.dp),
            ) {
                RoleAvatar(name = character.name, size = 48)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = character.name,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(56.dp)
                    .clickable(onClick = onManage),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(onClick = onManage),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(Ms.ChevronRight),
                        contentDescription = stringResource(R.string.action_manage),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.action_manage),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
