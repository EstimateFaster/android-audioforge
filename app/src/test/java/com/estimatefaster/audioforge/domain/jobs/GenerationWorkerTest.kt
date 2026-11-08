package com.estimatefaster.audioforge.domain.jobs

import android.Manifest
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.estimatefaster.audioforge.AudioForgeApp
import com.estimatefaster.audioforge.core.model.PipelineError
import com.estimatefaster.audioforge.data.db.JobEntity
import com.estimatefaster.audioforge.data.db.JobStatus
import com.estimatefaster.audioforge.data.repository.ChapterInput
import com.estimatefaster.audioforge.data.repository.ChapterRepository
import com.estimatefaster.audioforge.data.repository.JobRepository
import com.estimatefaster.audioforge.data.repository.ProjectRepository
import com.estimatefaster.audioforge.domain.pipeline.AnalyzeResult
import com.estimatefaster.audioforge.domain.pipeline.AnalyzeStatus
import com.estimatefaster.audioforge.domain.pipeline.RenderResult
import com.estimatefaster.audioforge.domain.pipeline.RenderStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * P3-09：`GenerationWorker` 的 WorkManager 状态机测试（执行计划 §11.1 / §15 P3-09）。
 *
 * 运行在 JVM + Robolectric（`@Config(sdk = [35])`），不依赖设备：
 * - `TestListenableWorkerBuilder` 直接构造 Worker，配合 `WorkManagerTestInitHelper`
 *   初始化测试用 WorkManager；
 * - 通过 [GenerationGraph.executorFactory] 注入带 fake analyze/render 的
 *   [GenerationExecutor]，不联网、不启 TTS/FFmpeg；
 * - job 仓库来自真实 `AudioForgeApp.container`（文件型 Room，每个测试用独立项目），
 *   因此断言的是 Worker 真正写入 `jobs` 表的状态，而不是内存里的假状态；
 * - 通知断言走 Robolectric 的 `ShadowNotificationManager`；`POST_NOTIFICATIONS`
 *   在测试里显式授权，避免 Sdk 35 的运行时权限噪声。
 *
 * 覆盖：成功、全部失败可重试、不可重试失败、停止（`isStopped`）、finally 无残留
 * running、未装配 `GenerationGraph`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = AudioForgeApp::class)
class GenerationWorkerTest {

    private lateinit var app: AudioForgeApp
    private lateinit var jobs: JobRepository
    private lateinit var chapters: ChapterRepository
    private lateinit var projects: ProjectRepository

    private var sequence = 0

    private val notificationManager: NotificationManager
        get() = requireNotNull(app.getSystemService(NotificationManager::class.java)) {
            "NotificationManager 不可用"
        }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<AudioForgeApp>()
        jobs = app.container.jobRepository
        chapters = app.container.chapterRepository
        projects = app.container.projectRepository

        GenerationGraph.executorFactory = null
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notificationManager).setNotificationsEnabled(true)
        notificationManager.cancelAll()
    }

    @After
    fun tearDown() {
        GenerationGraph.executorFactory = null
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    // ------------------------------------------------------------ 成功

    @Test
    fun success_marks_job_done_posts_done_notification_and_returns_success() = runTest {
        val novel = createNovel("第一章", "第二章")
        val jobId = startJob(novel)
        installParseExecutor { _, chapterId, _ ->
            AnalyzeResult(AnalyzeStatus.Done, chapterId, segmentCount = 2)
        }

        val result = buildWorker(input(novel, jobId)).doWork()

        assertEquals(Result.success(), result)
        val job = requireNotNull(jobs.get(jobId))
        assertEquals(JobStatus.DONE, job.status)
        assertNotNull(job.finishedAt)
        assertNull(job.lastError)
        assertNull(jobs.activeJob(novel.novelId))

        val stats = statsOf(job)
        assertEquals(2L, stats.getValue("chaptersTotal").jsonPrimitive.long)
        assertEquals(2L, stats.getValue("chaptersDone").jsonPrimitive.long)
        assertEquals(0L, stats.getValue("chaptersFailed").jsonPrimitive.long)

        val notification = shadowOf(notificationManager)
            .getNotification(GenerationNotifications.DONE_NOTIFICATION_ID)
        assertNotNull(notification)
        assertEquals(GenerationNotifications.CHANNEL_DONE, notification!!.channelId)
    }

    // ------------------------------------------------------------ 可重试失败

    @Test
    fun retryable_failure_returns_retry_and_keeps_job_running() = runTest {
        val novel = createNovel("第一章", "第二章")
        val jobId = startJob(novel)
        installParseExecutor { _, chapterId, _ ->
            AnalyzeResult(
                status = AnalyzeStatus.Failed,
                chapterId = chapterId,
                error = "网络不可用",
                errorCode = PipelineError.NETWORK,
            )
        }

        val result = buildWorker(input(novel, jobId), runAttemptCount = 0).doWork()

        assertEquals(Result.retry(), result)
        val job = requireNotNull(jobs.get(jobId))
        assertEquals(JobStatus.RUNNING, job.status)
        assertNull(job.finishedAt)
        assertEquals(1, jobs.countActive(novel.novelId))

        val stats = statsOf(job)
        assertEquals(2L, stats.getValue("chaptersTotal").jsonPrimitive.long)
        assertEquals(0L, stats.getValue("chaptersDone").jsonPrimitive.long)
        assertEquals(2L, stats.getValue("chaptersFailed").jsonPrimitive.long)

        // 会重试时 Worker 刻意不发失败通知，避免“失败后又自动重跑”的噪音。
        assertNull(shadowOf(notificationManager).getNotification(GenerationNotifications.FAILED_NOTIFICATION_ID))
        assertNull(shadowOf(notificationManager).getNotification(GenerationNotifications.DONE_NOTIFICATION_ID))
    }

    // ------------------------------------------------------------ 不可重试失败

    @Test
    fun non_retryable_failure_marks_job_error_and_returns_failure() = runTest {
        val novel = createNovel("第一章", "第二章")
        val jobId = startJob(novel)
        installParseExecutor { _, chapterId, _ ->
            AnalyzeResult(
                status = AnalyzeStatus.Failed,
                chapterId = chapterId,
                error = "鉴权失败",
                errorCode = PipelineError.AUTH_LLM,
            )
        }

        val result = buildWorker(input(novel, jobId)).doWork()

        assertEquals(Result.failure(), result)
        val job = requireNotNull(jobs.get(jobId))
        assertEquals(JobStatus.ERROR, job.status)
        val error = requireNotNull(job.lastError)
        assertTrue(error.contains("全部 2 章失败"))
        assertTrue(error.contains("鉴权失败"))
        assertNull(jobs.activeJob(novel.novelId))

        val notification = shadowOf(notificationManager)
            .getNotification(GenerationNotifications.FAILED_NOTIFICATION_ID)
        assertNotNull(notification)
        assertEquals(GenerationNotifications.CHANNEL_FAILED, notification!!.channelId)
    }

    // ------------------------------------------------------------ 停止

    @Test
    fun stopped_worker_marks_job_cancelled_and_posts_cancel_notification() = runTest {
        val novel = createNovel("第一章", "第二章")
        val jobId = startJob(novel, GenerationMode.Render)

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        GenerationGraph.executorFactory = {
            GenerationExecutor(
                chapters = chapters,
                jobs = jobs,
                analyzeChapter = { _, _, _ -> error("render 模式不应调用解析") },
                renderChapter = { _, chapterId, shouldStop ->
                    entered.complete(Unit)
                    release.await()
                    if (shouldStop()) {
                        RenderResult(RenderStatus.Stopped, chapterId)
                    } else {
                        RenderResult(RenderStatus.Done, chapterId)
                    }
                },
            )
        }

        val worker = buildWorker(input(novel, jobId, GenerationMode.Render))
        val running = async { worker.doWork() }

        // 等渲染真正开始后再停，验证 isStopped 会被传给执行器的 shouldStop。
        entered.await()
        worker.stop(0)
        release.complete(Unit)

        assertEquals(Result.success(), running.await())
        val job = requireNotNull(jobs.get(jobId))
        assertEquals(JobStatus.CANCELLED, job.status)
        assertNull(jobs.activeJob(novel.novelId))

        val notification = shadowOf(notificationManager)
            .getNotification(GenerationNotifications.FAILED_NOTIFICATION_ID)
        assertNotNull(notification)
        assertEquals(GenerationNotifications.CHANNEL_FAILED, notification!!.channelId)
    }

    // ------------------------------------------------------------ finally sweep

    @Test
    fun finally_leaves_no_stale_running_job_and_does_not_touch_other_projects() = runTest {
        // 另一个项目中遗留的 running 任务：本 Worker 的 finally sweep 只能清理自己的项目。
        val other = createNovel("旁观章")
        val otherJobId = startJob(other)
        assertTrue(jobs.markRunning(otherJobId))

        val novel = createNovel("第一章")
        val jobId = startJob(novel)
        installParseExecutor { _, chapterId, _ ->
            AnalyzeResult(AnalyzeStatus.Done, chapterId, segmentCount = 1)
        }

        assertEquals(Result.success(), buildWorker(input(novel, jobId)).doWork())

        // 自己的 job 收尾后不留 running，finally 的安全网不会制造“假 running”。
        assertEquals(JobStatus.DONE, requireNotNull(jobs.get(jobId)).status)
        assertNull(jobs.activeJob(novel.novelId))
        assertEquals(0, jobs.countActive(novel.novelId))

        // 别的项目仍然保持 running，证明 sweep 只作用于 input.novelId。
        assertEquals(JobStatus.RUNNING, requireNotNull(jobs.get(otherJobId)).status)
    }

    // ------------------------------------------------------------ 未装配

    @Test
    fun missing_executor_factory_returns_failure() = runTest {
        GenerationGraph.executorFactory = null
        val input = GenerationWorker.inputData(
            novelId = 42L,
            jobId = 7L,
            chapterIds = longArrayOf(1L, 2L),
            mode = GenerationMode.Full,
            force = false,
        )

        assertEquals(Result.failure(), buildWorker(input).doWork())
    }

    // ------------------------------------------------------------ 脚手架

    private data class Novel(val novelId: Long, val chapterIds: List<Long>)

    private suspend fun createNovel(vararg chapterTitles: String): Novel {
        sequence += 1
        val novelId = projects.createProject(
            title = "P3-09 项目 ${System.nanoTime()}-$sequence",
            sourceName = "p309-$sequence.txt",
            sourceRelPath = "imports/p309-$sequence.txt",
            chapters = chapterTitles.map { title ->
                ChapterInput(title = title, rawText = "$title 正文")
            },
            createdAt = System.currentTimeMillis(),
        )
        val chapterIds = chapters.observeBriefs(novelId).first().map { it.id }
        return Novel(novelId = novelId, chapterIds = chapterIds)
    }

    private suspend fun startJob(
        novel: Novel,
        mode: GenerationMode = GenerationMode.Parse,
    ): Long = jobs.start(
        novelId = novel.novelId,
        mode = mode.wire,
        requestedIds = novel.chapterIds,
    ).id

    private fun input(
        novel: Novel,
        jobId: Long,
        mode: GenerationMode = GenerationMode.Parse,
    ): Data = GenerationWorker.inputData(
        novelId = novel.novelId,
        jobId = jobId,
        chapterIds = novel.chapterIds.toLongArray(),
        mode = mode,
        force = false,
    )

    private fun buildWorker(input: Data, runAttemptCount: Int = 0): GenerationWorker =
        TestListenableWorkerBuilder<GenerationWorker>(app)
            .setInputData(input)
            .setRunAttemptCount(runAttemptCount)
            .build()

    private fun installParseExecutor(analyze: AnalyzeChapterCall) {
        GenerationGraph.executorFactory = {
            GenerationExecutor(
                chapters = chapters,
                jobs = jobs,
                analyzeChapter = analyze,
                renderChapter = { _, _, _ -> error("parse 模式不应调用渲染") },
            )
        }
    }

    private fun statsOf(job: JobEntity): JsonObject =
        Json.parseToJsonElement(job.statsJson).jsonObject
}
