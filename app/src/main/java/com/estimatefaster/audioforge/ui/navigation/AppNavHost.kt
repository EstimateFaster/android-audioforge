package com.estimatefaster.audioforge.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute

/** 项目详情占位页的示例项目 ID，仅用于手动验收进入二级页/返回栈。 */
private const val SAMPLE_NOVEL_ID = 1L

/**
 * 应用导航入口（P4-01）。
 *
 * 结构：`AdaptiveScaffold`（底栏/rail + 迷你播放器）→ `NavHost`（类型安全路由）。
 * MainActivity 只负责 edge-to-edge 与主题，不再直接显示调试首页。
 */
@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selectedDestination = TopLevelDestination.fromDestination(backStackEntry?.destination)

    AdaptiveScaffold(
        selectedDestination = selectedDestination,
        onSelectDestination = { destination -> navController.navigateToTopLevel(destination) },
        modifier = modifier,
    ) {
        NavHost(
            navController = navController,
            startDestination = Route.Projects,
            modifier = Modifier.fillMaxSize(),
        ) {
            appNavGraph(navController)
        }
    }
}

/**
 * 路由表。抽成普通函数便于测试直接构建 NavGraph，不依赖 Compose UI。
 */
fun NavGraphBuilder.appNavGraph(navController: NavController) {
    composable<Route.Projects> {
        ProjectsPlaceholderScreen(
            onOpenProject = { navController.navigate(Route.ProjectDetail(SAMPLE_NOVEL_ID)) },
        )
    }

    composable<Route.Tasks> {
        TasksPlaceholderScreen()
    }

    composable<Route.Settings> {
        SettingsPlaceholderScreen()
    }

    composable<Route.ProjectDetail>(
        deepLinks = listOf(
            navDeepLink<Route.ProjectDetail>(basePath = AppDeepLink.PROJECT_BASE_PATH),
        ),
    ) { entry ->
        val route = entry.toRoute<Route.ProjectDetail>()
        ProjectDetailPlaceholderScreen(
            novelId = route.novelId,
            onOpenChapters = { navController.navigate(Route.ChapterList(route.novelId)) },
        )
    }

    composable<Route.ChapterList> { entry ->
        val route = entry.toRoute<Route.ChapterList>()
        ChapterListPlaceholderScreen(novelId = route.novelId)
    }
}

/**
 * 主目的地切换：回到起始图、保存/恢复各 tab 的 back stack，避免重复入栈。
 */
private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
