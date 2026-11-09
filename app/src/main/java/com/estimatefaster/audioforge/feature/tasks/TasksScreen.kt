@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.estimatefaster.audioforge.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.data.db.JobEntity
import com.estimatefaster.audioforge.ui.components.StatusKind
import com.estimatefaster.audioforge.ui.components.StatusPill
import com.estimatefaster.audioforge.ui.forge.ForgeCard
import com.estimatefaster.audioforge.ui.forge.ForgeEmpty
import com.estimatefaster.audioforge.ui.forge.ForgeError
import com.estimatefaster.audioforge.ui.forge.ForgeIcon
import com.estimatefaster.audioforge.ui.forge.ForgeIconAction
import com.estimatefaster.audioforge.ui.forge.ForgeIcons
import com.estimatefaster.audioforge.ui.forge.ForgeListRow
import com.estimatefaster.audioforge.ui.forge.ForgeLoading
import com.estimatefaster.audioforge.ui.forge.ForgeSecondaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSectionHeader
import com.estimatefaster.audioforge.ui.forge.ForgeTopBar
import com.estimatefaster.audioforge.ui.forge.ForgeWavyProgress
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * 任务页有状态入口（P4-08 / §12.4 S12）。
 *
 * 公开签名保持不变：导航层只注入 [TasksViewModel]、`onOpenProject` 与
 * [taskRunnerAvailable]；停止先二次确认，确认后回调 `onStop(novelId)`。
 *
 * 布局按 `docs/prototype/forge-framework.html` 的 Tasks 重写：
 * TopBar（刷新 icon-only）→ 当前任务 hero（大字百分比 + Wavy 进度 + danger 停止）
 * → 指标条 → 队列 → 历史任务。所有操作都带 Lucide 图标。
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
 * 无状态任务页。
 *
 * 状态机、校验与回调口径与旧版一致：
 * - 停止按钮点击后先弹二次确认；确认才回调 [onStop]（参数是 novelId）；
 * - 确认框打开期间任务自己结束会自动收起；
 * - [taskRunnerAvailable] 为 false 时停止按钮禁用并显示说明。
 *
 * 视觉全部走 `ui/forge/`：ForgeTopBar / ForgeCard / ForgeListRow /
 * ForgeSecondaryAction / ForgeIconAction / ForgeEmpty / ForgeError / ForgeLoading。
 */
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
    val loadError = state.loadError
    val actionError = state.actionError
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
            ForgeTopBar(
                title = stringResource(R.string.tasks_title),
                subtitle = stringResource(R.string.ux_tasks_subtitle),
                actions = {
                    ForgeIconAction(
                        icon = ForgeIcons.Refresh,
                        contentDescription = stringResource(R.string.ux_tasks_refresh),
                        onClick = onRetry,
                    )
                },
            )
        },
    ) { padding ->
        val contentModifier = Modifier.padding(padding)
        when {
            state.loading -> ForgeLoading(
                label = stringResource(R.string.tasks_loading),
                modifier = contentModifier,
            )

            loadError != null -> ForgeError(
                message = stringResource(R.string.tasks_load_error, loadError),
                modifier = contentModifier,
                onRetry = onRetry,
                retryLabel = stringResource(R.string.action_retry),
            )

            state.isEmpty -> ForgeEmpty(
                icon = ForgeIcons.Inbox,
                title = stringResource(R.string.ux_tasks_empty_title),
                description = stringResource(R.string.ux_tasks_empty_desc),
                modifier = contentModifier,
                actionIcon = ForgeIcons.Refresh,
                actionLabel = stringResource(R.string.action_refresh),
                onAction = onRetry,
            )

            else -> TasksContent(
                state = state,
                contentPadding = padding,
                actionError = actionError,
                taskRunnerAvailable = taskRunnerAvailable,
                onDismissActionError = onDismissActionError,
                onRequestStop = { novelId -> pendingStopNovelId = novelId },
                onOpenProject = onOpenProject,
                onRetry = onRetry,
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
    onRetry: () -> Unit,
) {
    val current = state.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(
            horizontal = MaterialTheme.spacing.lg,
            vertical = MaterialTheme.spacing.lg,
        ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
    ) {
        if (actionError != null) {
            item(key = "action-error") {
                ActionErrorCard(message = actionError, onDismiss = onDismissActionError)
            }
        }

        item(key = "current") {
            CurrentTaskSection(
                card = current,
                taskRunnerAvailable = taskRunnerAvailable,
                onRequestStop = onRequestStop,
            )
        }

        if (current != null) {
            item(key = "current-meta") {
                TaskMetaStrip(card = current)
            }
        }

        if (state.queued.isNotEmpty()) {
            item(key = "queue-header") {
                ForgeSectionHeader(
                    title = stringResource(R.string.ux_tasks_queue_title),
                    subtitle = stringResource(R.string.ux_tasks_queue_count, state.queued.size),
                )
            }
            items(items = state.queued, key = { "queue-${it.jobId}" }) { card ->
                ForgeListRow(
                    icon = if (card.status == TaskStatusUi.Running) {
                        ForgeIcons.Loader
                    } else {
                        ForgeIcons.Clock
                    },
                    title = card.novelTitle,
                    subtitle = stringResource(
                        R.string.tasks_queue_item_meta,
                        taskModeLabel(card.mode),
                        card.percent,
                    ),
                    trailingIcon = ForgeIcons.ChevronRight,
                    trailingDescription = stringResource(
                        R.string.ux_tasks_open_project,
                        card.novelTitle,
                    ),
                    onTrailingClick = { onOpenProject(card.novelId) },
                )
            }
        }

        if (state.recent.isNotEmpty()) {
            item(key = "history-header") {
                ForgeSectionHeader(
                    title = stringResource(R.string.ux_tasks_history_title),
                    subtitle = stringResource(R.string.ux_tasks_history_count, state.recent.size),
                    actionIcon = ForgeIcons.Refresh,
                    actionDescription = stringResource(R.string.ux_tasks_refresh_list),
                    onAction = onRetry,
                )
            }
            items(items = state.recent, key = { "history-${it.jobId}" }) { item ->
                HistoryCard(item = item, onOpenProject = onOpenProject)
            }
        }
    }
}

@Composable
private fun CurrentTaskSection(
    card: TaskCardUi?,
    taskRunnerAvailable: Boolean,
    onRequestStop: (Long) -> Unit,
) {
    if (card == null) {
        NoRunningCard()
        return
    }
    CurrentTaskHero(
        card = card,
        taskRunnerAvailable = taskRunnerAvailable,
        onRequestStop = { onRequestStop(card.novelId) },
    )
}

@Composable
private fun CurrentTaskHero(
    card: TaskCardUi,
    taskRunnerAvailable: Boolean,
    onRequestStop: () -> Unit,
) {
    val stopping = card.isStopping
    val chaptersTotal = card.stats.chaptersTotal
    val kicker = when {
        stopping -> stringResource(R.string.ux_tasks_kicker_stopping)
        chaptersTotal > 0 -> stringResource(
            R.string.ux_tasks_kicker_running,
            (card.stats.chaptersDone + 1).coerceAtMost(chaptersTotal),
            chaptersTotal,
        )

        else -> stringResource(R.string.ux_tasks_kicker_running_plain)
    }
    val progressDescription = stringResource(R.string.ux_tasks_progress_desc)

    ForgeCard(
        containerColor = if (stopping) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            ForgeIcon(
                icon = if (stopping) ForgeIcons.ErrorCircle else ForgeIcons.Loader,
                contentDescription = null,
                tint = if (stopping) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Text(
                text = kicker,
                style = MaterialTheme.typography.labelMediumEmphasized,
                color = if (stopping) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }

        Text(
            text = stringResource(
                R.string.ux_tasks_title_mode,
                card.novelTitle,
                taskModeLabel(card.mode),
            ),
            style = MaterialTheme.typography.headlineSmallEmphasized,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(
                R.string.tasks_started_elapsed,
                formatTimestamp(card.startedAt),
                formatTaskDuration(card.elapsedMs)
                    ?: stringResource(R.string.tasks_value_unknown),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (stopping) {
            Text(
                text = if (chaptersTotal > 0) {
                    stringResource(
                        R.string.ux_tasks_stopping_body,
                        card.stats.chaptersDone,
                        chaptersTotal,
                    )
                } else {
                    stringResource(R.string.ux_tasks_stopping_body_plain)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = stringResource(R.string.progress_percent, card.percent),
                    style = MaterialTheme.typography.displaySmallEmphasized,
                )
                Spacer(Modifier.weight(1f))
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs),
                ) {
                    if (card.currentTitle.isNotBlank()) {
                        Text(
                            text = stringResource(
                                R.string.tasks_current_chapter,
                                card.currentTitle,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = etaLabel(card),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        ForgeWavyProgress(
            progress = card.progress,
            modifier = Modifier.semantics {
                contentDescription = progressDescription
                progressBarRangeInfo = ProgressBarRangeInfo(card.progress, 0f..1f)
            },
        )

        ForgeSecondaryAction(
            icon = ForgeIcons.ErrorCircle,
            label = if (stopping) {
                stringResource(R.string.tasks_status_stopping)
            } else {
                stringResource(R.string.ux_tasks_stop)
            },
            onClick = onRequestStop,
            modifier = Modifier.fillMaxWidth(),
            enabled = taskRunnerAvailable && !card.stopRequested,
            danger = true,
        )

        if (!taskRunnerAvailable) {
            Text(
                text = stringResource(R.string.tasks_control_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TaskMetaStrip(card: TaskCardUi) {
    val duration = formatTaskDuration(card.elapsedMs)
        ?: stringResource(R.string.tasks_value_unknown)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
    ) {
        MetaLine(
            items = listOf(
                ForgeIcons.Users to stringResource(
                    R.string.tasks_progress_chapters,
                    card.stats.chaptersDone,
                    card.stats.chaptersTotal,
                ),
                ForgeIcons.FileText to stringResource(
                    R.string.tasks_progress_segments,
                    card.stats.segDone,
                    card.stats.segTotal,
                ),
            ),
        )
        val elapsed = ForgeIcons.Clock to stringResource(
            R.string.ux_tasks_elapsed,
            duration,
        )
        val tts = if (card.stats.hasTtsCounts) {
            ForgeIcons.AudioLines to stringResource(
                R.string.tasks_progress_tts,
                card.stats.ttsOk,
                card.stats.ttsFailed,
            )
        } else {
            null
        }
        MetaLine(items = listOfNotNull(elapsed, tts))
    }
}

@Composable
private fun MetaLine(items: List<Pair<Int, String>>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
    ) {
        items.forEach { (icon, label) ->
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
            ) {
                ForgeIcon(
                    icon = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NoRunningCard() {
    ForgeCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        ) {
            ForgeIcon(
                icon = ForgeIcons.Inbox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.tasks_no_running),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActionErrorCard(message: String, onDismiss: () -> Unit) {
    ForgeCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        ) {
            ForgeIcon(
                icon = ForgeIcons.AlertCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(R.string.ux_tasks_action_failed, message),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            ForgeIconAction(
                icon = ForgeIcons.Close,
                contentDescription = stringResource(R.string.action_dismiss),
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun HistoryCard(item: TaskHistoryUi, onOpenProject: (Long) -> Unit) {
    val status = item.status
    val container = when (status) {
        TaskStatusUi.Done -> MaterialTheme.colorScheme.tertiaryContainer
        TaskStatusUi.Error -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when (status) {
        TaskStatusUi.Done -> MaterialTheme.colorScheme.onTertiaryContainer
        TaskStatusUi.Error -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusIcon = when (status) {
        TaskStatusUi.Done -> ForgeIcons.CheckCircle
        TaskStatusUi.Cancelled -> ForgeIcons.Close
        else -> ForgeIcons.ErrorCircle
    }
    val duration = formatTaskDuration(item.durationMs)
        ?: stringResource(R.string.tasks_value_unknown)
    val errorSummary = item.errorSummary

    ForgeCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.large,
                color = container,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    ForgeIcon(
                        icon = statusIcon,
                        contentDescription = null,
                        tint = content,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs),
            ) {
                Text(
                    text = stringResource(
                        R.string.ux_tasks_title_mode,
                        item.novelTitle,
                        taskModeLabel(item.mode),
                    ),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = historyResultLabel(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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
            ForgeIconAction(
                icon = ForgeIcons.ChevronRight,
                contentDescription = stringResource(
                    R.string.ux_tasks_open_project,
                    item.novelTitle,
                ),
                onClick = { onOpenProject(item.novelId) },
                container = false,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatusPill(kind = StatusKind.Task, status = status.wire)
            Text(
                text = stringResource(R.string.ux_tasks_duration, duration),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        icon = {
            ForgeIcon(
                icon = ForgeIcons.ErrorCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text(
                text = stringResource(R.string.tasks_stop_confirm_title),
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.tasks_stop_confirm_message, novelTitle),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            ForgeSecondaryAction(
                icon = ForgeIcons.ErrorCircle,
                label = stringResource(R.string.tasks_action_stop_confirm),
                onClick = onConfirm,
                danger = true,
            )
        },
        dismissButton = {
            ForgeSecondaryAction(
                icon = ForgeIcons.Close,
                label = stringResource(R.string.action_cancel),
                onClick = onDismiss,
            )
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
private fun historyResultLabel(item: TaskHistoryUi): String = when (item.status) {
    TaskStatusUi.Done -> when {
        item.stats.chaptersDone <= 0 -> stringResource(R.string.tasks_value_unknown)
        item.stats.segDone > 0 -> stringResource(
            R.string.ux_tasks_result_done,
            item.stats.chaptersDone,
            item.stats.segDone,
        )

        else -> stringResource(
            R.string.tasks_progress_chapters,
            item.stats.chaptersDone,
            item.stats.chaptersTotal,
        )
    }

    TaskStatusUi.Cancelled -> stringResource(
        R.string.ux_tasks_result_cancelled,
        item.stats.chaptersDone,
        item.stats.chaptersTotal,
    )

    TaskStatusUi.Error -> stringResource(R.string.ux_tasks_result_error)
    else -> stringResource(R.string.tasks_value_unknown)
}

@Composable
private fun etaLabel(card: TaskCardUi): String {
    val eta = card.etaMs?.let { formatTaskDuration(it) }
    return if (eta != null) {
        stringResource(R.string.ux_tasks_eta, eta)
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
// Preview：正常 / 仅历史 / 空态 / 错误态。文案来自 strings.xml 与本页新文案文件。
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
                statsJson = """{"chaptersDone":5,"chaptersTotal":5,"segDone":120,"segTotal":120}""",
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
