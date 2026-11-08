package com.estimatefaster.audioforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.estimatefaster.audioforge.ui.navigation.AppNavHost
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AudioForgeTheme {
                AppNavHost()
            }
        }
    }
}
