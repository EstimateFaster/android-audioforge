package com.estimatefaster.audioforge.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * 存储维护：孤儿扫描、保留期清理与用量统计（执行计划 §7.6、P3-11）。
 *
 * 设计约束：
 * - **不依赖 DB**。哪些文件仍被引用由调用方通过 `referencedAudio` / `referencedImports`
 *   传入（DB 的 `audioRelPath`、`sourceRelPath` 集合），本类只做文件系统判断，
 *   因此可以纯 JVM 单测，也避免存储层反向依赖 `data/db`。
 * - **路径安全**：所有删除先把相对路径解析为根目录下的规范路径；
 *   拒绝绝对路径、`.`/`..` 段、根目录本身与越界路径；任何一层是符号链接都拒绝，
 *   扫描时也**不跟随**符号链接（只记录一条 [StorageFailure]）。
 * - **只删普通文件**，不递归删目录；删除失败不抛异常，计入 [CleanupReport.failures]。
 * - 孤儿判定只看引用集合；保留期判定只看 mtime 与 [retentionDays]；
 *   删除前再校验一次引用集合，防止调用方扫描后引用状态变化。
 *
 * 典型用法：
 * ```kotlin
 * val report = maintenance.scan(audioRefs, importRefs)
 * val freed = maintenance.cleanOrphans(audioRefs, importRefs, olderThanHours = 6)
 * val expired = maintenance.cleanExpired(candidates = report.expired.map { it.relative },
 *     referencedAudio = audioRefs, referencedImports = importRefs)
 * ```
 *
 * [cleanExpired] 的 `referenced*` 是**保护集合**：调用方应先解除 DB 引用
 * （`audioRelPath = null`）再清理保留期音频，否则候选会被跳过并记入
 * [CleanupReport.skippedReferenced]，避免出现 DB 指向不存在文件。
 *
 * 扫描/删除是阻塞 IO，已在 [Dispatchers.IO] 上执行；[stats] 是同步方法，
 * UI 层应放到后台线程调用。
 */
class StorageMaintenance(
    private val storage: AudioStorage,
    private val now: () -> Long = { System.currentTimeMillis() },
    val retentionDays: Int = DEFAULT_RETENTION_DAYS,
) {

    private val root: File get() = storage.projectFiles.root

    init {
        require(retentionDays >= 0) { "retentionDays 不能为负：$retentionDays" }
    }

    // ------------------------------------------------------------------
    // 扫描
    // ------------------------------------------------------------------

    /**
     * 扫描 `audio/`、`imports/`、`exports/`（缺失的目录视为空），把文件分成三类：
     *
     * - [StorageReport.referenced]：路径在引用集合内，或 `.hash` 对应的音频在引用集合内；
     * - [StorageReport.expired]：**被引用**的项目音频且 mtime 早于保留期（保留期候选）；
     * - [StorageReport.orphans]：其余文件（无引用，含 `exports/` 全部文件、`.tmp` 残留）。
     *
     * 三类互斥，`totalBytes` = 三类之和。扫描失败（目录不可读、符号链接）记入
     * [StorageReport.errors]，不抛异常。
     *
     * @param referencedAudio 仍被 DB 引用的音频相对路径（`audio/<nid>/...`）
     * @param referencedImports 仍被 DB 引用的导入源文件相对路径（`imports/<uuid>.txt`）
     */
    suspend fun scan(
        referencedAudio: Set<String>,
        referencedImports: Set<String>,
    ): StorageReport = withContext(Dispatchers.IO) {
        val refs = referenceIndex(referencedAudio, referencedImports)
        val scannedAt = now()
        val cutoff = retentionCutoff(scannedAt, retentionDays)

        val errors = mutableListOf<StorageFailure>()
        val entries = mutableListOf<StorageEntry>()
        entries += scanArea(ProjectFiles.AUDIO_DIR, StorageKind.AUDIO, errors)
        entries += scanArea(ProjectFiles.IMPORTS_DIR, StorageKind.IMPORT, errors)
        entries += scanArea(ProjectFiles.EXPORTS_DIR, StorageKind.EXPORT, errors)
        entries.sortBy { it.relative }

        val referenced = mutableListOf<StorageEntry>()
        val orphans = mutableListOf<StorageEntry>()
        val expired = mutableListOf<StorageEntry>()
        for (entry in entries) {
            val isReferenced = isReferencedPath(entry.relative, refs)
            when {
                entry.kind == StorageKind.AUDIO &&
                    isReferenced &&
                    entry.modifiedAt >= 1L &&
                    entry.modifiedAt <= cutoff -> expired += entry

                isReferenced -> referenced += entry
                else -> orphans += entry
            }
        }

        StorageReport(
            referenced = referenced,
            orphans = orphans,
            expired = expired,
            totalBytes = entries.sumOf { it.bytes },
            referencedBytes = referenced.sumOf { it.bytes },
            orphanBytes = orphans.sumOf { it.bytes },
            expiredBytes = expired.sumOf { it.bytes },
            scannedAt = scannedAt,
            retentionDays = retentionDays,
            errors = errors,
        )
    }

    // ------------------------------------------------------------------
    // 清理
    // ------------------------------------------------------------------

    /**
     * 删除**孤儿**且 mtime 早于 [olderThanHours] 的文件。
     *
     * - 被引用文件（含 `.hash` 对应的音频被引用）一律不删；
     * - 未到阈值的孤儿不删（保护正在写入的 `.tmp` 与正在分享的导出文件）；
     * - 只删普通文件；删除失败/被拒绝的路径逐条计入
     *   [CleanupReport.failures]，返回结果不抛异常。
     *
     * @param olderThanHours 孤儿年龄阈值（小时，默认 6）
     */
    suspend fun cleanOrphans(
        referencedAudio: Set<String>,
        referencedImports: Set<String>,
        olderThanHours: Long = DEFAULT_ORPHAN_AGE_HOURS,
    ): CleanupReport = withContext(Dispatchers.IO) {
        require(olderThanHours >= 0) { "olderThanHours 不能为负：$olderThanHours" }
        val refs = referenceIndex(referencedAudio, referencedImports)
        val report = scan(refs.audio, refs.imports)
        val cutoff = now() - olderThanHours * MILLIS_PER_HOUR
        val sink = CleanupSink()
        for (orphan in report.orphans) {
            // scan 之后引用可能变化：删除前再校验一次，宁可不删。
            if (isReferencedPath(orphan.relative, refs)) {
                sink.skippedReferenced += orphan.relative
                continue
            }
            deleteRegularFile(orphan.relative, cutoff, sink)
        }
        sink.toReport()
    }

    /**
     * 删除调用方指定的、超过保留期的**项目音频**（`audio/<novelId>/...`）。
     *
     * 只接受 [candidates] 中列出的路径（由调用方判定哪些项目已完成、可丢弃），
     * 且必须同时满足：
     * - 位于 `audio/<novelId>/` 下（拒绝 `imports/`、`exports/`、根目录与目录本身）；
     * - 是普通文件、不在引用集合内（被引用的候选记入
     *   [CleanupReport.skippedReferenced]，调用方先解除 DB 引用）；
     * - mtime 早于 `now - retentionDays`。
     *
     * 拒绝/失败逐条计入 [CleanupReport.failures]，不抛异常。
     *
     * @param candidates 相对路径候选（可含 `.hash`；重复项自动去重）
     * @param referencedAudio 仍被引用的音频；`.hash` 的基名被引用时同样受保护
     * @param referencedImports 仍被引用的导入源文件（导入文件永不被保留期清理）
     * @param retentionDays 保留天数，默认取构造参数
     */
    suspend fun cleanExpired(
        candidates: Collection<String>,
        referencedAudio: Set<String> = emptySet(),
        referencedImports: Set<String> = emptySet(),
        retentionDays: Int = this.retentionDays,
    ): CleanupReport = withContext(Dispatchers.IO) {
        require(retentionDays >= 0) { "retentionDays 不能为负：$retentionDays" }
        val refs = referenceIndex(referencedAudio, referencedImports)
        val cutoff = retentionCutoff(now(), retentionDays)
        val sink = CleanupSink()
        val seen = mutableSetOf<String>()
        for (raw in candidates) {
            val relative = normalizeReference(raw)
            if (relative == null) {
                sink.failures += CleanupFailure(raw, "路径非法（绝对路径或包含 . / ..）")
                continue
            }
            if (!seen.add(relative)) continue
            if (!isProjectAudioFile(relative)) {
                sink.failures += CleanupFailure(relative, "只允许清理 audio/<novelId>/ 下的项目音频")
                continue
            }
            if (isReferencedPath(relative, refs)) {
                sink.skippedReferenced += relative
                continue
            }
            deleteRegularFile(relative, cutoff, sink)
        }
        sink.toReport()
    }

    // ------------------------------------------------------------------
    // 统计 / 空间
    // ------------------------------------------------------------------

    /**
     * 同步统计当前用量（总字节、`audio`/`imports`/`exports` 分项、按项目字节数）。
     *
     * 缺失目录按 0 计；符号链接不计入（与扫描口径一致）。
     */
    fun stats(): StorageStats {
        val errors = mutableListOf<StorageFailure>()
        val entries = mutableListOf<StorageEntry>()
        entries += scanArea(ProjectFiles.AUDIO_DIR, StorageKind.AUDIO, errors)
        entries += scanArea(ProjectFiles.IMPORTS_DIR, StorageKind.IMPORT, errors)
        entries += scanArea(ProjectFiles.EXPORTS_DIR, StorageKind.EXPORT, errors)

        val perNovel = mutableMapOf<Long, Long>()
        for (entry in entries) {
            val novelId = entry.novelId ?: continue
            perNovel[novelId] = (perNovel[novelId] ?: 0L) + entry.bytes
        }

        return StorageStats(
            totalBytes = entries.sumOf { it.bytes },
            audioBytes = entries.filter { it.kind == StorageKind.AUDIO }.sumOf { it.bytes },
            importsBytes = entries.filter { it.kind == StorageKind.IMPORT }.sumOf { it.bytes },
            exportsBytes = entries.filter { it.kind == StorageKind.EXPORT }.sumOf { it.bytes },
            perNovelBytes = perNovel.toMap(),
            fileCount = entries.size,
            usableBytes = storage.usableSpaceBytes(),
        )
    }

    /** 空间预检（委托 [AudioStorage.hasSpaceFor]，默认写后保留 500MB）。 */
    fun hasSpaceFor(
        requiredBytes: Long,
        minFreeBytes: Long = AudioStorage.MIN_FREE_BYTES,
    ): Boolean = storage.hasSpaceFor(requiredBytes, minFreeBytes)

    // ------------------------------------------------------------------
    // 内部：删除
    // ------------------------------------------------------------------

    /**
     * 删除一个普通文件；不区分孤儿/过期，只做路径安全与「普通文件」校验。
     *
     * 先 [resolveForDeletion]，再检查存在性、类型与 mtime；任何一步失败都只记录，
     * 不抛异常、不影响后续文件。
     */
    private fun deleteRegularFile(relative: String, cutoff: Long, sink: CleanupSink) {
        val target = try {
            resolveForDeletion(relative)
        } catch (e: IllegalArgumentException) {
            sink.failures += CleanupFailure(relative, e.message ?: "路径非法")
            return
        }
        if (!target.exists()) {
            sink.skippedMissing += relative
            return
        }
        if (!target.isFile) {
            sink.failures += CleanupFailure(relative, "不是普通文件，拒绝删除")
            return
        }
        val modifiedAt = target.lastModified()
        if (modifiedAt <= 0L) {
            sink.failures += CleanupFailure(relative, "无法读取修改时间，跳过")
            return
        }
        if (modifiedAt > cutoff) {
            sink.skippedTooRecent += relative
            return
        }
        val bytes = target.length()
        val deleted = try {
            target.delete()
        } catch (e: SecurityException) {
            false
        }
        if (!deleted) {
            sink.failures += CleanupFailure(relative, "删除失败（文件可能被占用或无权限）")
            return
        }
        sink.deleted += CleanedFile(relative, bytes)
    }

    /**
     * 把相对路径解析为根目录内、可安全删除的 [File]。
     *
     * @throws IllegalArgumentException 路径非法、越界、指向根目录，
     *   或路径链上任意一段是符号链接（不跟随）。
     */
    private fun resolveForDeletion(relative: String): File {
        val normalized = normalizeReference(relative)
            ?: throw IllegalArgumentException("路径非法（绝对路径或包含 . / ..）")
        val target = File(root, normalized)
        if (target == root || !target.absolutePath.startsWith(rootPrefix)) {
            throw IllegalArgumentException("路径越出根目录：$relative")
        }
        // 不跟随符号链接：扫描时已跳过，这里再拒绝任何一段是链接的路径。
        var probe: File? = target
        while (probe != null && probe != root) {
            if (Files.isSymbolicLink(probe.toPath())) {
                throw IllegalArgumentException("路径包含符号链接，拒绝操作：$relative")
            }
            probe = probe.parentFile
        }
        val canonical = canonicalFileOf(target)
        if (canonical == root || !canonical.path.startsWith(rootPrefix)) {
            throw IllegalArgumentException("路径越出根目录：$relative")
        }
        if (canonical != target.absoluteFile) {
            throw IllegalArgumentException("规范化路径不一致（疑似符号链接）：$relative")
        }
        return target
    }

    // ------------------------------------------------------------------
    // 内部：扫描
    // ------------------------------------------------------------------

    /** 遍历一个区域（`audio` / `imports` / `exports`），返回普通文件条目。 */
    private fun scanArea(
        baseRelative: String,
        kind: StorageKind,
        errors: MutableList<StorageFailure>,
    ): List<StorageEntry> {
        val baseDir = File(root, baseRelative)
        if (!baseDir.isDirectory) return emptyList()
        if (Files.isSymbolicLink(baseDir.toPath())) {
            errors += StorageFailure(baseRelative, "区域根目录是符号链接，已跳过")
            return emptyList()
        }
        val result = mutableListOf<StorageEntry>()
        val pending = ArrayDeque<File>()
        pending.addLast(baseDir)
        while (pending.isNotEmpty()) {
            val dir = pending.removeLast()
            val children = dir.listFiles()
            if (children == null) {
                errors += StorageFailure(relativeOf(dir), "无法读取目录")
                continue
            }
            for (child in children) {
                if (Files.isSymbolicLink(child.toPath())) {
                    errors += StorageFailure(relativeOf(child), "符号链接，已跳过")
                    continue
                }
                when {
                    child.isDirectory -> pending.addLast(child)
                    child.isFile -> {
                        val relative = relativeOf(child)
                        result += StorageEntry(
                            relative = relative,
                            bytes = child.length(),
                            kind = kind,
                            novelId = if (kind == StorageKind.AUDIO) novelIdOf(relative) else null,
                            modifiedAt = child.lastModified(),
                        )
                    }

                    else -> errors += StorageFailure(relativeOf(child), "未知文件类型，已跳过")
                }
            }
        }
        return result
    }

    /** 根目录下的相对路径（`/` 分隔）；调用方保证 [file] 在根目录内。 */
    private fun relativeOf(file: File): String {
        val path = file.absolutePath
        return if (path.startsWith(rootPrefix)) {
            path.substring(rootPrefix.length).replace(File.separatorChar, '/')
        } else {
            file.name
        }
    }

    /** `audio/<novelId>/...` 的 novelId；非项目音频返回 null。 */
    private fun novelIdOf(relative: String): Long? {
        val parts = relative.split('/')
        if (parts.size < 2 || parts[0] != ProjectFiles.AUDIO_DIR) return null
        return parts[1].toLongOrNull()?.takeIf { it > 0 }
    }

    /** 保留期清理只接受 `audio/<novelId>/<file>`（至少三级、id 为正数）。 */
    private fun isProjectAudioFile(relative: String): Boolean {
        val parts = relative.split('/')
        if (parts.size < 3) return false
        if (parts[0] != ProjectFiles.AUDIO_DIR) return false
        val novelId = parts[1].toLongOrNull() ?: return false
        return novelId > 0
    }

    // ------------------------------------------------------------------
    // 内部：引用集合
    // ------------------------------------------------------------------

    /**
     * 路径是否被引用。
     *
     * `.hash` 跟随其音频：音频引用 `audio/1/chapter_001.m4a` 对应哈希
     * `audio/1/chapter_001.hash`（去掉扩展名），因此只要音频被引用，哈希也算被引用。
     */
    private fun isReferencedPath(relative: String, refs: ReferenceIndex): Boolean {
        if (relative in refs.audio || relative in refs.imports) return true
        if (!relative.endsWith(ProjectFiles.HASH_SUFFIX)) return false
        val base = relative.removeSuffix(ProjectFiles.HASH_SUFFIX)
        return base in refs.audioHashBases
    }

    private fun referenceIndex(
        referencedAudio: Set<String>,
        referencedImports: Set<String>,
    ): ReferenceIndex {
        val audio = normalizeReferences(referencedAudio)
        return ReferenceIndex(
            audio = audio,
            imports = normalizeReferences(referencedImports),
            audioHashBases = audio.mapTo(mutableSetOf()) { hashBaseOf(it) },
        )
    }

    /** 音频路径对应的 `.hash` 基名（去掉扩展名，规则与 [ProjectFiles.hashRelativeTo] 一致）。 */
    private fun hashBaseOf(audioRelative: String): String {
        val slash = audioRelative.lastIndexOf('/')
        val dot = audioRelative.lastIndexOf('.')
        return if (dot > slash) audioRelative.substring(0, dot) else audioRelative
    }

    private fun normalizeReferences(values: Set<String>): Set<String> =
        values.mapNotNull { normalizeReference(it) }.toSet()

    /**
     * 规范化引用/候选路径：`\` → `/`、去掉 `./`、拒绝空路径、绝对路径与 `.`/`..`。
     *
     * 只做词法规范化，不解析符号链接；返回 `null` 表示该值不可用于文件操作。
     */
    private fun normalizeReference(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("/") || trimmed.startsWith("\\")) return null
        if (WINDOWS_ABSOLUTE.containsMatchIn(trimmed)) return null
        if ('\u0000' in trimmed) return null
        val parts = trimmed.replace('\\', '/').split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return parts.joinToString("/")
    }

    // ------------------------------------------------------------------
    // 内部：工具
    // ------------------------------------------------------------------

    private val rootPrefix: String
        get() = root.path.trimEnd(File.separatorChar) + File.separator

    private fun retentionCutoff(nowMillis: Long, days: Int): Long =
        nowMillis - days * MILLIS_PER_DAY

    /** 规范化后的引用集合 + 音频的 `.hash` 基名，避免每次比较都重算。 */
    private class ReferenceIndex(
        val audio: Set<String>,
        val imports: Set<String>,
        val audioHashBases: Set<String>,
    )

    /** 清理过程的可变累积器；方法返回时转换为不可变 [CleanupReport]。 */
    private class CleanupSink {
        val deleted = mutableListOf<CleanedFile>()
        val skippedReferenced = mutableListOf<String>()
        val skippedTooRecent = mutableListOf<String>()
        val skippedMissing = mutableListOf<String>()
        val failures = mutableListOf<CleanupFailure>()

        fun toReport(): CleanupReport = CleanupReport(
            deleted = deleted.toList(),
            skippedReferenced = skippedReferenced.toList(),
            skippedTooRecent = skippedTooRecent.toList(),
            skippedMissing = skippedMissing.toList(),
            failures = failures.toList(),
        )
    }

    companion object {
        /** 默认保留期：30 天（§7.6 清理策略）。 */
        const val DEFAULT_RETENTION_DAYS: Int = 30

        /** 孤儿默认年龄阈值：6 小时，避免误删进行中的写入/分享。 */
        const val DEFAULT_ORPHAN_AGE_HOURS: Long = 6L

        private const val MILLIS_PER_HOUR: Long = 60L * 60L * 1000L
        private const val MILLIS_PER_DAY: Long = 24L * MILLIS_PER_HOUR

        /** Windows 盘符绝对路径（Linux/Android 上 `File.isAbsolute` 不认）。 */
        private val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:[\\\\/].*")
    }
}

/** 存储区域。 */
enum class StorageKind { AUDIO, IMPORT, EXPORT }

/** 一个被扫描到的普通文件（相对路径 + 字节数 + 区域 + 项目 id + mtime）。 */
data class StorageEntry(
    val relative: String,
    val bytes: Long,
    val kind: StorageKind,
    val novelId: Long?,
    val modifiedAt: Long,
)

/** 扫描过程中的非致命问题（不可读目录、跳过的符号链接等）。 */
data class StorageFailure(val relative: String, val reason: String)

/**
 * 扫描结果；三类文件互斥。
 *
 * @property referenced 被引用且未超过保留期的文件
 * @property orphans 无引用文件（含 `exports/` 全部文件、`.tmp` 残留）
 * @property expired 被引用但超过保留期的项目音频（保留期候选）
 */
data class StorageReport(
    val referenced: List<StorageEntry>,
    val orphans: List<StorageEntry>,
    val expired: List<StorageEntry>,
    val totalBytes: Long,
    val referencedBytes: Long,
    val orphanBytes: Long,
    val expiredBytes: Long,
    val scannedAt: Long,
    val retentionDays: Int,
    val errors: List<StorageFailure> = emptyList(),
) {
    val isEmpty: Boolean
        get() = referenced.isEmpty() && orphans.isEmpty() && expired.isEmpty()
}

/** 已删除的文件及其字节数（字节数为删除前大小）。 */
data class CleanedFile(val relative: String, val bytes: Long)

/** 一条清理失败（路径 + 可读原因）；失败不会中断整次清理。 */
data class CleanupFailure(val relative: String, val reason: String)

/**
 * 清理结果。
 *
 * @property freedBytes 实际释放字节数（= [deleted] 之和）
 * @property skippedReferenced 因仍被引用而跳过的路径
 * @property skippedTooRecent 未到年龄阈值而跳过的路径
 * @property skippedMissing 已不存在而跳过的路径
 * @property failures 路径非法/不是普通文件/删除失败等
 */
data class CleanupReport(
    val deleted: List<CleanedFile>,
    val skippedReferenced: List<String>,
    val skippedTooRecent: List<String>,
    val skippedMissing: List<String>,
    val failures: List<CleanupFailure>,
) {
    val deletedCount: Int get() = deleted.size
    val freedBytes: Long get() = deleted.sumOf { it.bytes }
    val failureCount: Int get() = failures.size
}

/** 用量统计：总字节、分区域字节、按项目字节（`audio/<novelId>`）、文件数、卷可用空间。 */
data class StorageStats(
    val totalBytes: Long,
    val audioBytes: Long,
    val importsBytes: Long,
    val exportsBytes: Long,
    val perNovelBytes: Map<Long, Long>,
    val fileCount: Int,
    val usableBytes: Long,
) {
    fun bytesFor(novelId: Long): Long = perNovelBytes[novelId] ?: 0L
}

private fun canonicalFileOf(file: File): File = try {
    file.canonicalFile
} catch (e: IOException) {
    // 与 ProjectFiles 一致：极少数文件系统无法 canonicalize 时退回绝对路径；
    // 词法越界校验已在 normalizeReference 完成，不削弱安全性。
    file.absoluteFile
}
