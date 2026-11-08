package com.estimatefaster.audioforge.ui.navigation

import java.net.URI

/**
 * 应用深链约定（P4-01）。
 *
 * 唯一支持的形式：`audioforge://project/{novelId}`，映射到 [Route.ProjectDetail]。
 *
 * NavHost 侧用 [PROJECT_BASE_PATH] 生成类型安全的 `navDeepLink<Route.ProjectDetail>()`，
 * 这里的 [parse] 负责纯 JVM 可测的 URI 解析；两者共用同一组常量，避免漂移。
 */
object AppDeepLink {

    const val SCHEME = "audioforge"

    const val PROJECT_HOST = "project"

    /** NavHost 里传给 `navDeepLink<Route.ProjectDetail>(basePath = ...)` 的前缀。 */
    const val PROJECT_BASE_PATH = "$SCHEME://$PROJECT_HOST"

    /** Navigation 最终生成的 pattern，仅用于文档与测试断言。 */
    const val PROJECT_PATTERN = "$PROJECT_BASE_PATH/{novelId}"

    /** 生成一个项目详情深链，例如 `audioforge://project/42`。 */
    fun projectDetailUri(novelId: Long): String = "$PROJECT_BASE_PATH/$novelId"

    /**
     * 解析深链。只接受恰好一个正整数 `novelId` 的 project URI，
     * 其它形式（未知 host、非数字、0/负数、多余路径、query/fragment）都返回 null。
     */
    fun parse(uri: String): Route? {
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
        if (!SCHEME.equals(parsed.scheme, ignoreCase = true)) return null
        if (!PROJECT_HOST.equals(parsed.host, ignoreCase = true)) return null
        if (parsed.rawQuery != null || parsed.rawFragment != null) return null

        val path = parsed.path ?: return null
        if (!path.startsWith("/")) return null
        val segment = path.removePrefix("/")
        if (segment.isEmpty() || segment.contains('/')) return null

        val novelId = segment.toLongOrNull() ?: return null
        if (novelId <= 0L) return null
        return Route.ProjectDetail(novelId)
    }
}
