package com.estimatefaster.audioforge.domain.pipeline

import com.estimatefaster.audioforge.core.audio.StitchResult
import com.estimatefaster.audioforge.core.model.SegmentType
import java.io.File

/**
 * 单章渲染的结束状态（执行计划 §8.9）。
 *
 * 与 [AnalyzeStatus] 一样，状态只描述「本次调用发生了什么」，不代替 DB 状态：
 * 章节最终状态仍由 [RenderChapterUseCase] 写入 `chapters.audio_status`。
 */
enum class RenderStatus {
    /** 全部片段就绪并拼接成功，章节 `audio_status=done`。 */
    Done,

    /** 部分片段失败（章节 `audio_status=error`），已完成的片段保留。 */
    Partial,

    /** 调用方通过 `shouldStop` 提前停止；未开始的片段保持原状态，章节回到 `pending`。 */
    Stopped,

    /** 没有任何片段成功，或拼接失败；章节 `audio_status=error`。 */
    Error,

    /** 章节没有片段，未调用 TTS/ffmpeg（§8.9「空片段章节：明确报错」）。 */
    NoSegments,
}

/**
 * [RenderChapterUseCase.render] 的返回值。
 *
 * 计数语义：
 * - [cachedCount]：命中缓存、本次**没有**真正合成的片段数（含头部命中与
 *   [com.estimatefaster.audioforge.core.tts.TtsResult.Cached]）；
 * - [synthesizedCount]：本次真的产出新音频的片段数；
 * - [failedCount]：本段合成失败的片段数（不含拼接失败，拼接失败只进 [errors]）。
 *
 * [outputRelative] / [durationMs] 只在 [status] 为 [RenderStatus.Done] 时非空/非零。
 */
data class RenderResult(
    val status: RenderStatus,
    val chapterId: Long,
    val cachedCount: Int = 0,
    val synthesizedCount: Int = 0,
    val failedCount: Int = 0,
    val outputRelative: String? = null,
    val durationMs: Long = 0L,
    val errors: List<RenderError> = emptyList(),
) {
    /** 章节已产出成品音频（只有 [RenderStatus.Done]）。 */
    val succeeded: Boolean get() = status == RenderStatus.Done
}

/**
 * 一条渲染错误。
 *
 * 片段级错误的 [segmentSeq] 是 DB 里的 **0 基** `seq`；拼接错误为 `null`。
 * [code] 优先用 [com.estimatefaster.audioforge.core.model.PipelineError.code]，
 * 拼接失败统一为 `FFMPEG`，章节不存在为 `CHAPTER_NOT_FOUND`。
 */
data class RenderError(
    val code: String,
    val message: String,
    val segmentId: Long? = null,
    val segmentSeq: Int? = null,
)

/**
 * 拼接调用的注入槽位；默认实现是 `ChapterStitcher.stitch(...)`。
 *
 * 单独抽成函数类型是为了让测试注入 fake 拼接器（`ChapterStitcher` 是 final，
 * 且依赖 FFmpegKit，单元测试不启动真实 ffmpeg）。
 */
typealias ChapterStitchCall = suspend (
    novelId: Long,
    chapterIdx: Int,
    files: List<File>,
    types: List<SegmentType>
) -> StitchResult
