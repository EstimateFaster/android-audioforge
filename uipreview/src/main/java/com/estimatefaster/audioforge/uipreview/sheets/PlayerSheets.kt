package com.estimatefaster.audioforge.uipreview.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.uipreview.R
import com.estimatefaster.audioforge.uipreview.data.PlayerState
import com.estimatefaster.audioforge.uipreview.data.formatTime
import com.estimatefaster.audioforge.uipreview.design.SegmentedChoice
import com.estimatefaster.audioforge.uipreview.design.SheetHeader
import com.estimatefaster.audioforge.uipreview.design.StatusChip
import com.estimatefaster.audioforge.uipreview.design.Tone
import com.estimatefaster.audioforge.uipreview.icons.Ms

@Composable
fun SpeedSheet(player: PlayerState, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var value by remember { mutableFloatStateOf(player.speed) }
    val options = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            SheetHeader(
                icon = Ms.Speed,
                title = stringResource(R.string.player_speed_title),
                subtitle = "仅影响本机播放，不改变音频文件",
                onClose = onDismiss,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${value}×",
                style = MaterialTheme.typography.displaySmallEmphasized,
            )
            Slider(
                value = value,
                onValueChange = { value = (it * 20f).toInt() / 20f },
                valueRange = 0.5f..3f,
                steps = 49,
                onValueChangeFinished = { player.applySpeed(value) },
            )
            Spacer(Modifier.height(8.dp))
            SegmentedChoice(
                options = options.map { "${it}×" }.map { com.estimatefaster.audioforge.uipreview.design.ChoiceOption(it) },
                selectedIndex = options.indexOfFirst { it == player.speed }.coerceAtLeast(0),
                onSelect = {
                    value = options[it]
                    player.applySpeed(options[it])
                },
            )
        }
    }
}

@Composable
fun SleepSheet(player: PlayerState, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val options = listOf(
        0 to stringResource(R.string.player_sleep_off),
        15 to stringResource(R.string.player_sleep_15),
        30 to stringResource(R.string.player_sleep_30),
        60 to stringResource(R.string.player_sleep_60),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            SheetHeader(
                icon = Ms.Bedtime,
                title = stringResource(R.string.player_sleep_title),
                subtitle = "到点自动暂停，不影响下载与生成",
                onClose = onDismiss,
            )
            Spacer(Modifier.height(8.dp))
            options.forEach { (minutes, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            player.applySleepMinutes(minutes)
                            onDismiss()
                        }
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(Ms.Timer),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(text = label, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    if (player.sleepMinutes == minutes) {
                        Icon(
                            painter = painterResource(Ms.Check),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChapterPickerSheet(player: PlayerState, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            SheetHeader(
                icon = Ms.FormatListNumbered,
                title = stringResource(R.string.player_chapters),
                subtitle = "共 ${player.chapters.size} 章 · ${player.book.title}",
                onClose = onDismiss,
            )
        }
        LazyColumn(modifier = Modifier.padding(bottom = 24.dp)) {
            items(player.chapters, key = { it.id }) { chapter ->
                val current = chapter.id == player.chapter.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            player.selectChapter(chapter.id)
                            player.playSegment(0)
                            onDismiss()
                        }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(36.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = chapter.number.toString().padStart(2, '0'),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (current) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = chapter.title,
                            style = MaterialTheme.typography.titleSmallEmphasized,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${chapter.segments.size} 段 · ${formatTime(chapter.durationSec)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (current) {
                        StatusChip(
                            text = stringResource(R.string.player_now),
                            tone = Tone.Success,
                            icon = Ms.GraphicEq,
                        )
                    } else if (chapter.segments.isEmpty()) {
                        StatusChip(text = stringResource(R.string.script_no_audio), tone = Tone.Neutral)
                    }
                }
            }
        }
    }
}
