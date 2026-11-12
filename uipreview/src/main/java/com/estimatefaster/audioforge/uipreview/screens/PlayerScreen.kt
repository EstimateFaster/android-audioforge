package com.estimatefaster.audioforge.uipreview.screens

import androidx.compose.foundation.background
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.uipreview.R
import com.estimatefaster.audioforge.uipreview.data.FakeData
import com.estimatefaster.audioforge.uipreview.data.PlayerState
import com.estimatefaster.audioforge.uipreview.data.formatTime
import com.estimatefaster.audioforge.uipreview.design.CoverArt
import com.estimatefaster.audioforge.uipreview.design.EmptyState
import com.estimatefaster.audioforge.uipreview.design.IconAction
import com.estimatefaster.audioforge.uipreview.design.LoadingState
import com.estimatefaster.audioforge.uipreview.icons.Ms
import com.estimatefaster.audioforge.uipreview.lab.LocalPreviewSettings
import com.estimatefaster.audioforge.uipreview.lab.ScreenPhase
import com.estimatefaster.audioforge.uipreview.nav.Navigator
import com.estimatefaster.audioforge.uipreview.nav.RoleAvatar
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
                onMore = {},
                largeFont = largeFont,
            )
        },
    ) { padding ->
        when (phase) {
            ScreenPhase.Loading -> LoadingState(
                label = "正在准备播放…",
                modifier = Modifier.padding(padding),
            )

            ScreenPhase.Normal, ScreenPhase.Empty, ScreenPhase.Error ->
                if (player.segments.isEmpty()) {
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
                        onSpeed = { showSpeed = true },
                        onSleep = { showSleep = true },
                        onChapters = { showChapters = true },
                    )
                }
        }
    }

    if (showSpeed) SpeedSheet(player = player, onDismiss = { showSpeed = false })
    if (showSleep) SleepSheet(player = player, onDismiss = { showSleep = false })
    if (showChapters) ChapterPickerSheet(player = player, onDismiss = { showChapters = false })
}

@Composable
private fun PlayerTopBar(onBack: () -> Unit, onMore: () -> Unit, largeFont: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(Ms.ExpandMore, stringResource(R.string.action_close), onBack)
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.player_title),
                style = MaterialTheme.typography.labelLargeEmphasized,
            )
            Text(
                text = "本地音频 · 不耗流量",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        IconAction(Ms.MoreVert, stringResource(R.string.action_more), onMore)
    }
}

@Composable
private fun PlayerContent(
    player: PlayerState,
    largeFont: Boolean,
    modifier: Modifier = Modifier,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
    onChapters: () -> Unit,
) {
    val segment = player.currentSegment
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .size(if (largeFont) 180.dp else 240.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            CoverArt(
                title = player.book.title,
                modifier = Modifier.size(if (largeFont) 160.dp else 216.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = player.book.title,
            style = MaterialTheme.typography.headlineSmallEmphasized,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${player.chapter.title} · 第 ${player.segmentIndex + 1} / ${player.segments.size} 句",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        if (segment != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RoleAvatar(name = segment.role, size = 28)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = segment.role,
                            style = MaterialTheme.typography.labelLargeEmphasized,
                        )
                        if (segment.emotion != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "· ${segment.emotion}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = segment.text,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        ProgressSeek(player = player, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(8.dp))
        Transport(
            player = player,
            onPrevChapter = { player.previousChapter() },
            onNextChapter = { player.nextChapter() },
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistChip(
                onClick = onSpeed,
                label = { Text(stringResource(R.string.player_speed_value, player.speed.toString())) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(Ms.Speed),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                shape = CircleShape,
            )
            AssistChip(
                onClick = onSleep,
                label = {
                    Text(
                        if (player.sleepMinutes == 0) {
                            stringResource(R.string.player_sleep)
                        } else {
                            formatTime(player.sleepRemainingSec)
                        },
                    )
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(Ms.Bedtime),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                shape = CircleShape,
                colors = if (player.sleepMinutes > 0) {
                    AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        leadingIconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                } else {
                    AssistChipDefaults.assistChipColors()
                },
            )
            AssistChip(
                onClick = { player.applyContinuous(!player.continuous) },
                label = { Text(stringResource(R.string.player_continuous)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(Ms.Repeat),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                shape = CircleShape,
                colors = if (player.continuous) {
                    AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                } else {
                    AssistChipDefaults.assistChipColors()
                },
            )
            AssistChip(
                onClick = onChapters,
                label = { Text("${player.chapters.size} 章") },
                leadingIcon = {
                    Icon(
                        painter = painterResource(Ms.FormatListNumbered),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                shape = CircleShape,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "音频由本机合成 · ${FakeData.storage.usedLabel} 已用",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProgressSeek(player: PlayerState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            LinearWavyProgressIndicator(
                progress = { player.progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Slider(
                value = player.progress,
                onValueChange = { player.seekToFraction(it) },
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
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
private fun Transport(
    player: PlayerState,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FilledTonalIconButton(
                onClick = { player.previousSegment() },
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    painter = painterResource(Ms.SkipPrevious),
                    contentDescription = stringResource(R.string.action_prev_segment),
                    modifier = Modifier.size(26.dp),
                )
            }
            FloatingActionButton(
                onClick = { player.toggle() },
                modifier = Modifier.size(72.dp),
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
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    painter = painterResource(Ms.SkipNext),
                    contentDescription = stringResource(R.string.action_next_segment),
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onPrevChapter, shape = CircleShape) {
                Icon(painterResource(Ms.ChevronLeft), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.action_prev_chapter), style = MaterialTheme.typography.labelMedium)
            }
            FilledTonalButton(onClick = onNextChapter, shape = CircleShape) {
                Text(stringResource(R.string.action_next_chapter), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(4.dp))
                Icon(painterResource(Ms.ChevronRight), contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}
