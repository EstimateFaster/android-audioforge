package com.estimatefaster.audioforge.core.storage

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * P3-11：StorageMaintenance 的孤儿识别、保留期清理、路径安全与统计语义。
 *
 * 纯 JVM：临时目录 + 注入时钟，不依赖 Android / DB。
 */
class StorageMaintenanceTest {

    private lateinit var root: File
    private lateinit var storage: AudioStorage
    private lateinit var maintenance: StorageMaintenance

    /** 注入的“当前时间”，避免用真实时钟造成抖动。 */
    private val now: Long = 1_700_000_000_000L

    private val hour: Long = 60L * 60L * 1000L
    private val day: Long = 24L * hour

    @Before
    fun setUp() {
        root = Files.createTempDirectory("storage-maintenance-").toFile()
        storage = AudioStorage(root)
        maintenance = StorageMaintenance(storage, now = { now })
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // 扫描：三类文件
    // ------------------------------------------------------------------

    @Test
    fun scan_classifies_referenced_orphans_and_expired_across_audio_imports_and_exports() = runTest {
        val referencedChapter = "audio/1/chapter_001.m4a"
        write(referencedChapter, bytes = 1000)
        val expiredChapter = "audio/1/chapter_002.m4a"
        write(expiredChapter, bytes = 2000, modifiedAt = now - 31 * day)
        val referencedSegment = "audio/1/segments/ch_001/seg_000001.mp3"
        write(referencedSegment, bytes = 300)
        val referencedHash = "audio/1/segments/ch_001/seg_000001.hash"
        write(referencedHash, bytes = 32)
        val orphanSegment = "audio/1/segments/ch_001/seg_000002.mp3"
        write(orphanSegment, bytes = 400)
        val referencedImport = "imports/ref.txt"
        write(referencedImport, bytes = 50)
        val orphanImport = "imports/orphan.txt"
        write(orphanImport, bytes = 60)
        val orphanExport = "exports/old.m4a"
        write(orphanExport, bytes = 70)

        val report = maintenance.scan(
            referencedAudio = setOf(referencedChapter, expiredChapter, referencedSegment),
            referencedImports = setOf(referencedImport),
        )

        assertEquals(listOf(expiredChapter), report.expired.map { it.relative })
        assertEquals(
            listOf(orphanExport, orphanImport, orphanSegment).sorted(),
            report.orphans.map { it.relative }.sorted(),
        )
        assertEquals(
            listOf(referencedChapter, referencedHash, referencedImport, referencedSegment).sorted(),
            report.referenced.map { it.relative }.sorted(),
        )
        assertEquals(3912L, report.totalBytes)
        assertEquals(1382L, report.referencedBytes)
        assertEquals(530L, report.orphanBytes)
        assertEquals(2000L, report.expiredBytes)
        assertEquals(30, report.retentionDays)
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun scan_treats_a_hash_as_referenced_when_its_audio_is_referenced() = runTest {
        val audio = "audio/2/segments/ch_001/seg_000001.mp3"
        write(audio, bytes = 500)
        val hash = "audio/2/segments/ch_001/seg_000001.hash"
        write(hash, bytes = 10)

        val report = maintenance.scan(setOf(audio), emptySet())

        assertTrue(report.referenced.any { it.relative == hash })
        assertTrue(report.orphans.none { it.relative == hash })
    }

    // ------------------------------------------------------------------
    // 孤儿清理
    // ------------------------------------------------------------------

    @Test
    fun clean_orphans_deletes_only_unreferenced_files_older_than_threshold() = runTest {
        val oldOrphan = "audio/1/chapter_001.m4a"
        val oldOrphanFile = write(oldOrphan, bytes = 1000, modifiedAt = now - 7 * hour)
        val freshOrphan = "audio/1/chapter_002.m4a"
        val freshOrphanFile = write(freshOrphan, bytes = 2000, modifiedAt = now - 1 * hour)
        val referencedOld = "audio/1/chapter_003.m4a"
        val referencedFile = write(referencedOld, bytes = 3000, modifiedAt = now - 7 * hour)

        val report = maintenance.cleanOrphans(
            referencedAudio = setOf(referencedOld),
            referencedImports = emptySet(),
            olderThanHours = 6,
        )

        assertEquals(listOf(oldOrphan), report.deleted.map { it.relative })
        assertEquals(1000L, report.freedBytes)
        assertFalse(oldOrphanFile.exists())
        assertTrue(freshOrphanFile.exists())
        assertTrue(referencedFile.exists())
        assertEquals(listOf(freshOrphan), report.skippedTooRecent)
        assertTrue(report.failures.isEmpty())
    }

    @Test
    fun clean_orphans_never_deletes_referenced_files_even_with_zero_threshold() = runTest {
        val audio = "audio/2/segments/ch_001/seg_000001.mp3"
        write(audio, bytes = 500, modifiedAt = now - 100 * day)
        val hash = "audio/2/segments/ch_001/seg_000001.hash"
        write(hash, bytes = 10, modifiedAt = now - 100 * day)

        val report = maintenance.cleanOrphans(
            referencedAudio = setOf(audio),
            referencedImports = emptySet(),
            olderThanHours = 0,
        )

        assertTrue(report.deleted.isEmpty())
        assertEquals(0L, report.freedBytes)
        assertTrue(storage.resolve(audio).exists())
        assertTrue(storage.resolve(hash).exists())
    }

    @Test
    fun clean_orphans_removes_stale_exports_and_imports_but_keeps_referenced_imports() = runTest {
        write("exports/stale.m4a", bytes = 100, modifiedAt = now - 48 * hour)
        write("exports/fresh.m4a", bytes = 200, modifiedAt = now - 1 * hour)
        write("imports/stale.txt", bytes = 300, modifiedAt = now - 48 * hour)
        write("imports/fresh.txt", bytes = 400, modifiedAt = now - 1 * hour)
        write("imports/used.txt", bytes = 500, modifiedAt = now - 48 * hour)

        val report = maintenance.cleanOrphans(
            referencedAudio = emptySet(),
            referencedImports = setOf("imports/used.txt"),
            olderThanHours = 6,
        )

        assertEquals(
            listOf("exports/stale.m4a", "imports/stale.txt"),
            report.deleted.map { it.relative },
        )
        assertEquals(400L, report.freedBytes)
        assertTrue(storage.resolve("imports/used.txt").exists())
        assertTrue(storage.resolve("exports/fresh.m4a").exists())
        assertTrue(storage.resolve("imports/fresh.txt").exists())
    }

    // ------------------------------------------------------------------
    // 保留期清理
    // ------------------------------------------------------------------

    @Test
    fun clean_expired_deletes_only_old_candidates_under_project_audio() = runTest {
        val oldCandidate = "audio/1/chapter_001.m4a"
        val oldCandidateFile = write(oldCandidate, bytes = 1000, modifiedAt = now - 31 * day)
        val oldNotCandidate = "audio/1/chapter_002.m4a"
        val oldNotCandidateFile = write(oldNotCandidate, bytes = 2000, modifiedAt = now - 31 * day)
        val oldReferenced = "audio/1/chapter_003.m4a"
        val oldReferencedFile = write(oldReferenced, bytes = 3000, modifiedAt = now - 31 * day)
        val freshCandidate = "audio/1/chapter_004.m4a"
        val freshCandidateFile = write(freshCandidate, bytes = 4000, modifiedAt = now - 1 * day)
        val importCandidate = "imports/old.txt"
        val importCandidateFile = write(importCandidate, bytes = 5000, modifiedAt = now - 31 * day)
        storage.ensureChapterDirectories(1, 0)

        val report = maintenance.cleanExpired(
            candidates = listOf(
                oldCandidate,
                oldReferenced,
                freshCandidate,
                importCandidate,
                "audio/1/chapter_099.m4a",
                "audio/1/segments",
            ),
            referencedAudio = setOf(oldReferenced),
            referencedImports = emptySet(),
            retentionDays = 30,
        )

        assertEquals(listOf(oldCandidate), report.deleted.map { it.relative })
        assertEquals(1000L, report.freedBytes)
        assertFalse(oldCandidateFile.exists())
        assertTrue(oldNotCandidateFile.exists())
        assertTrue(oldReferencedFile.exists())
        assertTrue(freshCandidateFile.exists())
        assertTrue(importCandidateFile.exists())
        assertTrue(storage.resolve("audio/1/segments").isDirectory)
        assertEquals(listOf(oldReferenced), report.skippedReferenced)
        assertEquals(listOf(freshCandidate), report.skippedTooRecent)
        assertEquals(listOf("audio/1/chapter_099.m4a"), report.skippedMissing)
        assertEquals(2, report.failureCount)
    }

    @Test
    fun clean_expired_protects_referenced_audio_and_its_hash() = runTest {
        val audio = "audio/3/chapter_001.m4a"
        write(audio, bytes = 800, modifiedAt = now - 40 * day)
        val hash = "audio/3/chapter_001.hash"
        write(hash, bytes = 8, modifiedAt = now - 40 * day)

        val report = maintenance.cleanExpired(
            candidates = listOf(audio, hash),
            referencedAudio = setOf(audio),
            retentionDays = 30,
        )

        assertTrue(report.deleted.isEmpty())
        assertEquals(listOf(audio, hash), report.skippedReferenced)
        assertTrue(storage.resolve(audio).exists())
        assertTrue(storage.resolve(hash).exists())
    }

    @Test
    fun default_retention_is_thirty_days() = runTest {
        assertEquals(30, maintenance.retentionDays)
        val old = "audio/1/chapter_001.m4a"
        write(old, bytes = 100, modifiedAt = now - 31 * day)
        val almostOld = "audio/1/chapter_002.m4a"
        write(almostOld, bytes = 200, modifiedAt = now - 29 * day)

        val report = maintenance.cleanExpired(listOf(old, almostOld))

        assertEquals(listOf(old), report.deleted.map { it.relative })
        assertEquals(listOf(almostOld), report.skippedTooRecent)
    }

    // ------------------------------------------------------------------
    // 路径安全
    // ------------------------------------------------------------------

    @Test
    fun delete_paths_outside_root_or_with_symlinks_are_rejected() = runTest {
        val outside = Files.createTempFile("storage-outside-", ".txt").toFile()
        outside.writeText("secret")
        try {
            val link = storage.resolve("audio/1/escape.m4a")
            link.parentFile.mkdirs()
            val linkCreated = runCatching {
                Files.createSymbolicLink(link.toPath(), outside.toPath())
            }.isSuccess
            storage.writeBytes("audio/1/real.m4a", ByteArray(10))
            storage.writeBytes("imports/keep.txt", ByteArray(10))

            val candidates = mutableListOf(
                "../escape.txt",
                outside.absolutePath,
                "audio/../imports/keep.txt",
                "C:\\Windows\\evil.txt",
                "audio/1/../../escape.m4a",
            )
            if (linkCreated) candidates += "audio/1/escape.m4a"

            val report = maintenance.cleanExpired(candidates, retentionDays = 0)

            assertEquals(candidates.size, report.failureCount)
            assertTrue(report.deleted.isEmpty())
            assertEquals("secret", outside.readText())
            assertTrue(storage.resolve("imports/keep.txt").exists())
            if (linkCreated) {
                assertTrue(Files.isSymbolicLink(link.toPath()))
                val scan = maintenance.scan(emptySet(), emptySet())
                assertTrue(scan.errors.any { it.relative == "audio/1/escape.m4a" })
                assertTrue(scan.referenced.none { it.relative == "audio/1/escape.m4a" })
                assertTrue(scan.orphans.none { it.relative == "audio/1/escape.m4a" })
            }
        } finally {
            outside.delete()
        }
    }

    @Test
    fun cleanup_failures_are_counted_with_reason_and_nothing_is_deleted() = runTest {
        storage.ensureChapterDirectories(1, 0)

        val report = maintenance.cleanExpired(
            candidates = listOf("audio/1/segments", "imports/nope.txt"),
            retentionDays = 0,
        )

        assertEquals(2, report.failureCount)
        assertTrue(report.deleted.isEmpty())
        assertTrue(report.failures.all { it.reason.isNotBlank() })
        assertTrue(
            report.failures.any {
                it.relative == "audio/1/segments" && it.reason.contains("普通文件")
            },
        )
        assertTrue(storage.resolve("audio/1/segments").isDirectory)
    }

    // ------------------------------------------------------------------
    // 空目录 / 缺失目录
    // ------------------------------------------------------------------

    @Test
    fun empty_or_missing_directories_do_not_crash() = runTest {
        val report = maintenance.scan(emptySet(), emptySet())
        assertTrue(report.isEmpty)
        assertEquals(0L, report.totalBytes)
        assertTrue(report.errors.isEmpty())

        val cleanup = maintenance.cleanOrphans(emptySet(), emptySet())
        assertTrue(cleanup.deleted.isEmpty())
        assertTrue(cleanup.failures.isEmpty())

        val emptyStats = maintenance.stats()
        assertEquals(0L, emptyStats.totalBytes)
        assertEquals(0, emptyStats.fileCount)
        assertTrue(emptyStats.perNovelBytes.isEmpty())

        // 根目录尚不存在时同样不崩溃。
        val missing = StorageMaintenance(
            AudioStorage(File(root, "not-created-yet")),
            now = { now },
        )
        assertTrue(missing.scan(emptySet(), emptySet()).isEmpty)
        assertEquals(0L, missing.stats().totalBytes)
        assertTrue(missing.cleanOrphans(emptySet(), emptySet()).deleted.isEmpty())
        val missingCleanup = missing.cleanExpired(listOf("audio/1/chapter_001.m4a"))
        assertTrue(missingCleanup.deleted.isEmpty())
        assertEquals(listOf("audio/1/chapter_001.m4a"), missingCleanup.skippedMissing)
    }

    // ------------------------------------------------------------------
    // 统计
    // ------------------------------------------------------------------

    @Test
    fun stats_reports_total_per_area_and_per_novel_then_tracks_freed_bytes() = runTest {
        write("audio/1/chapter_001.m4a", bytes = 1000, modifiedAt = now - 7 * hour)
        write("audio/1/chapter_002.m4a", bytes = 500, modifiedAt = now - 7 * hour)
        write("audio/2/chapter_001.m4a", bytes = 250, modifiedAt = now - 7 * hour)
        write("imports/source.txt", bytes = 100, modifiedAt = now - 1 * hour)
        write("exports/recent.m4a", bytes = 50, modifiedAt = now - 1 * hour)

        val before = maintenance.stats()
        assertEquals(1900L, before.totalBytes)
        assertEquals(1750L, before.audioBytes)
        assertEquals(100L, before.importsBytes)
        assertEquals(50L, before.exportsBytes)
        assertEquals(5, before.fileCount)
        assertEquals(1500L, before.bytesFor(1))
        assertEquals(250L, before.bytesFor(2))
        assertEquals(0L, before.bytesFor(9))

        val cleanup = maintenance.cleanOrphans(emptySet(), emptySet(), olderThanHours = 6)
        assertEquals(1750L, cleanup.freedBytes)
        assertEquals(3, cleanup.deletedCount)

        val after = maintenance.stats()
        assertEquals(before.totalBytes - cleanup.freedBytes, after.totalBytes)
        assertEquals(150L, after.totalBytes)
        assertEquals(0L, after.audioBytes)
        assertEquals(2, after.fileCount)
        assertTrue(after.perNovelBytes.isEmpty())
    }

    @Test
    fun has_space_for_uses_the_injected_storage() = runTest {
        assertTrue(maintenance.hasSpaceFor(0, minFreeBytes = 0))
        assertFalse(maintenance.hasSpaceFor(Long.MAX_VALUE, minFreeBytes = 0))
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 写入相对路径并设置 mtime（默认等于注入的 now）。 */
    private fun write(
        relative: String,
        bytes: Int = 100,
        modifiedAt: Long = now,
    ): File {
        storage.writeBytes(relative, ByteArray(bytes) { 1 })
        val file = storage.resolve(relative)
        assertTrue("无法设置 mtime：$relative", file.setLastModified(modifiedAt))
        return file
    }
}
