package com.estimatefaster.audioforge.domain.pipeline

import com.estimatefaster.audioforge.core.model.Emotion
import com.estimatefaster.audioforge.core.model.NewCharacter
import com.estimatefaster.audioforge.core.model.ScriptSegment
import com.estimatefaster.audioforge.core.model.SegmentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 单章解析结果状态（执行计划 §8.7 / §8.10）。 */
enum class AnalyzeStatus {
    /** 正常解析并落库。 */
    Done,

    /** 模型输出不可用（非法 JSON / 空剧本），已降级为整章旁白。 */
    Degraded,

    /** 章节已解析且 `force=false`，本次没有调用 LLM。 */
    Skipped,

    /** 请求失败（截断、网络、鉴权等），章节标记为 `error`。 */
    Failed,
}

/**
 * [AnalyzeChapterUseCase] 的返回值。
 *
 * [warning] 是最终写入 `chapters.parseWarn` 的文案（覆盖率、未识别说话人、
 * 降级原因、修复重试结果），无告警时为 `null`；调用方可以直接展示。
 * [coverage] 只在真正产出剧本时才有值（[AnalyzeStatus.Skipped] /
 * [AnalyzeStatus.Failed] 为 `null`）。
 */
data class AnalyzeResult(
    val status: AnalyzeStatus,
    val chapterId: Long,
    val coverage: Double? = null,
    val segmentCount: Int = 0,
    val warning: String? = null,
    val error: String? = null,
    val errorCode: String? = null,
    /** 因无法映射到已注册角色而改成旁白的 dialogue 段数。 */
    val unknownSpeakers: Int = 0,
    /** 是否实际采用了修复重试的输出（第二次 LLM 调用且覆盖率更高）。 */
    val repaired: Boolean = false,
) {
    /** 章节已产出可渲染的片段（[AnalyzeStatus.Done] / [AnalyzeStatus.Degraded]）。 */
    val succeeded: Boolean
        get() = status == AnalyzeStatus.Done || status == AnalyzeStatus.Degraded
}

// ---------------------------------------------------------------- JSON DTO
//
// 字段全部可空：LLM 漏字段/给 null 时按「结构非法」处理，而不是在反序列化阶段
// 抛异常。真正的整章降级判定在 AnalyzeChapterUseCase.parseScript 里。

@Serializable
internal data class RawScript(
    @SerialName("new_characters") val newCharacters: List<RawNewCharacter>? = null,
    val segments: List<RawSegment>? = null,
)

@Serializable
internal data class RawNewCharacter(
    val name: String? = null,
    val gender: String? = null,
    val importance: String? = null,
    val aliases: List<String>? = null,
    val note: String? = null,
    val voice: String? = null,
    @SerialName("alias_of") val aliasOf: String? = null,
)

@Serializable
internal data class RawSegment(
    val type: String? = null,
    val speaker: String? = null,
    val emotion: String? = null,
    val text: String? = null,
)

/** 通过结构校验的剧本；speaker 仍是 LLM 给的名字，尚未映射为 roleId。 */
internal data class ParsedScript(
    val newCharacters: List<NewCharacter>,
    val lines: List<ParsedLine>,
)

internal data class ParsedLine(
    val type: SegmentType,
    val speaker: String,
    val emotion: Emotion,
    val text: String,
)

/** speaker 映射 + 清洗后的落库前片段。 */
internal data class MappedScript(
    val segments: List<ScriptSegment>,
    val unknownSpeakers: Int,
)
