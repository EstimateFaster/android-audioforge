package com.estimatefaster.audioforge.ui.navigation

/**
 * 首启目的地（接线 2/2）。
 *
 * [onboardingCompleted] 为 null 表示设置尚未读出：返回 null，调用方显示空白/进度，
 * 避免闪一下引导页再跳到项目库。false 进入 [Route.Onboarding]，true 进入
 * [Route.Projects]。
 *
 * 纯函数：不依赖 Android/Compose，可直接在 JVM 测试里断言。
 */
fun startDestinationFor(onboardingCompleted: Boolean?): Route? = when (onboardingCompleted) {
    null -> null
    true -> Route.Projects
    false -> Route.Onboarding
}
