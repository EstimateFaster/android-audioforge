package com.estimatefaster.audioforge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.estimatefaster.audioforge.BuildConfig
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.core.audio.FfmpegProbeState
import com.estimatefaster.audioforge.data.db.RoomProbeState
import com.estimatefaster.audioforge.ui.forge.ForgeCard
import com.estimatefaster.audioforge.ui.forge.ForgeIcon
import com.estimatefaster.audioforge.ui.forge.ForgeIcons
import com.estimatefaster.audioforge.ui.forge.ForgePrimaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSecondaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSectionHeader
import com.estimatefaster.audioforge.ui.forge.ForgeTopBar
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing
import com.estimatefaster.audioforge.work.ProbeWorkSnapshot
import com.estimatefaster.audioforge.work.ProbeWorkStatus

/**
 * 首页（Forge Framework v1）。
 *
 * 版式照设计稿的 tab 页面：`ForgeTopBar` + 内容卡片；诊断区沿用 Phase 0 的
 * Room / FFmpeg / WorkManager 冒烟卡，但全部换成 [ForgeCard] 与
 * [ForgePrimaryAction] / [ForgeSecondaryAction]，操作一律 Lucide 图标 + 文案。
 *
 * 回调与既有文案 key 全部保留：`onRunRoomProbe`、`onRunFfmpegProbe`、
 * `onStartProbeWorker`、`onStopProbeWorker` 及 `diag_*` / `home_*` / `checklist_*` 等。
 */
@Composable
fun HomeScreen(
    roomProbeState: RoomProbeState = RoomProbeState.Idle,
    onRunRoomProbe: () -> Unit = {},
    ffmpegProbeState: FfmpegProbeState = FfmpegProbeState.Idle,
    onRunFfmpegProbe: () -> Unit = {},
    probeWork: ProbeWorkSnapshot = ProbeWorkSnapshot(),
    onStartProbeWorker: () -> Unit = {},
    onStopProbeWorker: () -> Unit = {},
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            ForgeTopBar(
                title = stringResource(R.string.home_title),
                subtitle = stringResource(R.string.home_subtitle),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                horizontal = MaterialTheme.spacing.lg,
                vertical = MaterialTheme.spacing.md,
            ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        ) {
            item { HomeHeroCard() }
            item { ChecklistCard() }
            if (BuildConfig.DEBUG) {
                item {
                    ForgeSectionHeader(
                        title = stringResource(R.string.ux_home_diagnostics_title),
                        subtitle = stringResource(R.string.ux_home_diagnostics_subtitle),
                    )
                }
                item { RoomProbeCard(roomProbeState, onRunRoomProbe) }
                item { FfmpegProbeCard(ffmpegProbeState, onRunFfmpegProbe) }
                item { ProbeWorkerCard(probeWork, onStartProbeWorker, onStopProbeWorker) }
            }
            item { NextCard(showDetails) { showDetails = !showDetails } }
            item {
                Text(
                    text = stringResource(R.string.footer),
                    modifier = Modifier.padding(vertical = MaterialTheme.spacing.md),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HomeHeroCard() {
    ForgeCard(contentPadding = PaddingValues(MaterialTheme.spacing.xl)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    ForgeIcon(
                        icon = ForgeIcons.Sparkles,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Text(
                text = stringResource(R.string.phase0_badge),
                style = MaterialTheme.typography.labelLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.home_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChecklistCard() {
    ForgeCard {
        Text(
            text = stringResource(R.string.checklist_title),
            style = MaterialTheme.typography.titleMediumEmphasized,
        )
        listOf(
            R.string.checklist_1,
            R.string.checklist_2,
            R.string.checklist_3,
            R.string.checklist_4,
        ).forEachIndexed { index, textRes ->
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            ) {
                ForgeIcon(
                    icon = ForgeIcons.Check,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(textRes),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun RoomProbeCard(state: RoomProbeState, onReRun: () -> Unit) {
    val failed = state is RoomProbeState.Failed
    val running = state is RoomProbeState.Running
    val status = when {
        running -> ProbeStatus(ForgeIcons.Loader, R.string.ux_home_status_running, ProbeTone.Running)
        failed -> ProbeStatus(ForgeIcons.AlertCircle, R.string.ux_home_status_failed, ProbeTone.Failed)
        state is RoomProbeState.Passed ->
            ProbeStatus(ForgeIcons.CheckCircle, R.string.ux_home_status_passed, ProbeTone.Passed)
        else -> ProbeStatus(ForgeIcons.Clock, R.string.ux_home_status_idle, ProbeTone.Idle)
    }
    val body = when (state) {
        RoomProbeState.Idle -> stringResource(R.string.diag_room_idle)
        RoomProbeState.Running -> stringResource(R.string.diag_room_running)
        is RoomProbeState.Passed -> stringResource(
            R.string.diag_room_passed,
            state.counter,
            state.elapsedMs,
        )
        is RoomProbeState.Failed -> stringResource(R.string.diag_room_failed, state.message)
    }

    ForgeCard {
        DiagnosticCardHeader(
            icon = ForgeIcons.Database,
            title = stringResource(R.string.diag_title),
            status = status,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = if (failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        ForgePrimaryAction(
            icon = ForgeIcons.Refresh,
            label = stringResource(R.string.diag_room_action),
            onClick = onReRun,
            modifier = Modifier.fillMaxWidth(),
            enabled = !running,
            loading = running,
        )
    }
}

@Composable
private fun FfmpegProbeCard(state: FfmpegProbeState, onRun: () -> Unit) {
    val failed = state is FfmpegProbeState.Failed
    val running = state is FfmpegProbeState.Running
    val status = when {
        running -> ProbeStatus(ForgeIcons.Loader, R.string.ux_home_status_running, ProbeTone.Running)
        failed -> ProbeStatus(ForgeIcons.AlertCircle, R.string.ux_home_status_failed, ProbeTone.Failed)
        state is FfmpegProbeState.Passed ->
            ProbeStatus(ForgeIcons.CheckCircle, R.string.ux_home_status_passed, ProbeTone.Passed)
        else -> ProbeStatus(ForgeIcons.Clock, R.string.ux_home_status_idle, ProbeTone.Idle)
    }
    val body = when (state) {
        FfmpegProbeState.Idle -> stringResource(R.string.diag_ffmpeg_idle)
        FfmpegProbeState.Running -> stringResource(R.string.diag_ffmpeg_running)
        is FfmpegProbeState.Passed -> stringResource(
            R.string.diag_ffmpeg_passed,
            state.version,
            state.silenceBytes,
            state.joinedDurationMs,
        )
        is FfmpegProbeState.Failed -> stringResource(R.string.diag_ffmpeg_failed, state.message)
    }

    ForgeCard {
        DiagnosticCardHeader(
            icon = ForgeIcons.AudioLines,
            title = stringResource(R.string.diag_ffmpeg_title),
            status = status,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = if (failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        ForgePrimaryAction(
            icon = ForgeIcons.Wand,
            label = stringResource(R.string.diag_ffmpeg_action),
            onClick = onRun,
            modifier = Modifier.fillMaxWidth(),
            enabled = !running,
            loading = running,
        )
    }
}

@Composable
private fun ProbeWorkerCard(
    snapshot: ProbeWorkSnapshot,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        // 无论是否授权都继续启动；未授权时只是通知不可见（执行计划 §11.2）。
        onStart()
    }
    val active = snapshot.status == ProbeWorkStatus.RUNNING ||
        snapshot.status == ProbeWorkStatus.ENQUEUED

    ForgeCard {
        DiagnosticCardHeader(
            icon = ForgeIcons.Timer,
            title = stringResource(R.string.diag_work_title),
            status = snapshot.status.probeStatus(),
        )
        Text(
            text = stringResource(
                R.string.diag_work_status,
                stringResource(snapshot.status.labelRes()),
                snapshot.progress,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ForgePrimaryAction(
                icon = ForgeIcons.Play,
                label = stringResource(R.string.diag_work_start),
                onClick = {
                    val needsPermission =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                    if (needsPermission) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onStart()
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !active,
                loading = active,
            )
            ForgeSecondaryAction(
                icon = ForgeIcons.ErrorCircle,
                label = stringResource(R.string.diag_work_stop),
                onClick = onStop,
                modifier = Modifier.weight(1f),
                danger = true,
            )
        }
    }
}

@Composable
private fun NextCard(showDetails: Boolean, onToggle: () -> Unit) {
    ForgeCard {
        Text(
            text = stringResource(R.string.next_title),
            style = MaterialTheme.typography.titleMediumEmphasized,
        )
        Text(
            text = stringResource(R.string.next_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ForgeSecondaryAction(
            icon = if (showDetails) ForgeIcons.Close else ForgeIcons.ChevronDown,
            label = stringResource(
                if (showDetails) R.string.ux_home_action_details_collapse else R.string.next_action,
            ),
            onClick = onToggle,
        )
        AnimatedVisibility(visible = showDetails) {
            Column {
                Spacer(Modifier.height(MaterialTheme.spacing.md))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(MaterialTheme.spacing.md))
                Text(
                    text = stringResource(R.string.phase0_details),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticCardHeader(
    @DrawableRes icon: Int,
    title: String,
    status: ProbeStatus,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                ForgeIcon(
                    icon = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmallEmphasized,
        )
        DiagnosticStatusBadge(status)
    }
}

@Composable
private fun DiagnosticStatusBadge(status: ProbeStatus) {
    val containerColor = when (status.tone) {
        ProbeTone.Idle -> MaterialTheme.colorScheme.surfaceContainerHigh
        ProbeTone.Running -> MaterialTheme.colorScheme.secondaryContainer
        ProbeTone.Passed -> MaterialTheme.colorScheme.primaryContainer
        ProbeTone.Failed -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (status.tone) {
        ProbeTone.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
        ProbeTone.Running -> MaterialTheme.colorScheme.onSecondaryContainer
        ProbeTone.Passed -> MaterialTheme.colorScheme.onPrimaryContainer
        ProbeTone.Failed -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MaterialTheme.spacing.sm,
                vertical = MaterialTheme.spacing.xs,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
        ) {
            ForgeIcon(
                icon = status.icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = contentColor,
            )
            Text(
                text = stringResource(status.labelRes),
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
            )
        }
    }
}

private data class ProbeStatus(
    @DrawableRes val icon: Int,
    @StringRes val labelRes: Int,
    val tone: ProbeTone,
)

private enum class ProbeTone {
    Idle,
    Running,
    Passed,
    Failed,
}

private fun ProbeWorkStatus.probeStatus(): ProbeStatus = when (this) {
    ProbeWorkStatus.RUNNING, ProbeWorkStatus.ENQUEUED ->
        ProbeStatus(ForgeIcons.Loader, labelRes(), ProbeTone.Running)

    ProbeWorkStatus.SUCCEEDED ->
        ProbeStatus(ForgeIcons.CheckCircle, labelRes(), ProbeTone.Passed)

    ProbeWorkStatus.FAILED ->
        ProbeStatus(ForgeIcons.AlertCircle, labelRes(), ProbeTone.Failed)

    ProbeWorkStatus.IDLE, ProbeWorkStatus.CANCELLED, ProbeWorkStatus.BLOCKED ->
        ProbeStatus(ForgeIcons.Clock, labelRes(), ProbeTone.Idle)
}

private fun ProbeWorkStatus.labelRes(): Int = when (this) {
    ProbeWorkStatus.IDLE -> R.string.diag_work_state_idle
    ProbeWorkStatus.ENQUEUED -> R.string.diag_work_state_enqueued
    ProbeWorkStatus.RUNNING -> R.string.diag_work_state_running
    ProbeWorkStatus.SUCCEEDED -> R.string.diag_work_state_succeeded
    ProbeWorkStatus.FAILED -> R.string.diag_work_state_failed
    ProbeWorkStatus.CANCELLED -> R.string.diag_work_state_cancelled
    ProbeWorkStatus.BLOCKED -> R.string.diag_work_state_blocked
}

// ---------------------------------------------------------------------------
// Preview：示例数据
// ---------------------------------------------------------------------------
@Preview(showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun HomeScreenPreview() {
    AudioForgeTheme(dynamicColor = false) {
        HomeScreen(
            roomProbeState = RoomProbeState.Passed(counter = 3, elapsedMs = 12),
            ffmpegProbeState = FfmpegProbeState.Passed(
                version = "ffmpeg version 8.1.9",
                silenceBytes = 3_264,
                joinedDurationMs = 1_018,
            ),
            probeWork = ProbeWorkSnapshot(status = ProbeWorkStatus.RUNNING, progress = 60),
        )
    }
}
