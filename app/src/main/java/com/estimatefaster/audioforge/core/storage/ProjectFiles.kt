package com.estimatefaster.audioforge.core.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * 项目文件系统布局（执行计划 §7.6）。
 *
 * ```text
 * <root>/
 * ├── imports/<uuid>.txt
 * ├── audio/<novelId>/chapter_<nnn>.m4a
 * ├── audio/<novelId>/segments/ch_<nnn>/seg_<nnnnnn>.mp3
 * ├── audio/<novelId>/segments/ch_<nnn>/seg_<nnnnnn>.hash
 * └── exports/<safeName>
 * ```
 *
 * 约定：
 * - 项目内部文件**只用 ASCII 与数字序号**；用户标题只进 DB 与导出名。
 * - 所有 API 只接受/返回**相对路径**（`audio/1/chapter_001.m4a`），DB 只存相对路径；
 *   [resolve] / [writeAtomic] 会拒绝绝对路径与 `..` 越界。
 * - 章节 `idx` 与片段 `seq` 均为 **0 基**（与 DB / `ChapterSplitter` 一致），
 *   落到文件名时转成 1 基并补零：`chapter_<idx+1:03d>`、`seg_<seq+1:06d>`。
 * - 原子写：先写 `<name>.tmp` 再 `renameTo`，失败清理临时文件，目标保持原样。
 * - 空间预检：`usableSpace - required >= minFree`，默认保留 500MB。
 *
 * 该类是纯 JVM 代码，不依赖 Android `Context`；App 侧传入 `filesDir`，
 * 单元测试传入临时目录。
 */
class ProjectFiles(rootDir: File) {

    /** 根目录（规范化后的绝对路径）；调用方传入 `filesDir`。 */
    val root: File = canonicalFileOf(rootDir)

    init {
        require(!root.exists() || root.isDirectory) { "根目录必须是目录：$root" }
    }

    // ------------------------------------------------------------------
    // 相对路径（DB 只存这些）
    // ------------------------------------------------------------------

    /** 导入源文件目录：`imports`。 */
    fun importsDir(): String = IMPORTS_DIR

    /** 导出临时目录：`exports`。 */
    fun exportsDir(): String = EXPORTS_DIR

    /** 项目音频目录：`audio/<novelId>`。 */
    fun audioDir(novelId: Long): String = "$AUDIO_DIR/${requireNovelId(novelId)}"

    /** 章节片段目录：`audio/<novelId>/segments/ch_<nnn>`。 */
    fun segmentsDir(novelId: Long, chapterIdx: Int): String =
        "${audioDir(novelId)}/segments/${segmentDirName(chapterIdx)}"

    /** 导入源文件：`imports/<uuid>.txt`。 */
    fun importFile(uuid: UUID = UUID.randomUUID()): String = "$IMPORTS_DIR/${uuid}.txt"

    /** 导入源文件（字符串形式，必须是合法 UUID）。 */
    fun importFile(uuid: String): String = importFile(parseUuid(uuid))

    /** 章节成品：`audio/<novelId>/chapter_<idx+1:03d>.m4a`。 */
    fun chapterAudio(novelId: Long, chapterIdx: Int): String =
        "${audioDir(novelId)}/${chapterFileName(chapterIdx)}"

    /** 片段音频：`audio/<novelId>/segments/ch_<nnn>/seg_<seq+1:06d>.mp3`。 */
    fun segmentAudio(novelId: Long, chapterIdx: Int, segmentSeq: Int): String =
        "${segmentsDir(novelId, chapterIdx)}/${segmentFileName(segmentSeq)}"

    /** 片段哈希：与音频同名、后缀 `.hash`（`seg_000001.hash`）。 */
    fun segmentHash(novelId: Long, chapterIdx: Int, segmentSeq: Int): String =
        hashRelativeTo(segmentAudio(novelId, chapterIdx, segmentSeq))

    /** 任一音频相对路径对应的 `.hash` 相对路径（去掉原扩展名）。 */
    fun hashRelativeTo(audioRelative: String): String {
        val normalized = normalizeRelative(audioRelative)
        val slash = normalized.lastIndexOf('/')
        val dot = normalized.lastIndexOf('.')
        val base = if (dot > slash) normalized.substring(0, dot) else normalized
        return "$base$HASH_SUFFIX"
    }

    /** 导出临时文件：`exports/<safeName>`；文件名会被清洗并截断到 80 字符。 */
    fun exportFile(safeName: String): String = "$EXPORTS_DIR/${sanitizeFileName(safeName)}"

    /** 章节文件名：`chapter_<idx+1:03d>.m4a`（`idx` 为 0 基）。 */
    fun chapterFileName(chapterIdx: Int): String =
        "chapter_${displayNumber(chapterIdx, CHAPTER_DIGITS)}.m4a"

    /** 片段目录名：`ch_<idx+1:03d>`（`idx` 为 0 基）。 */
    fun segmentDirName(chapterIdx: Int): String =
        "ch_${displayNumber(chapterIdx, CHAPTER_DIGITS)}"

    /** 片段文件名：`seg_<seq+1:06d>.mp3`（`seq` 为 0 基）。 */
    fun segmentFileName(segmentSeq: Int): String =
        "seg_${displayNumber(segmentSeq, SEGMENT_DIGITS)}.mp3"

    // ------------------------------------------------------------------
    // 解析与目录
    // ------------------------------------------------------------------

    /**
     * 把相对路径解析为根目录下的 [File]。
     *
     * @throws IllegalArgumentException 路径为空、为绝对路径、含 `.`/`..` 段、
     *   或规范化后越出根目录（如通过符号链接）。
     */
    fun resolve(relative: String): File {
        val normalized = normalizeRelative(relative)
        val target = File(root, normalized)
        requireInsideRoot(target)
        return target
    }

    /** 返回 [file] 相对根目录的路径；用于入库前去掉绝对路径前缀。 */
    fun relativePathOf(file: File): String {
        val canonical = canonicalFileOf(file)
        val rootPath = root.path
        if (canonical != root && !canonical.path.startsWith(rootPath + File.separator)) {
            throw IllegalArgumentException("文件不在根目录内：$file")
        }
        if (canonical == root) return "."
        return canonical.path.substring(rootPath.length + 1).replace(File.separatorChar, '/')
    }

    /** 创建目录并返回 [File]；已存在但不是目录时抛 [IOException]。 */
    fun ensureDirectory(relative: String): File {
        val dir = resolve(relative)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("无法创建目录：$relative")
        }
        return dir
    }

    fun ensureImportsDir(): File = ensureDirectory(importsDir())

    fun ensureExportsDir(): File = ensureDirectory(exportsDir())

    fun ensureAudioDir(novelId: Long): File = ensureDirectory(audioDir(novelId))

    fun ensureSegmentsDir(novelId: Long, chapterIdx: Int): File =
        ensureDirectory(segmentsDir(novelId, chapterIdx))

    // ------------------------------------------------------------------
    // 原子写
    // ------------------------------------------------------------------

    /**
     * 原子写入字节：`<target>.tmp` → rename。
     *
     * 失败时删除 `.tmp`，不会留下半文件；目标已存在则被整体替换。
     */
    @Synchronized
    fun writeAtomic(target: File, bytes: ByteArray) {
        val sink = requireWritableTarget(target)
        val tmp = tempFileFor(sink)
        ensureParentDirectory(tmp)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            moveIntoPlace(tmp, sink)
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    /**
     * 原子写入输入流（调用方负责关闭 [input]）。
     *
     * 与 [writeAtomic] 一样先写 `.tmp` 再 rename；读取失败会清理临时文件。
     */
    @Synchronized
    fun writeAtomic(target: File, input: InputStream) {
        val sink = requireWritableTarget(target)
        val tmp = tempFileFor(sink)
        ensureParentDirectory(tmp)
        try {
            FileOutputStream(tmp).use { out ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    out.write(buffer, 0, read)
                }
                out.flush()
                out.fd.sync()
            }
            moveIntoPlace(tmp, sink)
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    /** 原子写入字节到相对路径。 */
    fun writeAtomic(relative: String, bytes: ByteArray) {
        writeAtomic(resolve(relative), bytes)
    }

    /** 原子写入输入流到相对路径。 */
    fun writeAtomic(relative: String, input: InputStream) {
        writeAtomic(resolve(relative), input)
    }

    // ------------------------------------------------------------------
    // 删除
    // ------------------------------------------------------------------

    /** 删除项目的音频目录 `audio/<novelId>`；不存在时返回 true（幂等）。 */
    fun deleteNovel(novelId: Long): Boolean {
        val dir = File(root, audioDir(novelId))
        if (!dir.exists()) return true
        return dir.deleteRecursively()
    }

    /** 删除相对路径对应的文件或目录；不存在返回 false（不做静默越权删除）。 */
    fun deleteFile(relative: String): Boolean {
        val target = resolve(relative)
        if (!target.exists()) return false
        return target.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // 空间预检
    // ------------------------------------------------------------------

    /** 根目录所在卷的可用字节数；根目录尚不存在时回退到最近的已存在祖先。 */
    fun usableSpaceBytes(): Long {
        var probe: File? = root
        while (probe != null && !probe.exists()) {
            probe = probe.parentFile
        }
        return probe?.usableSpace ?: 0L
    }

    /**
     * 是否有足够空间：`usableSpace - requiredBytes >= minFreeBytes`。
     *
     * @param requiredBytes 本次预计新增字节数（`>= 0`）
     * @param minFreeBytes 写入后必须保留的空闲字节数，默认 [MIN_FREE_BYTES]（500MB）
     */
    fun hasSpaceFor(requiredBytes: Long, minFreeBytes: Long = MIN_FREE_BYTES): Boolean {
        require(requiredBytes >= 0) { "requiredBytes 不能为负：$requiredBytes" }
        require(minFreeBytes >= 0) { "minFreeBytes 不能为负：$minFreeBytes" }
        val usable = usableSpaceBytes()
        if (usable <= 0) return false
        if (requiredBytes > usable) return false
        return usable - requiredBytes >= minFreeBytes
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun requireNovelId(novelId: Long): Long {
        require(novelId > 0) { "novelId 必须为正数：$novelId" }
        return novelId
    }

    private fun displayNumber(index: Int, digits: Int): String {
        require(index >= 0) { "序号不能为负：$index" }
        return (index.toLong() + 1L).toString().padStart(digits, '0')
    }

    /** 校验并规范化相对路径；返回使用 `/` 分隔的相对路径。 */
    private fun normalizeRelative(relative: String): String {
        require(relative.isNotBlank()) { "相对路径不能为空" }
        require(!relative.startsWith("/") && !relative.startsWith("\\")) {
            "拒绝绝对路径：$relative"
        }
        require(!WINDOWS_ABSOLUTE.containsMatchIn(relative)) {
            "拒绝绝对路径：$relative"
        }
        require('\u0000' !in relative) { "路径不能包含 NUL：$relative" }
        val parts = relative.split('/', '\\')
        parts.forEach { part ->
            require(part.isNotEmpty()) { "路径不能包含空段：$relative" }
            require(part != "." && part != "..") { "路径不能包含 . 或 ..：$relative" }
        }
        return parts.joinToString("/")
    }

    private fun requireInsideRoot(file: File) {
        require(file != root) { "不能直接操作根目录" }
        val canonical = canonicalFileOf(file)
        val rootPath = root.path
        require(canonical.path.startsWith(rootPath + File.separator)) {
            "路径越出根目录：$file"
        }
    }

    private fun requireWritableTarget(target: File): File {
        requireInsideRoot(target)
        return target
    }

    private fun ensureParentDirectory(file: File) {
        val parent = file.parentFile ?: throw IOException("文件没有父目录：$file")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("无法创建目录：$parent")
        }
    }

    private fun tempFileFor(target: File): File = File(target.parentFile, target.name + TEMP_SUFFIX)

    private fun moveIntoPlace(tmp: File, target: File) {
        // POSIX rename 覆盖已存在文件；不先删目标，避免失败时丢失旧文件。
        if (tmp.renameTo(target)) return
        throw IOException("无法原子替换文件：$target")
    }

    private fun parseUuid(value: String): UUID = try {
        UUID.fromString(value)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("非法 UUID：$value", e)
    }

    companion object {
        /** 写批次前必须保留的最小空闲空间：500MB（§7.6）。 */
        const val MIN_FREE_BYTES: Long = 500L * 1024L * 1024L

        /** 导出文件名最大长度（字符数）。 */
        const val MAX_FILE_NAME: Int = 80

        const val IMPORTS_DIR: String = "imports"
        const val AUDIO_DIR: String = "audio"
        const val EXPORTS_DIR: String = "exports"
        const val TEMP_SUFFIX: String = ".tmp"
        const val HASH_SUFFIX: String = ".hash"

        private const val CHAPTER_DIGITS = 3
        private const val SEGMENT_DIGITS = 6
        private const val BUFFER_SIZE = 64 * 1024

        /** Windows 盘符绝对路径（Linux/Android 上 `File.isAbsolute` 不认）。 */
        private val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:[\\\\/].*")

        /** `\ / : * ? " < > |` 以及控制字符。 */
        private fun isIllegalFileNameChar(ch: Char): Boolean = when (ch) {
            '\\', '/', ':', '*', '?', '"', '<', '>', '|' -> true
            else -> ch < ' ' || ch == '\u007F'
        }

        /**
         * 清洗导出文件名：
         * - `\ / : * ? " < > |` 与控制字符 → `_`；
         * - 去掉首尾空白与结尾的点（避免 `.`/`..`）；
         * - 空名回退为 `export`；
         * - 截断到 [MAX_FILE_NAME]，尽量保留扩展名。
         */
        fun sanitizeFileName(raw: String): String {
            val replaced = buildString(raw.length) {
                for (ch in raw) {
                    append(if (isIllegalFileNameChar(ch)) '_' else ch)
                }
            }
            val cleaned = replaced.trim().trimEnd('.')
            val candidate = cleaned.ifEmpty { "export" }
            return truncatePreservingExtension(candidate, MAX_FILE_NAME)
        }

        private fun truncatePreservingExtension(name: String, max: Int): String {
            if (name.codePointCount(0, name.length) <= max) return name
            val dot = name.lastIndexOf('.')
            val extension = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
            val stem = if (extension.isEmpty()) name else name.substring(0, dot)
            val budget = (max - extension.length).coerceAtLeast(1)
            val codePoints = stem.codePointCount(0, stem.length)
            val end = stem.offsetByCodePoints(0, minOf(codePoints, budget))
            return stem.substring(0, end) + extension
        }
    }
}

private fun canonicalFileOf(file: File): File = try {
    file.canonicalFile
} catch (e: IOException) {
    // 极少数文件系统（或父路径是普通文件）无法 canonicalize；
    // 词法越界校验已在 normalizeRelative 完成，这里退回绝对路径不削弱安全性。
    file.absoluteFile
}
