package com.estimatefaster.audioforge.ui.navigation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 导航栏/侧边栏图标。项目不引入 material-icons-extended，图标在本地用 ImageVector 绘制。 */
enum class AppIcon {
    Projects,
    Tasks,
    Settings,
}

internal val AppIcon.vector: ImageVector
    get() = when (this) {
        AppIcon.Projects -> ProjectsVector
        AppIcon.Tasks -> TasksVector
        AppIcon.Settings -> SettingsVector
    }

private val ProjectsVector: ImageVector = buildIcon("nav_projects") {
    // 文件夹：左上书脊 + 主体
    moveTo(10f, 4f)
    lineTo(4f, 4f)
    lineTo(4f, 20f)
    lineTo(20f, 20f)
    lineTo(20f, 8f)
    lineTo(10f, 8f)
    close()
}

private val TasksVector: ImageVector = buildIcon("nav_tasks") {
    // 三行清单：左侧方块 + 右侧长条
    moveTo(3f, 4.5f)
    lineTo(6f, 4.5f)
    lineTo(6f, 7.5f)
    lineTo(3f, 7.5f)
    close()
    moveTo(8f, 5f)
    lineTo(21f, 5f)
    lineTo(21f, 7f)
    lineTo(8f, 7f)
    close()

    moveTo(3f, 10.5f)
    lineTo(6f, 10.5f)
    lineTo(6f, 13.5f)
    lineTo(3f, 13.5f)
    close()
    moveTo(8f, 11f)
    lineTo(21f, 11f)
    lineTo(21f, 13f)
    lineTo(8f, 13f)
    close()

    moveTo(3f, 16.5f)
    lineTo(6f, 16.5f)
    lineTo(6f, 19.5f)
    lineTo(3f, 19.5f)
    close()
    moveTo(8f, 17f)
    lineTo(21f, 17f)
    lineTo(21f, 19f)
    lineTo(8f, 19f)
    close()
}

private val SettingsVector: ImageVector = buildIcon("nav_settings") {
    // 三条滑杆 + 滑块（tune 风格，避免复杂齿轮路径）
    moveTo(3f, 5f)
    lineTo(21f, 5f)
    lineTo(21f, 7f)
    lineTo(3f, 7f)
    close()
    moveTo(7f, 3f)
    lineTo(11f, 3f)
    lineTo(11f, 9f)
    lineTo(7f, 9f)
    close()

    moveTo(3f, 11f)
    lineTo(21f, 11f)
    lineTo(21f, 13f)
    lineTo(3f, 13f)
    close()
    moveTo(14f, 9f)
    lineTo(18f, 9f)
    lineTo(18f, 15f)
    lineTo(14f, 15f)
    close()

    moveTo(3f, 17f)
    lineTo(21f, 17f)
    lineTo(21f, 19f)
    lineTo(3f, 19f)
    close()
    moveTo(9f, 15f)
    lineTo(13f, 15f)
    lineTo(13f, 21f)
    lineTo(9f, 21f)
    close()
}

private fun buildIcon(name: String, pathBuilder: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            pathBuilder.invoke(this)
        }
    }.build()
