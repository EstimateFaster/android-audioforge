package com.estimatefaster.audioforge.ui.navigation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.app.AppContainer
import com.estimatefaster.audioforge.app.AppViewModelFactory
import com.estimatefaster.audioforge.core.settings.SecureKeyStore
import com.estimatefaster.audioforge.core.storage.ExportManager
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.data.db.SegmentEntity
import com.estimatefaster.audioforge.feature.about.AboutScreen
import com.estimatefaster.audioforge.feature.about.AboutViewModel
import com.estimatefaster.audioforge.feature.characters.CharactersScreen
import com.estimatefaster.audioforge.feature.characters.CharactersViewModel
import com.estimatefaster.audioforge.feature.characters.VoicePickerSheet
import com.estimatefaster.audioforge.feature.characters.VoicePickerViewModel
import com.estimatefaster.audioforge.feature.export.ExportSheet
import com.estimatefaster.audioforge.feature.export.ExportViewModel
import com.estimatefaster.audioforge.feature.onboarding.OnboardingScreen
import com.estimatefaster.audioforge.feature.onboarding.OnboardingViewModel
import com.estimatefaster.audioforge.feature.player.PlayerScreen
import com.estimatefaster.audioforge.feature.player.PlayerUiState
import com.estimatefaster.audioforge.feature.project.BatchChapterUi
import com.estimatefaster.audioforge.feature.project.BatchSheet
import com.estimatefaster.audioforge.feature.project.BatchViewModel
import com.estimatefaster.audioforge.feature.project.ChapterListScreen
import com.estimatefaster.audioforge.feature.project.ChapterListViewModel
import com.estimatefaster.audioforge.feature.project.ProjectScreen
import com.estimatefaster.audioforge.feature.project.ProjectViewModel
import com.estimatefaster.audioforge.feature.project.TuningSheet
import com.estimatefaster.audioforge.feature.project.TuningViewModel
import com.estimatefaster.audioforge.feature.projects.ImportSheet
import com.estimatefaster.audioforge.feature.projects.ImportViewModel
import com.estimatefaster.audioforge.feature.projects.ProjectLibraryScreen
import com.estimatefaster.audioforge.feature.projects.ProjectLibraryViewModel
import com.estimatefaster.audioforge.feature.script.ScriptScreen
import com.estimatefaster.audioforge.feature.script.ScriptViewModel
import com.estimatefaster.audioforge.feature.script.SegmentEditSheet
import com.estimatefaster.audioforge.feature.script.SegmentEditViewModel
import com.estimatefaster.audioforge.feature.settings.SettingsScreen
import com.estimatefaster.audioforge.feature.settings.SettingsViewModel
import com.estimatefaster.audioforge.feature.storage.StorageScreen
import com.estimatefaster.audioforge.feature.storage.StorageViewModel
import com.estimatefaster.audioforge.feature.tasks.TasksScreen
import com.estimatefaster.audioforge.feature.tasks.TasksViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 片段编辑 Sheet 的「未选中」哨兵；片段主键由 Room 自增，从 1 开始。 */
private const val NO_SEGMENT_ID = -1L

/**
 * 打开片段编辑 Sheet 所需的一组实体快照。
 *
 * 片段与角色列表必须**一起**加载完成后再创建 ViewModel，否则角色下拉会先拿到空列表。
 */
private data class SegmentEditTarget(
    val segment: SegmentEntity,
    val characters: List<CharacterEntity>,
)

/**
 * 应用导航入口（P4-01 / 接线 2/2）。
 *
 * 结构：`AdaptiveScaffold`（底栏/rail + 迷你播放器）→ `NavHost`（类型安全路由）。
 * 每个目的地都在自己的 `viewModel { }` 里用 [AppViewModelFactory] 的工厂函数构造
 * ViewModel，依赖全部来自 [container]；页面只暴露导航与一次性动作回调。
 *
 * @param container 进程内 DI 容器
 * @param startDestination 首启目的地：首次引导或项目库（由 MainActivity 判断）
 * @param navController 外部可注入，便于 Activity 处理通知深链
 */
@Composable
fun AppNavHost(
    container: AppContainer,
    startDestination: Route,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selectedDestination = TopLevelDestination.fromDestination(backStackEntry?.destination)
    val factory = remember(container) { AppViewModelFactory(container) }

    AdaptiveScaffold(
        selectedDestination = selectedDestination,
        onSelectDestination = { destination -> navController.navigateToTopLevel(destination) },
        modifier = modifier,
    ) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.fillMaxSize(),
        ) {
            appNavGraph(navController = navController, container = container, factory = factory)
        }
    }
}

/**
 * 路由表。抽成普通函数便于测试直接构建 NavGraph，不依赖 Compose UI 渲染。
 *
 * @param factory ViewModel 工厂；默认按 [container] 新建，测试可注入替换表
 */
fun NavGraphBuilder.appNavGraph(
    navController: NavController,
    container: AppContainer,
    factory: AppViewModelFactory = AppViewModelFactory(container),
) {
    composable<Route.Projects> {
        val libraryViewModel: ProjectLibraryViewModel = viewModel(factory = factory)
        var showImport by rememberSaveable { mutableStateOf(false) }

        ProjectLibraryScreen(
            viewModel = libraryViewModel,
            onOpenProject = { novelId -> navController.navigate(Route.ProjectDetail(novelId)) },
            onImport = { showImport = true },
        )

        if (showImport) {
            val importViewModel: ImportViewModel = viewModel(factory = factory)
            ImportSheet(
                viewModel = importViewModel,
                onDismiss = { showImport = false },
                onImported = { novelId ->
                    showImport = false
                    navController.navigate(Route.ProjectDetail(novelId))
                },
                onOpenExisting = { novelId ->
                    showImport = false
                    navController.navigate(Route.ProjectDetail(novelId))
                },
            )
        }
    }

    composable<Route.Tasks> {
        val tasksViewModel: TasksViewModel = viewModel(factory = factory)
        val scope = rememberCoroutineScope()
        TasksScreen(
            viewModel = tasksViewModel,
            onOpenProject = { novelId -> navController.navigate(Route.ProjectDetail(novelId)) },
            taskRunnerAvailable = true,
            onStop = { novelId ->
                scope.launch {
                    runCatching { container.batchRunner.stop(novelId) }
                }
            },
        )
    }

    composable<Route.Settings> {
        val settingsViewModel: SettingsViewModel = viewModel(factory = factory)
        SettingsScreen(
            viewModel = settingsViewModel,
            onBack = { navController.popBackStack() },
            onOpenLicenses = { navController.navigate(Route.About) },
            onOpenStorage = { navController.navigate(Route.Storage) },
            onOpenAbout = { navController.navigate(Route.About) },
            onRestartOnboarding = {
                navController.navigate(Route.Onboarding) { launchSingleTop = true }
            },
        )
    }

    composable<Route.ProjectDetail>(
        deepLinks = listOf(
            navDeepLink<Route.ProjectDetail>(basePath = AppDeepLink.PROJECT_BASE_PATH),
        ),
    ) { entry ->
        val novelId = entry.toRoute<Route.ProjectDetail>().novelId
        val projectViewModel: ProjectViewModel = viewModel { factory.project(novelId) }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        var showBatch by rememberSaveable { mutableStateOf(false) }
        var showExport by rememberSaveable { mutableStateOf(false) }
        var batchSession by rememberSaveable { mutableStateOf(0) }
        var exportSession by rememberSaveable { mutableStateOf(0) }
        var batchChapters by remember(novelId) { mutableStateOf<List<BatchChapterUi>?>(null) }
        var hasLlmKey by remember(novelId) { mutableStateOf(false) }
        var hasFishKey by remember(novelId) { mutableStateOf(false) }

        // BatchSheet 需要完整章节投影；打开 Sheet 后再读一次，避免项目页常驻大列表。
        LaunchedEffect(showBatch, novelId) {
            if (!showBatch) return@LaunchedEffect
            batchChapters = runCatching {
                container.chapterRepository.observeBriefs(novelId).first()
                    .map { chapter -> BatchChapterUi.from(chapter) }
            }.getOrElse { emptyList() }
            hasLlmKey = container.secureKeyStore.get(SecureKeyStore.LLM_API_KEY)
                ?.isNotBlank() == true
            hasFishKey = container.secureKeyStore.get(SecureKeyStore.FISH_API_KEY)
                ?.isNotBlank() == true
        }

        ProjectScreen(
            viewModel = projectViewModel,
            onBack = { navController.popBackStack() },
            onStartBatch = {
                batchSession += 1
                showBatch = true
            },
            onStopBatch = {
                scope.launch {
                    runCatching { container.batchRunner.stop(novelId) }
                }
            },
            onRename = { title ->
                scope.launch {
                    runCatching { container.projectRepository.rename(novelId, title) }
                }
            },
            onDelete = {
                scope.launch {
                    val deleted = runCatching {
                        container.projectRepository.delete(novelId)
                    }.getOrDefault(false)
                    if (deleted) navController.popBackStack()
                }
            },
            batchRunnerAvailable = true,
            onOpenChapters = { navController.navigate(Route.ChapterList(novelId)) },
            onOpenCharacters = { navController.navigate(Route.Characters(novelId)) },
            onOpenPlayer = { navController.navigate(Route.Player(novelId)) },
            onOpenExport = {
                exportSession += 1
                showExport = true
            },
            onOpenStorage = { navController.navigate(Route.Storage) },
            onOpenAbout = { navController.navigate(Route.About) },
        )

        if (showBatch) {
            val chapters = batchChapters
            if (chapters != null) {
                val batchViewModel: BatchViewModel = viewModel(
                    key = "batch-$novelId-$batchSession",
                ) {
                    factory.batch(
                        novelId = novelId,
                        chapters = chapters,
                        hasLlmKey = hasLlmKey,
                        hasFishKey = hasFishKey,
                    )
                }
                BatchSheet(
                    viewModel = batchViewModel,
                    onDismiss = { showBatch = false },
                    onStarted = { showBatch = false },
                    hasLlmKey = hasLlmKey,
                    hasFishKey = hasFishKey,
                    onOpenSettings = {
                        showBatch = false
                        navController.navigate(Route.Settings)
                    },
                )
            }
        }

        if (showExport) {
            val exportViewModel: ExportViewModel = viewModel(
                key = "export-$novelId-$exportSession",
            ) {
                factory.export(novelId)
            }
            ExportSheet(
                viewModel = exportViewModel,
                onDismiss = { showExport = false },
                onShare = { uris -> shareExportedAudio(context, container, uris) },
            )
        }
    }

    composable<Route.ChapterList> { entry ->
        val novelId = entry.toRoute<Route.ChapterList>().novelId
        val chapterListViewModel: ChapterListViewModel = viewModel { factory.chapterList(novelId) }
        val state by chapterListViewModel.state.collectAsStateWithLifecycle()
        ChapterListScreen(
            viewModel = chapterListViewModel,
            onBack = { navController.popBackStack() },
            onOpenScript = { chapterId ->
                val title = state.chapters.firstOrNull { it.id == chapterId }?.title.orEmpty()
                navController.navigate(Route.Script(novelId, chapterId, title))
            },
        )
    }

    composable<Route.Script> { entry ->
        val route = entry.toRoute<Route.Script>()
        val scriptViewModel: ScriptViewModel = viewModel {
            factory.script(
                chapterId = route.chapterId,
                novelId = route.novelId,
                chapterTitle = route.chapterTitle,
            )
        }
        val state by scriptViewModel.state.collectAsStateWithLifecycle()
        var editingSegmentId by rememberSaveable { mutableStateOf(NO_SEGMENT_ID) }

        ScriptScreen(
            viewModel = scriptViewModel,
            onBack = { navController.popBackStack() },
            onEditSegment = { index ->
                state.segments.firstOrNull { it.index == index }?.let { segment ->
                    editingSegmentId = segment.id
                }
            },
            // TTS 重新生成尚未接入导航层：保留入口但不下发任务，避免误触发计费。
            onRegenerateSegment = { },
        )

        val segmentId = editingSegmentId
        if (segmentId != NO_SEGMENT_ID) {
            var editTarget by remember(segmentId) { mutableStateOf<SegmentEditTarget?>(null) }
            LaunchedEffect(segmentId, route.novelId) {
                val segment = container.segmentRepository.get(segmentId) ?: return@LaunchedEffect
                editTarget = SegmentEditTarget(
                    segment = segment,
                    characters = container.characterRepository.list(route.novelId),
                )
            }
            val target = editTarget
            if (target != null) {
                val editViewModel: SegmentEditViewModel = viewModel(
                    key = "segment-edit-$segmentId",
                ) {
                    factory.segmentEdit(target.segment, target.characters)
                }
                SegmentEditSheet(
                    viewModel = editViewModel,
                    onDismiss = { editingSegmentId = NO_SEGMENT_ID },
                    onSaved = { editingSegmentId = NO_SEGMENT_ID },
                    // 重新生成同样只保留 UI 回调，未接 TTS 时忽略。
                    onRegenerate = { },
                )
            }
        }
    }

    composable<Route.Characters> { entry ->
        val novelId = entry.toRoute<Route.Characters>().novelId
        val charactersViewModel: CharactersViewModel = viewModel { factory.characters(novelId) }
        var voiceRoleId by rememberSaveable { mutableStateOf("") }
        var tuningRoleId by rememberSaveable { mutableStateOf("") }

        CharactersScreen(
            viewModel = charactersViewModel,
            onBack = { navController.popBackStack() },
            onVoiceClick = { roleId -> voiceRoleId = roleId },
            onTuneCharacter = { roleId -> tuningRoleId = roleId },
        )

        if (voiceRoleId.isNotBlank()) {
            var role by remember(voiceRoleId) { mutableStateOf<CharacterEntity?>(null) }
            LaunchedEffect(voiceRoleId, novelId) {
                role = container.characterRepository.getByRoleId(novelId, voiceRoleId)
            }
            val currentRole = role
            if (currentRole != null) {
                val pickerViewModel: VoicePickerViewModel = viewModel(
                    key = "voice-picker-$novelId-$voiceRoleId",
                ) {
                    factory.voicePicker(novelId = novelId, role = currentRole)
                }
                VoicePickerSheet(
                    viewModel = pickerViewModel,
                    onDismiss = { voiceRoleId = "" },
                    onAssigned = { _, _, _ -> voiceRoleId = "" },
                )
            }
        }

        if (tuningRoleId.isNotBlank()) {
            val tuningViewModel: TuningViewModel = viewModel(
                key = "tuning-$novelId-$tuningRoleId",
            ) {
                factory.tuning(novelId = novelId, roleId = tuningRoleId)
            }
            TuningSheet(
                viewModel = tuningViewModel,
                onDismiss = { tuningRoleId = "" },
                onSaved = { tuningRoleId = "" },
            )
        }
    }

    composable<Route.Player>(
        deepLinks = listOf(navDeepLink { uriPattern = AppDeepLink.PLAYER_URI }),
    ) { entry ->
        val novelId = entry.toRoute<Route.Player>().novelId
        var bookTitle by remember(novelId) { mutableStateOf("") }
        LaunchedEffect(novelId) {
            bookTitle = novelId
                ?.let { id -> container.projectRepository.getProject(id)?.title.orEmpty() }
                .orEmpty()
        }

        // AppContainer 尚未装配 PlayerController（需要 MediaSession/前台服务生命周期），
        // 因此播放器页先以空态可达；装配后把这里换成
        // viewModel { factory.player(contentSource, gateway) } 即可。
        PlayerScreen(
            state = PlayerUiState(loading = false, bookTitle = bookTitle),
            onBack = { navController.popBackStack() },
        )
    }

    composable<Route.Storage> {
        val storageViewModel: StorageViewModel = viewModel(factory = factory)
        StorageScreen(
            viewModel = storageViewModel,
            onBack = { navController.popBackStack() },
        )
    }

    composable<Route.About> {
        val aboutViewModel: AboutViewModel = viewModel(factory = factory)
        val context = LocalContext.current
        val appName = stringResource(R.string.app_name)
        AboutScreen(
            viewModel = aboutViewModel,
            onBack = { navController.popBackStack() },
            onOpenGitHub = { url -> openUrl(context, url) },
            onCopyDiagnostics = { text -> copyDiagnostics(context, text, appName) },
            onExportLogs = { text -> shareText(context, text, appName) },
        )
    }

    composable<Route.Onboarding> {
        val onboardingViewModel: OnboardingViewModel = viewModel(factory = factory)
        val scope = rememberCoroutineScope()
        val complete: () -> Unit = {
            // ViewModel 的 finish/skip 已写 onboardingCompleted；这里再兜一次，
            // 保证任何完成路径都留下 true，随后把引导页从返回栈移除。
            scope.launch {
                runCatching {
                    container.settingsRepository.update { settings ->
                        settings.copy(onboardingCompleted = true)
                    }
                }
            }
            navController.navigate(Route.Projects) {
                popUpTo(Route.Onboarding) { inclusive = true }
                launchSingleTop = true
            }
        }
        OnboardingScreen(
            viewModel = onboardingViewModel,
            onFinished = complete,
            onSkipped = complete,
        )
    }
}

/**
 * 主目的地切换：回到起始图、保存/恢复各 tab 的 back stack，避免重复入栈。
 */
private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** 打开外部链接（关于页 GitHub）；没有浏览器时静默失败。 */
private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

/** 复制诊断文本到剪贴板。 */
private fun copyDiagnostics(context: Context, text: String, label: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    runCatching {
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}

/** 分享纯文本（诊断/日志）。 */
private fun shareText(context: Context, text: String, title: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, title))
    }
}

/**
 * 分享导出后的音频：单章走 [ExportManager.shareIntent]，多章用 `ACTION_SEND_MULTIPLE`。
 */
private fun shareExportedAudio(
    context: Context,
    container: AppContainer,
    uris: List<Uri>,
) {
    if (uris.isEmpty()) return
    val intent = if (uris.size == 1) {
        container.exportManager.shareIntent(uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = ExportManager.MIME_TYPE_AUDIO_MP4
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.app_name)))
    }
}
