package com.estimatefaster.audioforge.feature.about

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.feature.storage.formatByteSize
import com.estimatefaster.audioforge.ui.forge.ForgeCard
import com.estimatefaster.audioforge.ui.forge.ForgeEmpty
import com.estimatefaster.audioforge.ui.forge.ForgeError
import com.estimatefaster.audioforge.ui.forge.ForgeIcon
import com.estimatefaster.audioforge.ui.forge.ForgeIconAction
import com.estimatefaster.audioforge.ui.forge.ForgeIcons
import com.estimatefaster.audioforge.ui.forge.ForgeListRow
import com.estimatefaster.audioforge.ui.forge.ForgeLoading
import com.estimatefaster.audioforge.ui.forge.ForgePrimaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSecondaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSectionHeader
import com.estimatefaster.audioforge.ui.forge.ForgeTopBar
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing

/**
 * 关于页（P5-10 / 执行计划 §12.4 S15、§15 P5-10），Forge Framework v1 重做。
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
 * 无状态关于页（Forge Framework v1）。
 *
 * 布局严格对齐 `docs/prototype/forge-framework.html` 的 About 页：
 * - ForgeTopBar：返回图标、标题、副标题、复制诊断图标操作；
 * - 应用 hero：AudioLines logo、应用名、副标题、版本状态胶囊、构建信息、许可声明、
 *   GitHub 地址与「图标 + 文案」的打开按钮；
 * - 诊断：每行 Lucide leading icon + 标题 + 读数 + 「图标 + 状态」胶囊；
 *   Room / FFmpeg / WorkManager / 存储占用 / 最近错误；
 * - 开源许可：ForgeListRow + 展开/收起图标操作，展开后显示许可全文占位；
 * - 复制诊断 / 导出日志：唯一主次操作，禁用条件仍由 [AboutUiState.canShareDiagnostics] 决定；
 * - 加载、错误、空态复用 [ForgeLoading] / [ForgeError] / [ForgeEmpty]。
 *
 * 所有数据来自 [AboutUiState]，本文件不采集诊断、不发网络请求。
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
    val report = state.report

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ForgeTopBar(
                title = stringResource(R.string.about_title),
                subtitle = stringResource(R.string.about_v2_topbar_subtitle),
                navigationIcon = ForgeIcons.Back,
                navigationDescription = stringResource(R.string.action_back),
                onNavigation = onBack,
                actions = {
                    if (state.canShareDiagnostics) {
                        ForgeIconAction(
                            icon = ForgeIcons.Copy,
                            contentDescription = stringResource(R.string.about_copy_diagnostics),
                            onClick = { onCopyDiagnostics(state.diagnosticText) },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                report == null && state.loading -> ForgeLoading(
                    label = stringResource(R.string.about_loading),
                )

                report == null && state.loadError != null -> ForgeError(
                    message = stringResource(
                        R.string.about_load_error,
                        state.loadError.orEmpty(),
                    ),
                    onRetry = onRefresh,
                    retryLabel = stringResource(R.string.action_retry),
                )

                report == null -> ForgeEmpty(
                    icon = ForgeIcons.Info,
                    title = stringResource(R.string.about_empty_title),
                    description = stringResource(R.string.about_empty_description),
                    actionIcon = ForgeIcons.Refresh,
                    actionLabel = stringResource(R.string.about_empty_action),
                    onAction = onRefresh,
                )

                else -> AboutContent(
                    state = state,
                    report = report,
                    onRefresh = onRefresh,
                    onToggleLicense = onToggleLicense,
                    onOpenGitHub = onOpenGitHub,
                    onCopyDiagnostics = onCopyDiagnostics,
                    onExportLogs = onExportLogs,
                )
            }
        }
    }
}

@Composable
private fun AboutContent(
    state: AboutUiState,
    report: DiagnosticsReport,
    onRefresh: () -> Unit,
    onToggleLicense: (String) -> Unit,
    onOpenGitHub: (String) -> Unit,
    onCopyDiagnostics: (String) -> Unit,
    onExportLogs: (String) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val refreshingDescription = stringResource(R.string.about_loading)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        if (state.isRefreshing) {
            item(key = "refreshing") {
                LinearWavyProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = refreshingDescription },
                )
            }
        }
        if (state.loadError != null) {
            item(key = "load-error") {
                AboutLoadErrorCard(
                    message = stringResource(
                        R.string.about_load_error,
                        state.loadError.orEmpty(),
                    ),
                    onRetry = onRefresh,
                )
            }
        }

        item(key = "hero") {
            AboutHeroCard(
                buildInfo = report.buildInfo,
                onOpenGitHub = onOpenGitHub,
            )
        }

        item(key = "diagnostics") {
            DiagnosticsCard(report = report, onRefresh = onRefresh)
        }

        item(key = "licenses-header") {
            ForgeSectionHeader(
                title = stringResource(R.string.about_licenses_title),
                subtitle = stringResource(
                    R.string.about_licenses_subtitle,
                    state.licenses.size,
                ),
            )
        }
        items(
            items = state.licenses,
            key = { entry -> "license-${entry.id}" },
        ) { entry ->
            LicenseRow(
                entry = entry,
                expanded = entry.id == state.expandedLicenseId,
                onToggle = { onToggleLicense(entry.id) },
            )
        }

        item(key = "actions") {
            ShareDiagnosticsCard(
                canShare = state.canShareDiagnostics,
                diagnosticText = state.diagnosticText,
                onCopyDiagnostics = onCopyDiagnostics,
                onExportLogs = onExportLogs,
            )
        }

        item(key = "footer") {
            Text(
                text = stringResource(R.string.about_v2_footer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.lg, vertical = spacing.md),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 应用 hero
// ---------------------------------------------------------------------------

@Composable
private fun AboutHeroCard(buildInfo: AppBuildInfo?, onOpenGitHub: (String) -> Unit) {
    val spacing = MaterialTheme.spacing
    ForgeCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraExtraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(80.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ForgeIcon(
                        icon = ForgeIcons.AudioLines,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmallEmphasized,
            )
            Text(
                text = stringResource(R.string.about_v2_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (buildInfo != null) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = spacing.sm,
                            vertical = spacing.xxs,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        ForgeIcon(
                            icon = ForgeIcons.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = stringResource(
                                R.string.about_v2_version_pill,
                                buildInfo.versionLabel,
                                buildInfo.buildTypeLabel,
                            ),
                            style = MaterialTheme.typography.labelMediumEmphasized,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            HorizontalDivider()

            if (buildInfo == null) {
                Text(
                    text = stringResource(R.string.about_build_info_missing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                InfoLine(
                    icon = ForgeIcons.Info,
                    label = stringResource(R.string.about_version_label),
                    value = buildInfo.versionLabel,
                )
                InfoLine(
                    icon = ForgeIcons.Shield,
                    label = stringResource(R.string.about_build_type_label),
                    value = buildInfo.buildTypeLabel,
                )
                InfoLine(
                    icon = ForgeIcons.Smartphone,
                    label = stringResource(R.string.about_package_label),
                    value = buildInfo.packageLabel,
                )
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.about_license_statement),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.about_github_url, ABOUT_GITHUB_URL),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            ForgeSecondaryAction(
                icon = ForgeIcons.Globe,
                label = stringResource(R.string.about_github_open),
                onClick = { onOpenGitHub(ABOUT_GITHUB_URL) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun InfoLine(@DrawableRes icon: Int, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
    ) {
        ForgeIcon(
            icon = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMediumEmphasized,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 诊断
// ---------------------------------------------------------------------------

@Composable
private fun DiagnosticsCard(report: DiagnosticsReport, onRefresh: () -> Unit) {
    val spacing = MaterialTheme.spacing
    ForgeCard {
        ForgeSectionHeader(
            title = stringResource(R.string.about_diagnostics_title),
            subtitle = stringResource(R.string.about_v2_diag_hint),
            actionIcon = ForgeIcons.Refresh,
            actionDescription = stringResource(R.string.about_v2_rerun_diagnostics),
            onAction = onRefresh,
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
            icon = ForgeIcons.Database,
            label = stringResource(R.string.about_room_label),
            status = probeStatus(report.room.state),
            value = roomValue,
            extra = listOfNotNull(report.room.summary, report.room.detail)
                .joinToString(" · ")
                .takeIf { it.isNotBlank() },
            errorDetail = report.room.detail
                .takeIf { report.room.state == ProbeState.FAILED },
        )

        HorizontalDivider()

        DiagnosticRow(
            icon = ForgeIcons.AudioLines,
            label = stringResource(R.string.about_ffmpeg_label),
            status = probeStatus(report.ffmpeg.state),
            value = report.ffmpeg.summary ?: stringResource(R.string.about_value_unknown),
            extra = report.ffmpeg.detail
                .takeIf { report.ffmpeg.state != ProbeState.FAILED },
            errorDetail = report.ffmpeg.detail
                .takeIf { report.ffmpeg.state == ProbeState.FAILED },
        )

        HorizontalDivider()

        val workManager = report.workManager
        DiagnosticRow(
            icon = ForgeIcons.Timer,
            label = stringResource(R.string.about_workmanager_label),
            status = workManagerStatus(workManager.state),
            value = stringResource(
                R.string.about_v2_workmanager_active,
                workManager.activeWorkCount,
            ),
            extra = workManager.detail,
        )

        HorizontalDivider()

        val storage = report.storage
        DiagnosticRow(
            icon = ForgeIcons.Storage,
            label = stringResource(R.string.about_storage_label),
            status = if (storage == null) {
                DiagStatus(
                    icon = ForgeIcons.TriangleAlert,
                    text = stringResource(R.string.about_storage_unavailable),
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                )
            } else {
                DiagStatus(
                    icon = ForgeIcons.CheckCircle,
                    text = stringResource(R.string.about_v2_storage_status_ok),
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                )
            },
            value = storage?.let {
                stringResource(
                    R.string.about_storage_line,
                    formatByteSize(it.totalBytes),
                    it.fileCount,
                )
            },
            extra = storage?.let {
                stringResource(R.string.about_storage_usable, formatByteSize(it.usableBytes))
            },
        )

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            ForgeIcon(
                icon = ForgeIcons.AlertCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.about_recent_errors_title),
                style = MaterialTheme.typography.titleSmallEmphasized,
            )
        }
        if (report.recentErrors.isEmpty()) {
            Text(
                text = stringResource(R.string.about_recent_errors_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            report.recentErrors.forEach { error ->
                RecentErrorRow(error = error)
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    @DrawableRes icon: Int,
    label: String,
    status: DiagStatus,
    value: String? = null,
    extra: String? = null,
    errorDetail: String? = null,
) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        IconBubble(icon = icon)
        Column(modifier = Modifier.weight(1f)) {
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
                StatusPill(status = status)
            }
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (errorDetail != null) {
                Text(
                    text = stringResource(R.string.about_probe_error, errorDetail),
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
}

@Composable
private fun RecentErrorRow(error: RecentError) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        ForgeIcon(
            icon = ForgeIcons.AlertCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(
                R.string.about_recent_error_line,
                error.projectTitle
                    ?: stringResource(R.string.about_recent_error_unknown_project),
                error.message,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 诊断状态胶囊：Lucide 图标 + 状态文案，颜色由调用方按严重程度给出。 */
private data class DiagStatus(
    @DrawableRes val icon: Int,
    val text: String,
    val contentColor: Color,
    val containerColor: Color,
)

@Composable
private fun StatusPill(status: DiagStatus) {
    Surface(
        shape = CircleShape,
        color = status.containerColor,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MaterialTheme.spacing.sm,
                vertical = MaterialTheme.spacing.xxs,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
        ) {
            ForgeIcon(
                icon = status.icon,
                contentDescription = null,
                tint = status.contentColor,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = status.text,
                style = MaterialTheme.typography.labelMediumEmphasized,
                color = status.contentColor,
            )
        }
    }
}

@Composable
private fun IconBubble(@DrawableRes icon: Int) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.size(44.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ForgeIcon(
                icon = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun probeStatus(state: ProbeState?): DiagStatus = when (state) {
    ProbeState.PASSED -> DiagStatus(
        icon = ForgeIcons.CheckCircle,
        text = stringResource(R.string.about_probe_status_passed),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
    )

    ProbeState.FAILED -> DiagStatus(
        icon = ForgeIcons.TriangleAlert,
        text = stringResource(R.string.about_probe_status_failed),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        containerColor = MaterialTheme.colorScheme.errorContainer,
    )

    ProbeState.TIMED_OUT -> DiagStatus(
        icon = ForgeIcons.TriangleAlert,
        text = stringResource(R.string.about_probe_status_timed_out),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        containerColor = MaterialTheme.colorScheme.errorContainer,
    )

    ProbeState.RUNNING -> DiagStatus(
        icon = ForgeIcons.Loader,
        text = stringResource(R.string.about_probe_status_running),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )

    ProbeState.IDLE -> DiagStatus(
        icon = ForgeIcons.Clock,
        text = stringResource(R.string.about_probe_status_idle),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )

    null -> DiagStatus(
        icon = ForgeIcons.Clock,
        text = stringResource(R.string.about_probe_status_unknown),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

@Composable
private fun workManagerStatus(state: WorkManagerState): DiagStatus = when (state) {
    WorkManagerState.AVAILABLE -> DiagStatus(
        icon = ForgeIcons.CheckCircle,
        text = stringResource(R.string.about_workmanager_available),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
    )

    WorkManagerState.DEGRADED -> DiagStatus(
        icon = ForgeIcons.TriangleAlert,
        text = stringResource(R.string.about_workmanager_degraded),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        containerColor = MaterialTheme.colorScheme.errorContainer,
    )

    WorkManagerState.UNAVAILABLE -> DiagStatus(
        icon = ForgeIcons.Clock,
        text = stringResource(R.string.about_workmanager_unavailable),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

// ---------------------------------------------------------------------------
// 许可与分享
// ---------------------------------------------------------------------------

@Composable
private fun LicenseRow(
    entry: LicenseEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
    ) {
        ForgeListRow(
            icon = ForgeIcons.Shield,
            title = stringResource(R.string.about_license_line, entry.name, entry.version),
            subtitle = stringResource(R.string.about_license_type, entry.license),
            trailingIcon = if (expanded) ForgeIcons.ChevronUp else ForgeIcons.ChevronDown,
            trailingDescription = stringResource(
                R.string.about_v2_license_toggle,
                entry.name,
            ),
            onTrailingClick = onToggle,
        )
        if (expanded) {
            Text(
                text = stringResource(
                    R.string.about_license_placeholder,
                    entry.name,
                    entry.license,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.lg),
            )
        }
    }
}

@Composable
private fun ShareDiagnosticsCard(
    canShare: Boolean,
    diagnosticText: String,
    onCopyDiagnostics: (String) -> Unit,
    onExportLogs: (String) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    ForgeCard {
        ForgePrimaryAction(
            icon = ForgeIcons.Copy,
            label = stringResource(R.string.about_copy_diagnostics),
            onClick = { onCopyDiagnostics(diagnosticText) },
            modifier = Modifier.fillMaxWidth(),
            enabled = canShare,
        )
        ForgeSecondaryAction(
            icon = ForgeIcons.Download,
            label = stringResource(R.string.about_export_logs),
            onClick = { onExportLogs(diagnosticText) },
            modifier = Modifier.fillMaxWidth(),
            enabled = canShare,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            ForgeIcon(
                icon = ForgeIcons.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.about_v2_share_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AboutLoadErrorCard(message: String, onRetry: () -> Unit) {
    val spacing = MaterialTheme.spacing
    ForgeCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            ForgeIcon(
                icon = ForgeIcons.TriangleAlert,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
        }
        ForgeSecondaryAction(
            icon = ForgeIcons.Refresh,
            label = stringResource(R.string.action_retry),
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// Preview：正常 / 加载 / 错误 / 空态（暗色 200%）。文案全部来自 strings。
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

@Preview(name = "About Forge Normal", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun AboutNormalPreview() {
    AudioForgeTheme(darkTheme = false, dynamicColor = false) {
        AboutScreen(state = previewState())
    }
}

@Preview(name = "About Forge Loading", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun AboutLoadingPreview() {
    AudioForgeTheme(darkTheme = false, dynamicColor = false) {
        AboutScreen(state = AboutUiState(loading = true))
    }
}

@Preview(name = "About Forge Error", showBackground = true, backgroundColor = 0xFFF7F8FC)
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
    name = "About Forge Empty Dark 200%",
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
