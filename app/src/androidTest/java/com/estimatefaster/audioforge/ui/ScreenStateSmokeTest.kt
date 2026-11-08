package com.estimatefaster.audioforge.ui

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.ui.components.EmptyState
import com.estimatefaster.audioforge.ui.components.ErrorState
import com.estimatefaster.audioforge.ui.components.ProgressRing
import com.estimatefaster.audioforge.ui.components.StatusKind
import com.estimatefaster.audioforge.ui.components.StatusPill
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P5-14 无状态组件 instrumented smoke（emulator smoke 会跑）。
 *
 * 不启动 MainActivity、不触碰 DataStore / Room / 网络，只直接 compose
 * [EmptyState]、[ErrorState]、[StatusPill]、[ProgressRing]，
 * 断言可见文本、点击回调与无障碍语义（进度条 range info / contentDescription）。
 */
@RunWith(AndroidJUnit4::class)
class ScreenStateSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyState_exposesTitleDescriptionAndAction() {
        var actionClicks = 0
        val title = text(R.string.project_library_empty_title)
        val description = text(R.string.project_library_empty_description)
        val action = text(R.string.project_library_empty_action)

        composeRule.setContent {
            AudioForgeTheme(dynamicColor = false) {
                EmptyState(
                    title = title,
                    description = description,
                    actionText = action,
                    onAction = { actionClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText(title).assertIsDisplayed()
        composeRule.onNodeWithText(description).assertIsDisplayed()
        composeRule.onNodeWithText(action).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, actionClicks) }
    }

    @Test
    fun errorState_exposesMessageAndRetryCallback() {
        var retryClicks = 0
        val message = text(R.string.error_state_demo_message)
        val retry = text(R.string.action_retry)

        composeRule.setContent {
            AudioForgeTheme(dynamicColor = false) {
                ErrorState(
                    message = message,
                    retryText = retry,
                    onRetry = { retryClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText(message).assertIsDisplayed()
        composeRule.onNodeWithText(retry).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, retryClicks) }
    }

    @Test
    fun statusPill_mapsStatusToLabelAndStaysClickable() {
        var clicks = 0
        val doneLabel = text(R.string.status_audio_done)

        composeRule.setContent {
            AudioForgeTheme(dynamicColor = false) {
                StatusPill(
                    kind = StatusKind.Audio,
                    status = "done",
                    onClick = { clicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText(doneLabel).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun progressRing_exposesRangeInfoAndDescription() {
        val label = text(R.string.progress_demo_label)

        composeRule.setContent {
            AudioForgeTheme(dynamicColor = false) {
                ProgressRing(progress = 0.42f, label = label)
            }
        }

        composeRule.onNodeWithContentDescription(label)
            .assertIsDisplayed()
            .assertRangeInfoEquals(ProgressBarRangeInfo(0.42f, 0f..1f))
    }

    private fun text(resId: Int): String = InstrumentationRegistry
        .getInstrumentation()
        .targetContext
        .getString(resId)
}
