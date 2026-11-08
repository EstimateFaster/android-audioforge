package com.estimatefaster.audioforge.ui.navigation

import android.content.Context
import android.net.Uri
import androidx.navigation.NavDeepLinkRequest
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.navigation.navDeepLink
import androidx.test.core.app.ApplicationProvider
import com.estimatefaster.audioforge.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P4-01 路由表与深链的纯 JVM 测试（不需要 Robolectric）。
 *
 * 覆盖：默认 tab、tab 顺序、路由类型、深链常量与 URI 解析；不涉及 Compose 渲染。
 */
class AppRouteTableTest {

    @Test
    fun top_level_destinations_are_projects_tasks_settings_in_order() {
        assertEquals(
            listOf(Route.Projects, Route.Tasks, Route.Settings),
            TopLevelDestination.entries.map { it.route },
        )
        assertEquals(TopLevelDestination.Projects, TopLevelDestination.start)
        assertEquals(
            listOf(R.string.nav_projects, R.string.nav_tasks, R.string.nav_settings),
            TopLevelDestination.entries.map { it.labelRes },
        )
    }

    @Test
    fun project_detail_preserves_novel_id() {
        val route = Route.ProjectDetail(novelId = 42L)
        assertEquals(42L, route.novelId)
        assertEquals(Route.ProjectDetail(novelId = 42L), route)
    }

    @Test
    fun deep_link_constants_describe_project_detail_pattern() {
        assertEquals("audioforge://project", AppDeepLink.PROJECT_BASE_PATH)
        assertEquals("audioforge://project/{novelId}", AppDeepLink.PROJECT_PATTERN)
        assertEquals("audioforge://project/7", AppDeepLink.projectDetailUri(7L))
    }

    @Test
    fun deep_link_parser_resolves_project_detail() {
        assertEquals(Route.ProjectDetail(42L), AppDeepLink.parse("audioforge://project/42"))
        assertEquals(Route.ProjectDetail(1L), AppDeepLink.parse("audioforge://PROJECT/1"))
    }

    @Test
    fun deep_link_parser_rejects_unsupported_uris() {
        val invalid = listOf(
            "",
            "not a uri",
            "audioforge://project",
            "audioforge://project/",
            "audioforge://project/abc",
            "audioforge://project/0",
            "audioforge://project/-1",
            "audioforge://project/42/extra",
            "audioforge://project/42?x=1",
            "audioforge://project/42#frag",
            "https://project/42",
            "audioforge://novel/42",
        )
        invalid.forEach { uri ->
            assertNull("应拒绝: $uri", AppDeepLink.parse(uri))
        }
    }
}

/**
 * P4-01 真实 NavGraph/NavController 行为测试（Robolectric，不依赖 ui-test-junit4）。
 *
 * 覆盖：默认到 Projects、切换 Tasks/Settings、pop 回 Projects、深链落到 ProjectDetail 且带 novelId。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppNavigationTest {

    private lateinit var navController: NavHostController
    private lateinit var graph: NavGraph

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        navController = NavHostController(context)
        navController.navigatorProvider.addNavigator(ComposeNavigator())
        graph = navController.createGraph(startDestination = Route.Projects) {
            appNavGraph(navController)
        }
        navController.setGraph(graph, null)
    }

    @Test
    fun graph_starts_at_projects_and_registers_all_routes() {
        assertTrue(graph.findStartDestination().hasRoute<Route.Projects>())
        assertNotNull(graph.findNode<Route.Projects>())
        assertNotNull(graph.findNode<Route.Tasks>())
        assertNotNull(graph.findNode<Route.Settings>())
        assertNotNull(graph.findNode<Route.ProjectDetail>())
        assertNotNull(graph.findNode<Route.ChapterList>())
    }

    @Test
    fun default_destination_is_projects() {
        assertTrue(navController.currentDestination!!.hasRoute<Route.Projects>())
        assertNull(navController.previousBackStackEntry)
    }

    @Test
    fun switching_to_tasks_then_settings_updates_current_destination() {
        navController.navigate(Route.Tasks)
        assertTrue(navController.currentDestination!!.hasRoute<Route.Tasks>())

        navController.navigate(Route.Settings)
        assertTrue(navController.currentDestination!!.hasRoute<Route.Settings>())
    }

    @Test
    fun back_stack_pops_back_to_projects() {
        navController.navigate(Route.Tasks)
        assertNotNull(navController.previousBackStackEntry)

        assertTrue(navController.popBackStack())

        assertTrue(navController.currentDestination!!.hasRoute<Route.Projects>())
        assertNull(navController.previousBackStackEntry)
    }

    @Test
    fun deep_link_resolves_project_detail_with_novel_id() {
        val deepLink = navDeepLink<Route.ProjectDetail>(basePath = AppDeepLink.PROJECT_BASE_PATH)
        assertEquals(AppDeepLink.PROJECT_PATTERN, deepLink.uriPattern)

        val request = NavDeepLinkRequest.Builder
            .fromUri(Uri.parse(AppDeepLink.projectDetailUri(42L)))
            .build()

        assertTrue(navController.handleDeepLink(request))
        assertTrue(navController.currentDestination!!.hasRoute<Route.ProjectDetail>())

        val arguments = navController.currentBackStackEntry?.arguments
        assertNotNull(arguments)
        assertEquals(42L, arguments!!.getLong("novelId"))
    }

    @Test
    fun top_level_labels_are_chinese() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("项目", context.getString(R.string.nav_projects))
        assertEquals("任务", context.getString(R.string.nav_tasks))
        assertEquals("设置", context.getString(R.string.nav_settings))
        assertEquals("未在播放", context.getString(R.string.mini_player_idle))
    }
}
