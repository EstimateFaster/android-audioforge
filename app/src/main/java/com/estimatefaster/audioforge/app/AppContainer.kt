package com.estimatefaster.audioforge.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.WorkManager
import com.estimatefaster.audioforge.core.audio.ChapterStitcher
import com.estimatefaster.audioforge.core.audio.FfmpegKitRunner
import com.estimatefaster.audioforge.core.audio.FfmpegProbe
import com.estimatefaster.audioforge.core.ingest.ChapterSplitter
import com.estimatefaster.audioforge.core.ingest.TextDecoder
import com.estimatefaster.audioforge.core.network.HttpClients
import com.estimatefaster.audioforge.core.network.LlmClient
import com.estimatefaster.audioforge.core.network.LlmConfig
import com.estimatefaster.audioforge.core.settings.AppSettings
import com.estimatefaster.audioforge.core.settings.SecureKeyStore
import com.estimatefaster.audioforge.core.settings.SettingsRepository
import com.estimatefaster.audioforge.core.storage.AudioStorage
import com.estimatefaster.audioforge.core.storage.ExportManager
import com.estimatefaster.audioforge.core.storage.StorageMaintenance
import com.estimatefaster.audioforge.core.tts.AudioPostProcessor
import com.estimatefaster.audioforge.core.tts.FishTtsClient
import com.estimatefaster.audioforge.core.tts.FishTtsConfig
import com.estimatefaster.audioforge.core.tts.FishTtsTransport
import com.estimatefaster.audioforge.core.tts.TtsSynthesizer
import com.estimatefaster.audioforge.core.tts.TtsTransport
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
import com.estimatefaster.audioforge.data.repository.TuningRepository
import com.estimatefaster.audioforge.domain.jobs.BatchRunner
import com.estimatefaster.audioforge.domain.jobs.GenerationExecutor
import com.estimatefaster.audioforge.domain.jobs.GenerationGraph
import com.estimatefaster.audioforge.domain.jobs.JobQueue
import com.estimatefaster.audioforge.domain.jobs.JobWatchdog
import com.estimatefaster.audioforge.domain.jobs.RecoveryCoordinator
import com.estimatefaster.audioforge.domain.jobs.SweepRunning
import com.estimatefaster.audioforge.domain.jobs.WorkManagerJobQueue
import com.estimatefaster.audioforge.domain.pipeline.AnalyzeChapterUseCase
import com.estimatefaster.audioforge.domain.pipeline.CharacterRegistryAdapter
import com.estimatefaster.audioforge.domain.pipeline.RenderChapterUseCase
import com.estimatefaster.audioforge.domain.usecase.ImportNovelUseCase
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 缺少 BYOK API Key 时抛出的明确错误（接线 1/2）。
 *
 * 消息里带存储 key 名（`llm_api_key` / `fish_api_key`），调用方可以直接提示用户
 * 去设置页填写；容器不会用空 Key 发请求，也不会在日志里打印 Key 本身。
 *
 * @param keyName [SecureKeyStore.get] 使用的名字，同时是 Keystore 的 AAD
 */
class MissingApiKeyException(
    val keyName: String,
) : IllegalStateException(
    "缺少 $keyName，请先在设置页填写 API Key 后再试",
)

/**
 * 手写 DI 容器（ADR-010 / P2-02 / 接线 1/2）。
 *
 * 依赖分三类：
 * - **纯对象 / 只依赖 Context 或文件的**：用 `by lazy` 持有，首次使用时才创建；
 *   构造容器不会建库、读 asset、起线程或初始化 WorkManager。
 * - **需要用户设置的**（LLM / Fish 客户端、分析与渲染用例、Tuning 仓库）：走
 *   `suspend` 访问器。原因有两个：Key 只以密文存在 [SecureKeyStore] 里，读取必须是
 *   suspend；base URL / model / 并发 / 默认音色来自设置 flow，构建时必须取最新值。
 *   它们每次调用都很便宜（共享 [HttpClients] 的连接池 / Dispatcher），因此不做缓存，
 *   避免设置变更后仍用旧配置。
 * - **任务执行图**：[generationGraph] 在容器构造时只写入一个 lambda（不解析任何
 *   重依赖），保证 [com.estimatefaster.audioforge.AudioForgeApp] 启动后 Worker 立即
 *   有线可用。
 *
 * DataStore：本类用 [preferencesDataStore] 声明进程内唯一实例，[SettingsRepository]
 * 与 [SecureKeyStore] 共用它。**生产路径不要再调用** `SettingsRepository.create`
 * （它内部有自己的同名委托），否则同一文件会出现两个 DataStore 而抛异常。
 *
 * UI / ViewModel 只能拿仓库、用例与 core 服务，禁止直接持有 DAO。
 * Phase 0 的 `roomProbe` / `ffmpegProbe` 与 `start*` 仍被首页调试卡引用，保持不变。
 */
class AppContainer(context: Context) {

    /** App 级 Context；不保留 Activity，避免泄漏。 */
    internal val appContext: Context = context.applicationContext

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probeRunning = AtomicBoolean(false)
    private val ffmpegProbeRunning = AtomicBoolean(false)

    // ------------------------------------------------------------------
    // 设置 / Key（同一个 DataStore）
    // ------------------------------------------------------------------

    /** 进程内唯一的 Preferences DataStore，文件名与 §7.4 一致。 */
    val dataStore: DataStore<Preferences> by lazy { appContext.audioForgeSettingsDataStore }

    /**
     * 设置仓库：键名与默认值由 core 层定义；保存前用音色池校验类别。
     *
     * 与 [SettingsRepository.create] 等价，但复用 [dataStore]，保证 Key 与普通设置
     * 在同一个文件里（[SecureKeyStore] 只写 `secure_` 前缀的键）。
     */
    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(
            dataStore = dataStore,
            isValidVoiceCategory = { category ->
                // 资产异常时放行，与 SettingsRepository.create 的行为保持一致。
                runCatching { voicePool.valid(category) }.getOrDefault(true)
            },
        )
    }

    /** LLM / Fish Key 的安全存取；只做加解密，不打印明文。 */
    val secureKeyStore: SecureKeyStore by lazy { SecureKeyStore.create(appContext, dataStore) }

    // ------------------------------------------------------------------
    // 存储 / 语音资产 / 数据库（P2-02 起既有）
    // ------------------------------------------------------------------

    val database: AppDatabase by lazy { AppDatabase.create(appContext) }

    val audioStorage: AudioStorage by lazy { AudioStorage(appContext.filesDir) }

    val voicePool: VoicePool by lazy { VoicePoolProvider.get(appContext.assets) }

    val promptAssets: PromptAssets by lazy { PromptAssets(appContext.assets) }

    val promptRenderer: PromptRenderer by lazy { PromptRenderer(promptAssets.templates) }

    // ------------------------------------------------------------------
    // 仓库
    // ------------------------------------------------------------------

    val projectRepository: ProjectRepository by lazy {
        ProjectRepository(database, audioStorage)
    }

    val chapterRepository: ChapterRepository by lazy { ChapterRepository(database) }

    val characterRepository: CharacterRepository by lazy { CharacterRepository(database) }

    val segmentRepository: SegmentRepository by lazy { SegmentRepository(database) }

    val jobRepository: JobRepository by lazy { JobRepository(database) }

    /**
     * 角色微调仓库：`settingsProvider` 用当前设置投影「跟随全局」时的数值，
     * 因此用户改默认语速/音量后不需要重新创建容器。
     */
    val tuningRepository: TuningRepository by lazy {
        TuningRepository(
            db = database,
            settingsProvider = { currentSettings() },
        )
    }

    val roomProbe: RoomProbe by lazy { RoomProbe(database.probeDao()) }

    val ffmpegRunner: FfmpegKitRunner by lazy { FfmpegKitRunner() }

    val ffmpegProbe: FfmpegProbe by lazy { FfmpegProbe(ffmpegRunner, appContext.cacheDir) }

    // ------------------------------------------------------------------
    // 导出 / 存储维护 / 导入
    // ------------------------------------------------------------------

    /** 章节成品导出与分享（MediaStore / FileProvider，P2-15）。 */
    val exportManager: ExportManager by lazy { ExportManager(appContext, audioStorage) }

    /**
     * 存储扫描/清理；保留期用 core 的默认值。StorageViewModel 会按 UI 当前保留期
     * 重新构造轻量实例，这里保留一份是给诊断与维护任务复用。
     */
    val storageMaintenance: StorageMaintenance by lazy { StorageMaintenance(audioStorage) }

    /** txt 导入（P2-06）：解码 + 切章 + 单事务落库。 */
    val importNovelUseCase: ImportNovelUseCase by lazy {
        ImportNovelUseCase(
            projects = projectRepository,
            audioStorage = audioStorage,
            textDecoder = TextDecoder(),
            chapterSplitter = ChapterSplitter(),
        )
    }

    // ------------------------------------------------------------------
    // TTS / 后处理
    // ------------------------------------------------------------------

    /** ffmpeg 音调/音量后处理（§10.2）；不依赖 Key。 */
    val audioPostProcessor: AudioPostProcessor by lazy { AudioPostProcessor(ffmpegRunner) }

    /** 章节拼接器；静音缓存放在 cache 目录下，随系统清理。 */
    val chapterStitcher: ChapterStitcher by lazy {
        ChapterStitcher(ffmpegRunner, File(appContext.cacheDir, STITCH_CACHE_DIR))
    }

    /**
     * Fish Audio 客户端：base URL / model 取当前设置，Key 取 [secureKeyStore]。
     *
     * @throws MissingApiKeyException 未配置 `fish_api_key`
     */
    suspend fun fishTtsClient(): FishTtsClient {
        val settings = currentSettings()
        return FishTtsClient(
            config = FishTtsConfig(
                baseUrl = settings.fishBaseUrl,
                model = settings.fishTtsModel,
                apiKey = requireApiKey(SecureKeyStore.FISH_API_KEY),
            ),
            httpClient = HttpClients.defaultTts(),
        )
    }

    /** 生产 TTS 传输层；与 [fishTtsClient] 一起按需创建。 */
    suspend fun ttsTransport(): TtsTransport = FishTtsTransport(fishTtsClient())

    /** 生产合成器：缓存键/原子写/后处理由 core 负责。 */
    suspend fun ttsSynthesizer(): TtsSynthesizer = TtsSynthesizer(
        transport = ttsTransport(),
        postProcessor = audioPostProcessor,
        storage = audioStorage,
    )

    // ------------------------------------------------------------------
    // LLM / 管线用例
    // ------------------------------------------------------------------

    /**
     * OpenAI 兼容 LLM 客户端：base URL / model 取当前设置，Key 取 [secureKeyStore]。
     *
     * @throws MissingApiKeyException 未配置 `llm_api_key`
     */
    suspend fun llmClient(): LlmClient {
        val settings = currentSettings()
        return LlmClient(
            config = LlmConfig(
                baseUrl = settings.llmBaseUrl,
                model = settings.llmModel,
                apiKey = requireApiKey(SecureKeyStore.LLM_API_KEY),
            ),
            httpClient = HttpClients.defaultLlm(),
        )
    }

    /**
     * 解析单章用例。
     *
     * **必须按书创建**：`CharacterRegistryAdapter` 在构造时绑定 novelId，一个实例
     * 不能同时服务多本书，所以它不是容器级单例，而是按 novelId 现建（内部只有
     * 轻量对象与一次按需 refresh）。
     *
     * @throws MissingApiKeyException 未配置 `llm_api_key`
     */
    suspend fun analyzeChapterUseCase(novelId: Long): AnalyzeChapterUseCase {
        require(novelId > 0L) { "novelId 必须为正数：$novelId" }
        return analyzeChapterUseCase(novelId, currentSettings())
    }

    /**
     * 渲染单章用例：ttsConcurrency / 默认语速音量音调 / 静音间隔 / 默认音色都取
     * 当前设置；TTS 客户端同样在调用时按最新 Key 构建。
     *
     * @throws MissingApiKeyException 未配置 `fish_api_key`
     * @throws IllegalArgumentException 音色池缺少默认音色（资产损坏）
     */
    suspend fun renderChapterUseCase(): RenderChapterUseCase {
        val settings = currentSettings()
        return RenderChapterUseCase(
            segmentRepository = segmentRepository,
            chapterRepository = chapterRepository,
            db = database,
            audioStorage = audioStorage,
            ttsSynthesizer = ttsSynthesizer(),
            chapterStitcher = chapterStitcher,
            voicePool = voicePool,
            characterRepository = characterRepository,
            ttsConcurrency = settings.ttsConcurrency,
            defaultSpeed = settings.defaultSpeed,
            defaultVolume = settings.defaultVolume,
            defaultPitch = settings.defaultPitch,
            ttsModel = settings.fishTtsModel,
            narratorSilenceMs = settings.narratorSilenceMs,
            paragraphSilenceMs = settings.paragraphSilenceMs,
            defaultVoiceCategory = settings.defaultVoiceCategory,
        )
    }

    private suspend fun analyzeChapterUseCase(
        novelId: Long,
        settings: AppSettings,
    ): AnalyzeChapterUseCase = AnalyzeChapterUseCase(
        db = database,
        chapterRepository = chapterRepository,
        segmentRepository = segmentRepository,
        llmClient = llmClient(),
        registryAdapter = CharacterRegistryAdapter(
            db = database,
            repository = characterRepository,
            voicePool = voicePool,
            novelId = novelId,
        ),
        voicePool = voicePool,
        promptRenderer = promptRenderer,
        segmentMaxChars = settings.segmentMaxChars,
        minCoverage = settings.minCoverage,
    )

    // ------------------------------------------------------------------
    // 任务队列 / 恢复 / 看门狗
    // ------------------------------------------------------------------

    /** WorkManager 队列（唯一任务名 `generation-<novelId>`）。首次访问才初始化 WorkManager。 */
    val jobQueue: JobQueue by lazy { WorkManagerJobQueue.create(appContext) }

    /** 批次启停入口；UI 通过它入队，不直接碰 WorkManager。 */
    val batchRunner: BatchRunner by lazy {
        BatchRunner(queue = jobQueue, jobs = jobRepository, db = database)
    }

    /** 启动/批次后清理残留 running。 */
    val sweepRunning: SweepRunning by lazy {
        SweepRunning(
            jobs = jobRepository,
            jobDao = database.jobDao(),
            queue = jobQueue,
            workManager = WorkManager.getInstance(appContext),
            knownNovelIds = { knownNovelIds() },
        )
    }

    /** App 启动恢复：先 sweep，再重排 pending/queued。 */
    val recoveryCoordinator: RecoveryCoordinator by lazy {
        RecoveryCoordinator(
            sweep = sweepRunning,
            jobs = jobRepository,
            queue = jobQueue,
            knownNovelIds = { knownNovelIds() },
        )
    }

    /** 内存态超时看门狗（进程内有效；重启由 sweep 兜底）。 */
    val jobWatchdog: JobWatchdog by lazy { JobWatchdog() }

    /**
     * 生成执行图句柄。`executorFactory` 在 init 中装配成 `{ executorFor() }`：
     * Worker 拿到的 Context 就是本容器的 Application，直接复用容器即可，
     * 不需要再做类型转换。
     */
    val generationGraph: GenerationGraph = GenerationGraph

    init {
        // 只写入一个 lambda：不建库/不读 Key/不建网络客户端，不阻塞 Application 启动。
        generationGraph.executorFactory = { executorFor() }
    }

    /** 读取一次当前设置（DataStore 已缓存在内存，读取代价很低）。 */
    private suspend fun currentSettings(): AppSettings = settingsRepository.settings.first()

    private suspend fun requireApiKey(name: String): String =
        secureKeyStore.get(name)?.takeIf { it.isNotBlank() } ?: throw MissingApiKeyException(name)

    private suspend fun knownNovelIds(): List<Long> =
        projectRepository.listProjects().map { it.id }

    /**
     * 装配 `GenerationWorker` 用的执行器。
     *
     * 分析/渲染都在 Worker 协程里创建（suspend 访问器），因此 Key 与设置总是取最新值；
     * 排除角色来自设置 `excluded_characters`（空白或逗号分隔），命中者不注册角色、
     * 台词降级旁白。
     */
    private fun executorFor(): GenerationExecutor = GenerationExecutor(
        chapters = chapterRepository,
        jobs = jobRepository,
        analyzeChapter = { novelId, chapterId, force ->
            val settings = currentSettings()
            analyzeChapterUseCase(novelId, settings).analyze(
                novelId = novelId,
                chapterId = chapterId,
                force = force,
                excluded = excludedCharacters(settings),
            )
        },
        renderChapter = { novelId, chapterId, shouldStop ->
            renderChapterUseCase().render(
                novelId = novelId,
                chapterId = chapterId,
                shouldStop = shouldStop,
            )
        },
    )

    /**
     * 当前仍被 DB 引用的音频相对路径（`segments.audioRelPath`）。
     *
     * 存储清理用它保护「还在用的音频」，**不能**返回空集合，否则孤儿清理会误删
     * 已生成片段。当前仓库层没有跨书投影，这里按项目 → 章节分页 → 片段聚合；
     * 接线 2 应改成 `SegmentRepository` 上的单条查询。
     *
     * 只在用户手动扫描/清理存储时调用，且切到 IO 线程。
     */
    suspend fun referencedAudioPaths(): Set<String> = withContext(Dispatchers.IO) {
        val references = LinkedHashSet<String>()
        projectRepository.listProjects().forEach { novel ->
            var offset = 0
            while (true) {
                val page = chapterRepository.pageBriefs(
                    novelId = novel.id,
                    limit = REFERENCE_PAGE_SIZE,
                    offset = offset,
                )
                page.forEach { brief ->
                    segmentRepository.list(brief.id).forEach { segment ->
                        val path = segment.audioRelPath
                        if (!path.isNullOrBlank()) references.add(path)
                    }
                }
                if (page.size < REFERENCE_PAGE_SIZE) break
                offset += page.size
            }
        }
        references
    }

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

    private companion object {
        /** 静音缓存子目录（相对 `cacheDir`）。 */
        const val STITCH_CACHE_DIR = "stitch"

        /** 聚合音频引用时的章节分页大小。 */
        const val REFERENCE_PAGE_SIZE = 200

        /** 设置里的排除角色：空白或中英文逗号分隔。 */
        fun excludedCharacters(settings: AppSettings): Set<String> =
            settings.excludedCharacters
                .split(' ', '\n', '\t', ',', '，')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
    }
}

/**
 * 进程内唯一的设置 DataStore（文件名严格等于 §7.4 的 `audioforge_settings`）。
 *
 * 只在本文件声明；[AppContainer] 与 [SettingsRepository]、[SecureKeyStore] 共用它。
 * 注意不要与 `SettingsRepository.create` 同时使用（同文件只能有一个 DataStore 实例）。
 */
private val Context.audioForgeSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SettingsRepository.DATASTORE_NAME,
)
