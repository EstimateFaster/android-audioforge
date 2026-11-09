package com.estimatefaster.audioforge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.core.content.ContextCompat
import com.estimatefaster.audioforge.BuildConfig
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.core.audio.FfmpegProbeState
import com.estimatefaster.audioforge.data.db.RoomProbeState
import com.estimatefaster.audioforge.ui.components.ExpressiveCard
import com.estimatefaster.audioforge.ui.components.ExpressivePrimaryButton
import com.estimatefaster.audioforge.ui.components.ExpressiveTonalButton
import com.estimatefaster.audioforge.ui.components.SectionHeader
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing
import com.estimatefaster.audioforge.work.ProbeWorkSnapshot
import com.estimatefaster.audioforge.work.ProbeWorkStatus

/**
 * 首页（Expressive 重做）。
 *
 * 结构：`HeroCard`（primaryContainer 大圆角）→ 清单 → Debug 冒烟检测 → 下一步 → 页脚。
 * 所有分组用 [SectionHeader]，卡片统一 [ExpressiveCard]，动作只用 expressive 按钮；
 * 原有回调与文案 key 保持不变。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val spacing = MaterialTheme.spacing

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                        Text(
                            text = stringResource(R.string.home_title),
                            style = MaterialTheme.typography.titleLargeEmphasized,
                        )
                        Text(
                            text = stringResource(R.string.home_subtitle),
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
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = spacing.xl,
                end = spacing.xl,
                top = spacing.sm,
                bottom = spacing.lg,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            item { HeroCard() }
            item {
                SectionHeader(
                    title = stringResource(R.string.checklist_title),
                    subtitle = stringResource(R.string.ux_home_checklist_subtitle),
                )
            }
            item { ChecklistCard() }
            if (BuildConfig.DEBUG) {
                item {
                    SectionHeader(
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
                AnimatedVisibility(visible = showDetails) {
                    ExpressiveCard {
                        Text(
                            text = stringResource(R.string.phase0_details),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Text(
                    text = stringResource(R.string.footer),
                    modifier = Modifier.padding(vertical = spacing.md),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HeroCard() {
    val spacing = MaterialTheme.spacing
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraExtraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.phase0_badge),
                style = MaterialTheme.typography.labelLargeEmphasized,
            )
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                text = stringResource(R.string.home_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun ChecklistCard() {
    val spacing = MaterialTheme.spacing
    ExpressiveCard {
        listOf(
            R.string.checklist_1,
            R.string.checklist_2,
            R.string.checklist_3,
            R.string.checklist_4,
        ).forEachIndexed { index, textRes ->
            if (index > 0) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
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
    ExpressiveCard {
        SectionHeader(title = stringResource(R.string.diag_title))
        Text(
            text = when (state) {
                RoomProbeState.Idle -> stringResource(R.string.diag_room_idle)
                RoomProbeState.Running -> stringResource(R.string.diag_room_running)
                is RoomProbeState.Passed -> stringResource(
                    R.string.diag_room_passed,
                    state.counter,
                    state.elapsedMs,
                )
                is RoomProbeState.Failed -> stringResource(
                    R.string.diag_room_failed,
                    state.message,
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        ExpressiveTonalButton(
            text = stringResource(R.string.diag_room_action),
            onClick = onReRun,
            enabled = state !is RoomProbeState.Running,
        )
    }
}

@Composable
private fun FfmpegProbeCard(state: FfmpegProbeState, onRun: () -> Unit) {
    val failed = state is FfmpegProbeState.Failed
    ExpressiveCard {
        SectionHeader(title = stringResource(R.string.diag_ffmpeg_title))
        Text(
            text = when (state) {
                FfmpegProbeState.Idle -> stringResource(R.string.diag_ffmpeg_idle)
                FfmpegProbeState.Running -> stringResource(R.string.diag_ffmpeg_running)
                is FfmpegProbeState.Passed -> stringResource(
                    R.string.diag_ffmpeg_passed,
                    state.version,
                    state.silenceBytes,
                    state.joinedDurationMs,
                )
                is FfmpegProbeState.Failed -> stringResource(
                    R.string.diag_ffmpeg_failed,
                    state.message,
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        ExpressiveTonalButton(
            text = stringResource(R.string.diag_ffmpeg_action),
            onClick = onRun,
            enabled = state !is FfmpegProbeState.Running,
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

    ExpressiveCard {
        SectionHeader(title = stringResource(R.string.diag_work_title))
        Text(
            text = stringResource(
                R.string.diag_work_status,
                stringResource(snapshot.status.labelRes()),
                snapshot.progress,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
            ExpressivePrimaryButton(
                text = stringResource(R.string.diag_work_start),
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
                enabled = snapshot.status != ProbeWorkStatus.RUNNING &&
                    snapshot.status != ProbeWorkStatus.ENQUEUED,
            )
            ExpressiveTonalButton(
                text = stringResource(R.string.diag_work_stop),
                onClick = onStop,
            )
        }
    }
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

@Composable
private fun NextCard(showDetails: Boolean, onToggle: () -> Unit) {
    ExpressiveCard {
        SectionHeader(
            title = stringResource(R.string.next_title),
            subtitle = stringResource(R.string.next_body),
        )
        ExpressiveTonalButton(
            text = if (showDetails) {
                stringResource(R.string.ux_home_collapse)
            } else {
                stringResource(R.string.next_action)
            },
            onClick = onToggle,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    AudioForgeTheme(dynamicColor = false) {
        HomeScreen()
    }
}

@Preview(showBackground = true)
@Composable
private fun RoomProbeCardPreview() {
    AudioForgeTheme(dynamicColor = false) {
        RoomProbeCard(
            state = RoomProbeState.Passed(counter = 3, elapsedMs = 12),
            onReRun = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun FfmpegProbeCardPreview() {
    AudioForgeTheme(dynamicColor = false) {
        FfmpegProbeCard(
            state = FfmpegProbeState.Passed(
                version = "ffmpeg version 8.1.9",
                silenceBytes = 3_264,
                joinedDurationMs = 1_018,
            ),
            onRun = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ProbeWorkerCardPreview() {
    AudioForgeTheme(dynamicColor = false) {
        ProbeWorkerCard(
            snapshot = ProbeWorkSnapshot(status = ProbeWorkStatus.RUNNING, progress = 60),
            onStart = {},
            onStop = {},
        )
    }
}
