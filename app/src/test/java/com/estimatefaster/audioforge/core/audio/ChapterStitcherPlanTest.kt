package com.estimatefaster.audioforge.core.audio

import com.estimatefaster.audioforge.core.model.SegmentType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** P2-10：拼接计划与 concat 清单的纯 JVM 测试（不依赖 FFmpeg/Android）。 */
class ChapterStitcherPlanTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("stitch-plan-").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun empty_list_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            ChapterStitcher.buildPlan(emptyList(), emptyList())
        }
    }

    @Test
    fun files_types_length_mismatch_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            ChapterStitcher.buildPlan(
                files("a.mp3", "b.mp3"),
                listOf(SegmentType.Narration),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterStitcher.buildPlan(
                files("a.mp3"),
                listOf(SegmentType.Narration, SegmentType.Dialogue),
            )
        }
    }

    @Test
    fun dialogue_to_narration_uses_paragraph_silence_700() {
        val plan = ChapterStitcher.buildPlan(
            files("a.mp3", "b.mp3"),
            listOf(SegmentType.Dialogue, SegmentType.Narration),
        )

        assertEquals(700, plan[0].silenceAfterMs)
        assertEquals(0, plan[1].silenceAfterMs)
    }

    @Test
    fun other_transitions_use_narrator_silence_350() {
        val narrationToNarration = ChapterStitcher.buildPlan(
            files("a.mp3", "b.mp3"),
            listOf(SegmentType.Narration, SegmentType.Narration),
        )
        assertEquals(350, narrationToNarration[0].silenceAfterMs)

        val narrationToDialogue = ChapterStitcher.buildPlan(
            files("a.mp3", "b.mp3"),
            listOf(SegmentType.Narration, SegmentType.Dialogue),
        )
        assertEquals(350, narrationToDialogue[0].silenceAfterMs)
    }

    @Test
    fun consecutive_dialogue_uses_350() {
        val plan = ChapterStitcher.buildPlan(
            files("a.mp3", "b.mp3", "c.mp3"),
            listOf(SegmentType.Dialogue, SegmentType.Dialogue, SegmentType.Narration),
        )

        assertEquals(350, plan[0].silenceAfterMs)
        assertEquals(700, plan[1].silenceAfterMs)
        assertEquals(0, plan[2].silenceAfterMs)
    }

    @Test
    fun last_item_has_no_silence() {
        val plan = ChapterStitcher.buildPlan(
            files("a.mp3", "b.mp3", "c.mp3"),
            listOf(SegmentType.Dialogue, SegmentType.Narration, SegmentType.Dialogue),
        )

        assertEquals(listOf(700, 350, 0), plan.map { it.silenceAfterMs })
    }

    @Test
    fun plan_preserves_order_and_types() {
        val plan = ChapterStitcher.buildPlan(
            files("a.mp3", "b.mp3"),
            listOf(SegmentType.Dialogue, SegmentType.Narration),
        )

        assertEquals(listOf("a.mp3", "b.mp3"), plan.map { it.file.name })
        assertEquals(
            listOf(SegmentType.Dialogue, SegmentType.Narration),
            plan.map { it.type },
        )
    }

    @Test
    fun custom_pauses_are_used() {
        val plan = ChapterStitcher.buildPlan(
            files("a.mp3", "b.mp3", "c.mp3"),
            listOf(SegmentType.Dialogue, SegmentType.Narration, SegmentType.Dialogue),
            narratorSilenceMs = 100,
            paragraphSilenceMs = 900,
        )

        assertEquals(listOf(900, 100, 0), plan.map { it.silenceAfterMs })
    }

    @Test
    fun negative_pauses_are_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ChapterStitcher.buildPlan(
                files("a.mp3"),
                listOf(SegmentType.Narration),
                narratorSilenceMs = -1,
            )
        }
    }

    @Test
    fun manifest_escapes_single_quotes_and_preserves_spaces() {
        val file = File(tempDir, "a b'c.mp3")
        val manifest = ChapterStitcher.buildConcatManifest(listOf(file))

        assertEquals("file '${tempDir.absolutePath}/a b'\\''c.mp3'\n", manifest)
        assertTrue(manifest.startsWith("file '"))
    }

    @Test
    fun manifest_keeps_input_order() {
        val first = File(tempDir, "first.mp3")
        val second = File(tempDir, "second.mp3")

        val manifest = ChapterStitcher.buildConcatManifest(listOf(first, second))

        val lines = manifest.trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("first.mp3"))
        assertTrue(lines[1].contains("second.mp3"))
    }

    @Test
    fun manifest_rejects_non_ascii_paths() {
        assertThrows(IllegalArgumentException::class.java) {
            ChapterStitcher.buildConcatManifest(listOf(File(tempDir, "中文.mp3")))
        }
    }

    @Test
    fun manifest_rejects_empty_input_and_invalid_max_chars() {
        assertThrows(IllegalArgumentException::class.java) {
            ChapterStitcher.buildConcatManifest(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterStitcher.buildConcatManifest(
                listOf(File(tempDir, "a.mp3")),
                maxChars = 0,
            )
        }
    }

    @Test
    fun manifest_max_chars_guard() {
        val files = listOf(File(tempDir, "a.mp3"))
        val manifest = ChapterStitcher.buildConcatManifest(files)

        assertEquals(manifest, ChapterStitcher.buildConcatManifest(files, manifest.length))
        assertThrows(IllegalArgumentException::class.java) {
            ChapterStitcher.buildConcatManifest(files, manifest.length - 1)
        }
    }

    private fun files(vararg names: String): List<File> = names.map { File(tempDir, it) }
}
