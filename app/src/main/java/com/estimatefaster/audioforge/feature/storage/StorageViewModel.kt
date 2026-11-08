package com.estimatefaster.audioforge.feature.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estimatefaster.audioforge.core.storage.AudioStorage
import com.estimatefaster.audioforge.core.storage.CleanupReport
import com.estimatefaster.audioforge.core.storage.StorageMaintenance
import com.estimatefaster.audioforge.core.storage.StorageReport
import com.estimatefaster.audioforge.core.storage.StorageStats
import com.estimatefaster.audioforge.data.db.ProjectSummary
import com.estimatefaster.audioforge.data.repository.ProjectRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 存储页的数据端口：扫描与两种清理。
 *
 * 接口只暴露 UI 需要的三个动作，便于 JVM 测试注入 fake；生产实现见
 * [StorageMaintenanceGateway]（包装 `StorageMaintenance` + `ProjectRepository`）。
 */
interface StorageGateway {

    /** 按 [retentionDays] 扫描并汇总。 */
    suspend fun scan(retentionDays: Int): StorageOverview

    /** 删除无引用且超过安全年龄的文件；不会删除仍被引用的文件。 */
    suspend fun cleanOrphans(): StorageCleanupResult

    /** 按保留期清理项目音频；仍被引用的候选会被跳过。 */
    suspend fun cleanExpired(retentionDays: Int): StorageCleanupResult
}

/**
 * 当前仍被 DB 引用的文件集合。
 *
 * [audio] 应来自 `segments.audioRelPath`，[imports] 来自 `novels.sourceRelPath`。
 * 两者都会交给 `StorageMaintenance` 做删除前保护。
 */
data class StorageReferences(
    val audio: Set<String>,
    val imports: Set<String>,
)

/** 引用集合提供者；与文件扫描解耦，测试可用固定集合。 */
interface StorageReferenceProvider {
    suspend fun load(): StorageReferences
}

/**
 * 生产引用集合：导入源文件从项目仓库读取，音频引用由调用方提供。
 *
 * 音频引用必须显式传入（DB 的 `segments.audioRelPath`），**不提供空集合默认值**：
 * 空集合会让孤儿清理把仍在使用的音频当成孤儿删除。
 */
class ProjectStorageReferenceProvider(
    private val projectRepository: ProjectRepository,
    private val audioReferences: suspend () -> Set<String>,
) : StorageReferenceProvider {

    override suspend fun load(): StorageReferences {
        val imports = projectRepository.listProjects()
            .mapNotNull { novel -> novel.sourceRelPath.takeIf { it.isNotBlank() } }
            .toSet()
        return StorageReferences(audio = audioReferences(), imports = imports)
    }
}

/**
 * 生产适配器：把 `StorageMaintenance`、`ProjectRepository` 与引用集合接到
 * [StorageGateway]。
 *
 * 保留期是 `StorageMaintenance` 的构造参数，因此每次扫描都按 UI 当前值重新构造一个
 * 轻量实例（内部只持有目录与时钟，不建连接/不读文件）。
 *
 * @param maintenanceFor 按保留期（天）构造扫描器
 * @param projectRepository 提供项目名与章节数
 * @param references 提供 DB 引用集合，删除前用于保护
 */
class StorageMaintenanceGateway private constructor(
    private val maintenanceFor: (Int) -> StorageMaintenance,
    private val projectRepository: ProjectRepository,
    private val references: StorageReferenceProvider,
) : StorageGateway {

    constructor(
        audioStorage: AudioStorage,
        projectRepository: ProjectRepository,
        references: StorageReferenceProvider,
        now: () -> Long = System::currentTimeMillis,
    ) : this(
        maintenanceFor = { days -> StorageMaintenance(audioStorage, now, days) },
        projectRepository = projectRepository,
        references = references,
    )

    constructor(
        maintenance: StorageMaintenance,
        projectRepository: ProjectRepository,
        references: StorageReferenceProvider,
    ) : this(
        maintenanceFor = { _ -> maintenance },
        projectRepository = projectRepository,
        references = references,
    )

    override suspend fun scan(retentionDays: Int): StorageOverview {
        requireRetention(retentionDays)
        val maintenance = maintenanceFor(retentionDays)
        val refs = references.load()
        val report = maintenance.scan(refs.audio, refs.imports)
        return buildStorageOverview(
            stats = maintenance.stats(),
            report = report,
            projects = projectRepository.observeProjects().first(),
        )
    }

    override suspend fun cleanOrphans(): StorageCleanupResult {
        // 孤儿清理使用固定 6 小时安全阈值，与保留期无关。
        val maintenance = maintenanceFor(DEFAULT_RETENTION_DAYS)
        val refs = references.load()
        return maintenance.cleanOrphans(refs.audio, refs.imports)
            .toStorageCleanupResult(StorageCleanupKind.ORPHANS)
    }

    /**
     * 过期清理只使用扫描结果里的 `expired` 候选（被 DB 引用且超过保留期的项目音频）。
     *
     * `StorageMaintenance` 的保护集合会跳过仍被引用的候选并计入 `skippedReferenced`；
     * 这是有意的：若 DB 仍指向该音频，删除会造成悬空引用。调用方需要先解除引用
     * （`audioRelPath = null`）才会真正释放字节，因此本页宁可少删也不误删。
     */
    override suspend fun cleanExpired(retentionDays: Int): StorageCleanupResult {
        requireRetention(retentionDays)
        val maintenance = maintenanceFor(retentionDays)
        val refs = references.load()
        val scan = maintenance.scan(refs.audio, refs.imports)
        return maintenance.cleanExpired(
            candidates = scan.expired.map { it.relative },
            referencedAudio = refs.audio,
            referencedImports = refs.imports,
            retentionDays = retentionDays,
        ).toStorageCleanupResult(StorageCleanupKind.EXPIRED)
    }

    private fun requireRetention(days: Int) {
        require(days in MIN_RETENTION_DAYS..MAX_RETENTION_DAYS) {
            "保留期必须在 $MIN_RETENTION_DAYS..$MAX_RETENTION_DAYS 天：$days"
        }
    }
}

/**
 * 把 `StorageStats` / `StorageReport` / 项目聚合结果映射成 UI 概览。
 *
 * 项目顺序沿用仓库（最近打开优先）；`perNovelBytes` 中已不在项目列表里的残留
 * 以 `title = null` 追加在末尾，保证「总占用 ≠ 项目之和」时不会静默丢数据。
 */
internal fun buildStorageOverview(
    stats: StorageStats,
    report: StorageReport,
    projects: List<ProjectSummary>,
): StorageOverview {
    val known = projects.map { summary ->
        StorageProjectUsage(
            novelId = summary.id,
            title = summary.title,
            bytes = stats.bytesFor(summary.id),
            chapterCount = summary.chapterCount,
        )
    }
    val knownIds = projects.mapTo(mutableSetOf()) { summary -> summary.id }
    val detached = stats.perNovelBytes
        .filterKeys { novelId -> novelId !in knownIds }
        .toSortedMap()
        .map { (novelId, bytes) ->
            StorageProjectUsage(
                novelId = novelId,
                title = null,
                bytes = bytes,
                chapterCount = 0,
            )
        }

    return StorageOverview(
        totalBytes = stats.totalBytes,
        usableBytes = stats.usableBytes,
        audioBytes = stats.audioBytes,
        importsBytes = stats.importsBytes,
        exportsBytes = stats.exportsBytes,
        fileCount = stats.fileCount,
        projects = known + detached,
        orphanCount = report.orphans.size,
        orphanBytes = report.orphanBytes,
        expiredCount = report.expired.size,
        expiredBytes = report.expiredBytes,
        scanFailureCount = report.errors.size,
        scannedAt = report.scannedAt,
    )
}

/** 清理报告 → UI 结果；只保留界面要展示的计数。 */
internal fun CleanupReport.toStorageCleanupResult(
    kind: StorageCleanupKind,
): StorageCleanupResult = StorageCleanupResult(
    kind = kind,
    freedBytes = freedBytes,
    deletedCount = deletedCount,
    failureCount = failureCount,
    skippedReferencedCount = skippedReferenced.size,
    skippedTooRecentCount = skippedTooRecent.size,
    skippedMissingCount = skippedMissing.size,
)

/** 执行一种清理；抽成顶层函数便于用 fake gateway 做纯 JVM 测试。 */
internal suspend fun runStorageCleanup(
    gateway: StorageGateway,
    kind: StorageCleanupKind,
    retentionDays: Int,
): StorageCleanupResult = when (kind) {
    StorageCleanupKind.ORPHANS -> gateway.cleanOrphans()
    StorageCleanupKind.EXPIRED -> gateway.cleanExpired(retentionDays)
}

/**
 * 存储页 ViewModel（P5-09 / 执行计划 §12.4 S14、§15 P5-09）。
 *
 * 数据流：
 * ```text
 * StorageGateway.scan(retentionDays) ─→ StorageOverview ─┐
 * 保留期输入（纯函数校验）            ─→ retentionDays  ─┼→ StorageUiState
 * 确认清理 → gateway.clean* → 重扫   ─→ StorageCleanupResult ┘
 * ```
 *
 * 所有文件系统/DB 工作都在注入的 [ioDispatcher] 上执行；并发清理由
 * [StorageUiState.beginCleanup] 的状态迁移去重，ViewModel 不启动第二个协程。
 *
 * @param gateway 扫描/清理端口
 * @param ioDispatcher IO 调度器，测试可注入
 */
class StorageViewModel(
    private val gateway: StorageGateway,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var cleanupJob: Job? = null

    init {
        refresh()
    }

    /** 重新统计；清理进行中时忽略，避免覆盖结果。 */
    fun refresh() {
        load()
    }

    /** 保留期变化后重扫；已有概览会保留，只在顶部显示进度。 */
    fun onRetentionCommit() {
        if (_state.value.retentionError != null) return
        load()
    }

    fun onRetentionInputChange(raw: String) {
        _state.update { it.withRetentionInput(raw) }
    }

    /** 滑块只传整数天；越界值收敛到 7..365。 */
    fun onRetentionSliderChange(days: Int) {
        _state.update { it.withRetentionDays(days) }
    }

    /** 点击清理按钮：打开确认对话框。 */
    fun requestCleanup(kind: StorageCleanupKind) {
        _state.update { it.requestCleanup(kind) }
    }

    fun cancelCleanup() {
        _state.update { it.cancelCleanup() }
    }

    /** 确认清理；并发点击只会有一次生效。 */
    fun confirmCleanup() {
        val current = _state.value
        val kind = current.pendingCleanup ?: return
        val next = current.beginCleanup(kind) ?: return
        _state.value = next
        launchCleanup(kind)
    }

    /** 失败/部分失败后重试同一种清理。 */
    fun retryCleanup() {
        val current = _state.value
        if (!current.canRetryCleanup) return
        val kind = current.lastCleanupKind ?: return
        val next = current.beginCleanup(kind) ?: return
        _state.value = next
        launchCleanup(kind)
    }

    fun dismissCleanupResult() {
        _state.update { it.dismissCleanupResult() }
    }

    private fun load() {
        if (_state.value.cleaning != null) return
        loadJob?.cancel()
        _state.update { it.markLoading() }
        loadJob = viewModelScope.launch { loadOverview() }
    }

    private suspend fun loadOverview() {
        val retentionDays = _state.value.retentionDays
        try {
            val overview = withContext(ioDispatcher) { gateway.scan(retentionDays) }
            _state.update { it.withOverview(overview) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.withLoadError(e.toMessage()) }
        }
    }

    private fun launchCleanup(kind: StorageCleanupKind) {
        cleanupJob?.cancel()
        cleanupJob = viewModelScope.launch {
            val retentionDays = _state.value.retentionDays
            try {
                val result = withContext(ioDispatcher) {
                    runStorageCleanup(gateway, kind, retentionDays)
                }
                // 清理后重扫，确保概览反映实际释放量；重扫失败不影响已完成的清理结果。
                val refreshed = try {
                    withContext(ioDispatcher) { gateway.scan(retentionDays) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                _state.update { it.cleanupSucceeded(result, refreshed) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.cleanupFailed(kind, e.toMessage()) }
            }
        }
    }

    private fun Throwable.toMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    companion object {
        /**
         * 生产入口：包装 `AppContainer` 的 `AudioStorage` / `ProjectRepository`。
         *
         * [audioReferences] 必须返回 DB 中仍被引用的音频相对路径（`segments.audioRelPath`）；
         * 不提供默认空集合，避免把仍在使用的音频误判为孤儿。
         */
        fun fromProduction(
            audioStorage: AudioStorage,
            projectRepository: ProjectRepository,
            audioReferences: suspend () -> Set<String>,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): StorageViewModel = StorageViewModel(
            gateway = StorageMaintenanceGateway(
                audioStorage = audioStorage,
                projectRepository = projectRepository,
                references = ProjectStorageReferenceProvider(
                    projectRepository = projectRepository,
                    audioReferences = audioReferences,
                ),
            ),
            ioDispatcher = ioDispatcher,
        )
    }
}
