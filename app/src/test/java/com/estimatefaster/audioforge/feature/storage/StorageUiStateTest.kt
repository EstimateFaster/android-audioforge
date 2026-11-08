package com.estimatefaster.audioforge.feature.storage

import com.estimatefaster.audioforge.core.storage.CleanupFailure
import com.estimatefaster.audioforge.core.storage.CleanupReport
import com.estimatefaster.audioforge.core.storage.CleanedFile
import com.estimatefaster.audioforge.core.storage.StorageEntry
import com.estimatefaster.audioforge.core.storage.StorageFailure
import com.estimatefaster.audioforge.core.storage.StorageKind
import com.estimatefaster.audioforge.core.storage.StorageReport
import com.estimatefaster.audioforge.core.storage.StorageStats
import com.estimatefaster.audioforge.data.db.ProjectSummary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-09：存储页 UI 状态的纯 JVM 测试。
 *
 * 不依赖 Android/Robolectric、不碰真实文件系统：字节格式化、分区/项目映射、
 * 保留期边界、清理确认/成功/失败/并发去重、空态/加载/错误全部由纯函数与 fake
 * [StorageGateway] 覆盖。生产适配器对 `StorageMaintenance`/`ProjectRepository`
 * 的封装只做映射，不在此重复测试文件系统行为。
 */
class StorageUiStateTest {

    // ------------------------------------------------------------------
    // 字节格式化
    // ------------------------------------------------------------------

    @Test
    fun byte_format_uses_binary_units_with_one_decimal() {
        assertEquals("0 B", formatByteSize(0L))
        assertEquals("0 B", formatByteSize(-1L))
        assertEquals("512 B", formatByteSize(512L))
        assertEquals("1.0 KB", formatByteSize(1024L))
        assertEquals("1.5 KB", formatByteSize(1536L))
        assertEquals("1.0 MB", formatByteSize(1024L * 1024L))
        assertEquals("5.5 MB", formatByteSize(5L * 1024L * 1024L + 512L * 1024L))
        assertEquals("2.0 GB", formatByteSize(2L * 1024L * 1024L * 1024L))
    }

    // ------------------------------------------------------------------
    // 分区 / 项目映射
    // ------------------------------------------------------------------

    @Test
    fun overview_maps_areas_and_known_projects_in_repository_order() {
        val stats = StorageStats(
            totalBytes = 3_000L,
            audioBytes = 2_000L,
            importsBytes = 700L,
            exportsBytes = 300L,
            perNovelBytes = mapOf(1L to 2_000L, 9L to 100L),
            fileCount = 5,
            usableBytes = 10_000L,
        )
        val report = StorageReport(
            referenced = emptyList(),
            orphans = listOf(
                entry("exports/分享.txt", 100L, StorageKind.EXPORT),
                entry("audio/9/old.m4a", 100L, StorageKind.AUDIO, novelId = 9L),
            ),
            expired = listOf(
                entry("audio/1/chapter_001.m4a", 500L, StorageKind.AUDIO, novelId = 1L),
            ),
            totalBytes = 3_000L,
            referencedBytes = 2_000L,
            orphanBytes = 200L,
            expiredBytes = 500L,
            scannedAt = 1_234L,
            retentionDays = 30,
            errors = listOf(StorageFailure("imports/x.txt", "无法读取目录")),
        )
        val projects = listOf(
            summary(id = 1L, title = "长夜将明", chapterCount = 12),
            summary(id = 2L, title = "空项目", chapterCount = 0),
        )

        val overview = buildStorageOverview(stats = stats, report = report, projects = projects)

        assertEquals(listOf(StorageArea.AUDIO, StorageArea.IMPORTS, StorageArea.EXPORTS), overview.areas.map { it.area })
        assertEquals(2_000L, overview.areas[0].bytes)
        assertEquals(700L, overview.areas[1].bytes)
        assertEquals(300L, overview.areas[2].bytes)
        assertEquals(2_000L, overview.bytesFor(StorageArea.AUDIO))
        assertEquals(300L, overview.bytesFor(StorageArea.EXPORTS))
        assertEquals(0.666f, overview.shareOf(StorageArea.AUDIO), 0.001f)
        assertEquals(0f, overview.fractionOf(-10L), 0f)
        assertEquals("超过总占用时收敛到 1", 1f, overview.fractionOf(99_999L), 0f)

        assertEquals(3, overview.projects.size)
        assertEquals(1L, overview.projects[0].novelId)
        assertEquals("长夜将明", overview.projects[0].title)
        assertEquals(2_000L, overview.projects[0].bytes)
        assertEquals(12, overview.projects[0].chapterCount)
        assertEquals(0L, overview.projects[1].bytes)
        assertEquals(0, overview.projects[1].chapterCount)

        // audio/9 的残留：项目已不在列表里，仍以「未关联」保留。
        assertEquals(9L, overview.projects[2].novelId)
        assertNull(overview.projects[2].title)
        assertEquals(100L, overview.projects[2].bytes)

        assertEquals(2, overview.orphanCount)
        assertEquals(200L, overview.orphanBytes)
        assertEquals(1, overview.expiredCount)
        assertEquals(500L, overview.expiredBytes)
        assertEquals(1, overview.scanFailureCount)
        assertEquals(5, overview.fileCount)
        assertEquals(10_000L, overview.usableBytes)
        assertEquals(1_234L, overview.scannedAt)
        assertFalse(overview.isEmpty)
        assertTrue(overview.hasOrphans)
        assertTrue(overview.hasExpired)
    }

    @Test
    fun empty_overview_has_zero_areas_and_no_projects() {
        val overview = buildStorageOverview(
            stats = StorageStats(
                totalBytes = 0L,
                audioBytes = 0L,
                importsBytes = 0L,
                exportsBytes = 0L,
                perNovelBytes = emptyMap(),
                fileCount = 0,
                usableBytes = 12_000L,
            ),
            report = StorageReport(
                referenced = emptyList(),
                orphans = emptyList(),
                expired = emptyList(),
                totalBytes = 0L,
                referencedBytes = 0L,
                orphanBytes = 0L,
                expiredBytes = 0L,
                scannedAt = 7L,
                retentionDays = 30,
            ),
            projects = emptyList(),
        )

        assertTrue(overview.isEmpty)
        assertEquals(0f, overview.shareOf(StorageArea.AUDIO), 0f)
        assertTrue(overview.areas.all { it.bytes == 0L })
        assertFalse(overview.hasOrphans)
        assertFalse(overview.hasExpired)
    }

    // ------------------------------------------------------------------
    // 保留期边界 / 非法输入
    // ------------------------------------------------------------------

    @Test
    fun retention_accepts_lower_bound_default_and_upper_bound() {
        assertEquals(RetentionValidation(7, null), validateRetention("7"))
        assertEquals(RetentionValidation(30, null), validateRetention(" 30 "))
        assertEquals(RetentionValidation(365, null), validateRetention("365"))
    }

    @Test
    fun retention_rejects_out_of_range_values() {
        assertEquals(RetentionError.OutOfRange, validateRetention("6").error)
        assertEquals(RetentionError.OutOfRange, validateRetention("0").error)
        assertEquals(RetentionError.OutOfRange, validateRetention("-1").error)
        assertEquals(RetentionError.OutOfRange, validateRetention("366").error)
        assertNull(validateRetention("366").days)
    }

    @Test
    fun retention_rejects_blank_and_non_integer_input() {
        assertEquals(RetentionError.Missing, validateRetention("").error)
        assertEquals(RetentionError.Missing, validateRetention("   ").error)
        assertEquals(RetentionError.NotAnInteger, validateRetention("abc").error)
        assertEquals(RetentionError.NotAnInteger, validateRetention("12.5").error)
        assertNull(validateRetention("abc").days)
    }

    @Test
    fun invalid_retention_input_keeps_last_valid_days_and_blocks_expired_cleanup() {
        val loaded = loadedState()
        val valid = loaded.withRetentionInput("365")
        assertEquals(365, valid.retentionDays)
        assertNull(valid.retentionError)

        val invalid = valid.withRetentionInput("5")
        assertEquals("非法输入不改变已生效的天数", 365, invalid.retentionDays)
        assertEquals(RetentionError.OutOfRange, invalid.retentionError)
        assertFalse(invalid.canCleanExpired)
        assertEquals(invalid, invalid.requestCleanup(StorageCleanupKind.EXPIRED))
        // 孤儿清理与保留期无关，仍可确认。
        assertEquals(
            StorageCleanupKind.ORPHANS,
            invalid.requestCleanup(StorageCleanupKind.ORPHANS).pendingCleanup,
        )
    }

    @Test
    fun slider_values_are_clamped_to_7_365() {
        val state = loadedState().withRetentionDays(1)
        assertEquals(MIN_RETENTION_DAYS, state.retentionDays)
        assertEquals(MIN_RETENTION_DAYS.toString(), state.retentionInput)
        assertNull(state.retentionError)

        val high = loadedState().withRetentionDays(999)
        assertEquals(MAX_RETENTION_DAYS, high.retentionDays)
        assertEquals(MAX_RETENTION_DAYS.toString(), high.retentionInput)
    }

    // ------------------------------------------------------------------
    // 清理确认 / 成功 / 失败 / 并发去重
    // ------------------------------------------------------------------

    @Test
    fun request_cleanup_opens_confirmation_without_starting() {
        val pending = loadedState().requestCleanup(StorageCleanupKind.ORPHANS)

        assertEquals(StorageCleanupKind.ORPHANS, pending.pendingCleanup)
        assertNull("尚未确认，不应开始清理", pending.cleaning)
        assertFalse(pending.isBusy)
        assertEquals(pending, pending.requestCleanup(StorageCleanupKind.EXPIRED))

        val cancelled = pending.cancelCleanup()
        assertNull(cancelled.pendingCleanup)
    }

    @Test
    fun begin_cleanup_moves_from_confirmation_to_progress() {
        val started = loadedState()
            .requestCleanup(StorageCleanupKind.ORPHANS)
            .beginCleanup(StorageCleanupKind.ORPHANS)

        requireNotNull(started)
        assertEquals(StorageCleanupKind.ORPHANS, started.cleaning)
        assertNull(started.pendingCleanup)
        assertEquals(StorageCleanupKind.ORPHANS, started.lastCleanupKind)
        assertTrue(started.isBusy)
        assertFalse(started.canStartCleanup)
    }

    @Test
    fun begin_cleanup_is_rejected_while_another_cleanup_is_running() {
        val running = loadedState().beginCleanup(StorageCleanupKind.ORPHANS)
        requireNotNull(running)

        assertNull("第二次清理必须被去重", running.beginCleanup(StorageCleanupKind.EXPIRED))
        assertEquals(running, running.requestCleanup(StorageCleanupKind.EXPIRED))
    }

    @Test
    fun cleanup_success_shows_result_and_clears_progress() {
        val started = loadedState().beginCleanup(StorageCleanupKind.ORPHANS)
        requireNotNull(started)
        val result = StorageCleanupResult(
            kind = StorageCleanupKind.ORPHANS,
            freedBytes = 1_536L,
            deletedCount = 3,
            failureCount = 0,
            skippedReferencedCount = 2,
            skippedTooRecentCount = 1,
            skippedMissingCount = 0,
        )

        val done = started.cleanupSucceeded(result)

        assertEquals(result, done.lastCleanup)
        assertNull(done.cleaning)
        assertNull(done.cleanupError)
        assertTrue(done.hasResult)
        assertFalse(done.canRetryCleanup)
        assertEquals(StorageCleanupKind.ORPHANS, done.lastCleanupKind)
    }

    @Test
    fun partial_cleanup_failure_keeps_result_and_allows_retry() {
        val started = loadedState().beginCleanup(StorageCleanupKind.EXPIRED)
        requireNotNull(started)
        val partial = StorageCleanupResult(
            kind = StorageCleanupKind.EXPIRED,
            freedBytes = 0L,
            deletedCount = 0,
            failureCount = 2,
            skippedReferencedCount = 9,
            skippedTooRecentCount = 0,
            skippedMissingCount = 0,
        )

        val done = started.cleanupSucceeded(partial)

        assertTrue(done.hasResult)
        assertTrue(done.canRetryCleanup)
        assertEquals(partial, done.lastCleanup)
        val retried = done.beginCleanup(StorageCleanupKind.EXPIRED)
        requireNotNull(retried)
        assertEquals(StorageCleanupKind.EXPIRED, retried.cleaning)
    }

    @Test
    fun cleanup_exception_is_retryable_with_the_same_action() {
        val started = loadedState().beginCleanup(StorageCleanupKind.ORPHANS)
        requireNotNull(started)

        val failed = started.cleanupFailed(StorageCleanupKind.ORPHANS, "磁盘忙")

        assertNull(failed.lastCleanup)
        assertEquals("磁盘忙", failed.cleanupError)
        assertTrue(failed.canRetryCleanup)
        assertEquals(StorageCleanupKind.ORPHANS, failed.lastCleanupKind)
        assertFalse(failed.isBusy)

        val retried = failed.beginCleanup(StorageCleanupKind.ORPHANS)
        requireNotNull(retried)
        assertNull(retried.cleanupError)
        assertEquals(StorageCleanupKind.ORPHANS, retried.cleaning)
    }

    @Test
    fun dismissing_result_removes_retry_entry_point() {
        val started = loadedState().beginCleanup(StorageCleanupKind.ORPHANS)
        requireNotNull(started)
        val failed = started.cleanupFailed(StorageCleanupKind.ORPHANS, "io")

        val dismissed = failed.dismissCleanupResult()

        assertFalse(dismissed.hasResult)
        assertFalse(dismissed.canRetryCleanup)
        assertNull(dismissed.lastCleanupKind)
    }

    @Test
    fun cleanup_without_overview_is_rejected() {
        val loading = StorageUiState(loading = true)
        assertNull(loading.beginCleanup(StorageCleanupKind.ORPHANS))
        assertFalse(loading.canStartCleanup)

        val errored = StorageUiState(loading = false, loadError = "db")
        assertNull(errored.beginCleanup(StorageCleanupKind.ORPHANS))
    }

    // ------------------------------------------------------------------
    // 空态 / 加载 / 错误
    // ------------------------------------------------------------------

    @Test
    fun initial_state_is_loading_not_empty() {
        val initial = StorageUiState()

        assertTrue(initial.isLoading)
        assertFalse(initial.isEmpty)
        assertFalse(initial.isReady)
        assertFalse(initial.canCleanOrphans)
        assertFalse(initial.canCleanExpired)
    }

    @Test
    fun loaded_state_is_ready_with_overview() {
        val loaded = loadedState()

        assertFalse(loaded.isLoading)
        assertNull(loaded.loadError)
        assertTrue(loaded.isReady)
        assertFalse(loaded.isEmpty)
        assertTrue(loaded.canCleanOrphans)
        assertTrue(loaded.canCleanExpired)
    }

    @Test
    fun empty_overview_is_empty_but_ready() {
        val empty = StorageUiState(
            loading = false,
            overview = emptyOverview(),
            retentionInput = "30",
            retentionDays = 30,
        )

        assertTrue(empty.isEmpty)
        assertTrue(empty.isReady)
        assertFalse(empty.canCleanOrphans)
        assertFalse(empty.canCleanExpired)
    }

    @Test
    fun load_error_is_not_empty_and_keeps_previous_overview() {
        val loaded = loadedState()
        val errored = loaded.withLoadError("database is locked")

        assertFalse(errored.isLoading)
        assertEquals("database is locked", errored.loadError)
        assertFalse(errored.isEmpty)
        assertEquals(loaded.overview, errored.overview)
        assertFalse(errored.canStartCleanup)

        val retrying = errored.markLoading()
        assertTrue(retrying.isLoading)
        assertNull(retrying.loadError)
    }

    // ------------------------------------------------------------------
    // fake gateway：三种动作的分派与保留期透传
    // ------------------------------------------------------------------

    @Test
    fun fake_gateway_receives_retention_and_dispatches_cleanup_kind() = runTest {
        val overview = sampleOverview()
        val orphanResult = StorageCleanupResult(
            kind = StorageCleanupKind.ORPHANS,
            freedBytes = 1L,
            deletedCount = 1,
            failureCount = 0,
            skippedReferencedCount = 0,
            skippedTooRecentCount = 0,
            skippedMissingCount = 0,
        )
        val expiredResult = orphanResult.copy(kind = StorageCleanupKind.EXPIRED, freedBytes = 2L)
        val gateway = FakeStorageGateway(overview, orphanResult, expiredResult)

        assertSame(overview, gateway.scan(45))
        assertEquals(45, gateway.lastRetentionDays)
        assertEquals(1, gateway.scanCalls)

        assertSame(orphanResult, runStorageCleanup(gateway, StorageCleanupKind.ORPHANS, 45))
        assertEquals(1, gateway.orphanCalls)
        assertEquals(0, gateway.expiredCalls)

        assertSame(expiredResult, runStorageCleanup(gateway, StorageCleanupKind.EXPIRED, 60))
        assertEquals(1, gateway.expiredCalls)
        assertEquals(60, gateway.lastRetentionDays)
    }

    @Test
    fun cleanup_report_maps_to_ui_counts() {
        val report = CleanupReport(
            deleted = listOf(
                CleanedFile("audio/1/chapter_001.m4a", 1_024L),
                CleanedFile("exports/分享.m4a", 512L),
            ),
            skippedReferenced = listOf("audio/1/chapter_002.m4a"),
            skippedTooRecent = emptyList(),
            skippedMissing = listOf("audio/1/gone.m4a"),
            failures = listOf(CleanupFailure("audio/1/locked.m4a", "无权限")),
        )

        val ui = report.toStorageCleanupResult(StorageCleanupKind.ORPHANS)

        assertEquals(StorageCleanupKind.ORPHANS, ui.kind)
        assertEquals(1_536L, ui.freedBytes)
        assertEquals(2, ui.deletedCount)
        assertEquals(1, ui.failureCount)
        assertEquals(1, ui.skippedReferencedCount)
        assertEquals(0, ui.skippedTooRecentCount)
        assertEquals(1, ui.skippedMissingCount)
        assertTrue(ui.hasFailures)
        assertFalse(ui.deletedNothing)
    }

    // ------------------------------------------------------------------
    // 测试夹具
    // ------------------------------------------------------------------

    private fun loadedState(): StorageUiState = StorageUiState(
        loading = false,
        overview = sampleOverview(),
        retentionInput = DEFAULT_RETENTION_DAYS.toString(),
        retentionDays = DEFAULT_RETENTION_DAYS,
    )

    private fun sampleOverview(): StorageOverview = StorageOverview(
        totalBytes = 4_000L,
        usableBytes = 8_000L,
        audioBytes = 3_000L,
        importsBytes = 600L,
        exportsBytes = 400L,
        fileCount = 10,
        projects = listOf(
            StorageProjectUsage(
                novelId = 1L,
                title = "长夜将明",
                bytes = 3_000L,
                chapterCount = 12,
            ),
        ),
        orphanCount = 3,
        orphanBytes = 300L,
        expiredCount = 2,
        expiredBytes = 200L,
        scanFailureCount = 0,
        scannedAt = 2_000L,
    )

    private fun emptyOverview(): StorageOverview = StorageOverview(
        totalBytes = 0L,
        usableBytes = 8_000L,
        audioBytes = 0L,
        importsBytes = 0L,
        exportsBytes = 0L,
        fileCount = 0,
        projects = emptyList(),
        orphanCount = 0,
        orphanBytes = 0L,
        expiredCount = 0,
        expiredBytes = 0L,
        scanFailureCount = 0,
        scannedAt = 2_000L,
    )

    private fun entry(
        relative: String,
        bytes: Long,
        kind: StorageKind,
        novelId: Long? = null,
    ): StorageEntry = StorageEntry(
        relative = relative,
        bytes = bytes,
        kind = kind,
        novelId = novelId,
        modifiedAt = 1L,
    )

    private fun summary(
        id: Long,
        title: String,
        chapterCount: Int,
    ): ProjectSummary = ProjectSummary(
        id = id,
        title = title,
        sourceName = "$title.txt",
        charCount = 10_000,
        createdAt = 1L,
        lastOpenedAt = null,
        chapterCount = chapterCount,
        chapterDoneCount = 0,
        segmentCount = 0,
        segmentDoneCount = 0,
    )

    private class FakeStorageGateway(
        private val overview: StorageOverview,
        private val orphanResult: StorageCleanupResult,
        private val expiredResult: StorageCleanupResult,
    ) : StorageGateway {

        var scanCalls: Int = 0
        var orphanCalls: Int = 0
        var expiredCalls: Int = 0
        var lastRetentionDays: Int = -1

        override suspend fun scan(retentionDays: Int): StorageOverview {
            scanCalls += 1
            lastRetentionDays = retentionDays
            return overview
        }

        override suspend fun cleanOrphans(): StorageCleanupResult {
            orphanCalls += 1
            return orphanResult
        }

        override suspend fun cleanExpired(retentionDays: Int): StorageCleanupResult {
            expiredCalls += 1
            lastRetentionDays = retentionDays
            return expiredResult
        }
    }
}
