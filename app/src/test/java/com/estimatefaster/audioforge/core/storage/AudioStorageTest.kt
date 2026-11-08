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
import java.nio.file.Files
import java.util.UUID

/** P2-05：AudioStorage 只返回相对路径，并封装原子写/目录/删除/空间预检。 */
class AudioStorageTest {

    private lateinit var root: File
    private lateinit var storage: AudioStorage

    private val uuid: UUID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")

    @Before
    fun setUp() {
        root = Files.createTempDirectory("audio-storage-").toFile()
        storage = AudioStorage(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun paths_match_the_documented_layout() {
        assertEquals("imports/$uuid.txt", storage.importRelative(uuid))
        assertEquals("audio/7", storage.novelDirRelative(7))
        assertEquals("audio/7/segments/ch_001", storage.segmentsDirRelative(7, 0))
        assertEquals("audio/7/chapter_001.m4a", storage.chapterAudioRelative(7, 0))
        assertEquals(
            "audio/7/segments/ch_001/seg_000001.mp3",
            storage.segmentAudioRelative(7, 0, 0),
        )
        assertEquals(
            "audio/7/segments/ch_001/seg_000001.hash",
            storage.segmentHashRelative(7, 0, 0),
        )
        assertEquals(
            "audio/7/chapter_001.hash",
            storage.hashRelativeTo("audio/7/chapter_001.m4a"),
        )
        assertEquals("exports/书名_第1章", storage.exportRelative("书名:第1章"))
    }

    @Test
    fun write_import_returns_relative_path() {
        val relative = storage.writeImport(ByteArrayInputStream("正文".toByteArray()), uuid)
        assertEquals("imports/$uuid.txt", relative)
        assertFalse(File(relative).isAbsolute)
        assertEquals("正文", storage.resolve(relative).readText(Charsets.UTF_8))
        assertEquals(relative, storage.relativeOf(storage.resolve(relative)))
    }

    @Test
    fun chapter_and_segment_writes_return_relative_paths() {
        val chapter = storage.writeChapterAudio(3, 0, ByteArrayInputStream("m4a".toByteArray()))
        val segment = storage.writeSegmentAudio(3, 0, 0, ByteArrayInputStream("mp3".toByteArray()))

        assertEquals("audio/3/chapter_001.m4a", chapter)
        assertEquals("audio/3/segments/ch_001/seg_000001.mp3", segment)
        assertTrue(storage.resolve(chapter).isFile)
        assertTrue(storage.resolve(segment).isFile)
        assertEquals(chapter, storage.relativeOf(storage.resolve(chapter)))
        assertEquals(segment, storage.relativeOf(storage.resolve(segment)))
    }

    @Test
    fun hash_requires_existing_audio_then_writes_relative_path() {
        assertThrows(IOException::class.java) {
            storage.writeSegmentHash(3, 0, 0, "abc123")
        }

        storage.writeSegmentAudio(3, 0, 0, ByteArrayInputStream("mp3".toByteArray()))
        val hash = storage.writeSegmentHash(3, 0, 0, "abc123")
        assertEquals("audio/3/segments/ch_001/seg_000001.hash", hash)
        assertEquals("abc123", storage.resolve(hash).readText())
    }

    @Test
    fun directories_are_created_on_demand() {
        assertTrue(storage.ensureNovelDirectories(3).isDirectory)
        assertTrue(File(root, "audio/3/segments").isDirectory)

        val chapterDir = storage.ensureChapterDirectories(3, 0)
        assertTrue(chapterDir.isDirectory)
        assertTrue(File(root, "audio/3/segments/ch_001").isDirectory)
    }

    @Test
    fun delete_novel_keeps_other_projects_and_imports() {
        storage.writeChapterAudio(1, 0, ByteArrayInputStream("one".toByteArray()))
        storage.writeChapterAudio(2, 0, ByteArrayInputStream("two".toByteArray()))
        storage.writeImport(ByteArrayInputStream("src".toByteArray()), uuid)

        assertTrue(storage.deleteNovel(1))
        assertFalse(File(root, "audio/1").exists())
        assertTrue(File(root, "audio/2/chapter_001.m4a").isFile)
        assertTrue(storage.resolve("imports/$uuid.txt").isFile)
        assertTrue(storage.deleteNovel(1))
    }

    @Test
    fun writes_reject_absolute_or_traversing_paths() {
        assertThrows(IllegalArgumentException::class.java) {
            storage.writeBytes("/abs/file", "x".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            storage.writeStream("../escape", ByteArrayInputStream("x".toByteArray()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            storage.resolve("../escape")
        }
    }

    @Test
    fun relative_of_outside_file_is_rejected() {
        val outside = File(root.parentFile, "outside-${System.nanoTime()}.m4a")
        assertThrows(IllegalArgumentException::class.java) { storage.relativeOf(outside) }
    }

    @Test
    fun space_precheck_reports_insufficient_storage() {
        val usable = storage.usableSpaceBytes()
        assertTrue(usable > 0)
        assertEquals(ProjectFiles.MIN_FREE_BYTES, AudioStorage.MIN_FREE_BYTES)
        assertTrue(storage.hasSpaceFor(0, minFreeBytes = 0))
        storage.requireSpaceFor(0, minFreeBytes = 0)

        assertFalse(storage.hasSpaceFor(0, minFreeBytes = Long.MAX_VALUE))
        val error = assertThrows(InsufficientStorageException::class.java) {
            storage.requireSpaceFor(0, minFreeBytes = Long.MAX_VALUE)
        }
        assertEquals(0L, error.requiredBytes)
        assertTrue("可用空间应为正：${error.availableBytes}", error.availableBytes > 0)
        assertEquals(Long.MAX_VALUE, error.minFreeBytes)
        assertTrue(error.message!!.contains("存储空间不足"))
    }

    @Test
    fun project_files_can_be_shared_or_constructed_from_root() {
        val shared = AudioStorage(ProjectFiles(root))
        assertEquals(root.canonicalPath, shared.root.canonicalPath)
        assertEquals(
            storage.chapterAudioRelative(1, 0),
            shared.chapterAudioRelative(1, 0),
        )
    }
}
