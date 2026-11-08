package com.estimatefaster.audioforge.core.model

/** 清洗阶段的片段模型（未落库）；与 `Seg`/room 的 Segment 解耦。 */
data class ScriptSegment(
    val type: SegmentType,
    val speaker: String,
    val emotion: Emotion,
    val text: String,
)
