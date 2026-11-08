package com.estimatefaster.audioforge.app

import android.content.Context
import com.estimatefaster.audioforge.core.audio.FfmpegKitRunner
import com.estimatefaster.audioforge.core.audio.FfmpegProbe
import com.estimatefaster.audioforge.core.storage.AudioStorage
import com.estimatefaster.audioforge.core.voice.PromptAssets
import com.estimatefaster.audioforge.core.voice.PromptRenderer
import com.estimatefaster.audioforge.core.voice.VoicePool
import com.estimatefaster.audioforge.core.voice.VoicePoolProvider
import com.estimatefaster.audioforge.data.db.AppDatabase
import com.estimatefaster.audioforge.data.db.RoomProbe
import com.estimatefaster.audioforge.data.repository.ChapterRepository
import com.estimatefaster.audioforge.data.repository.CharacterRepository
import com.estimatefaster.audioforge.data.repository.JobRepository
import com.estimatefaster.audioforge.data.repository.ProjectRepository
import com.estimatefaster.audioforge.data.repository.SegmentRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 手写 DI 容器（ADR-010 / P2-02）。
 *
 * 所有依赖都是 `by lazy`：构造容器不会建库、读 asset 或起线程，首次使用时才初始化。
 * UI/ViewModel 只能拿仓库与 core 服务，禁止直接持有 DAO。
 *
 * Phase 0 的 `roomProbe` / `ffmpegProbe` 与 `start*` 仍被首页调试卡引用，暂时保留。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probeRunning = AtomicBoolean(false)
    private val ffmpegProbeRunning = AtomicBoolean(false)

    val database: AppDatabase by lazy { AppDatabase.create(appContext) }

    val audioStorage: AudioStorage by lazy { AudioStorage(appContext.filesDir) }

    val voicePool: VoicePool by lazy { VoicePoolProvider.get(appContext.assets) }

    val promptAssets: PromptAssets by lazy { PromptAssets(appContext.assets) }

    val promptRenderer: PromptRenderer by lazy { PromptRenderer(promptAssets.templates) }

    val projectRepository: ProjectRepository by lazy {
        ProjectRepository(database, audioStorage)
    }

    val chapterRepository: ChapterRepository by lazy { ChapterRepository(database) }

    val characterRepository: CharacterRepository by lazy { CharacterRepository(database) }

    val segmentRepository: SegmentRepository by lazy { SegmentRepository(database) }

    val jobRepository: JobRepository by lazy { JobRepository(database) }

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
