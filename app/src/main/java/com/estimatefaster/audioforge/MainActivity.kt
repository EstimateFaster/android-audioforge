package com.estimatefaster.audioforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import com.estimatefaster.audioforge.ui.HomeScreen
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.work.ProbeWorker
import com.estimatefaster.audioforge.work.workInfoToSnapshot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as AudioForgeApp).container
        val appContext = applicationContext
        val workManager = WorkManager.getInstance(appContext)
        setContent {
            AudioForgeTheme {
                val probeState by container.roomProbe.state.collectAsStateWithLifecycle()
                val workInfos by ProbeWorker.observe(appContext, workManager)
                    .collectAsStateWithLifecycle(initialValue = emptyList())
                val workSnapshot = remember(workInfos) {
                    workInfoToSnapshot(workInfos.firstOrNull())
                }
                HomeScreen(
                    roomProbeState = probeState,
                    onRunRoomProbe = container::startRoomProbe,
                    probeWork = workSnapshot,
                    onStartProbeWorker = { ProbeWorker.enqueue(appContext, workManager) },
                    onStopProbeWorker = { ProbeWorker.cancel(appContext, workManager) },
                )
            }
        }
    }
}
