@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.estimatefaster.audioforge.feature.tasks

import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.data.db.JobEntity
import com.estimatefaster.audioforge.ui.components.ErrorBanner
import com.estimatefaster.audioforge.ui.components.ExpressiveCard
import com.estimatefaster.audioforge.ui.components.ScreenState
import com.estimatefaster.audioforge.ui.components.SectionHeader
import com.estimatefaster.audioforge.ui.components.StatusKind
import com.estimatefaster.audioforge.ui.components.StatusPill
import com.estimatefaster.audioforge.ui.components.StatusTone
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// 任务页有状态入口（P4-08 / §12.4 S12）。
//
// 导航层只需注入 [TasksViewModel]、`onOpenProject` 与 [taskRunnerAvailable]；
// 停止先弹确认框，确认后调用 `viewModel.stop(novelId)`（只写 stopRequested）。
//
// [taskRunnerAvailable] 与 P4-05 的 `batchRunnerAvailable` 同口径：批次执行/队列
// （P3-04 BatchRunner）尚未装配时为 false，停止按钮禁用并显示说明，避免给用户
// “点了没反应”的假象。任务页本身仍可只读展示库里已有的任务与历史。
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

// 无状态任务页：当前任务 hero + 队列 + 最近任务。
//
// 停止按钮点击后先弹二次确认；确认才回调 [onStop]（参数是 novelId，不是 jobId，
// 与页面/导航的解耦口径一致）。整行点击队列/历史会通过 [onOpenProject] 打开项目，
// 失败重试在项目页/章节页完成。
//
// Expressive 改版只调整呈现：hero 用 primaryContainer 大圆角承载书名、状态、进度、
// ETA 与停止操作；队列/历史统一为 tonal 卡片；加载/错误/空态统一走 [ScreenState]。
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

    val screenState = when {
        state.loading -> ScreenState.Loading(stringResource(R.string.tasks_loading))
        loadError != null -> ScreenState.Error(
            message = stringResource(R.string.tasks_load_error, loadError),
            onRetry = onRetry,
        )

        state.isEmpty -> ScreenState.Empty(
            title = stringResource(R.string.tasks_empty_title),
            description = stringResource(R.string.tasks_empty_description),
            actionText = stringResource(R.string.action_refresh),
            onAction = onRetry,
        )

        else -> ScreenState.Content
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.tasks_title),
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        maxLines = 1,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Text(
                text = stringResource(R.string.tasks_subtitle),
                modifier = Modifier.padding(
                    start = MaterialTheme.spacing.lg,
                    top = MaterialTheme.spacing.md,
                    end = MaterialTheme.spacing.lg,
                    bottom = MaterialTheme.spacing.xs,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                ScreenState(
                    state = screenState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    TasksContent(
                        state = state,
                        actionError = actionError,
                        taskRunnerAvailable = taskRunnerAvailable,
                        onDismissActionError = onDismissActionError,
                        onRequestStop = { novelId -> pendingStopNovelId = novelId },
                        onOpenProject = onOpenProject,
                    )
                }
            }
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
    actionError: String?,
    taskRunnerAvailable: Boolean,
    onDismissActionError: () -> Unit,
    onRequestStop: (Long) -> Unit,
    onOpenProject: (Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = MaterialTheme.spacing.lg,
            vertical = MaterialTheme.spacing.md,
        ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
    ) {
        if (actionError != null) {
            item(key = "action-error") {
                ErrorBanner(
                    message = stringResource(R.string.action_failed, actionError),
                    onDismiss = onDismissActionError,
                )
            }
        }

        item(key = "current-header") {
            SectionHeader(title = stringResource(R.string.tasks_current_title))
        }

        val current = state.current
        if (current != null) {
            item(key = "current-card") {
                CurrentTaskHero(
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
                QueueTaskCard(card = card, onOpenProject = onOpenProject)
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
                HistoryCard(item = item, onOpenProject = onOpenProject)
            }
        }
    }
}

@Composable
private fun CurrentTaskHero(
    card: TaskCardUi,
    taskRunnerAvailable: Boolean,
    onRequestStop: () -> Unit,
) {
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    val secondaryContentColor = contentColor.copy(alpha = 0.8f)
    val progressDescription = stringResource(
        R.string.ux_tasks_progress_content_description,
        card.percent,
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraExtraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = contentColor,
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        ) {
            Text(
                text = card.novelTitle,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmallEmphasized,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.tasks_mode_label, taskModeLabel(card.mode)),
                style = MaterialTheme.typography.labelLarge,
                color = secondaryContentColor,
            )

            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(top = MaterialTheme.spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(kind = StatusKind.Task, status = card.status.wire)
                if (card.isStopping) {
                    StatusPill(
                        text = stringResource(R.string.tasks_status_stopping),
                        tone = StatusTone.Warning,
                    )
                }
            }

            HorizontalDivider(color = contentColor.copy(alpha = 0.16f))

            Text(
                text = stringResource(R.string.ux_tasks_progress_label),
                style = MaterialTheme.typography.labelLargeEmphasized,
                color = secondaryContentColor,
            )
            Text(
                text = stringResource(R.string.progress_percent, card.percent),
                modifier = Modifier.clearAndSetSemantics { },
                style = MaterialTheme.typography.displaySmallEmphasized,
            )
            LinearWavyProgressIndicator(
                progress = { card.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = progressDescription },
                color = MaterialTheme.colorScheme.primary,
                trackColor = contentColor.copy(alpha = 0.18f),
            )

            Text(
                text = etaLabel(card),
                style = MaterialTheme.typography.titleMediumEmphasized,
            )

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
                    R.string.tasks_progress_chapters,
                    card.stats.chaptersDone,
                    card.stats.chaptersTotal,
                ),
                style = MaterialTheme.typography.titleSmallEmphasized,
            )
            Text(
                text = stringResource(
                    R.string.tasks_progress_segments,
                    card.stats.segDone,
                    card.stats.segTotal,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = secondaryContentColor,
            )
            if (card.stats.hasTtsCounts) {
                Text(
                    text = stringResource(
                        R.string.tasks_progress_tts,
                        card.stats.ttsOk,
                        card.stats.ttsFailed,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryContentColor,
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
                color = secondaryContentColor,
            )

            if (taskRunnerAvailable && !card.isStopping) {
                Text(
                    text = stringResource(R.string.ux_tasks_stop_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryContentColor,
                )
            }

            DangerTonalButton(
                text = stringResource(R.string.tasks_action_stop),
                onClick = onRequestStop,
                enabled = taskRunnerAvailable && !card.stopRequested,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!taskRunnerAvailable) {
                Text(
                    text = stringResource(R.string.tasks_control_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryContentColor,
                )
            }
        }
    }
}

// 停止是危险动作：用 errorContainer 的 tonal 按钮保持醒目，但不与主行动争色。
@Composable
private fun DangerTonalButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        contentPadding = PaddingValues(
            horizontal = MaterialTheme.spacing.lg,
            vertical = MaterialTheme.spacing.sm,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLargeEmphasized,
        )
    }
}

@Composable
private fun NoRunningCard() {
    ExpressiveCard(contentPadding = PaddingValues(MaterialTheme.spacing.lg)) {
        Text(
            text = stringResource(R.string.ux_tasks_no_running_title),
            style = MaterialTheme.typography.titleMediumEmphasized,
        )
        Text(
            text = stringResource(R.string.tasks_no_running),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QueueTaskCard(
    card: TaskCardUi,
    onOpenProject: (Long) -> Unit,
) {
    ExpressiveCard(
        onClick = { onOpenProject(card.novelId) },
        contentPadding = PaddingValues(MaterialTheme.spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs),
            ) {
                Text(
                    text = card.novelTitle,
                    style = MaterialTheme.typography.titleMediumEmphasized,
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
            Spacer(Modifier.width(MaterialTheme.spacing.sm))
            StatusPill(kind = StatusKind.Task, status = card.status.wire)
        }
    }
}

@Composable
private fun HistoryCard(
    item: TaskHistoryUi,
    onOpenProject: (Long) -> Unit,
) {
    val duration = formatTaskDuration(item.durationMs)
    val errorSummary = item.errorSummary

    ExpressiveCard(
        onClick = { onOpenProject(item.novelId) },
        contentPadding = PaddingValues(MaterialTheme.spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = item.novelTitle,
                style = MaterialTheme.typography.titleMediumEmphasized,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(MaterialTheme.spacing.sm))
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
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = stringResource(R.string.tasks_action_stop_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
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
// Preview：正常 / 空态 / 错误态 / 深色 200%。所有文案来自 strings.xml。
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

@Preview(name = "TasksScreen Normal", showBackground = true)
@Composable
private fun TasksScreenNormalPreview() {
    AudioForgeTheme(dynamicColor = false) {
        TasksScreen(state = previewTasksState(), taskRunnerAvailable = true)
    }
}

@Preview(name = "TasksScreen History Only", showBackground = true)
@Composable
private fun TasksScreenHistoryOnlyPreview() {
    AudioForgeTheme(dynamicColor = false) {
        TasksScreen(state = previewHistoryOnlyState(), taskRunnerAvailable = true)
    }
}

@Preview(name = "TasksScreen Empty", showBackground = true)
@Composable
private fun TasksScreenEmptyPreview() {
    AudioForgeTheme(dynamicColor = false) {
        TasksScreen(state = TasksUiState(loading = false))
    }
}

@Preview(name = "TasksScreen Error", showBackground = true)
@Composable
private fun TasksScreenErrorPreview() {
    AudioForgeTheme(dynamicColor = false) {
        TasksScreen(
            state = TasksUiState(loading = false, loadError = "database is locked"),
        )
    }
}

@Preview(
    name = "TasksScreen Dark 200%",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun TasksScreenDarkPreview() {
    AudioForgeTheme(darkTheme = true, dynamicColor = false) {
        TasksScreen(state = previewTasksState(), taskRunnerAvailable = false)
    }
}
