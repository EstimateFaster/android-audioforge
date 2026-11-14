package com.estimatefaster.audioforge.uipreview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * 预览宿主。
 *
 * 支持通过 Intent extra 直接落到某个屏幕，供 CI 在模拟器里逐屏截图：
 * `adb shell am start -n <pkg>/<activity> --es screen player --es theme dark --es font 200`
 * `--ez search true` 直接进入书架搜索态。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val screen = intent.getStringExtra("screen")
        val phase = intent.getStringExtra("phase")
        val theme = intent.getStringExtra("theme")
        val font = intent.getStringExtra("font")
        val dynamic = intent.getBooleanExtra("dynamic", true)
        val search = intent.getBooleanExtra("search", false)
        setContent {
            AppRoot(
                initialScreen = screen,
                initialPhase = phase,
                forceTheme = theme,
                forceFont = font,
                forceDynamicColor = dynamic,
                initialSearch = search,
            )
        }
    }
}
