package com.estimatefaster.audioforge.uipreview.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.uipreview.R
import com.estimatefaster.audioforge.uipreview.data.FakeData
import com.estimatefaster.audioforge.uipreview.data.PlayerState
import com.estimatefaster.audioforge.uipreview.design.AppTopBar
import com.estimatefaster.audioforge.uipreview.design.ConfirmDialog
import com.estimatefaster.audioforge.uipreview.design.IconAction
import com.estimatefaster.audioforge.uipreview.design.ListRow
import com.estimatefaster.audioforge.uipreview.design.ProgressLine
import com.estimatefaster.audioforge.uipreview.design.RowDivider
import com.estimatefaster.audioforge.uipreview.design.SecondaryButton
import com.estimatefaster.audioforge.uipreview.design.SectionLabel
import com.estimatefaster.audioforge.uipreview.design.StatusChip
import com.estimatefaster.audioforge.uipreview.design.Tone
import com.estimatefaster.audioforge.uipreview.icons.Ms
import com.estimatefaster.audioforge.uipreview.nav.AppScaffold
import com.estimatefaster.audioforge.uipreview.nav.Dest
import com.estimatefaster.audioforge.uipreview.nav.Navigator
import kotlinx.coroutines.launch

@Composable
fun TasksScreen(navigator: Navigator, player: PlayerState) {
    val task = FakeData.runningTask
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showStop by remember { mutableStateOf(false) }

    AppScaffold(
        navigator = navigator,
        player = player,
        showNavBar = false,
        showMiniPlayer = true,
        snackbarHostState = snackbarHostState,
        topBar = {
            AppTopBar(
                title = "任务",
                subtitle = "1 个进行中 · ${task.queued} 个排队",
                scrollBehavior = scrollBehavior,
                onBack = { navigator.pop() },
                actions = {
                    IconAction(
                        icon = Ms.Refresh,
                        description = stringResource(R.string.action_refresh),
                        onClick = { scope.launch { snackbarHostState.showSnackbar("已刷新任务状态") } },
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item(key = "current") {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(
                            text = stringResource(R.string.status_generating),
                            tone = Tone.Info,
                            icon = Ms.Sync,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "第 ${task.chapterIndex} / ${task.chapterCount} 章",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(task.bookTitle, style = MaterialTheme.typography.titleLargeEmphasized)
                    Text(
                        text = "已运行 12:31 · ${stringResource(R.string.task_running_eta, task.etaLabel)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    ProgressLine(progress = task.percent / 100f)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${task.percent}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        SecondaryButton(
                            text = stringResource(R.string.task_stop),
                            icon = Ms.StopCircle,
                            onClick = { showStop = true },
                        )
                    }
                }
            }
            item(key = "queue-label") {
                SectionLabel(text = "队列", trailing = "${task.queued}")
            }
            item(key = "queue") {
                Column {
                    QueueRow(title = "长夜将明", subtitle = "批量生成 · 全部章节", onClick = { navigator.push(Dest.Project(3)) })
                    RowDivider()
                    QueueRow(title = "机械之心", subtitle = "重新生成 · 第 3 章", onClick = { navigator.push(Dest.Project(4)) })
                }
            }
            item(key = "history-label") {
                SectionLabel(text = "历史", trailing = "最近 3 天")
            }
            item(key = "history") {
                Column {
                    HistoryRow(title = "山海食话", subtitle = "完成 8 章 · 96 段", time = "今天 09:12")
                    RowDivider()
                    HistoryRow(title = "明朝那些事儿", subtitle = "完成 5 章 · 60 段", time = "昨天 21:40")
                    RowDivider()
                    HistoryRow(title = "机械之心", subtitle = "失败 1 章 · 已重试", time = "昨天 18:03")
                }
            }
        }
    }

    if (showStop) {
        ConfirmDialog(
            title = "停止生成",
            body = "会中断当前章节的语音合成，已完成的片段不会丢失。",
            confirmText = stringResource(R.string.task_stop),
            icon = Ms.StopCircle,
            danger = true,
            onConfirm = {
                showStop = false
                scope.launch { snackbarHostState.showSnackbar("预览构建：已停止当前任务") }
            },
            onDismiss = { showStop = false },
        )
    }
}

@Composable
private fun QueueRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListRow(
        title = title,
        subtitle = subtitle,
        icon = Ms.Pending,
        trailingText = stringResource(R.string.status_queued),
        onClick = onClick,
    )
}

@Composable
private fun HistoryRow(title: String, subtitle: String, time: String) {
    ListRow(
        title = title,
        subtitle = subtitle,
        icon = Ms.CheckCircle,
        trailingText = time,
        onClick = null,
        showChevron = false,
    )
}
