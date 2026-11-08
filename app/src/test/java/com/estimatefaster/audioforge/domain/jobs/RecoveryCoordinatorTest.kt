package com.estimatefaster.audioforge.domain.jobs

import androidx.work.Data
import com.estimatefaster.audioforge.data.db.JobEntity
import com.estimatefaster.audioforge.data.db.JobStatus
import com.estimatefaster.audioforge.data.repository.RepositoryFixture
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P3-08：`RecoveryCoordinator` 的启动 sweep + 重排、active work 保护、幂等、
 * 脏数据容错、多书混合、历史排序与入队失败报告。
 *
 * Robolectric + in-memory Room + fake [JobQueue]；`isWorkActive` 走默认实现
 * （读取 fake 队列的 observe 快照），因此不启动真实 WorkManager。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecoveryCoordinatorTest {

    private lateinit var fixture: RepositoryFixture
    private lateinit var queue: FakeJobQueue
    private lateinit var recovery: RecoveryCoordinator
    private val knownNovels = mutableListOf<Long>()
    private var now: Long = 10_000L

    @Before
    fun setUp() {
        fixture = RepositoryFixture()
        queue = FakeJobQueue()
        knownNovels.clear()
        now = 10_000L
        val sweep = SweepRunning(
            jobs = fixture.jobs,
            jobDao = fixture.db.jobDao(),
            clock = { now++ },
            awaitPollMs = 50L,
            queue = queue,
            knownNovelIds = { knownNovels.toList() },
        )
        recovery = RecoveryCoordinator(
            sweep = sweep,
            jobs = fixture.jobs,
            queue = queue,
            knownNovelIds = { knownNovels.toList() },
            clock = { now++ },
        )
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    // ------------------------------------------------------------ 启动恢复

    @Test
    fun appStart_sweeps_running_and_reschedules_missing_work() = runTest {
        val (novelId, first, second) = twoChapterNovel()
        val running = insertJob(
            novelId = novelId,
            status = JobStatus.RUNNING,
            requestedIdsJson = "[$first,$second]",
            mode = "full",
        )

        val report = recovery.recoverOnAppStart()

        assertEquals(1, report.swept)
        assertEquals(1, report.rescheduledCount)
        assertTrue("正常恢复不应有 skipped", report.skipped.isEmpty())
        assertTrue("正常恢复不应有 failure", report.failures.isEmpty())

        assertEquals(JobStatus.PENDING, fixture.jobs.get(running)!!.status)

        val resumed = report.rescheduled.single()
        assertEquals(running, resumed.sourceJobId)
        assertEquals(novelId, resumed.novelId)
        assertEquals(GenerationNotifications.workName(novelId), resumed.workName)
        assertNotEquals("pending 行不能直接当可调度行复用", running, resumed.jobId)

        val newJob = fixture.jobs.get(resumed.jobId)!!
        assertEquals(JobStatus.QUEUED, newJob.status)
        assertEquals("full", newJob.mode)
        assertEquals("[$first,$second]", newJob.requestedIdsJson)

        val request = queue.enqueued.single()
        assertEquals(resumed.workName, request.workName)
        assertEquals(novelId, request.inputData.getLong(GenerationWorker.KEY_NOVEL_ID, -1L))
        assertEquals(resumed.jobId, request.inputData.getLong(GenerationWorker.KEY_JOB_ID, -1L))
        assertArrayEquals(
            longArrayOf(first, second),
            request.inputData.getLongArray(GenerationWorker.KEY_CHAPTER_IDS),
        )
        assertEquals("full", request.inputData.getString(GenerationWorker.KEY_MODE))
        assertFalse(
            "恢复必须默认 force=false，交给 P2 缓存跳过已完成段",
            request.inputData.getBoolean(GenerationWorker.KEY_FORCE, true),
        )
        assertEquals(1, fixture.jobs.countActive(novelId))
    }

    @Test
    fun queued_job_without_active_work_is_reenqueued_with_same_row() = runTest {
        val (novelId, first, second) = twoChapterNovel()
        val queued = insertJob(
            novelId = novelId,
            status = JobStatus.QUEUED,
            requestedIdsJson = "[$first,$second]",
        )

        val report = recovery.recoverOnAppStart()

        assertEquals(0, report.swept)
        assertEquals(1, report.rescheduledCount)
        val resumed = report.rescheduled.single()
        assertEquals(queued, resumed.sourceJobId)
        assertEquals("queued 行应复用原 jobId", queued, resumed.jobId)
        assertEquals(1, fixture.countRows("jobs"))

        val request = queue.enqueued.single()
        assertEquals(queued, request.inputData.getLong(GenerationWorker.KEY_JOB_ID, -1L))
        assertArrayEquals(
            longArrayOf(first, second),
            request.inputData.getLongArray(GenerationWorker.KEY_CHAPTER_IDS),
        )
        assertEquals(1, fixture.jobs.countActive(novelId))
    }

    @Test
    fun existing_active_work_is_not_rescheduled() = runTest {
        val (novelId, first, second) = twoChapterNovel()
        val running = insertJob(
            novelId = novelId,
            status = JobStatus.RUNNING,
            requestedIdsJson = "[$first,$second]",
        )
        queue.markActive(GenerationNotifications.workName(novelId))

        val report = recovery.recoverOnAppStart()

        assertEquals(1, report.swept)
        assertTrue("有活跃 work 时不能重排", report.rescheduled.isEmpty())
        assertEquals(1, report.skippedCount)
        assertEquals(running, report.skipped.single().jobId)
        assertTrue(report.skipped.single().reason.contains("活跃"))
        assertTrue(queue.enqueued.isEmpty())
        assertEquals(JobStatus.PENDING, fixture.jobs.get(running)!!.status)
        assertEquals(0, fixture.jobs.countActive(novelId))
    }

    @Test
    fun repeated_start_and_reschedule_all_are_idempotent() = runTest {
        val (novelId, first, second) = twoChapterNovel()
        insertJob(
            novelId = novelId,
            status = JobStatus.RUNNING,
            requestedIdsJson = "[$first,$second]",
        )

        val firstReport = recovery.recoverOnAppStart()
        assertEquals(1, firstReport.rescheduledCount)
        assertEquals(1, queue.enqueued.size)
        assertEquals(2, fixture.countRows("jobs")) // 1 pending + 1 queued

        val secondReport = recovery.recoverOnAppStart()
        assertEquals("第二次没有 running 可 sweep", 0, secondReport.swept)
        assertTrue("第二次不能重复入队", secondReport.rescheduled.isEmpty())
        assertEquals(1, secondReport.skippedCount)
        assertTrue(secondReport.skipped.single().reason.contains("活跃"))
        assertEquals(1, queue.enqueued.size)
        assertEquals(2, fixture.countRows("jobs"))

        val thirdReport = recovery.rescheduleAll()
        assertTrue(thirdReport.rescheduled.isEmpty())
        assertEquals(1, queue.enqueued.size)
        assertEquals(2, fixture.countRows("jobs"))
        assertEquals(1, fixture.jobs.countActive(novelId))
    }

    // ------------------------------------------------------------ 不可行候选

    @Test
    fun dirty_or_empty_requested_ids_are_skipped_without_crashing() = runTest {
        val dirtyNovel = newNovel("脏 JSON")
        val dirty = insertJob(
            novelId = dirtyNovel,
            status = JobStatus.RUNNING,
            requestedIdsJson = "{not-json",
        )
        val emptyNovel = newNovel("空章节")
        val empty = insertJob(
            novelId = emptyNovel,
            status = JobStatus.RUNNING,
            requestedIdsJson = "[]",
        )
        val blankNovel = newNovel("空白章节")
        val blank = insertJob(
            novelId = blankNovel,
            status = JobStatus.RUNNING,
            requestedIdsJson = "   ",
        )

        val report = recovery.recoverOnAppStart()

        assertEquals(3, report.swept)
        assertTrue("脏/空范围不能重排", report.rescheduled.isEmpty())
        assertTrue("脏/空范围不是失败", report.failures.isEmpty())
        assertEquals(3, report.skippedCount)
        assertEquals(
            setOf(dirty, empty, blank),
            report.skipped.mapNotNull { it.jobId }.toSet(),
        )
        report.skipped.forEach { assertTrue("必须给出可读原因", it.reason.isNotBlank()) }

        val reasons = report.skipped.associate { it.jobId to it.reason }
        assertTrue(reasons.getValue(dirty).contains("损坏"))
        assertTrue(reasons.getValue(empty).contains("为空"))
        assertTrue(reasons.getValue(blank).contains("损坏"))

        assertTrue(queue.enqueued.isEmpty())
        listOf(dirty, empty, blank).forEach {
            assertEquals(JobStatus.PENDING, fixture.jobs.get(it)!!.status)
        }
    }

    @Test
    fun invalid_ids_and_unknown_mode_are_skipped() = runTest {
        val (novelId, first, _) = twoChapterNovel()
        val negative = insertJob(
            novelId = novelId,
            status = JobStatus.QUEUED,
            requestedIdsJson = "[$first,0]",
        )
        val badModeNovel = newNovel("未知模式")
        val badMode = insertJob(
            novelId = badModeNovel,
            status = JobStatus.QUEUED,
            requestedIdsJson = "[$first]",
            mode = "bogus",
        )

        val report = recovery.rescheduleAll()

        assertTrue(report.rescheduled.isEmpty())
        assertEquals(2, report.skippedCount)
        assertEquals(setOf(negative, badMode), report.skipped.mapNotNull { it.jobId }.toSet())
        val reasons = report.skipped.associate { it.jobId to it.reason }
        assertTrue(reasons.getValue(negative).contains("非法"))
        assertTrue(reasons.getValue(badMode).contains("模式"))
        assertTrue(queue.enqueued.isEmpty())
    }

    @Test
    fun reschedule_skips_terminal_missing_and_invalid_jobs() = runTest {
        val novelId = newNovel()
        val done = insertJob(
            novelId = novelId,
            status = JobStatus.DONE,
            finishedAt = now++,
        )

        val terminal = recovery.reschedule(done)
        assertTrue(terminal.rescheduled.isEmpty())
        assertEquals(1, terminal.skippedCount)
        assertTrue(terminal.skipped.single().reason.contains("状态"))

        val missing = recovery.reschedule(9_999_999L)
        assertTrue(missing.rescheduled.isEmpty())
        assertEquals(1, missing.skippedCount)
        assertTrue(missing.skipped.single().reason.contains("不存在"))

        val invalid = recovery.reschedule(-1L)
        assertTrue(invalid.rescheduled.isEmpty())
        assertEquals(1, invalid.skippedCount)
        assertTrue(queue.enqueued.isEmpty())
    }

    @Test
    fun reschedule_pending_job_creates_queued_row_and_preserves_requested_ids() = runTest {
        val (novelId, first, second) = twoChapterNovel()
        val pending = insertJob(
            novelId = novelId,
            status = JobStatus.PENDING,
            requestedIdsJson = "[$first,$second]",
            mode = "render",
        )

        val report = recovery.reschedule(pending)

        assertEquals(1, report.rescheduledCount)
        val resumed = report.rescheduled.single()
        assertEquals(pending, resumed.sourceJobId)
        assertNotEquals(pending, resumed.jobId)
        val target = fixture.jobs.get(resumed.jobId)!!
        assertEquals(JobStatus.QUEUED, target.status)
        assertEquals("render", target.mode)
        assertEquals("[$first,$second]", target.requestedIdsJson)
        assertEquals(JobStatus.PENDING, fixture.jobs.get(pending)!!.status)

        val request = queue.enqueued.single()
        assertEquals(target.id, request.inputData.getLong(GenerationWorker.KEY_JOB_ID, -1L))
        assertEquals("render", request.inputData.getString(GenerationWorker.KEY_MODE))
    }

    // ------------------------------------------------------------ 多书混合 / 历史

    @Test
    fun mixed_books_reschedule_only_feasible_ones() = runTest {
        val (sweptNovel, a1, a2) = twoChapterNovel("待恢复")
        val sweptJob = insertJob(
            novelId = sweptNovel,
            status = JobStatus.RUNNING,
            requestedIdsJson = "[$a1,$a2]",
        )

        val (queuedNovel, b1, b2) = twoChapterNovel("排队中")
        val queuedJob = insertJob(
            novelId = queuedNovel,
            status = JobStatus.QUEUED,
            requestedIdsJson = "[$b1,$b2]",
        )

        val (blockedNovel, c1, c2) = twoChapterNovel("已有工作")
        val blockedJob = insertJob(
            novelId = blockedNovel,
            status = JobStatus.RUNNING,
            requestedIdsJson = "[$c1,$c2]",
        )
        queue.markActive(GenerationNotifications.workName(blockedNovel))

        val (dirtyNovel, d1, d2) = twoChapterNovel("脏数据")
        val dirtyJob = insertJob(
            novelId = dirtyNovel,
            status = JobStatus.RUNNING,
            requestedIdsJson = "not-json-$d1-$d2",
        )

        val (doneNovel, _, _) = twoChapterNovel("已完成")
        val doneJob = insertJob(
            novelId = doneNovel,
            status = JobStatus.DONE,
            finishedAt = now++,
        )

        val report = recovery.recoverOnAppStart()

        assertEquals(3, report.swept)
        assertEquals(2, report.rescheduledCount)
        assertEquals(
            setOf(sweptJob, queuedJob),
            report.rescheduled.map { it.sourceJobId }.toSet(),
        )
        assertEquals(
            setOf(blockedJob, dirtyJob),
            report.skipped.mapNotNull { it.jobId }.toSet(),
        )
        assertTrue(report.failures.isEmpty())
        assertEquals(2, queue.enqueued.size)

        // 待恢复书：旧 running 变 pending，新建一条 queued；排队书复用原行。
        assertEquals(JobStatus.PENDING, fixture.jobs.get(sweptJob)!!.status)
        assertEquals(1, fixture.jobs.countActive(sweptNovel))
        assertEquals(1, fixture.jobs.countActive(queuedNovel))
        assertEquals(0, fixture.jobs.countActive(blockedNovel))
        assertEquals(0, fixture.jobs.countActive(dirtyNovel))
        assertEquals(JobStatus.DONE, fixture.jobs.get(doneJob)!!.status)

        val workNames = queue.enqueued.map { it.workName }.toSet()
        assertTrue(workNames.contains(GenerationNotifications.workName(sweptNovel)))
        assertTrue(workNames.contains(GenerationNotifications.workName(queuedNovel)))
        assertFalse(workNames.contains(GenerationNotifications.workName(blockedNovel)))
        assertFalse(workNames.contains(GenerationNotifications.workName(dirtyNovel)))
        assertFalse(workNames.contains(GenerationNotifications.workName(doneNovel)))
    }

    @Test
    fun history_returns_last_finished_per_novel_sorted_and_limited() = runTest {
        val (novelA, _, _) = twoChapterNovel("历史 A")
        val (novelB, _, _) = twoChapterNovel("历史 B")
        val (novelC, _, _) = twoChapterNovel("历史 C")

        insertJob(
            novelId = novelA,
            status = JobStatus.DONE,
            finishedAt = 1_000L,
        )
        val newestA = insertJob(
            novelId = novelA,
            status = JobStatus.ERROR,
            finishedAt = 4_000L,
            lastError = "1 章失败：第一章：网络不可用",
        )
        val jobB = insertJob(
            novelId = novelB,
            status = JobStatus.ERROR,
            finishedAt = 3_000L,
            lastError = "1 章失败：第一章：额度不足",
        )
        val jobC = insertJob(
            novelId = novelC,
            status = JobStatus.CANCELLED,
            finishedAt = 2_000L,
        )
        // pending/queued 不应进入历史。
        insertJob(
            novelId = novelC,
            status = JobStatus.PENDING,
        )
        insertJob(
            novelId = novelB,
            status = JobStatus.QUEUED,
        )

        val all = recovery.history(limit = 10)

        assertEquals(listOf(newestA, jobB, jobC), all.map { it.id })
        assertEquals(listOf(4_000L, 3_000L, 2_000L), all.map { it.finishedAt })
        assertEquals(listOf(newestA, jobB), recovery.history(limit = 2).map { it.id })
        assertEquals(1, recovery.history(limit = 1).size)
        assertTrue(recovery.history(limit = 0).isEmpty())
    }

    // ------------------------------------------------------------ 入队失败

    @Test
    fun enqueue_failure_is_reported_without_throwing_and_can_retry() = runTest {
        val (novelId, first, second) = twoChapterNovel()
        val running = insertJob(
            novelId = novelId,
            status = JobStatus.RUNNING,
            requestedIdsJson = "[$first,$second]",
        )
        queue.failEnqueue = true

        val report = recovery.recoverOnAppStart()

        assertEquals(1, report.swept)
        assertTrue("入队失败不能算重排成功", report.rescheduled.isEmpty())
        assertEquals(1, report.failureCount)
        assertTrue(report.failures.single().reason.contains("入队"))
        assertEquals(1, queue.enqueued.size)
        assertEquals(JobStatus.PENDING, fixture.jobs.get(running)!!.status)

        // 失败时保留新建的 queued 行；下一次恢复复用同一行重试，不重复建行。
        val queued = fixture.jobs.activeJob(novelId)!!
        assertEquals(JobStatus.QUEUED, queued.status)
        assertNotEquals(running, queued.id)
        assertEquals(queued.id, report.failures.single().jobId)
        assertEquals(2, fixture.countRows("jobs"))

        queue.failEnqueue = false
        val retried = recovery.rescheduleAll()
        assertEquals(1, retried.rescheduledCount)
        assertEquals(queued.id, retried.rescheduled.single().jobId)
        assertEquals(2, queue.enqueued.size)
        assertEquals(2, fixture.countRows("jobs"))
        assertEquals(1, fixture.jobs.countActive(novelId))
    }

    // ------------------------------------------------------------ helpers

    private suspend fun newNovel(title: String = "测试项目"): Long {
        val novelId = fixture.createEmptyNovel(title)
        knownNovels += novelId
        return novelId
    }

    private suspend fun twoChapterNovel(title: String = "两章项目"): Triple<Long, Long, Long> {
        val novel = fixture.createTwoChapterNovel(title)
        knownNovels += novel.first
        return novel
    }

    private suspend fun insertJob(
        novelId: Long,
        status: String,
        requestedIdsJson: String = "[]",
        mode: String = "full",
        startedAt: Long = now++,
        finishedAt: Long? = null,
        lastError: String? = null,
        statsJson: String = "{}",
    ): Long = fixture.jobs.insert(
        JobEntity(
            novelId = novelId,
            mode = mode,
            status = status,
            requestedIdsJson = requestedIdsJson,
            startedAt = startedAt,
            finishedAt = finishedAt,
            lastError = lastError,
            statsJson = statsJson,
        ),
    )

    private class FakeJobQueue : JobQueue {

        data class Enqueued(val workName: String, val inputData: Data)

        val enqueued = mutableListOf<Enqueued>()
        val cancelled = mutableListOf<String>()
        var failEnqueue = false

        private val snapshots = mutableMapOf<String, MutableStateFlow<QueueSnapshot?>>()

        fun markActive(workName: String) {
            snapshotOf(workName).value = QueueSnapshot(workName, QueueState.ENQUEUED)
        }

        fun markFinished(workName: String, state: QueueState = QueueState.SUCCEEDED) {
            snapshotOf(workName).value = QueueSnapshot(workName, state)
        }

        override suspend fun enqueue(workName: String, inputData: Data): Boolean {
            enqueued += Enqueued(workName, inputData)
            if (failEnqueue) return false
            markActive(workName)
            return true
        }

        override suspend fun cancel(workName: String): Boolean {
            cancelled += workName
            markFinished(workName, QueueState.CANCELLED)
            return true
        }

        override fun observe(workName: String): Flow<QueueSnapshot?> = snapshotOf(workName)

        private fun snapshotOf(workName: String): MutableStateFlow<QueueSnapshot?> =
            snapshots.getOrPut(workName) { MutableStateFlow(null) }
    }
}
