package com.estimatefaster.audioforge.uipreview.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.uipreview.R
import com.estimatefaster.audioforge.uipreview.data.Book
import com.estimatefaster.audioforge.uipreview.data.ChapterStatus
import com.estimatefaster.audioforge.uipreview.data.FakeData
import com.estimatefaster.audioforge.uipreview.data.PlayerState
import com.estimatefaster.audioforge.uipreview.design.EmptyState
import com.estimatefaster.audioforge.uipreview.design.IconAction
import com.estimatefaster.audioforge.uipreview.design.IconBubble
import com.estimatefaster.audioforge.uipreview.design.PrimaryButton
import com.estimatefaster.audioforge.uipreview.design.SecondaryButton
import com.estimatefaster.audioforge.uipreview.design.SectionHeader
import com.estimatefaster.audioforge.uipreview.design.SheetHeader
import com.estimatefaster.audioforge.uipreview.design.StatusChip
import com.estimatefaster.audioforge.uipreview.design.Tone
import com.estimatefaster.audioforge.uipreview.icons.Ms
import kotlinx.coroutines.delay

@Composable
fun ExportSheet(book: Book, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var scope by rememberSaveable { mutableStateOf("all") }
    var phase by remember { mutableStateOf("select") }
    var progress by remember { mutableFloatStateOf(0f) }
    val done = book.chapters.filter { it.status == ChapterStatus.Done }
    val selected = when (scope) {
        "current" -> done.take(1)
        else -> done
    }

    LaunchedEffect(phase) {
        if (phase != "exporting") return@LaunchedEffect
        while (progress < 1f) {
            delay(90)
            progress = (progress + 0.06f).coerceAtMost(1f)
        }
        phase = "result"
    }

    ModalBottomSheet(
        onDismissRequest = { if (phase != "exporting") onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            SheetHeader(
                icon = Ms.Download,
                title = stringResource(R.string.export_title),
                subtitle = stringResource(R.string.export_subtitle),
                onClose = { if (phase != "exporting") onDismiss() },
            )
            when (phase) {
                "select" -> {
                    SectionHeader(title = stringResource(R.string.export_range))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "all" to stringResource(R.string.export_all),
                            "current" to stringResource(R.string.export_current),
                        ).forEach { (id, label) ->
                            FilterChip(
                                selected = scope == id,
                                onClick = { scope = id },
                                label = { Text(label) },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(
                                    R.string.export_estimate,
                                    "${selected.size * 4} 分钟",
                                    "${selected.size * 6} MB",
                                ),
                                style = MaterialTheme.typography.titleMediumEmphasized,
                            )
                            Text(
                                text = "已选 ${selected.size} 章 · 仅导出已生成的音频",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(
                        text = stringResource(R.string.export_start, selected.size),
                        icon = Ms.FileDownload,
                        enabled = selected.isNotEmpty(),
                        onClick = {
                            progress = 0f
                            phase = "exporting"
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton(
                        text = stringResource(R.string.action_close),
                        icon = Ms.Close,
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                "exporting" -> {
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.displaySmallEmphasized,
                    )
                    Text(
                        text = "正在拼接 ${selected.size} 章的音频…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(20.dp))
                    SecondaryButton(
                        text = "取消导出",
                        icon = Ms.Close,
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBubble(
                            icon = Ms.CheckCircle,
                            container = MaterialTheme.colorScheme.primaryContainer,
                            content = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "导出完成",
                                style = MaterialTheme.typography.titleMediumEmphasized,
                            )
                            Text(
                                text = "成功 ${selected.size} 章 · 保存到 音乐/AudioForge",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            selected.take(3).forEach { chapter ->
                                Row(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        painter = painterResource(Ms.AudioFile),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "${chapter.title}.mp3",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(
                        text = stringResource(R.string.action_share),
                        icon = Ms.Share,
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton(
                        text = stringResource(R.string.action_done),
                        icon = Ms.Check,
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
fun BatchSheet(
    book: Book,
    onDismiss: () -> Unit,
    onStart: (Int) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var scope by rememberSaveable { mutableStateOf("pending") }
    val pending = book.chapters.filter { it.status != ChapterStatus.Done }
    val failed = book.chapters.filter { it.status == ChapterStatus.Failed }
    val selected = when (scope) {
        "failed" -> failed
        "all" -> book.chapters
        else -> pending
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            SheetHeader(
                icon = Ms.AutoAwesome,
                title = stringResource(R.string.batch_title),
                subtitle = stringResource(R.string.batch_subtitle),
                onClose = onDismiss,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "pending" to stringResource(R.string.batch_scope_pending),
                    "failed" to stringResource(R.string.batch_scope_failed),
                    "all" to stringResource(R.string.batch_scope_all),
                ).forEach { (id, label) ->
                    FilterChip(
                        selected = scope == id,
                        onClick = { scope = id },
                        label = { Text(label) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "${selected.size} 章待生成",
                        style = MaterialTheme.typography.titleLargeEmphasized,
                    )
                    Text(
                        text = "预计 ${selected.size * 4} 分钟 · 预计消耗 ${selected.sumOf { it.wordCount } / 1000} 千字",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = stringResource(R.string.batch_start, selected.size),
                icon = Ms.PlaylistPlay,
                enabled = selected.isNotEmpty(),
                onClick = { onStart(selected.size) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun TasksSheet(player: PlayerState, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val task = FakeData.runningTask
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            SheetHeader(
                icon = Ms.GraphicEq,
                title = "任务",
                subtitle = "1 个进行中 · ${task.queued} 个排队",
                onClose = onDismiss,
            )
        }
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = task.bookTitle,
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            modifier = Modifier.weight(1f),
                        )
                        StatusChip(text = "生成中", tone = Tone.Info, icon = Ms.Sync)
                    }
                    Text(
                        text = "第 ${task.chapterIndex} / ${task.chapterCount} 章 · ${task.etaLabel}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    LinearWavyProgressIndicator(
                        progress = { task.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(
                            text = stringResource(R.string.task_stop),
                            icon = Ms.StopCircle,
                            onClick = onDismiss,
                        )
                        SecondaryButton(
                            text = stringResource(R.string.action_open),
                            icon = Ms.MenuBook,
                            onClick = onDismiss,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        SectionHeader(
            title = "队列",
            subtitle = "${task.queued} 个任务等待",
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            items(listOf("长夜将明 · 批量生成", "机械之心 · 重新生成")) { name ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(Ms.Pending),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(text = "排队中", tone = Tone.Neutral)
                }
            }
            item {
                SectionHeader(
                    title = "历史",
                    subtitle = "最近 3 天",
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            items(listOf("山海食话 · 全部章节", "明朝那些事儿 · 第 1-5 章")) { name ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(Ms.CheckCircle),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(text = "已完成", tone = Tone.Success)
                }
            }
        }
    }
}
