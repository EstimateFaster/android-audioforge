package com.estimatefaster.audioforge.data.repository

import com.estimatefaster.audioforge.data.db.ChapterBrief
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P2-02：章节 brief 投影、正文读取、状态/警告/计数更新、分页。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChapterRepositoryTest {

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
    fun observe_briefs_never_loads_raw_text_but_get_chapter_does() = runTest {
        val rawText = "这一段正文只能通过详情接口读取。".repeat(30)
        val novelId = fixture.projects.createProject(
            title = "章节投影",
            sourceName = "章节投影.txt",
            sourceRelPath = "imports/chapter.txt",
            chapters = listOf(ChapterInput(title = "第一章", rawText = rawText)),
            createdAt = 1_000L,
        )
        val chapterId = fixture.chapters.observeBriefs(novelId).first().single().id

        val brief = fixture.chapters.observeBriefs(novelId).first().single()
        assertEquals(chapterId, brief.id)
        assertEquals(0, brief.idx)
        assertEquals("第一章", brief.title)
        assertTrue(
            "ChapterBrief 不应包含 rawText 字段",
            ChapterBrief::class.java.declaredFields.none { it.name == "rawText" },
        )

        assertEquals(rawText, fixture.chapters.getRawText(chapterId))
        assertEquals(rawText, fixture.chapters.getChapter(chapterId)?.rawText)
        assertNull(fixture.chapters.getChapter(id = 9_999L))
        assertNull(fixture.chapters.getRawText(id = 9_999L))
    }

    @Test
    fun parse_audio_warn_and_counter_updates_are_persisted() = runTest {
        val novelId = fixture.projects.createProject(
            title = "章节更新",
            sourceName = "章节更新.txt",
            sourceRelPath = "imports/update.txt",
            chapters = listOf(ChapterInput(title = "第一章", rawText = "正文")),
            createdAt = 1_000L,
        )
        val chapterId = fixture.chapters.observeBriefs(novelId).first().single().id

        fixture.chapters.updateParseStatus(
            chapterId,
            status = "running",
            error = null,
            updatedAt = 2_001L,
        )
        fixture.chapters.updateAudioStatus(
            chapterId,
            status = "error",
            error = "TTS 超时",
            updatedAt = 2_002L,
        )
        fixture.chapters.updateWarn(chapterId, "覆盖率 0.91", updatedAt = 2_003L)
        fixture.chapters.updateCounters(
            chapterId,
            segCount = 6,
            segDone = 4,
            updatedAt = 2_004L,
        )

        val updated = fixture.chapters.getChapter(chapterId)!!
        assertEquals("running", updated.parseStatus)
        assertEquals("error", updated.audioStatus)
        assertEquals("TTS 超时", updated.audioError)
        assertEquals("覆盖率 0.91", updated.parseWarn)
        assertEquals(6, updated.segCount)
        assertEquals(4, updated.segDone)
        assertEquals(2_004L, updated.updatedAt)

        fixture.chapters.updateParseStatus(chapterId, "done", updatedAt = 2_005L)
        fixture.chapters.updateAudioStatus(chapterId, "done", updatedAt = 2_006L)
        val done = fixture.chapters.getChapter(chapterId)!!
        assertEquals("done", done.parseStatus)
        assertEquals("done", done.audioStatus)
        assertNull(done.parseError)
        assertNull(done.audioError)
    }

    @Test
    fun page_briefs_filters_by_title_and_applies_limit_offset() = runTest {
        val novelId = fixture.projects.createProject(
            title = "分页项目",
            sourceName = "分页项目.txt",
            sourceRelPath = "imports/page.txt",
            chapters = (1..5).map { index ->
                ChapterInput(title = "第${index}章", rawText = "正文$index")
            },
            createdAt = 1_000L,
        )

        val firstPage = fixture.chapters.pageBriefs(novelId, limit = 2, offset = 0)
        assertEquals(listOf("第1章", "第2章"), firstPage.map { it.title })

        val secondPage = fixture.chapters.pageBriefs(novelId, limit = 2, offset = 2)
        assertEquals(listOf("第3章", "第4章"), secondPage.map { it.title })

        val filtered = fixture.chapters.pageBriefs(novelId, query = "第5", limit = 10, offset = 0)
        assertEquals(listOf("第5章"), filtered.map { it.title })

        assertTrue(
            fixture.chapters.pageBriefs(novelId, query = "不存在", limit = 10, offset = 0).isEmpty(),
        )
        assertTrue(fixture.chapters.pageBriefs(novelId, limit = 10, offset = 10).isEmpty())

        var thrown: Throwable? = null
        try {
            fixture.chapters.pageBriefs(novelId, limit = 0)
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun update_counters_rejects_inconsistent_done_count() = runTest {
        val novelId = fixture.projects.createProject(
            title = "计数校验",
            sourceName = "计数校验.txt",
            sourceRelPath = "imports/counter.txt",
            chapters = listOf(ChapterInput(title = "第一章", rawText = "正文")),
            createdAt = 1_000L,
        )
        val chapterId = fixture.chapters.observeBriefs(novelId).first().single().id

        var thrown: Throwable? = null
        try {
            fixture.chapters.updateCounters(chapterId, segCount = 2, segDone = 3)
        } catch (t: Throwable) {
            thrown = t
        }

        assertTrue(thrown is IllegalArgumentException)
        val unchanged = fixture.chapters.getChapter(chapterId)!!
        assertEquals(0, unchanged.segCount)
        assertEquals(0, unchanged.segDone)
    }
}
