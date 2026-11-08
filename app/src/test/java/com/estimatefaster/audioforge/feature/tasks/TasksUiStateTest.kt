package com.estimatefaster.audioforge.feature.tasks

import com.estimatefaster.audioforge.data.db.JobEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4-08：任务页 UI 状态的纯 JVM 测试。
 *
 * 不依赖 Android/Robolectric，也不启动协程：statsJson 容错、进度/ETA/耗时格式、
 * 当前任务/队列/历史的排序、空态、错误摘要与模式/状态映射全部是纯函数。
 */
class TasksUiStateTest {

    // ------------------------------------------------------------------
    // statsJson 解析
    // ------------------------------------------------------------------

    @Test
    fun stats_parse_reads_all_counters() {
        val stats = TaskStats.parse(
            """{"chaptersDone":2,"chaptersTotal":8,"segDone":42,"segTotal":150,"ttsOk":40,"ttsFailed":2,"retries":3,"startedAt":1760000000000}""",
        )

        assertEquals(8, stats.chaptersTotal)
        assertEquals(2, stats.chaptersDone)
        assertEquals(150, stats.segTotal)
        assertEquals(42, stats.segDone)
        assertEquals(40, stats.ttsOk)
        assertEquals(2, stats.ttsFailed)
        assertTrue(stats.hasTtsCounts)
    }

    @Test
    fun stats_parse_missing_keys_and_blank_payload_yield_zero() {
        val onlyChapters = TaskStats.parse("""{"chaptersTotal":5}""")

        assertEquals(5, onlyChapters.chaptersTotal)
        assertEquals(0, onlyChapters.chaptersDone)
        assertEquals(0, onlyChapters.segTotal)
        assertEquals(0, onlyChapters.segDone)
        assertEquals(0, onlyChapters.ttsOk)
        assertEquals(0, onlyChapters.ttsFailed)
        assertFalse(onlyChapters.hasTtsCounts)

        assertEquals(TaskStats.EMPTY, TaskStats.parse(null))
        assertEquals(TaskStats.EMPTY, TaskStats.parse(""))
        assertEquals(TaskStats.EMPTY, TaskStats.parse("   "))
        assertEquals(TaskStats.EMPTY, TaskStats.parse("{}"))
    }

    @Test
    fun stats_parse_tolerates_dirty_values() {
        val stats = TaskStats.parse(
            """{"chaptersTotal":"abc","chaptersDone":-1,"segTotal":-5,"segDone":2.9,"ttsOk":true,"ttsFailed":null}""",
        )

        assertEquals("非数字按 0", 0, stats.chaptersTotal)
        assertEquals("负数按 0", 0, stats.chaptersDone)
        assertEquals("负总数按 0", 0, stats.segTotal)
        assertEquals("小数字符串截断为整数", 2, stats.segDone)
        assertEquals("布尔按 0", 0, stats.ttsOk)
        assertEquals("null 按 0", 0, stats.ttsFailed)
    }

    @Test
    fun stats_parse_non_object_payload_yields_zero() {
        assertEquals(TaskStats.EMPTY, TaskStats.parse("[1,2,3]"))
        assertEquals(TaskStats.EMPTY, TaskStats.parse("42"))
        assertEquals(TaskStats.EMPTY, TaskStats.parse("not json"))
        assertEquals(TaskStats.EMPTY, TaskStats.parse("""{"chaptersTotal":{"nested":1}}"""))
    }

    // ------------------------------------------------------------------
    // 进度百分比
    // ------------------------------------------------------------------

    @Test
    fun progress_prefers_segments_over_chapters() {
        val stats = TaskStats(
            chaptersTotal = 10,
            chaptersDone = 5,
            segTotal = 100,
            segDone = 25,
        )

        assertEquals(0.5f, stats.chapterProgress, 0.0001f)
        assertEquals(0.25f, stats.segmentProgress, 0.0001f)
        assertEquals(0.25f, stats.progress, 0.0001f)
        assertEquals(25, stats.percent)
    }

    @Test
    fun progress_falls_back_to_chapters_and_handles_empty() {
        val stats = TaskStats(chaptersTotal = 4, chaptersDone = 2)

        assertEquals(0.5f, stats.progress, 0.0001f)
        assertEquals(50, stats.percent)
        assertEquals(0f, TaskStats.EMPTY.progress, 0.0001f)
        assertEquals(0, TaskStats.EMPTY.percent)
    }

    @Test
    fun progress_clamps_dirty_counts_to_zero_or_one() {
        assertEquals(1f, TaskStats(segTotal = 4, segDone = 9).progress, 0.0001f)
        assertEquals(100, TaskStats(segTotal = 4, segDone = 9).percent)
        assertEquals(0f, TaskStats(segTotal = 0, segDone = 9).progress, 0.0001f)
    }

    @Test
    fun percent_rounds_to_nearest_integer() {
        assertEquals(33, TaskStats(segTotal = 3, segDone = 1).percent)
        assertEquals(67, TaskStats(segTotal = 3, segDone = 2).percent)
        assertEquals(100, TaskStats(segTotal = 3, segDone = 3).percent)
    }

    // ------------------------------------------------------------------
    // ETA / 耗时格式
    // ------------------------------------------------------------------

    @Test
    fun estimate_remaining_returns_null_when_not_estimable() {
        assertNull(estimateRemainingMs(startedAt = 0, now = 1_000, progress = 0f))
        assertNull(estimateRemainingMs(startedAt = 0, now = 1_000, progress = 1f))
        assertNull(estimateRemainingMs(startedAt = 0, now = 1_000, progress = Float.NaN))
        assertNull(estimateRemainingMs(startedAt = 1_000, now = 1_000, progress = 0.5f))
        assertNull(estimateRemainingMs(startedAt = 2_000, now = 1_000, progress = 0.5f))
    }

    @Test
    fun estimate_remaining_scales_elapsed_time_by_done_ratio() {
        // 已跑 100s、完成 25% → 剩余 300s。
        assertEquals(300_000L, estimateRemainingMs(startedAt = 0, now = 100_000, progress = 0.25f))
        assertEquals(60_000L, estimateRemainingMs(startedAt = 0, now = 60_000, progress = 0.5f))
    }

    @Test
    fun estimate_remaining_is_capped_for_absurdly_low_progress() {
        assertEquals(
            MAX_ESTIMATED_REMAINING_MS,
            estimateRemainingMs(startedAt = 0, now = 100_000, progress = 0.0001f),
        )
    }

    @Test
    fun duration_formats_as_clock_text() {
        assertNull(formatTaskDuration(null))
        assertNull(formatTaskDuration(-1L))
        assertEquals("0:00", formatTaskDuration(0L))
        assertEquals("0:59", formatTaskDuration(59_999L))
        assertEquals("1:00", formatTaskDuration(60_000L))
        assertEquals("1:30", formatTaskDuration(90_000L))
        assertEquals("1:01:05", formatTaskDuration(3_665_000L))
    }

    // ------------------------------------------------------------------
    // 当前任务 / 队列 / 历史排序 + 空态
    // ------------------------------------------------------------------

    @Test
    fun build_orders_current_queue_and_history() {
        val state = buildTasksUiState(
            snapshots = listOf(
                snapshot(1L, "A", active = job(11L, 1L, "running", startedAt = 300L)),
                snapshot(2L, "B", active = job(12L, 2L, "running", startedAt = 200L)),
                snapshot(3L, "C", active = job(13L, 3L, "queued", startedAt = 100L)),
                snapshot(4L, "D", active = job(14L, 4L, "queued", startedAt = 50L)),
                snapshot(5L, "E", lastFinished = job(15L, 5L, "done", finishedAt = 900L)),
                snapshot(6L, "F", lastFinished = job(16L, 6L, "error", finishedAt = 1_000L)),
                snapshot(7L, "G", lastFinished = job(17L, 7L, "cancelled", finishedAt = 800L)),
            ),
            now = 400L,
        )

        assertEquals("running 里 startedAt 最新的作为当前任务", 1L, state.current?.novelId)
        assertEquals(TaskStatusUi.Running, state.current?.status)
        assertEquals(
            "其余 running 排在 queue 最前；queued 按 startedAt 升序（先排队先执行）",
            listOf(2L, 4L, 3L),
            state.queued.map { it.novelId },
        )
        assertEquals("历史按 finishedAt 倒序", listOf(6L, 5L, 7L), state.recent.map { it.novelId })
        assertTrue(state.hasRunning)
        assertTrue(state.hasActive)
        assertTrue(state.hasHistory)
        assertFalse(state.isEmpty)
    }

    @Test
    fun build_keeps_active_and_last_finished_for_the_same_novel() {
        val state = buildTasksUiState(
            snapshots = listOf(
                snapshot(
                    novelId = 7L,
                    title = "长夜将明",
                    active = job(70L, 7L, "running", startedAt = 10L),
                    lastFinished = job(69L, 7L, "done", finishedAt = 5L),
                ),
            ),
            now = 20L,
        )

        assertEquals(70L, state.current?.jobId)
        assertEquals(69L, state.recent.single().jobId)
    }

    @Test
    fun build_filters_non_active_or_non_terminal_dirty_rows() {
        val state = buildTasksUiState(
            snapshots = listOf(
                // active 字段里是终态：不当当前任务。
                snapshot(1L, "A", active = job(10L, 1L, "done", finishedAt = 10L)),
                // lastFinished 字段里是活跃态：不进历史。
                snapshot(2L, "B", lastFinished = job(11L, 2L, "running")),
            ),
            now = 20L,
        )

        assertNull(state.current)
        assertTrue(state.queued.isEmpty())
        assertTrue(state.recent.isEmpty())
        assertTrue(state.isEmpty)
    }

    @Test
    fun build_empty_snapshots_is_empty_but_loading_is_not() {
        val empty = buildTasksUiState(emptyList(), now = 1L)

        assertTrue(empty.isEmpty)
        assertNull(empty.current)
        assertTrue(empty.queued.isEmpty())
        assertTrue(empty.recent.isEmpty())
        assertFalse(empty.hasRunning)
        assertTrue(TasksUiState(loading = false).isEmpty)
        assertFalse("加载中不能显示空态", TasksUiState().isEmpty)
        assertFalse(
            "错误态不能显示空态",
            empty.copy(loadError = "database is locked").isEmpty,
        )
    }

    @Test
    fun history_respects_limit_and_zero_disables_it() {
        val snapshots = (1L..5L).map { index ->
            snapshot(
                novelId = index,
                title = "Book $index",
                lastFinished = job(
                    id = 100L + index,
                    novelId = index,
                    status = "done",
                    finishedAt = index * 10L,
                ),
            )
        }

        assertEquals(
            listOf(5L, 4L),
            buildTasksUiState(snapshots, now = 100L, historyLimit = 2).recent.map { it.novelId },
        )
        assertTrue(buildTasksUiState(snapshots, now = 100L, historyLimit = 0).recent.isEmpty())
    }

    @Test
    fun history_computes_duration_and_progress() {
        val state = buildTasksUiState(
            snapshots = listOf(
                snapshot(
                    novelId = 1L,
                    title = "A",
                    lastFinished = job(
                        id = 10L,
                        novelId = 1L,
                        status = "done",
                        startedAt = 1_000L,
                        finishedAt = 91_000L,
                        stats = """{"chaptersDone":3,"chaptersTotal":4}""",
                    ),
                ),
            ),
            now = 100_000L,
        )

        val item = state.recent.single()
        assertEquals(90_000L, item.durationMs)
        assertEquals(91_000L, item.finishedAt)
        assertEquals(75, item.percent)
        assertNull(item.errorSummary)
    }

    // ------------------------------------------------------------------
    // 错误摘要
    // ------------------------------------------------------------------

    @Test
    fun error_summary_collapses_whitespace_and_keeps_short_message() {
        assertEquals("boom now", summarizeTaskError("  boom \n  now  "))
        assertEquals(null, summarizeTaskError("   "))
        assertEquals(null, summarizeTaskError(null))
    }

    @Test
    fun error_summary_truncates_long_message_with_ellipsis() {
        val message = "Fish Audio 429\n" + "请求过于频繁 ".repeat(60)
        val summary = summarizeTaskError(message)

        assertNotNull(summary)
        requireNotNull(summary)
        assertTrue("总长度不超过上限", summary.length <= DEFAULT_ERROR_SUMMARY_LIMIT)
        assertTrue("截断后带省略号", summary.endsWith("…"))
        assertFalse("换行被折叠", summary.contains("\n"))
        assertTrue("保留开头", summary.startsWith("Fish Audio 429 请求过于频繁"))
    }

    @Test
    fun history_uses_truncated_error_summary() {
        val state = buildTasksUiState(
            snapshots = listOf(
                snapshot(
                    novelId = 1L,
                    title = "A",
                    lastFinished = job(
                        id = 10L,
                        novelId = 1L,
                        status = "error",
                        finishedAt = 5L,
                        error = "x".repeat(300),
                    ),
                ),
            ),
            now = 10L,
        )

        val summary = state.recent.single().errorSummary
        assertNotNull(summary)
        assertTrue(requireNotNull(summary).length <= DEFAULT_ERROR_SUMMARY_LIMIT)
    }

    // ------------------------------------------------------------------
    // 任务卡映射：模式 / 状态 / 停止态
    // ------------------------------------------------------------------

    @Test
    fun task_card_maps_mode_status_and_progress() {
        val state = buildTasksUiState(
            snapshots = listOf(
                snapshot(
                    novelId = 7L,
                    title = "长夜将明",
                    active = job(
                        id = 70L,
                        novelId = 7L,
                        status = "running",
                        mode = "render",
                        startedAt = 1_000L,
                        currentTitle = "第 2 章 雨夜来客",
                        stats = """{"chaptersDone":1,"chaptersTotal":4,"segDone":30,"segTotal":60,"ttsOk":29,"ttsFailed":1}""",
                    ),
                ),
            ),
            now = 61_000L,
        )

        val card = state.current
        assertNotNull(card)
        requireNotNull(card)
        assertEquals(TaskModeUi.Render, card.mode)
        assertEquals("render", card.mode.wire)
        assertEquals(TaskStatusUi.Running, card.status)
        assertEquals(30, card.stats.segDone)
        assertEquals(0.5f, card.progress, 0.0001f)
        assertEquals(50, card.percent)
        assertEquals(60_000L, card.elapsedMs)
        assertEquals("完成 50%、已跑 60s → 剩余约 60s", 60_000L, card.etaMs)
        assertEquals("第 2 章 雨夜来客", card.currentTitle)
        assertFalse(card.isStopping)
    }

    @Test
    fun task_card_maps_unknown_mode_and_stop_requested() {
        val state = buildTasksUiState(
            snapshots = listOf(
                snapshot(
                    novelId = 1L,
                    title = "A",
                    active = job(
                        id = 10L,
                        novelId = 1L,
                        status = "running",
                        mode = "weird",
                        startedAt = 0L,
                        stopRequested = true,
                    ),
                ),
            ),
            now = 10_000L,
        )

        val card = requireNotNull(state.current)
        assertEquals(TaskModeUi.Unknown, card.mode)
        assertEquals("", card.mode.wire)
        assertTrue(card.isStopping)
        assertNull("已请求停止不再估算 ETA", card.etaMs)
    }

    @Test
    fun task_card_for_queued_job_has_no_eta() {
        val state = buildTasksUiState(
            snapshots = listOf(
                snapshot(
                    novelId = 1L,
                    title = "A",
                    active = job(10L, 1L, "queued", startedAt = 0L),
                ),
            ),
            now = 10_000L,
        )

        assertNull("排队中没有当前任务", state.current)
        val card = state.queued.single()
        assertEquals(TaskStatusUi.Queued, card.status)
        assertNull(card.etaMs)
        assertNull(state.taskFor(1L)?.etaMs)
        assertEquals(10L, state.taskFor(1L)?.jobId)
    }

    @Test
    fun status_and_mode_parsing_normalizes_case_and_blank() {
        assertEquals(TaskStatusUi.Running, TaskStatusUi.from(" RUNNING "))
        assertEquals(TaskStatusUi.Error, TaskStatusUi.from("error"))
        assertEquals(TaskStatusUi.Pending, TaskStatusUi.from("pending"))
        assertEquals(TaskStatusUi.Unknown, TaskStatusUi.from("weird"))
        assertEquals(TaskStatusUi.Unknown, TaskStatusUi.from(null))
        assertEquals(TaskStatusUi.Unknown, TaskStatusUi.from("   "))
        assertTrue(TaskStatusUi.Running.isActive)
        assertTrue(TaskStatusUi.Error.isTerminal)

        assertEquals(TaskModeUi.Full, TaskModeUi.from(" FULL "))
        assertEquals(TaskModeUi.Parse, TaskModeUi.from("parse"))
        assertEquals(TaskModeUi.Unknown, TaskModeUi.from("voice"))
        assertEquals(TaskModeUi.Unknown, TaskModeUi.from(null))
    }

    // ------------------------------------------------------------------
    // 测试夹具
    // ------------------------------------------------------------------

    private fun snapshot(
        novelId: Long,
        title: String,
        active: JobEntity? = null,
        lastFinished: JobEntity? = null,
    ): TaskJobSnapshot = TaskJobSnapshot(
        novelId = novelId,
        novelTitle = title,
        active = active,
        lastFinished = lastFinished,
    )

    private fun job(
        id: Long,
        novelId: Long,
        status: String,
        startedAt: Long = 0L,
        mode: String = "full",
        finishedAt: Long? = null,
        stats: String = "{}",
        currentTitle: String = "",
        error: String? = null,
        stopRequested: Boolean = false,
    ): JobEntity = JobEntity(
        id = id,
        novelId = novelId,
        mode = mode,
        status = status,
        requestedIdsJson = "[]",
        currentTitle = currentTitle,
        startedAt = startedAt,
        finishedAt = finishedAt,
        lastError = error,
        statsJson = stats,
        stopRequested = stopRequested,
    )
}
