package com.estimatefaster.audioforge.ui.navigation

import kotlinx.serialization.Serializable

/**
 * P4-01：类型安全路由。
 *
 * 每个目的地都用 `@Serializable` 的对象/数据类表示，交给 Navigation Compose 的
 * `composable<T>()` 生成 route pattern，避免手写字符串路由。
 *
 * 本轮只落地三个主路由 + [ProjectDetail] 深链示例；[ChapterList] 作为二级路由示例保留，
 * 后续 P4-07 再替换为真实页面。
 */
@Serializable
sealed interface Route {

    /** 底部/侧边栏第一个主目的地。 */
    @Serializable
    data object Projects : Route

    /** 任务中心。 */
    @Serializable
    data object Tasks : Route

    /** 设置。 */
    @Serializable
    data object Settings : Route

    /**
     * 项目详情，深链：`audioforge://project/{novelId}`。
     *
     * 用 `Long` 而不是 `String`，Navigation 会自动绑定 `NavType.LongType`。
     */
    @Serializable
    data class ProjectDetail(val novelId: Long) : Route

    /** 章节列表（二级页面示例）。 */
    @Serializable
    data class ChapterList(val novelId: Long) : Route
}
