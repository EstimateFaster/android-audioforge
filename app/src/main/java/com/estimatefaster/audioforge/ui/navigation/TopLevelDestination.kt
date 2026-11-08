package com.estimatefaster.audioforge.ui.navigation

import androidx.annotation.StringRes
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import com.estimatefaster.audioforge.R

/**
 * 自适应壳里的三个主目的地：项目 / 任务 / 设置。
 *
 * 顺序即底栏/rail 的展示顺序；[start] 是 NavHost 的起始目的地。
 */
enum class TopLevelDestination(
    @StringRes val labelRes: Int,
    val route: Route,
    val icon: AppIcon,
) {
    Projects(
        labelRes = R.string.nav_projects,
        route = Route.Projects,
        icon = AppIcon.Projects,
    ),
    Tasks(
        labelRes = R.string.nav_tasks,
        route = Route.Tasks,
        icon = AppIcon.Tasks,
    ),
    Settings(
        labelRes = R.string.nav_settings,
        route = Route.Settings,
        icon = AppIcon.Settings,
    ),
    ;

    companion object {

        /** NavHost 起始目的地，验收要求默认落在 Projects。 */
        val start: TopLevelDestination = TopLevelDestination.Projects

        /** 当前 back stack 顶部对应的主目的地；二级页面（ProjectDetail/ChapterList）返回 null。 */
        fun fromDestination(destination: NavDestination?): TopLevelDestination? {
            if (destination == null) return null
            return TopLevelDestination.entries.firstOrNull { destination.matches(it) }
        }

        private fun NavDestination.matches(destination: TopLevelDestination): Boolean =
            when (destination) {
                TopLevelDestination.Projects ->
                    hierarchy.any { it.hasRoute<Route.Projects>() }
                TopLevelDestination.Tasks ->
                    hierarchy.any { it.hasRoute<Route.Tasks>() }
                TopLevelDestination.Settings ->
                    hierarchy.any { it.hasRoute<Route.Settings>() }
            }
    }
}
