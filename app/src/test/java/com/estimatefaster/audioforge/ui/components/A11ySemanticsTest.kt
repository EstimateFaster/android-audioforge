package com.estimatefaster.audioforge.ui.components

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.estimatefaster.audioforge.R
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
 * P5-13：无障碍/字体复查中可纯逻辑验证的部分。
 *
 * 不引入 compose ui-test（本项目 testImplementation 未声明 ui-test，按任务要求
 * 不新增 Gradle 依赖），因此覆盖：系统“移除动画”判定、200% 字体下的表单重排、
 * 圆环字号上限，以及新增朗读文案的占位符格式。语义树本身留给真机 TalkBack 检查。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class A11ySemanticsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // ------------------------------------------------------------------
    // 动效：系统“移除动画”
    // ------------------------------------------------------------------

    @Test
    fun animations_disabled_only_when_animator_duration_scale_is_zero() {
        assertFalse("0 表示系统已移除动画", animationsEnabled(0f))
        assertFalse(animationsEnabled(-1f))
        assertTrue(animationsEnabled(0.5f))
        assertTrue(animationsEnabled(1f))
        assertTrue(animationsEnabled(2f))
    }

    // ------------------------------------------------------------------
    // 200% 字体：并排表单改为纵向
    // ------------------------------------------------------------------

    @Test
    fun form_rows_stack_only_at_large_font_scale() {
        assertFalse(shouldStackFormFields(1f))
        assertFalse(shouldStackFormFields(1.3f))
        assertFalse("阈值前仍保持并排", shouldStackFormFields(1.49f))
        assertTrue("达到 1.5x 改为纵向", shouldStackFormFields(1.5f))
        assertTrue("200% 必须纵向", shouldStackFormFields(2f))
    }

    // ------------------------------------------------------------------
    // 进度环：中心百分比字号不超过圆环预算
    // ------------------------------------------------------------------

    @Test
    fun progress_ring_text_size_scales_with_diameter() {
        assertEquals(72f * 0.24f, progressRingMaxTextSize(72.dp).value, 0.001f)
        assertEquals(120f * 0.24f, progressRingMaxTextSize(120.dp).value, 0.001f)
    }

    @Test
    fun progress_ring_text_size_leaves_room_for_the_glyphs() {
        listOf(48.dp, 72.dp, 96.dp, 120.dp).forEach { diameter ->
            val textSize = progressRingMaxTextSize(diameter)
            assertTrue("字号必须为正：$diameter", textSize.value > 0f)
            assertTrue(
                "字号 $textSize 应明显小于直径 $diameter，避免 200% 时溢出",
                textSize.value < diameter.value * 0.5f,
            )
        }
    }

    // ------------------------------------------------------------------
    // 新增朗读文案：占位符必须匹配，避免 TalkBack 读出格式串
    // ------------------------------------------------------------------

    @Test
    fun playback_progress_state_description_has_position_and_duration() {
        val formatted = context.getString(
            R.string.player_progress_state_description,
            "01:23",
            "04:05",
        )
        assertEquals("01:23 / 04:05", formatted)
    }

    @Test
    fun batch_concurrency_description_includes_current_value() {
        val formatted = context.getString(
            R.string.batch_concurrency_content_description,
            3,
        )
        assertTrue(formatted.contains("3"))
        assertNotEquals(
            "占位符应被替换",
            context.getString(R.string.batch_concurrency_content_description),
            formatted,
        )
    }

    @Test
    fun storage_area_progress_description_includes_label_and_percent() {
        val formatted = context.getString(
            R.string.storage_area_progress_content_description,
            "音频",
            42,
        )
        assertTrue(formatted.contains("音频"))
        assertTrue(formatted.contains("42"))
    }

    @Test
    fun avatar_and_progress_descriptions_are_non_blank() {
        assertTrue(context.getString(R.string.role_avatar_unknown).isNotBlank())
        assertTrue(
            context.getString(R.string.role_avatar_content_description, "林黛玉")
                .contains("林黛玉"),
        )
        assertTrue(context.getString(R.string.progress_default_description).isNotBlank())
    }
}
