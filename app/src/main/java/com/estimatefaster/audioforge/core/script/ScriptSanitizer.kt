package com.estimatefaster.audioforge.core.script

import com.estimatefaster.audioforge.core.model.Emotion
import com.estimatefaster.audioforge.core.model.NARRATOR_ROLE_ID
import com.estimatefaster.audioforge.core.model.ScriptSegment
import com.estimatefaster.audioforge.core.model.SegmentType
import java.util.regex.Pattern

/**
 * 剧本清洗（执行计划 §8.4）。**最高风险移植点**：逐函数、逐正则对照
 * 上游 `core/analyzer.py` 的 `_strip_speech_tail` / `_is_residue` / `_find_quoted` /
 * `_split_dialogue_mixed` / `_split_mixed` / `_split_long` / `_sanitize`。
 *
 * 不要“优化”正则里的交替顺序；词表顺序会影响“先剥哪个引导语”。
 */
class ScriptSanitizer {

    /** 引号片段：成对弯/直双引号、中文括号引号。 */
    data class Quoted(val start: Int, val end: Int, val inner: String)

    // ---------- 公共入口 ----------

    fun sanitize(
        segments: List<ScriptSegment>,
        maxChars: Int = DEFAULT_SEGMENT_MAX_CHARS,
    ): List<ScriptSegment> {
        val cleaned = splitMixed(segments, maxChars)
        val limit = maxOf(20, maxChars)
        val out = mutableListOf<ScriptSegment>()
        for (segment in cleaned) {
            for (part in splitLong(segment.text, limit)) {
                out += segment.copy(text = part)
            }
        }
        return out.filter { it.text.trim().isNotEmpty() }
    }

    /** 剥掉旁白段尾部的引导语残渣，保留前面的动作描写；迭代剥除直到不再变化。 */
    fun stripSpeechTail(chunk: String): String {
        var current = chunk.trim()
        repeat(MAX_STRIP_ROUNDS) {
            var next = current
            for (pattern in SPEECH_PATTERNS) {
                val match = pattern.find(next)
                if (match != null) {
                    next = next.substring(0, match.range.first)
                        .trimEnd('，', ',', '。', '：', ':', ' ')
                    break
                }
            }
            if (next == current) return current
            current = next
        }
        return current
    }

    /** 剥完引导语后剩余的是否只是人名/残渣（应丢弃）。 */
    fun isResidue(text: String): Boolean =
        text.length <= 6 &&
            !RESIDUE_PUNCTUATION.containsMatchIn(text) &&
            !RESIDUE_INTENSIFIER.containsMatchIn(text)

    /** 返回文本中第一个引号台词片段；支持 LLM 常见的单边引号。 */
    fun findQuoted(text: String): Quoted? {
        for (index in text.indices) {
            val ch = text[index]
            if (ch in OPEN_QUOTES) {
                val close = when (ch) {
                    '“' -> '”'
                    '「' -> '」'
                    else -> '"'
                }
                val rest = text.substring(index + 1)
                val closeIndex = rest.indexOf(close)
                return if (closeIndex == -1) {
                    val sentenceEnd = SENTENCE_END.find(rest)
                    val end = sentenceEnd?.range?.last?.plus(1) ?: rest.length
                    Quoted(index, index + 1 + end, rest.substring(0, end))
                } else {
                    Quoted(
                        index,
                        index + 1 + closeIndex + 1,
                        rest.substring(0, closeIndex),
                    )
                }
            }
            if (ch in CLOSE_QUOTES) {
                val open = when (ch) {
                    '”' -> '“'
                    '」' -> '「'
                    else -> '"'
                }
                val openIndex = text.lastIndexOf(open, index - 1)
                val start = if (openIndex != -1) openIndex + 1 else 0
                return Quoted(start, index + 1, text.substring(start, index))
            }
        }
        return null
    }

    /**
     * LLM 把「引导语/旁白 + 台词」混在一个 dialogue 段时拆开。
     * 返回 null 表示无需拆分。
     */
    fun splitDialogueMixed(segment: ScriptSegment): List<ScriptSegment>? {
        val text = segment.text.trim()
        val found = findQuoted(text) ?: return null
        val inner = found.inner.trim()
        if (inner.isEmpty()) return null
        val head = text.substring(0, found.start).trim()
        val isSpeech = SPEECH_VERB_BEFORE.containsMatchIn(head) ||
            (inner.length >= 3 && inner.last() in SENTENCE_END_CHARS)
        if (!isSpeech) return null

        val out = mutableListOf<ScriptSegment>()
        if (head.isNotEmpty()) {
            var strippedHead = stripSpeechTail(head)
            strippedHead = TRAILING_PUNCTUATION.replace(strippedHead, "").trim()
            if (strippedHead.isNotEmpty() && !isResidue(strippedHead)) {
                out += ScriptSegment(
                    type = SegmentType.Narration,
                    speaker = NARRATOR_ROLE_ID,
                    emotion = Emotion.Neutral,
                    text = strippedHead,
                )
            }
        }
        out += segment.copy(text = inner)
        val tail = text.substring(found.end).trim()
        if (tail.isNotEmpty()) {
            var strippedTail = stripSpeechTail(tail)
            strippedTail = TRAILING_PUNCTUATION.replace(strippedTail, "").trim()
            if (strippedTail.isNotEmpty() && !isResidue(strippedTail)) {
                out += ScriptSegment(
                    type = SegmentType.Narration,
                    speaker = NARRATOR_ROLE_ID,
                    emotion = Emotion.Neutral,
                    text = strippedTail,
                )
            }
        }
        return out
    }

    /**
     * 防错兜底：只修 LLM 明确犯的错（整段引号、台词与旁白重复、旁白尾部引导语残留），
     * 不按引号强行切分；引号不等于对话。
     */
    fun splitMixed(
        segments: List<ScriptSegment>,
        maxChars: Int = DEFAULT_SEGMENT_MAX_CHARS,
    ): List<ScriptSegment> {
        val out = mutableListOf<ScriptSegment>()
        val dialogueTexts = mutableSetOf<String>()

        for (segment in segments) {
            var text = segment.text.trim()
            if (text.isEmpty()) continue
            if (segment.type == SegmentType.Dialogue) {
                if (QUOTED_ONLY.matches(text)) {
                    text = text.trim(*QUOTE_CHARS).trim()
                    if (text.isEmpty()) continue
                    out += segment.copy(text = text)
                    dialogueTexts += text
                    continue
                }
                val parts = splitDialogueMixed(segment.copy(text = text))
                if (parts != null) {
                    for (part in parts) {
                        if (part.type == SegmentType.Dialogue) dialogueTexts += part.text
                        out += part
                    }
                    continue
                }
                out += segment.copy(text = text)
                dialogueTexts += text
            } else {
                out += segment.copy(text = text)
            }
        }

        val deduped = mutableListOf<ScriptSegment>()
        for (segment in out) {
            if (segment.type == SegmentType.Dialogue) {
                deduped += segment
                continue
            }
            var text = segment.text.trim()
            // 整段与某句台词重复 -> 丢弃旁白副本。
            if (text in dialogueTexts) continue
            // 旁白里「他淡淡道：“台词”」且台词已单独成段 -> 删引号台词与引导语残渣。
            while (true) {
                val match = QUOTE_RUN.find(text) ?: break
                val inner = match.groupValues[1].trim(*QUOTE_CHARS).trim()
                if (inner !in dialogueTexts) break
                var head = stripSpeechTail(text.substring(0, match.range.first))
                if (head.isNotEmpty() && isResidue(head)) head = ""
                text = (head + text.substring(match.range.last + 1)).trim()
            }
            if (text.isNotEmpty()) deduped += segment.copy(text = text)
        }

        // 台词前的引导语残渣：仅当后一段是台词时才剥；纯人名整段丢弃。
        val trimmed = mutableListOf<ScriptSegment>()
        deduped.forEachIndexed { index, segment ->
            if (segment.type == SegmentType.Dialogue) {
                trimmed += segment
                return@forEachIndexed
            }
            var text = segment.text.trim()
            if (index + 1 < deduped.size && deduped[index + 1].type == SegmentType.Dialogue) {
                text = stripSpeechTail(text)
                text = TRAILING_PUNCTUATION.replace(text, "").trim()
                if (text.isNotEmpty() && isResidue(text)) text = ""
            }
            if (text.isNotEmpty()) trimmed += segment.copy(text = text)
        }

        return mergeAdjacentNarration(trimmed, maxChars)
    }

    /** 按句末标点把超长段切成 <= limit 的片段（不改字，标点随前句）。 */
    fun splitLong(text: String, limit: Int): List<String> {
        val trimmed = text.trim()
        if (trimmed.length <= limit) {
            return if (trimmed.isEmpty()) emptyList() else listOf(trimmed)
        }
        val parts = mutableListOf<String>()
        var buffer = ""
        for (piece in SENTENCE_BREAK.split(trimmed)) {
            if (piece.isEmpty()) continue
            if (buffer.isNotEmpty() && buffer.length + piece.length > limit) {
                parts += buffer
                buffer = piece
            } else {
                buffer += piece
            }
        }
        if (buffer.isNotEmpty()) parts += buffer

        val out = mutableListOf<String>()
        for (part in parts) {
            if (part.length <= limit) {
                out += part
            } else {
                // 单句超长（无句末标点）：硬切，保证不超上限。
                var start = 0
                while (start < part.length) {
                    out += part.substring(start, minOf(start + limit, part.length))
                    start += limit
                }
            }
        }
        return out
    }

    // ---------- 内部 ----------

    private fun mergeAdjacentNarration(
        segments: List<ScriptSegment>,
        maxChars: Int,
    ): List<ScriptSegment> {
        val merged = mutableListOf<ScriptSegment>()
        for (segment in segments) {
            val last = merged.lastOrNull()
            if (last != null &&
                last.type == segment.type &&
                segment.type == SegmentType.Narration &&
                last.speaker == segment.speaker &&
                last.text.length + segment.text.length < maxChars
            ) {
                val separator = if (last.text.endsWith("。", "！", "？", "…")) "" else "。"
                merged[merged.size - 1] = last.copy(text = last.text + separator + segment.text)
            } else {
                merged += segment
            }
        }
        return merged
    }

    companion object {
        const val DEFAULT_SEGMENT_MAX_CHARS = 120
        private const val MAX_STRIP_ROUNDS = 8

        private const val QUOTE_CHARS_STRING = "“”\"‘’'「」『』"
        private val QUOTE_CHARS = QUOTE_CHARS_STRING.toCharArray()
        private const val OPEN_QUOTES = "“「\""
        private const val CLOSE_QUOTES = "”」\""
        private const val SENTENCE_END_CHARS = "。！？…"

        private fun compile(pattern: String): Regex =
            Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS).toRegex()

        private val QUOTE_RUN = compile("([“\"][^”\"]*[”\"]|「[^」]*」)")
        private val QUOTED_ONLY = compile("^\\s*[“\"‘’「『][^“”\"‘’「」『』]{0,500}[”\"’」』]\\s*${'$'}")
        private val SENTENCE_END = compile("[。！？…]")
        private val SENTENCE_BREAK = compile("(?<=[。！？…!?])\\s*")
        private val TRAILING_PUNCTUATION = compile("[，,：:]\\s*${'$'}")
        private val RESIDUE_PUNCTUATION = compile("[。！？；…，]")
        private val RESIDUE_INTENSIFIER = compile("[很真太非常]")

        // 副词性修饰词（_SPEECH_TAIL 与 _SPEECH_SINGLE 共用一份词表）。
        private const val MODS =
            "接着|然后|又|忙|赶紧|有些|微微|便|就|也|再|终于|忍不住|不禁|不由|" +
                "连忙|急忙|随即|立刻|马上|突然|忽然|再次|重新|缓缓|仔细|淡淡|幽幽|悠悠|" +
                "轻轻|低声|小声|大声|严肃|平静|着急|无奈|吃惊|恍然|喃喃|嘟囔|嘀咕|" +
                "迟疑|犹豫|试探|随口|正色|厉声|高声|笑呵呵|和蔼|深沉|冷冷|冷声|" +
                "自言自语|想了想|停顿了一下|沉吟了一会儿|但|却|还|仍|仍然|依旧|依然|" +
                "有点|有点儿|略|稍|稍稍"

        private const val PRONOUNS = "他|她|我|你|他们|她们|我们|你们"

        private val SPEECH_TAIL = compile(
            "(?:$MODS)?" +
                "(?:的|地)?" +
                "(?:对|向|朝|跟|和|与)?" +
                "(?:$PRONOUNS)?" +
                "(?:无奈道|冷笑道|沉声道|低声道|淡淡道|幽幽道|悠悠道|轻轻道|笑呵呵道|" +
                "高声道|厉声道|严肃道|平静道|着急道|点头道|吃惊道|自言自语道|" +
                "恍然道|喃喃道|嘟囔道|嘀咕道|提醒道|警告道|安慰道|催促道|吩咐道|" +
                "抱怨道|解释道|迟疑道|犹豫道|试探道|回应道|应道|接口道|接话道|" +
                "正色道|冷冷道|冷声道|小声道|轻声道|大声道|随口道|笑着道|" +
                "说道|问道|答道|喊道|叫道|笑道|怒道|叹道|开口道|回答|解释|" +
                "想了想道|喝问道|喝道|冷喝|笑问|反问|嘱咐道|开口道|" +
                "说完|说罢|说着|说起|提起)" +
                "[：:。，,]?${'$'}",
        )

        private val SPEECH_SINGLE = compile(
            "(?:$MODS|的|地)" +
                "(?:的|地)?" +
                "(?:对|向|朝|跟|和|与)?" +
                "(?:$PRONOUNS)?" +
                "(?:便|就|又|再)?" +
                "[道说问答喊叫笑叹喝回应]" +
                "[：:。，,]?${'$'}",
        )

        private val SPEECH_MOD = compile(
            "(?:接着|然后|又|忙|赶紧|便|就|也|再|终于|忍不住|不禁|不由|" +
                "连忙|急忙|随即|立刻|马上|突然|忽然|再次|重新|" +
                "略|稍|稍稍)?" +
                "(?:有些|微微|有点|有点儿)?" +
                "(?:缓缓|仔细|淡淡|幽幽|悠悠|轻轻|低声|小声|大声|严肃|平静|着急|无奈|" +
                "吃惊|恍然|喃喃|嘟囔|嘀咕|随口|正色|厉声|高声|笑呵呵|和蔼|深沉|冷冷|" +
                "冷声|自言自语|想了想|停顿了一下|沉吟了一会儿|异口同声|齐声|同声|稍微)" +
                "(?:的|地)?" +
                "(?:又|再|便|就|也)?" +
                "[：:。，,]?${'$'}",
        )

        private val SPEECH_PATTERNS = listOf(SPEECH_TAIL, SPEECH_SINGLE, SPEECH_MOD)

        private val SPEECH_VERB_BEFORE = compile(
            "(?:道|说|问|喊|叫|笑|答|叹|喝|嘱咐|开口|传来|响起|解释|回答|提醒|警告|" +
                "安慰|催促|吩咐|抱怨|嘀咕|嘟囔|喃喃|自言自语|施了一个礼|说了一句|问了一句|" +
                "喊了一声|叫了一声|说了一声|问了一声|嘱咐了一句|拍手叫好|眉头一皱|" +
                "轻轻一笑|微微一笑|一笑|轻笑)[：:，,]?\\s*${'$'}",
        )
    }
}
