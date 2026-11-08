package com.estimatefaster.audioforge.core.model

/**
 * 领域枚举。`wire` 是落库 / LLM JSON / API 的稳定值，必须与 Python 版一致，不能翻译。
 * 执行计划 §8.1。
 */
enum class ParseStatus(val wire: String) {
    Pending("pending"),
    Running("running"),
    Done("done"),
    Error("error"),
    ;

    companion object {
        fun fromWire(value: String): ParseStatus = entries.firstOrNull { it.wire == value } ?: Pending
    }
}

enum class AudioStatus(val wire: String) {
    Pending("pending"),
    Running("running"),
    Done("done"),
    Error("error"),
    ;

    companion object {
        fun fromWire(value: String): AudioStatus = entries.firstOrNull { it.wire == value } ?: Pending
    }
}

enum class SegmentStatus(val wire: String) {
    Pending("pending"),
    Running("running"),
    Done("done"),
    Error("error"),
    ;

    companion object {
        fun fromWire(value: String): SegmentStatus = entries.firstOrNull { it.wire == value } ?: Pending
    }
}

enum class SegmentType(val wire: String) {
    Narration("narration"),
    Dialogue("dialogue"),
    ;

    companion object {
        fun fromWire(value: String): SegmentType = entries.firstOrNull { it.wire == value } ?: Narration
    }
}

enum class Importance(val wire: String) {
    Protagonist("protagonist"),
    Supporting("supporting"),
    Minor("minor"),
    ;

    companion object {
        fun fromWire(value: String): Importance = entries.firstOrNull { it.wire == value } ?: Supporting
    }
}

enum class Gender(val wire: String) {
    Male("male"),
    Female("female"),
    Unknown("unknown"),
    ;

    companion object {
        fun fromWire(value: String): Gender = entries.firstOrNull { it.wire == value } ?: Unknown
    }
}

enum class BatchMode(val wire: String) {
    Full("full"),
    Parse("parse"),
    Render("render"),
    ;

    companion object {
        fun fromWire(value: String): BatchMode = entries.firstOrNull { it.wire == value } ?: Full
    }
}

/**
 * 11 个情绪；`ttsPrefix` 会拼在 Fish Audio 文本前。Neutral 不加前缀。
 * wire 列表与 `porting-vectors.json` 的 `tts.emotion_prefix` 对齐（该 fixture 只含 10 个非 Neutral 前缀）。
 */
enum class Emotion(val wire: String, val ttsPrefix: String?) {
    Neutral("neutral", null),
    Happy("happy", "[happy]"),
    Sad("sad", "[sad]"),
    Angry("angry", "[angry]"),
    Anxious("anxious", "[anxious]"),
    Excited("excited", "[excited]"),
    Surprised("surprised", "[surprised]"),
    SoftTone("soft tone", "[soft tone]"),
    Serious("serious", "[serious]"),
    Calm("calm", "[calm]"),
    Whispering("whispering", "[whispering]"),
    ;

    /** 给 TTS 文本加情绪前缀；Neutral 原样返回。 */
    fun applyTo(text: String): String = ttsPrefix?.let { "$it$text" } ?: text

    companion object {
        fun fromWire(value: String): Emotion = entries.firstOrNull { it.wire == value } ?: Neutral
    }
}
