package com.estimatefaster.audioforge.data.repository

import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.data.db.JobEntity
import com.estimatefaster.audioforge.data.db.SegmentEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P2-02：项目创建事务、级联删除 + 文件清理、重命名/最近打开、字符计数。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProjectRepositoryTest {

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
    fun create_project_inserts_novel_and_chapters_with_computed_char_count() = runTest {
        val novelId = fixture.projects.createProject(
            title = " 长夜 ",
            sourceName = "长夜.txt",
            sourceRelPath = "imports/source.txt",
            chapters = listOf(
                ChapterInput(title = "第一章", rawText = "正文一"),
                ChapterInput(title = " 第二章 ", rawText = "正文二二"),
            ),
            createdAt = 1_000L,
        )

        val novel = fixture.projects.getProject(novelId)!!
        assertEquals("长夜", novel.title)
        assertEquals("长夜.txt", novel.sourceName)
        assertEquals("imports/source.txt", novel.sourceRelPath)
        assertEquals(1_000L, novel.createdAt)
        assertEquals(7, novel.charCount)

        val briefs = fixture.chapters.observeBriefs(novelId).first()
        assertEquals(listOf(0, 1), briefs.map { it.idx })
        assertEquals(listOf("第一章", "第二章"), briefs.map { it.title })
        assertEquals("正文一", fixture.chapters.getRawText(briefs[0].id))

        val summary = fixture.projects.observeProjects().first().single()
        assertEquals(novelId, summary.id)
        assertEquals(2, summary.chapterCount)
        assertEquals(0, summary.segmentCount)
    }

    @Test
    fun create_project_rolls_back_everything_when_a_chapter_insert_fails() = runTest {
        // 第一段写成功、第二段失败，验证 novel + 第一段都被事务回滚。
        fixture.db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_second_chapter
            BEFORE INSERT ON chapters
            WHEN NEW.idx = 1
            BEGIN
                SELECT RAISE(ABORT, 'boom');
            END
            """.trimIndent(),
        )

        var thrown: Throwable? = null
        try {
            fixture.projects.createProject(
                title = "回滚项目",
                sourceName = "回滚项目.txt",
                sourceRelPath = "imports/rollback.txt",
                chapters = listOf(
                    ChapterInput(title = "第一章", rawText = "正文一"),
                    ChapterInput(title = "第二章", rawText = "正文二"),
                ),
                createdAt = 1_000L,
            )
        } catch (t: Throwable) {
            thrown = t
        }

        assertNotNull("章节插入失败必须抛出异常", thrown)
        assertEquals(0, fixture.countRows("novels"))
        assertEquals(0, fixture.countRows("chapters"))
        assertTrue(fixture.projects.listProjects().isEmpty())
    }

    @Test
    fun create_project_rejects_blank_chapter_title_without_writing_anything() = runTest {
        var thrown: Throwable? = null
        try {
            fixture.projects.createProject(
                title = "非法章节",
                sourceName = "非法章节.txt",
                sourceRelPath = "imports/bad.txt",
                chapters = listOf(ChapterInput(title = "  ", rawText = "正文")),
            )
        } catch (t: Throwable) {
            thrown = t
        }

        assertTrue(thrown is IllegalArgumentException)
        assertEquals(0, fixture.countRows("novels"))
        assertEquals(0, fixture.countRows("chapters"))
    }

    @Test
    fun delete_project_cascades_rows_and_removes_audio_and_source_files() = runTest {
        val novelId = fixture.projects.createProject(
            title = "待删除",
            sourceName = "待删除.txt",
            sourceRelPath = "imports/to-delete.txt",
            chapters = listOf(ChapterInput(title = "第一章", rawText = "正文")),
            createdAt = 1_000L,
        )
        val chapterId = fixture.chapters.observeBriefs(novelId).first().single().id

        fixture.segments.replace(
            chapterId,
            listOf(segment(chapterId, "旁白一"), segment(chapterId, "旁白二")),
        )
        fixture.characters.upsert(
            CharacterEntity(
                novelId = novelId,
                roleId = "role_0001",
                name = "张三",
                category = "male_lead_steady",
                voiceId = "voice-1",
            ),
        )
        fixture.jobs.insert(
            JobEntity(
                novelId = novelId,
                mode = "full",
                status = "running",
                requestedIdsJson = "[]",
                startedAt = 1_000L,
            ),
        )

        fixture.audioStorage.writeBytes("imports/to-delete.txt", "源文件".toByteArray())
        fixture.audioStorage.writeBytes(
            "audio/$novelId/segments/ch_001/seg_000001.mp3",
            "mp3".toByteArray(),
        )
        fixture.audioStorage.writeBytes("audio/$novelId/chapter_001.m4a", "m4a".toByteArray())

        assertTrue(fixture.projects.delete(novelId))

        assertNull(fixture.projects.getProject(novelId))
        assertEquals(0, fixture.countRows("novels"))
        assertEquals(0, fixture.countRows("chapters"))
        assertEquals(0, fixture.countRows("segments"))
        assertEquals(0, fixture.countRows("characters"))
        assertEquals(0, fixture.countRows("jobs"))
        assertFalse(
            "导入源文件必须删除",
            fixture.audioStorage.resolve("imports/to-delete.txt").exists(),
        )
        assertFalse(
            "项目音频目录必须删除",
            fixture.audioStorage.resolve("audio/$novelId").exists(),
        )
        assertFalse("项目不存在时删除返回 false", fixture.projects.delete(novelId))
    }

    @Test
    fun rename_and_last_opened_are_persisted_and_drive_list_order() = runTest {
        val older = fixture.projects.createProject(
            title = "旧项目",
            sourceName = "旧项目.txt",
            sourceRelPath = "imports/old.txt",
            chapters = emptyList(),
            createdAt = 1_000L,
        )
        val newer = fixture.projects.createProject(
            title = "新项目",
            sourceName = "新项目.txt",
            sourceRelPath = "imports/new.txt",
            chapters = emptyList(),
            createdAt = 2_000L,
        )

        assertEquals(listOf(newer, older), fixture.projects.listProjects().map { it.id })

        assertTrue(fixture.projects.updateLastOpened(older, openedAt = 3_000L))
        assertEquals(listOf(older, newer), fixture.projects.listProjects().map { it.id })
        assertEquals(3_000L, fixture.projects.getProject(older)?.lastOpenedAt)

        assertTrue(fixture.projects.rename(older, " 改名后的项目 "))
        assertEquals("改名后的项目", fixture.projects.getProject(older)?.title)
        assertEquals(older, fixture.projects.findByTitle("改名后的项目")?.id)
        assertFalse(fixture.projects.rename(id = 9_999L, title = "不存在"))
        assertFalse(fixture.projects.updateLastOpened(id = 9_999L))
    }

    @Test
    fun rename_rejects_blank_title() = runTest {
        val novelId = fixture.createEmptyNovel("原项目")

        var thrown: Throwable? = null
        try {
            fixture.projects.rename(novelId, "   ")
        } catch (t: Throwable) {
            thrown = t
        }

        assertTrue(thrown is IllegalArgumentException)
        assertEquals("原项目", fixture.projects.getProject(novelId)?.title)
    }

    private fun segment(chapterId: Long, text: String): SegmentEntity = SegmentEntity(
        chapterId = chapterId,
        seq = 0,
        type = "narration",
        roleId = "narrator",
        emotion = "neutral",
        text = text,
    )
}
