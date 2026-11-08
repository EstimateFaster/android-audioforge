package com.estimatefaster.audioforge.domain.jobs

import com.estimatefaster.audioforge.core.model.PipelineError
import com.estimatefaster.audioforge.data.db.ChapterEntity
import com.estimatefaster.audioforge.data.repository.ChapterRepository
import com.estimatefaster.audioforge.data.repository.JobRepository
import com.estimatefaster.audioforge.domain.pipeline.AnalyzeChapterUseCase
import com.estimatefaster.audioforge.domain.pipeline.AnalyzeResult
import com.estimatefaster.audioforge.domain.pipeline.AnalyzeStatus
import com.estimatefaster.audioforge.domain.pipeline.RenderResult
import com.estimatefaster.audioforge.domain.pipeline.RenderStatus
import kotlinx.coroutines.CancellationException

/** 批次模式（与 `jobs.mode` 的 wire 值一致，见执行计划 §11.1）。 */
enum class GenerationMode(val wire: String) {
    /** 先解析再渲染。 */
    Full("full"),

    /** 只解析（LLM）。 */
    Parse("parse"),

    /** 只渲染（TTS + 拼接）。 */
    Render("render");

    val parses: Boolean get() = this != Render

    val renders: Boolean get() = this != Parse

    companion object {
        fun fromWire(value: String?): GenerationMode? =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

/**
 * 一次生成批次的输入。
 *
 * [chapterIds] 由 BatchRunner 决定（默认 5 章、上限 20 章，§11.2）；执行器按数组顺序串行处理，
 * 不并发同一本书的章节。
 *
 * [jobId] 是 `jobs` 表行 id。Worker 通过它写进度/终态；0 表示“不写 job 表”，
 * 只用于纯执行器测试。P3-04 的 BatchRunner 必须把真实 jobId 传给 Worker。
 */
data class GenerationInput(
    val novelId: Long,
    val chapterIds: List<Long>,
    val mode: GenerationMode,
    val force: Boolean = false,
    val jobId: Long = 0L,
)

/** 批次统计；`toJobStats()` 的 key 与 §11.3 对齐。 */
data class GenerationStats(
    val chaptersTotal: Int = 0,
    val chaptersDone: Int = 0,
    val chaptersFailed: Int = 0,
    val segDone: Long = 0L,
    val segTotal: Long = 0L,
    val ttsOk: Long = 0L,
    val ttsFailed: Long = 0L,
) {
    /** 已处理章数（成功 + 失败）；用于进度百分比，避免失败章节让进度永远停在半路。 */
    val chaptersProcessed: Int get() = chaptersDone + chaptersFailed

    val percent: Int
        get() = if (chaptersTotal <= 0) {
            0
        } else {
            (chaptersProcessed * 100 / chaptersTotal).coerceIn(0, 100)
        }

    fun toJobStats(): Map<String, Long> = buildMap {
        put(KEY_CHAPTERS_TOTAL, chaptersTotal.toLong())
        put(KEY_CHAPTERS_DONE, chaptersDone.toLong())
        put(KEY_CHAPTERS_FAILED, chaptersFailed.toLong())
        put(KEY_SEG_DONE, segDone)
        put(KEY_SEG_TOTAL, segTotal)
        put(KEY_TTS_OK, ttsOk)
        put(KEY_TTS_FAILED, ttsFailed)
    }

    companion object {
        const val KEY_CHAPTERS_TOTAL = "chaptersTotal"
        const val KEY_CHAPTERS_DONE = "chaptersDone"
        const val KEY_CHAPTERS_FAILED = "chaptersFailed"
        const val KEY_SEG_DONE = "segDone"
        const val KEY_SEG_TOTAL = "segTotal"
        const val KEY_TTS_OK = "ttsOk"
        const val KEY_TTS_FAILED = "ttsFailed"
    }
}

/** 进度快照；Worker 用它更新 `jobs` 行与前台通知。 */
data class GenerationProgress(
    val chapterId: Long?,
    val chapterTitle: String,
    val stats: GenerationStats,
) {
    val percent: Int get() = stats.percent
}

/** 失败发生在哪个阶段。 */
enum class GenerationStage { Parse, Render }

/** 单章失败记录；批次继续处理其余章节，最后汇总。 */
data class GenerationFailure(
    val chapterId: Long,
    val chapterTitle: String,
    val stage: GenerationStage,
    val code: String,
    val message: String,
    val retryable: Boolean,
)

/**
 * 批次结果（§15 P3-02）。
 *
 * - [Success]：所有章节都完成；
 * - [Partial]：至少一章成功、至少一章失败；**不**触发 WorkManager 整批重试；
 * - [Failed]：所有章节都失败；[retryable] 为 true 时 Worker 可 `Result.retry()`；
 * - [Stopped]：调用方通过 `shouldStop` 提前停止；已完成章节的结果保留。
 *
 * 失败默认「记录并继续下一章」（§15 P3-02 明确选择），因此部分章节失败不会中断批次；
 * 只有全部失败才可能重试。重试时已解析/已渲染的章节会被用例层的 `done` 判定跳过，
 * 天然是断点续传。
 */
sealed class BatchOutcome {
    abstract val stats: GenerationStats
    abstract val failures: List<GenerationFailure>

    /**
     * 是否所有失败都值得整批重试。
     *
     * 只有 [Failed] 且失败列表非空、每一项都是 Network/Quota 时才为 true；
     * Worker 还必须满足 `runAttemptCount < MAX_RETRIES` 才会返回 `Result.retry()`。
     */
    val retryable: Boolean
        get() = failures.isNotEmpty() && failures.all { it.retryable }

    /** 可直接落 `jobs.lastError` 的中文摘要（截断到 500 字）。 */
    val errorSummary: String
        get() {
            if (failures.isEmpty()) return ""
            val head = if (this is Failed) {
                "全部 ${stats.chaptersTotal} 章失败"
            } else {
                "${stats.chaptersFailed} 章失败"
            }
            val detail = failures.take(MAX_SUMMARY_ITEMS)
                .joinToString("；") { "${it.chapterTitle}：${it.message}" }
            return "$head：$detail".take(MAX_SUMMARY_CHARS)
        }

    data class Success(override val stats: GenerationStats) : BatchOutcome() {
        override val failures: List<GenerationFailure> = emptyList()
    }

    data class Partial(
        override val stats: GenerationStats,
        override val failures: List<GenerationFailure>,
    ) : BatchOutcome()

    data class Failed(
        override val stats: GenerationStats,
        override val failures: List<GenerationFailure>,
    ) : BatchOutcome()

    data class Stopped(
        override val stats: GenerationStats,
        override val failures: List<GenerationFailure> = emptyList(),
    ) : BatchOutcome()

    companion object {
        private const val MAX_SUMMARY_ITEMS = 3
        private const val MAX_SUMMARY_CHARS = 500
    }
}

/** 解析调用槽；生产由 `AnalyzeChapterUseCase::analyze` 适配，测试注入 fake。 */
typealias AnalyzeChapterCall = suspend (
    novelId: Long,
    chapterId: Long,
    force: Boolean
) -> AnalyzeResult

/** 渲染调用槽；生产由 `RenderChapterUseCase::render` 适配，测试注入 fake。 */
typealias RenderChapterCall = suspend (
    novelId: Long,
    chapterId: Long,
    shouldStop: () -> Boolean
) -> RenderResult

/**
 * 批次执行器（P3-02）：纯逻辑，不依赖 Android 组件，可在单元测试里用 fake 解析/渲染跑通。
 *
 * 执行语义：
 * 1. 校验输入（novelId、非空且无重复的正 chapterId、mode）；参数不合法抛
 *    [IllegalArgumentException]，由 Worker 转成 `Result.failure()`；
 * 2. 预读本次章节并校验归属（章节不存在/属于别的书同样抛参数异常）；
 * 3. 按输入顺序逐章执行：
 *    - `full`：先 analyze，再 render；
 *    - `parse`：只 analyze；
 *    - `render`：只 render；
 * 4. 每章开始前、每阶段开始前检查 `shouldStop`，另外每章边界检查 `jobs.stopRequested`；
 *    render 内部的 worker 池通过传入的 `shouldStop` 在**每段前**再次检查；
 * 5. 单章失败只记录、继续其余章；全部失败 → [BatchOutcome.Failed]，部分成功 →
 *    [BatchOutcome.Partial]；
 * 6. 每章开始前/结束后写 `jobs.statsJson`（`chaptersDone/Total`、`segDone/Total`、
 *    `ttsOk/ttsFailed`）并回调 [onProgress]，供 Worker 更新前台通知。
 *
 * 停止语义：返回 [BatchOutcome.Stopped] 时，**已完成章节的 DB 结果保留**；当前章若只完成
 * parse，片段已落库，下次 full/parse 会因 `parse_status=done` 跳过；render 的未开始片段
 * 保持 pending（见 [com.estimatefaster.audioforge.domain.pipeline.RenderChapterUseCase]）。
 * 执行器不写 job 终态——`markDone/markError/markCancelled` 由 Worker 统一负责。
 *
 * 错误分类见 [GenerationErrors]：Network/Quota 可重试，Auth/Model/BadJson/Truncated/
 * Storage/NoSegments 不可重试；`TtsFailed`/`Ffmpeg` 按 `PipelineError.retryable`
 * 的默认值（false）处理。
 */
class GenerationExecutor(
    private val chapters: ChapterRepository,
    private val jobs: JobRepository,
    private val analyzeChapter: AnalyzeChapterCall,
    private val renderChapter: RenderChapterCall,
) {

    /**
     * 执行一个批次。
     *
     * @param onProgress 进度回调；每章开始/结束各一次，外加一次初始快照
     * @param shouldStop 同步停止查询（Worker 传 `isStopped`）；返回 true 时在下一个安全点停止
     */
    suspend fun run(
        input: GenerationInput,
        onProgress: suspend (GenerationProgress) -> Unit = {},
        shouldStop: () -> Boolean = { false },
    ): BatchOutcome {
        validate(input)

        val plans = input.chapterIds.map { chapterId ->
            val chapter = chapters.getChapter(chapterId)
                ?: throw IllegalArgumentException("章节不存在：$chapterId")
            require(chapter.novelId == input.novelId) {
                "章节 $chapterId 属于项目 ${chapter.novelId}，与传入的 ${input.novelId} 不一致"
            }
            chapter
        }

        val segCounts = plans.associate { it.id to it.segCount.toLong() }.toMutableMap()
        val segDones = plans.associate { it.id to it.segDone.toLong() }.toMutableMap()
        val failures = mutableListOf<GenerationFailure>()
        var chaptersDone = 0
        var chaptersFailed = 0
        var ttsOk = 0L
        var ttsFailed = 0L

        fun segTotal(): Long = segCounts.values.sum()

        fun segDone(): Long = segDones.values.sum()

        fun snapshot(): GenerationStats = GenerationStats(
            chaptersTotal = plans.size,
            chaptersDone = chaptersDone,
            chaptersFailed = chaptersFailed,
            segDone = segDone(),
            segTotal = segTotal(),
            ttsOk = ttsOk,
            ttsFailed = ttsFailed,
        )

        suspend fun refresh(chapterId: Long) {
            val fresh = chapters.getChapter(chapterId) ?: return
            segCounts[chapterId] = fresh.segCount.toLong()
            segDones[chapterId] = fresh.segDone.toLong()
        }

        suspend fun publish(chapter: ChapterEntity?) {
            val progress = GenerationProgress(
                chapterId = chapter?.id,
                chapterTitle = chapter?.title.orEmpty(),
                stats = snapshot(),
            )
            if (input.jobId > 0L) {
                jobs.updateProgress(
                    id = input.jobId,
                    chapterId = progress.chapterId,
                    chapterTitle = progress.chapterTitle,
                    stats = progress.stats.toJobStats(),
                )
            }
            onProgress(progress)
        }

        suspend fun stopNow(): Boolean =
            shouldStop() || (input.jobId > 0L && jobs.isStopRequested(input.jobId))

        fun stopped(): BatchOutcome.Stopped = BatchOutcome.Stopped(snapshot(), failures.toList())

        publish(null)

        for (plan in plans) {
            if (stopNow()) return stopped()

            publish(plan)

            var failure: GenerationFailure? = null

            if (input.mode.parses) {
                val result = try {
                    analyzeChapter(input.novelId, plan.id, input.force)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    failure = GenerationErrors.fromThrowable(e, plan, GenerationStage.Parse)
                    null
                }
                if (result != null) {
                    failure = GenerationErrors.fromAnalyze(result, plan)
                }
                refresh(plan.id)
            }

            if (failure == null && input.mode.renders) {
                // parse 完成后、render 开始前再检查一次：停止时当前章只保留解析结果。
                refresh(plan.id)
                if (stopNow()) {
                    publish(plan)
                    return stopped()
                }

                val result = try {
                    renderChapter(input.novelId, plan.id, shouldStop)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    failure = GenerationErrors.fromThrowable(e, plan, GenerationStage.Render)
                    null
                }

                if (result != null) {
                    when (result.status) {
                        RenderStatus.Done -> {
                            ttsOk += (result.cachedCount + result.synthesizedCount).toLong()
                        }

                        RenderStatus.Stopped -> {
                            ttsOk += (result.cachedCount + result.synthesizedCount).toLong()
                            ttsFailed += result.failedCount.toLong()
                            refresh(plan.id)
                            publish(plan)
                            return stopped()
                        }

                        RenderStatus.Partial, RenderStatus.Error -> {
                            ttsOk += (result.cachedCount + result.synthesizedCount).toLong()
                            ttsFailed += result.failedCount.toLong()
                            failure = GenerationErrors.fromRender(result, plan)
                        }

                        RenderStatus.NoSegments -> {
                            failure = GenerationErrors.fromRender(result, plan)
                        }
                    }
                }
                refresh(plan.id)
            }

            val reason = failure
            if (reason == null) {
                chaptersDone++
            } else {
                chaptersFailed++
                failures += reason
            }
            publish(plan)
        }

        return when {
            failures.isEmpty() -> BatchOutcome.Success(snapshot())
            chaptersDone == 0 -> BatchOutcome.Failed(snapshot(), failures.toList())
            else -> BatchOutcome.Partial(snapshot(), failures.toList())
        }
    }

    private fun validate(input: GenerationInput) {
        require(input.novelId > 0L) { "novelId 必须为正数：${input.novelId}" }
        require(input.chapterIds.isNotEmpty()) { "chapterIds 不能为空" }
        require(input.chapterIds.all { it > 0L }) { "chapterIds 只能包含正数：${input.chapterIds}" }
        require(input.chapterIds.distinct().size == input.chapterIds.size) {
            "chapterIds 不能包含重复值：${input.chapterIds}"
        }
        require(input.jobId >= 0L) { "jobId 不能为负：${input.jobId}" }
    }
}

/**
 * 错误分类（§9.5 / §15 P3-02）。
 *
 * 优先使用 [PipelineError.retryable]；从 [AnalyzeResult]/[RenderResult] 拿到的只有错误码，
 * 因此按码表判断：只有 [PipelineError.NETWORK] / [PipelineError.QUOTA] 可重试。
 */
internal object GenerationErrors {

    /** 可重试的错误码；与 [PipelineError] 的 retryable 语义保持一致。 */
    val RETRYABLE_CODES: Set<String> = setOf(PipelineError.NETWORK, PipelineError.QUOTA)

    const val CODE_INTERNAL: String = "INTERNAL"

    fun fromThrowable(
        error: Throwable,
        chapter: ChapterEntity,
        stage: GenerationStage,
    ): GenerationFailure {
        val pipeline = error as? PipelineError
        return GenerationFailure(
            chapterId = chapter.id,
            chapterTitle = chapter.title,
            stage = stage,
            code = pipeline?.code ?: CODE_INTERNAL,
            message = pipeline?.userMessage
                ?: error.message?.takeIf { it.isNotBlank() }
                ?: error.javaClass.simpleName,
            retryable = pipeline?.retryable == true,
        )
    }

    /** @return null 表示该章解析成功（Done/Degraded/Skipped）。 */
    fun fromAnalyze(result: AnalyzeResult, chapter: ChapterEntity): GenerationFailure? = when (result.status) {
        AnalyzeStatus.Done, AnalyzeStatus.Degraded, AnalyzeStatus.Skipped -> null

        AnalyzeStatus.TooLong -> GenerationFailure(
            chapterId = chapter.id,
            chapterTitle = chapter.title,
            stage = GenerationStage.Parse,
            code = AnalyzeChapterUseCase.ERROR_CHAPTER_TOO_LONG,
            message = result.warning ?: "章节过长，等待用户确认",
            retryable = false,
        )

        AnalyzeStatus.Failed -> {
            val code = result.errorCode ?: CODE_INTERNAL
            GenerationFailure(
                chapterId = chapter.id,
                chapterTitle = chapter.title,
                stage = GenerationStage.Parse,
                code = code,
                message = result.error ?: "解析失败，请重试",
                retryable = code in RETRYABLE_CODES,
            )
        }
    }

    /** @return null 表示该章渲染成功（Done）；Stopped 由调用方单独处理。 */
    fun fromRender(result: RenderResult, chapter: ChapterEntity): GenerationFailure? = when (result.status) {
        RenderStatus.Done, RenderStatus.Stopped -> null

        RenderStatus.NoSegments -> GenerationFailure(
            chapterId = chapter.id,
            chapterTitle = chapter.title,
            stage = GenerationStage.Render,
            code = PipelineError.NO_SEGMENTS,
            message = result.errors.firstOrNull()?.message
                ?: PipelineError.NoSegments().userMessage,
            retryable = false,
        )

        RenderStatus.Partial, RenderStatus.Error -> {
            val errors = result.errors
            val message = when {
                errors.isEmpty() -> "渲染失败"
                errors.size == 1 -> errors.first().message
                else -> "有 ${errors.size} 段合成失败：${errors.first().message}"
            }
            GenerationFailure(
                chapterId = chapter.id,
                chapterTitle = chapter.title,
                stage = GenerationStage.Render,
                code = errors.firstOrNull()?.code ?: CODE_INTERNAL,
                message = message,
                // 全部错误码都可重试才算可重试；混合错误按不可重试处理（避免无意义整批重跑）。
                retryable = errors.isNotEmpty() && errors.all { it.code in RETRYABLE_CODES },
            )
        }
    }
}
