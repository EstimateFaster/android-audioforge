package com.estimatefaster.audioforge.feature.storage

import java.util.Locale

/** 保留期下限（天）。低于该值时无法保护正在写入/刚生成的文件。 */
const val MIN_RETENTION_DAYS: Int = 7

/** 保留期上限（天）。 */
const val MAX_RETENTION_DAYS: Int = 365

/** 默认保留期（天），与 `StorageMaintenance.DEFAULT_RETENTION_DAYS` 一致。 */
const val DEFAULT_RETENTION_DAYS: Int = 30

/** 存储分区；顺序即界面展示顺序。 */
enum class StorageArea {
    AUDIO,
    IMPORTS,
    EXPORTS,
}

/** 一个分区的占用。 */
data class StorageAreaUsage(
    val area: StorageArea,
    val bytes: Long,
)

/**
 * 按项目的占用。
 *
 * [title] 为 null 表示 `audio/<novelId>` 下仍有文件、但项目已从 DB 删除；
 * 界面用「未关联项目（#id）」兜底，避免把残余文件静默隐藏。
 */
data class StorageProjectUsage(
    val novelId: Long,
    val title: String?,
    val bytes: Long,
    val chapterCount: Int,
)

/**
 * 存储概览：一次扫描得到的不可变快照。
 *
 * 总占用与分区来自 `StorageStats`；孤儿/过期统计来自 `StorageReport`；
 * 项目名与章节数来自项目仓库的聚合结果。界面不直接触碰文件系统或 DB。
 */
data class StorageOverview(
    val totalBytes: Long,
    val usableBytes: Long,
    val audioBytes: Long,
    val importsBytes: Long,
    val exportsBytes: Long,
    val fileCount: Int,
    val projects: List<StorageProjectUsage>,
    val orphanCount: Int,
    val orphanBytes: Long,
    val expiredCount: Int,
    val expiredBytes: Long,
    val scanFailureCount: Int,
    val scannedAt: Long,
) {

    /** 三个分区，顺序固定，便于 UI 与测试断言。 */
    val areas: List<StorageAreaUsage>
        get() = listOf(
            StorageAreaUsage(StorageArea.AUDIO, audioBytes),
            StorageAreaUsage(StorageArea.IMPORTS, importsBytes),
            StorageAreaUsage(StorageArea.EXPORTS, exportsBytes),
        )

    fun bytesFor(area: StorageArea): Long = when (area) {
        StorageArea.AUDIO -> audioBytes
        StorageArea.IMPORTS -> importsBytes
        StorageArea.EXPORTS -> exportsBytes
    }

    /** 分区占总占用的比例 0..1；总占用为 0 时返回 0。 */
    fun shareOf(area: StorageArea): Float = fractionOf(bytesFor(area))

    fun fractionOf(bytes: Long): Float {
        if (totalBytes <= 0L) return 0f
        return (bytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    /** 没有任何文件、也没有任何项目占用。 */
    val isEmpty: Boolean
        get() = totalBytes <= 0L && projects.isEmpty()

    val hasOrphans: Boolean
        get() = orphanCount > 0

    val hasExpired: Boolean
        get() = expiredCount > 0
}

/** 两种清理动作。 */
enum class StorageCleanupKind {
    /** 清理无引用文件（含导出残留、`.tmp`）。 */
    ORPHANS,

    /** 按保留期清理项目音频（仍被引用的会被跳过）。 */
    EXPIRED,
}

/**
 * 一次清理的结果（已折算成 UI 可直接展示的数字）。
 *
 * [freedBytes] 是删除前文件大小之和；[failureCount] > 0 时界面提供「重试清理」。
 */
data class StorageCleanupResult(
    val kind: StorageCleanupKind,
    val freedBytes: Long,
    val deletedCount: Int,
    val failureCount: Int,
    val skippedReferencedCount: Int,
    val skippedTooRecentCount: Int,
    val skippedMissingCount: Int,
) {
    val hasFailures: Boolean
        get() = failureCount > 0

    val deletedNothing: Boolean
        get() = deletedCount == 0
}

/** 保留期输入错误；文案由 `strings.xml` 映射，状态层只保留枚举。 */
enum class RetentionError {
    /** 空输入。 */
    Missing,

    /** 不是整数。 */
    NotAnInteger,

    /** 超出 7..365。 */
    OutOfRange,
}

/** 保留期解析结果；[days] 非空表示合法。 */
data class RetentionValidation(
    val days: Int?,
    val error: RetentionError?,
) {
    val isValid: Boolean
        get() = days != null && error == null
}

/** 解析并校验保留期输入；不合法时 [RetentionValidation.days] 为 null。 */
fun validateRetention(raw: String): RetentionValidation {
    val text = raw.trim()
    if (text.isEmpty()) {
        return RetentionValidation(days = null, error = RetentionError.Missing)
    }
    val days = text.toIntOrNull()
        ?: return RetentionValidation(days = null, error = RetentionError.NotAnInteger)
    if (days !in MIN_RETENTION_DAYS..MAX_RETENTION_DAYS) {
        return RetentionValidation(days = null, error = RetentionError.OutOfRange)
    }
    return RetentionValidation(days = days, error = null)
}

/**
 * 存储页的不可变 UI 状态。
 *
 * 状态迁移全部写成纯函数（见文件末尾），ViewModel 只负责调用、调度 IO 与合并结果，
 * 因此确认对话框、并发去重、成功/失败/重试都能在纯 JVM 测试里覆盖。
 */
data class StorageUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val overview: StorageOverview? = null,
    val retentionInput: String = DEFAULT_RETENTION_DAYS.toString(),
    val retentionDays: Int = DEFAULT_RETENTION_DAYS,
    val retentionError: RetentionError? = null,
    val pendingCleanup: StorageCleanupKind? = null,
    val cleaning: StorageCleanupKind? = null,
    val lastCleanupKind: StorageCleanupKind? = null,
    val lastCleanup: StorageCleanupResult? = null,
    val cleanupError: String? = null,
) {

    val isLoading: Boolean
        get() = loading

    /** 首次扫描完成、没有任何错误，但确实没有文件。 */
    val isEmpty: Boolean
        get() = !loading && loadError == null && overview?.isEmpty == true

    val isReady: Boolean
        get() = !loading && loadError == null && overview != null

    val isBusy: Boolean
        get() = cleaning != null

    /** 任一清理动作的前置条件；具体动作还会检查各自的可清理数量。 */
    val canStartCleanup: Boolean
        get() = isReady && cleaning == null

    val canCleanOrphans: Boolean
        get() = canStartCleanup && (overview?.orphanCount ?: 0) > 0

    val canCleanExpired: Boolean
        get() = canStartCleanup &&
            retentionError == null &&
            (overview?.expiredCount ?: 0) > 0

    /** 清理抛异常，或清理部分失败时允许重试；页面必须仍处于可操作状态。 */
    val canRetryCleanup: Boolean
        get() = isReady &&
            cleaning == null &&
            lastCleanupKind != null &&
            (lastCleanupKind != StorageCleanupKind.EXPIRED || retentionError == null) &&
            (cleanupError != null || lastCleanup?.hasFailures == true)

    val hasResult: Boolean
        get() = lastCleanup != null || cleanupError != null
}

// ---------------------------------------------------------------------------
// 状态迁移：纯函数，ViewModel 与测试共用。
// ---------------------------------------------------------------------------

/** 开始/重新开始统计；保留当前保留期与已展示数据，避免闪烁。 */
fun StorageUiState.markLoading(): StorageUiState = copy(
    loading = true,
    loadError = null,
)

/** 统计成功：整体替换概览。 */
fun StorageUiState.withOverview(next: StorageOverview): StorageUiState = copy(
    loading = false,
    loadError = null,
    overview = next,
)

/** 统计失败：保留旧概览（如果有），只标记错误。 */
fun StorageUiState.withLoadError(message: String): StorageUiState = copy(
    loading = false,
    loadError = message,
)

/**
 * 保留期输入变化。
 *
 * 合法输入立即更新 [StorageUiState.retentionDays]；非法输入保留上一次合法值，
 * 只设置 [StorageUiState.retentionError]，因此拖动滑块/清空输入框不会让清理动作
 * 用到一个未定义的保留期。
 */
fun StorageUiState.withRetentionInput(raw: String): StorageUiState {
    val validation = validateRetention(raw)
    return copy(
        retentionInput = raw,
        retentionDays = validation.days ?: retentionDays,
        retentionError = validation.error,
    )
}

/** 滑块变化：收敛到 7..365 并同步输入框。 */
fun StorageUiState.withRetentionDays(days: Int): StorageUiState {
    val clamped = days.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)
    return copy(
        retentionInput = clamped.toString(),
        retentionDays = clamped,
        retentionError = null,
    )
}

/**
 * 用户点击清理按钮：只打开确认对话框，不直接开始。
 *
 * 清理中/已在确认中/未加载完成时是 no-op；过期清理还要求保留期合法。
 */
fun StorageUiState.requestCleanup(kind: StorageCleanupKind): StorageUiState {
    if (!canStartCleanup || pendingCleanup != null) return this
    if (kind == StorageCleanupKind.EXPIRED && retentionError != null) return this
    return copy(pendingCleanup = kind)
}

/** 关闭确认对话框。 */
fun StorageUiState.cancelCleanup(): StorageUiState = copy(pendingCleanup = null)

/**
 * 确认后真正开始清理。
 *
 * 返回 null 表示被并发去重拒绝：已有清理在跑、页面未就绪或保留期非法。
 * ViewModel 据此直接返回，不会启动第二个协程，也不会覆盖第一个清理的结果。
 */
fun StorageUiState.beginCleanup(kind: StorageCleanupKind): StorageUiState? {
    if (!isReady || cleaning != null) return null
    if (kind == StorageCleanupKind.EXPIRED && retentionError != null) return null
    return copy(
        pendingCleanup = null,
        cleaning = kind,
        lastCleanupKind = kind,
        lastCleanup = null,
        cleanupError = null,
    )
}

/** 清理成功：清掉进度与错误，展示结果；[refreshedOverview] 为空时保留旧概览。 */
fun StorageUiState.cleanupSucceeded(
    result: StorageCleanupResult,
    refreshedOverview: StorageOverview? = null,
): StorageUiState = copy(
    cleaning = null,
    pendingCleanup = null,
    lastCleanup = result,
    cleanupError = null,
    overview = refreshedOverview ?: overview,
)

/** 清理失败（异常）：保留动作类型，供「重试清理」直接复用。 */
fun StorageUiState.cleanupFailed(
    kind: StorageCleanupKind,
    message: String,
): StorageUiState = copy(
    cleaning = null,
    pendingCleanup = null,
    lastCleanup = null,
    cleanupError = message,
    lastCleanupKind = kind,
)

/** 关闭结果卡；关闭后不再提供重试入口。 */
fun StorageUiState.dismissCleanupResult(): StorageUiState = copy(
    lastCleanup = null,
    cleanupError = null,
    lastCleanupKind = null,
)

// ---------------------------------------------------------------------------
// 格式化
// ---------------------------------------------------------------------------

/**
 * 人类可读的字节数：B / KB / MB / GB，保留一位小数。
 *
 * 使用 1024 进制（与 `AudioStorage`/`StorageMaintenance` 的 MB 口径一致），
 * `Locale.ROOT` 保证小数点不受系统语言影响。
 */
fun formatByteSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val value = bytes.toDouble()
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        value < kb -> "${bytes} B"
        value < mb -> formatUnit(value / kb, "KB")
        value < gb -> formatUnit(value / mb, "MB")
        else -> formatUnit(value / gb, "GB")
    }
}

private fun formatUnit(value: Double, unit: String): String =
    String.format(Locale.ROOT, "%.1f %s", value, unit)
