package com.estimatefaster.audioforge.uipreview.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.estimatefaster.audioforge.uipreview.R
import com.estimatefaster.audioforge.uipreview.data.PlayerState
import com.estimatefaster.audioforge.uipreview.data.formatTime
import com.estimatefaster.audioforge.uipreview.design.CoverArt
import com.estimatefaster.audioforge.uipreview.design.EmptyState
import com.estimatefaster.audioforge.uipreview.design.FadeThrough
import com.estimatefaster.audioforge.uipreview.design.IconAction
import com.estimatefaster.audioforge.uipreview.design.LoadingState
import com.estimatefaster.audioforge.uipreview.design.ProgressLine
import com.estimatefaster.audioforge.uipreview.icons.Ms
import com.estimatefaster.audioforge.uipreview.lab.LocalPreviewSettings
import com.estimatefaster.audioforge.uipreview.lab.ScreenPhase
import com.estimatefaster.audioforge.uipreview.nav.Navigator
import com.estimatefaster.audioforge.uipreview.sheets.ChapterPickerSheet
import com.estimatefaster.audioforge.uipreview.sheets.SleepSheet
import com.estimatefaster.audioforge.uipreview.sheets.SpeedSheet

@Composable
fun PlayerScreen(navigator: Navigator, player: PlayerState) {
    val phase = LocalPreviewSettings.current.phase("player")
    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    val largeFont = LocalDensity.current.fontScale >= 1.4f

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            PlayerTopBar(
                onBack = { navigator.pop() },
                speedLabel = stringResource(R.string.player_speed_value, player.speed.toString()),
                sleepActive = player.sleepMinutes > 0,
                continuous = player.continuous,
                onSpeed = { showSpeed = true },
                onSleep = { showSleep = true },
                onToggleContinuous = { player.applyContinuous(!player.continuous) },
            )
        },
    ) { padding ->
        FadeThrough(
            target = phase,
            modifier = Modifier.fillMaxSize(),
            label = "player-phase",
        ) { state ->
            when (state) {
                ScreenPhase.Loading -> LoadingState(
                    label = "正在准备播放…",
                    modifier = Modifier.padding(padding),
                )

                else -> if (player.segments.isEmpty()) {
                    EmptyState(
                        icon = Ms.Headphones,
                        title = stringResource(R.string.player_empty_title),
                        body = stringResource(R.string.player_empty_body),
                        actionLabel = stringResource(R.string.action_back),
                        actionIcon = Ms.ArrowBack,
                        onAction = { navigator.pop() },
                        modifier = Modifier.padding(padding),
                    )
                } else {
                    PlayerContent(
                        player = player,
                        largeFont = largeFont,
                        modifier = Modifier.padding(padding),
                        onChapters = { showChapters = true },
                    )
                }
            }
        }
    }

    if (showSpeed) SpeedSheet(player = player, onDismiss = { showSpeed = false })
    if (showSleep) SleepSheet(player = player, onDismiss = { showSleep = false })
    if (showChapters) ChapterPickerSheet(player = player, onDismiss = { showChapters = false })
}

@Composable
private fun PlayerTopBar(
    onBack: () -> Unit,
    speedLabel: String,
    sleepActive: Boolean,
    continuous: Boolean,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
    onToggleContinuous: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(Ms.KeyboardArrowDown, stringResource(R.string.action_close), onBack)
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.player_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Box {
            IconAction(Ms.MoreVert, stringResource(R.string.action_more), { menuOpen = true })
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("播放速度 · $speedLabel") },
                    leadingIcon = { Icon(painterResource(Ms.Speed), contentDescription = null) },
                    onClick = { menuOpen = false; onSpeed() },
                )
                DropdownMenuItem(
                    text = { Text(if (sleepActive) "睡眠定时 · 已开启" else "睡眠定时") },
                    leadingIcon = { Icon(painterResource(Ms.Bedtime), contentDescription = null) },
                    onClick = { menuOpen = false; onSleep() },
                )
                DropdownMenuItem(
                    text = { Text("连播模式") },
                    leadingIcon = { Icon(painterResource(Ms.Repeat), contentDescription = null) },
                    trailingIcon = {
                        if (continuous) Icon(painterResource(Ms.Check), contentDescription = null)
                    },
                    onClick = { menuOpen = false; onToggleContinuous() },
                )
            }
        }
    }
}

@Composable
private fun PlayerContent(
    player: PlayerState,
    largeFont: Boolean,
    modifier: Modifier = Modifier,
    onChapters: () -> Unit,
) {
    val segment = player.currentSegment
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        CoverArt(
            title = player.book.title,
            modifier = Modifier.size(if (largeFont) 160.dp else 200.dp),
            textStyle = MaterialTheme.typography.displaySmallEmphasized,
        )
        Spacer(Modifier.height(22.dp))
        Text(
            text = player.book.title,
            style = MaterialTheme.typography.headlineSmallEmphasized,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable(role = Role.Button, onClick = onChapters)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${player.chapter.title} · ${player.chapter.number}/${player.chapters.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                painter = painterResource(Ms.ExpandMore),
                contentDescription = stringResource(R.string.player_chapters),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        if (segment != null) {
            Column(
                modifier = Modifier.padding(horizontal = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = buildString {
                        append(segment.role)
                        if (segment.emotion != null) append(" · ${segment.emotion}")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = segment.text,
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        ProgressSeek(player = player, modifier = Modifier.padding(horizontal = 28.dp))
        Spacer(Modifier.height(10.dp))
        Transport(player = player)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProgressSeek(player: PlayerState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            Slider(
                value = player.progress,
                onValueChange = { player.seekToFraction(it) },
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.primaryContainer,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTime(player.elapsedSec.toInt()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatTime(player.totalSec),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Transport(player: PlayerState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        FilledTonalIconButton(
            onClick = { player.previousSegment() },
            modifier = Modifier.size(52.dp),
        ) {
            Icon(
                painter = painterResource(Ms.SkipPrevious),
                contentDescription = stringResource(R.string.action_prev_segment),
                modifier = Modifier.size(24.dp),
            )
        }
        val canPlay = player.currentSegment?.hasAudio == true
        FloatingActionButton(
            onClick = { if (canPlay) player.toggle() },
            modifier = Modifier
                .size(72.dp)
                .alpha(if (canPlay) 1f else 0.45f),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(
                painter = painterResource(if (player.isPlaying) Ms.Pause else Ms.PlayArrow),
                contentDescription = stringResource(
                    if (player.isPlaying) R.string.action_pause else R.string.action_play,
                ),
                modifier = Modifier.size(34.dp),
            )
        }
        FilledTonalIconButton(
            onClick = { player.nextSegment() },
            modifier = Modifier.size(52.dp),
        ) {
            Icon(
                painter = painterResource(Ms.SkipNext),
                contentDescription = stringResource(R.string.action_next_segment),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
