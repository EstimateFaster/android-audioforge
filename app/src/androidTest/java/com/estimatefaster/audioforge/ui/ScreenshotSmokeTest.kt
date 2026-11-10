package com.estimatefaster.audioforge.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.estimatefaster.audioforge.AudioForgeApp
import com.estimatefaster.audioforge.MainActivity
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.data.db.SegmentEntity
import com.estimatefaster.audioforge.data.repository.ChapterInput
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** CI 截图：seed 一个项目后逐屏 captureToImage，smoke workflow 通过 adb pull 上传。 */
@RunWith(AndroidJUnit4::class)
class ScreenshotSmokeTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(SeedRule())
        .around(composeRule)

    @Test
    fun capture_main_screens() {
        val skip = string(R.string.onboarding_skip)
        waitForText(skip)
        composeRule.onNodeWithText(skip).performClick()
        waitForText(string(R.string.ux2_projects_title))
        capture("01-onboarding-bookshelf")

        val title = "长夜将明"
        composeRule.onAllNodesWithText(title).onFirst().performClick()
        composeRule.waitForIdle()
        Thread.sleep(600)
        capture("02-project")
        composeRule.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        waitForText(string(R.string.ux2_projects_title))

        openTab(R.string.ux_nav_tab_tasks)
        composeRule.waitForIdle()
        capture("03-tasks")

        openTab(R.string.ux_nav_tab_settings)
        composeRule.waitForIdle()
        capture("04-settings")

        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(string(R.string.settings_action_open_storage)))
        composeRule.onNodeWithText(string(R.string.settings_action_open_storage)).performClick()
        waitForText(string(R.string.storage_title))
        capture("05-storage")
        composeRule.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        composeRule.waitForIdle()

        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(string(R.string.settings_action_open_about)))
        composeRule.onNodeWithText(string(R.string.settings_action_open_about)).performClick()
        waitForText(string(R.string.about_title))
        capture("06-about")
    }

    private fun capture(name: String) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir("screenshots") ?: return
        base.mkdirs()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        FileOutputStream(File(base, "$name.png")).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun openTab(resId: Int) {
        composeRule.onNode(hasText(string(resId)) and hasClickAction()).performClick()
    }

    private fun waitForText(value: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun string(resId: Int): String = InstrumentationRegistry.getInstrumentation()
        .targetContext.getString(resId)
}

/** Activity 启动前 seed：清库 + 一个项目 / 两章 / 三个角色 / 若干片段。 */
private class SeedRule : ExternalResource() {
    override fun before() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as AudioForgeApp
        val c = app.container
        runBlocking {
            withTimeout(20_000) {
                c.database.clearAllTables()
                c.settingsRepository.update { it.copy(onboardingCompleted = false) }
                val novelId = c.projectRepository.createProject(
                    title = "长夜将明",
                    sourceName = "长夜将明.txt",
                    sourceRelPath = "imports/seed.txt",
                    chapters = listOf(
                        ChapterInput("第一章 雨夜", "旧城的雨落了一夜。沈砚推门而入。"),
                        ChapterInput("第二章 灯火", "顾青抬头，看见远处的灯。"),
                    ),
                )
                val chapters = c.chapterRepository.observeBriefs(novelId).first()
                c.characterRepository.upsert(CharacterEntity(novelId = novelId, roleId = "role_0001", name = "沈砚", gender = "male", importance = "protagonist", category = "male_lead_steady", voiceId = "voice-1"))
                c.characterRepository.upsert(CharacterEntity(novelId = novelId, roleId = "role_0002", name = "顾青", gender = "female", importance = "supporting", category = "female_lead_warm", voiceId = "voice-2"))
                c.segmentRepository.replace(
                    chapters.first().id,
                    listOf(
                        SegmentEntity(chapterId = chapters.first().id, seq = 0, type = "narration", roleId = "narrator", emotion = "neutral", text = "旧城的雨落了一夜。", status = "done"),
                        SegmentEntity(chapterId = chapters.first().id, seq = 1, type = "dialogue", roleId = "role_0001", emotion = "calm", text = "灯还亮着。", status = "done"),
                    ),
                )
            }
        }
    }
}
