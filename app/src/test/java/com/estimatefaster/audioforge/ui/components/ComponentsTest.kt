package com.estimatefaster.audioforge.ui.components

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.estimatefaster.audioforge.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P4-02：设计系统组件的纯逻辑/Robolectric 测试。
 *
 * 不引入 compose ui-test（本项目未在 testImplementation 中声明；
 * 按任务要求不新增 Gradle 依赖），因此覆盖可独立验证的规则：
 * 进度 clamp、状态色/文案映射、头像首字与哈希稳定性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComponentsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // ------------------------------------------------------------------
    // ProgressRing
    // ------------------------------------------------------------------

    @Test
    fun progress_is_clamped_to_zero_one() {
        assertEquals(0f, clampProgress(-0.5f), 0f)
        assertEquals(0f, clampProgress(0f), 0f)
        assertEquals(0.25f, clampProgress(0.25f), 0f)
        assertEquals(1f, clampProgress(1f), 0f)
        assertEquals(1f, clampProgress(1.5f), 0f)
    }

    @Test
    fun progress_handles_nan_and_infinity() {
        assertEquals(0f, clampProgress(Float.NaN), 0f)
        assertEquals(0f, clampProgress(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(1f, clampProgress(Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun progress_percent_rounds_half_up_and_clamps() {
        assertEquals(0, progressPercent(-1f))
        assertEquals(0, progressPercent(Float.NaN))
        assertEquals(46, progressPercent(0.456f))
        assertEquals(50, progressPercent(0.5f))
        assertEquals(100, progressPercent(1f))
        assertEquals(100, progressPercent(2f))
    }

    // ------------------------------------------------------------------
    // StatusPill
    // ------------------------------------------------------------------

    @Test
    fun status_tone_maps_wire_values_and_is_case_insensitive() {
        assertEquals(StatusTone.Running, statusToneFor("running"))
        assertEquals(StatusTone.Running, statusToneFor(" RUNNING "))
        assertEquals(StatusTone.Success, statusToneFor("done"))
        assertEquals(StatusTone.Success, statusToneFor("succeeded"))
        assertEquals(StatusTone.Danger, statusToneFor("error"))
        assertEquals(StatusTone.Danger, statusToneFor("failed"))
        assertEquals(StatusTone.Info, statusToneFor("queued"))
        assertEquals(StatusTone.Info, statusToneFor("enqueued"))
        assertEquals(StatusTone.Warning, statusToneFor("blocked"))
        assertEquals(StatusTone.Neutral, statusToneFor("pending"))
        assertEquals(StatusTone.Neutral, statusToneFor("idle"))
        assertEquals(StatusTone.Neutral, statusToneFor("cancelled"))
        assertEquals(StatusTone.Neutral, statusToneFor("not-a-status"))
    }

    @Test
    fun status_labels_are_domain_specific() {
        assertEquals("待解析", context.getString(statusLabelRes(StatusKind.Parse, "pending")))
        assertEquals("待生成", context.getString(statusLabelRes(StatusKind.Audio, "pending")))
        assertEquals("待处理", context.getString(statusLabelRes(StatusKind.Task, "pending")))

        assertEquals("解析中", context.getString(statusLabelRes(StatusKind.Parse, " RUNNING ")))
        assertEquals("生成中", context.getString(statusLabelRes(StatusKind.Audio, "running")))
        assertEquals("运行中", context.getString(statusLabelRes(StatusKind.Task, "running")))

        assertEquals("已取消", context.getString(statusLabelRes(StatusKind.Task, "cancelled")))
        assertEquals("等待条件", context.getString(statusLabelRes(StatusKind.Task, "blocked")))
        assertEquals("未知", context.getString(statusLabelRes(StatusKind.Parse, "not-a-status")))
    }

    @Test
    fun status_colors_are_resolved_from_the_color_scheme() {
        val scheme = lightColorScheme()
        assertStatusColors(StatusTone.Neutral, scheme, scheme.surfaceVariant, scheme.onSurfaceVariant)
        assertStatusColors(StatusTone.Info, scheme, scheme.primaryContainer, scheme.onPrimaryContainer)
        assertStatusColors(StatusTone.Running, scheme, scheme.primary, scheme.onPrimary)
        assertStatusColors(StatusTone.Success, scheme, scheme.tertiaryContainer, scheme.onTertiaryContainer)
        assertStatusColors(StatusTone.Warning, scheme, scheme.secondaryContainer, scheme.onSecondaryContainer)
        assertStatusColors(StatusTone.Danger, scheme, scheme.errorContainer, scheme.onErrorContainer)
    }

    @Test
    fun every_status_tone_has_a_distinct_container_color() {
        val scheme = lightColorScheme()
        val containers = StatusTone.entries.map { resolveStatusPillColors(it, scheme).container }
        assertEquals(
            "同一 colorScheme 下各语义色不应重复：$containers",
            StatusTone.entries.size,
            containers.toSet().size,
        )
    }

    // ------------------------------------------------------------------
    // RoleAvatar
    // ------------------------------------------------------------------

    @Test
    fun avatar_initial_uses_first_code_point_and_trims() {
        assertEquals("林", avatarInitial("林黛玉"))
        assertEquals("林", avatarInitial("  林黛玉  "))
        assertEquals("a", avatarInitial("alice"))
        assertEquals("🎙", avatarInitial("🎙️主播"))
    }

    @Test
    fun avatar_initial_falls_back_for_blank_names() {
        assertEquals("?", avatarInitial(""))
        assertEquals("?", avatarInitial("   "))
    }

    @Test
    fun avatar_color_index_is_stable_and_in_range() {
        val first = avatarColorIndex("林黛玉", 4)
        assertTrue("色槽应在 0..3，实际 $first", first in 0 until 4)
        repeat(20) {
            assertEquals(first, avatarColorIndex("林黛玉", 4))
        }
        assertEquals("trim 不应改变色槽", first, avatarColorIndex("  林黛玉  ", 4))
    }

    @Test
    fun avatar_color_index_distributes_and_handles_edge_cases() {
        assertEquals(0, avatarColorIndex("", 4))
        assertEquals(0, avatarColorIndex("   ", 4))
        assertEquals(0, avatarColorIndex("任意名字", 1))

        val spread = (1..40).map { avatarColorIndex("角色$it", 4) }.toSet()
        assertTrue("40 个名字应覆盖至少 2 个色槽，实际 $spread", spread.size >= 2)

        assertThrows(IllegalArgumentException::class.java) {
            avatarColorIndex("任意名字", 0)
        }
    }

    private fun assertStatusColors(
        tone: StatusTone,
        scheme: ColorScheme,
        container: Color,
        content: Color,
    ) {
        val colors = resolveStatusPillColors(tone, scheme)
        assertEquals("$tone container", container, colors.container)
        assertEquals("$tone content", content, colors.content)
    }
}
