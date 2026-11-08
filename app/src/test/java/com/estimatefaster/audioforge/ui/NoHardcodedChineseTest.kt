package com.estimatefaster.audioforge.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-12：检查 `feature/` 与 `ui/components/` 的正式界面代码里没有硬编码中文。
 *
 * 扫描规则（与 `docs/strings-audit.md` 一致）：
 * - 只看 `.kt` 源码；注释、KDoc、字符串插值外的代码不影响结果。
 * - 预览区整体跳过：文件末尾的 `// Preview` 分隔注释之后都是 `@Preview` 示例数据，
 *   不属于正式界面文案。
 * - `require` / `check` / `error` / 异常构造函数里的中文是开发者断言，不是用户
 *   文案，允许保留。
 * - 纯状态层的诊断导出标记 [DIAGNOSTIC_TRUNCATED_MARKER] 没有 Context 可取
 *   `strings.xml`，作为唯一显式白名单。
 */
class NoHardcodedChineseTest {

    @Test
    fun no_hardcoded_chinese_in_feature_and_ui_components() {
        val roots = sourceRoots()
        assertTrue(
            "找不到 main 源码目录，工作目录=${File(".").absolutePath}",
            roots.isNotEmpty(),
        )

        val violations = roots
            .flatMap { root -> listOf(File(root, "feature"), File(root, "ui/components")) }
            .filter { it.isDirectory }
            .flatMap { directory ->
                directory.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .sortedBy { it.absolutePath }
                    .flatMap { file -> scan(file, displayPath = file.relativeTo(rootOf(file, roots))) }
                    .toList()
            }

        assertTrue(
            buildString {
                appendLine("发现 ${violations.size} 处硬编码中文，请迁入 res/values/strings.xml：")
                violations.forEach { appendLine(it.describe()) }
            },
            violations.isEmpty(),
        )
    }

    @Test
    fun scanner_ignores_comments_previews_and_developer_assertions() {
        val source = """
            // 这是注释里的中文
            /**
             * KDoc 里的中文也不算用户文案
             */
            val hardcoded = "真正的问题"

            require(value > 0) { "value 必须为正数" }

            // -------------------------------------------------------------------
            // Preview：示例数据
            // -------------------------------------------------------------------
            private fun previewData() = "预览样例"
        """.trimIndent()

        val violations = scanText(
            text = source,
            displayPath = "Synthetic.kt",
            fileName = "Synthetic.kt",
        )

        assertEquals(1, violations.size)
        assertEquals("真正的问题", violations.single().literal)
    }

    private fun sourceRoots(): List<File> = listOf(
        File("src/main/java/com/estimatefaster/audioforge"),
        File("app/src/main/java/com/estimatefaster/audioforge"),
    ).filter { it.isDirectory }

    private fun rootOf(file: File, roots: List<File>): File =
        roots.first { file.startsWith(it) }

    private fun scan(file: File, displayPath: String): List<HardcodedChinese> =
        scanText(
            text = file.readText(),
            displayPath = displayPath,
            fileName = file.name,
        )

    private fun scanText(
        text: String,
        displayPath: String,
        fileName: String,
    ): List<HardcodedChinese> {
        val production = text.lineSequence()
            .takeWhile { line ->
                !(line.trimStart().startsWith("//") && line.contains("Preview"))
            }
            .joinToString(separator = "\n")

        val masked = stripComments(production)
        val lines = masked.lines()

        return STRING_LITERAL.findAll(masked)
            .filter { CJK.containsMatchIn(it.value) }
            .filterNot { match ->
                val line = masked.lineCountUpTo(match.range.first)
                // require/check 的文案可能换行写在下一行，向前多看 3 行。
                val context = lines.subList(
                    maxOf(0, line - 4),
                    minOf(lines.size, line),
                ).joinToString(separator = "\n")
                DEVELOPER_MARKERS.any { marker -> context.contains(marker) }
            }
            .filterNot { match -> isWhitelisted(fileName, match.value) }
            .map { match ->
                HardcodedChinese(
                    file = displayPath,
                    line = masked.lineCountUpTo(match.range.first),
                    literal = match.value.trim('"'),
                )
            }
            .toList()
    }

    private fun isWhitelisted(fileName: String, literal: String): Boolean =
        fileName == "AboutUiState.kt" &&
            literal.contains("诊断信息已截断")

    private fun String.lineCountUpTo(index: Int): Int =
        take(index).count { it == '\n' } + 1

    /** 去掉行注释与块注释，保留字符串字面量；换行保留，行号不变。 */
    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var index = 0
        while (index < source.length) {
            when {
                source.startsWith("//", index) -> {
                    val end = source.indexOf('\n', index).let { if (it < 0) source.length else it }
                    repeat(end - index) { out.append(' ') }
                    index = end
                }

                source.startsWith("/*", index) -> {
                    val close = source.indexOf("*/", index + 2)
                    val end = if (close < 0) source.length else close + 2
                    for (position in index until end) {
                        out.append(if (source[position] == '\n') '\n' else ' ')
                    }
                    index = end
                }

                source.startsWith("\"\"\"", index) -> {
                    val close = source.indexOf("\"\"\"", index + 3)
                    val end = if (close < 0) source.length else close + 3
                    out.append(source, index, end)
                    index = end
                }

                source[index] == '"' -> {
                    out.append('"')
                    index++
                    while (index < source.length) {
                        val current = source[index]
                        out.append(current)
                        index++
                        if (current == '\\' && index < source.length) {
                            out.append(source[index])
                            index++
                        } else if (current == '"') {
                            break
                        }
                    }
                }

                else -> {
                    out.append(source[index])
                    index++
                }
            }
        }
        return out.toString()
    }

    private data class HardcodedChinese(
        val file: String,
        val line: Int,
        val literal: String,
    ) {
        fun describe(): String = "$file:$line  \"$literal\""
    }

    private companion object {
        val CJK = Regex("[\\u4E00-\\u9FFF]")
        val STRING_LITERAL = Regex("\"(?:\\\\.|[^\"\\\\])*\"")
        val DEVELOPER_MARKERS = listOf(
            "require(",
            "check(",
            "checkNotNull(",
            "error(",
            "IllegalArgumentException(",
            "IllegalStateException(",
        )
    }
}
