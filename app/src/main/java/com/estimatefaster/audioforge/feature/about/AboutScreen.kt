@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.estimatefaster.audioforge.feature.about

import android.content.res.Configuration
import androidx.compose.animation.animateContentSize
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.feature.storage.formatByteSize
import com.estimatefaster.audioforge.ui.components.EmptyState
import com.estimatefaster.audioforge.ui.components.ErrorBanner
import com.estimatefaster.audioforge.ui.components.ErrorState
import com.estimatefaster.audioforge.ui.components.ExpressiveCard
import com.estimatefaster.audioforge.ui.components.ExpressivePrimaryButton
import com.estimatefaster.audioforge.ui.components.ExpressiveTonalButton
import com.estimatefaster.audioforge.ui.components.LoadingState
import com.estimatefaster.audioforge.ui.components.SectionHeader
import com.estimatefaster.audioforge.ui.components.StatusPill
import com.estimatefaster.audioforge.ui.components.StatusTone
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing

/**
 * 关于页（P5-10 / 执行计划 §12.4 S15、§15 P5-10）。
 *
 * 有状态入口：收集 [AboutViewModel.state] 后委托给无状态版本。页面不直接读
 * `BuildConfig`、文件系统或 WorkManager；复制与导出只把
 * [AboutUiState.diagnosticText] 交给回调，由宿主决定写剪贴板还是走 FileProvider。
 */
@Composable
fun AboutScreen(
    viewModel: AboutViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenGitHub: (String) -> Unit = {},
    onCopyDiagnostics: (String) -> Unit = {},
    onExportLogs: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AboutScreen(
        state = state,
        modifier = modifier,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onToggleLicense = viewModel::toggleLicense,
        onOpenGitHub = onOpenGitHub,
        onCopyDiagnostics = onCopyDiagnostics,
        onExportLogs = onExportLogs,
    )
}

/**
 * 无状态关于页（Expressive 布局）。
 *
 * 布局：
 * - 应用信息 hero：primaryContainer 大圆角容器，名称与版本、构建类型/包名、
 *   开源声明与 GitHub 入口；
 * - 诊断卡：Room、FFmpeg、WorkManager、存储占用、最近错误，各自一张 tonal 行卡，
 *   状态用 [StatusPill] 表达，失败原因就地显示；
 * - 许可证列表：每个依赖一张可展开卡片，展开内容为许可证占位说明；
 * - 复制/导出按钮只回调文本；加载、错误、空态复用共享组件。
 *
 * 页面不改变任何诊断/许可状态，所有数据来自 [AboutUiState]。
 */
@Composable
fun AboutScreen(
    state: AboutUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onToggleLicense: (String) -> Unit = {},
    onOpenGitHub: (String) -> Unit = {},
    onCopyDiagnostics: (String) -> Unit = {},
    onExportLogs: (String) -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.action_back))
                    }
                },
                title = {
                    Text(
                        text = stringResource(R.string.about_title),
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLargeEmphasized,
                    )
                },
                actions = {
                    TextButton(
                        onClick = onRefresh,
                        enabled = !state.isLoadingFirstTime,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.action_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        when {
            state.isLoadingFirstTime -> LoadingState(
                message = stringResource(R.string.about_loading),
                modifier = Modifier.padding(padding),
            )

            state.loadError != null && state.report == null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                ErrorState(
                    message = stringResource(
                        R.string.about_load_error,
                        state.loadError.orEmpty(),
                    ),
                    onRetry = onRefresh,
                )
            }

            else -> AboutContent(
                state = state,
                contentPadding = padding,
                onRefresh = onRefresh,
                onToggleLicense = onToggleLicense,
                onOpenGitHub = onOpenGitHub,
                onCopyDiagnostics = onCopyDiagnostics,
                onExportLogs = onExportLogs,
            )
        }
    }
}

@Composable
private fun AboutContent(
    state: AboutUiState,
    contentPadding: PaddingValues,
    onRefresh: () -> Unit,
    onToggleLicense: (String) -> Unit,
    onOpenGitHub: (String) -> Unit,
    onCopyDiagnostics: (String) -> Unit,
    onExportLogs: (String) -> Unit,
) {
    val report = state.report
    val spacing = MaterialTheme.spacing

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        if (state.isRefreshing) {
            item(key = "refreshing") {
                LinearWavyProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.xs),
                )
            }
        }
        if (state.loadError != null) {
            item(key = "inline-error") {
                ErrorBanner(
                    message = stringResource(
                        R.string.about_load_error,
                        state.loadError.orEmpty(),
                    ),
                    onRetry = onRefresh,
                )
            }
        }
        if (report == null) {
            item(key = "empty") {
                EmptyState(
                    title = stringResource(R.string.about_empty_title),
                    description = stringResource(R.string.about_empty_description),
                    actionText = stringResource(R.string.about_empty_action),
                    onAction = onRefresh,
                )
            }
        } else {
            item(key = "app") {
                AboutHero(
                    buildInfo = report.buildInfo,
                    onOpenGitHub = onOpenGitHub,
                )
            }
            item(key = "diagnostics") {
                DiagnosticsCard(
                    report = report,
                    canShare = state.canShareDiagnostics,
                    onCopyDiagnostics = onCopyDiagnostics,
                    onExportLogs = onExportLogs,
                    diagnosticText = state.diagnosticText,
                )
            }
        }
        item(key = "licenses-header") {
            SectionHeader(
                title = stringResource(R.string.about_licenses_title),
                subtitle = stringResource(
                    R.string.about_licenses_subtitle,
                    state.licenses.size,
                ),
            )
        }
        items(
            items = state.licenses,
            key = { entry -> entry.id },
        ) { entry ->
            LicenseCard(
                entry = entry,
                expanded = entry.id == state.expandedLicenseId,
                onToggle = { onToggleLicense(entry.id) },
            )
        }
    }
}

@Composable
private fun AboutHero(
    buildInfo: AppBuildInfo?,
    onOpenGitHub: (String) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraExtraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.about_app_card_title),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmallEmphasized,
            )
            if (buildInfo == null) {
                Text(
                    text = stringResource(R.string.about_build_info_missing),
                    style = MaterialTheme.typography.bodyMediumEmphasized,
                )
            } else {
                Text(
                    text = buildInfo.versionLabel,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                HeroInfoLine(
                    label = stringResource(R.string.about_build_type_label),
                    value = buildInfo.buildTypeLabel,
                )
                HeroInfoLine(
                    label = stringResource(R.string.about_package_label),
                    value = buildInfo.packageLabel,
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = spacing.xs),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.24f),
            )
            Text(
                text = stringResource(R.string.about_license_statement),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.about_github_url, ABOUT_GITHUB_URL),
                style = MaterialTheme.typography.bodySmall,
            )
            ExpressiveTonalButton(
                text = stringResource(R.string.about_github_open),
                onClick = { onOpenGitHub(ABOUT_GITHUB_URL) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.xs),
            )
        }
    }
}

@Composable
private fun HeroInfoLine(label: String, value: String) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.35f),
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.65f),
        )
    }
}

@Composable
private fun DiagnosticsCard(
    report: DiagnosticsReport,
    canShare: Boolean,
    onCopyDiagnostics: (String) -> Unit,
    onExportLogs: (String) -> Unit,
    diagnosticText: String,
) {
    val spacing = MaterialTheme.spacing
    val database = report.database
    val roomValue = if (database.tableCount == null && database.schemaVersion == null) {
        stringResource(R.string.about_database_unavailable)
    } else {
        val schema = database.schemaVersion
            ?.let { stringResource(R.string.about_room_schema_value, it) }
            ?: stringResource(R.string.about_value_unknown)
        val tables = database.tableCount
            ?.let { stringResource(R.string.about_room_table_value, it) }
            ?: stringResource(R.string.about_value_unknown)
        stringResource(
            R.string.about_room_line,
            database.libraryVersion,
            schema,
            tables,
        )
    }
    val roomDetail = if (report.room.state == ProbeState.FAILED) {
        report.room.detail?.let { detail ->
            stringResource(R.string.about_probe_error, detail)
        }
    } else {
        listOfNotNull(report.room.summary, report.room.detail)
            .joinToString(" · ")
            .takeIf { text -> text.isNotBlank() }
    }
    val ffmpegDetail = if (report.ffmpeg.state == ProbeState.FAILED) {
        report.ffmpeg.detail?.let { detail ->
            stringResource(R.string.about_probe_error, detail)
        }
    } else {
        report.ffmpeg.detail
    }

    val workManager = report.workManager
    val workManagerLabel = workManagerStatusLabel(workManager.state)
    val storage = report.storage

    ExpressiveCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = stringResource(R.string.about_diagnostics_title),
            subtitle = stringResource(R.string.about_diagnostics_subtitle),
        )
        DiagnosticTonalRow(
            label = stringResource(R.string.about_room_label),
            statusText = probeStatusLabel(report.room.state),
            tone = probeTone(report.room.state),
            value = roomValue,
            detail = roomDetail,
            detailIsError = report.room.state == ProbeState.FAILED,
        )
        DiagnosticTonalRow(
            label = stringResource(R.string.about_ffmpeg_label),
            statusText = probeStatusLabel(report.ffmpeg.state),
            tone = probeTone(report.ffmpeg.state),
            value = report.ffmpeg.summary,
            detail = ffmpegDetail,
            detailIsError = report.ffmpeg.state == ProbeState.FAILED,
        )
        DiagnosticTonalRow(
            label = stringResource(R.string.about_workmanager_label),
            statusText = workManagerLabel,
            tone = workManagerTone(workManager.state),
            value = stringResource(
                R.string.about_workmanager_line,
                workManagerLabel,
                workManager.activeWorkCount,
            ),
            detail = workManager.detail,
            detailIsError = false,
        )
        DiagnosticTonalRow(
            label = stringResource(R.string.about_storage_label),
            statusText = if (storage == null) {
                stringResource(R.string.about_storage_unavailable)
            } else {
                stringResource(R.string.about_storage_status_ok)
            },
            tone = if (storage == null) StatusTone.Danger else StatusTone.Success,
            value = if (storage == null) {
                stringResource(R.string.about_storage_unavailable)
            } else {
                stringResource(
                    R.string.about_storage_line,
                    formatByteSize(storage.totalBytes),
                    storage.fileCount,
                )
            },
            detail = storage?.let { snapshot ->
                stringResource(R.string.about_storage_usable, formatByteSize(snapshot.usableBytes))
            },
            detailIsError = false,
        )
        Text(
            text = stringResource(R.string.about_recent_errors_title),
            style = MaterialTheme.typography.titleSmallEmphasized,
            modifier = Modifier
                .padding(top = spacing.xs)
                .semantics { heading() },
        )
        if (report.recentErrors.isEmpty()) {
            RecentErrorsEmptyRow()
        } else {
            report.recentErrors.forEach { error ->
                RecentErrorRow(error = error)
            }
        }
        ExpressivePrimaryButton(
            text = stringResource(R.string.about_copy_diagnostics),
            onClick = { onCopyDiagnostics(diagnosticText) },
            enabled = canShare,
            modifier = Modifier.fillMaxWidth(),
        )
        ExpressiveTonalButton(
            text = stringResource(R.string.about_export_logs),
            onClick = { onExportLogs(diagnosticText) },
            enabled = canShare,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DiagnosticTonalRow(
    label: String,
    statusText: String,
    tone: StatusTone,
    value: String?,
    detail: String?,
    detailIsError: Boolean,
) {
    val spacing = MaterialTheme.spacing
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(text = statusText, tone = tone)
            }
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (detailIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun RecentErrorsEmptyRow() {
    val spacing = MaterialTheme.spacing
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = stringResource(R.string.about_recent_errors_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(spacing.md),
        )
    }
}

@Composable
private fun RecentErrorRow(error: RecentError) {
    val spacing = MaterialTheme.spacing
    val project = error.projectTitle
        ?: stringResource(R.string.about_recent_error_unknown_project)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            StatusPill(
                text = stringResource(R.string.about_probe_status_failed),
                tone = StatusTone.Danger,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Text(
                    text = error.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LicenseCard(
    entry: LicenseEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val shape = MaterialTheme.shapes.large
    val actionLabel = stringResource(
        if (expanded) {
            R.string.about_license_collapse
        } else {
            R.string.about_license_expand
        },
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) { stateDescription = actionLabel },
        shape = shape,
        color = if (expanded) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (expanded) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Column(
            modifier = Modifier
                .animateContentSize()
                .fillMaxWidth()
                .padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            R.string.about_license_line,
                            entry.name,
                            entry.version,
                        ),
                        style = MaterialTheme.typography.titleSmallEmphasized,
                    )
                    Text(
                        text = stringResource(R.string.about_license_type, entry.license),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(spacing.xs))
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (expanded) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            if (expanded) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = spacing.xs),
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.24f),
                )
                Text(
                    text = stringResource(
                        R.string.about_license_placeholder,
                        entry.name,
                        entry.license,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun probeStatusLabel(state: ProbeState?): String = when (state) {
    null -> stringResource(R.string.about_probe_status_unknown)
    ProbeState.IDLE -> stringResource(R.string.about_probe_status_idle)
    ProbeState.RUNNING -> stringResource(R.string.about_probe_status_running)
    ProbeState.PASSED -> stringResource(R.string.about_probe_status_passed)
    ProbeState.FAILED -> stringResource(R.string.about_probe_status_failed)
    ProbeState.TIMED_OUT -> stringResource(R.string.about_probe_status_timed_out)
}

private fun probeTone(state: ProbeState?): StatusTone = when (state) {
    ProbeState.PASSED -> StatusTone.Success
    ProbeState.FAILED, ProbeState.TIMED_OUT -> StatusTone.Danger
    ProbeState.RUNNING -> StatusTone.Running
    ProbeState.IDLE, null -> StatusTone.Neutral
}

@Composable
private fun workManagerStatusLabel(state: WorkManagerState): String = when (state) {
    WorkManagerState.AVAILABLE -> stringResource(R.string.about_workmanager_available)
    WorkManagerState.UNAVAILABLE -> stringResource(R.string.about_workmanager_unavailable)
    WorkManagerState.DEGRADED -> stringResource(R.string.about_workmanager_degraded)
}

private fun workManagerTone(state: WorkManagerState): StatusTone = when (state) {
    WorkManagerState.AVAILABLE -> StatusTone.Success
    WorkManagerState.DEGRADED -> StatusTone.Warning
    WorkManagerState.UNAVAILABLE -> StatusTone.Neutral
}

// ---------------------------------------------------------------------------
// Preview：正常 / 加载 / 错误 / 空态（暗色 200%）。文案全部来自 strings.xml。
// ---------------------------------------------------------------------------

private fun previewBuildInfo(): AppBuildInfo = AppBuildInfo(
    versionName = "0.1.0-debug",
    versionCode = 1,
    buildType = "debug",
    packageName = "com.estimatefaster.audioforge.debug",
)

private fun previewReport(): DiagnosticsReport = DiagnosticsReport(
    buildInfo = previewBuildInfo(),
    database = DatabaseDiagnostic(
        libraryVersion = "2.8.5",
        schemaVersion = 1,
        tableCount = 8,
    ),
    room = ProbeDiagnostic.passed(
        kind = ProbeKind.ROOM,
        summary = "counter=3",
        detail = "elapsed_ms=12",
    ),
    ffmpeg = ProbeDiagnostic.passed(
        kind = ProbeKind.FFMPEG,
        summary = "ffmpeg version 8.1.9",
        detail = "silence_bytes=1234, joined_ms=812",
    ),
    workManager = WorkManagerDiagnostic.available(activeWorkCount = 0),
    storage = StorageDiagnostic(
        totalBytes = 3_150_000_000L,
        fileCount = 1_842,
        usableBytes = 12_000_000_000L,
    ),
    recentErrors = listOf(
        RecentError(
            projectTitle = "长夜将明",
            message = "第 12 章 TTS 超时（已重试 2 次）",
            occurredAt = 1_760_000_000_000L,
        ),
    ),
    generatedAt = 1_760_000_000_000L,
)

private fun previewState(): AboutUiState = AboutUiState().withReport(previewReport())

@Preview(name = "About Normal", showBackground = true)
@Composable
private fun AboutNormalPreview() {
    AudioForgeTheme(darkTheme = false, dynamicColor = false) {
        AboutScreen(state = previewState())
    }
}

@Preview(name = "About License Expanded", showBackground = true)
@Composable
private fun AboutLicenseExpandedPreview() {
    AudioForgeTheme(darkTheme = false, dynamicColor = false) {
        AboutScreen(
            state = previewState().copy(expandedLicenseId = "ffmpeg-kit"),
        )
    }
}

@Preview(name = "About Loading", showBackground = true)
@Composable
private fun AboutLoadingPreview() {
    AudioForgeTheme(darkTheme = false, dynamicColor = false) {
        AboutScreen(state = AboutUiState(loading = true))
    }
}

@Preview(name = "About Error", showBackground = true)
@Composable
private fun AboutErrorPreview() {
    AudioForgeTheme(darkTheme = false, dynamicColor = false) {
        AboutScreen(
            state = AboutUiState(
                loading = false,
                loadError = "WorkManager 未初始化",
            ),
        )
    }
}

@Preview(
    name = "About Empty Dark 200%",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun AboutEmptyPreview() {
    AudioForgeTheme(darkTheme = true, dynamicColor = false) {
        AboutScreen(
            state = AboutUiState(
                loading = false,
                licenses = AboutLicenses.entries,
            ),
        )
    }
}
