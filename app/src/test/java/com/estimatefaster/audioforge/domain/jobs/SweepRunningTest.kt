package com.estimatefaster.audioforge.domain.jobs

import androidx.work.Data
import com.estimatefaster.audioforge.core.model.PipelineError
import com.estimatefaster.audioforge.data.db.JobEntity
import com.estimatefaster.audioforge.data.db.JobStatus
import com.estimatefaster.audioforge.data.repository.RepositoryFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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
 * P3-05：`SweepRunning` 的启动/收尾 sweep、终态等待、取消与错误汇总。
 *
 * Robolectric + in-memory Room + fake [JobQueue] + fake `isWorkActive`：
 * 不启动真实 WorkManager，也不依赖设备/TestDriver。轮询测试使用 `runTest` 的
 * 虚拟时间（[SweepRunning] 用 `withTimeoutOrNull` 计时）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SweepRunningTest {

    private lateinit var fixture: RepositoryFixture
    private lateinit var queue: FakeJobQueue
    private lateinit var sweep: SweepRunning
    private val knownNovels = mutableListOf<Long>()
    private var now: Long = 10_000L

    @Before
    fun setUp() {
        fixture = RepositoryFixture()
        queue = FakeJobQueue()
        knownNovels.clear()
        now = 10_000L
        sweep = SweepRunning(
            jobs = fixture.jobs,
            jobDao = fixture.db.jobDao(),
            clock = { now++ },
            awaitPollMs = 50L,
            queue = queue,
            knownNovelIds = { knownNovels.toList() },
        )
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    // ------------------------------------------------------------ App 启动 sweep

    @Test
    fun appStart_sweeps_only_running_and_clears_stop_flag() = runTest {
        val runningNovel = newNovel("运行中")
        knownNovels += runningNovel
        val runningJob = insertJob(
            novelId = runningNovel,
            status = JobStatus.RUNNING,
            stopRequested = true,
        )

        val queuedNovel = newNovel("排队中")
        knownNovels += queuedNovel
        val queuedJob = insertJob(
            novelId = queuedNovel,
            status = JobStatus.QUEUED,
            stopRequested = true,
        )

        val doneNovel = newNovel("已完成")
        knownNovels += doneNovel
        val doneJob = insertJob(doneNovel, JobStatus.DONE, finishedAt = now++)

        val errorNovel = newNovel("失败")
        knownNovels += errorNovel
        val errorJob = insertJob(
            novelId = errorNovel,
            status = JobStatus.ERROR,
            lastError = "2 章失败：第一章：网络不可用",
            finishedAt = now++,
        )

        val cancelledNovel = newNovel("已取消")
        knownNovels += cancelledNovel
        val cancelledJob = insertJob(
            novelId = cancelledNovel,
            status = JobStatus.CANCELLED,
            finishedAt = now++,
        )

        val pendingNovel = newNovel("上次中断")
        knownNovels += pendingNovel
        val pendingJob = insertJob(pendingNovel, JobStatus.PENDING)

        val report = sweep.sweepOnAppStart()

        assertEquals(1, report.count)
        assertEquals(listOf(runningJob), report.details.map { it.jobId })
        assertEquals(runningNovel, report.details.single().novelId)
        assertFalse("app 启动 sweep 不应被标记为 work-active 跳过", report.skippedBecauseWorkActive)

        val swept = fixture.jobs.get(runningJob)!!
        assertEquals(JobStatus.PENDING, swept.status)
        assertFalse("残留 running 的 stopRequested 必须清掉", swept.stopRequested)
        assertNull(swept.finishedAt)

        // queued 不碰：仍在排队，且它的 stopRequested 不属于「残留 running」，不能被清。
        val queued = fixture.jobs.get(queuedJob)!!
        assertEquals(JobStatus.QUEUED, queued.status)
        assertTrue("queued 的停止标记不应被 sweep 误清", queued.stopRequested)

        assertEquals(JobStatus.DONE, fixture.jobs.get(doneJob)!!.status)
        assertEquals(JobStatus.ERROR, fixture.jobs.get(errorJob)!!.status)
        assertEquals(JobStatus.CANCELLED, fixture.jobs.get(cancelledJob)!!.status)
        assertEquals(JobStatus.PENDING, fixture.jobs.get(pendingJob)!!.status)

        // 幂等：第二次没有 running 可复位。
        val second = sweep.sweepOnAppStart()
        assertEquals(0, second.count)
        assertTrue(second.details.isEmpty())
    }

    @Test
    fun appStart_with_no_running_returns_empty_report() = runTest {
        val novelId = newNovel()
        knownNovels += novelId
        insertJob(novelId, JobStatus.QUEUED)

        val report = sweep.sweepOnAppStart()

        assertEquals(0, report.count)
        assertTrue(report.details.isEmpty())
        assertFalse(report.skippedBecauseWorkActive)
    }

    // ------------------------------------------------------------ 批次后 sweep

    @Test
    fun afterBatch_skips_when_work_is_still_active() = runTest {
        val (novelId, jobId) = createRunningJob()
        val seenNames = mutableListOf<String>()

        val report = sweep.sweepAfterBatch(novelId) { name ->
            seenNames += name
            true
        }

        assertEquals(0, report.count)
        assertTrue(report.details.isEmpty())
        assertTrue(report.skippedBecauseWorkActive)
        assertEquals(
            "isWorkActive 必须收到唯一任务名",
            listOf(GenerationNotifications.workName(novelId)),
            seenNames,
        )
        assertEquals(JobStatus.RUNNING, fixture.jobs.get(jobId)!!.status)
    }

    @Test
    fun afterBatch_sweeps_only_given_novel_when_no_work_is_active() = runTest {
        val novelA = newNovel("A")
        knownNovels += novelA
        // 先插 queued 再插 running：activeJob 取 startedAt 最新的一行，确保明细能定位 running。
        val queuedA = insertJob(novelA, JobStatus.QUEUED, stopRequested = true)
        val runningA = insertJob(novelA, JobStatus.RUNNING, stopRequested = true)

        val (novelB, runningB) = createRunningJob("B")

        val report = sweep.sweepAfterBatch(novelA) { false }

        assertEquals(1, report.count)
        assertEquals(listOf(runningA), report.details.map { it.jobId })
        assertFalse(report.skippedBecauseWorkActive)
        assertEquals(JobStatus.PENDING, fixture.jobs.get(runningA)!!.status)
        assertFalse(fixture.jobs.get(runningA)!!.stopRequested)

        // 同一本书的 queued 不动；别的书的 running 也不动。
        assertEquals(JobStatus.QUEUED, fixture.jobs.get(queuedA)!!.status)
        assertTrue(fixture.jobs.get(queuedA)!!.stopRequested)
        assertEquals(JobStatus.RUNNING, fixture.jobs.get(runningB)!!.status)
        assertEquals(novelB, fixture.jobs.get(runningB)!!.novelId)
    }

    @Test
    fun afterBatch_treats_probe_failure_as_active() = runTest {
        val (novelId, jobId) = createRunningJob()

        val report = sweep.sweepAfterBatch(novelId) { throw IllegalStateException("WorkManager 不可用") }

        assertEquals(0, report.count)
        assertTrue(report.skippedBecauseWorkActive)
        assertEquals(JobStatus.RUNNING, fixture.jobs.get(jobId)!!.status)
    }

    // ------------------------------------------------------------ awaitTerminal

    @Test
    fun awaitTerminal_returns_terminal_job_without_polling() = runTest {
        val (_, jobId) = createRunningJob()
        assertTrue(fixture.jobs.markDone(jobId))

        val job = sweep.awaitTerminal(jobId, timeoutMs = 1_000L)

        assertEquals(jobId, job?.id)
        assertEquals(JobStatus.DONE, job?.status)
    }

    @Test
    fun awaitTerminal_waits_until_job_becomes_terminal() = runTest {
        val (_, jobId) = createRunningJob()
        val finisher = launch {
            delay(120L)
            assertTrue(fixture.jobs.markDone(jobId))
        }

        val job = sweep.awaitTerminal(jobId, timeoutMs = 2_000L)
        finisher.join()

        assertEquals(jobId, job?.id)
        assertEquals(JobStatus.DONE, job?.status)
    }

    @Test
    fun awaitTerminal_returns_null_on_timeout_and_keeps_running() = runTest {
        val (_, jobId) = createRunningJob()

        val job = sweep.awaitTerminal(jobId, timeoutMs = 200L)

        assertNull(job)
        assertEquals(JobStatus.RUNNING, fixture.jobs.get(jobId)!!.status)
    }

    @Test
    fun awaitTerminal_returns_null_for_missing_or_invalid_job() = runTest {
        assertNull(sweep.awaitTerminal(9_999L, timeoutMs = 100L))
        assertNull(sweep.awaitTerminal(0L))
        assertNull(sweep.awaitTerminal(-1L))
    }

    @Test
    fun awaitTerminal_propagates_cancellation() = runTest {
        val (_, jobId) = createRunningJob()
        val deferred = async { sweep.awaitTerminal(jobId, timeoutMs = 60_000L) }
        runCurrent()

        deferred.cancel()
        deferred.join()

        assertTrue("外部取消不能伪装成超时/null", deferred.isCancelled)
        assertEquals(JobStatus.RUNNING, fixture.jobs.get(jobId)!!.status)
    }

    @Test
    fun awaitTerminal_timeout_zero_returns_null_for_running_job() = runTest {
        val (_, jobId) = createRunningJob()
        assertNull(sweep.awaitTerminal(jobId, timeoutMs = 0L))
    }

    // ------------------------------------------------------------ cancelAndAwait

    @Test
    fun cancelAndAwait_requests_stop_then_cancels_work_and_awaits_terminal() = runTest {
        val (novelId, jobId) = createRunningJob()
        val workName = GenerationNotifications.workName(novelId)
        val stopSeenAtCancel = CompletableDeferred<Boolean>()
        queue.onCancel = { _ ->
            // cancel 发生时 stopRequested 必须已经落库，Worker 才能在任何安全点看到。
            stopSeenAtCancel.complete(fixture.jobs.isStopRequested(jobId))
            Unit
        }
        val worker = launch {
            assertTrue("queue.cancel 前必须先 requestStop", stopSeenAtCancel.await())
            delay(60L)
            assertTrue(fixture.jobs.markCancelled(jobId))
        }

        val terminal = sweep.cancelAndAwait(novelId, workName, timeoutMs = 2_000L)
        worker.join()

        assertEquals(jobId, terminal?.id)
        assertEquals(JobStatus.CANCELLED, terminal?.status)
        assertEquals(listOf(workName), queue.cancelled)
    }

    @Test
    fun cancelAndAwait_marks_queued_job_cancelled_without_waiting_for_worker() = runTest {
        val novelId = newNovel()
        knownNovels += novelId
        val jobId = insertJob(novelId, JobStatus.QUEUED)
        val workName = GenerationNotifications.workName(novelId)

        val terminal = sweep.cancelAndAwait(novelId, workName, timeoutMs = 1_000L)

        assertEquals(jobId, terminal?.id)
        assertEquals(JobStatus.CANCELLED, terminal?.status)
        assertEquals(listOf(workName), queue.cancelled)
    }

    @Test
    fun cancelAndAwait_returns_null_without_active_job_and_does_not_cancel_queue() = runTest {
        val novelId = newNovel()
        knownNovels += novelId

        val terminal = sweep.cancelAndAwait(novelId, "generation-$novelId", timeoutMs = 100L)

        assertNull(terminal)
        assertTrue(queue.cancelled.isEmpty())
    }

    @Test
    fun cancelAndAwait_still_awaits_when_queue_cancel_fails() = runTest {
        val (novelId, jobId) = createRunningJob()
        val workName = GenerationNotifications.workName(novelId)
        queue.cancelResult = false
        // 用 cancel 回调同步落终态，避免与虚拟时间轮询竞争（本用例只验证 cancel 失败仍会等待）。
        queue.cancelHook = { assertTrue(fixture.jobs.markCancelled(jobId)) }

        val terminal = sweep.cancelAndAwait(novelId, workName, timeoutMs = 2_000L)

        assertEquals(JobStatus.CANCELLED, terminal?.status)
        assertEquals(listOf(workName), queue.cancelled)
    }

    @Test
    fun cancelAndAwait_times_out_without_forcing_status() = runTest {
        val (novelId, jobId) = createRunningJob()

        val terminal = sweep.cancelAndAwait(
            novelId = novelId,
            workName = GenerationNotifications.workName(novelId),
            timeoutMs = 200L,
        )

        assertNull(terminal)
        val job = fixture.jobs.get(jobId)!!
        assertEquals("超时不能强行改状态，留给 sweepAfterBatch", JobStatus.RUNNING, job.status)
        assertTrue(job.stopRequested)
        assertEquals(1, queue.cancelled.size)
    }

    // ------------------------------------------------------------ 错误汇总

    @Test
    fun aggregateErrors_summarizes_failed_chapters_and_codes() = runTest {
        val novelId = newNovel()
        val jobId = insertJob(
            novelId = novelId,
            status = JobStatus.ERROR,
            lastError = "2 章失败：第一章：网络不可用，已暂停，恢复后继续；" +
                "第二章：额度不足或被限流，稍后再试",
            statsJson = """{"chaptersTotal":5,"chaptersFailed":2,"ttsFailed":1}""",
            finishedAt = now++,
        )

        val summary = sweep.aggregateErrors(novelId)

        assertEquals(novelId, summary.novelId)
        assertEquals(jobId, summary.jobId)
        assertEquals(JobStatus.ERROR, summary.status)
        assertEquals(5, summary.chaptersTotal)
        assertEquals(2, summary.chaptersFailed)
        assertEquals(1L, summary.ttsFailed)
        assertEquals(2, summary.details.size)
        assertEquals("第一章", summary.details[0].chapterTitle)
        assertEquals(PipelineError.NETWORK, summary.details[0].code)
        assertEquals("第二章", summary.details[1].chapterTitle)
        assertEquals(PipelineError.QUOTA, summary.details[1].code)
        assertEquals(
            listOf(PipelineError.NETWORK, PipelineError.QUOTA),
            summary.errorCodes,
        )
        assertTrue(summary.hasErrors)
    }

    @Test
    fun aggregateErrors_returns_empty_summary_without_history() = runTest {
        val novelId = newNovel()

        val summary = sweep.aggregateErrors(novelId)

        assertEquals(novelId, summary.novelId)
        assertNull(summary.jobId)
        assertEquals(0, summary.chaptersTotal)
        assertEquals(0, summary.chaptersFailed)
        assertEquals(0L, summary.ttsFailed)
        assertTrue(summary.details.isEmpty())
        assertTrue(summary.errorCodes.isEmpty())
        assertFalse(summary.hasErrors)
    }

    @Test
    fun aggregateErrors_uses_last_finished_job_only() = runTest {
        val novelId = newNovel()
        insertJob(
            novelId = novelId,
            status = JobStatus.ERROR,
            lastError = "1 章失败：第一章：网络不可用",
            finishedAt = 1_000L,
        )
        val doneJob = insertJob(
            novelId = novelId,
            status = JobStatus.DONE,
            statsJson = """{"chaptersTotal":3,"chaptersDone":3}""",
            finishedAt = 2_000L,
        )

        val summary = sweep.aggregateErrors(novelId)

        assertEquals(doneJob, summary.jobId)
        assertEquals(JobStatus.DONE, summary.status)
        assertEquals(3, summary.chaptersTotal)
        assertEquals(0, summary.chaptersFailed)
        assertTrue(summary.details.isEmpty())
        assertFalse(summary.hasErrors)
    }

    @Test
    fun aggregateErrors_tolerates_dirty_stats_and_unstructured_error() = runTest {
        val novelId = newNovel()
        insertJob(
            novelId = novelId,
            status = JobStatus.ERROR,
            lastError = "1 章失败：第一章：未知故障",
            statsJson = "{not-json",
            finishedAt = now++,
        )

        val summary = sweep.aggregateErrors(novelId)

        // stats 解析失败时用 lastError 的 “N 章失败” 与明细条数兜底。
        assertEquals(1, summary.chaptersFailed)
        assertEquals(0, summary.chaptersTotal)
        assertEquals(1, summary.details.size)
        assertEquals("未知故障", summary.details.single().message)
        assertEquals(SweepRunning.UNKNOWN_ERROR_CODE, summary.details.single().code)
        assertEquals(listOf(SweepRunning.UNKNOWN_ERROR_CODE), summary.errorCodes)
        assertTrue(summary.hasErrors)
    }

    @Test
    fun aggregateErrors_clamps_negative_stats_and_ignores_missing_error() = runTest {
        val novelId = newNovel()
        insertJob(
            novelId = novelId,
            status = JobStatus.ERROR,
            statsJson = """{"chaptersTotal":-3,"chaptersFailed":-2,"ttsFailed":-1}""",
            finishedAt = now++,
        )

        val summary = sweep.aggregateErrors(novelId)

        assertEquals(0, summary.chaptersTotal)
        assertEquals(0, summary.chaptersFailed)
        assertEquals(0L, summary.ttsFailed)
        assertTrue(summary.details.isEmpty())
        assertFalse(summary.hasErrors)
    }

    @Test
    fun aggregateErrors_limits_details_but_keeps_all_codes() = runTest {
        val novelId = newNovel()
        insertJob(
            novelId = novelId,
            status = JobStatus.ERROR,
            lastError = "4 章失败：一：网络不可用；二：额度不足；三：存储空间不足；四：神秘故障",
            statsJson = """{"chaptersTotal":4,"chaptersFailed":4}""",
            finishedAt = now++,
        )

        val summary = sweep.aggregateErrors(novelId, limit = 2)

        assertEquals(4, summary.chaptersFailed)
        assertEquals(2, summary.details.size)
        assertEquals(
            listOf(
                PipelineError.NETWORK,
                PipelineError.QUOTA,
                PipelineError.STORAGE,
                SweepRunning.UNKNOWN_ERROR_CODE,
            ),
            summary.errorCodes,
        )
    }

    @Test
    fun aggregateErrors_with_zero_limit_returns_counts_without_details() = runTest {
        val novelId = newNovel()
        insertJob(
            novelId = novelId,
            status = JobStatus.ERROR,
            lastError = "1 章失败：第一章：网络不可用",
            finishedAt = now++,
        )

        val summary = sweep.aggregateErrors(novelId, limit = 0)

        assertEquals(1, summary.chaptersFailed)
        assertTrue(summary.details.isEmpty())
        assertEquals(listOf(PipelineError.NETWORK), summary.errorCodes)
        assertTrue(summary.hasErrors)
    }

    // ------------------------------------------------------------ helpers

    private suspend fun newNovel(title: String = "测试项目"): Long =
        fixture.createEmptyNovel(title)

    private suspend fun createRunningJob(
        title: String = "运行中项目",
        stopRequested: Boolean = false,
    ): Pair<Long, Long> {
        val novelId = newNovel(title)
        knownNovels += novelId
        val jobId = insertJob(
            novelId = novelId,
            status = JobStatus.RUNNING,
            stopRequested = stopRequested,
        )
        return novelId to jobId
    }

    private suspend fun insertJob(
        novelId: Long,
        status: String,
        stopRequested: Boolean = false,
        lastError: String? = null,
        statsJson: String = "{}",
        finishedAt: Long? = null,
        startedAt: Long = now++,
    ): Long = fixture.jobs.insert(
        JobEntity(
            novelId = novelId,
            mode = "full",
            status = status,
            requestedIdsJson = "[]",
            startedAt = startedAt,
            finishedAt = finishedAt,
            lastError = lastError,
            statsJson = statsJson,
            stopRequested = stopRequested,
        ),
    )

    private class FakeJobQueue : JobQueue {

        val cancelled = mutableListOf<String>()
        var cancelResult = true
        var onCancel: suspend (String) -> Unit = {}

        override suspend fun enqueue(workName: String, inputData: Data): Boolean = true

        override suspend fun cancel(workName: String): Boolean {
            cancelled += workName
            cancelHook?.invoke()
            onCancel(workName)
            return cancelResult
        }

        override fun observe(workName: String): Flow<QueueSnapshot?> =
            MutableStateFlow<QueueSnapshot?>(null)
    }
}
