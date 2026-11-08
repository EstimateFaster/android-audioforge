package com.estimatefaster.audioforge.core.script

import com.estimatefaster.audioforge.core.model.ScriptSegment
import java.util.regex.Pattern

data class CoverageResult(
    val coverage: Double,
    val missing: List<String>,
)

/**
 * 覆盖率校验（执行计划 §8.5）。逐行移植上游 `core/analyzer.py` 的
 * `norm_text` / `_is_expected_drop` / `text_coverage` / `missing_for_repair`。
 *
 * diff 必须与 Python `difflib.SequenceMatcher(autojunk=False)` 的 opcodes 一致，
 * 否则 fixture 里的缺失片段（如 `句。第二`）会对不上。这里移植了
 * `find_longest_match` + `get_matching_blocks`，而不是换用 Myers。
 */
class CoverageChecker(
    private val sanitizer: ScriptSanitizer = ScriptSanitizer(),
) {

    fun normText(text: String): String = NORM_STRIP.replace(text, "")

    /** 这段原文“消失”是否属于清洗阶段的设计内行为。 */
    fun isExpectedDrop(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        if (sanitizer.isResidue(trimmed)) return true
        val stripped = sanitizer.stripSpeechTail(trimmed)
        if (stripped.isEmpty()) return trimmed.length <= 20
        return stripped.length <= 3 && sanitizer.isResidue(stripped)
    }

    fun check(raw: String, segments: List<ScriptSegment>): CoverageResult {
        val original = normText(raw)
        val produced = normText(segments.joinToString("") { it.text })
        if (original.isEmpty()) return CoverageResult(1.0, emptyList())

        val missing = SequenceMatcher(original, produced).opcodes()
            .asSequence()
            .filter { it.tag == TAG_DELETE || it.tag == TAG_REPLACE }
            .filter { it.i2 - it.i1 >= MIN_MISSING_CHARS }
            .map { original.substring(it.i1, it.i2) }
            .toList()

        val real = missing.filterNot { isExpectedDrop(it) }
        val lost = real.sumOf { it.length }
        val coverage = maxOf(0.0, 1.0 - lost.toDouble() / original.length)
        return CoverageResult(coverage, real)
    }

    /** 挑出值得喂回模型补写的缺失片段（滤掉设计内会被剥掉的引导语）。 */
    fun missingForRepair(missing: List<String>, limit: Int = MAX_REPAIR_FRAGMENTS): List<String> =
        missing.filterNot { isExpectedDrop(it) }.take(limit)

    // ---------- difflib.SequenceMatcher 移植 ----------

    private data class Match(val i: Int, val j: Int, val size: Int)

    private data class Range(val alo: Int, val ahi: Int, val blo: Int, val bhi: Int)

    private data class Opcode(
        val tag: String,
        val i1: Int,
        val i2: Int,
        val j1: Int,
        val j2: Int,
    )

    private class SequenceMatcher(private val a: String, private val b: String) {

        private val b2j: Map<Char, List<Int>> = HashMap<Char, MutableList<Int>>().also { map ->
            for (index in b.indices) {
                map.getOrPut(b[index]) { mutableListOf() }.add(index)
            }
        }

        fun opcodes(): List<Opcode> {
            val blocks = matchingBlocks()
            val ops = mutableListOf<Opcode>()
            var i = 0
            var j = 0
            for (block in blocks) {
                val tag = when {
                    i < block.i && j < block.j -> TAG_REPLACE
                    i < block.i -> TAG_DELETE
                    j < block.j -> TAG_INSERT
                    else -> null
                }
                if (tag != null) ops += Opcode(tag, i, block.i, j, block.j)
                i = block.i + block.size
                j = block.j + block.size
                if (block.size > 0) ops += Opcode(TAG_EQUAL, block.i, i, block.j, j)
            }
            return ops
        }

        private fun matchingBlocks(): List<Match> {
            val queue = ArrayDeque<Range>()
            queue.addLast(Range(0, a.length, 0, b.length))
            val blocks = mutableListOf<Match>()
            while (queue.isNotEmpty()) {
                val range = queue.removeLast()
                val match = findLongestMatch(range)
                if (match.size > 0) {
                    blocks += match
                    if (range.alo < match.i && range.blo < match.j) {
                        queue.addLast(Range(range.alo, match.i, range.blo, match.j))
                    }
                    if (match.i + match.size < range.ahi && match.j + match.size < range.bhi) {
                        queue.addLast(
                            Range(
                                match.i + match.size,
                                range.ahi,
                                match.j + match.size,
                                range.bhi,
                            ),
                        )
                    }
                }
            }
            blocks.sortWith(compareBy({ it.i }, { it.j }, { it.size }))

            // 合并相邻块（difflib 的 get_matching_blocks 尾处理）。
            val nonAdjacent = mutableListOf<Match>()
            var i1 = 0
            var j1 = 0
            var k1 = 0
            for (block in blocks) {
                if (i1 + k1 == block.i && j1 + k1 == block.j) {
                    k1 += block.size
                } else {
                    if (k1 != 0) nonAdjacent += Match(i1, j1, k1)
                    i1 = block.i
                    j1 = block.j
                    k1 = block.size
                }
            }
            if (k1 != 0) nonAdjacent += Match(i1, j1, k1)
            nonAdjacent += Match(a.length, b.length, 0)
            return nonAdjacent
        }

        private fun findLongestMatch(range: Range): Match {
            var besti = range.alo
            var bestj = range.blo
            var bestsize = 0
            var j2len = HashMap<Int, Int>()
            for (i in range.alo until range.ahi) {
                val newJ2Len = HashMap<Int, Int>()
                for (j in b2j[a[i]] ?: emptyList()) {
                    if (j < range.blo) continue
                    if (j >= range.bhi) break
                    val k = (j2len[j - 1] ?: 0) + 1
                    newJ2Len[j] = k
                    if (k > bestsize) {
                        besti = i - k + 1
                        bestj = j - k + 1
                        bestsize = k
                    }
                }
                j2len = newJ2Len
            }
            while (besti > range.alo && bestj > range.blo &&
                a[besti - 1] == b[bestj - 1]
            ) {
                besti--
                bestj--
                bestsize++
            }
            while (besti + bestsize < range.ahi && bestj + bestsize < range.bhi &&
                a[besti + bestsize] == b[bestj + bestsize]
            ) {
                bestsize++
            }
            return Match(besti, bestj, bestsize)
        }
    }

    companion object {
        const val DEFAULT_MIN_COVERAGE = 0.97
        const val MAX_REPAIR_FRAGMENTS = 6
        private const val MIN_MISSING_CHARS = 3

        private const val TAG_REPLACE = "replace"
        private const val TAG_DELETE = "delete"
        private const val TAG_INSERT = "insert"
        private const val TAG_EQUAL = "equal"

        private val NORM_STRIP = Pattern.compile(
            "[\\s“”\"‘’「」『』]+",
            Pattern.UNICODE_CHARACTER_CLASS,
        ).toRegex()
    }
}
