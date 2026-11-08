package com.estimatefaster.audioforge.ui.navigation

import android.content.Context
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import com.estimatefaster.audioforge.app.AppContainer
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
 * 接线 2/2 的导航接线测试。
 *
 * 覆盖三件事：
 * - 首启目的地是纯函数：null → 加载中，false → Onboarding，true → Projects；
 * - NavGraph 注册了全部 11 个目的地；
 * - 深链解析 accept player/project，并拒绝带路径/query/fragment 的形式。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NavWiringTest {

    private lateinit var context: Context
    private lateinit var container: AppContainer
    private lateinit var navController: NavHostController
    private lateinit var graph: NavGraph

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        container = AppContainer(context)
        buildGraph(startDestination = Route.Projects)
    }

    @Test
    fun null_settings_keep_start_destination_undecided() {
        assertNull(startDestinationFor(null))
    }

    @Test
    fun first_launch_starts_at_onboarding() {
        assertEquals(Route.Onboarding, startDestinationFor(false))
    }

    @Test
    fun completed_onboarding_starts_at_projects() {
        assertEquals(Route.Projects, startDestinationFor(true))
    }

    @Test
    fun graph_registers_every_destination() {
        assertTrue(graph.findStartDestination().hasRoute<Route.Projects>())
        assertNotNull(graph.findNode<Route.Projects>())
        assertNotNull(graph.findNode<Route.Tasks>())
        assertNotNull(graph.findNode<Route.Settings>())
        assertNotNull(graph.findNode<Route.Storage>())
        assertNotNull(graph.findNode<Route.About>())
        assertNotNull(graph.findNode<Route.Onboarding>())
        assertNotNull(graph.findNode<Route.ProjectDetail>())
        assertNotNull(graph.findNode<Route.ChapterList>())
        assertNotNull(graph.findNode<Route.Script>())
        assertNotNull(graph.findNode<Route.Characters>())
        assertNotNull(graph.findNode<Route.Player>())
    }

    @Test
    fun onboarding_can_be_the_start_destination() {
        buildGraph(startDestination = Route.Onboarding)
        assertTrue(graph.findStartDestination().hasRoute<Route.Onboarding>())
    }

    @Test
    fun deep_link_parser_resolves_project_and_player() {
        assertEquals(Route.ProjectDetail(42L), AppDeepLink.parse("audioforge://project/42"))
        assertEquals(Route.Player(), AppDeepLink.parse("audioforge://player"))
    }

    @Test
    fun deep_link_parser_rejects_player_variants_with_extra_parts() {
        listOf(
            "audioforge://player/",
            "audioforge://player/7",
            "audioforge://player?novelId=7",
            "audioforge://player#top",
        ).forEach { uri ->
            assertNull("应拒绝: $uri", AppDeepLink.parse(uri))
        }
    }

    private fun buildGraph(startDestination: Route) {
        navController = NavHostController(context)
        navController.navigatorProvider.addNavigator(ComposeNavigator())
        graph = navController.createGraph(startDestination = startDestination) {
            appNavGraph(navController, container)
        }
        navController.setGraph(graph, null)
    }
}
