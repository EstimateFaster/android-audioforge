package com.estimatefaster.audioforge.feature.tasks

import com.estimatefaster.audioforge.data.db.JobEntity
import com.estimatefaster.audioforge.data.db.JobStatus
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 任务页最多展示的历史条数；更早的结果仍在库里，只是不在这里铺开。 */
const val DEFAULT_TASK_HISTORY_LIMIT: Int = 20

/** 历史错误摘要的最大长度（含省略号）；失败原因往往很长，列表里只给一眼可读的摘要。 */
const val DEFAULT_ERROR_SUMMARY_LIMIT: Int = 120

/** 粗略 ETA 的上限：进度极低时按线性外推会得到离谱数字，这里封顶 24 小时。 */
const val MAX_ESTIMATED_REMAINING_MS: Long = 24L * 60L * 60L * 1000L

private val statsJsonParser = Json { ignoreUnknownKeys = true }
private val whitespaceRegex = Regex("\\s+")

/**
 * `jobs.statsJson` 里任务页关心的计数（§11.3）。
 *
 * 解析规则刻意宽松：缺失 key、负数、非数字、非对象 JSON 都按 0 处理，
 * 一条脏统计不应该让整个任务页进入错误态。字段顺序与计划一致：
 * `chaptersTotal/chaptersDone/segTotal/segDone/ttsOk/ttsFailed`。
 */
data class TaskStats(
    val chaptersTotal: Int = 0,
    val chaptersDone: Int = 0,
    val segTotal: Int = 0,
    val segDone: Int = 0,
    val ttsOk: Int = 0,
    val ttsFailed: Int = 0,
) {
    /** 章节进度 0..1；[chaptersTotal] 为 0 时按 0 处理。 */
    val chapterProgress: Float
        get() = progressRatio(chaptersDone, chaptersTotal)

    /** 片段进度 0..1；[segTotal] 为 0 时按 0 处理。 */
    val segmentProgress: Float
        get() = progressRatio(segDone, segTotal)

    /**
     * 任务卡只展示一个进度值：有片段统计时用片段（粒度更细），否则退回章节。
     * 两者都缺失时为 0，UI 显示“等待进度”。
     */
    val progress: Float
        get() = if (segTotal > 0) segmentProgress else chapterProgress

    /** 0..100 的整数百分比（四舍五入）。 */
    val percent: Int
        get() = (progress * 100f).roundToInt()

    /** 是否出现过 TTS 计数；为 false 时 UI 不显示语音成功/失败一行。 */
    val hasTtsCounts: Boolean
        get() = ttsOk > 0 || ttsFailed > 0

    companion object {
        val EMPTY: TaskStats = TaskStats()

        /** 容错解析；任何异常/脏值都退化为 0，不抛出。 */
        fun parse(statsJson: String?): TaskStats = parseStats(statsJson)
    }
}

/**
 * 任务的 wire 状态（`jobs.status`）→ UI 枚举。
 *
 * 大小写/空白不敏感；未知值落到 [Unknown]，UI 仍能用 `StatusKind.Task` 显示“未知”，
 * 不会把未知状态误报成成功/失败。
 */
enum class TaskStatusUi(val wire: String) {
    Pending(JobStatus.PENDING),
    Queued(JobStatus.QUEUED),
    Running(JobStatus.RUNNING),
    Done(JobStatus.DONE),
    Error(JobStatus.ERROR),
    Cancelled(JobStatus.CANCELLED),
    Unknown(""),
    ;

    /** queued/running 都可以停止。 */
    val isActive: Boolean
        get() = this == Queued || this == Running

    /** done/error/cancelled 是历史状态。 */
    val isTerminal: Boolean
        get() = this == Done || this == Error || this == Cancelled

    companion object {
        fun from(raw: String?): TaskStatusUi {
            val normalized = raw?.trim()?.lowercase(Locale.ROOT)
            if (normalized.isNullOrEmpty()) return Unknown
            return entries.firstOrNull { it.wire == normalized } ?: Unknown
        }
    }
}

/** `jobs.mode` → 中文文案的资源选择只发生在 UI；纯逻辑层只做枚举映射。 */
enum class TaskModeUi(val wire: String) {
    Full("full"),
    Parse("parse"),
    Render("render"),
    Unknown(""),
    ;

    companion object {
        fun from(raw: String?): TaskModeUi {
            val normalized = raw?.trim()?.lowercase(Locale.ROOT)
            if (normalized.isNullOrEmpty()) return Unknown
            return entries.firstOrNull { it.wire == normalized } ?: Unknown
        }
    }
}

/**
 * 一个项目的任务快照：当前活跃任务 + 最近一次已结束任务。
 *
 * 用纯数据而非 `JobEntity?` 的命名参数包一层，测试可以只构造需要的字段；
 * [novelTitle] 来自项目摘要（`novels.title`），避免任务页为每行再查一次项目表。
 */
data class TaskJobSnapshot(
    val novelId: Long,
    val novelTitle: String,
    val active: JobEntity? = null,
    val lastFinished: JobEntity? = null,
)

/**
 * 当前任务卡（或队列中的一行）。
 *
 * [elapsedMs] 与 [etaMs] 在构建时按注入的“现在”算一次；长任务每次进度写库都会
 * 重新构建，因此不需要 UI 自己轮询时钟。进度、百分比、停止态都从统计/状态派生。
 */
data class TaskCardUi(
    val jobId: Long,
    val novelId: Long,
    val novelTitle: String,
    val mode: TaskModeUi,
    val status: TaskStatusUi,
    val stats: TaskStats,
    val currentTitle: String,
    val startedAt: Long,
    val elapsedMs: Long,
    val etaMs: Long?,
    val stopRequested: Boolean,
) {
    val progress: Float
        get() = stats.progress

    val percent: Int
        get() = stats.percent

    /** 已请求停止但 Worker 还没收尾。 */
    val isStopping: Boolean
        get() = stopRequested && status.isActive
}

/**
 * 最近任务的一行。
 *
 * [durationMs] 由 finishedAt - startedAt 得到；时钟回拨/脏数据时收敛到 0。
 * [errorSummary] 已折叠空白并截断，可直接展示；成功/取消时为 null。
 */
data class TaskHistoryUi(
    val jobId: Long,
    val novelId: Long,
    val novelTitle: String,
    val mode: TaskModeUi,
    val status: TaskStatusUi,
    val stats: TaskStats,
    val startedAt: Long,
    val finishedAt: Long,
    val durationMs: Long,
    val errorSummary: String?,
) {
    val percent: Int
        get() = stats.percent
}

/**
 * 任务页不可变状态。
 *
 * 排序语义：
 * - 同一时刻最多显示一个“当前任务”：多条 running 时取 `startedAt` 最新的一条；
 * - [queued] 里其余 running（跨书并发）排在 queued 前面，queued 按 `startedAt` 升序（FIFO）；
 * - [recent] 按 `finishedAt` 倒序，`finishedAt` 缺失的排最后，再按 job id 倒序。
 */
data class TasksUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val current: TaskCardUi? = null,
    val queued: List<TaskCardUi> = emptyList(),
    val recent: List<TaskHistoryUi> = emptyList(),
    val actionError: String? = null,
) {
    /** 只有 running 才算“正在运行”；queued 不算。 */
    val hasRunning: Boolean
        get() = current?.status == TaskStatusUi.Running

    val hasActive: Boolean
        get() = current != null || queued.isNotEmpty()

    /** 无活跃任务、也无历史：展示整页空态。 */
    val isEmpty: Boolean
        get() = !loading && loadError == null && current == null && queued.isEmpty() && recent.isEmpty()

    val hasHistory: Boolean
        get() = recent.isNotEmpty()

    /** 按项目找活跃任务；停止按钮只需要 novelId，由调用方回调 `onStop(novelId)`。 */
    fun taskFor(novelId: Long): TaskCardUi? =
        current?.takeIf { it.novelId == novelId }
            ?: queued.firstOrNull { it.novelId == novelId }
}

/**
 * 把“每个项目一个快照”的列表折叠成任务页状态。
 *
 * 纯函数：不碰 Android/协程，[now] 由 ViewModel 注入，便于测试 ETA 与耗时。
 *
 * @param historyLimit 最多保留的历史条数；<=0 表示不展示历史
 * @param errorSummaryLimit 错误摘要最大长度（含省略号）
 */
internal fun buildTasksUiState(
    snapshots: List<TaskJobSnapshot>,
    now: Long,
    historyLimit: Int = DEFAULT_TASK_HISTORY_LIMIT,
    errorSummaryLimit: Int = DEFAULT_ERROR_SUMMARY_LIMIT,
): TasksUiState {
    val activePairs = snapshots.mapNotNull { snapshot ->
        val job = snapshot.active ?: return@mapNotNull null
        if (!TaskStatusUi.from(job.status).isActive) return@mapNotNull null
        snapshot to job
    }

    val running = activePairs
        .filter { TaskStatusUi.from(it.second.status) == TaskStatusUi.Running }
        .sortedWith(
            compareByDescending<Pair<TaskJobSnapshot, JobEntity>> { it.second.startedAt }
                .thenByDescending { it.second.id },
        )
    val waiting = activePairs
        .filter { TaskStatusUi.from(it.second.status) == TaskStatusUi.Queued }
        .sortedWith(
            compareBy<Pair<TaskJobSnapshot, JobEntity>> { it.second.startedAt }
                .thenBy { it.second.id },
        )

    val current = running.firstOrNull()?.let { (snapshot, job) -> toTaskCard(snapshot, job, now) }
    val queue = (running.drop(1) + waiting).map { (snapshot, job) ->
        toTaskCard(snapshot, job, now)
    }

    val recent = snapshots.mapNotNull { snapshot ->
        val job = snapshot.lastFinished ?: return@mapNotNull null
        if (!TaskStatusUi.from(job.status).isTerminal) return@mapNotNull null
        snapshot to job
    }
        .sortedWith(
            compareByDescending<Pair<TaskJobSnapshot, JobEntity>> {
                it.second.finishedAt ?: Long.MIN_VALUE
            }.thenByDescending { it.second.id },
        )
        .take(historyLimit.coerceAtLeast(0))
        .map { (snapshot, job) ->
            toTaskHistory(snapshot, job, errorSummaryLimit)
        }

    return TasksUiState(
        loading = false,
        current = current,
        queued = queue,
        recent = recent,
    )
}

/** 当前任务卡：运行中才计算 ETA，已请求停止/排队中不估算。 */
private fun toTaskCard(
    snapshot: TaskJobSnapshot,
    job: JobEntity,
    now: Long,
): TaskCardUi {
    val stats = TaskStats.parse(job.statsJson)
    val status = TaskStatusUi.from(job.status)
    return TaskCardUi(
        jobId = job.id,
        novelId = snapshot.novelId,
        novelTitle = snapshot.novelTitle,
        mode = TaskModeUi.from(job.mode),
        status = status,
        stats = stats,
        currentTitle = job.currentTitle.trim(),
        startedAt = job.startedAt,
        elapsedMs = (now - job.startedAt).coerceAtLeast(0L),
        etaMs = if (status == TaskStatusUi.Running && !job.stopRequested) {
            estimateRemainingMs(job.startedAt, now, stats.progress)
        } else {
            null
        },
        stopRequested = job.stopRequested,
    )
}

private fun toTaskHistory(
    snapshot: TaskJobSnapshot,
    job: JobEntity,
    errorSummaryLimit: Int,
): TaskHistoryUi {
    val finishedAt = job.finishedAt ?: job.startedAt
    return TaskHistoryUi(
        jobId = job.id,
        novelId = snapshot.novelId,
        novelTitle = snapshot.novelTitle,
        mode = TaskModeUi.from(job.mode),
        status = TaskStatusUi.from(job.status),
        stats = TaskStats.parse(job.statsJson),
        startedAt = job.startedAt,
        finishedAt = finishedAt,
        durationMs = (finishedAt - job.startedAt).coerceAtLeast(0L),
        errorSummary = summarizeTaskError(job.lastError, errorSummaryLimit),
    )
}

/**
 * 按“已用时间 / 已完成比例”线性外推剩余时间。
 *
 * 只在 `0 < progress < 1` 且 `now > startedAt` 时有意义；否则返回 null，
 * UI 显示“计算中”。结果封顶 [MAX_ESTIMATED_REMAINING_MS]，避免极端值。
 */
internal fun estimateRemainingMs(startedAt: Long, now: Long, progress: Float): Long? {
    if (!progress.isFinite() || progress <= 0f || progress >= 1f) return null
    val elapsed = now - startedAt
    if (elapsed <= 0L) return null
    val remaining = (
        elapsed.toDouble() * (1.0 - progress.toDouble()) / progress.toDouble()
        ).roundToLong()
    return remaining.coerceIn(0L, MAX_ESTIMATED_REMAINING_MS)
}

/**
 * 时钟式时长："M:SS"，超过 1 小时为 "H:MM:SS"。
 *
 * 不包含中文单位，耗时/预计剩余的句式由 `strings.xml` 提供；
 * null/负数（未知）返回 null，UI 自行决定是否省略该行。
 */
fun formatTaskDuration(durationMs: Long?): String? {
    if (durationMs == null || durationMs < 0L) return null
    val totalSeconds = durationMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

/**
 * 错误摘要：折叠空白、去首尾，超长时截断并追加省略号（总长度不超过 [limit]）。
 *
 * 空/纯空白返回 null，调用方据此隐藏错误行。
 */
internal fun summarizeTaskError(
    message: String?,
    limit: Int = DEFAULT_ERROR_SUMMARY_LIMIT,
): String? {
    val normalized = message
        ?.replace(whitespaceRegex, " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    val max = limit.coerceAtLeast(1)
    if (normalized.length <= max) return normalized
    return normalized.take(max - 1).trimEnd() + "…"
}

private fun parseStats(statsJson: String?): TaskStats {
    val text = statsJson?.trim().orEmpty()
    if (text.isEmpty()) return TaskStats.EMPTY
    val obj = runCatching { statsJsonParser.parseToJsonElement(text) as? JsonObject }
        .getOrNull()
        ?: return TaskStats.EMPTY
    return TaskStats(
        chaptersTotal = obj.intValue("chaptersTotal"),
        chaptersDone = obj.intValue("chaptersDone"),
        segTotal = obj.intValue("segTotal"),
        segDone = obj.intValue("segDone"),
        ttsOk = obj.intValue("ttsOk"),
        ttsFailed = obj.intValue("ttsFailed"),
    )
}

/**
 * 宽松读取计数：
 * - 非数字/布尔/null/对象 → 0；
 * - 负数 → 0；
 * - 小数（例如 `"2.9"`）→ 截断为 2；
 * - 超过 Int 上限 → 收敛到 [Int.MAX_VALUE]。
 */
private fun JsonObject.intValue(key: String): Int {
    val primitive = this[key] as? JsonPrimitive ?: return 0
    val raw = primitive.content.trim()
    if (raw.isEmpty()) return 0
    val value = raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong() ?: return 0
    return value.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

private fun progressRatio(done: Int, total: Int): Float {
    if (total <= 0) return 0f
    return (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}
