package com.estimatefaster.audioforge.ui.navigation

import kotlinx.serialization.Serializable

/**
 * P4-01：类型安全路由。
 *
 * 每个目的地都用 `@Serializable` 的对象/数据类表示，交给 Navigation Compose 的
 * `composable<T>()` 生成 route pattern，避免手写字符串路由。
 *
 * 接线 2/2 起，所有 feature 页面都有对应路由：三个主 tab、项目内二级页
 * （详情/章节/剧本/人物/播放）、以及存储、关于、首次引导。
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

    /** 存储管理（从设置或项目详情进入的二级页）。 */
    @Serializable
    data object Storage : Route

    /** 关于与诊断（从设置或项目详情进入的二级页）。 */
    @Serializable
    data object About : Route

    /** 首次引导；首启且 `onboardingCompleted` 为 false 时作为起始目的地。 */
    @Serializable
    data object Onboarding : Route

    /**
     * 项目详情，深链：`audioforge://project/{novelId}`。
     *
     * 用 `Long` 而不是 `String`，Navigation 会自动绑定 `NavType.LongType`。
     */
    @Serializable
    data class ProjectDetail(val novelId: Long) : Route

    /** 章节列表。 */
    @Serializable
    data class ChapterList(val novelId: Long) : Route

    /** 单章剧本页；[chapterTitle] 只用于顶栏展示，缺省时显示通用标题。 */
    @Serializable
    data class Script(
        val novelId: Long,
        val chapterId: Long,
        val chapterTitle: String = "",
    ) : Route

    /** 人物音色页。 */
    @Serializable
    data class Characters(val novelId: Long) : Route

    /**
     * 播放器页，深链：`audioforge://player`。
     *
     * [novelId] 可空：深链不带书籍 id 时打开播放器空态，从项目页进入时携带项目 id。
     */
    @Serializable
    data class Player(val novelId: Long? = null) : Route
}
