package com.estimatefaster.audioforge.feature.about

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.estimatefaster.audioforge.BuildConfig
import com.estimatefaster.audioforge.core.audio.FfmpegProbe
import com.estimatefaster.audioforge.core.audio.FfmpegProbeState
import com.estimatefaster.audioforge.core.storage.AudioStorage
import com.estimatefaster.audioforge.core.storage.StorageMaintenance
import com.estimatefaster.audioforge.data.db.AppDatabase
import com.estimatefaster.audioforge.data.db.JobStatus
import com.estimatefaster.audioforge.data.db.RoomProbe
import com.estimatefaster.audioforge.data.db.RoomProbeState
import com.estimatefaster.audioforge.data.repository.JobRepository
import com.estimatefaster.audioforge.data.repository.ProjectRepository
import com.estimatefaster.audioforge.domain.jobs.GenerationNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 关于页的数据端口。
 *
 * 只暴露展示所需的数据，不暴露 DAO/WorkManager 类型。生产实现见
 * [AppDiagnosticsSource]；测试可用固定 Flow 与数据类实现，不需要 Room 或
 * WorkManager。
 */
interface DiagnosticsSource {

    /** 版本/构建信息，生产实现读 `BuildConfig`。 */
    val buildInfo: AppBuildInfo

    /** Room 探针状态流（P0-05）。 */
    val roomProbe: Flow<ProbeDiagnostic>

    /** FFmpeg 探针状态流（P0-07）。 */
    val ffmpegProbe: Flow<ProbeDiagnostic>

    /** Room 库版本 / schema 版本 / 表数。 */
    suspend fun database(): DatabaseDiagnostic

    /** WorkManager 是否可用与进行中的生成任务数。 */
    suspend fun workManager(): WorkManagerDiagnostic

    /** 存储占用；扫描失败时返回 null，界面显示「统计失败」。 */
    suspend fun storage(): StorageDiagnostic?

    /** 最近失败摘要，按结束时间倒序；实现应自行限制条数。 */
    suspend fun recentErrors(limit: Int): List<RecentError>
}

/**
 * 生产适配器：把 `AppContainer` 暴露的 `roomProbe` / `ffmpegProbe`、仓库与
 * `StorageMaintenance` 接到 [DiagnosticsSource]。
 *
 * - 探针只读 `state`，不会主动重跑，避免打开关于页就触发 FFmpeg 文件操作；
 * - 表数用 `sqlite_master` 统计，不硬编码实体数量；
 * - 最近错误复用 `JobRepository.lastFinished`，不直接持有 DAO；
 * - 所有可能失败的数据源都在本类内降级，单个数据源异常不影响整页。
 *
 * @param roomProbeSource P0-05 探针（`AppContainer.roomProbe`）
 * @param ffmpegProbeSource P0-07 探针（`AppContainer.ffmpegProbe`）
 * @param databaseSource 只用于读取 schema 版本与表数
 * @param projectRepository 提供项目名与生成任务名
 * @param jobRepository 读取每个项目最近一次批次结果
 * @param audioStorage 存储统计入口
 * @param workManagerSource 可为 null（未初始化/测试），对应「不可用」
 * @param buildInfoSource 版本/构建信息，默认读 `BuildConfig`
 * @param ioDispatcher 数据库与存储统计的调度器
 * @param now 存储统计与生成时间戳使用的时钟
 */
class AppDiagnosticsSource(
    private val roomProbeSource: RoomProbe,
    private val ffmpegProbeSource: FfmpegProbe,
    private val databaseSource: AppDatabase,
    private val projectRepository: ProjectRepository,
    private val jobRepository: JobRepository,
    private val audioStorage: AudioStorage,
    private val workManagerSource: WorkManager? = null,
    private val buildInfoSource: AppBuildInfo = buildConfigInfo(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : DiagnosticsSource {

    override val buildInfo: AppBuildInfo = buildInfoSource

    override val roomProbe: Flow<ProbeDiagnostic> =
        roomProbeSource.state.map { it.toProbeDiagnostic() }

    override val ffmpegProbe: Flow<ProbeDiagnostic> =
        ffmpegProbeSource.state.map { it.toProbeDiagnostic() }

    override suspend fun database(): DatabaseDiagnostic = withContext(ioDispatcher) {
        val libraryVersion = ROOM_LIBRARY_VERSION
        try {
            val db = databaseSource.openHelper.readableDatabase
            val schemaVersion = db.version
            val tableCount = db.query(TABLE_COUNT_SQL).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else null
            }
            DatabaseDiagnostic(
                libraryVersion = libraryVersion,
                schemaVersion = schemaVersion,
                tableCount = tableCount,
            )
        } catch (e: Exception) {
            Log.w(TAG, "room diagnostics unavailable", e)
            DatabaseDiagnostic(libraryVersion = libraryVersion)
        }
    }

    override suspend fun workManager(): WorkManagerDiagnostic {
        val manager = workManagerSource
            ?: return WorkManagerDiagnostic.unavailable()
        return try {
            val activeCount = projectRepository.listProjects().count { project ->
                manager.getWorkInfosForUniqueWorkFlow(GenerationNotifications.workName(project.id))
                    .first()
                    .any { it.state.isActiveWork() }
            }
            WorkManagerDiagnostic.available(activeCount)
        } catch (e: Exception) {
            Log.w(TAG, "workmanager diagnostics degraded", e)
            WorkManagerDiagnostic.degraded(e.message ?: e::class.java.simpleName)
        }
    }

    override suspend fun storage(): StorageDiagnostic? = withContext(ioDispatcher) {
        try {
            val stats = StorageMaintenance(audioStorage, now).stats()
            StorageDiagnostic(
                totalBytes = stats.totalBytes,
                fileCount = stats.fileCount,
                usableBytes = stats.usableBytes,
            )
        } catch (e: Exception) {
            Log.w(TAG, "storage diagnostics unavailable", e)
            null
        }
    }

    override suspend fun recentErrors(limit: Int): List<RecentError> {
        if (limit <= 0) return emptyList()
        val errors = projectRepository.listProjects().mapNotNull { project ->
            val job = jobRepository.lastFinished(project.id) ?: return@mapNotNull null
            if (job.status != JobStatus.ERROR) return@mapNotNull null
            val message = job.lastError?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RecentError(
                projectTitle = project.title,
                message = message,
                occurredAt = job.finishedAt,
            )
        }
        return errors.sortedByDescending { it.occurredAt ?: 0L }.take(limit)
    }

    private fun WorkInfo.State.isActiveWork(): Boolean =
        this == WorkInfo.State.ENQUEUED ||
            this == WorkInfo.State.RUNNING ||
            this == WorkInfo.State.BLOCKED

    companion object {
        private const val TAG = "AudioForgeAbout"

        /** 与执行计划 §3.1 / `docs/dependency-audit.md` 的 Room 版本一致。 */
        private const val ROOM_LIBRARY_VERSION = "2.8.5"

        private const val TABLE_COUNT_SQL =
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_metadata' " +
                "AND name NOT LIKE 'room_%'"
    }
}

/** 生产 `BuildConfig` → 纯数据类；package 用 applicationId（debug 带后缀）。 */
private fun buildConfigInfo(): AppBuildInfo = AppBuildInfo(
    versionName = BuildConfig.VERSION_NAME,
    versionCode = BuildConfig.VERSION_CODE,
    buildType = BuildConfig.BUILD_TYPE,
    packageName = BuildConfig.APPLICATION_ID,
)

/** Room 探针状态 → 展示快照；成功时带上写入计数与耗时。 */
internal fun RoomProbeState.toProbeDiagnostic(): ProbeDiagnostic = when (this) {
    RoomProbeState.Idle -> ProbeDiagnostic.idle(ProbeKind.ROOM)
    RoomProbeState.Running -> ProbeDiagnostic.running(ProbeKind.ROOM)
    is RoomProbeState.Passed -> ProbeDiagnostic.passed(
        kind = ProbeKind.ROOM,
        summary = "counter=$counter",
        detail = "elapsed_ms=$elapsedMs",
    )

    is RoomProbeState.Failed -> ProbeDiagnostic.failed(ProbeKind.ROOM, message)
}

/** FFmpeg 探针状态 → 展示快照；成功时 summary 即版本行。 */
internal fun FfmpegProbeState.toProbeDiagnostic(): ProbeDiagnostic = when (this) {
    FfmpegProbeState.Idle -> ProbeDiagnostic.idle(ProbeKind.FFMPEG)
    FfmpegProbeState.Running -> ProbeDiagnostic.running(ProbeKind.FFMPEG)
    is FfmpegProbeState.Passed -> ProbeDiagnostic.passed(
        kind = ProbeKind.FFMPEG,
        summary = version,
        detail = "silence_bytes=$silenceBytes, joined_ms=$joinedDurationMs",
    )

    is FfmpegProbeState.Failed -> ProbeDiagnostic.failed(ProbeKind.FFMPEG, message)
}

/**
 * 关于页 ViewModel（P5-10 / 执行计划 §12.4 S15、§15 P5-10）。
 *
 * 数据流：
 * ```text
 * DiagnosticsSource.buildInfo ─────────────────┐
 * roomProbe / ffmpegProbe ─→ resolveProbe ─────┤
 * database() / workManager() / storage() ──────┼→ DiagnosticsReport → AboutUiState
 * recentErrors(limit) ─────────────────────────┘
 * ```
 *
 * 单个数据源失败只降级该行（探针超时 → TIMED_OUT、WorkManager → DEGRADED、
 * 存储 → null），不会让整页进入错误态；只有构建信息都拿不到时才可能整体失败。
 * 复制/导出不在 ViewModel 里执行，界面使用 [AboutUiState.diagnosticText] 走回调。
 *
 * @param source 诊断数据端口
 * @param probeTimeoutMs 运行中的探针最长等待时间
 * @param recentErrorLimit 最近错误条数
 * @param maxDiagnosticChars 复制/导出文本上限
 * @param now 生成时间戳，测试可注入
 */
class AboutViewModel(
    private val source: DiagnosticsSource,
    private val probeTimeoutMs: Long = DEFAULT_PROBE_TIMEOUT_MS,
    private val recentErrorLimit: Int = DEFAULT_RECENT_ERROR_LIMIT,
    private val maxDiagnosticChars: Int = DEFAULT_DIAGNOSTIC_MAX_CHARS,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow(AboutUiState())
    val state: StateFlow<AboutUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        refresh()
    }

    /** 重新收集诊断信息；保留旧报告，界面显示顶部进度条。 */
    fun refresh() {
        loadJob?.cancel()
        _state.update { it.markLoading() }
        loadJob = viewModelScope.launch { load() }
    }

    /** 展开/收起许可证全文占位。 */
    fun toggleLicense(id: String) {
        _state.update { it.toggleLicense(id) }
    }

    private suspend fun load() {
        try {
            val buildInfo = source.buildInfo
            coroutineScope {
                val room = async {
                    attempt(ProbeDiagnostic.idle(ProbeKind.ROOM)) {
                        resolveProbe(ProbeKind.ROOM, source.roomProbe, probeTimeoutMs)
                    }
                }
                val ffmpeg = async {
                    attempt(ProbeDiagnostic.idle(ProbeKind.FFMPEG)) {
                        resolveProbe(ProbeKind.FFMPEG, source.ffmpegProbe, probeTimeoutMs)
                    }
                }
                val database = async {
                    attempt(DatabaseDiagnostic(UNKNOWN_VALUE)) { source.database() }
                }
                val workManager = async {
                    attempt(WorkManagerDiagnostic.unavailable()) { source.workManager() }
                }
                val storage = async {
                    attempt<StorageDiagnostic?>(null) { source.storage() }
                }
                val recentErrors = async {
                    attempt(emptyList<RecentError>()) { source.recentErrors(recentErrorLimit) }
                }

                val report = DiagnosticsReport(
                    buildInfo = buildInfo,
                    database = database.await(),
                    room = room.await(),
                    ffmpeg = ffmpeg.await(),
                    workManager = workManager.await(),
                    storage = storage.await(),
                    recentErrors = recentErrors.await(),
                    generatedAt = now(),
                )
                _state.update { it.withReport(report, maxDiagnosticChars) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.withLoadError(e.toMessage()) }
        }
    }

    private suspend fun <T> attempt(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fallback
    }

    private fun Throwable.toMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    companion object {
        /**
         * 生产入口：接 `AppContainer` 的探针、仓库与存储。
         *
         * 目前由 app 层负责创建（P5-10 的文件范围不含 app 模块）；WorkManager
         * 未初始化时降级为「不可用」，不阻塞页面。
         */
        fun fromProduction(
            context: Context,
            roomProbe: RoomProbe,
            ffmpegProbe: FfmpegProbe,
            database: AppDatabase,
            projectRepository: ProjectRepository,
            jobRepository: JobRepository,
            audioStorage: AudioStorage,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): AboutViewModel {
            val workManager = try {
                WorkManager.getInstance(context.applicationContext)
            } catch (e: IllegalStateException) {
                Log.w("AudioForgeAbout", "workmanager not initialized", e)
                null
            }
            return AboutViewModel(
                source = AppDiagnosticsSource(
                    roomProbeSource = roomProbe,
                    ffmpegProbeSource = ffmpegProbe,
                    databaseSource = database,
                    projectRepository = projectRepository,
                    jobRepository = jobRepository,
                    audioStorage = audioStorage,
                    workManagerSource = workManager,
                    ioDispatcher = ioDispatcher,
                ),
            )
        }
    }
}
