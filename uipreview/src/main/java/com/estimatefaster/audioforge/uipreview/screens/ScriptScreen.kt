package com.estimatefaster.audioforge.uipreview.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.estimatefaster.audioforge.uipreview.R
import com.estimatefaster.audioforge.uipreview.data.FakeData
import com.estimatefaster.audioforge.uipreview.data.PlayerState
import com.estimatefaster.audioforge.uipreview.data.Segment
import com.estimatefaster.audioforge.uipreview.design.AppTopBar
import com.estimatefaster.audioforge.uipreview.design.EmptyState
import com.estimatefaster.audioforge.uipreview.design.ErrorState
import com.estimatefaster.audioforge.uipreview.design.FadeThrough
import com.estimatefaster.audioforge.uipreview.design.FilterChipRow
import com.estimatefaster.audioforge.uipreview.design.IconAction
import com.estimatefaster.audioforge.uipreview.design.LoadingState
import com.estimatefaster.audioforge.uipreview.icons.Ms
import com.estimatefaster.audioforge.uipreview.lab.LocalPreviewSettings
import com.estimatefaster.audioforge.uipreview.lab.ScreenPhase
import com.estimatefaster.audioforge.uipreview.nav.AppScaffold
import com.estimatefaster.audioforge.uipreview.nav.RoleAvatar
import com.estimatefaster.audioforge.uipreview.nav.Navigator
import com.estimatefaster.audioforge.uipreview.sheets.SegmentEditSheet
import kotlinx.coroutines.launch

@Composable
fun ScriptScreen(navigator: Navigator, player: PlayerState, bookId: Long, chapterId: Long) {
    val chapter = remember(bookId, chapterId) {
        FakeData.chapter(bookId, chapterId) ?: FakeData.book(bookId).chapters.first()
    }
    val phase = LocalPreviewSettings.current.phase("script")
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.script_copied)
    val listState = rememberLazyListState()

    var roleFilter by rememberSaveable { mutableStateOf("all") }
    var follow by rememberSaveable { mutableStateOf(true) }
    var editing by remember { mutableStateOf<Segment?>(null) }

    val roles = remember(chapter.id) {
        listOf("all" to "全部") + chapter.segments.map { it.role }.distinct().map { it to it }
    }
    val segments = remember(chapter.id, roleFilter) {
        if (roleFilter == "all") chapter.segments else chapter.segments.filter { it.role == roleFilter }
    }
    val currentIndex = segments.indexOfFirst { it.index == player.segmentIndex }

    LaunchedEffect(player.segmentIndex, follow, roleFilter) {
        if (follow && currentIndex >= 0) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    AppScaffold(
        navigator = navigator,
        player = player,
        showNavBar = false,
        showMiniPlayer = true,
        snackbarHostState = snackbarHostState,
        topBar = {
            AppTopBar(
                title = chapter.title,
                subtitle = stringResource(R.string.script_subtitle, chapter.segments.size, chapter.generatedCount),
                scrollBehavior = scrollBehavior,
                onBack = { navigator.pop() },
                actions = {
                    IconAction(
                        icon = Ms.MyLocation,
                        description = stringResource(R.string.action_locate_current),
                        onClick = {
                            follow = true
                            if (currentIndex >= 0) scope.launch { listState.animateScrollToItem(currentIndex) }
                        },
                    )
                    IconAction(
                        icon = if (follow) Ms.Sync else Ms.SyncDisabled,
                        description = stringResource(R.string.script_follow),
                        onClick = { follow = !follow },
                        tint = if (follow) MaterialTheme.colorScheme.primary else null,
                    )
                },
            )
        },
    ) { padding ->
        FadeThrough(
            target = phase,
            modifier = Modifier.fillMaxSize(),
            label = "script-phase",
        ) { state ->
            when (state) {
                ScreenPhase.Loading -> LoadingState(
                    label = "正在解析台词…",
                    modifier = Modifier.padding(padding),
                )

                ScreenPhase.Error -> ErrorState(
                    title = "台词加载失败",
                    body = "解析结果仍在本机，可以重试。",
                    onRetry = {},
                    modifier = Modifier.padding(padding),
                )

                ScreenPhase.Empty, ScreenPhase.Normal -> if (chapter.segments.isEmpty()) {
                    EmptyState(
                        icon = Ms.GraphicEq,
                        title = stringResource(R.string.script_empty_title),
                        body = stringResource(R.string.script_empty_body),
                        actionLabel = stringResource(R.string.script_parse),
                        actionIcon = Ms.AutoAwesome,
                        onAction = { scope.launch { snackbarHostState.showSnackbar("预览构建：已提交本章解析") } },
                        modifier = Modifier.padding(padding),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                        FilterChipRow(
                            options = roles,
                            selectedId = roleFilter,
                            onSelect = { roleFilter = it },
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                        )
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .nestedScroll(scrollBehavior.nestedScrollConnection),
                            contentPadding = PaddingValues(bottom = 32.dp),
                        ) {
                            itemsIndexed(segments, key = { _, segment -> segment.index }) { _, segment ->
                                val isCurrent = segment.index == player.segmentIndex
                                SegmentRow(
                                    segment = segment,
                                    isCurrent = isCurrent,
                                    isPlaying = isCurrent && player.isPlaying,
                                    onClick = { player.playSegment(segment.index) },
                                    onTogglePlay = {
                                        if (isCurrent) player.toggle() else player.playSegment(segment.index)
                                    },
                                    onEdit = { editing = segment },
                                    onLongPress = {
                                        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        manager.setPrimaryClip(ClipData.newPlainText("script", segment.text))
                                        scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                                    },
                                    showDivider = !isCurrent,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { segment ->
        SegmentEditSheet(
            segment = segment,
            onDismiss = { editing = null },
            onSave = {
                editing = null
                scope.launch { snackbarHostState.showSnackbar("已保存这一段") }
            },
        )
    }
}

@Composable
private fun SegmentRow(
    segment: Segment,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onTogglePlay: () -> Unit,
    onEdit: () -> Unit,
    onLongPress: () -> Unit,
    showDivider: Boolean,
) {
    val rowModifier = Modifier
        .combinedClickable(onClick = onClick, onLongClick = onLongPress)
        .padding(start = if (isCurrent) 8.dp else 20.dp, end = 4.dp, top = 16.dp, bottom = 16.dp)

    Column {
        Row(
            modifier = if (isCurrent) {
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
            } else {
                Modifier.fillMaxWidth()
            },
        ) {
            Column(modifier = rowModifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoleAvatar(
                        name = segment.role,
                        size = 22,
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = segment.role,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    if (segment.emotion != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "· ${segment.emotion}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (!segment.hasAudio) {
                        Text(
                            text = stringResource(R.string.script_no_audio),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = segment.text,
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 27.sp,
                )
            }
            Column(
                modifier = Modifier.padding(end = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                IconButton(onClick = onTogglePlay, modifier = Modifier.size(40.dp), enabled = segment.hasAudio) {
                    Icon(
                        painter = painterResource(
                            when {
                                isPlaying -> Ms.Pause
                                segment.hasAudio -> Ms.PlayArrow
                                else -> Ms.VolumeOff
                            },
                        ),
                        contentDescription = stringResource(
                            if (isPlaying) R.string.action_pause else R.string.action_play,
                        ),
                        tint = if (segment.hasAudio) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        },
                        modifier = Modifier.size(22.dp),
                    )
                }
                if (isCurrent) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                        Icon(
                            painter = painterResource(Ms.Edit),
                            contentDescription = stringResource(R.string.action_edit),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
            )
        }
    }
}
