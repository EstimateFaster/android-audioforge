package com.estimatefaster.audioforge.app

import android.content.Context
import com.estimatefaster.audioforge.core.audio.FfmpegKitRunner
import com.estimatefaster.audioforge.core.audio.FfmpegProbe
import com.estimatefaster.audioforge.data.db.AppDatabase
import com.estimatefaster.audioforge.data.db.RoomProbe
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 手写 DI 容器（ADR-010）。Phase 0 只挂 Room 与 FFmpeg 探针；P2 再挂仓库、HTTP 客户端与用例。
 * 不要在 Composable 里直接 new 数据库。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probeRunning = AtomicBoolean(false)
    private val ffmpegProbeRunning = AtomicBoolean(false)

    val database: AppDatabase by lazy { AppDatabase.create(appContext) }

    val roomProbe: RoomProbe by lazy { RoomProbe(database.probeDao()) }

    val ffmpegRunner: FfmpegKitRunner by lazy { FfmpegKitRunner() }

    val ffmpegProbe: FfmpegProbe by lazy { FfmpegProbe(ffmpegRunner, appContext.cacheDir) }

    /** 幂等触发：探针未结束前重复点击直接忽略。 */
    fun startRoomProbe() {
        if (!probeRunning.compareAndSet(false, true)) return
        appScope.launch {
            try {
                roomProbe.run()
            } finally {
                probeRunning.set(false)
            }
        }
    }

    fun startFfmpegProbe() {
        if (!ffmpegProbeRunning.compareAndSet(false, true)) return
        appScope.launch {
            try {
                ffmpegProbe.run()
            } finally {
                ffmpegProbeRunning.set(false)
            }
        }
    }
}
