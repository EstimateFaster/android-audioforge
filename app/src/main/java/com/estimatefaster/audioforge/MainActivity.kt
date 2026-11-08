package com.estimatefaster.audioforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.ui.HomeScreen
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as AudioForgeApp).container
        setContent {
            AudioForgeTheme {
                val probeState by container.roomProbe.state.collectAsStateWithLifecycle()
                HomeScreen(
                    roomProbeState = probeState,
                    onRunRoomProbe = container::startRoomProbe,
                )
            }
        }
    }
}
