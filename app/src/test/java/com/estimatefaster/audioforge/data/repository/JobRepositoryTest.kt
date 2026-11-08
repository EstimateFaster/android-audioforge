package com.estimatefaster.audioforge.data.repository

import com.estimatefaster.audioforge.data.db.JobEntity
import com.estimatefaster.audioforge.data.db.JobStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P3-01：JobRepository 的状态机、单活跃约束、stats 合并、stopRequested、sweep 与查询。
 *
 * DAO 的 SQL 条件更新在 `JobDaoTest` 里单独覆盖；这里验证仓库层语义与原子性。
 */
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

    // ------------------------------------------------------ start / 单活跃约束

    @Test
    fun start_creates_queued_job_and_enforces_single_active_per_novel() = runTest {
        val novelId = fixture.createEmptyNovel("任务书")
        val otherNovel = fixture.createEmptyNovel("别的书")

        val job = fixture.jobs.start(
            novelId = novelId,
            mode = "full",
            requestedIds = listOf(3L, 1L, 3L),
            stats = mapOf("chaptersTotal" to 2L),
            now = 500L,
        )
        assertEquals(JobStatus.QUEUED, job.status)
        assertTrue(job.id > 0)
        assertEquals("[3,1]", job.requestedIdsJson)
        assertEquals(500L, job.startedAt)
        assertNull(job.finishedAt)
        assertEquals(2L, statsOf(job).value("chaptersTotal"))

        assertEquals(job.id, fixture.jobs.observeActive(novelId).first()?.id)
        assertEquals(job.id, fixture.jobs.activeJob(novelId)?.id)
        assertEquals(1, fixture.jobs.countActive(novelId))

        // 同一本书重复 start 必须被拒绝，且异常里带上冲突任务。
        try {
            fixture.jobs.start(novelId, "full", now = 600L)
            fail("已有活跃任务时 start 必须抛 JobAlreadyActiveException")
        } catch (expected: JobAlreadyActiveException) {
            assertEquals(job.id, expected.activeJob.id)
        }
        assertEquals(1, fixture.jobs.countActive(novelId))

        // 别的书不受影响。
        val otherJob = fixture.jobs.start(otherNovel, "parse", now = 700L)
        assertEquals(1, fixture.jobs.countActive(otherNovel))

        // 终态释放单活跃名额。
        assertTrue(fixture.jobs.markCancelled(job.id, now = 800L))
        assertEquals(0, fixture.jobs.countActive(novelId))
        val restarted = fixture.jobs.start(novelId, "full", now = 900L)
        assertTrue(restarted.id > job.id)

        assertEquals("parse", otherJob.mode)
    }

    @Test
    fun start_rejects_invalid_arguments_without_writing_a_job() = runTest {
        val novelId = fixture.createEmptyNovel("参数校验")

        assertIllegalArgument { fixture.jobs.start(novelId = 0L, mode = "full") }
        assertIllegalArgument { fixture.jobs.start(novelId, mode = "  ") }
        assertIllegalArgument { fixture.jobs.start(novelId, mode = "full", requestedIds = listOf(0L)) }
        assertIllegalArgument {
            fixture.jobs.start(novelId, mode = "full", stats = mapOf(" " to 1L))
        }

        assertEquals(0, fixture.jobs.countActive(novelId))
        assertNull(fixture.jobs.observeActive(novelId).first())
    }

    // ------------------------------------------------------------ 完整生命周期

    @Test
    fun queued_running_done_lifecycle_merges_stats_and_moves_to_last_finished() = runTest {
        val novelId = fixture.createEmptyNovel("生命周期")
        val job = start(
            novelId,
            stats = mapOf("chaptersTotal" to 5L, "segTotal" to 100L),
            now = 100L,
        )

        assertTrue(fixture.jobs.markRunning(job.id))
        assertEquals(JobStatus.RUNNING, fixture.jobs.get(job.id)?.status)
        assertFalse(fixture.jobs.markRunning(job.id)) // 重复调用不重复生效

        assertTrue(
            fixture.jobs.updateProgress(
                id = job.id,
                chapterId = 11L,
                chapterTitle = "第2章",
                stats = mapOf("segDone" to 40L, "startedAt" to 100L),
            ),
        )
        val progressed = fixture.jobs.get(job.id)!!
        assertEquals(11L, progressed.currentChapterId)
        assertEquals("第2章", progressed.currentTitle)
        assertEquals(5L, statsOf(progressed).value("chaptersTotal"))
        assertEquals(40L, statsOf(progressed).value("segDone"))

        assertTrue(
            fixture.jobs.markDone(
                id = job.id,
                stats = mapOf(
                    "chaptersDone" to 1L,
                    "segDone" to 50L,
                    "ttsOk" to 45L,
                    "ttsFailed" to 5L,
                ),
                now = 200L,
            ),
        )

        val done = fixture.jobs.get(job.id)!!
        assertEquals(JobStatus.DONE, done.status)
        assertEquals(200L, done.finishedAt)
        assertNull(done.lastError)
        assertFalse(done.stopRequested)
        // 进度字段保留，stats 增量覆盖同名 key、其余 key 原样保留。
        assertEquals("第2章", done.currentTitle)
        assertEquals(5L, statsOf(done).value("chaptersTotal"))
        assertEquals(100L, statsOf(done).value("segTotal"))
        assertEquals(1L, statsOf(done).value("chaptersDone"))
        assertEquals(50L, statsOf(done).value("segDone"))
        assertEquals(45L, statsOf(done).value("ttsOk"))
        assertEquals(5L, statsOf(done).value("ttsFailed"))
        assertEquals(100L, statsOf(done).value("startedAt"))

        assertNull(fixture.jobs.observeActive(novelId).first())
        assertEquals(job.id, fixture.jobs.lastFinished(novelId)?.id)
    }

    @Test
    fun terminal_job_is_immutable_and_illegal_transitions_are_rejected() = runTest {
        val novelId = fixture.createEmptyNovel("非法跃迁")
        val job = start(novelId, now = 100L)

        // queued 不能直接 done/error。
        assertFalse(fixture.jobs.markDone(job.id, now = 150L))
        assertFalse(fixture.jobs.markError(job.id, "不合法", now = 150L))
        assertEquals(JobStatus.QUEUED, fixture.jobs.get(job.id)?.status)
        assertEquals("{}", fixture.jobs.get(job.id)?.statsJson)

        assertTrue(fixture.jobs.markRunning(job.id))
        assertTrue(fixture.jobs.markDone(job.id, mapOf("chaptersDone" to 1L), now = 200L))

        // done 是终态：不能再回退或改写。
        assertFalse(fixture.jobs.markRunning(job.id))
        assertFalse(fixture.jobs.markError(job.id, "晚到错误", now = 300L))
        assertFalse(fixture.jobs.markCancelled(job.id, now = 300L))
        assertFalse(fixture.jobs.markDone(job.id, mapOf("chaptersDone" to 99L), now = 400L))
        assertFalse(fixture.jobs.mergeStats(job.id, mapOf("chaptersDone" to 99L)))

        val done = fixture.jobs.get(job.id)!!
        assertEquals(JobStatus.DONE, done.status)
        assertEquals(200L, done.finishedAt)
        assertNull(done.lastError)
        assertEquals(1L, statsOf(done).value("chaptersDone"))

        // queued 可以直接 cancelled（排队期间用户点停止）。
        val queued = start(novelId, now = 500L)
        assertTrue(fixture.jobs.markCancelled(queued.id, now = 600L))
        assertFalse(fixture.jobs.markRunning(queued.id))
        assertEquals(JobStatus.CANCELLED, fixture.jobs.get(queued.id)?.status)
    }

    @Test
    fun mark_error_records_message_stats_and_finished_time() = runTest {
        val novelId = fixture.createEmptyNovel("失败收尾")
        val job = start(novelId, stats = mapOf("chaptersTotal" to 5L, "segDone" to 10L), now = 100L)
        assertTrue(fixture.jobs.markRunning(job.id))

        assertTrue(
            fixture.jobs.markError(
                id = job.id,
                error = "TTS 超时",
                stats = mapOf("ttsFailed" to 2L, "retries" to 1L),
                now = 300L,
            ),
        )

        val error = fixture.jobs.get(job.id)!!
        assertEquals(JobStatus.ERROR, error.status)
        assertEquals(300L, error.finishedAt)
        assertEquals("TTS 超时", error.lastError)
        assertFalse(error.stopRequested)
        assertEquals(5L, statsOf(error).value("chaptersTotal"))
        assertEquals(10L, statsOf(error).value("segDone"))
        assertEquals(2L, statsOf(error).value("ttsFailed"))
        assertEquals(1L, statsOf(error).value("retries"))

        // 重复失败不能覆盖第一次的错误信息。
        assertFalse(fixture.jobs.markError(job.id, "第二次错误", now = 400L))
        assertEquals("TTS 超时", fixture.jobs.get(job.id)?.lastError)

        assertNull(fixture.jobs.observeActive(novelId).first())
        assertEquals(job.id, fixture.jobs.lastFinished(novelId)?.id)
    }

    // ------------------------------------------------------ statsJson 合并

    @Test
    fun stats_merge_preserves_unknown_keys_and_recovers_from_malformed_json() = runTest {
        val novelId = fixture.createEmptyNovel("统计合并")
        val job = start(novelId, stats = mapOf("segDone" to 1L, "custom" to 7L), now = 100L)

        assertTrue(
            fixture.jobs.mergeStats(job.id, mapOf("segDone" to 2L, "chaptersTotal" to 3L)),
        )
        val merged = statsOf(fixture.jobs.get(job.id)!!)
        assertEquals(2L, merged.value("segDone"))
        assertEquals(3L, merged.value("chaptersTotal"))
        assertEquals(7L, merged.value("custom"))

        // 脏数据不应让收尾流程永久失败：按空对象重建。
        val brokenId = fixture.db.jobDao().insert(
            JobEntity(
                novelId = novelId,
                mode = "full",
                status = JobStatus.QUEUED,
                requestedIdsJson = "[]",
                startedAt = 200L,
                statsJson = "not-json",
            ),
        )
        assertTrue(fixture.jobs.mergeStats(brokenId, mapOf("segDone" to 1L)))
        assertEquals("""{"segDone":1}""", fixture.jobs.get(brokenId)?.statsJson)

        // 终态不接受 stats 写入。
        assertTrue(fixture.jobs.markCancelled(job.id, now = 300L))
        assertFalse(fixture.jobs.mergeStats(job.id, mapOf("segDone" to 99L)))
        assertEquals(2L, statsOf(fixture.jobs.get(job.id)!!).value("segDone"))
    }

    @Test
    fun update_progress_only_applies_to_active_jobs() = runTest {
        val novelId = fixture.createEmptyNovel("进度")
        val job = start(novelId, now = 100L)

        assertTrue(
            fixture.jobs.updateProgress(job.id, chapterId = 1L, chapterTitle = "第1章", stats = mapOf("segDone" to 1L)),
        )
        assertEquals(JobStatus.QUEUED, fixture.jobs.get(job.id)?.status)

        assertTrue(fixture.jobs.markRunning(job.id))
        assertTrue(
            fixture.jobs.updateProgress(job.id, chapterId = 2L, chapterTitle = "第2章", stats = mapOf("segDone" to 2L)),
        )

        assertTrue(fixture.jobs.markDone(job.id, mapOf("chaptersDone" to 1L), now = 400L))
        assertFalse(
            fixture.jobs.updateProgress(job.id, chapterId = 3L, chapterTitle = "第3章", stats = mapOf("segDone" to 99L)),
        )

        val done = fixture.jobs.get(job.id)!!
        assertEquals("第2章", done.currentTitle)
        assertEquals(2L, done.currentChapterId)
        assertEquals(2L, statsOf(done).value("segDone"))
        assertEquals(1L, statsOf(done).value("chaptersDone"))
    }

    // --------------------------------------------------------- stopRequested

    @Test
    fun stop_request_is_readable_resettable_and_cleared_by_terminal_states() = runTest {
        val novelId = fixture.createEmptyNovel("停止")
        val job = start(novelId, now = 100L)

        assertFalse(fixture.jobs.isStopRequested(job.id))
        assertTrue(fixture.jobs.requestStop(job.id))
        assertTrue(fixture.jobs.isStopRequested(job.id))

        // markRunning 不清停止标记，Worker 启动后仍能读到。
        assertTrue(fixture.jobs.markRunning(job.id))
        assertTrue(fixture.jobs.isStopRequested(job.id))

        assertTrue(fixture.jobs.clearStopRequested(job.id))
        assertFalse(fixture.jobs.isStopRequested(job.id))
        assertFalse(fixture.jobs.clearStopRequested(job.id))

        // 终态自动复位，且终态上 requestStop 无效。
        assertTrue(fixture.jobs.requestStop(job.id))
        assertTrue(fixture.jobs.markCancelled(job.id, now = 200L))
        assertFalse(fixture.jobs.isStopRequested(job.id))
        assertFalse(fixture.jobs.requestStop(job.id))

        val next = start(novelId, now = 300L)
        assertTrue(fixture.jobs.markRunning(next.id))
        assertTrue(fixture.jobs.requestStop(next.id))
        assertTrue(fixture.jobs.markDone(next.id, now = 400L))
        assertFalse(fixture.jobs.get(next.id)!!.stopRequested)
    }

    // ------------------------------------------------------------- sweepRunning

    @Test
    fun sweep_running_resets_only_running_jobs_and_frees_the_novel_for_restart() = runTest {
        val first = fixture.createEmptyNovel("书一")
        val second = fixture.createEmptyNovel("书二")
        val firstJob = start(first, now = 100L)
        val secondJob = start(second, now = 200L)
        assertTrue(fixture.jobs.markRunning(firstJob.id))
        assertTrue(fixture.jobs.markRunning(secondJob.id))
        assertTrue(fixture.jobs.requestStop(firstJob.id))

        assertEquals(1, fixture.jobs.sweepRunning(first))

        val swept = fixture.jobs.get(firstJob.id)!!
        assertEquals(JobStatus.PENDING, swept.status)
        assertNull(swept.finishedAt)
        assertFalse(swept.stopRequested)
        assertEquals(JobStatus.RUNNING, fixture.jobs.get(secondJob.id)?.status)
        assertEquals(secondJob.id, fixture.jobs.observeActive(second).first()?.id)
        // pending 既不是活跃也不是“上次完成”，不占用单活跃名额。
        assertNull(fixture.jobs.observeActive(first).first())
        assertNull(fixture.jobs.lastFinished(first))
        assertTrue(fixture.jobs.start(first, "full", now = 300L).id > firstJob.id)

        assertEquals(1, fixture.jobs.sweepRunning())
        assertEquals(JobStatus.PENDING, fixture.jobs.get(secondJob.id)?.status)
        assertEquals(0, fixture.jobs.sweepRunning())
    }

    @Test
    fun last_finished_uses_finished_time_and_ignores_active_jobs() = runTest {
        val novelId = fixture.createEmptyNovel("上次完成")
        val first = start(novelId, now = 100L)
        assertTrue(fixture.jobs.markRunning(first.id))
        assertTrue(fixture.jobs.markDone(first.id, now = 200L))

        val second = start(novelId, now = 300L)
        assertTrue(fixture.jobs.markRunning(second.id))
        assertTrue(fixture.jobs.markError(second.id, "网络失败", now = 400L))

        assertEquals(second.id, fixture.jobs.lastFinished(novelId)?.id)
        assertEquals(JobStatus.ERROR, fixture.jobs.lastFinished(novelId)?.status)
        assertNull(fixture.jobs.observeActive(novelId).first())

        // 更新的活跃任务不参与 lastFinished。
        start(novelId, now = 500L)
        assertEquals(second.id, fixture.jobs.lastFinished(novelId)?.id)
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun start(
        novelId: Long,
        stats: Map<String, Long> = emptyMap(),
        now: Long,
    ): JobEntity = fixture.jobs.start(
        novelId = novelId,
        mode = "full",
        stats = stats,
        now = now,
    )

    private fun statsOf(job: JobEntity): JsonObject =
        Json.parseToJsonElement(job.statsJson).jsonObject

    private fun JsonObject.value(key: String): Long = getValue(key).jsonPrimitive.long

    private suspend fun assertIllegalArgument(block: suspend () -> Unit) {
        try {
            block()
            fail("期望 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // 期望路径：参数校验在写库前拒绝。
        }
    }
}
