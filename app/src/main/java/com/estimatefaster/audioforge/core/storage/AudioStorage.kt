package com.estimatefaster.audioforge.core.storage

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * 音频/导入文件的外观层（执行计划 §7.6、P2-05）。
 *
 * 职责：
 * - 统一生产章节音频、片段音频/哈希、导入源文件、导出临时文件与片段目录的路径；
 * - 所有方法返回**相对路径**（`audio/<nid>/...`），保证 DB 只存相对路径；
 * - 封装原子写、目录创建、删除与空间预检，调用方不直接拼路径、不碰绝对路径。
 *
 * 底层的布局、越界校验与原子写由 [ProjectFiles] 实现；本类可安全地在
 * WorkManager/协程中使用（[ProjectFiles.writeAtomic] 对同一实例加锁）。
 */
class AudioStorage(val projectFiles: ProjectFiles) {

    constructor(rootDir: File) : this(ProjectFiles(rootDir))

    /** App 私有根目录（`filesDir`）。 */
    val root: File get() = projectFiles.root

    // ------------------------------------------------------------------
    // 路径（只返回相对路径）
    // ------------------------------------------------------------------

    /** 导入源文件：`imports/<uuid>.txt`。 */
    fun importRelative(uuid: UUID = UUID.randomUUID()): String =
        projectFiles.importFile(uuid)

    /** 项目音频目录：`audio/<novelId>`。 */
    fun novelDirRelative(novelId: Long): String = projectFiles.audioDir(novelId)

    /** 章节片段目录：`audio/<novelId>/segments/ch_<nnn>`。 */
    fun segmentsDirRelative(novelId: Long, chapterIdx: Int): String =
        projectFiles.segmentsDir(novelId, chapterIdx)

    /** 章节成品：`audio/<novelId>/chapter_<nnn>.m4a`。 */
    fun chapterAudioRelative(novelId: Long, chapterIdx: Int): String =
        projectFiles.chapterAudio(novelId, chapterIdx)

    /** 片段音频：`audio/<novelId>/segments/ch_<nnn>/seg_<nnnnnn>.mp3`。 */
    fun segmentAudioRelative(novelId: Long, chapterIdx: Int, segmentSeq: Int): String =
        projectFiles.segmentAudio(novelId, chapterIdx, segmentSeq)

    /** 片段哈希：`audio/<novelId>/segments/ch_<nnn>/seg_<nnnnnn>.hash`。 */
    fun segmentHashRelative(novelId: Long, chapterIdx: Int, segmentSeq: Int): String =
        projectFiles.segmentHash(novelId, chapterIdx, segmentSeq)

    /** 任一音频相对路径对应的 `.hash` 相对路径。 */
    fun hashRelativeTo(audioRelative: String): String =
        projectFiles.hashRelativeTo(audioRelative)

    /** 导出临时文件：`exports/<safeName>`（文件名已清洗、长度 ≤80）。 */
    fun exportRelative(name: String): String = projectFiles.exportFile(name)

    /** 把相对路径解析为根目录下的文件；拒绝绝对路径与 `..`。 */
    fun resolve(relative: String): File = projectFiles.resolve(relative)

    /** 取文件的相对路径（入库前用）；文件越出根目录时抛 [IllegalArgumentException]。 */
    fun relativeOf(file: File): String = projectFiles.relativePathOf(file)

    // ------------------------------------------------------------------
    // 目录创建
    // ------------------------------------------------------------------

    /** 创建 `audio/<novelId>` 与 `audio/<novelId>/segments`。 */
    fun ensureNovelDirectories(novelId: Long): File {
        val dir = projectFiles.ensureAudioDir(novelId)
        projectFiles.ensureDirectory("${novelDirRelative(novelId)}/segments")
        return dir
    }

    /** 创建章节片段目录 `audio/<novelId>/segments/ch_<nnn>`（含父目录）。 */
    fun ensureChapterDirectories(novelId: Long, chapterIdx: Int): File =
        projectFiles.ensureSegmentsDir(novelId, chapterIdx)

    // ------------------------------------------------------------------
    // 写入（返回相对路径）
    // ------------------------------------------------------------------

    /** 原子写入导入源文件，返回相对路径。调用方负责关闭 [input]。 */
    fun writeImport(input: InputStream, uuid: UUID = UUID.randomUUID()): String {
        val relative = importRelative(uuid)
        projectFiles.writeAtomic(relative, input)
        return relative
    }

    /** 原子写入章节成品，返回相对路径。调用方负责关闭 [input]。 */
    fun writeChapterAudio(novelId: Long, chapterIdx: Int, input: InputStream): String {
        val relative = chapterAudioRelative(novelId, chapterIdx)
        projectFiles.writeAtomic(relative, input)
        return relative
    }

    /** 原子写入片段音频，返回相对路径。调用方负责关闭 [input]。 */
    fun writeSegmentAudio(
        novelId: Long,
        chapterIdx: Int,
        segmentSeq: Int,
        input: InputStream,
    ): String {
        val relative = segmentAudioRelative(novelId, chapterIdx, segmentSeq)
        projectFiles.writeAtomic(relative, input)
        return relative
    }

    /**
     * 原子写入片段哈希（UTF-8），返回相对路径。
     *
     * 仅在对应音频已存在时允许写（§7.6：`.hash` 只在音频成功后写）。
     *
     * @throws IOException 音频文件不存在
     */
    fun writeSegmentHash(
        novelId: Long,
        chapterIdx: Int,
        segmentSeq: Int,
        hash: String,
    ): String {
        val audioRelative = segmentAudioRelative(novelId, chapterIdx, segmentSeq)
        if (!resolve(audioRelative).isFile) {
            throw IOException("音频不存在，拒绝写 .hash：$audioRelative")
        }
        val relative = segmentHashRelative(novelId, chapterIdx, segmentSeq)
        projectFiles.writeAtomic(relative, hash.toByteArray(Charsets.UTF_8))
        return relative
    }

    /** 原子写入任意已校验的相对路径（字节），返回同一相对路径。 */
    fun writeBytes(relative: String, bytes: ByteArray): String {
        projectFiles.writeAtomic(relative, bytes)
        return relative
    }

    /** 原子写入任意已校验的相对路径（输入流），返回同一相对路径。 */
    fun writeStream(relative: String, input: InputStream): String {
        projectFiles.writeAtomic(relative, input)
        return relative
    }

    // ------------------------------------------------------------------
    // 删除 / 空间预检
    // ------------------------------------------------------------------

    /** 删除项目音频目录；不存在时返回 true（幂等）。 */
    fun deleteNovel(novelId: Long): Boolean = projectFiles.deleteNovel(novelId)

    /** 删除相对路径对应的文件/目录；不存在返回 false。 */
    fun deleteFile(relative: String): Boolean = projectFiles.deleteFile(relative)

    /** 根目录所在卷的可用字节数。 */
    fun usableSpaceBytes(): Long = projectFiles.usableSpaceBytes()

    /** 是否有足够空间（默认写后至少保留 500MB）。 */
    fun hasSpaceFor(requiredBytes: Long, minFreeBytes: Long = MIN_FREE_BYTES): Boolean =
        projectFiles.hasSpaceFor(requiredBytes, minFreeBytes)

    /**
     * 空间预检，不足时抛 [InsufficientStorageException]，供批次开始前调用。
     */
    fun requireSpaceFor(requiredBytes: Long, minFreeBytes: Long = MIN_FREE_BYTES) {
        if (!hasSpaceFor(requiredBytes, minFreeBytes)) {
            throw InsufficientStorageException(
                requiredBytes = requiredBytes,
                availableBytes = usableSpaceBytes(),
                minFreeBytes = minFreeBytes,
            )
        }
    }

    companion object {
        /** 写批次前必须保留的最小空闲空间：500MB（§7.6）；与 [ProjectFiles.MIN_FREE_BYTES] 一致。 */
        const val MIN_FREE_BYTES: Long = 500L * 1024L * 1024L
    }
}

/** 空间预检失败；[requiredBytes] / [availableBytes] / [minFreeBytes] 均为字节。 */
class InsufficientStorageException(
    val requiredBytes: Long,
    val availableBytes: Long,
    val minFreeBytes: Long,
) : IOException(
    "存储空间不足：需要 ${bytesToMb(requiredBytes)}MB，可用 ${bytesToMb(availableBytes)}MB，" +
        "需保留 ${bytesToMb(minFreeBytes)}MB",
)

private fun bytesToMb(bytes: Long): Long = bytes / (1024L * 1024L)
