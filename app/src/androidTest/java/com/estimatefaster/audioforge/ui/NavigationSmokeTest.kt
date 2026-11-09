package com.estimatefaster.audioforge.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.estimatefaster.audioforge.AudioForgeApp
import com.estimatefaster.audioforge.MainActivity
import com.estimatefaster.audioforge.R
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * P5-14 导航链路 instrumented smoke（emulator smoke 会跑）。
 *
 * 覆盖：
 * - 首次启动落在引导页，点「跳过」后进入项目库（空态 + 导入入口）；
 * - 底栏在项目 / 任务 / 设置之间切换；
 * - 设置页可进入存储、关于，并能用页内「返回」回到设置。
 *
 * 隔离：DataStore 中的 `onboarding_completed` 与 App 数据库在 **Activity 启动之前**
 * 由 [ResetAppStateRule] 重置。不使用真实 API Key，也不发起任何网络请求。
 * 说明：Activity 由 `createAndroidComposeRule` 在 JUnit `@Before` 之前启动，
 * 因此重置放在 RuleChain 的外层规则里，而不是 `@Before`。
 */
@RunWith(AndroidJUnit4::class)
class NavigationSmokeTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(ResetAppStateRule())
        .around(composeRule)

    @Test
    fun onboarding_skip_opens_project_library() {
        val onboardingTitle = text(R.string.onboarding_title)
        val skip = text(R.string.onboarding_skip)

        waitForText(skip)
        composeRule.onNodeWithText(onboardingTitle).assertIsDisplayed()
        composeRule.onNodeWithText(skip).assertIsDisplayed().performClick()

        waitForText(text(R.string.project_library_title))
        composeRule.onNodeWithText(text(R.string.project_library_title)).assertIsDisplayed()

        // 数据库已清空，项目库应稳定落在空态；导入 FAB 始终可见。
        waitForText(text(R.string.project_library_empty_title))
        composeRule.onNodeWithText(text(R.string.project_library_empty_title)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.project_library_empty_action)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.project_library_import)).assertIsDisplayed()
    }

    @Test
    fun bottom_navigation_switches_projects_tasks_and_settings() {
        skipOnboarding()

        openTopLevelTab(R.string.nav_tasks)
        waitForText(text(R.string.tasks_subtitle))
        composeRule.onNodeWithText(text(R.string.tasks_subtitle)).assertIsDisplayed()

        openTopLevelTab(R.string.nav_settings)
        waitForText(text(R.string.settings_section_api))
        composeRule.onNodeWithText(text(R.string.settings_section_api)).assertIsDisplayed()

        openTopLevelTab(R.string.nav_projects)
        waitForText(text(R.string.project_library_title))
        composeRule.onNodeWithText(text(R.string.project_library_title)).assertIsDisplayed()
    }

    @Test
    fun settings_opens_storage_and_about_then_returns() {
        skipOnboarding()
        openTopLevelTab(R.string.nav_settings)
        waitForSettingsContent()

        scrollSettingsTo(text(R.string.settings_action_open_storage))
        composeRule.onNodeWithText(text(R.string.settings_action_open_storage))
            .assertIsDisplayed()
            .performClick()

        // 设置页没有「刷新」，它是存储页顶栏的稳定标记。
        waitForText(text(R.string.action_refresh))
        composeRule.onNodeWithText(text(R.string.storage_title)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.action_back)).assertIsDisplayed().performClick()
        waitForSettingsContent()

        scrollSettingsTo(text(R.string.settings_action_open_about))
        composeRule.onNodeWithText(text(R.string.settings_action_open_about))
            .assertIsDisplayed()
            .performClick()

        // 同上：设置页没有「刷新」，用它判断关于页已经进入且转场结束。
        waitForText(text(R.string.action_refresh))
        composeRule.onNodeWithText(text(R.string.about_title)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.action_back)).assertIsDisplayed().performClick()
        waitForSettingsContent()
    }

    @Test
    fun theme_switch_applies_immediately_and_keeps_navigation() {
        skipOnboarding()
        openTopLevelTab(R.string.nav_settings)
        waitForSettingsContent()

        scrollSettingsTo(text(R.string.settings_theme_dark))
        composeRule.onNodeWithText(text(R.string.settings_theme_dark)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(text(R.string.settings_theme_dark)).assertIsSelected()

        scrollSettingsTo(text(R.string.settings_theme_light))
        composeRule.onNodeWithText(text(R.string.settings_theme_light)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(text(R.string.settings_theme_light)).assertIsSelected()

        openTopLevelTab(R.string.nav_projects)
        waitForText(text(R.string.project_library_title))
        composeRule.onNodeWithText(text(R.string.project_library_title)).assertIsDisplayed()
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private fun skipOnboarding() {
        val skip = text(R.string.onboarding_skip)
        waitForText(skip)
        composeRule.onNodeWithText(skip).performClick()
        waitForText(text(R.string.project_library_title))
    }

    private fun openTopLevelTab(labelRes: Int) {
        val label = text(labelRes)
        composeRule.onNode(hasText(label) and hasClickAction()).performClick()
    }

    private fun scrollSettingsTo(label: String) {
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label))
    }

    private fun waitForText(value: String) {
        composeRule.waitUntil(UI_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * 等设置页顶栏和可滚动内容都就绪。
     *
     * 顶栏「保存」在加载态也存在；从存储/关于返回时设置列表可能保留在原滚动位置，
     * 因此不能再用列表顶部的「API」判断。再等一个 ScrollToIndex 节点，
     * 保证 [scrollSettingsTo] 拿到的是设置页自己的 LazyColumn 而不是上一页面的列表。
     */
    private fun waitForSettingsContent() {
        waitForText(text(R.string.action_save))
        composeRule.waitUntil(UI_TIMEOUT_MS) {
            composeRule.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun text(resId: Int): String = InstrumentationRegistry
        .getInstrumentation()
        .targetContext
        .getString(resId)

    private companion object {
        /** 单次等待上限；模拟器冷启动 + DataStore 首次读取偶尔偏慢。 */
        const val UI_TIMEOUT_MS = 10_000L
    }
}

/**
 * 在 Activity 启动前重置导航相关状态。
 *
 * - `onboarding_completed=false` 让每个用例都从引导页开始，避免依赖设备历史；
 * - `clearAllTables()` 清掉项目/章节/任务等行，保证项目库空态断言可复现。
 *
 * 这里不用删除 `databases/` 文件：Application 启动时 debug Room 探针已经可能打开了
 * 数据库，删除文件对已打开的连接不生效；走 Room API 才是确定性的。
 */
private class ResetAppStateRule : ExternalResource() {

    override fun before() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as AudioForgeApp
        app.container.database.clearAllTables()
        runBlocking {
            withTimeout(RESET_TIMEOUT_MS) {
                app.container.settingsRepository.update { settings ->
                    settings.copy(onboardingCompleted = false)
                }
            }
        }
    }

    private companion object {
        const val RESET_TIMEOUT_MS = 10_000L
    }
}
