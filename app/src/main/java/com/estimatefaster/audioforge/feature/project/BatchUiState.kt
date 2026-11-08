package com.estimatefaster.audioforge.feature.project

import com.estimatefaster.audioforge.data.db.ChapterBrief
import com.estimatefaster.audioforge.domain.jobs.BatchParams
import com.estimatefaster.audioforge.domain.jobs.GenerationMode

/**
 * 批次 Sheet 的三种生成模式（P4-06 / 执行计划 §12.4 S05）。
 *
 * [wire] 必须与 `GenerationMode.wire` 一致，直接写入 [BatchParams.mode] 和
 * `jobs.mode`；[requiresLlm]/[requiresFish] 用于无 Key 时禁用开始。
 */
enum class BatchMode(
    val generationMode: GenerationMode,
    val minutesPerChapter: Int,
    val costCentsPerChapter: Int,
) {
    /** 先解析再渲染，两种 Key 都需要。 */
    Full(
        generationMode = GenerationMode.Full,
        minutesPerChapter = 3,
        costCentsPerChapter = 15,
    ),

    /** 只调用 LLM 解析。 */
    Parse(
        generationMode = GenerationMode.Parse,
        minutesPerChapter = 1,
        costCentsPerChapter = 2,
    ),

    /** 只调用 Fish Audio 合成。 */
    Render(
        generationMode = GenerationMode.Render,
        minutesPerChapter = 2,
        costCentsPerChapter = 10,
    );

    val wire: String get() = generationMode.wire

    val requiresLlm: Boolean get() = generationMode.parses

    val requiresFish: Boolean get() = generationMode.renders
}

/** 无 Key 时具体缺哪一类；UI 映射到 `strings.xml`。 */
enum class BatchKeyKind {
    Llm,
    Fish,
}

/**
 * 批次参数的前置校验错误（P4-06）。
 *
 * 只放类型，不拼中文：文案由 UI 从 `strings.xml` 取，ViewModel 保持纯 JVM 可测。
 * 无 Key 单独放在 [BatchUiState.missingKeys]，因为 UI 需要显示“去设置”入口。
 */
enum class BatchValidationError {
    /** 项目没有章节，或调用方没有传入任何章节。 */
    NoChapters,

    /** 起始章为空/非整数/不在 1..章数 内。 */
    InvalidStart,

    /** 结束章为空/非整数/不在 1..章数 内。 */
    InvalidEnd,

    /** 起止都合法，但起始章大于结束章。 */
    StartAfterEnd,

    /** 选中章节数超过 [BatchParams.DEFAULT_MAX_BATCH]；提示用户分批。 */
    TooManyChapters,

    /** 并发不在 1..[BatchParams.MAX_CONCURRENCY] 内。 */
    InvalidConcurrency,
}

/**
 * 批次 Sheet 需要的单章最小信息。
 *
 * 只依赖投影后的章节字段（id/number/两个状态），不读正文；2000 章也是轻量 O(n)。
 * [parseDone]/[audioDone] 用于计算“默认全部未完成”，[isComplete] 仍要求两者都完成。
 */
data class BatchChapterUi(
    val id: Long,
    /** 1 基章节序号（`ChapterBrief.idx` 是 0 基，[from] 会 +1）。 */
    val number: Int,
    val parseDone: Boolean = false,
    val audioDone: Boolean = false,
) {
    val isComplete: Boolean
        get() = parseDone && audioDone

    companion object {
        /** 从 `ChapterBrief` 投影，避免调用方重复写状态字符串判断。 */
        fun from(chapter: ChapterBrief): BatchChapterUi = BatchChapterUi(
            id = chapter.id,
            number = chapter.idx + 1,
            parseDone = chapter.parseStatus.isDoneStatus(),
            audioDone = chapter.audioStatus.isDoneStatus(),
        )

        fun from(chapters: List<ChapterBrief>): List<BatchChapterUi> =
            chapters.map { from(it) }
    }
}

/** 开始动作的 UI 结果；对应 [com.estimatefaster.audioforge.domain.jobs.StartResult]。 */
sealed interface BatchStartStatus {

    data object Starting : BatchStartStatus

    data class Started(val jobId: Long) : BatchStartStatus

    data class AlreadyRunning(val jobId: Long) : BatchStartStatus

    data class Failure(val reason: String) : BatchStartStatus
}

/** 停止动作的 UI 结果；对应 [com.estimatefaster.audioforge.domain.jobs.StopResult]。 */
sealed interface BatchStopStatus {

    /** 请求已发出，协程仍在等待 `BatchRunner.stop` 返回。 */
    data object Requesting : BatchStopStatus

    /** `BatchRunner.stop` 已接受；Worker 会在下一个安全点收尾。 */
    data class Stopping(val jobId: Long) : BatchStopStatus

    data class NotRunning(val novelId: Long) : BatchStopStatus

    data class Failure(val reason: String) : BatchStopStatus
}

/**
 * 批次 Sheet 的不可变 UI 状态（P4-06）。
 *
 * 范围用字符串保存，允许“空/非数字/越界”这些中间输入被校验而不是被输入框吞掉；
 * [startChapter]/[endChapter] 是解析后的派生值。所有校验与展开都是纯函数，
 * 不依赖 Android/协程，测试可直接构造状态。
 *
 * [startText]/[endText] 默认由 [initial] 设为“全部未完成”的首尾章；没有任何未完成
 * 章节时回退到整本书。
 */
data class BatchUiState(
    val novelId: Long,
    val chapters: List<BatchChapterUi> = emptyList(),
    val mode: BatchMode = BatchMode.Full,
    val startText: String = DEFAULT_START,
    val endText: String = DEFAULT_END,
    val force: Boolean = false,
    val concurrency: Int = BatchParams.DEFAULT_CONCURRENCY,
    val hasLlmKey: Boolean = true,
    val hasFishKey: Boolean = true,
    val starting: Boolean = false,
    val stopping: Boolean = false,
    val startStatus: BatchStartStatus? = null,
    val stopStatus: BatchStopStatus? = null,
) {

    val chapterCount: Int
        get() = chapters.size

    /** 空串/非数字都是 null，交给 [validationErrors] 报错。 */
    val startChapter: Int?
        get() = startText.trim().toIntOrNull()

    val endChapter: Int?
        get() = endText.trim().toIntOrNull()

    /** 按 1 基序号过滤，保持章节原始顺序；范围非法时为空。 */
    val selectedChapters: List<BatchChapterUi>
        get() {
            val start = startChapter ?: return emptyList()
            val end = endChapter ?: return emptyList()
            if (chapterCount <= 0) return emptyList()
            if (start !in 1..chapterCount || end !in 1..chapterCount) return emptyList()
            if (start > end) return emptyList()
            return chapters.filter { it.number in start..end }
        }

    /** 批次实际入参：把起止范围展开为 chapterId 列表（含范围内的已完成章节）。 */
    val selectedChapterIds: List<Long>
        get() = selectedChapters.map { it.id }

    val selectedChapterCount: Int
        get() = selectedChapters.size

    /** 当前模式缺失的 Key；Full 缺两个时按 Llm、Fish 顺序返回。 */
    val missingKeys: List<BatchKeyKind>
        get() = buildList {
            if (mode.requiresLlm && !hasLlmKey) add(BatchKeyKind.Llm)
            if (mode.requiresFish && !hasFishKey) add(BatchKeyKind.Fish)
        }

    /** 除 Key 外的全部阻塞项；空列表表示范围/并发合法。 */
    val validationErrors: List<BatchValidationError>
        get() {
            if (chapters.isEmpty()) return listOf(BatchValidationError.NoChapters)

            val errors = mutableListOf<BatchValidationError>()
            val start = startChapter
            val end = endChapter
            if (start == null || start !in 1..chapterCount) {
                errors += BatchValidationError.InvalidStart
            }
            if (end == null || end !in 1..chapterCount) {
                errors += BatchValidationError.InvalidEnd
            }
            if (errors.isEmpty() && start != null && end != null && start > end) {
                errors += BatchValidationError.StartAfterEnd
            }
            if (errors.isEmpty() &&
                selectedChapterIds.size > BatchParams.DEFAULT_MAX_BATCH
            ) {
                errors += BatchValidationError.TooManyChapters
            }
            if (concurrency !in 1..BatchParams.MAX_CONCURRENCY) {
                errors += BatchValidationError.InvalidConcurrency
            }
            return errors
        }

    /** 范围/并发合法且当前模式所需的 Key 都已配置。 */
    val canStart: Boolean
        get() = !starting &&
            !stopping &&
            validationErrors.isEmpty() &&
            missingKeys.isEmpty()

    /** 停止按钮始终可点：对没有活跃批次的书会得到 NotRunning 反馈。 */
    val canStop: Boolean
        get() = !starting && !stopping

    /** 范围合法时才有估算；否则 UI 显示提示而不是 0。 */
    val hasEstimate: Boolean
        get() = validationErrors.isEmpty() && selectedChapterIds.isNotEmpty()

    val estimatedMinutes: Int
        get() = selectedChapterCount * mode.minutesPerChapter

    val estimatedCostCents: Int
        get() = selectedChapterCount * mode.costCentsPerChapter

    /** 元，两位小数；整数运算避免 Locale/浮点格式差异。 */
    val estimatedCostText: String
        get() {
            val cents = estimatedCostCents
            return "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
        }

    /**
     * 构造批次入参；范围/并发非法时返回 null。
     *
     * 注意：这里**不**检查 Key（Key 不是 `BatchParams` 的字段），但 [canStart]
     * 会同时检查 Key，所以正常路径不会把无 Key 的状态交给 Runner。
     */
    fun toBatchParams(): BatchParams? {
        val ids = selectedChapterIds
        if (ids.isEmpty() || validationErrors.isNotEmpty()) return null
        return BatchParams(
            novelId = novelId,
            chapterIds = ids,
            mode = mode.wire,
            force = force,
            concurrency = concurrency,
        )
    }

    companion object {
        const val DEFAULT_START: String = "1"
        const val DEFAULT_END: String = "1"

        /**
         * 初始状态：起止默认覆盖“全部未完成”的首尾章。
         *
         * 未完成按完整生成（解析 + 音频都 done 才算完成）判断；没有未完成章节时
         * 回退到整本书，用户可打开“强制重跑”。
         */
        fun initial(
            novelId: Long,
            chapters: List<BatchChapterUi>,
            defaultConcurrency: Int = BatchParams.DEFAULT_CONCURRENCY,
            hasLlmKey: Boolean = true,
            hasFishKey: Boolean = true,
        ): BatchUiState {
            val ordered = chapters.sortedBy { it.number }
            val unfinished = ordered.filterNot { it.isComplete }
            val startChapter = unfinished.firstOrNull()?.number
                ?: ordered.firstOrNull()?.number
                ?: 1
            val endChapter = unfinished.lastOrNull()?.number
                ?: ordered.lastOrNull()?.number
                ?: 1
            return BatchUiState(
                novelId = novelId,
                chapters = ordered,
                startText = startChapter.toString(),
                endText = endChapter.toString(),
                concurrency = defaultConcurrency,
                hasLlmKey = hasLlmKey,
                hasFishKey = hasFishKey,
            )
        }
    }
}

private fun String.isDoneStatus(): Boolean =
    trim().equals("done", ignoreCase = true)
