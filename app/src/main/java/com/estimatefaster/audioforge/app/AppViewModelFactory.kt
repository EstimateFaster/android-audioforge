package com.estimatefaster.audioforge.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.data.db.SegmentEntity
import com.estimatefaster.audioforge.feature.about.AboutViewModel
import com.estimatefaster.audioforge.feature.characters.CharactersViewModel
import com.estimatefaster.audioforge.feature.characters.VoicePickerViewModel
import com.estimatefaster.audioforge.feature.export.ExportManagerGateway
import com.estimatefaster.audioforge.feature.export.ExportViewModel
import com.estimatefaster.audioforge.feature.onboarding.OnboardingViewModel
import com.estimatefaster.audioforge.feature.player.PlayerContentSource
import com.estimatefaster.audioforge.feature.player.PlayerGateway
import com.estimatefaster.audioforge.feature.player.PlayerViewModel
import com.estimatefaster.audioforge.feature.project.BatchChapterUi
import com.estimatefaster.audioforge.feature.project.BatchViewModel
import com.estimatefaster.audioforge.feature.project.ChapterListViewModel
import com.estimatefaster.audioforge.feature.project.ProjectViewModel
import com.estimatefaster.audioforge.feature.project.TuningViewModel
import com.estimatefaster.audioforge.feature.projects.ImportViewModel
import com.estimatefaster.audioforge.feature.projects.ProjectLibraryViewModel
import com.estimatefaster.audioforge.feature.script.RoomSegmentEditor
import com.estimatefaster.audioforge.feature.script.ScriptViewModel
import com.estimatefaster.audioforge.feature.script.SegmentEditViewModel
import com.estimatefaster.audioforge.feature.settings.SettingsViewModel
import com.estimatefaster.audioforge.feature.storage.StorageViewModel
import com.estimatefaster.audioforge.feature.tasks.TasksViewModel
import kotlinx.coroutines.CoroutineScope

/**
 * 需要导航参数时使用的 [CreationExtras] key（接线 1/2）。
 *
 * 用法：`viewModel(factory = appViewModelFactory, extras = MutableCreationExtras().apply {
 * this[AppViewModelKeys.NOVEL_ID] = novelId })`。key 用对象身份比较，不要拿同名的
 * 其它对象代替；缺失时工厂会抛带字段名的 [IllegalArgumentException]，不会创建半成品。
 */
object AppViewModelKeys {

    /** 项目 id（`novelId`），所有项目内页面都需要。 */
    val NOVEL_ID: CreationExtras.Key<Long> = object : CreationExtras.Key<Long> {}

    /** 章节 id（`chapterId`）。 */
    val CHAPTER_ID: CreationExtras.Key<Long> = object : CreationExtras.Key<Long> {}

    /** 章节标题；只影响脚本页顶栏展示，缺省空串。 */
    val CHAPTER_TITLE: CreationExtras.Key<String> = object : CreationExtras.Key<String> {}

    /** 角色 roleId（`narrator` / `char_0001` 这类稳定 id）。 */
    val ROLE_ID: CreationExtras.Key<String> = object : CreationExtras.Key<String> {}

    /** 导出页默认选中的 0 基章节序号；缺省表示不预选。 */
    val CHAPTER_INDEX: CreationExtras.Key<Int> = object : CreationExtras.Key<Int> {}
}

/**
 * 手写 ViewModel 工厂（P2-02 起，接线 1/2 补全）。
 *
 * 注册表是 `Class -> (CreationExtras) -> ViewModel`，两种用法：
 * - **无导航参数**的页面（项目库、任务、设置、存储、关于、导入、引导）已注册，
 *   导航层直接 `viewModel<XxxViewModel>(factory = factory)` 即可，全部依赖来自
 *   [AppContainer]，不会碰网络/WorkManager（除非该 VM 自己需要）。
 * - **需要参数**的页面分两类：
 *   1. 参数是标量（novelId / chapterId / roleId / chapterIdx）的已注册，必须通过
 *      [CreationExtras] 传参，key 见 [AppViewModelKeys]；
 *   2. 参数是实体或列表（VoicePicker 的 `CharacterEntity`、SegmentEdit 的
 *      `SegmentEntity + characters`、Batch 的 `List<BatchChapterUi>`）以及
 *      [PlayerViewModel]（需要播放器门面与内容源），注册表无法凭空产生这些值，
 *      请在导航层 `viewModel { }` 里调用对应的工厂函数（[voicePicker]、[segmentEdit]、
 *      [batch]、[player]）构造。
 *
 * 工厂函数与注册表共用同一套构造逻辑，避免两处参数写歪。
 *
 * `register` 支持运行时追加/覆盖（例如测试或后续拆模块），返回 `this` 便于链式调用。
 * 未注册的类明确抛 [IllegalArgumentException]，不静默创建错对象。
 */
class AppViewModelFactory(
    internal val container: AppContainer,
    extraCreators: Map<Class<*>, (CreationExtras) -> ViewModel> = emptyMap(),
) : ViewModelProvider.Factory {

    /**
     * 可变的注册表：先装默认表，再用调用方传入的表覆盖（便于测试替换）。
     * 只保存 lambda，构造工厂不会创建任何 ViewModel 或容器依赖。
     */
    private val creators: MutableMap<Class<*>, (CreationExtras) -> ViewModel> =
        LinkedHashMap<Class<*>, (CreationExtras) -> ViewModel>().apply {
            putAll(defaultCreators())
            putAll(extraCreators)
        }

    /**
     * 追加或替换一个注册项。
     *
     * @param modelClass 目标 ViewModel 类
     * @param creator 从 [CreationExtras] 构造实例；不需要 extras 时忽略参数即可
     * @return `this`，便于连续注册
     */
    fun register(
        modelClass: Class<out ViewModel>,
        creator: (CreationExtras) -> ViewModel,
    ): AppViewModelFactory {
        creators[modelClass] = creator
        return this
    }

    /** 无 extras 的默认入口；等价于传 [CreationExtras.Empty]。 */
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        create(modelClass, CreationExtras.Empty)

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val creator = creators[modelClass]
            ?: creators.entries.firstOrNull { (key, _) -> key.isAssignableFrom(modelClass) }?.value
            ?: throw IllegalArgumentException(
                "未注册的 ViewModel：${modelClass.name}；" +
                    "请在 AppViewModelFactory 注册，或用工厂函数在 viewModel { } 中构造",
            )
        return creator(extras) as T
    }

    // ------------------------------------------------------------------
    // 带参数 ViewModel 的工厂函数（导航层 viewModel { } 使用）
    // ------------------------------------------------------------------

    /** 项目详情页；章节/角色都走仓库 Flow。 */
    fun project(novelId: Long): ProjectViewModel = ProjectViewModel(
        novelId = novelId,
        projectRepository = container.projectRepository,
        chapterRepository = container.chapterRepository,
        characterRepository = container.characterRepository,
    )

    /** 章节列表（分页/搜索都在仓库侧）。 */
    fun chapterList(novelId: Long): ChapterListViewModel =
        ChapterListViewModel(novelId, container.chapterRepository)

    /** 角色管理页；音色标签直接用音色池解析。 */
    fun characters(novelId: Long): CharactersViewModel = CharactersViewModel(
        novelId = novelId,
        characterRepository = container.characterRepository,
        voicePool = container.voicePool,
    )

    /** 剧本页；标题只用于展示，缺省空串。 */
    fun script(
        chapterId: Long,
        novelId: Long,
        chapterTitle: String = "",
    ): ScriptViewModel = ScriptViewModel(
        chapterId = chapterId,
        novelId = novelId,
        chapterTitle = chapterTitle,
        segmentRepository = container.segmentRepository,
        characterRepository = container.characterRepository,
    )

    /** 角色调参页；`voiceLabelFor` 用音色池把类别翻译成中文标签。 */
    fun tuning(novelId: Long, roleId: String): TuningViewModel = TuningViewModel(
        novelId = novelId,
        roleId = roleId,
        tuningRepository = container.tuningRepository,
        characterRepository = container.characterRepository,
        voiceLabelFor = container.voicePool::labelFor,
    )

    /** 导出页；`currentChapterIdx` 为 0 基序号，null 表示不预选。 */
    fun export(novelId: Long, currentChapterIdx: Int? = null): ExportViewModel = ExportViewModel(
        novelId = novelId,
        gateway = ExportManagerGateway(
            exportManager = container.exportManager,
            chapterRepository = container.chapterRepository,
            audioStorage = container.audioStorage,
        ),
        currentChapterIdx = currentChapterIdx,
    )

    /**
     * 音色选择 Sheet；从角色实体与音色池构造，保存走 `RoomVoiceAssigner`。
     *
     * 需要角色实体，因此只能由导航层在拿到 `CharacterEntity` 后调用。
     */
    fun voicePicker(
        novelId: Long,
        role: CharacterEntity,
        externalScope: CoroutineScope? = null,
    ): VoicePickerViewModel = VoicePickerViewModel(
        novelId = novelId,
        role = role,
        voicePool = container.voicePool,
        repository = container.characterRepository,
        database = container.database,
        externalScope = externalScope,
    )

    /**
     * 片段编辑 Sheet；需要片段实体与当前角色列表，因此只能由导航层调用。
     *
     * 写入走 `RoomSegmentEditor`（`segments` 表），与剧本页共用同一份数据。
     */
    fun segmentEdit(
        segment: SegmentEntity,
        characters: List<CharacterEntity>,
    ): SegmentEditViewModel = SegmentEditViewModel(
        segment = segment,
        characters = characters,
        editor = RoomSegmentEditor(container.database),
    )

    /**
     * 批量生成页；章节列表与 Key 状态由调用方（项目页/设置页）提供。
     *
     * `hasLlmKey` / `hasFishKey` 建议来自 `secureKeyStore.hasKey(...)` 的当前值；
     * 首次构造时缺省按已配置处理，Sheet 打开后可再同步。
     */
    fun batch(
        novelId: Long,
        chapters: List<BatchChapterUi>,
        hasLlmKey: Boolean = true,
        hasFishKey: Boolean = true,
    ): BatchViewModel = BatchViewModel(
        novelId = novelId,
        chapters = chapters,
        batchRunner = container.batchRunner,
        hasLlmKey = hasLlmKey,
        hasFishKey = hasFishKey,
    )

    /**
     * 播放器页；播放器门面与内容源由播放模块提供，容器不持有
     * `PlayerController`（它需要 MediaSession 与前台服务生命周期），因此这里只做
     * 参数透传，真正的装配在导航层/播放模块完成。
     */
    fun player(
        contentSource: PlayerContentSource,
        gateway: PlayerGateway,
    ): PlayerViewModel = PlayerViewModel(contentSource = contentSource, gateway = gateway)

    // ------------------------------------------------------------------
    // 默认注册表
    // ------------------------------------------------------------------

    /**
     * 默认注册表：全部是 `(CreationExtras) -> ViewModel` 的 lambda，构造工厂时不解析
     * 容器依赖；携带标量参数的条目在创建时才校验 extras。
     */
    private fun defaultCreators(): Map<Class<*>, (CreationExtras) -> ViewModel> {
        val map = LinkedHashMap<Class<*>, (CreationExtras) -> ViewModel>()

        // 无导航参数：仅依赖 AppContainer。
        map[ProjectLibraryViewModel::class.java] = {
            ProjectLibraryViewModel(container.projectRepository)
        }
        map[TasksViewModel::class.java] = {
            TasksViewModel(container.projectRepository, container.jobRepository)
        }
        map[SettingsViewModel::class.java] = {
            SettingsViewModel(container.settingsRepository, container.secureKeyStore)
        }
        map[StorageViewModel::class.java] = {
            StorageViewModel.fromProduction(
                audioStorage = container.audioStorage,
                projectRepository = container.projectRepository,
                audioReferences = { container.referencedAudioPaths() },
            )
        }
        map[AboutViewModel::class.java] = {
            AboutViewModel.fromProduction(
                context = container.appContext,
                roomProbe = container.roomProbe,
                ffmpegProbe = container.ffmpegProbe,
                database = container.database,
                projectRepository = container.projectRepository,
                jobRepository = container.jobRepository,
                audioStorage = container.audioStorage,
            )
        }
        map[ImportViewModel::class.java] = {
            ImportViewModel(
                projectRepository = container.projectRepository,
                audioStorage = container.audioStorage,
                contentResolver = container.appContext.contentResolver,
            )
        }
        map[OnboardingViewModel::class.java] = {
            OnboardingViewModel(container.secureKeyStore, container.settingsRepository)
        }

        // 标量参数：必须由 CreationExtras 提供，缺失时抛带字段名的错误。
        map[ProjectViewModel::class.java] = { extras ->
            project(extras.require(AppViewModelKeys.NOVEL_ID, "novelId"))
        }
        map[ChapterListViewModel::class.java] = { extras ->
            chapterList(extras.require(AppViewModelKeys.NOVEL_ID, "novelId"))
        }
        map[CharactersViewModel::class.java] = { extras ->
            characters(extras.require(AppViewModelKeys.NOVEL_ID, "novelId"))
        }
        map[ScriptViewModel::class.java] = { extras ->
            script(
                chapterId = extras.require(AppViewModelKeys.CHAPTER_ID, "chapterId"),
                novelId = extras.require(AppViewModelKeys.NOVEL_ID, "novelId"),
                chapterTitle = extras[AppViewModelKeys.CHAPTER_TITLE].orEmpty(),
            )
        }
        map[TuningViewModel::class.java] = { extras ->
            tuning(
                novelId = extras.require(AppViewModelKeys.NOVEL_ID, "novelId"),
                roleId = extras.require(AppViewModelKeys.ROLE_ID, "roleId"),
            )
        }
        map[ExportViewModel::class.java] = { extras ->
            export(
                novelId = extras.require(AppViewModelKeys.NOVEL_ID, "novelId"),
                currentChapterIdx = extras[AppViewModelKeys.CHAPTER_INDEX],
            )
        }

        return map
    }

    /** 取必填 extras；缺失时错误信息带字段名，方便导航层定位。 */
    private fun <T : Any> CreationExtras.require(
        key: CreationExtras.Key<T>,
        name: String,
    ): T = this[key] ?: throw IllegalArgumentException(
        "创建 ViewModel 缺少 CreationExtras[$name]；" +
            "请用 MutableCreationExtras 传入，或改用 AppViewModelFactory 的工厂函数",
    )
}
