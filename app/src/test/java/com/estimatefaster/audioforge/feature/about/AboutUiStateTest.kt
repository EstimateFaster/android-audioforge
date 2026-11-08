package com.estimatefaster.audioforge.feature.about

import com.estimatefaster.audioforge.core.audio.FfmpegProbeState
import com.estimatefaster.audioforge.data.db.RoomProbeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-10：关于页的纯 JVM 测试。
 *
 * 不依赖 Android/Robolectric、不读写文件：版本格式化、探针状态映射与超时降级、
 * 诊断文本拼装/脱敏/截断、许可顺序、加载/错误/空态全部由纯函数覆盖。
 * 生产适配器对 Room/WorkManager/StorageMaintenance 的封装只做映射，不在此重复测试。
 */
class AboutUiStateTest {

    // ------------------------------------------------------------------
    // 版本 / 构建类型格式化
    // ------------------------------------------------------------------

    @Test
    fun version_label_combines_name_and_code() {
        assertEquals("0.1.0 (42)", formatVersionLabel("0.1.0", 42))
        assertEquals("0.1.0-debug (1)", formatVersionLabel(" 0.1.0-debug ", 1))
        assertEquals("unknown (7)", formatVersionLabel("   ", 7))
        assertEquals("unknown (0)", formatVersionLabel("", 0))
    }

    @Test
    fun build_info_normalizes_build_type_and_package() {
        assertEquals("debug", formatBuildType(" DEBUG "))
        assertEquals("release", formatBuildType("release"))
        assertEquals(UNKNOWN_VALUE, formatBuildType(""))

        val info = AppBuildInfo(
            versionName = "1.2.3",
            versionCode = 9,
            buildType = "Release",
            packageName = " com.estimatefaster.audioforge ",
        )
        assertEquals("1.2.3 (9)", info.versionLabel)
        assertEquals("release", info.buildTypeLabel)
        assertEquals("com.estimatefaster.audioforge", info.packageLabel)
        assertEquals(UNKNOWN_VALUE, info.copy(packageName = " ").packageLabel)
    }

    // ------------------------------------------------------------------
    // 探针成功 / 失败 / 空闲 / 运行中
    // ------------------------------------------------------------------

    @Test
    fun room_probe_passed_maps_counter_and_elapsed_time() {
        val diagnostic = RoomProbeState.Passed(counter = 3, elapsedMs = 12L).toProbeDiagnostic()

        assertEquals(ProbeKind.ROOM, diagnostic.kind)
        assertEquals(ProbeState.PASSED, diagnostic.state)
        assertEquals("counter=3", diagnostic.summary)
        assertEquals("elapsed_ms=12", diagnostic.detail)
        assertTrue(diagnostic.isSuccessful)
        assertTrue(diagnostic.isTerminal)
    }

    @Test
    fun ffmpeg_probe_passed_maps_version_and_sizes() {
        val diagnostic = FfmpegProbeState.Passed(
            version = "ffmpeg version 8.1.9",
            silenceBytes = 1_000L,
            joinedDurationMs = 812L,
        ).toProbeDiagnostic()

        assertEquals(ProbeKind.FFMPEG, diagnostic.kind)
        assertEquals(ProbeState.PASSED, diagnostic.state)
        assertEquals("ffmpeg version 8.1.9", diagnostic.summary)
        assertEquals("silence_bytes=1000, joined_ms=812", diagnostic.detail)
    }

    @Test
    fun probe_failures_keep_message() {
        val room = RoomProbeState.Failed("db locked").toProbeDiagnostic()
        assertEquals(ProbeKind.ROOM, room.kind)
        assertEquals(ProbeState.FAILED, room.state)
        assertEquals("db locked", room.detail)
        assertFalse(room.isSuccessful)
        assertTrue(room.isTerminal)

        val ffmpeg = FfmpegProbeState.Failed("codec missing").toProbeDiagnostic()
        assertEquals(ProbeKind.FFMPEG, ffmpeg.kind)
        assertEquals(ProbeState.FAILED, ffmpeg.state)
        assertEquals("codec missing", ffmpeg.detail)
    }

    @Test
    fun idle_and_running_probes_are_not_terminal() {
        val room = RoomProbeState.Idle.toProbeDiagnostic()
        assertEquals(ProbeState.IDLE, room.state)
        assertFalse(room.isTerminal)

        val ffmpeg = FfmpegProbeState.Running.toProbeDiagnostic()
        assertEquals(ProbeState.RUNNING, ffmpeg.state)
        assertFalse(ffmpeg.isTerminal)
    }

    // ------------------------------------------------------------------
    // 超时降级
    // ------------------------------------------------------------------

    @Test
    fun resolve_probe_returns_terminal_state_immediately() = runTest {
        val states = MutableStateFlow(
            ProbeDiagnostic.passed(ProbeKind.ROOM, summary = "counter=1"),
        )

        val resolved = resolveProbe(ProbeKind.ROOM, states, timeoutMs = 0L)

        assertEquals(ProbeState.PASSED, resolved.state)
        assertEquals("counter=1", resolved.summary)
    }

    @Test
    fun resolve_probe_leaves_idle_probe_alone() = runTest {
        val states = MutableStateFlow(ProbeDiagnostic.idle(ProbeKind.FFMPEG))

        val resolved = resolveProbe(ProbeKind.FFMPEG, states, timeoutMs = 1_000L)

        assertEquals(ProbeState.IDLE, resolved.state)
    }

    @Test
    fun resolve_probe_waits_for_running_probe_to_finish() = runTest {
        val states = MutableStateFlow(ProbeDiagnostic.running(ProbeKind.ROOM))
        launch {
            delay(100L)
            states.value = ProbeDiagnostic.passed(ProbeKind.ROOM, summary = "counter=4")
        }

        val resolved = resolveProbe(ProbeKind.ROOM, states, timeoutMs = 1_000L)

        assertEquals(ProbeState.PASSED, resolved.state)
        assertEquals("counter=4", resolved.summary)
    }

    @Test
    fun resolve_probe_times_out_without_throwing() = runTest {
        val states = MutableStateFlow(ProbeDiagnostic.running(ProbeKind.FFMPEG))

        val resolved = resolveProbe(ProbeKind.FFMPEG, states, timeoutMs = 1_000L)

        assertEquals(ProbeKind.FFMPEG, resolved.kind)
        assertEquals(ProbeState.TIMED_OUT, resolved.state)
        assertTrue(resolved.isTerminal)
        assertFalse(resolved.isSuccessful)
        assertNull(resolved.summary)
    }

    // ------------------------------------------------------------------
    // 诊断文本：含/不含错误、脱敏、截断
    // ------------------------------------------------------------------

    @Test
    fun diagnostic_text_lists_core_sections_without_errors() {
        val text = buildDiagnosticText(report())

        assertTrue(text.startsWith("AudioForge diagnostics v1"))
        assertTrue(text.contains("app.version_name=0.1.0-debug"))
        assertTrue(text.contains("app.version_code=1"))
        assertTrue(text.contains("app.build_type=debug"))
        assertTrue(text.contains("room.library_version=2.8.5"))
        assertTrue(text.contains("room.schema_version=1"))
        assertTrue(text.contains("room.table_count=8"))
        assertTrue(text.contains("room.probe.status=passed"))
        assertTrue(text.contains("ffmpeg.probe.summary=ffmpeg version 8.1.9"))
        assertTrue(text.contains("workmanager.status=available"))
        assertTrue(text.contains("storage.total_bytes=1536"))
        assertTrue(text.contains("errors.count=0"))
        assertFalse(text.contains("error.0"))
    }

    @Test
    fun diagnostic_text_includes_recent_errors() {
        val text = buildDiagnosticText(
            report(
                recentErrors = listOf(
                    RecentError("长夜将明", "第 12 章 TTS 超时", 1_760_000_000_000L),
                    RecentError(null, "未知项目失败", null),
                ),
            ),
        )

        assertTrue(text.contains("errors.count=2"))
        assertTrue(text.contains("error.0.project=长夜将明"))
        assertTrue(text.contains("error.0.message=第 12 章 TTS 超时"))
        assertTrue(text.contains("error.0.finished_at_ms=1760000000000"))
        assertTrue(text.contains("error.1.project=unknown"))
        assertTrue(text.contains("error.1.message=未知项目失败"))
        assertFalse(text.contains("error.1.finished_at_ms"))
    }

    @Test
    fun diagnostic_text_redacts_api_keys_and_bearer_tokens() {
        val text = buildDiagnosticText(
            report(
                recentErrors = listOf(
                    RecentError(
                        projectTitle = "书",
                        message = "apiKey=sk-abcdef123456 调用失败；Authorization: Bearer abc.def.ghi",
                    ),
                ),
            ),
        )

        assertFalse(text.contains("abcdef123456"))
        assertFalse(text.contains("abc.def.ghi"))
        assertTrue(text.contains("apiKey="))
        assertTrue(text.contains("***"))
    }

    @Test
    fun diagnostic_text_caps_single_error_message() {
        val longMessage = "x".repeat(MAX_ERROR_MESSAGE_CHARS + 60)
        val text = buildDiagnosticText(
            report(recentErrors = listOf(RecentError("书", longMessage))),
        )

        assertTrue(text.contains("x".repeat(MAX_ERROR_MESSAGE_CHARS) + "…"))
        assertFalse(text.contains("x".repeat(MAX_ERROR_MESSAGE_CHARS + 1)))
    }

    @Test
    fun diagnostic_text_truncates_to_limit() {
        val text = buildDiagnosticText(
            report(recentErrors = listOf(RecentError("书", "失败"))),
            maxChars = 200,
        )

        assertTrue("实际长度 ${text.length}", text.length <= 200)
        assertTrue(text.contains("截断"))
        assertTrue(text.endsWith("）"))
    }

    @Test
    fun diagnostic_text_with_zero_limit_is_empty() {
        assertEquals("", buildDiagnosticText(report(), maxChars = 0))
    }

    // ------------------------------------------------------------------
    // 许可证列表
    // ------------------------------------------------------------------

    @Test
    fun license_catalog_is_non_empty_and_ordered() {
        val licenses = AboutLicenses.entries

        assertTrue(licenses.isNotEmpty())
        assertEquals(
            listOf(
                "ffmpeg-kit",
                "okhttp",
                "room",
                "media3",
                "compose",
                "serialization",
                "workmanager",
            ),
            licenses.map { it.id },
        )
        assertEquals("FFmpegKit", licenses.first().name)
        assertEquals("LGPL-3.0", licenses.first().license)
        assertTrue(licenses.drop(1).all { it.license == "Apache-2.0" })
        assertTrue(licenses.all { it.name.isNotBlank() && it.version.isNotBlank() })
        assertEquals(licenses.size, licenses.map { it.id }.distinct().size)
    }

    @Test
    fun toggling_license_expands_and_collapses_the_same_entry() {
        val loaded = AboutUiState().withReport(report())
        assertNull(loaded.expandedLicenseId)

        val expanded = loaded.toggleLicense("okhttp")
        assertEquals("okhttp", expanded.expandedLicenseId)
        assertEquals("OkHttp", expanded.expandedLicense?.name)

        val switched = expanded.toggleLicense("room")
        assertEquals("room", switched.expandedLicenseId)

        val collapsed = switched.toggleLicense("room")
        assertNull(collapsed.expandedLicenseId)
        assertNull(collapsed.expandedLicense)
    }

    // ------------------------------------------------------------------
    // 加载 / 错误 / 空态
    // ------------------------------------------------------------------

    @Test
    fun initial_state_is_loading_and_not_empty() {
        val initial = AboutUiState()

        assertTrue(initial.isLoadingFirstTime)
        assertFalse(initial.isRefreshing)
        assertFalse(initial.isEmpty)
        assertFalse(initial.isReady)
        assertFalse(initial.canShareDiagnostics)
    }

    @Test
    fun with_report_clears_loading_and_builds_shareable_text() {
        val loaded = AboutUiState().withReport(report())

        assertFalse(loaded.loading)
        assertNull(loaded.loadError)
        assertTrue(loaded.isReady)
        assertTrue(loaded.canShareDiagnostics)
        assertFalse(loaded.diagnosticText.isBlank())
        assertTrue(loaded.diagnosticText.startsWith("AudioForge diagnostics v1"))
        assertNotNull(loaded.report)
    }

    @Test
    fun load_error_keeps_previous_report_and_text() {
        val loaded = AboutUiState().withReport(report())
        val failed = loaded.markLoading().withLoadError("database is locked")

        assertFalse(failed.loading)
        assertEquals("database is locked", failed.loadError)
        assertEquals(loaded.report, failed.report)
        assertEquals(loaded.diagnosticText, failed.diagnosticText)
        assertFalse(failed.isEmpty)
        assertTrue(failed.canShareDiagnostics)
    }

    @Test
    fun empty_state_only_after_loading_finishes_without_error() {
        val empty = AboutUiState(loading = false)

        assertTrue(empty.isEmpty)
        assertFalse(empty.isLoadingFirstTime)
        assertFalse(empty.canShareDiagnostics)
        assertNull(empty.report)
    }

    @Test
    fun refreshing_keeps_old_report_visible() {
        val loaded = AboutUiState().withReport(report())
        val refreshing = loaded.markLoading()

        assertTrue(refreshing.isRefreshing)
        assertFalse(refreshing.isLoadingFirstTime)
        assertEquals(loaded.report, refreshing.report)
        assertEquals(loaded.diagnosticText, refreshing.diagnosticText)
    }

    // ------------------------------------------------------------------
    // 测试夹具
    // ------------------------------------------------------------------

    private fun report(
        database: DatabaseDiagnostic = DatabaseDiagnostic(
            libraryVersion = "2.8.5",
            schemaVersion = 1,
            tableCount = 8,
        ),
        room: ProbeDiagnostic = ProbeDiagnostic.passed(
            kind = ProbeKind.ROOM,
            summary = "counter=3",
            detail = "elapsed_ms=12",
        ),
        ffmpeg: ProbeDiagnostic = ProbeDiagnostic.passed(
            kind = ProbeKind.FFMPEG,
            summary = "ffmpeg version 8.1.9",
            detail = "silence_bytes=1000, joined_ms=812",
        ),
        workManager: WorkManagerDiagnostic = WorkManagerDiagnostic.available(activeWorkCount = 0),
        storage: StorageDiagnostic? = StorageDiagnostic(
            totalBytes = 1_536L,
            fileCount = 3,
            usableBytes = 8_192L,
        ),
        recentErrors: List<RecentError> = emptyList(),
        generatedAt: Long = 1_760_000_000_000L,
    ): DiagnosticsReport = DiagnosticsReport(
        buildInfo = AppBuildInfo(
            versionName = "0.1.0-debug",
            versionCode = 1,
            buildType = "debug",
            packageName = "com.estimatefaster.audioforge.debug",
        ),
        database = database,
        room = room,
        ffmpeg = ffmpeg,
        workManager = workManager,
        storage = storage,
        recentErrors = recentErrors,
        generatedAt = generatedAt,
    )
}
