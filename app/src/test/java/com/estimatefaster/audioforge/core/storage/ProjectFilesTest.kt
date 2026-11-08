package com.estimatefaster.audioforge.core.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID

/** P2-05：ProjectFiles 的路径格式、越界校验、原子写、删除、清洗与空间预检。 */
class ProjectFilesTest {

    private lateinit var root: File
    private lateinit var files: ProjectFiles

    @Before
    fun setUp() {
        root = Files.createTempDirectory("project-files-").toFile()
        files = ProjectFiles(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // 路径格式
    // ------------------------------------------------------------------

    @Test
    fun chapter_audio_path_is_zero_padded_and_one_based() {
        assertEquals("audio/7/chapter_001.m4a", files.chapterAudio(7, 0))
        assertEquals("audio/7/chapter_042.m4a", files.chapterAudio(7, 41))
        assertEquals("audio/7/chapter_1000.m4a", files.chapterAudio(7, 999))
        assertEquals("chapter_007.m4a", files.chapterFileName(6))
    }

    @Test
    fun segment_paths_are_zero_padded_and_one_based() {
        assertEquals(
            "audio/7/segments/ch_001/seg_000001.mp3",
            files.segmentAudio(7, 0, 0),
        )
        assertEquals(
            "audio/7/segments/ch_042/seg_000042.mp3",
            files.segmentAudio(7, 41, 41),
        )
        assertEquals("audio/7/segments/ch_001", files.segmentsDir(7, 0))
        assertEquals("seg_000001.mp3", files.segmentFileName(0))
    }

    @Test
    fun segment_hash_path_shares_base_name_with_audio() {
        assertEquals(
            "audio/7/segments/ch_001/seg_000001.hash",
            files.segmentHash(7, 0, 0),
        )
        assertEquals(
            "audio/7/chapter_001.hash",
            files.hashRelativeTo("audio/7/chapter_001.m4a"),
        )
        assertEquals("audio/7/chapter_001.hash", files.hashRelativeTo("audio/7/chapter_001"))
    }

    @Test
    fun import_audio_and_export_paths_are_relative() {
        val uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        assertEquals("imports/123e4567-e89b-12d3-a456-426614174000.txt", files.importFile(uuid))
        assertEquals(files.importFile(uuid), files.importFile(uuid.toString()))
        assertEquals("imports", files.importsDir())
        assertEquals("exports", files.exportsDir())
        assertEquals("exports/我的_小说", files.exportFile("我的:小说"))
    }

    @Test
    fun relative_paths_never_contain_absolute_prefix_or_traversal() {
        val uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val relatives = listOf(
            files.importFile(uuid),
            files.chapterAudio(1, 0),
            files.segmentAudio(1, 0, 0),
            files.segmentHash(1, 0, 0),
            files.exportFile("书名-第1章"),
        )
        relatives.forEach { relative ->
            assertFalse("不能是绝对路径：$relative", File(relative).isAbsolute)
            assertFalse("不能以 / 开头：$relative", relative.startsWith("/"))
            assertFalse("不能含 ..：$relative", relative.contains(".."))
            assertFalse("不能含反斜杠：$relative", relative.contains('\\'))
            val resolved = files.resolve(relative)
            assertTrue(
                "必须落在根目录内：$relative -> $resolved",
                resolved.canonicalPath.startsWith(root.canonicalPath + File.separator),
            )
        }
    }

    @Test
    fun invalid_identifiers_are_rejected() {
        assertThrows(IllegalArgumentException::class.java) { files.chapterAudio(0, 0) }
        assertThrows(IllegalArgumentException::class.java) { files.chapterAudio(1, -1) }
        assertThrows(IllegalArgumentException::class.java) { files.segmentAudio(1, 0, -1) }
        assertThrows(IllegalArgumentException::class.java) { files.importFile("not-a-uuid") }
    }

    // ------------------------------------------------------------------
    // resolve 越界
    // ------------------------------------------------------------------

    @Test
    fun resolve_accepts_normal_relative_path() {
        val target = files.resolve("audio/1/chapter_001.m4a")
        assertEquals(
            File(root, "audio/1/chapter_001.m4a").canonicalPath,
            target.canonicalPath,
        )
    }

    @Test
    fun resolve_rejects_parent_traversal() {
        listOf("../x", "audio/../../x", "..", "audio/../1").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                files.resolve(path)
            }
        }
    }

    @Test
    fun resolve_rejects_absolute_paths() {
        listOf("/abs", "\\abs", "C:\\x", "C:/x").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                files.resolve(path)
            }
        }
    }

    @Test
    fun resolve_rejects_blank_and_malformed_paths() {
        listOf("", "   ", "audio//x", "audio/./x", "audio/1/").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                files.resolve(path)
            }
        }
    }

    @Test
    fun relative_path_of_resolved_file_round_trips() {
        val relative = files.chapterAudio(3, 0)
        assertEquals(relative, files.relativePathOf(files.resolve(relative)))
        assertEquals(".", files.relativePathOf(files.root))
    }

    @Test
    fun relative_path_of_file_outside_root_is_rejected() {
        val outside = File(root.parentFile, "outside-${System.nanoTime()}.txt")
        assertThrows(IllegalArgumentException::class.java) { files.relativePathOf(outside) }
    }

    // ------------------------------------------------------------------
    // 目录创建
    // ------------------------------------------------------------------

    @Test
    fun directory_creation_is_idempotent() {
        assertTrue(files.ensureImportsDir().isDirectory)
        assertTrue(files.ensureImportsDir().isDirectory)
        assertTrue(files.ensureExportsDir().isDirectory)
        val segments = files.ensureSegmentsDir(1, 0)
        assertTrue(segments.isDirectory)
        assertTrue(File(root, "audio/1/segments/ch_001").isDirectory)
    }

    // ------------------------------------------------------------------
    // 原子写
    // ------------------------------------------------------------------

    @Test
    fun atomic_write_bytes_creates_parents_and_leaves_no_tmp() {
        val relative = "audio/1/chapter_001.m4a"
        val payload = "hello audio".toByteArray()
        files.writeAtomic(relative, payload)

        val target = files.resolve(relative)
        assertEquals("hello audio", target.readText())
        assertTrue(target.isFile)
        assertTrue("不能残留 .tmp", tempFiles().isEmpty())
    }

    @Test
    fun atomic_write_replaces_existing_file() {
        val relative = "audio/1/chapter_001.m4a"
        files.writeAtomic(relative, "a much longer original content".toByteArray())
        files.writeAtomic(relative, "new".toByteArray())

        assertEquals("new", files.resolve(relative).readText())
        assertTrue(tempFiles().isEmpty())
    }

    @Test
    fun atomic_write_accepts_input_stream() {
        val relative = "imports/stream.txt"
        files.writeAtomic(relative, ByteArrayInputStream("来自输入流".toByteArray(Charsets.UTF_8)))

        assertEquals("来自输入流", files.resolve(relative).readText(Charsets.UTF_8))
        assertTrue(tempFiles().isEmpty())
    }

    @Test
    fun failed_write_leaves_no_target_and_no_tmp() {
        // 用普通文件占住目录位置，让 mkdirs 失败。
        val blocker = File(root, "audio/1")
        assertTrue(blocker.parentFile.mkdirs())
        blocker.writeText("blocker")

        assertThrows(IOException::class.java) {
            files.writeAtomic("audio/1/chapter_001.m4a", "data".toByteArray())
        }
        assertFalse(files.resolve("audio/1/chapter_001.m4a").exists())
        assertTrue(tempFiles().isEmpty())
    }

    @Test
    fun stream_failure_does_not_leave_partial_file() {
        val relative = "audio/1/chapter_001.m4a"
        assertThrows(IOException::class.java) {
            files.writeAtomic(relative, FailingInputStream("partial".toByteArray()))
        }
        assertFalse(files.resolve(relative).exists())
        assertTrue(tempFiles().isEmpty())
    }

    @Test
    fun write_atomic_file_overload_rejects_outside_root() {
        val outside = File(root.parentFile, "outside-${System.nanoTime()}.m4a")
        assertThrows(IllegalArgumentException::class.java) {
            files.writeAtomic(outside, "x".toByteArray())
        }
        assertFalse(outside.exists())
    }

    // ------------------------------------------------------------------
    // 删除
    // ------------------------------------------------------------------

    @Test
    fun delete_novel_only_removes_target_project() {
        files.writeAtomic("audio/1/chapter_001.m4a", "one".toByteArray())
        files.writeAtomic("audio/1/segments/ch_001/seg_000001.mp3", "one-seg".toByteArray())
        files.writeAtomic("audio/2/chapter_001.m4a", "two".toByteArray())
        val importRelative = files.importFile(
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
        )
        files.writeAtomic(importRelative, "source".toByteArray())

        assertTrue(files.deleteNovel(1))
        assertFalse(File(root, "audio/1").exists())
        assertTrue(File(root, "audio/2/chapter_001.m4a").isFile)
        assertTrue(files.resolve(importRelative).isFile)

        // 幂等：不存在也返回 true。
        assertTrue(files.deleteNovel(1))
        assertTrue(files.deleteNovel(99))
        assertTrue(File(root, "audio/2").exists())
    }

    @Test
    fun delete_file_removes_only_that_relative_path() {
        files.writeAtomic("exports/book.txt", "x".toByteArray())
        assertTrue(files.deleteFile("exports/book.txt"))
        assertFalse(File(root, "exports/book.txt").exists())
        assertFalse(files.deleteFile("exports/book.txt"))

        assertThrows(IllegalArgumentException::class.java) { files.deleteFile("../book.txt") }
    }

    // ------------------------------------------------------------------
    // 文件名清洗
    // ------------------------------------------------------------------

    @Test
    fun sanitize_replaces_illegal_characters() {
        assertEquals(
            "a_b_c_d_e_f_g_h_i_j",
            ProjectFiles.sanitizeFileName("a\\b/c:d*e?f\"g<h>i|j"),
        )
        assertEquals("a_b", ProjectFiles.sanitizeFileName("a\nb"))
        assertEquals("title.txt", ProjectFiles.sanitizeFileName("  title.txt  "))
    }

    @Test
    fun sanitize_handles_empty_and_dot_only_names() {
        assertEquals("export", ProjectFiles.sanitizeFileName(""))
        assertEquals("export", ProjectFiles.sanitizeFileName("   "))
        assertEquals("export", ProjectFiles.sanitizeFileName("..."))
    }

    @Test
    fun sanitize_limits_length_and_keeps_extension() {
        val longName = "x".repeat(200) + ".m4a"
        val sanitized = ProjectFiles.sanitizeFileName(longName)
        assertTrue("长度必须 ≤80：${sanitized.length}", sanitized.length <= ProjectFiles.MAX_FILE_NAME)
        assertTrue("应保留扩展名：$sanitized", sanitized.endsWith(".m4a"))

        val longCjk = ProjectFiles.sanitizeFileName("长".repeat(120))
        assertEquals(ProjectFiles.MAX_FILE_NAME, longCjk.length)
    }

    // ------------------------------------------------------------------
    // 空间预检
    // ------------------------------------------------------------------

    @Test
    fun has_space_for_insufficient_requirements() {
        val usable = files.usableSpaceBytes()
        assertTrue("临时目录应有可用空间：$usable", usable > 0)

        // 9EB 远超任何真实卷的可用空间，等价于「required 超过可用空间」。
        assertFalse(files.hasSpaceFor(Long.MAX_VALUE / 2, minFreeBytes = 0))
        assertFalse(files.hasSpaceFor(Long.MAX_VALUE))

        assertTrue(files.hasSpaceFor(0, minFreeBytes = 0))
        assertEquals(usable >= ProjectFiles.MIN_FREE_BYTES, files.hasSpaceFor(0))
        assertEquals(500L * 1024L * 1024L, ProjectFiles.MIN_FREE_BYTES)
    }

    @Test
    fun has_space_for_huge_min_free_is_false() {
        assertFalse(files.hasSpaceFor(0, minFreeBytes = Long.MAX_VALUE))
        assertFalse(files.hasSpaceFor(1024, minFreeBytes = Long.MAX_VALUE))
    }

    @Test
    fun has_space_for_rejects_negative_inputs() {
        assertThrows(IllegalArgumentException::class.java) {
            files.hasSpaceFor(-1, minFreeBytes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            files.hasSpaceFor(0, minFreeBytes = -1)
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun tempFiles(): List<File> = root.walkTopDown()
        .filter { it.isFile && it.name.endsWith(ProjectFiles.TEMP_SUFFIX) }
        .toList()

    /** 先返回一段字节，之后每次读取都抛 [IOException]。 */
    private class FailingInputStream(private val prefix: ByteArray) : InputStream() {
        private var offset = 0

        override fun read(): Int = throw IOException("single-byte read 不应被调用")

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (offset >= prefix.size) throw IOException("模拟读取失败")
            val count = minOf(len, prefix.size - offset)
            System.arraycopy(prefix, offset, buffer, off, count)
            offset += count
            return count
        }
    }
}
