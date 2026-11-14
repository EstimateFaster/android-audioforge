package com.estimatefaster.audioforge.uipreview

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.estimatefaster.audioforge.uipreview.data.PlayerState
import com.estimatefaster.audioforge.uipreview.lab.FontScaleOption
import com.estimatefaster.audioforge.uipreview.lab.LocalPreviewSettings
import com.estimatefaster.audioforge.uipreview.lab.PreviewSettings
import com.estimatefaster.audioforge.uipreview.lab.ScreenPhase
import com.estimatefaster.audioforge.uipreview.lab.ThemeMode
import com.estimatefaster.audioforge.uipreview.nav.Dest
import com.estimatefaster.audioforge.uipreview.nav.Navigator
import com.estimatefaster.audioforge.uipreview.nav.ScreenHost
import com.estimatefaster.audioforge.uipreview.screens.AboutScreen
import com.estimatefaster.audioforge.uipreview.screens.ChaptersScreen
import com.estimatefaster.audioforge.uipreview.screens.CharactersScreen
import com.estimatefaster.audioforge.uipreview.screens.DesignLabScreen
import com.estimatefaster.audioforge.uipreview.screens.GenerationScreen
import com.estimatefaster.audioforge.uipreview.screens.LibraryScreen
import com.estimatefaster.audioforge.uipreview.screens.ModelConfigScreen
import com.estimatefaster.audioforge.uipreview.screens.NetworkScreen
import com.estimatefaster.audioforge.uipreview.screens.OnboardingScreen
import com.estimatefaster.audioforge.uipreview.screens.PlayerScreen
import com.estimatefaster.audioforge.uipreview.screens.ProjectScreen
import com.estimatefaster.audioforge.uipreview.screens.ScriptScreen
import com.estimatefaster.audioforge.uipreview.screens.SettingsScreen
import com.estimatefaster.audioforge.uipreview.screens.StorageScreen
import com.estimatefaster.audioforge.uipreview.screens.TasksScreen
import com.estimatefaster.audioforge.uipreview.theme.UiPreviewTheme
import kotlinx.coroutines.delay

private fun startStackFor(screen: String?): List<Dest> = when (screen) {
    "project" -> listOf(Dest.Library, Dest.Project(1L))
    "chapters" -> listOf(Dest.Library, Dest.Project(1L), Dest.Chapters(1L))
    "script" -> listOf(Dest.Library, Dest.Project(1L), Dest.Chapters(1L), Dest.Script(1L, 101L))
    "characters" -> listOf(Dest.Library, Dest.Project(1L), Dest.Characters(1L))
    "player" -> listOf(Dest.Library, Dest.Player)
    "tasks" -> listOf(Dest.Library, Dest.Tasks)
    "onboarding" -> listOf(Dest.Library, Dest.Onboarding)
    "storage" -> listOf(Dest.Settings, Dest.Storage)
    "about" -> listOf(Dest.Settings, Dest.About)
    "model" -> listOf(Dest.Settings, Dest.ModelConfig)
    "generation" -> listOf(Dest.Settings, Dest.Generation)
    "network" -> listOf(Dest.Settings, Dest.Network)
    "settings" -> listOf(Dest.Settings)
    "lab" -> listOf(Dest.Settings, Dest.Lab)
    else -> listOf(Dest.Library)
}

/**
 * 纯 UI 预览的根节点：主题 + 设计实验室状态 + 预览播放器 + 自定义返回栈。
 */
@Composable
fun AppRoot(
    initialScreen: String? = null,
    initialPhase: String? = null,
    forceTheme: String? = null,
    forceFont: String? = null,
    forceDynamicColor: Boolean = true,
) {
    val settings = remember {
        PreviewSettings().apply {
            dynamicColor = forceDynamicColor
            themeMode = when (forceTheme) {
                "light" -> ThemeMode.Light
                "dark" -> ThemeMode.Dark
                else -> themeMode
            }
            fontScale = when (forceFont) {
                "130" -> FontScaleOption.S130
                "200" -> FontScaleOption.S200
                else -> fontScale
            }
            if (initialScreen != null && initialPhase != null) {
                val parsed = when (initialPhase) {
                    "loading" -> ScreenPhase.Loading
                    "empty" -> ScreenPhase.Empty
                    "error" -> ScreenPhase.Error
                    else -> ScreenPhase.Normal
                }
                setPhase(initialScreen, parsed)
            }
        }
    }
    CompositionLocalProvider(LocalPreviewSettings provides settings) {
        UiPreviewTheme {
            val navigator = remember { Navigator(startStackFor(initialScreen)) }
            val player = remember { PlayerState() }

            LaunchedEffect(player.isPlaying) {
                while (player.isPlaying) {
                    delay(250)
                    player.tick(0.25f)
                }
            }

            BackHandler(enabled = navigator.canGoBack) { navigator.pop() }

            ScreenHost(navigator = navigator) { dest ->
                when (dest) {
                    is Dest.Library -> LibraryScreen(navigator = navigator, player = player)
                    is Dest.Settings -> SettingsScreen(navigator = navigator, player = player)
                    is Dest.Project -> ProjectScreen(navigator = navigator, player = player, bookId = dest.id)
                    is Dest.Chapters -> ChaptersScreen(navigator = navigator, player = player, bookId = dest.id)
                    is Dest.Script -> ScriptScreen(
                        navigator = navigator,
                        player = player,
                        bookId = dest.bookId,
                        chapterId = dest.chapterId,
                    )
                    is Dest.Characters -> CharactersScreen(navigator = navigator, player = player, bookId = dest.id)
                    is Dest.Player -> PlayerScreen(navigator = navigator, player = player)
                    is Dest.Tasks -> TasksScreen(navigator = navigator, player = player)
                    is Dest.Storage -> StorageScreen(navigator = navigator, player = player)
                    is Dest.About -> AboutScreen(navigator = navigator, player = player)
                    is Dest.ModelConfig -> ModelConfigScreen(navigator = navigator, player = player)
                    is Dest.Generation -> GenerationScreen(navigator = navigator, player = player)
                    is Dest.Network -> NetworkScreen(navigator = navigator, player = player)
                    is Dest.Onboarding -> OnboardingScreen(navigator = navigator, player = player)
                    is Dest.Lab -> DesignLabScreen(navigator = navigator, player = player)
                }
            }
        }
    }
}
