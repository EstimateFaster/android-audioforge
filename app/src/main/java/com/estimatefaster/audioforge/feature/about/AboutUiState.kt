package com.estimatefaster.audioforge.feature.about

import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** 值缺失时的统一占位，避免界面出现空字符串或只有冒号的行。 */
const val UNKNOWN_VALUE: String = "unknown"

/** 复制/导出文本的默认字符上限；超出后截断并附提示。 */
const val DEFAULT_DIAGNOSTIC_MAX_CHARS: Int = 4_000

/** 单条错误摘要的字符上限，防止把长堆栈或正文塞进诊断文本。 */
const val MAX_ERROR_MESSAGE_CHARS: Int = 240

/** 探针最长等待时间（毫秒）；超时降级为 [ProbeState.TIMED_OUT]，不阻塞整页。 */
const val DEFAULT_PROBE_TIMEOUT_MS: Long = 2_000L

/** 最近错误默认展示条数。 */
const val DEFAULT_RECENT_ERROR_LIMIT: Int = 3

/** 诊断文本被截断时追加的标记。 */
const val DIAGNOSTIC_TRUNCATED_MARKER: String = "\n…（诊断信息已截断）"

/** 项目开源仓库地址；界面通过回调打开，状态层不发 Intent。 */
const val ABOUT_GITHUB_URL: String = "https://github.com/EstimateFaster/android-audioforge"

/**
 * 版本与构建信息。
 *
 * 生产来源是 `BuildConfig`（见 `AppDiagnosticsSource`），状态层只用这个纯 Kotlin
 * 数据类，因此格式化逻辑可以在无 Android 的 JVM 测试里直接覆盖。
 */
data class AppBuildInfo(
    val versionName: String,
    val versionCode: Int,
    val buildType: String,
    val packageName: String,
) {

    /** 形如 `0.1.0 (1)`；名称缺失时用 [UNKNOWN_VALUE] 兜底。 */
    val versionLabel: String
        get() = formatVersionLabel(versionName, versionCode)

    /** 构建类型统一小写；空值用 [UNKNOWN_VALUE] 兜底。 */
    val buildTypeLabel: String
        get() = formatBuildType(buildType)

    /** 包名（debug 构建带 `.debug` 后缀）；空值用 [UNKNOWN_VALUE] 兜底。 */
    val packageLabel: String
        get() = packageName.trim().ifBlank { UNKNOWN_VALUE }
}

/** 版本名 + versionCode 的稳定格式；不依赖 Android 资源。 */
fun formatVersionLabel(versionName: String, versionCode: Int): String {
    val name = versionName.trim().ifBlank { UNKNOWN_VALUE }
    return "$name ($versionCode)"
}

/** 构建类型统一小写（debug/release），空值返回 [UNKNOWN_VALUE]。 */
fun formatBuildType(buildType: String): String =
    buildType.trim().ifBlank { UNKNOWN_VALUE }.lowercase(Locale.ROOT)

/** 两个探针各自的命名空间；同时用于诊断文本的 key 前缀。 */
enum class ProbeKind(val wireName: String) {
    ROOM("room"),
    FFMPEG("ffmpeg"),
}

/** 探针状态。超时是一种独立的降级结果，不与失败混用。 */
enum class ProbeState {
    IDLE,
    RUNNING,
    PASSED,
    FAILED,
    TIMED_OUT,
}

/**
 * 一次探针读数的快照。
 *
 * [summary] 是适合直接展示的一行结论（例如 FFmpeg 版本号），[detail] 是补充信息
 * （耗时、字节数或失败原因）。两者都由生产适配器从探针状态映射而来。
 */
data class ProbeDiagnostic(
    val kind: ProbeKind,
    val state: ProbeState,
    val summary: String? = null,
    val detail: String? = null,
) {

    /** 不会再变化的状态；只有终止态才算探针结果。 */
    val isTerminal: Boolean
        get() = state == ProbeState.PASSED ||
            state == ProbeState.FAILED ||
            state == ProbeState.TIMED_OUT

    val isSuccessful: Boolean
        get() = state == ProbeState.PASSED

    companion object {
        fun idle(kind: ProbeKind): ProbeDiagnostic = ProbeDiagnostic(kind, ProbeState.IDLE)

        fun running(kind: ProbeKind): ProbeDiagnostic = ProbeDiagnostic(kind, ProbeState.RUNNING)

        fun passed(
            kind: ProbeKind,
            summary: String?,
            detail: String? = null,
        ): ProbeDiagnostic = ProbeDiagnostic(
            kind = kind,
            state = ProbeState.PASSED,
            summary = summary,
            detail = detail,
        )

        fun failed(kind: ProbeKind, message: String): ProbeDiagnostic = ProbeDiagnostic(
            kind = kind,
            state = ProbeState.FAILED,
            summary = null,
            detail = message,
        )

        /** 探针在超时窗口内没有给出终止态；界面据此显示「超时（已降级）」。 */
        fun timedOut(kind: ProbeKind): ProbeDiagnostic = ProbeDiagnostic(
            kind = kind,
            state = ProbeState.TIMED_OUT,
        )
    }
}

/** Room 运行时版本 + schema 版本 + 表数；schema/表数读不到时为 null。 */
data class DatabaseDiagnostic(
    val libraryVersion: String,
    val schemaVersion: Int? = null,
    val tableCount: Int? = null,
)

/** WorkManager 的三态：可用、不可用、查询受限（超时/异常降级）。 */
enum class WorkManagerState {
    AVAILABLE,
    UNAVAILABLE,
    DEGRADED,
}

/** WorkManager 诊断结果；[detail] 只在降级时带原因。 */
data class WorkManagerDiagnostic(
    val state: WorkManagerState,
    val activeWorkCount: Int = 0,
    val detail: String? = null,
) {
    companion object {
        fun available(activeWorkCount: Int): WorkManagerDiagnostic = WorkManagerDiagnostic(
            state = WorkManagerState.AVAILABLE,
            activeWorkCount = activeWorkCount,
        )

        fun unavailable(): WorkManagerDiagnostic = WorkManagerDiagnostic(
            state = WorkManagerState.UNAVAILABLE,
        )

        fun degraded(detail: String?): WorkManagerDiagnostic = WorkManagerDiagnostic(
            state = WorkManagerState.DEGRADED,
            detail = detail,
        )
    }
}

/** 存储占用快照；与 `StorageMaintenance.stats()` 一一对应。 */
data class StorageDiagnostic(
    val totalBytes: Long,
    val fileCount: Int,
    val usableBytes: Long,
) {
    val isEmpty: Boolean
        get() = totalBytes <= 0L && fileCount <= 0
}

/**
 * 最近失败摘要。
 *
 * [projectTitle] 允许为 null（项目已删除），[occurredAt] 为 `jobs.finishedAt`
 * 的原始毫秒值；诊断文本直接输出毫秒，避免依赖设备时区。
 */
data class RecentError(
    val projectTitle: String?,
    val message: String,
    val occurredAt: Long? = null,
)

/** 一条开源许可条目。 */
data class LicenseEntry(
    val id: String,
    val name: String,
    val version: String,
    val license: String,
    val url: String? = null,
)

/**
 * 关于页列出的主要依赖与许可证；顺序即展示顺序（FFmpegKit 在最前，因为它是
 * 唯一非 Apache/MIT 的运行时组件）。
 *
 * 版本与 `docs/dependency-audit.md`、执行计划 §3.1 一致；本对象只用于展示，
 * 不参与构建配置。
 */
object AboutLicenses {

    val entries: List<LicenseEntry> = listOf(
        LicenseEntry(
            id = "ffmpeg-kit",
            name = "FFmpegKit",
            version = "8.1.9",
            license = "LGPL-3.0",
            url = "https://github.com/arthenica/ffmpeg-kit",
        ),
        LicenseEntry(
            id = "okhttp",
            name = "OkHttp",
            version = "5.5.1",
            license = "Apache-2.0",
            url = "https://square.github.io/okhttp/",
        ),
        LicenseEntry(
            id = "room",
            name = "Room",
            version = "2.8.5",
            license = "Apache-2.0",
            url = "https://developer.android.com/jetpack/androidx/releases/room",
        ),
        LicenseEntry(
            id = "media3",
            name = "Media3",
            version = "1.11.1",
            license = "Apache-2.0",
            url = "https://developer.android.com/media/media3",
        ),
        LicenseEntry(
            id = "compose",
            name = "Jetpack Compose",
            version = "BOM 2026.09.00",
            license = "Apache-2.0",
            url = "https://developer.android.com/jetpack/compose",
        ),
        LicenseEntry(
            id = "serialization",
            name = "kotlinx.serialization",
            version = "1.9.0",
            license = "Apache-2.0",
            url = "https://github.com/Kotlin/kotlinx.serialization",
        ),
        LicenseEntry(
            id = "workmanager",
            name = "WorkManager",
            version = "2.12.0",
            license = "Apache-2.0",
            url = "https://developer.android.com/jetpack/androidx/releases/work",
        ),
    )
}

/** 一页诊断所需的全部不可变数据；由 [AboutUiState] 持有。 */
data class DiagnosticsReport(
    val buildInfo: AppBuildInfo,
    val database: DatabaseDiagnostic,
    val room: ProbeDiagnostic,
    val ffmpeg: ProbeDiagnostic,
    val workManager: WorkManagerDiagnostic,
    val storage: StorageDiagnostic?,
    val recentErrors: List<RecentError>,
    val generatedAt: Long,
)

/**
 * 关于页的不可变 UI 状态。
 *
 * 迁移函数全部为纯函数，ViewModel 只负责调度与合并，因此加载/错误/刷新失败
 * 保留旧报告、许可展开等都能在纯 JVM 测试里覆盖。
 */
data class AboutUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val report: DiagnosticsReport? = null,
    val licenses: List<LicenseEntry> = AboutLicenses.entries,
    val expandedLicenseId: String? = null,
    val diagnosticText: String = "",
) {

    /** 首次加载中（还没有任何报告）。 */
    val isLoadingFirstTime: Boolean
        get() = loading && report == null

    /** 已有报告时的后台刷新。 */
    val isRefreshing: Boolean
        get() = loading && report != null

    val isReady: Boolean
        get() = !loading && loadError == null && report != null

    /** 加载结束、没有错误，也没有任何诊断报告。 */
    val isEmpty: Boolean
        get() = !loading && loadError == null && report == null

    /** 有诊断文本才允许复制/导出。 */
    val canShareDiagnostics: Boolean
        get() = diagnosticText.isNotBlank()

    val expandedLicense: LicenseEntry?
        get() = licenses.firstOrNull { it.id == expandedLicenseId }
}

// ---------------------------------------------------------------------------
// 状态迁移：纯函数，ViewModel 与测试共用。
// ---------------------------------------------------------------------------

/** 开始/重新开始加载；保留旧报告，刷新失败时可以继续展示已知信息。 */
fun AboutUiState.markLoading(): AboutUiState = copy(
    loading = true,
    loadError = null,
)

/** 加载成功：替换报告，并按上限重建可复制的诊断文本。 */
fun AboutUiState.withReport(
    report: DiagnosticsReport,
    maxChars: Int = DEFAULT_DIAGNOSTIC_MAX_CHARS,
): AboutUiState = copy(
    loading = false,
    loadError = null,
    report = report,
    diagnosticText = buildDiagnosticText(report, maxChars),
)

/** 加载失败：保留旧报告与旧诊断文本，只记录错误供界面提示。 */
fun AboutUiState.withLoadError(message: String): AboutUiState = copy(
    loading = false,
    loadError = message,
)

/** 展开/收起某个许可证；再次点击同一条目会收起。 */
fun AboutUiState.toggleLicense(id: String): AboutUiState = copy(
    expandedLicenseId = if (expandedLicenseId == id) null else id,
)

// ---------------------------------------------------------------------------
// 探针解析：不阻塞界面，超时降级。
// ---------------------------------------------------------------------------

/**
 * 读取探针的当前快照；若正在运行，最多等待 [timeoutMs] 毫秒。
 *
 * - 已终止（通过/失败/超时）或空闲时立即返回，不等待；
 * - 运行中只在收到终止态时返回；超时返回 [ProbeDiagnostic.timedOut]，
 *   不抛异常，整页仍可展示其他诊断项。
 */
internal suspend fun resolveProbe(
    kind: ProbeKind,
    states: Flow<ProbeDiagnostic>,
    timeoutMs: Long,
): ProbeDiagnostic {
    val current = states.first()
    if (current.state != ProbeState.RUNNING) return current

    val resolved = withTimeoutOrNull(timeoutMs.coerceAtLeast(0L)) {
        states.first { it.isTerminal }
    }
    return resolved ?: ProbeDiagnostic.timedOut(kind)
}

// ---------------------------------------------------------------------------
// 诊断文本：复制/导出共用，不写文件、不读日志文件。
// ---------------------------------------------------------------------------

/**
 * 把报告拼成可复制的纯文本。
 *
 * 采用 `section.key=value` 形式，便于贴到 issue / 日志里机械比对；不含小说正文，
 * 且所有值都先经过 [redactSecrets]。超过 [maxChars] 时截断并追加
 * [DIAGNOSTIC_TRUNCATED_MARKER]。
 */
fun buildDiagnosticText(
    report: DiagnosticsReport,
    maxChars: Int = DEFAULT_DIAGNOSTIC_MAX_CHARS,
): String {
    if (maxChars <= 0) return ""

    val build = report.buildInfo
    val database = report.database
    val lines = buildList {
        add("AudioForge diagnostics v1")
        add("generated_at_ms=${report.generatedAt}")
        add("app.version_name=${diagnosticValue(build.versionName)}")
        add("app.version_code=${build.versionCode}")
        add("app.build_type=${diagnosticValue(build.buildTypeLabel)}")
        add("app.package=${diagnosticValue(build.packageLabel)}")

        add("room.library_version=${diagnosticValue(database.libraryVersion)}")
        add("room.schema_version=${database.schemaVersion?.toString() ?: UNKNOWN_VALUE}")
        add("room.table_count=${database.tableCount?.toString() ?: UNKNOWN_VALUE}")
        addProbe(report.room)
        addProbe(report.ffmpeg)

        add("workmanager.status=${report.workManager.state.name.lowercase(Locale.ROOT)}")
        add("workmanager.active_work=${report.workManager.activeWorkCount}")
        report.workManager.detail?.let { detail ->
            add("workmanager.detail=${diagnosticValue(detail)}")
        }

        val storage = report.storage
        if (storage == null) {
            add("storage.status=unavailable")
        } else {
            add("storage.total_bytes=${storage.totalBytes}")
            add("storage.file_count=${storage.fileCount}")
            add("storage.usable_bytes=${storage.usableBytes}")
        }

        add("errors.count=${report.recentErrors.size}")
        report.recentErrors.forEachIndexed { index, error ->
            add("error.$index.project=${diagnosticValue(error.projectTitle)}")
            add("error.$index.message=${errorMessageValue(error.message)}")
            error.occurredAt?.let { occurredAt ->
                add("error.$index.finished_at_ms=$occurredAt")
            }
        }
    }

    val text = redactSecrets(lines.joinToString(separator = "\n"))
    if (text.length <= maxChars) return text

    val marker = DIAGNOSTIC_TRUNCATED_MARKER
    val budget = (maxChars - marker.length).coerceAtLeast(0)
    return text.take(budget) + marker.take((maxChars - budget).coerceAtLeast(0))
}

private fun MutableList<String>.addProbe(probe: ProbeDiagnostic) {
    val prefix = "${probe.kind.wireName}.probe"
    add("$prefix.status=${probe.state.name.lowercase(Locale.ROOT)}")
    probe.summary?.let { add("$prefix.summary=${diagnosticValue(it)}") }
    probe.detail?.let { add("$prefix.detail=${diagnosticValue(it)}") }
}

/**
 * 掩码常见密钥形态：`sk-…`、`Bearer …`、`api_key=…` / `token: …`。
 *
 * 诊断文本只应该包含版本、计数与错误摘要；这里再加一层兜底，避免用户把上游
 * 返回体里的 Key 原样贴出去。
 */
fun redactSecrets(text: String): String {
    if (text.isEmpty()) return text
    var result = SK_KEY_PATTERN.replace(text, "sk-***")
    result = BEARER_PATTERN.replace(result) { "Bearer ***" }
    result = KEY_VALUE_PATTERN.replace(result) { match ->
        val name = match.groupValues[1]
        val separator = match.groupValues[2]
        "$name$separator***"
    }
    return result
}

private fun diagnosticValue(raw: String?): String {
    if (raw == null) return UNKNOWN_VALUE
    val singleLine = raw
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
    return singleLine.ifBlank { UNKNOWN_VALUE }
}

private fun errorMessageValue(message: String): String {
    val singleLine = diagnosticValue(message)
    if (singleLine.length <= MAX_ERROR_MESSAGE_CHARS) return singleLine
    return singleLine.take(MAX_ERROR_MESSAGE_CHARS) + "…"
}

private val SK_KEY_PATTERN = Regex("""sk-[A-Za-z0-9_-]{6,}""")

private val BEARER_PATTERN = Regex("""(?i)bearer\s+[A-Za-z0-9._~+/=-]+""")

private val KEY_VALUE_PATTERN = Regex(
    """(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|authorization|secret|token)(\s*[=:]\s*)([^\s"',;]+)""",
)
