package com.estimatefaster.audioforge.data.repository

import com.estimatefaster.audioforge.data.db.JobEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P2-02：任务批次插入、状态流转、活跃/上次完成查询、sweepRunning。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JobRepositoryTest {

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
    fun observe_active_and_last_finished_follow_status_transitions() = runTest {
        val novelId = fixture.createEmptyNovel("任务书")
        assertNull(fixture.jobs.observeActive(novelId).first())
        assertNull(fixture.jobs.lastFinished(novelId))

        val queuedId = fixture.jobs.insert(job(novelId, status = "queued", startedAt = 100L))
        assertEquals(queuedId, fixture.jobs.observeActive(novelId).first()?.id)

        val runningId = fixture.jobs.insert(job(novelId, status = "running", startedAt = 200L))
        assertEquals(runningId, fixture.jobs.observeActive(novelId).first()?.id)

        fixture.jobs.updateStatus(
            runningId,
            status = "error",
            finishedAt = 300L,
            lastError = "网络失败",
        )

        assertEquals(queuedId, fixture.jobs.observeActive(novelId).first()?.id)
        val finished = fixture.jobs.lastFinished(novelId)
        assertEquals(runningId, finished?.id)
        assertEquals("error", finished?.status)
        assertEquals(300L, finished?.finishedAt)
        assertEquals("网络失败", finished?.lastError)

        fixture.jobs.updateStatus(queuedId, status = "running")
        assertEquals("running", fixture.jobs.get(queuedId)?.status)
        assertNull(fixture.jobs.get(queuedId)?.finishedAt)
    }

    @Test
    fun sweep_running_resets_only_running_jobs() = runTest {
        val novelId = fixture.createEmptyNovel("复位书")
        val doneId = fixture.jobs.insert(job(novelId, status = "done", startedAt = 100L))
        fixture.jobs.updateStatus(doneId, "done", finishedAt = 150L)
        val runningId = fixture.jobs.insert(job(novelId, status = "running", startedAt = 200L))
        val queuedId = fixture.jobs.insert(job(novelId, status = "queued", startedAt = 300L))

        assertEquals(1, fixture.jobs.sweepRunning())

        assertEquals("pending", fixture.jobs.get(runningId)?.status)
        assertNull(fixture.jobs.get(runningId)?.finishedAt)
        assertEquals("done", fixture.jobs.get(doneId)?.status)
        assertEquals("queued", fixture.jobs.get(queuedId)?.status)
        assertEquals(queuedId, fixture.jobs.observeActive(novelId).first()?.id)
    }

    @Test
    fun sweep_running_by_novel_does_not_touch_other_projects() = runTest {
        val first = fixture.createEmptyNovel("书一")
        val second = fixture.createEmptyNovel("书二")
        val firstRunning = fixture.jobs.insert(job(first, status = "running", startedAt = 100L))
        val secondRunning = fixture.jobs.insert(job(second, status = "running", startedAt = 200L))

        assertEquals(1, fixture.jobs.sweepRunning(first))

        assertEquals("pending", fixture.jobs.get(firstRunning)?.status)
        assertEquals("running", fixture.jobs.get(secondRunning)?.status)
        assertEquals(secondRunning, fixture.jobs.observeActive(second).first()?.id)
        assertNull(fixture.jobs.get(id = 9_999L))
    }

    @Test
    fun last_finished_ignores_active_jobs_and_uses_finished_time() = runTest {
        val novelId = fixture.createEmptyNovel("最近完成")
        val older = fixture.jobs.insert(job(novelId, status = "running", startedAt = 100L))
        fixture.jobs.updateStatus(older, "done", finishedAt = 400L)
        val newer = fixture.jobs.insert(job(novelId, status = "running", startedAt = 200L))
        fixture.jobs.updateStatus(newer, "cancelled", finishedAt = 500L)
        fixture.jobs.insert(job(novelId, status = "running", startedAt = 600L))

        assertEquals(newer, fixture.jobs.lastFinished(novelId)?.id)
        assertEquals("cancelled", fixture.jobs.lastFinished(novelId)?.status)
    }

    private fun job(novelId: Long, status: String, startedAt: Long): JobEntity = JobEntity(
        novelId = novelId,
        mode = "full",
        status = status,
        requestedIdsJson = "[]",
        startedAt = startedAt,
    )
}
