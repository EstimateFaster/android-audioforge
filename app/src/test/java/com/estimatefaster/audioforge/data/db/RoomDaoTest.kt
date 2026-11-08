package com.estimatefaster.audioforge.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P2-01：纯 JVM（Robolectric）跑真实 Room 建库/DAO/SQL。
 *
 * 注意：[Room.inMemoryDatabaseBuilder] 不会走 [AppDatabase.create] 的 builder，
 * 所以这里额外注册 onOpen 回调，并对写连接再手动执行一次 `PRAGMA foreign_keys=ON`，
 * 确保级联删除在 Robolectric 下真的生效。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomDaoTest {

    private lateinit var db: AppDatabase
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(database: SupportSQLiteDatabase) {
                    database.execSQL("PRAGMA foreign_keys=ON")
                }
            })
            .build()

        // in-memory 只有一个写连接；在这里打开 PRAGMA 即可覆盖 DAO 的写操作。
        val writable = db.openHelper.writableDatabase
        writable.execSQL("PRAGMA foreign_keys=ON")
        writable.query("PRAGMA foreign_keys").use { cursor ->
            assertTrue("PRAGMA foreign_keys 查询应返回一行", cursor.moveToFirst())
            assertEquals("in-memory 测试必须启用外键", 1, cursor.getInt(0))
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------------------------------------------------------------- 外键级联

    @Test
    fun deleting_novel_cascades_to_chapters_segments_characters_and_jobs() = runTest {
        val novelId = db.novelDao().insert(novel("级联删除"))
        val chapterId = db.chapterDao().insert(chapter(novelId, idx = 0))
        db.segmentDao().insert(segment(chapterId, seq = 0))
        db.segmentDao().insert(segment(chapterId, seq = 1))
        db.characterDao().insert(character(novelId))
        db.jobDao().insert(job(novelId, status = "running"))

        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO character_tuning(novelId, roleId, key, value) " +
                "VALUES($novelId, 'role_0001', 'speed', '1.1')",
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO novel_meta(novelId, lastGeneratedChapter) VALUES($novelId, 3)",
        )

        assertEquals(1, countRows("chapters"))
        assertEquals(2, countRows("segments"))
        assertEquals(1, countRows("characters"))
        assertEquals(1, countRows("character_tuning"))
        assertEquals(1, countRows("novel_meta"))
        assertEquals(1, countRows("jobs"))

        db.novelDao().delete(novelId)

        assertNull(db.chapterDao().getChapter(chapterId))
        assertTrue(db.segmentDao().list(chapterId).isEmpty())
        assertEquals(0, countRows("chapters"))
        assertEquals(0, countRows("segments"))
        assertEquals(0, countRows("characters"))
        assertEquals(0, countRows("character_tuning"))
        assertEquals(0, countRows("novel_meta"))
        assertEquals(0, countRows("jobs"))
    }

    // ---------------------------------------------------------------- 唯一约束

    @Test
    fun unique_constraints_on_chapter_segment_and_character_are_enforced() = runTest {
        val novelId = db.novelDao().insert(novel("唯一约束"))
        val chapterId = db.chapterDao().insert(chapter(novelId, idx = 0))

        assertConstraintViolation {
            db.chapterDao().insert(chapter(novelId, idx = 0))
        }

        db.segmentDao().insert(segment(chapterId, seq = 0))
        assertConstraintViolation {
            db.segmentDao().insert(segment(chapterId, seq = 0))
        }

        db.characterDao().insert(character(novelId, roleId = "role_0001"))
        assertConstraintViolation {
            db.characterDao().insert(character(novelId, roleId = "role_0001"))
        }

        assertEquals(1, countRows("chapters"))
        assertEquals(1, countRows("segments"))
        assertEquals(1, countRows("characters"))
    }

    // ------------------------------------------------------------ 项目聚合查询

    @Test
    fun observe_projects_aggregates_chapters_and_segments_per_novel() = runTest {
        val first = db.novelDao().insert(novel("聚合甲", charCount = 120))
        val firstChapter = db.chapterDao().insert(
            chapter(first, idx = 0, segCount = 2, segDone = 1, audioStatus = "done"),
        )
        db.segmentDao().insert(segment(firstChapter, seq = 0, status = "done"))
        db.segmentDao().insert(segment(firstChapter, seq = 1))

        val secondChapter = db.chapterDao().insert(
            chapter(first, idx = 1, segCount = 3, segDone = 2, audioStatus = "pending"),
        )
        db.segmentDao().insert(segment(secondChapter, seq = 0, status = "done"))
        db.segmentDao().insert(segment(secondChapter, seq = 1, status = "done"))
        db.segmentDao().insert(segment(secondChapter, seq = 2))

        val empty = db.novelDao().insert(novel("聚合乙", charCount = 10))

        val projects = db.novelDao().observeProjects().first()
        assertEquals(2, projects.size)

        val firstSummary = projects.single { it.id == first }
        assertEquals("聚合甲", firstSummary.title)
        assertEquals(120, firstSummary.charCount)
        assertEquals(2, firstSummary.chapterCount)
        assertEquals(1, firstSummary.chapterDoneCount)
        assertEquals(5, firstSummary.segmentCount)
        assertEquals(3, firstSummary.segmentDoneCount)

        val emptySummary = projects.single { it.id == empty }
        assertEquals(0, emptySummary.chapterCount)
        assertEquals(0, emptySummary.chapterDoneCount)
        assertEquals(0, emptySummary.segmentCount)
        assertEquals(0, emptySummary.segmentDoneCount)
    }

    // ---------------------------------------------------------- 章节 brief 投影

    @Test
    fun observe_briefs_projects_without_raw_text_but_get_chapter_has_it() = runTest {
        val novelId = db.novelDao().insert(novel("投影"))
        val rawText = "这是绝不能被列表投影读取的正文。".repeat(20)
        val chapterId = db.chapterDao().insert(
            chapter(novelId, idx = 0, rawText = rawText).copy(
                parseStatus = "done",
                parseWarn = "覆盖率 0.95",
                audioStatus = "running",
                segCount = 4,
                segDone = 2,
            ),
        )

        val brief = db.chapterDao().observeBriefs(novelId).first().single()
        assertEquals(
            ChapterBrief(
                id = chapterId,
                idx = 0,
                title = "第1章",
                parseStatus = "done",
                audioStatus = "running",
                parseWarn = "覆盖率 0.95",
                segCount = 4,
                segDone = 2,
            ),
            brief,
        )
        assertTrue(
            "ChapterBrief 不应包含 rawText 字段",
            ChapterBrief::class.java.declaredFields.none { it.name == "rawText" },
        )

        assertEquals(rawText, db.chapterDao().getRawText(chapterId))
        assertEquals(rawText, db.chapterDao().getChapter(chapterId)?.rawText)

        // 分页查询同样走投影；空串不过滤，关键词只匹配 title。
        assertEquals(listOf(chapterId), db.chapterDao().pageBriefs(novelId, "", 10, 0).map { it.id })
        assertEquals(listOf(chapterId), db.chapterDao().pageBriefs(novelId, "第1", 10, 0).map { it.id })
        assertTrue(db.chapterDao().pageBriefs(novelId, "不存在", 10, 0).isEmpty())
    }

    // ------------------------------------------------------ 片段批量状态/计数

    @Test
    fun chapter_status_warn_and_counters_updates_are_persisted() = runTest {
        val novelId = db.novelDao().insert(novel("章节更新"))
        val chapterId = db.chapterDao().insert(chapter(novelId, idx = 0))

        db.chapterDao().updateStatus(
            id = chapterId,
            parseStatus = "running",
            parseError = null,
            audioStatus = "error",
            audioError = "TTS 超时",
            updatedAt = 2_001L,
        )
        db.chapterDao().updateWarn(chapterId, "覆盖率 0.91", 2_002L)
        db.chapterDao().updateCounters(chapterId, segCount = 6, segDone = 4, updatedAt = 2_003L)

        val updated = db.chapterDao().getChapter(chapterId)!!
        assertEquals("running", updated.parseStatus)
        assertEquals("error", updated.audioStatus)
        assertEquals("TTS 超时", updated.audioError)
        assertEquals("覆盖率 0.91", updated.parseWarn)
        assertEquals(6, updated.segCount)
        assertEquals(4, updated.segDone)
        assertEquals(2_003L, updated.updatedAt)

        db.chapterDao().updateParseStatus(chapterId, "done", null, 2_004L)
        db.chapterDao().updateAudioStatus(chapterId, "done", null, 2_005L)
        val separate = db.chapterDao().getChapter(chapterId)!!
        assertEquals("done", separate.parseStatus)
        assertEquals("done", separate.audioStatus)
        assertEquals(2_005L, separate.updatedAt)
    }

    @Test
    fun character_lookup_by_name_or_alias_and_voice_update() = runTest {
        val novelId = db.novelDao().insert(novel("角色"))
        val characterId = db.characterDao().insert(
            CharacterEntity(
                novelId = novelId,
                roleId = "role_0001",
                name = "张三",
                aliasesJson = """["小三","阿三"]""",
            ),
        )

        assertEquals(characterId, db.characterDao().getByNameOrAlias(novelId, "张三")?.id)
        assertEquals(characterId, db.characterDao().getByNameOrAlias(novelId, "小三")?.id)
        assertNull(db.characterDao().getByNameOrAlias(novelId, "李四"))
        assertEquals(1, db.characterDao().list(novelId).size)

        db.characterDao().updateVoice(
            id = characterId,
            voiceId = "voice-1",
            category = "male_lead_steady",
            speed = 1.15,
        )
        db.characterDao().updateAliases(characterId, """["三哥"]""")

        val updated = db.characterDao().getByRoleId(novelId, "role_0001")!!
        assertEquals("voice-1", updated.voiceId)
        assertEquals("male_lead_steady", updated.category)
        assertEquals(1.15, updated.speed!!, 0.0)
        assertEquals("""["三哥"]""", updated.aliasesJson)
    }

    @Test
    fun segment_batch_set_status_and_count_done() = runTest {
        val novelId = db.novelDao().insert(novel("片段批量"))
        val chapterId = db.chapterDao().insert(chapter(novelId, idx = 0))
        val otherChapterId = db.chapterDao().insert(chapter(novelId, idx = 1))

        val ids = listOf(
            db.segmentDao().insert(segment(chapterId, seq = 0)),
            db.segmentDao().insert(segment(chapterId, seq = 1)),
            db.segmentDao().insert(segment(chapterId, seq = 2)),
        )
        db.segmentDao().insert(segment(otherChapterId, seq = 0))

        db.segmentDao().setStatus(ids.take(2), "done", 9_999L)

        val segments = db.segmentDao().list(chapterId)
        assertEquals(listOf("done", "done", "pending"), segments.map { it.status })
        assertTrue(segments.take(2).all { it.updatedAt == 9_999L })
        assertEquals(2, db.segmentDao().countDone(chapterId))
        assertEquals(3, db.segmentDao().countByChapter(chapterId))
        assertEquals(0, db.segmentDao().countDone(otherChapterId))
    }

    @Test
    fun reset_by_role_clears_audio_state_only_for_that_role() = runTest {
        val novelId = db.novelDao().insert(novel("换音色"))
        val chapterA = db.chapterDao().insert(chapter(novelId, idx = 0))
        val chapterB = db.chapterDao().insert(chapter(novelId, idx = 1))

        val roleA = db.segmentDao().insert(
            segment(chapterA, seq = 0, roleId = "role_0001").copy(
                status = "done",
                audioRelPath = "audio/1/ch_001/seg_000001.mp3",
                durationMs = 1_200,
                ttsKey = "key-a",
            ),
        )
        val roleB = db.segmentDao().insert(
            segment(chapterB, seq = 0, roleId = "role_0001").copy(
                status = "error",
                errorMsg = "超时",
            ),
        )
        val untouched = db.segmentDao().insert(
            segment(chapterA, seq = 1, roleId = "role_0002").copy(status = "done"),
        )

        db.segmentDao().resetByRole(novelId, "role_0001", 7_777L)

        val resetA = db.segmentDao().get(roleA)!!
        assertEquals("pending", resetA.status)
        assertNull(resetA.audioRelPath)
        assertNull(resetA.durationMs)
        assertNull(resetA.ttsKey)
        assertEquals(7_777L, resetA.updatedAt)

        val resetB = db.segmentDao().get(roleB)!!
        assertEquals("pending", resetB.status)
        assertNull(resetB.errorMsg)
        assertEquals(7_777L, resetB.updatedAt)

        val stillDone = db.segmentDao().get(untouched)
        assertNotNull(stillDone)
        assertEquals("done", stillDone?.status)
    }

    // ------------------------------------------------------------ 事务回滚

    @Test
    fun with_transaction_rolls_back_all_writes_on_exception() = runTest {
        var thrown: Throwable? = null
        try {
            db.withTransaction {
                val novelId = db.novelDao().insert(novel("回滚项目"))
                val chapterId = db.chapterDao().insert(chapter(novelId, idx = 0))
                db.segmentDao().insert(segment(chapterId, seq = 0))
                error("boom")
            }
        } catch (t: Throwable) {
            thrown = t
        }

        assertTrue("期望 IllegalStateException，实际：$thrown", thrown is IllegalStateException)
        assertTrue(db.novelDao().listNovels().isEmpty())
        assertEquals(0, countRows("novels"))
        assertEquals(0, countRows("chapters"))
        assertEquals(0, countRows("segments"))
    }

    // ---------------------------------------------------------------- 任务表

    @Test
    fun job_dao_tracks_active_and_last_finished_job() = runTest {
        val novelId = db.novelDao().insert(novel("任务表"))

        assertNull(db.jobDao().observeActive(novelId).first())
        assertNull(db.jobDao().lastFinished(novelId))

        val queuedId = db.jobDao().insert(job(novelId, status = "queued", startedAt = 100L))
        assertEquals(queuedId, db.jobDao().observeActive(novelId).first()?.id)

        val runningId = db.jobDao().insert(job(novelId, status = "running", startedAt = 200L))
        // 最近开始的批次优先。
        assertEquals(runningId, db.jobDao().observeActive(novelId).first()?.id)

        assertEquals(
            1,
            db.jobDao().markError(
                id = runningId,
                now = 300L,
                error = "网络失败",
                statsJson = "{}",
            ),
        )

        assertEquals(queuedId, db.jobDao().observeActive(novelId).first()?.id)
        val finished = db.jobDao().lastFinished(novelId)
        assertEquals(runningId, finished?.id)
        assertEquals("error", finished?.status)
        assertEquals(300L, finished?.finishedAt)
        assertEquals("网络失败", finished?.lastError)

        assertEquals(1, db.jobDao().markRunning(queuedId))
        assertEquals(1, db.jobDao().sweepRunning(novelId))
        assertEquals("pending", db.jobDao().get(queuedId)?.status)
        assertNull(db.jobDao().observeActive(novelId).first())
    }

    // ---------------------------------------------------------------- helpers

    private fun novel(title: String, charCount: Int = 1_000): NovelEntity = NovelEntity(
        title = title,
        sourceName = "$title.txt",
        sourceRelPath = "imports/$title.txt",
        charCount = charCount,
        createdAt = now++,
    )

    private fun chapter(
        novelId: Long,
        idx: Int,
        rawText: String = "第${idx + 1}章正文",
        segCount: Int = 0,
        segDone: Int = 0,
        audioStatus: String = "pending",
    ): ChapterEntity = ChapterEntity(
        novelId = novelId,
        idx = idx,
        title = "第${idx + 1}章",
        rawText = rawText,
        audioStatus = audioStatus,
        segCount = segCount,
        segDone = segDone,
        updatedAt = now++,
    )

    private fun segment(
        chapterId: Long,
        seq: Int,
        roleId: String = "narrator",
        status: String = "pending",
    ): SegmentEntity = SegmentEntity(
        chapterId = chapterId,
        seq = seq,
        type = "narration",
        roleId = roleId,
        emotion = "neutral",
        text = "片段$seq",
        status = status,
        updatedAt = now++,
    )

    private fun character(
        novelId: Long,
        roleId: String = "role_0001",
        name: String = "张三",
    ): CharacterEntity = CharacterEntity(
        novelId = novelId,
        roleId = roleId,
        name = name,
    )

    private fun job(
        novelId: Long,
        status: String,
        startedAt: Long = now++,
    ): JobEntity = JobEntity(
        novelId = novelId,
        mode = "full",
        status = status,
        requestedIdsJson = "[]",
        startedAt = startedAt,
    )

    private fun countRows(table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private suspend fun assertConstraintViolation(block: suspend () -> Unit) {
        try {
            block()
            fail("期望 SQLiteConstraintException，但插入成功了")
        } catch (expected: SQLiteConstraintException) {
            // 期望路径：唯一索引/外键拒绝写入。
        }
    }
}
