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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
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
    var follow by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Segment?>(null) }
    val overrides = remember { mutableStateMapOf<Int, Segment>() }

    val stopFollow = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -2f) follow = false
                return Offset.Zero
            }
        }
    }

    val roles = remember(chapter.id) {
        listOf("all" to "全部") + chapter.segments.map { it.role }.distinct().map { it to it }
    }
    // 直接读 snapshot map，编辑保存后列表立即更新
    val segments = chapter.segments
        .filter { roleFilter == "all" || it.role == roleFilter }
        .map { overrides[it.index] ?: it }
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
                        Box(modifier = Modifier.padding(start = 12.dp)) {
                            var roleMenu by remember { mutableStateOf(false) }
                            TextButton(onClick = { roleMenu = true }) {
                                Icon(
                                    painter = painterResource(Ms.FilterAlt),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = roles.firstOrNull { it.first == roleFilter }?.second ?: "全部",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Spacer(Modifier.width(2.dp))
                                Icon(
                                    painter = painterResource(Ms.ExpandMore),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            DropdownMenu(
                                expanded = roleMenu,
                                onDismissRequest = { roleMenu = false },
                            ) {
                                roles.forEach { (id, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        trailingIcon = {
                                            if (roleFilter == id) {
                                                Icon(painterResource(Ms.Check), contentDescription = null)
                                            }
                                        },
                                        onClick = {
                                            roleFilter = id
                                            roleMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .nestedScroll(stopFollow)
                                .nestedScroll(scrollBehavior.nestedScrollConnection),
                            contentPadding = PaddingValues(bottom = 32.dp),
                        ) {
                            itemsIndexed(segments, key = { _, segment -> segment.index }) { _, segment ->
                                val isCurrent = segment.index == player.segmentIndex
                            Column(modifier = Modifier.animateItem()) {
                                        SegmentRow(
                                            segment = segment,
                                            isCurrent = isCurrent,
                                            isPlaying = isCurrent && player.isPlaying,
                                            onClick = {
                                                if (segment.hasAudio) player.playSegment(segment.index)
                                            },
                                            onTogglePlay = {
                                                if (isCurrent) {
                                                    player.toggle()
                                                } else {
                                                    player.playSegment(segment.index)
                                                }
                                            },
                                            onEdit = { editing = segment },
                                            onLongPress = {
                                                val manager = context.getSystemService(
                                                    Context.CLIPBOARD_SERVICE,
                                                ) as ClipboardManager
                                                manager.setPrimaryClip(
                                                    ClipData.newPlainText("script", segment.text),
                                                )
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(copiedMessage)
                                                }
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
    }

    editing?.let { segment ->
        SegmentEditSheet(
            segment = segment,
            onDismiss = { editing = null },
            onSave = { role, emotion, text ->
                overrides[segment.index] = segment.copy(role = role, emotion = emotion, text = text)
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
        .padding(start = 8.dp, end = 4.dp, top = 16.dp, bottom = 16.dp)

    val background by animateColorAsState(
        targetValue = if (isCurrent) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            Color.Transparent
        },
        label = "segment-current",
    )

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(MaterialTheme.shapes.large)
                .background(background),
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
                    if (isCurrent) {
                        TextButton(
                            onClick = onEdit,
                            modifier = Modifier.height(36.dp),
                        ) {
                            Icon(
                                painter = painterResource(Ms.Edit),
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.action_edit), style = MaterialTheme.typography.labelMedium)
                        }
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
                IconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier.size(48.dp),
                    enabled = segment.hasAudio,
                ) {
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
