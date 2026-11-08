package com.estimatefaster.audioforge.feature.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.data.db.JobEntity
import com.estimatefaster.audioforge.ui.components.EmptyState
import com.estimatefaster.audioforge.ui.components.ErrorBanner
import com.estimatefaster.audioforge.ui.components.ErrorState
import com.estimatefaster.audioforge.ui.components.LoadingState
import com.estimatefaster.audioforge.ui.components.ProgressRing
import com.estimatefaster.audioforge.ui.components.SectionHeader
import com.estimatefaster.audioforge.ui.components.StatusKind
import com.estimatefaster.audioforge.ui.components.StatusPill
import com.estimatefaster.audioforge.ui.components.StatusTone
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * 任务页有状态入口（P4-08 / §12.4 S12）。
 *
 * 导航层只需注入 [TasksViewModel]、`onOpenProject` 与 [taskRunnerAvailable]；
 * 停止先弹确认框，确认后调用 `viewModel.stop(novelId)`（只写 stopRequested）。
 *
 * [taskRunnerAvailable] 与 P4-05 的 `batchRunnerAvailable` 同口径：批次执行/队列
 * （P3-04 BatchRunner）尚未装配时为 false，停止按钮禁用并显示说明，避免给用户
 * “点了没反应”的假象。任务页本身仍可只读展示库里已有的任务与历史。
 */
@Composable
fun TasksScreen(
    viewModel: TasksViewModel,
    modifier: Modifier = Modifier,
    onOpenProject: (Long) -> Unit = {},
    taskRunnerAvailable: Boolean = false,
    onStop: (Long) -> Unit = viewModel::stop,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TasksScreen(
        state = state,
        modifier = modifier,
        onRetry = viewModel::retry,
        onStop = onStop,
        onDismissActionError = viewModel::dismissActionError,
        onOpenProject = onOpenProject,
        taskRunnerAvailable = taskRunnerAvailable,
    )
}

/**
 * 无状态任务页：当前任务卡 + 队列 + 最近任务。
 *
 * 停止按钮点击后先弹二次确认；确认才回调 [onStop]（参数是 novelId，不是 jobId，
 * 与页面/导航的解耦口径一致）。整行点击队列/历史会通过 [onOpenProject] 打开项目，
 * 失败重试在项目页/章节页完成。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    state: TasksUiState,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    onStop: (Long) -> Unit = {},
    onDismissActionError: () -> Unit = {},
    onOpenProject: (Long) -> Unit = {},
    taskRunnerAvailable: Boolean = false,
) {
    var pendingStopNovelId by rememberSaveable { mutableStateOf<Long?>(null) }
    val actionError = state.actionError
    val loadError = state.loadError
    val stopTarget = pendingStopNovelId?.let { novelId -> state.taskFor(novelId) }

    // 任务在确认框打开期间自己结束了：自动收起，避免对已结束任务发停止请求。
    LaunchedEffect(pendingStopNovelId, state.current, state.queued) {
        val target = pendingStopNovelId
        if (target != null && state.taskFor(target) == null) {
            pendingStopNovelId = null
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.tasks_title),
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.tasks_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(
                message = stringResource(R.string.tasks_loading),
                modifier = Modifier.padding(padding),
            )

            loadError != null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                ErrorState(
                    message = stringResource(R.string.tasks_load_error, loadError),
                    onRetry = onRetry,
                )
            }

            state.isEmpty -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    title = stringResource(R.string.tasks_empty_title),
                    description = stringResource(R.string.tasks_empty_description),
                    actionText = stringResource(R.string.action_refresh),
                    onAction = onRetry,
                )
            }

            else -> TasksContent(
                state = state,
                contentPadding = padding,
                actionError = actionError,
                taskRunnerAvailable = taskRunnerAvailable,
                onDismissActionError = onDismissActionError,
                onRequestStop = { novelId -> pendingStopNovelId = novelId },
                onOpenProject = onOpenProject,
            )
        }
    }

    if (stopTarget != null) {
        StopConfirmDialog(
            novelTitle = stopTarget.novelTitle,
            onDismiss = { pendingStopNovelId = null },
            onConfirm = {
                val novelId = stopTarget.novelId
                pendingStopNovelId = null
                onStop(novelId)
            },
        )
    }
}

@Composable
private fun TasksContent(
    state: TasksUiState,
    contentPadding: PaddingValues,
    actionError: String?,
    taskRunnerAvailable: Boolean,
    onDismissActionError: () -> Unit,
    onRequestStop: (Long) -> Unit,
    onOpenProject: (Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (actionError != null) {
            item(key = "action-error") {
                ErrorBanner(
                    message = stringResource(R.string.action_failed, actionError),
                    onDismiss = onDismissActionError,
                )
            }
        }

        val current = state.current
        if (current != null) {
            item(key = "current-header") {
                SectionHeader(title = stringResource(R.string.tasks_current_title))
            }
            item(key = "current-card") {
                CurrentTaskCard(
                    card = current,
                    taskRunnerAvailable = taskRunnerAvailable,
                    onRequestStop = { onRequestStop(current.novelId) },
                )
            }
        } else {
            item(key = "no-running") { NoRunningCard() }
        }

        if (state.queued.isNotEmpty()) {
            item(key = "queue-header") {
                SectionHeader(
                    title = stringResource(R.string.tasks_queue_title),
                    subtitle = stringResource(R.string.tasks_queue_subtitle, state.queued.size),
                )
            }
            items(items = state.queued, key = { "queue-${it.jobId}" }) { card ->
                QueueTaskRow(card = card, onOpenProject = onOpenProject)
            }
        }

        if (state.recent.isNotEmpty()) {
            item(key = "history-header") {
                SectionHeader(
                    title = stringResource(R.string.tasks_history_title),
                    subtitle = stringResource(R.string.tasks_history_subtitle, state.recent.size),
                )
            }
            items(items = state.recent, key = { "history-${it.jobId}" }) { item ->
                HistoryRow(item = item, onOpenProject = onOpenProject)
            }
        }
    }
}

@Composable
private fun CurrentTaskCard(
    card: TaskCardUi,
    taskRunnerAvailable: Boolean,
    onRequestStop: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = card.novelTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            R.string.tasks_mode_label,
                            taskModeLabel(card.mode),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(kind = StatusKind.Task, status = card.status.wire)
                    if (card.isStopping) {
                        StatusPill(
                            text = stringResource(R.string.tasks_status_stopping),
                            tone = StatusTone.Warning,
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressRing(
                    progress = card.progress,
                    label = progressRingLabel(card),
                    diameter = 96.dp,
                    strokeWidth = 8.dp,
                    liveRegion = true,
                )
                Spacer(Modifier.width(18.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.tasks_progress_chapters,
                            card.stats.chaptersDone,
                            card.stats.chaptersTotal,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(
                            R.string.tasks_progress_segments,
                            card.stats.segDone,
                            card.stats.segTotal,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (card.stats.hasTtsCounts) {
                        Text(
                            text = stringResource(
                                R.string.tasks_progress_tts,
                                card.stats.ttsOk,
                                card.stats.ttsFailed,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (card.currentTitle.isNotBlank()) {
                Text(
                    text = stringResource(R.string.tasks_current_chapter, card.currentTitle),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = stringResource(
                    R.string.tasks_started_elapsed,
                    formatTimestamp(card.startedAt),
                    formatTaskDuration(card.elapsedMs)
                        ?: stringResource(R.string.tasks_value_unknown),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!card.isStopping) {
                Text(
                    text = etaLabel(card),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedButton(
                onClick = onRequestStop,
                enabled = taskRunnerAvailable && !card.stopRequested,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.tasks_action_stop))
            }
            if (!taskRunnerAvailable) {
                Text(
                    text = stringResource(R.string.tasks_control_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NoRunningCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Text(
            text = stringResource(R.string.tasks_no_running),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun QueueTaskRow(
    card: TaskCardUi,
    onOpenProject: (Long) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onOpenProject(card.novelId) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = card.novelTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.tasks_queue_item_meta,
                        taskModeLabel(card.mode),
                        card.percent,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (card.currentTitle.isNotBlank()) {
                    Text(
                        text = card.currentTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            StatusPill(kind = StatusKind.Task, status = card.status.wire)
        }
    }
}

@Composable
private fun HistoryRow(
    item: TaskHistoryUi,
    onOpenProject: (Long) -> Unit,
) {
    val duration = formatTaskDuration(item.durationMs)
    val errorSummary = item.errorSummary
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onOpenProject(item.novelId) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = item.novelTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                StatusPill(kind = StatusKind.Task, status = item.status.wire)
            }
            Text(
                text = stringResource(
                    R.string.tasks_history_meta,
                    taskModeLabel(item.mode),
                    duration ?: stringResource(R.string.tasks_value_unknown),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.tasks_history_finished,
                    formatTimestamp(item.finishedAt),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (errorSummary != null) {
                Text(
                    text = stringResource(R.string.tasks_history_error, errorSummary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StopConfirmDialog(
    novelTitle: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tasks_stop_confirm_title)) },
        text = { Text(stringResource(R.string.tasks_stop_confirm_message, novelTitle)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.tasks_action_stop_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun taskModeLabel(mode: TaskModeUi): String = stringResource(
    when (mode) {
        TaskModeUi.Full -> R.string.tasks_mode_full
        TaskModeUi.Parse -> R.string.tasks_mode_parse
        TaskModeUi.Render -> R.string.tasks_mode_render
        TaskModeUi.Unknown -> R.string.tasks_mode_unknown
    },
)

@Composable
private fun progressRingLabel(card: TaskCardUi): String = stringResource(
    when {
        card.stats.segTotal > 0 -> R.string.tasks_progress_ring_segments
        card.stats.chaptersTotal > 0 -> R.string.tasks_progress_ring_chapters
        else -> R.string.tasks_progress_ring_empty
    },
)

@Composable
private fun etaLabel(card: TaskCardUi): String {
    val eta = card.etaMs?.let { formatTaskDuration(it) }
    return if (eta != null) {
        stringResource(R.string.tasks_eta, eta)
    } else {
        stringResource(R.string.tasks_eta_unknown)
    }
}

@Composable
private fun formatTimestamp(epochMs: Long): String = remember(epochMs) {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}

// ---------------------------------------------------------------------------
// Preview：正常 / 空态 / 错误态。所有文案来自 strings.xml。
// ---------------------------------------------------------------------------

private const val PREVIEW_NOW: Long = 1_760_000_400_000L

private fun previewJob(
    id: Long,
    novelId: Long,
    status: String,
    startedAt: Long,
    mode: String = "full",
    finishedAt: Long? = null,
    statsJson: String = "{}",
    currentTitle: String = "",
    lastError: String? = null,
    stopRequested: Boolean = false,
): JobEntity = JobEntity(
    id = id,
    novelId = novelId,
    mode = mode,
    status = status,
    requestedIdsJson = "[]",
    currentTitle = currentTitle,
    startedAt = startedAt,
    finishedAt = finishedAt,
    lastError = lastError,
    statsJson = statsJson,
    stopRequested = stopRequested,
)

private fun previewTasksState(): TasksUiState = buildTasksUiState(
    snapshots = listOf(
        TaskJobSnapshot(
            novelId = 1L,
            novelTitle = "长夜将明",
            active = previewJob(
                id = 101L,
                novelId = 1L,
                status = "running",
                startedAt = PREVIEW_NOW - 12 * 60_000L,
                statsJson = """{"chaptersDone":2,"chaptersTotal":8,"segDone":42,"segTotal":150,"ttsOk":40,"ttsFailed":2}""",
                currentTitle = "第 3 章 雨夜来客",
            ),
        ),
        TaskJobSnapshot(
            novelId = 2L,
            novelTitle = "白夜行",
            active = previewJob(
                id = 102L,
                novelId = 2L,
                status = "queued",
                startedAt = PREVIEW_NOW - 60_000L,
                statsJson = """{"chaptersTotal":5}""",
            ),
        ),
        TaskJobSnapshot(
            novelId = 3L,
            novelTitle = "山海旅人",
            lastFinished = previewJob(
                id = 90L,
                novelId = 3L,
                status = "done",
                startedAt = PREVIEW_NOW - 3_600_000L,
                finishedAt = PREVIEW_NOW - 600_000L,
                statsJson = """{"chaptersDone":5,"chaptersTotal":5}""",
            ),
        ),
        TaskJobSnapshot(
            novelId = 4L,
            novelTitle = "雾都孤儿",
            lastFinished = previewJob(
                id = 89L,
                novelId = 4L,
                status = "error",
                startedAt = PREVIEW_NOW - 7_200_000L,
                finishedAt = PREVIEW_NOW - 7_000_000L,
                lastError = "Fish Audio 返回 429：请求过于频繁，请稍后重试。",
            ),
        ),
    ),
    now = PREVIEW_NOW,
)

private fun previewHistoryOnlyState(): TasksUiState = buildTasksUiState(
    snapshots = listOf(
        TaskJobSnapshot(
            novelId = 3L,
            novelTitle = "山海旅人",
            lastFinished = previewJob(
                id = 90L,
                novelId = 3L,
                status = "cancelled",
                startedAt = PREVIEW_NOW - 1_800_000L,
                finishedAt = PREVIEW_NOW - 1_200_000L,
                statsJson = """{"chaptersDone":1,"chaptersTotal":6,"segDone":12,"segTotal":180}""",
            ),
        ),
    ),
    now = PREVIEW_NOW,
)

@Preview(
    name = "TasksScreen Normal",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun TasksScreenNormalPreview() {
    AudioForgeTheme(dynamicColor = false) {
        TasksScreen(state = previewTasksState(), taskRunnerAvailable = true)
    }
}

@Preview(
    name = "TasksScreen History Only",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun TasksScreenHistoryOnlyPreview() {
    AudioForgeTheme(dynamicColor = false) {
        TasksScreen(state = previewHistoryOnlyState(), taskRunnerAvailable = true)
    }
}

@Preview(
    name = "TasksScreen Empty",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun TasksScreenEmptyPreview() {
    AudioForgeTheme(dynamicColor = false) {
        TasksScreen(state = TasksUiState(loading = false))
    }
}

@Preview(
    name = "TasksScreen Error",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun TasksScreenErrorPreview() {
    AudioForgeTheme(dynamicColor = false) {
        TasksScreen(
            state = TasksUiState(loading = false, loadError = "database is locked"),
        )
    }
}
