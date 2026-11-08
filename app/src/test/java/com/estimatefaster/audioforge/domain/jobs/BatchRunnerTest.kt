package com.estimatefaster.audioforge.domain.jobs

import androidx.work.Data
import com.estimatefaster.audioforge.data.db.JobStatus
import com.estimatefaster.audioforge.data.repository.RepositoryFixture
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P3-04：BatchRunner 的单活跃、唯一入队、停止/重启、参数校验、入队失败与观察。
 *
 * 使用 Robolectric 下的 in-memory Room + fake [JobQueue]；不启动真实 WorkManager，
 * 因此不依赖设备/TestDriver。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BatchRunnerTest {

    private lateinit var fixture: RepositoryFixture
    private lateinit var queue: FakeJobQueue
    private lateinit var runner: BatchRunner
    private var now: Long = 2_000L

    @Before
    fun setUp() {
        fixture = RepositoryFixture()
        queue = FakeJobQueue()
        runner = BatchRunner(
            queue = queue,
            jobs = fixture.jobs,
            db = fixture.db,
            clock = { now++ },
        )
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    // ------------------------------------------------------------ 启动

    @Test
    fun start_creates_queued_job_and_enqueues_once() = runTest {
        val (novelId, first, second) = fixture.createTwoChapterNovel()
        val started = startOrFail(params(novelId, first, second, force = true))

        assertTrue(started.jobId > 0L)
        assertEquals(GenerationNotifications.workName(novelId), started.workName)

        val job = fixture.jobs.get(started.jobId)
        assertNotNull(job)
        job!!
        assertEquals(JobStatus.QUEUED, job.status)
        assertEquals("full", job.mode)
        assertEquals("[$first,$second]", job.requestedIdsJson)
        assertEquals(now - 1, job.startedAt)
        assertNull(job.finishedAt)
        assertNull(job.lastError)

        val stats = Json.parseToJsonElement(job.statsJson).jsonObject
        assertEquals(2L, stats.value("chaptersTotal"))
        assertEquals(0L, stats.value("chaptersDone"))
        assertEquals(0L, stats.value("segTotal"))
        assertEquals(0L, stats.value("segDone"))

        // 只入队一次，输入与 job 行一致。
        assertEquals(1, queue.enqueued.size)
        val request = queue.enqueued.single()
        assertEquals(started.workName, request.workName)
        assertEquals(novelId, request.inputData.getLong(GenerationWorker.KEY_NOVEL_ID, -1L))
        assertEquals(started.jobId, request.inputData.getLong(GenerationWorker.KEY_JOB_ID, -1L))
        assertArrayEquals(
            longArrayOf(first, second),
            request.inputData.getLongArray(GenerationWorker.KEY_CHAPTER_IDS),
        )
        assertEquals("full", request.inputData.getString(GenerationWorker.KEY_MODE))
        assertTrue(request.inputData.getBoolean(GenerationWorker.KEY_FORCE, false))
        assertEquals(1, fixture.jobs.countActive(novelId))
    }

    @Test
    fun duplicate_start_returns_already_running_without_second_enqueue() = runTest {
        val (novelId, first, second) = fixture.createTwoChapterNovel()
        val started = startOrFail(params(novelId, first, second))

        val duplicate = runner.start(params(novelId, first, second))

        assertEquals(StartResult.AlreadyRunning(started.jobId), duplicate)
        assertEquals(1, queue.enqueued.size)
        assertEquals(1, fixture.jobs.countActive(novelId))
        assertEquals(1, fixture.countRows("jobs"))
    }

    @Test
    fun different_novels_get_independent_jobs_and_work_names() = runTest {
        val firstNovel = fixture.createTwoChapterNovel()
        val secondNovel = fixture.createTwoChapterNovel()

        val a = startOrFail(params(firstNovel.first, firstNovel.second, firstNovel.third))
        val b = startOrFail(params(secondNovel.first, secondNovel.second, secondNovel.third))

        assertNotEquals(a.jobId, b.jobId)
        assertNotEquals(a.workName, b.workName)
        assertEquals(2, queue.enqueued.size)
        assertEquals(1, fixture.jobs.countActive(firstNovel.first))
        assertEquals(1, fixture.jobs.countActive(secondNovel.first))
    }

    // ------------------------------------------------------------ 停止 / 重启

    @Test
    fun stop_sets_stop_requested_cancels_work_and_restart_creates_new_job() = runTest {
        val (novelId, first, second) = fixture.createTwoChapterNovel()
        val started = startOrFail(params(novelId, first, second))

        assertEquals(StopResult.Stopping(started.jobId), runner.stop(novelId))
        assertTrue(fixture.jobs.isStopRequested(started.jobId))
        assertEquals(listOf(started.workName), queue.cancelled)
        assertEquals(StopResult.NotRunning(999_999L), runner.stop(999_999L))

        // 停止是异步的：Worker 尚未收尾前重复 start 仍被拒绝，且不重复入队。
        assertEquals(
            StartResult.AlreadyRunning(started.jobId),
            runner.start(params(novelId, first, second)),
        )
        assertEquals(1, queue.enqueued.size)

        // 模拟 Worker 收到取消后在安全点收尾。
        assertTrue(fixture.jobs.markRunning(started.jobId))
        assertTrue(fixture.jobs.markCancelled(started.jobId))
        assertEquals(JobStatus.CANCELLED, fixture.jobs.get(started.jobId)?.status)

        // 收尾后可以重新 start，落一条新的 job 行（不复用旧行）。
        val restarted = startOrFail(params(novelId, first, second))
        assertTrue(restarted.jobId > started.jobId)
        assertEquals(2, queue.enqueued.size)
        assertEquals(1, fixture.jobs.countActive(novelId))
        assertEquals(JobStatus.CANCELLED, fixture.jobs.get(started.jobId)?.status)
    }

    // ------------------------------------------------------------ 参数校验

    @Test
    fun invalid_params_are_rejected_without_job_or_enqueue() = runTest {
        val (novelId, first, second) = fixture.createTwoChapterNovel()

        val invalid = listOf(
            "novelId 非正数" to BatchParams(0L, listOf(first), "full"),
            "章节为空" to params(novelId),
            "章节重复" to params(novelId, first, first),
            "章节 id 非正数" to params(novelId, 0L),
            "并发数过小" to params(novelId, first, second, concurrency = 0),
            "并发数过大" to params(novelId, first, second, concurrency = 9),
            "批上限过小" to params(novelId, first, second, maxBatch = 0),
            "批上限过大" to params(novelId, first, second, maxBatch = 21),
            "章节数超上限" to params(novelId, first, second, 999L, maxBatch = 2),
            "模式非法" to params(novelId, first, mode = "bogus"),
        )

        invalid.forEach { (label, params) ->
            val result = runner.start(params)
            assertTrue("$label 应被拒绝，实际：$result", result is StartResult.Failure)
            assertTrue("$label 应给出可读原因", (result as StartResult.Failure).reason.isNotBlank())
        }

        assertTrue(queue.enqueued.isEmpty())
        assertEquals(0, fixture.countRows("jobs"))
        assertEquals(0, fixture.jobs.countActive(novelId))
    }

    @Test
    fun unknown_novel_is_rejected_without_leaking_a_job() = runTest {
        val result = runner.start(params(999_999L, 1L))

        assertTrue("项目不存在时应返回失败，实际：$result", result is StartResult.Failure)
        assertTrue(queue.enqueued.isEmpty())
        assertEquals(0, fixture.countRows("jobs"))
    }

    // ------------------------------------------------------------ 入队失败

    @Test
    fun enqueue_failure_marks_job_error_and_releases_active_slot() = runTest {
        queue.failEnqueue = true
        val (novelId, first, second) = fixture.createTwoChapterNovel()

        val result = runner.start(params(novelId, first, second))

        assertTrue("入队失败应返回 Failure，实际：$result", result is StartResult.Failure)
        assertTrue((result as StartResult.Failure).reason.contains("入队"))
        assertEquals(1, queue.enqueued.size)
        assertEquals(0, fixture.jobs.countActive(novelId))

        val job = fixture.jobs.lastFinished(novelId)
        assertNotNull(job)
        job!!
        assertEquals(JobStatus.ERROR, job.status)
        assertEquals("任务入队失败", job.lastError)
        assertNotNull(job.finishedAt)
        assertEquals(2L, Json.parseToJsonElement(job.statsJson).jsonObject.value("chaptersTotal"))

        // 失败已释放名额：修复队列后可以重新启动。
        queue.failEnqueue = false
        val retried = startOrFail(params(novelId, first, second))
        assertTrue(retried.jobId > job.id)
        assertEquals(1, fixture.jobs.countActive(novelId))
    }

    // ------------------------------------------------------------ 观察

    @Test
    fun observe_returns_active_then_last_finished() = runTest {
        val (novelId, first, second) = fixture.createTwoChapterNovel()
        assertNull(runner.observe(novelId).first())

        val started = startOrFail(params(novelId, first, second))
        val active = runner.observe(novelId).first()
        assertNotNull(active)
        assertEquals(started.jobId, active?.id)
        assertEquals(JobStatus.QUEUED, active?.status)

        assertTrue(fixture.jobs.markRunning(started.jobId))
        assertTrue(
            fixture.jobs.markDone(
                id = started.jobId,
                stats = mapOf("chaptersDone" to 2L),
                now = 5_000L,
            ),
        )

        val finished = runner.observe(novelId).first()
        assertNotNull(finished)
        assertEquals(started.jobId, finished?.id)
        assertEquals(JobStatus.DONE, finished?.status)
        assertEquals(5_000L, finished?.finishedAt)
    }

    // ------------------------------------------------------------ helpers

    private suspend fun startOrFail(params: BatchParams): StartResult.Started {
        val result = runner.start(params)
        assertTrue("应启动成功，实际：$result", result is StartResult.Started)
        return result as StartResult.Started
    }

    private fun params(
        novelId: Long,
        vararg chapterIds: Long,
        mode: String = "full",
        force: Boolean = false,
        concurrency: Int = BatchParams.DEFAULT_CONCURRENCY,
        maxBatch: Int = BatchParams.DEFAULT_MAX_BATCH,
    ): BatchParams = BatchParams(
        novelId = novelId,
        chapterIds = chapterIds.toList(),
        mode = mode,
        force = force,
        concurrency = concurrency,
        maxBatch = maxBatch,
    )

    private fun JsonObject.value(key: String): Long =
        getValue(key).jsonPrimitive.long

    private class FakeJobQueue : JobQueue {

        data class Enqueued(val workName: String, val inputData: Data)

        val enqueued = mutableListOf<Enqueued>()
        val cancelled = mutableListOf<String>()
        var failEnqueue = false

        private val snapshots = mutableMapOf<String, MutableStateFlow<QueueSnapshot?>>()

        override suspend fun enqueue(workName: String, inputData: Data): Boolean {
            enqueued += Enqueued(workName, inputData)
            if (failEnqueue) return false
            snapshotOf(workName).value = QueueSnapshot(workName, QueueState.ENQUEUED)
            return true
        }

        override suspend fun cancel(workName: String): Boolean {
            cancelled += workName
            snapshotOf(workName).value = QueueSnapshot(workName, QueueState.CANCELLED)
            return true
        }

        override fun observe(workName: String): Flow<QueueSnapshot?> = snapshotOf(workName)

        private fun snapshotOf(workName: String): MutableStateFlow<QueueSnapshot?> =
            snapshots.getOrPut(workName) { MutableStateFlow(null) }
    }
}
