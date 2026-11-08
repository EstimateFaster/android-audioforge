package com.estimatefaster.audioforge.data.repository

import com.estimatefaster.audioforge.data.db.SegmentEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P2-02：片段替换事务、批量状态、按角色/章节重置、计数同步。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SegmentRepositoryTest {

    private lateinit var fixture: RepositoryFixture

    @Before
    fun setUp() {
        fixture = RepositoryFixture()
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun replace_is_transactional_and_syncs_chapter_counters() = runTest {
        val (novelId, chapterId, otherChapterId) = fixture.createTwoChapterNovel()

        val firstIds = fixture.segments.replace(
            chapterId,
            listOf(
                segment(chapterId, "旧片段一", status = "done"),
                segment(chapterId, "旧片段二"),
            ),
            updatedAt = 5_000L,
        )
        assertEquals(2, firstIds.size)
        assertEquals(listOf(0, 1), fixture.segments.list(chapterId).map { it.seq })
        assertChapterCounters(chapterId, segCount = 2, segDone = 1)

        val secondIds = fixture.segments.replace(
            chapterId,
            listOf(
                segment(chapterId, "新片段一", status = "done"),
                segment(chapterId, "新片段二", status = "done"),
                segment(chapterId, "新片段三"),
            ),
            updatedAt = 6_000L,
        )

        assertEquals(3, secondIds.size)
        assertEquals(listOf("新片段一", "新片段二", "新片段三"), fixture.segments.list(chapterId).map { it.text })
        assertEquals(listOf(0, 1, 2), fixture.segments.list(chapterId).map { it.seq })
        assertChapterCounters(chapterId, segCount = 3, segDone = 2)
        assertChapterCounters(otherChapterId, segCount = 0, segDone = 0)

        val summary = fixture.projects.observeProjects().first().single { it.id == novelId }
        assertEquals(3, summary.segmentCount)
        assertEquals(2, summary.segmentDoneCount)

        fixture.segments.replace(chapterId, emptyList(), updatedAt = 7_000L)
        assertTrue(fixture.segments.list(chapterId).isEmpty())
        assertChapterCounters(chapterId, segCount = 0, segDone = 0)
    }

    @Test
    fun replace_rolls_back_old_segments_when_insert_fails() = runTest {
        val (_, chapterId, _) = fixture.createTwoChapterNovel()
        fixture.segments.replace(
            chapterId,
            listOf(segment(chapterId, "保留一"), segment(chapterId, "保留二")),
            updatedAt = 5_000L,
        )
        assertChapterCounters(chapterId, segCount = 2, segDone = 0)

        fixture.db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_second_segment
            BEFORE INSERT ON segments
            WHEN NEW.seq = 1
            BEGIN
                SELECT RAISE(ABORT, 'boom');
            END
            """.trimIndent(),
        )

        var thrown: Throwable? = null
        try {
            fixture.segments.replace(
                chapterId,
                listOf(
                    segment(chapterId, "新一"),
                    segment(chapterId, "新二"),
                    segment(chapterId, "新三"),
                ),
                updatedAt = 6_000L,
            )
        } catch (t: Throwable) {
            thrown = t
        }

        assertNotNull("片段插入失败必须抛出异常", thrown)
        assertEquals(listOf("保留一", "保留二"), fixture.segments.list(chapterId).map { it.text })
        assertChapterCounters(chapterId, segCount = 2, segDone = 0)
    }

    @Test
    fun batch_set_status_updates_all_affected_chapters() = runTest {
        val (_, chapterId, otherChapterId) = fixture.createTwoChapterNovel()
        fixture.segments.replace(
            chapterId,
            listOf(
                segment(chapterId, "一"),
                segment(chapterId, "二"),
                segment(chapterId, "三"),
            ),
            updatedAt = 5_000L,
        )
        fixture.segments.replace(
            otherChapterId,
            listOf(segment(otherChapterId, "别章")),
            updatedAt = 5_000L,
        )

        val ids = fixture.segments.list(chapterId).take(2).map { it.id }
        fixture.segments.setStatus(ids, status = "done", updatedAt = 9_000L)

        assertEquals(listOf("done", "done", "pending"), fixture.segments.list(chapterId).map { it.status })
        assertEquals(2, fixture.segments.countDone(chapterId))
        assertEquals(3, fixture.segments.count(chapterId))
        assertChapterCounters(chapterId, segCount = 3, segDone = 2)
        assertChapterCounters(otherChapterId, segCount = 1, segDone = 0)

        // 空 ids 是 no-op，不抛异常。
        fixture.segments.setStatus(emptyList(), status = "done", updatedAt = 9_001L)
    }

    @Test
    fun reset_by_role_clears_audio_state_and_only_affects_that_role() = runTest {
        val (novelId, chapterId, otherChapterId) = fixture.createTwoChapterNovel()
        fixture.segments.replace(
            chapterId,
            listOf(
                segment(chapterId, "角色一", roleId = "role_0001", status = "done").copy(
                    audioRelPath = "audio/1/seg_000001.mp3",
                    durationMs = 1_200,
                    ttsKey = "key-a",
                ),
                segment(chapterId, "角色二", roleId = "role_0002", status = "done").copy(
                    audioRelPath = "audio/1/seg_000002.mp3",
                    durationMs = 900,
                    ttsKey = "key-b",
                ),
            ),
            updatedAt = 5_000L,
        )
        fixture.segments.replace(
            otherChapterId,
            listOf(
                segment(otherChapterId, "另一章角色一", roleId = "role_0001", status = "done").copy(
                    audioRelPath = "audio/1/seg_000003.mp3",
                ),
                segment(otherChapterId, "另一章角色二", roleId = "role_0002", status = "done"),
            ),
            updatedAt = 5_000L,
        )
        fixture.chapters.updateAudioStatus(chapterId, "done", updatedAt = 5_100L)
        fixture.chapters.updateAudioStatus(otherChapterId, "done", updatedAt = 5_100L)

        val affected = fixture.segments.resetByRole(
            novelId,
            roleId = "role_0001",
            updatedAt = 7_000L,
        )

        assertEquals(2, affected)
        for (chapter in listOf(chapterId, otherChapterId)) {
            val segments = fixture.segments.list(chapter)
            val reset = segments.first { it.roleId == "role_0001" }
            assertEquals("pending", reset.status)
            assertNull(reset.audioRelPath)
            assertNull(reset.durationMs)
            assertNull(reset.ttsKey)
            assertNull(reset.errorMsg)
            assertEquals(7_000L, reset.updatedAt)

            val untouched = segments.first { it.roleId == "role_0002" }
            assertEquals("done", untouched.status)
            if (chapter == chapterId) assertNotNull(untouched.audioRelPath)
        }
        assertChapterCounters(chapterId, segCount = 2, segDone = 1)
        assertChapterCounters(otherChapterId, segCount = 2, segDone = 1)
        assertEquals("pending", fixture.chapters.getChapter(chapterId)?.audioStatus)
        assertEquals("pending", fixture.chapters.getChapter(otherChapterId)?.audioStatus)

        assertEquals(0, fixture.segments.resetByRole(novelId, roleId = "role_9999"))
    }

    @Test
    fun reset_by_chapter_only_resets_that_chapter() = runTest {
        val (_, chapterId, otherChapterId) = fixture.createTwoChapterNovel()
        fixture.segments.replace(
            chapterId,
            listOf(
                segment(chapterId, "本章一", roleId = "role_0001", status = "done"),
                segment(chapterId, "本章二", roleId = "role_0002", status = "done"),
            ),
            updatedAt = 5_000L,
        )
        fixture.segments.replace(
            otherChapterId,
            listOf(segment(otherChapterId, "别章", roleId = "role_0001", status = "done")),
            updatedAt = 5_000L,
        )
        fixture.chapters.updateAudioStatus(chapterId, "done", updatedAt = 5_100L)
        fixture.chapters.updateAudioStatus(otherChapterId, "done", updatedAt = 5_100L)

        fixture.segments.resetByChapter(chapterId, updatedAt = 7_000L)

        assertEquals(listOf("pending", "pending"), fixture.segments.list(chapterId).map { it.status })
        assertEquals(listOf("done"), fixture.segments.list(otherChapterId).map { it.status })
        assertChapterCounters(chapterId, segCount = 2, segDone = 0)
        assertChapterCounters(otherChapterId, segCount = 1, segDone = 1)
        assertEquals("pending", fixture.chapters.getChapter(chapterId)?.audioStatus)
        assertEquals("done", fixture.chapters.getChapter(otherChapterId)?.audioStatus)
    }

    @Test
    fun replace_missing_chapter_throws_and_get_returns_null_for_unknown_id() = runTest {
        var thrown: Throwable? = null
        try {
            fixture.segments.replace(chapterId = 9_999L, segments = emptyList())
        } catch (t: Throwable) {
            thrown = t
        }

        assertTrue(thrown is IllegalArgumentException)
        assertNull(fixture.segments.get(id = 9_999L))
    }

    private suspend fun assertChapterCounters(chapterId: Long, segCount: Int, segDone: Int) {
        val chapter = fixture.chapters.getChapter(chapterId) ?: error("章节不存在：$chapterId")
        assertEquals("segCount", segCount, chapter.segCount)
        assertEquals("segDone", segDone, chapter.segDone)
    }

    private fun segment(
        chapterId: Long,
        text: String,
        roleId: String = "narrator",
        status: String = "pending",
    ): SegmentEntity = SegmentEntity(
        chapterId = chapterId,
        seq = 0,
        type = if (roleId == "narrator") "narration" else "dialogue",
        roleId = roleId,
        emotion = "neutral",
        text = text,
        status = status,
    )
}
