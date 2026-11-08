package com.estimatefaster.audioforge.feature.export

import java.util.Locale

/**
 * 导出范围（P5-11）。
 *
 * 三种范围互斥；选中章节由 [ExportUiState.selectedChapters] 从“已完成音频”的章节里
 * 推导，范围本身不保存列表，避免“筛选结果”和“章节快照”两份状态漂移。
 */
enum class ExportRange {
    /** 所有已完成音频的章节。 */
    AllCompleted,

    /** 当前正在播放/查看的一章。 */
    CurrentChapter,

    /** 用户输入的起止章号（1 基，两端都包含）。 */
    ChapterRange,
}

/** 导出阶段；Sheet 按阶段切换选择、进度与结果布局。 */
enum class ExportPhase {
    Selection,
    Exporting,
    Result,
}

/**
 * 范围校验错误。
 *
 * 只放类型，不在 ViewModel 里拼中文；UI 从 `strings.xml` 取文案。
 */
enum class ExportValidationError {
    /** 项目没有章节。 */
    NoChapters,

    /** 还没有任何章节完成音频。 */
    NoCompleted,

    /** 当前章没有完成音频。 */
    CurrentChapterNotCompleted,

    /** 起始章为空、非数字或超出 1..章数。 */
    InvalidStart,

    /** 结束章为空、非数字或超出 1..章数。 */
    InvalidEnd,

    /** 起始章大于结束章。 */
    StartAfterEnd,

    /** 起止范围合法，但范围内没有已完成音频的章节。 */
    NoCompletedInRange,
}

/**
 * 导出列表里的一章。
 *
 * [chapterIdx] 是数据库的 0 基章节序号，原样传给 [ExportGateway.exportChapter]；
 * [number] 是 UI 展示用的 1 基章节号。[durationMs]/[sizeBytes] 允许为 0（文件不存在或
 * 尚未探测），UI 显示“未知”。[audioDone] 为 false 的章节不参与导出。
 */
data class ExportChapterUi(
    val chapterIdx: Int,
    val title: String,
    val audioDone: Boolean = false,
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
) {
    val number: Int
        get() = chapterIdx + 1
}

/**
 * 一次成功导出的文件。
 *
 * [uri] 是 `Uri.toString()` 的结果，而不是 `android.net.Uri`：ViewModel 与纯 JVM 测试
 * 不依赖 Android，Sheet 在真正分享时再转回 Uri。
 */
data class ExportedFile(
    val chapterIdx: Int,
    val title: String,
    val displayName: String,
    val uri: String,
)

/** 单章导出失败；[message] 保留 ExportManager/网关的原文，便于展示与诊断。 */
data class ExportFailure(
    val chapterIdx: Int,
    val title: String,
    val message: String,
)

/**
 * 导出 Sheet 的完整状态。
 *
 * 选择相关字段只保存“范围类型 + 起止输入”，选中章节、汇总与校验都从 [chapters] 推导，
 * 因此章节状态变化后“全部已完成”会自动更新。
 *
 * [succeeded] 按章节序号排序累积；重试只重跑 [failures]，已成功的文件不会重复导出。
 * [cancelled] 只表示本次导出被用户取消；已发布到 MediaStore/应用外部目录的文件保留，
 * 未开始的章节不会写入。
 */
data class ExportUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val chapters: List<ExportChapterUi> = emptyList(),
    val currentChapterIdx: Int? = null,
    val range: ExportRange = ExportRange.AllCompleted,
    val startText: String = "",
    val endText: String = "",
    val phase: ExportPhase = ExportPhase.Selection,
    val total: Int = 0,
    val completed: Int = 0,
    val currentChapterTitle: String? = null,
    val succeeded: List<ExportedFile> = emptyList(),
    val failures: List<ExportFailure> = emptyList(),
    val cancelled: Boolean = false,
) {

    /** 已完成音频的章节；“已完成”的唯一判定是 [ExportChapterUi.audioDone]。 */
    val completedChapters: List<ExportChapterUi>
        get() = chapters.filter { it.audioDone }

    /** 加载完成且没有任何已完成章节时显示空态。 */
    val isEmpty: Boolean
        get() = !loading && loadError == null && completedChapters.isEmpty()

    /** 当前章；索引对不上时为 null。 */
    val currentChapter: ExportChapterUi?
        get() = currentChapterIdx?.let { idx ->
            chapters.firstOrNull { it.chapterIdx == idx }
        }

    /**
     * 当前范围实际选中的章节（只含已完成音频的章，按章节顺序）。
     *
     * 范围非法时返回空列表；调用方用 [selectionError] 显示原因。
     */
    val selectedChapters: List<ExportChapterUi>
        get() = when (range) {
            ExportRange.AllCompleted -> completedChapters
            ExportRange.CurrentChapter -> listOfNotNull(currentChapter?.takeIf { it.audioDone })
            ExportRange.ChapterRange -> {
                val start = startText.trim().toIntOrNull()
                val end = endText.trim().toIntOrNull()
                if (start == null || end == null || start > end) {
                    emptyList()
                } else {
                    completedChapters.filter { it.number in start..end }
                }
            }
        }

    val selectedCount: Int
        get() = selectedChapters.size

    /** 选中章节的估算总时长；未知按 0 计。 */
    val selectedDurationMs: Long
        get() = selectedChapters.sumOf { it.durationMs.coerceAtLeast(0L) }

    /** 选中章节的估算总体积；未知按 0 计。 */
    val selectedSizeBytes: Long
        get() = selectedChapters.sumOf { it.sizeBytes.coerceAtLeast(0L) }

    /**
     * 范围校验；null 表示可以开始导出。
     *
     * 加载中或加载失败时返回 null（错误由 [loadError] 表达）；空项目/无已完成章节优先于
     * 范围错误，避免在空态上叠加“起止章非法”。
     */
    val selectionError: ExportValidationError?
        get() {
            if (loading || loadError != null) return null
            if (chapters.isEmpty()) return ExportValidationError.NoChapters
            if (completedChapters.isEmpty()) return ExportValidationError.NoCompleted
            return when (range) {
                ExportRange.AllCompleted -> null

                ExportRange.CurrentChapter ->
                    if (currentChapter?.audioDone == true) {
                        null
                    } else {
                        ExportValidationError.CurrentChapterNotCompleted
                    }

                ExportRange.ChapterRange -> {
                    val start = startText.trim().toIntOrNull()
                    val end = endText.trim().toIntOrNull()
                    when {
                        start == null || start !in 1..chapters.size ->
                            ExportValidationError.InvalidStart

                        end == null || end !in 1..chapters.size ->
                            ExportValidationError.InvalidEnd

                        start > end -> ExportValidationError.StartAfterEnd

                        selectedChapters.isEmpty() ->
                            ExportValidationError.NoCompletedInRange

                        else -> null
                    }
                }
            }
        }

    val canStart: Boolean
        get() = !loading &&
            loadError == null &&
            phase == ExportPhase.Selection &&
            selectionError == null &&
            selectedCount > 0

    val isExporting: Boolean
        get() = phase == ExportPhase.Exporting

    val isFinished: Boolean
        get() = phase == ExportPhase.Result

    /** 已完成章节数 / 本次总数，已限制在 0..1；总数为 0 时为 0。 */
    val progress: Float
        get() = if (total <= 0) {
            0f
        } else {
            (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }

    val pendingCount: Int
        get() = (total - completed).coerceAtLeast(0)

    val hasFailures: Boolean
        get() = failures.isNotEmpty()

    /** 分享用 Uri 字符串列表；生产 ViewModel 按章节序号累积。 */
    val shareUris: List<String>
        get() = succeeded.map { it.uri }

    val canShare: Boolean
        get() = phase == ExportPhase.Result && shareUris.isNotEmpty()

    val canRetryFailed: Boolean
        get() = phase == ExportPhase.Result && failures.isNotEmpty()
}

/**
 * 毫秒 → `m:ss` 或 `h:mm:ss`；负数按 0，秒向下取整。
 *
 * 与播放器的时间格式规则一致，但导出模块自己保留一份，避免跨 feature 依赖。
 */
fun formatExportDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / MILLIS_PER_SECOND
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

/**
 * 字节 → 人类可读体积（B/KB/MB/GB）。
 *
 * 0 或负数返回 `0 B`；KB 及以上保留一位小数，固定 [Locale.ROOT] 避免小数逗号。
 */
fun formatExportFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    var value = bytes.toDouble()
    var unit = 0
    while (value >= BYTES_PER_UNIT && unit < SIZE_UNITS.lastIndex) {
        value /= BYTES_PER_UNIT
        unit++
    }
    return if (unit == 0) {
        "$bytes B"
    } else {
        String.format(Locale.ROOT, "%.1f %s", value, SIZE_UNITS[unit])
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val BYTES_PER_UNIT = 1024.0
private val SIZE_UNITS = listOf("B", "KB", "MB", "GB")
