package com.estimatefaster.audioforge.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P3-01：jobs 表状态机 / stats / stopRequested / sweepRunning 的 DAO 级测试。
 *
 * 这里只验证 SQL 条件更新本身（合法跃迁返回 1、非法返回 0 且不覆盖）；
 * stats 的 JSON 合并语义在 `JobRepositoryTest` 里验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JobDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: JobDao

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
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=ON")
        dao = db.jobDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------ 合法状态流转

    @Test
    fun queued_running_done_transition_persists_terminal_fields() = runTest {
        val novelId = novel()
        val id = insertJob(novelId, JobStatus.QUEUED, startedAt = 100L)

        assertEquals(1, dao.markRunning(id))
        val running = dao.get(id)!!
        assertEquals(JobStatus.RUNNING, running.status)
        assertNull(running.finishedAt)
        assertNull(running.lastError)
        assertEquals(id, dao.activeJob(novelId)?.id)

        assertEquals(1, dao.markDone(id, now = 200L, statsJson = """{"chaptersDone":5}"""))
        val done = dao.get(id)!!
        assertEquals(JobStatus.DONE, done.status)
        assertEquals(200L, done.finishedAt)
        assertEquals("""{"chaptersDone":5}""", done.statsJson)
        assertFalse(done.stopRequested)
        assertNull(dao.activeJob(novelId))
        assertEquals(id, dao.lastFinished(novelId)?.id)
    }

    @Test
    fun illegal_transitions_return_zero_and_never_overwrite_terminal_job() = runTest {
        val novelId = novel()

        // queued 不能直接 done/error，只能 running 或 cancelled。
        val queuedId = insertJob(novelId, JobStatus.QUEUED, startedAt = 100L)
        assertEquals(0, dao.markDone(queuedId, now = 150L, statsJson = "{}"))
        assertEquals(0, dao.markError(queuedId, now = 150L, error = "boom", statsJson = "{}"))
        assertEquals(JobStatus.QUEUED, dao.get(queuedId)?.status)

        // running 只能前进到终态；重复 markRunning 不生效。
        assertEquals(1, dao.markRunning(queuedId))
        assertEquals(0, dao.markRunning(queuedId))
        assertEquals(1, dao.markDone(queuedId, now = 200L, statsJson = """{"segDone":1}"""))

        // done 是终态：running / error / cancelled 都不能回退。
        assertEquals(0, dao.markRunning(queuedId))
        assertEquals(0, dao.markError(queuedId, now = 300L, error = "晚到错误", statsJson = "{}"))
        assertEquals(0, dao.markCancelled(queuedId, now = 300L))

        val done = dao.get(queuedId)!!
        assertEquals(JobStatus.DONE, done.status)
        assertEquals(200L, done.finishedAt)
        assertEquals("""{"segDone":1}""", done.statsJson)
        assertNull(done.lastError)

        // error 同样是终态。
        val runningId = insertJob(novelId, JobStatus.RUNNING, startedAt = 400L)
        assertEquals(1, dao.markError(runningId, now = 500L, error = "网络失败", statsJson = "{}"))
        assertEquals(0, dao.markRunning(runningId))
        assertEquals(0, dao.markDone(runningId, now = 600L, statsJson = "{}"))
        assertEquals(JobStatus.ERROR, dao.get(runningId)?.status)
        assertEquals("网络失败", dao.get(runningId)?.lastError)

        // cancelled 也可以直接来自 queued，之后不可再流转。
        val cancelledId = insertJob(novelId, JobStatus.QUEUED, startedAt = 700L)
        assertEquals(1, dao.markCancelled(cancelledId, now = 800L))
        assertEquals(0, dao.markRunning(cancelledId))
        assertEquals(JobStatus.CANCELLED, dao.get(cancelledId)?.status)
        assertEquals(800L, dao.get(cancelledId)?.finishedAt)
    }

    // --------------------------------------------------------- stopRequested

    @Test
    fun stop_request_is_visible_resettable_and_cleared_on_finish() = runTest {
        val novelId = novel()
        val queuedId = insertJob(novelId, JobStatus.QUEUED, startedAt = 100L)

        assertFalse(dao.countStopRequested(queuedId) > 0)
        assertEquals(1, dao.requestStop(queuedId))
        assertTrue(dao.countStopRequested(queuedId) > 0)

        // markRunning 不能在 Worker 启动时偷偷清掉用户已点的停止。
        assertEquals(1, dao.markRunning(queuedId))
        assertTrue(dao.countStopRequested(queuedId) > 0)

        assertEquals(1, dao.resetStopRequested(queuedId))
        assertEquals(0, dao.resetStopRequested(queuedId))
        assertFalse(dao.countStopRequested(queuedId) > 0)

        // 终态会清标记，且终态上 requestStop 无效。
        assertEquals(1, dao.requestStop(queuedId))
        assertEquals(1, dao.markCancelled(queuedId, now = 200L))
        assertFalse(dao.get(queuedId)!!.stopRequested)
        assertEquals(0, dao.requestStop(queuedId))
        assertFalse(dao.countStopRequested(queuedId) > 0)
    }

    // ------------------------------------------------- stats / progress 更新

    @Test
    fun progress_and_stats_updates_only_apply_while_active() = runTest {
        val novelId = novel()
        val id = insertJob(novelId, JobStatus.QUEUED, startedAt = 100L, statsJson = """{"segDone":1}""")

        assertEquals(
            1,
            dao.updateProgress(id, chapterId = 42L, chapterTitle = "第1章", statsJson = """{"segDone":1,"segTotal":10}"""),
        )
        assertEquals(
            1,
            dao.updateStatsJson(id, """{"chaptersDone":2,"segDone":1,"segTotal":10}"""),
        )
        val progressed = dao.get(id)!!
        assertEquals(42L, progressed.currentChapterId)
        assertEquals("第1章", progressed.currentTitle)
        assertEquals("""{"chaptersDone":2,"segDone":1,"segTotal":10}""", progressed.statsJson)

        assertEquals(1, dao.markRunning(id))
        assertEquals(1, dao.markDone(id, now = 200L, statsJson = """{"chaptersDone":3}"""))

        // 终态后迟到的进度/统计必须被拒绝，不能覆盖收尾结果。
        assertEquals(0, dao.updateProgress(id, chapterId = 43L, chapterTitle = "第2章", statsJson = "{}"))
        assertEquals(0, dao.updateStatsJson(id, """{"chaptersDone":99}"""))
        val done = dao.get(id)!!
        assertEquals("第1章", done.currentTitle)
        assertEquals("""{"chaptersDone":3}""", done.statsJson)
    }

    // -------------------------------------------------------- 活跃/历史查询

    @Test
    fun active_and_last_finished_queries_ignore_pending_and_terminal_jobs() = runTest {
        val novelId = novel()
        val doneId = insertJob(novelId, JobStatus.RUNNING, startedAt = 100L)
        assertEquals(1, dao.markDone(doneId, now = 150L, statsJson = "{}"))
        val queuedId = insertJob(novelId, JobStatus.QUEUED, startedAt = 200L)
        val runningId = insertJob(novelId, JobStatus.RUNNING, startedAt = 300L)

        assertEquals(2, dao.countActive(novelId))
        assertEquals(runningId, dao.activeJob(novelId)?.id)
        assertEquals(doneId, dao.lastFinished(novelId)?.id)

        // 手动写入 pending（sweep 后的中断态），它既不是活跃也不是“上次完成”。
        // queued 不受 sweep 影响，仍应是唯一活跃批次。
        assertEquals(1, dao.sweepRunning(novelId))
        assertEquals(queuedId, dao.activeJob(novelId)?.id)
        assertEquals(1, dao.countActive(novelId))
        assertEquals(JobStatus.PENDING, dao.get(runningId)?.status)
        assertEquals(doneId, dao.lastFinished(novelId)?.id)
        assertEquals(queuedId, dao.get(queuedId)?.id)
    }

    // ------------------------------------------------------------ sweepRunning

    @Test
    fun sweep_running_resets_running_rows_and_stop_flags_only() = runTest {
        val first = novel("书一")
        val second = novel("书二")
        val firstRunning = insertJob(first, JobStatus.RUNNING, startedAt = 100L)
        val firstQueued = insertJob(first, JobStatus.QUEUED, startedAt = 200L)
        val firstDone = insertJob(first, JobStatus.DONE, startedAt = 300L)
        val secondRunning = insertJob(second, JobStatus.RUNNING, startedAt = 400L)
        dao.requestStop(firstRunning)
        dao.requestStop(secondRunning)

        assertEquals(1, dao.sweepRunning(first))

        val swept = dao.get(firstRunning)!!
        assertEquals(JobStatus.PENDING, swept.status)
        assertNull(swept.finishedAt)
        assertFalse(swept.stopRequested)
        assertEquals(JobStatus.QUEUED, dao.get(firstQueued)?.status)
        assertEquals(JobStatus.DONE, dao.get(firstDone)?.status)
        assertEquals(JobStatus.RUNNING, dao.get(secondRunning)?.status)
        assertTrue(dao.get(secondRunning)!!.stopRequested)

        // 全书 sweep 只影响第二本的 running。
        assertEquals(1, dao.sweepRunning())
        assertEquals(JobStatus.PENDING, dao.get(secondRunning)?.status)
        assertEquals(0, dao.sweepRunning())
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun novel(title: String = "任务书"): Long = db.novelDao().insert(
        NovelEntity(
            title = title,
            sourceName = "$title.txt",
            sourceRelPath = "imports/$title.txt",
            charCount = 10,
            createdAt = 1_000L,
        ),
    )

    private suspend fun insertJob(
        novelId: Long,
        status: String,
        startedAt: Long,
        statsJson: String = "{}",
    ): Long = dao.insert(
        JobEntity(
            novelId = novelId,
            mode = "full",
            status = status,
            requestedIdsJson = "[]",
            startedAt = startedAt,
            statsJson = statsJson,
        ),
    )
}
