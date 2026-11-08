package com.estimatefaster.audioforge.ui.navigation

import java.net.URI

/**
 * 应用深链约定（P4-01 / 接线 2/2）。
 *
 * 支持的形式：
 * - `audioforge://project/{novelId}` → [Route.ProjectDetail]
 * - `audioforge://player` → [Route.Player]（播放器空态；不带书籍 id）
 *
 * NavHost 侧用 `navDeepLink<Route.ProjectDetail>(basePath = [PROJECT_BASE_PATH])` 与
 * `navDeepLink { uriPattern = [PLAYER_URI] }` 生成类型安全的目的地；这里的 [parse]
 * 负责纯 JVM 可测的 URI 解析，两者共用同一组常量，避免漂移。
 */
object AppDeepLink {

    const val SCHEME = "audioforge"

    const val PROJECT_HOST = "project"

    /** NavHost 里传给 `navDeepLink<Route.ProjectDetail>(basePath = ...)` 的前缀。 */
    const val PROJECT_BASE_PATH = "$SCHEME://$PROJECT_HOST"

    /** Navigation 最终生成的 pattern，仅用于文档与测试断言。 */
    const val PROJECT_PATTERN = "$PROJECT_BASE_PATH/{novelId}"

    const val PLAYER_HOST = "player"

    /** 播放器深链的完整 URI（无路径、无参数）。 */
    const val PLAYER_URI = "$SCHEME://$PLAYER_HOST"

    /** 播放器深链 pattern；当前与 [PLAYER_URI] 相同。 */
    const val PLAYER_PATTERN = PLAYER_URI

    /** 生成一个项目详情深链，例如 `audioforge://project/42`。 */
    fun projectDetailUri(novelId: Long): String = "$PROJECT_BASE_PATH/$novelId"

    /** 生成播放器深链，例如 `audioforge://player`。 */
    fun playerUri(): String = PLAYER_URI

    /**
     * 解析深链。只接受：
     * - 恰好一个正整数 `novelId` 的 project URI；
     * - 不带路径/query/fragment 的 player URI。
     *
     * 其它形式（未知 host、非数字、0/负数、多余路径、query/fragment）都返回 null。
     */
    fun parse(uri: String): Route? {
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
        if (!SCHEME.equals(parsed.scheme, ignoreCase = true)) return null
        if (parsed.rawQuery != null || parsed.rawFragment != null) return null

        val host = parsed.host ?: return null
        return when {
            PLAYER_HOST.equals(host, ignoreCase = true) -> parsePlayer(parsed)
            PROJECT_HOST.equals(host, ignoreCase = true) -> parseProject(parsed)
            else -> null
        }
    }

    private fun parsePlayer(parsed: URI): Route? {
        // `audioforge://player` 的 path 是空串；`audioforge://player/` 或更深路径都不接受。
        if (!parsed.path.isNullOrEmpty()) return null
        return Route.Player()
    }

    private fun parseProject(parsed: URI): Route? {
        val path = parsed.path ?: return null
        if (!path.startsWith("/")) return null
        val segment = path.removePrefix("/")
        if (segment.isEmpty() || segment.contains('/')) return null

        val novelId = segment.toLongOrNull() ?: return null
        if (novelId <= 0L) return null
        return Route.ProjectDetail(novelId)
    }
}
