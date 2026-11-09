package com.estimatefaster.audioforge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.estimatefaster.audioforge.core.settings.AppSettings
import com.estimatefaster.audioforge.core.settings.ThemeMode
import com.estimatefaster.audioforge.ui.navigation.AppNavHost
import com.estimatefaster.audioforge.ui.navigation.startDestinationFor
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme

class MainActivity : ComponentActivity() {

    /** 冷启动或通知/锁屏带来的深链 Intent；在 NavHost 建图后统一处理。 */
    private val deepLinkIntent = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLinkIntent.value = intent

        val container = (application as AudioForgeApp).container

        setContent {
            val settings: AppSettings? by container.settingsRepository.settings
                .collectAsState(initial = null)
            val darkTheme = when (settings?.theme) {
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
                else -> isSystemInDarkTheme()
            }

            AudioForgeTheme(
                darkTheme = darkTheme,
                dynamicColor = settings?.dynamicColor ?: true,
            ) {
                // 初始 null 表示设置尚未读出：先显示进度，避免先闪引导页再跳项目库。
                val startDestination = startDestinationFor(settings?.onboardingCompleted)

                if (startDestination == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    val navController = rememberNavController()
                    AppNavHost(
                        container = container,
                        startDestination = startDestination,
                        navController = navController,
                    )
                    val pendingIntent = deepLinkIntent.value
                    LaunchedEffect(pendingIntent) {
                        if (pendingIntent != null) {
                            navController.handleDeepLink(pendingIntent)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLinkIntent.value = intent
    }
}
