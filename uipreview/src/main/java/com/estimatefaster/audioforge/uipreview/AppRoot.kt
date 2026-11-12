package com.estimatefaster.audioforge.uipreview

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.estimatefaster.audioforge.uipreview.data.PlayerState
import com.estimatefaster.audioforge.uipreview.lab.LocalPreviewSettings
import com.estimatefaster.audioforge.uipreview.lab.PreviewSettings
import com.estimatefaster.audioforge.uipreview.nav.Dest
import com.estimatefaster.audioforge.uipreview.nav.Navigator
import com.estimatefaster.audioforge.uipreview.nav.ScreenHost
import com.estimatefaster.audioforge.uipreview.screens.ChaptersScreen
import com.estimatefaster.audioforge.uipreview.screens.DesignLabScreen
import com.estimatefaster.audioforge.uipreview.screens.LibraryScreen
import com.estimatefaster.audioforge.uipreview.screens.PlayerScreen
import com.estimatefaster.audioforge.uipreview.screens.ProjectScreen
import com.estimatefaster.audioforge.uipreview.screens.ScriptScreen
import com.estimatefaster.audioforge.uipreview.screens.SettingsScreen
import com.estimatefaster.audioforge.uipreview.theme.UiPreviewTheme
import kotlinx.coroutines.delay

/**
 * 纯 UI 预览的根节点：主题 + 设计实验室状态 + 预览播放器 + 自定义返回栈。
 *
 * 这里没有任何业务依赖：数据来自 [com.estimatefaster.audioforge.uipreview.data.FakeData]，
 * 播放进度由本机 ticker 模拟。
 */
@Composable
fun AppRoot() {
    val settings = remember { PreviewSettings() }
    CompositionLocalProvider(LocalPreviewSettings provides settings) {
        UiPreviewTheme {
            val navigator = remember { Navigator() }
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
                    is Dest.Player -> PlayerScreen(navigator = navigator, player = player)
                    is Dest.Lab -> DesignLabScreen(navigator = navigator, player = player)
                }
            }
        }
    }
}
