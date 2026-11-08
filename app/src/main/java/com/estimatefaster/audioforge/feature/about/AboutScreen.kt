package com.estimatefaster.audioforge.feature.about

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.feature.storage.formatByteSize
import com.estimatefaster.audioforge.ui.components.EmptyState
import com.estimatefaster.audioforge.ui.components.ErrorBanner
import com.estimatefaster.audioforge.ui.components.ErrorState
import com.estimatefaster.audioforge.ui.components.LoadingState
import com.estimatefaster.audioforge.ui.components.SectionHeader
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme

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
 * 无状态关于页。
 *
 * 布局：
 * - 应用卡：名称、版本（`VERSION_NAME (VERSION_CODE)`）、构建类型、包名、
 *   开源声明与 GitHub 入口（点击回调）；
 * - 诊断卡：Room（库版本/schema/表数/探针）、FFmpeg（探针版本）、
 *   WorkManager（可用性与进行中任务数）、存储占用、最近错误；
 *   「复制诊断信息」「导出日志」只回调文本；
 * - 许可卡：FFmpegKit / OkHttp / Room / Media3 / Compose / Serialization /
 *   WorkManager，顺序固定，可展开全文占位；
 * - 加载、错误、空态复用 P4-02 组件。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (state.isRefreshing) {
            item(key = "refreshing") {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
                AboutAppCard(
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
        item(key = "licenses") {
            LicensesCard(
                licenses = state.licenses,
                expandedLicenseId = state.expandedLicenseId,
                onToggleLicense = onToggleLicense,
            )
        }
    }
}

@Composable
private fun AboutAppCard(
    buildInfo: AppBuildInfo?,
    onOpenGitHub: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionHeader(
                title = stringResource(R.string.about_app_card_title),
                subtitle = stringResource(R.string.about_app_card_subtitle),
            )
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            if (buildInfo == null) {
                Text(
                    text = stringResource(R.string.about_build_info_missing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                InfoLine(
                    label = stringResource(R.string.about_version_label),
                    value = buildInfo.versionLabel,
                )
                InfoLine(
                    label = stringResource(R.string.about_build_type_label),
                    value = buildInfo.buildTypeLabel,
                )
                InfoLine(
                    label = stringResource(R.string.about_package_label),
                    value = buildInfo.packageLabel,
                )
            }
            HorizontalDivider()
            Text(
                text = stringResource(R.string.about_license_statement),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.about_github_url, ABOUT_GITHUB_URL),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            TextButton(
                onClick = { onOpenGitHub(ABOUT_GITHUB_URL) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.about_github_open))
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader(
                title = stringResource(R.string.about_diagnostics_title),
                subtitle = stringResource(R.string.about_diagnostics_subtitle),
            )

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
            DiagnosticRow(
                label = stringResource(R.string.about_room_label),
                probe = report.room,
                value = roomValue,
                extra = listOfNotNull(report.room.summary, report.room.detail)
                    .joinToString(" · ")
                    .takeIf { it.isNotBlank() },
            )
            HorizontalDivider()

            DiagnosticRow(
                label = stringResource(R.string.about_ffmpeg_label),
                probe = report.ffmpeg,
                value = report.ffmpeg.summary,
                extra = report.ffmpeg.detail,
            )
            HorizontalDivider()

            val workManager = report.workManager
            val workManagerLabel = workManagerStatusLabel(workManager.state)
            DiagnosticRow(
                label = stringResource(R.string.about_workmanager_label),
                probe = null,
                value = stringResource(
                    R.string.about_workmanager_line,
                    workManagerLabel,
                    workManager.activeWorkCount,
                ),
                extra = workManager.detail,
                statusText = workManagerLabel,
                statusColor = workManagerStatusColor(workManager.state),
            )
            HorizontalDivider()

            val storage = report.storage
            DiagnosticRow(
                label = stringResource(R.string.about_storage_label),
                probe = null,
                value = if (storage == null) {
                    stringResource(R.string.about_storage_unavailable)
                } else {
                    stringResource(
                        R.string.about_storage_line,
                        formatByteSize(storage.totalBytes),
                        storage.fileCount,
                    )
                },
                extra = storage?.let {
                    stringResource(R.string.about_storage_usable, formatByteSize(it.usableBytes))
                },
            )
            HorizontalDivider()

            Text(
                text = stringResource(R.string.about_recent_errors_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (report.recentErrors.isEmpty()) {
                Text(
                    text = stringResource(R.string.about_recent_errors_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                report.recentErrors.forEach { error ->
                    Text(
                        text = stringResource(
                            R.string.about_recent_error_line,
                            error.projectTitle
                                ?: stringResource(R.string.about_recent_error_unknown_project),
                            error.message,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = { onCopyDiagnostics(diagnosticText) },
                    enabled = canShare,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.about_copy_diagnostics))
                }
                FilledTonalButton(
                    onClick = { onExportLogs(diagnosticText) },
                    enabled = canShare,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.about_export_logs))
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    probe: ProbeDiagnostic?,
    value: String?,
    extra: String? = null,
    statusText: String? = null,
    statusColor: Color? = null,
) {
    val resolvedStatus = statusText ?: probeStatusLabel(probe?.state)
    val resolvedColor = statusColor ?: probeStatusColor(probe?.state)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = resolvedStatus,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = resolvedColor,
            )
        }
        if (value != null) {
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
        val failedDetail = probe?.detail
        if (probe?.state == ProbeState.FAILED && failedDetail != null) {
            Text(
                text = stringResource(R.string.about_probe_error, failedDetail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (extra != null) {
            Text(
                text = extra,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LicensesCard(
    licenses: List<LicenseEntry>,
    expandedLicenseId: String?,
    onToggleLicense: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionHeader(
                title = stringResource(R.string.about_licenses_title),
                subtitle = stringResource(R.string.about_licenses_subtitle, licenses.size),
            )
            licenses.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider()
                LicenseRow(
                    entry = entry,
                    expanded = entry.id == expandedLicenseId,
                    onToggle = { onToggleLicense(entry.id) },
                )
            }
        }
    }
}

@Composable
private fun LicenseRow(
    entry: LicenseEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.about_license_line, entry.name, entry.version),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.about_license_type, entry.license),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onToggle,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = stringResource(
                        if (expanded) {
                            R.string.about_license_collapse
                        } else {
                            R.string.about_license_expand
                        },
                    ),
                )
            }
        }
        if (expanded) {
            Text(
                text = stringResource(
                    R.string.about_license_placeholder,
                    entry.name,
                    entry.license,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

@Composable
private fun probeStatusColor(state: ProbeState?): Color = when (state) {
    ProbeState.PASSED -> MaterialTheme.colorScheme.primary
    ProbeState.FAILED, ProbeState.TIMED_OUT -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun workManagerStatusLabel(state: WorkManagerState): String = when (state) {
    WorkManagerState.AVAILABLE -> stringResource(R.string.about_workmanager_available)
    WorkManagerState.UNAVAILABLE -> stringResource(R.string.about_workmanager_unavailable)
    WorkManagerState.DEGRADED -> stringResource(R.string.about_workmanager_degraded)
}

@Composable
private fun workManagerStatusColor(state: WorkManagerState): Color = when (state) {
    WorkManagerState.AVAILABLE -> MaterialTheme.colorScheme.primary
    WorkManagerState.DEGRADED -> MaterialTheme.colorScheme.error
    WorkManagerState.UNAVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant
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

@Preview(name = "About Normal", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun AboutNormalPreview() {
    AudioForgeTheme(darkTheme = false, dynamicColor = false) {
        AboutScreen(state = previewState())
    }
}

@Preview(name = "About Loading", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun AboutLoadingPreview() {
    AudioForgeTheme(darkTheme = false, dynamicColor = false) {
        AboutScreen(state = AboutUiState(loading = true))
    }
}

@Preview(name = "About Error", showBackground = true, backgroundColor = 0xFFF7F8FC)
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
