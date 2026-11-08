# 性能与内存复查（P6-07）

> **状态**：文档完成；**运行时基线 BLOCKED**（本环境无真机、按仓库硬规则不执行本地构建/CI）。
> **分支**：`docs/p6-07-perf`（基于 `main@e3df050`）；**日期**：2026-11-09（Asia/Shanghai）。
> **对照**：执行计划 §1.5（成功标准）、§12.9（性能约定）、§14.4–§14.5（失败演练/性能目标）、§15 P6-07；`docs/r8-evaluation.md`、`docs/abi-split-evaluation.md`、`docs/dependency-audit.md`、`docs/device-matrix.md`、`docs/security-review.md`。
> **约束**：本任务只新增本文档，未改代码/构建/CI/`docs/progress.md`，未提交密钥/大文件，未切分支/建 PR/merge。
> **证据边界**：所有结论来自**静态阅读 + 仓库内已有的 JVM/Robolectric 测试 + 只读命令**（`git ls-files`、`grep`、解包本地 Gradle 缓存中的 AAR 清单）。数字分三类并逐条标注：
> `实测`（仓库内测试/已发布产物）、`代码推导`（按代码语义估算复杂度/字节数）、`估算`（经验值，必须真机替换）。
> **emulator 结果不得记为真机通过**；没跑过的项写 `BLOCKED`。

---

## 0. 结论（TL;DR）

1. **UI 列表策略基本达标**：项目库/章节/剧本三处 `LazyColumn` 都有稳定 `key`，章节用 `contentType`，数据映射在 ViewModel/状态层完成（item 内无 DB/网络），头像与图标全部是 Canvas/文字（**零 Bitmap、无 Coil/Glide**）。这与 §12.9 的约定一致。
2. **最大的内存风险不在 TTS，而在导入**：`ImportNovelUseCase` 把最多 **64MB** 的文件 `readBytes()` 进堆，随后 `String.lines()` 逐行复制、再拼回章节正文、且编码探测会**同时保留两个候选 String**（`TextDecoder`）。按 UTF-16 语义推导，64MB 导入的瞬时峰值约 **300–400MB**（20MB 也有 ~100–140MB 瞬时），叠加基线 PSS 后足以在 `heapGrowthLimit` 256–384MB 的中端机上 OOM，与 §1.5「导入 20MB 小说不 OOM」直接冲突。→ **P0**
3. **第二个 P0 是「不可测」**：P6-07 依赖的 **P4-14 性能 / P5-15 Phase 5 门禁在本分支没有对应提交**；`AppNavHost` 仍是占位页、`AppContainer` 仍是 P2-02 版本、`GenerationGraph.executorFactory` 只在测试里赋值。也就是说：列表/播放/生成三条链路在当前分支**没有一条能从 UI 跑到端到端**。因此本文所有目标值只能给「口径 + 测量方法 + 代码估算」，不能给「通过/不通过」。
4. **P1 级可定位问题 3 个**：
   - 存储页把**所有项目**塞进**一个** `LazyColumn` item 里（`projects.forEachIndexed`），2000 个项目时一次性组合 2000 行 → 首帧/滚动必然掉帧；
   - 任务页为**每个项目**订阅一条 `observeActive` 并额外做一次 `lastFinished` 查询（N 个项目 = N 条 Flow + N 次查询），jobs 表每次进度写入都会放大成 N 次查询；
   - 磁盘空间预检 `requireSpaceFor()` **没有任何生产调用方**，§14.4「存储剩 200MB → 预检拒绝」目前不成立（生成中写满磁盘会以异常形式失败，而不是提前拒绝）。
5. **生成侧的内存设计是可控的**：mp3 按段落在内存（64kbps、单段通常 <1MB），FFmpeg 由 `FfmpegKitRunner` 的全局 `Mutex` **串行**执行，拼接只落盘不拼内存。峰值估算 **170–280MB**（含基线），300MB 目标有余量；§14.5 的 `<200MB` 偏乐观，建议区分「拼接阶段峰值」与「整批峰值」。
6. **播放侧**：`PlaybackQueue` 只把**当前章**的 MediaItem 交给 ExoPlayer（正确），但 `PlayerViewModel.queue` 把**全书**展平成一份常驻列表（`PlayerControllerGateway` 持有同一引用，不复制；若改走 `playQueue` 才会再建一份进 Store），且 `PlayerController` 每 **500ms** 发布一次位置 → 播放页顶层每 0.5s 重组一次；通知不设 artwork（无 Bitmap，正面）。
7. **存储侧**：一次「刷新」实际遍历文件系统 **两遍**（`scan()` + `stats()`），清理后再扫一遍；`cacheDir`（ffmpeg 中间文件、静音缓存）**不纳管**，进程被杀留下的临时文件不会被清理任务处理。

**风险统计**：P0 = 2，P1 = 3，P2 = 8（详见 §7）。

| 基线 | 任务目标 | 执行计划值 | 本分支现状 | 证据类型 |
|---|---|---|---|---|
| 冷启动 | **<2s** | §14.5 `<2.5s`（中端机） | 未接线，无法测 | BLOCKED |
| 列表滚动 | **掉帧率 <5%** | §14.5/§12.9 `60fps`、2000 章首帧 `<500ms` | 代码层符合懒加载/key 约定（存储页除外） | 代码推导 |
| 生成峰值内存 | **<300MB** | §14.5 `<200MB`、§12.9 `<200MB` | 估算 170–280MB | 估算 |
| 导入 20MB | §1.5「不 OOM」 | 同上 | 估算 200–290MB（含基线），有 OOM 风险 | 代码推导 |

---

## 1. 范围、方法与前置状态

### 1.1 审阅范围

| 域 | 文件（只列关键） |
|---|---|
| 启动 | `app/src/main/java/.../AudioForgeApp.kt`、`app/AppContainer.kt`、`MainActivity.kt`、`AndroidManifest.xml`、`data/db/AppDatabase.kt`、`data/db/RoomProbe.kt`、`work/ProbeWorker.kt` |
| 列表 | `feature/projects/ProjectLibraryScreen.kt`、`ProjectLibraryUiState.kt`、`ProjectLibraryViewModel.kt`；`feature/project/ChapterListScreen.kt`、`ChapterListUiState.kt`、`ChapterListViewModel.kt`；`feature/script/ScriptScreen.kt`、`ScriptUiState.kt`；`feature/storage/StorageScreen.kt`、`StorageViewModel.kt`；`feature/tasks/TasksViewModel.kt`；`ui/components/{RoleAvatar,ProgressRing,StatusPill}.kt` |
| 生成 | `core/tts/{TtsSynthesizer,FishTtsClient,AudioPostProcessor,TtsCacheKey}.kt`、`domain/pipeline/{AnalyzeChapterUseCase,RenderChapterUseCase}.kt`、`domain/jobs/{GenerationWorker,GenerationExecutor,BatchRunner,JobConstraints}.kt`、`core/audio/{FfmpegKitRunner,ChapterStitcher,SilenceGenerator}.kt`、`domain/usecase/ImportNovelUseCase.kt`、`core/ingest/{TextDecoder,ChapterSplitter}.kt`、`core/storage/{AudioStorage,ProjectFiles,StorageMaintenance}.kt` |
| 播放 | `playback/{PlayerController,AudioForgeMediaSessionService,PlaybackQueue,PlaybackMediaItems,PlaybackNotificationProvider,SleepTimer}.kt`、`feature/player/{PlayerViewModel,PlayerControllerGateway,PlayerScreen}.kt` |
| 构建/依赖 | `app/build.gradle.kts`、`gradle.properties`、`/tmp/gradle-home` 缓存中的 AAR 清单（只读） |

### 1.2 方法

- 逐文件静态阅读；用 `grep` 确认调用方/被调用方是否存在（例如 `requireSpaceFor`、`FFmpegKitConfig`、`executorFactory`）。
- 用仓库内已有测试作为「行为已覆盖」的证据：`ChapterListUiStateTest`（2000 条映射）、`ProjectLibraryUiStateTest`（2000 章聚合卡）、`ScriptUiStateTest`（2000 段 key）、`CharactersUiStateTest`（2000 人 key）、`SanitizerBenchmarkTest`（20k 字清洗/覆盖率）。
- 依赖侧只读解包 `work-runtime-2.12.0.aar`、`profileinstaller-1.4.0.aar`、`okhttp.aar` 的 `AndroidManifest.xml`，确认启动初始化器。
- **未执行**任何 Gradle 任务、Lint、CI、`adb`、模拟器。

### 1.3 前置状态：关键链路未接线（决定「能不能测」）

| 事实 | 证据 | 对 P6-07 的影响 |
|---|---|---|
| P4-14 性能、P5-15 门禁**没有提交** | `git log --all --grep="p4-14\|p5-15"` 无结果；`docs/progress.md` 未记录 | P6-07 的依赖未满足；无「已达标」的既有基线可继承 |
| 导航仍是 P4-01 占位页 | `ui/navigation/AppNavHost.kt:54-83`：`ProjectsPlaceholderScreen`、`ProjectDetailPlaceholderScreen`、`ChapterListPlaceholderScreen` | 项目库/章节/剧本/播放器页**无法从 UI 到达** |
| DI 容器仍是 P2-02 版本 | `app/AppContainer.kt:39-65`：只有仓库、探针、`FfmpegKitRunner`；无 TTS/导入/分析/渲染/播放器；`AppViewModelFactory.kt:31-35` 只注册 `ProjectListViewModel` | 无法装配页面级依赖，测不了真实首帧/内存 |
| 生成图未装配 | `domain/jobs/GenerationWorker.kt:43-46` 定义 `GenerationGraph.executorFactory`；全仓只有 `GenerationWorkerTest` 赋值（`domain/jobs/GenerationWorkerTest.kt:198,323`），生产无赋值 | 生成链路**跑不起来**；「生成峰值内存」只能估算 |
| 存储引用集合无 DAO 查询 | `SegmentDao` 只有把 `audioRelPath` 置 NULL 的 UPDATE（`SegmentDao.kt:45,60`），没有「取全部 audioRelPath」的查询；`StorageReferenceProvider` 由宿主注入（`StorageViewModel.kt:54-78`） | 存储清理/扫描页也无法装配；`cleanOrphans` 的空引用集合会误删，因此不能给默认值（代码注释已强调） |

> **结论**：本分支的 P6-07 只能做「设计/代码级复查 + 测量方案」。把 §9 的基线表作为**接线后的验收清单**，不要在 `docs/progress.md` 里勾成「已通过」。

### 1.4 口径定义（避免「通过」口径漂移）

| 指标 | 定义 | 测量方法（release 包） |
|---|---|---|
| 冷启动 | `am force-stop` 后 `am start -W -n <pkg>/.MainActivity` 的 `TotalTime`；重复 5 次取中位数，室温、非充电、后台清空 | `adb shell am start -W`；辅以 `logcat -s ActivityTaskManager` 的 `Displayed` |
| 列表掉帧率 | 固定 fling 脚本（2000 章搜到底 → 连续上滑 10 屏 → 回顶），`Janky frames / Total frames` | `adb shell dumpsys gfxinfo <pkg> reset` → 操作 → `dumpsys gfxinfo <pkg>` |
| 生成峰值内存 | 5 章批次全过程的 `TOTAL PSS` 最大值（含 native/ffmpeg） | 1Hz 采样 `adb shell dumpsys meminfo <pkg>`；结束后 `dumpsys meminfo --unreachable` 辅助看泄漏 |
| 导入峰值内存 | 从点击「导入」到列表出现新项目期间的 PSS 最大值 | 同上；另用 `adb logcat` 观察 `GC_FOR_ALLOC`/`OutOfMemoryError` |
| 播放内存 | 播放 30 分钟 + 通知/锁屏切章后的 PSS | `meminfo`；`dumpsys media_session` 交叉验证 |

> `dumpsys gfxinfo ... framestats` 在部分系统上只对 debuggable 包返回逐帧数据；取不到时用 `Janky frames` 聚合值，或在 debug 包上做**相对对比**（不要拿 debug 的绝对值当发布基线）。

---

## 2. 启动

### 2.1 启动时序（实际发生了什么）

| # | 阶段 | 代码/来源 | 线程 | 成本判断 | 证据 |
|---|---|---|---|---|---|
| 1 | `InitializationProvider`（androidx.startup） | WorkManager、OkHttp、ProfileInstaller 三家 AAR 的 manifest 合并进 `${applicationId}.androidx-startup` | 主线程 | 三个 initializer 依次 `create()`；WorkManager 构造 `WorkManagerImpl`/调度器（`WorkDatabase` 与 ForceStop 清理在后台任务里首次触达），OkHttp 做平台探测，ProfileInstaller 投递后台写 profile | 解包 AAR：`work-runtime-2.12.0.aar`、`okhttp.aar`、`profileinstaller-1.4.0.aar` 均含 `<meta-data android:name="...Initializer" android:value="androidx.startup">`；`ProfileInstaller` 由 `activity 1.13.0`/`navigation 2.10.2`/`lifecycle 2.11.0`/`compose ui 1.12.1` 传递带入（缓存 POM 可证），仓库未显式声明 |
| 2 | `AudioForgeApp.onCreate` | `AudioForgeApp.kt:14-21` | 主线程 | 只 `new AppContainer(this)`；所有依赖 `by lazy`（`AppContainer.kt:27,39-65`），不建库、不读 asset、不起线程 | 代码 |
| 3 | DEBUG 探针 | `AudioForgeApp.kt:17-20` → `AppContainer.startRoomProbe()`（`:68-76`）→ `RoomProbe.run()` 内部 `withContext(Dispatchers.IO)`（`RoomProbe.kt:35-45`） | **后台**（`appScope = SupervisorJob + Dispatchers.IO`，`AppContainer.kt:35`） | 不阻塞主线程；但会**触发首次建库**（`database` 懒加载），debug 冷启动含建库 I/O | 代码 |
| 4 | Room 建库/开库 | `AppDatabase.create()`（`AppDatabase.kt:54-69`）：WAL + `onOpen` 设 `foreign_keys`/`busy_timeout` | 首次查询的线程（Room 内部执行器） | `.build()` 本身不打开 DB；真正 open 在首次 DAO 调用。`onOpen` 的 `PRAGMA` 很轻 | 代码 |
| 5 | WorkManager 真正干活 | 默认初始化，仓库**没有**自定义 `Configuration`/`WorkerFactory`，也没有 `tools:node="remove"` | 后台 | 若存在排队 Work，启动后会在后台查 `WorkDatabase` 并可能拉起 `SystemForegroundService`；**冷启动首帧不受影响，但会增加启动后 CPU/IO** | `AndroidManifest.xml:70-75`（只 merge 了 FGS 类型，未移除 initializer）；`GenerationWorker`/`ProbeWorker` 均走 WorkManager |
| 6 | `MainActivity` | `MainActivity.kt:11-19`：`enableEdgeToEdge()` + `setContent { AudioForgeTheme { AppNavHost() } }` | 主线程 | 首帧 = Compose 首次组合 + `AdaptiveScaffold` + 5 条路由的 NavGraph；当前占位页无 DB 读取 | 代码 |
| 7 | 首帧后的数据 | 接线后 `ProjectLibraryViewModel` 在 `init` 里订阅 Room Flow（`ProjectLibraryViewModel.kt:78-102`） | 主线程发起、Room 在后台查询 | 首帧显示 loading；聚合查询与 2000 章 GROUP BY 在后台，不阻塞首帧 | 代码 |

**没有发现主线程阻塞**（无 `runBlocking`、无 `Thread.sleep`、无主线程 `readText`/`listFiles`）。`RoomProbe`/`FfmpegProbe` 都显式切 IO；`AboutViewModel.storage()` 也 `withContext(ioDispatcher)`（`AboutViewModel.kt:144-146`）。唯一的「主线程轻量 I/O」是播放起播时 `AudioStorage.resolve()` 会 `canonicalFile`（`ProjectFiles.kt:291-298`），单次 <1ms，可接受。

### 2.2 发现的启动成本项

| 项 | 现状 | 影响（估算） | 建议 |
|---|---|---|---|
| 三个 startup initializer | 全部保留默认 | 主线程 10–40ms（需 Perfetto 实测） | v1 先不动；若冷启动差 200ms 以上，再考虑 `App Startup` 手动初始化 WorkManager（成本：1 个 manifest `remove` + `Configuration`，风险是 Worker 入队路径，需回归 `GenerationWorkerTest` 与 emulator smoke） |
| `profileinstaller` 传递依赖 | 已存在（1.4.0） | 首次启动会后台写 profile，无首帧成本 | 保留；它也是后续 Baseline Profile 的前置 |
| `BuildConfig.DEBUG` 探针 | debug 每次启动写一行 probe 表 | 只影响 debug 冷启动，**会让 debug 测出的冷启动偏大** | 测冷启动一律用 release；不要用 `assembleDebug` 的数字下结论 |
| minify 关闭 | `app/build.gradle.kts:52-59` `isMinifyEnabled=false` | dex 14–16MB、类验证/加载更多；R8 预计再省 4–6.5MB dex（`docs/r8-evaluation.md` §7） | 归 P6-01 后续提交；P6-07 不改 |
| 无 baseline profile 模块 | 仓库无 `baselineprofile` 模块/profile 文件 | 冷启动比有 profile 慢 15–30%（经验值，需实测） | v1.1 候选（§15 已列） |

### 2.3 冷启动目标

- 任务目标 **<2s** 比执行计划 §14.5 的 **<2.5s** 更严。建议双写：**Pixel/arm64 中端机 <2s；Honor 20/EMUI（D1）<2.5s**，两者都必须在 release 包上测。
- 当前 APK ≈60.5MB、44MB 是双 ABI 的 FFmpeg `.so`；`useLegacyPackaging=false` 意味着安装时不落地解压，但**首次启动不加载 `.so`**（FFmpegKit 只在第一次调 FFmpeg 时 `System.loadLibrary`），所以 `.so` 体积不直接计入首帧，主要影响安装/更新耗时与磁盘。**不要把「APK 大」当成冷启动慢的理由**，要拿 Perfetto 的主线程切片说话。

---

## 3. 列表

### 3.1 总览

| 列表 | 数据规模（目标） | 分页/裁剪 | 稳定 key | contentType | item 内 I/O | 结论 |
|---|---|---|---|---|---|---|
| 项目库 | 2000 个项目（聚合卡） | 一次性 `GROUP BY`，无分页 | `"project:$id"`（`ProjectLibraryUiState.kt:26-27`） | 未设（单一类型，可接受） | 无 | ✅ |
| 章节列表 | 2000 章 | `LIMIT/OFFSET` 每页 50（`ChapterListViewModel.kt:45-48,143-165`） | `chapter.id`（`ChapterListUiState.kt:46-48`） | `"chapter"` + `"footer"`（`ChapterListScreen.kt:350-362`） | 无 | ✅ |
| 剧本 | 200 段（上限约 300） | 一次性载入本章 | `segment.key`（`ScriptScreen.kt:360-363`） | `segment.type.wire` | 无 | ✅ |
| 角色 | 2000 人 | 一次性 | 稳定 key（`CharactersUiStateTest.kt:280-305`） | 未设 | 无 | ✅ |
| 存储项目卡 | 2000 个项目 | **无** | **无** | 无 | 无（数据已在内存） | ❌ P1 |
| 任务/历史 | 项目数 × 1 条活跃 + 历史上限 | `historyLimit`（`TasksUiState.kt:269`） | jobId | 未设 | **每个项目 1 次 `lastFinished` 查询** | ⚠️ P1 |

### 3.2 项目库

- 聚合查询一次拿全：`NovelDao.observeProjects()` 用 `LEFT JOIN chapters GROUP BY n.id`（`NovelDao.kt:34-50`），只投影聚合字段，**不读正文**。2000 章的项目卡与 2 章的项目卡开销相同（`ProjectLibraryUiState.kt:10-13`）。
- 映射成本：`summaries.map { toProjectCardUi() }`（`ProjectLibraryViewModel.kt:84`）每次 Room 发射都新建 2000 个 `ProjectCardUi`（每个约 11 字段，估 80–100B）+ 字符串引用；量级 ~0.2MB/次、映射 <1ms，可接受。
- 搜索是内存过滤且 `by lazy`（`ProjectLibraryUiState.kt:77-79,105-114`）：空查询原样返回，不复制列表。**输入每个字符只重建过滤结果，不重查 DB**，符合 §12.9。
- 潜在放大：`ProjectLibraryViewModel` 在 `viewModelScope` 里持续收集 Room Flow（`:78-102`），页面退到后台也不会停；生成期间每次 `chapters/segments` 写入都会让 2000 章的 `GROUP BY` 重跑一次。功能上正确，但属于「不可见页面仍在做 DB 工作」。v1 数据量（2000 章）下每次约 1–3ms，可接受；建议后续改 `stateIn(..., WhileSubscribed(5_000))`。

### 3.3 章节列表（2000 章的核心路径）

- **只查 brief 投影**：`SELECT id, idx, title, parseStatus, audioStatus, parseWarn, segCount, segDone`（`ChapterDao.kt:37-51`），不含 `rawText`。这是「2000 章不把正文拉进内存」的关键，✅。
- **分页而非全量**：首屏 50 条，接近底部由 `snapshotFlow { lastVisibleIndex }` 触发 `loadMore`（`ChapterListScreen.kt:319-341`），并在 `LaunchedEffect` 的 key 里带上 `chapters.size/hasMore/loadingMore/loadMoreError`，避免死循环。✅
- **状态筛选的内存循环**：`ChapterFilter` 没有下推到 SQL，`launchLoad` 会**连续取页直到凑够 50 条匹配或取空**（`ChapterListViewModel.kt:143-165`）。极端情况下（筛「已完成」但全书都未完成）会一次翻完 2000 章、映射 2000 个 `ChapterListItemUi`（每页之间有 suspend 让出，主线程只做每页 50 条的映射）。2000 条映射的纯构造成本约 1–3ms（`ChapterListUiStateTest.kt:285-301` 已覆盖稳定性，但**未覆盖耗时**）。→ P2，建议把 `filter` 也下推到 `pageBriefs` 的 WHERE。
- `rememberSaveable(chapter.id)` 只对已组合的行生效（可见 ~10 行），不会为 2000 行建 state。✅
- 行内 `LinearProgressIndicator(progress = { ... })` 是确定进度，无无限动画（`ChapterListScreen.kt:437-443`）。✅

### 3.4 剧本

- `visibleSegments` 与 `playableCount` 在 `data class` 构造时**急切计算**（`ScriptUiState.kt:114-118`）：每次 `copy()`（例如播放高亮变化）都会重新过滤全章。200–300 段时 <0.5ms，可接受；若未来单章上千段，建议改 `by lazy` 或只在筛选字段变化时重算。→ P2（记录，不改）。
- `scrollTargetFor()` 是 O(n) 线性查找（`ScriptUiState.kt:155-160`），每次组合调用一次；200 段可忽略。
- `LaunchedEffect(playingSegmentIndex, scrollTarget)` 里 `animateScrollToItem`（`ScriptScreen.kt:348-352`）：播放跨段时一次平滑滚动，**不会每 500ms 触发**（`playingSegmentIndex` 只在段切换时变）。✅
- 每段一个 `Text`，没有「整章一个 10 万字 Text」，符合 §12.9。✅

### 3.5 存储页（P1）

`StorageScreen.kt` 外层是 `LazyColumn`（`:217`），但项目用量卡是**单个 item**：

```kotlin
// StorageScreen.kt:252-254 → 379-406
item(key = "projects") { ProjectsCard(projects = overview.projects) }
...
projects.forEachIndexed { index, project ->
    if (index > 0) HorizontalDivider()
    ProjectRow(project = project)
}
```

- 2000 个项目 = 2000 个 `ProjectRow` 一次性组合 + 测量，单个 Lazy item 的高度可达数万 dp；首帧与滚动都会卡，且没有任何复用。
- 数据来源 `buildStorageOverview` 也是全量映射（`StorageViewModel.kt:168-210`）。
- **建议**：把 `ProjectsCard` 拆成「标题 item + `items(projects, key = { it.novelId })` + 可选『只显示前 N 个/展开全部』」；若产品上不需要一次列 2000 本，分页或 Top-N 更省。成本约 0.5–1h，风险低（纯 UI 重构）。

### 3.6 任务页（P1）

`TasksViewModel.kt:88-138`：

```kotlin
combine(projectList.map { project -> snapshotFlow(project) }) { ... }
...
private fun snapshotFlow(project: ProjectSummary) =
    jobs.observeActive(project.id)
        .distinctUntilChanged()
        .map { active -> TaskJobSnapshot(..., lastFinished = jobs.lastFinished(project.id)) }
```

- N 个项目 → N 条 Room Flow + N 个 `combine` 槽；`jobs` 表**任何**写入（Worker 每章更新进度）都会让每条 `observeActive` 重新查询并触发一次 `lastFinished`。
- 2000 个项目时，一次进度更新 ≈ 2000 次 `observeActive` 重查 + 2000 次 `lastFinished`（虽然界面只展示 `historyLimit` 条最近任务，`buildTasksUiState` 仍然吃全量快照）。
- **建议**：改为「一条 `observeActiveJobs()` 全表 Flow（或 `WHERE status IN ('queued','running')`）+ 一条 `observeRecentFinished(limit)`」，在 SQL 层过滤/排序/限量；`lastFinished` 不再按项目 N+1。成本 ~2–3h（DAO + ViewModel + 测试），风险中（要覆盖「别的书写入不触发本页」的既有测例）。
- 现状下若「项目数 ≤ 2–30」，问题不显现；这条是**规模风险**，不是当前 bug。

### 3.7 图片/头像/图形（正面结论，无需改）

| 组件 | 实现 | 内存特征 |
|---|---|---|
| 角色头像 | `RoleAvatar` 用首字 `Text` + `CircleShape` 背景，注释明确「不加载图片，避免 Bitmap」（`RoleAvatar.kt:67-103`） | 无 Bitmap；每行一个 Text |
| 进度环 | `ProgressRing` 用 `Canvas` 画两段 `drawArc`（`ProgressRing.kt:88-115`） | 无 Bitmap；`animateFloatAsState` 只在进度变化时动画 |
| 告警图标 | `WarningIcon` 用 `Canvas` + `Path`（`ChapterListScreen.kt:512-535`） | 每行 18dp，仅在 `hasWarning` 时组合 |
| 封面 | 播放器 `PlayerCover` 用书名首字 + 主题渐变（`PlayerScreen.kt:235`） | 无 Bitmap |
| 依赖 | 无 Coil/Glide/Fresco；全仓 `Bitmap` 只出现在 `RoleAvatar` 注释里 | 无图片缓存/无 OOM 来源 |

这满足 §12.9「本项目基本无图片；头像用 Canvas/首字，避免 Bitmap」。

### 3.8 「2000 项映射」成本小结

| 场景 | 映射次数 | 估算成本 | 结论 |
|---|---|---|---|
| 项目库 2000 张卡 | 每次 Room 发射 2000 次 `toProjectCardUi()` | ~0.2MB/次、<1ms | 可接受；不要每帧重映射（现在是每次发射一次） |
| 章节 2000 章 | **不是一次**：40 页 × 50 条，随滚动增量映射 | 每页 <0.2ms；筛选无匹配时累计 ~2000 次 | 可接受；把筛选下推可省掉极端路径 |
| 剧本 2000 段（超出当前上限） | 每次 `copy()` 全量过滤 | 2000 段约 1–2ms/次，播放高亮每段一次 | 200 段无影响；上千段需 `lazy` |
| 存储 2000 项目 | 一次性映射 + 一次性组合 2000 行 | **组合成本远大于映射** | P1，必须懒加载 |

---

## 4. 生成与内存

### 4.1 TTS 并发

| 项 | 值 | 证据 |
|---|---|---|
| 默认并发 | **4** | `AppSettings.kt:37`、`RenderChapterUseCase.kt:96,545` |
| 允许范围 | 1–8（设置校验） | `SettingsValidator.kt:27-28,86-90` |
| 批次参数默认 | 5（1–8） | `BatchRunner.kt:32,41-43` |
| 实际生效 | `min(params.concurrency, settings.ttsConcurrency)`，再按热降级 | `JobConstraints.kt:111-131` |
| 工作池 | 固定 worker 抢同一个 `ArrayDeque`，**无批屏障**；某段完成立即取下一段 | `RenderChapterUseCase.kt:274-310` |
| 停止 | 每段前后检查 `shouldStop`，已完成的 DB 结果保留 | 同上、`GenerationExecutor` |
| 过热 | LIGHT/MODERATE 减 1、SEVERE 减半、CRITICAL 暂停 | `JobConstraints.kt:117-124` |

**内存含义**：并发 4 时，同时在途的 mp3 字节最多 4 份；每份 = 64kbps × 段时长。一段 30s ≈ 240KB，一段 4min ≈ 1.9MB，因此 TTS 阶段常驻 **1–8MB**。真正的高峰来自 ffmpeg 后处理（见 4.3），而它被全局串行化了。

### 4.2 缓存与 ByteArray

- `FishTtsClient` 用 OkHttp `body.bytes()` 把**整个** mp3 响应读进 `ByteArray`（`FishTtsClient.kt:199-209`），再交给 `TtsSynthesizer` 落盘（`TtsSynthesizer.kt:167-177`）。没有流式写盘，但因为单段音频小，**这是可接受的设计**；不建议为此改成流式（改 `TtsTransport` 接口会波及全部测试）。
- 缓存键 v2（文本+情绪+音色+speed/volume/pitch+model）写 `.hash`（`TtsSynthesizer.kt:205-210`）；目标文件 + `.hash` 都存在且键一致才算命中（`:235-243`）。**重渲染只补失效段**，不会重复占内存。
- 缓存**无自动大小上限**：片段音频就是最终产物，不删；换角色/换音色走同一路径覆盖，单项目不会因重渲染膨胀。磁盘增长与「已生成章节数」线性相关，只能靠用户删项目或存储页的保留期清理（且保留期清理只删**未被 DB 引用**的文件）。→ 归 §6。
- 原子写：`ProjectFiles.writeAtomic` 先 `.tmp` → `fd.sync()` → rename（`ProjectFiles.kt:155-200`），失败清理 `.tmp`。`@Synchronized` 会把 4 个 worker 的写串行化；`fsync` 单次约 5–20ms，吞吐上限仍远高于目标 2 段/s。✅

### 4.3 FFmpeg（后处理/拼接）：串行是最大的保护

- `FfmpegKitRunner.run()` 用 `Mutex` **全局串行**（`FfmpegKitRunner.kt:31-52`），注释写明「避免 CPU/内存峰值」。这意味着并发 4 的 TTS 在需要 pitch/volume 时会排队等 ffmpeg，峰值内存不随并发数线性增长。✅
- 后处理先写 `.post.mp3`，成功后 rename（`AudioPostProcessor.kt` 的 `process`），失败删半成品、不碰旧文件。
- 拼接只生成 `concat.txt`（文本清单）并让 ffmpeg 读**磁盘上**的片段文件，**不把音频读进 Java 堆**（`ChapterStitcher.kt:84-155`）。✅
- 临时文件：`cacheDir/ffmpeg-work/concat_*.txt`、`chapter_*.m4a`、回退用的 `chapter_*.wav`，均在 `finally` 删除（`ChapterStitcher.kt:151-155,172-174`）；静音缓存 `sil_<ms>.mp3` 保留复用（`SilenceGenerator.kt:35-60`）。
- **缺口 1（P2）**：`FFmpegKit` 的 session 历史默认保留，仓库里**没有** `FFmpegKitConfig.clearSessions()` / `enableSessionHistory` 调用。每个 session 会持有 `output`/`failStackTrace` 字符串；一次 5 章批次 = 5 次拼接 + 若干后处理，长会话下累积可达数 MB～数十 MB。建议在 `FfmpegKitRunner` 的 finally 里 `FFmpegKitConfig.clearSessions()`，并用 `meminfo` 验证 30 分钟批次前后 native 不单调增长。
- **缺口 2（P2，磁盘）**：WAV 回退会生成 **30 分钟 × 44.1kHz × 16bit mono ≈ 159MB** 的临时 WAV（`ChapterStitcher.kt:163-182`，`SAMPLE_RATE/CHANNELS` 在 `:198-199`）。它不占 Java 堆，但要求 `cacheDir` 有足够空间；低端机 cache 分区小/被系统清理时会失败。建议回退路径前检查可用空间，或直接用「分段 AAC + 二次 concat」避免大 WAV。

### 4.4 导入：64MB ByteArray 是最大的内存风险（P0）

数据流（`ImportNovelUseCase.import`）：

```text
SAF InputStream
  → SizeLimitedInputStream（边读边限制 64MB、上报进度；正确的流式拷贝）
  → audioStorage.writeImport（流式落盘，OK）
  → copied.readBytes()                       ← 一次性 64MB 进堆
  → textDecoder.decode(bytes)                ← 可能同时构造 2 个候选 String
  → chapterSplitter.split(text)              ← lines() 逐行复制 + 逐章 joinToString
  → chapters.map { ChapterInput(...) }       ← 引用同一批 String，不再复制
```

证据：`ImportNovelUseCase.kt:127-155`、`TextDecoder.kt:61-110,152-162`、`ChapterSplitter.kt:22-40`。

**按字节/字符估算**（中文在 JVM 里是 UTF-16，每字符 2B；`String.lines()` 逐行 `substring` 复制；`CharsetDecoder.decode()` 的 `CharBuffer` 按 `averageCharsPerByte` 预留）：

| 阶段 | 20MB 文件 | 64MB 文件 | 说明 |
|---|---|---|---|
| `readBytes()` | 20MB | 64MB | 堆内 `ByteArray` |
| 候选解码 String（GB18030 + Big5 同时保留） | 20–40MB | 64–128MB | `FALLBACK_CHARSETS` 两个都严格解码后一起进 `Candidate` 列表再评分（`TextDecoder.kt:79-91`） |
| 解码瞬时 CharBuffer | 20–40MB | 64–140MB | 每个候选一次，UTF-8 的 `averageCharsPerByte=1.1` 会预留更大缓冲 |
| `text.lines()` | ~20MB | ~64MB | 每行复制 |
| 章节正文 `joinToString` | ~20MB | ~64MB | 每章复制 |
| **瞬时峰值（不含应用基线）** | **~100–140MB** | **~300–400MB** | 保守叠加 |

叠加基线 PSS（列表页约 100–150MB）后：**20MB 导入 ~200–290MB、64MB 导入 ~400–550MB**。多数中端机 `heapGrowthLimit` 在 256–384MB（`ActivityManager.getMemoryClass()` 需实测），64MB 场景基本必 OOM；20MB 场景在低端机也会擦边。而 §1.5 的验收句是「导入 20MB、2000+ 章不 ANR、不 OOM」，**当前实现没有为此留出余量**。

**建议（按成本从低到高）**：
1. 把 `MAX_IMPORT_BYTES` 从 64MB 降到 **20–24MB**（与 §1.5 对齐），文案同步（`TOO_LARGE_MESSAGE`、`strings.xml`）。改动 2 处 + 测试，~0.5h；代价是拒绝 20MB 以上的书。
2. `TextDecoder` 只在**采样窗口**（如前 256KB）判定编码，再用单一编码解全文，避免同时持有两个全文候选；采样所需字节数远小于全文，峰值立降一半。~3–4h + 测试（现有 5 编码 fixture 全部要过）。
3. `ChapterSplitter` 改用 `lineSequence()` + 记录行号区间，避免 `lines()` 全量复制；章节正文仍不可避免要复制一次（要入 DB）。
4. 流式解码 + 分章入库（事务内边解边插），彻底去掉全文 `String`；这是 v1.1 级重构，不建议 v1 做。

**验收**：用 20MB 真实 txt（2000+ 章、GBK 与 UTF-8 各一份）在 Honor 20（或同档 4GB 机）测 `meminfo` 峰值，并确认无 `OutOfMemoryError`；再测 64MB 文件确认是「明确拒绝」而不是崩溃。→ 见 §9。

### 4.5 生成峰值内存估算（<300MB 目标）

| 组成 | 估算 PSS | 依据 |
|---|---|---|
| 基线（Compose + Navigation + Room + OkHttp 已加载，列表页在前台） | 90–160MB | `估算`：同类 Compose 应用；需 `meminfo` 实测 |
| TTS 在途 ByteArray（并发 4） | 1–8MB | `代码推导`：64kbps × 段时长 × 4（§4.1） |
| OkHttp 连接池/缓冲 | <2MB | 共享 `HttpClients`（P6-04），无下载流 |
| ffmpeg native（后处理/拼接，**串行**） | 40–90MB | `估算`：单会话；FFmpegKit audio 全量 `libavcodec` |
| `MediaMetadataRetriever`（每段即建即释放） | <5MB | `代码推导`：`ChapterStitcher.kt:279-289` 单次使用 |
| Room/Cursor/实体（单章 100–300 段） | 5–15MB | `代码推导`：`SELECT` 单章 segments |
| **合计** | **170–280MB** | 目标 300MB 有余量 |

风险叠加：若生成期间用户在前台**播放**（Media3 解码缓冲 20–40MB）或停留在 2000 项列表（Compose 组合缓存），可能摸到 300MB。建议：生成时禁止在同一页面并行播放（产品已有前台服务，技术上可并行，但内存预算要按最坏情况算）；验收时按「生成 + 播放同时」与「仅生成」两组分别记录。

### 4.6 磁盘预检未接线（P1）

- `AudioStorage.requireSpaceFor()` / `hasSpaceFor()` / `StorageMaintenance.hasSpaceFor()` 都存在（`AudioStorage.kt:159-172`、`StorageMaintenance.kt:228-233`），但全仓 `grep` **没有任何生产调用**（只有测试与注释）。
- 因此 §14.4「存储剩 200MB → 预检拒绝，不写坏文件」目前不成立：生成会在写片段/拼接时才以 `IOException` 失败，错误路径虽不会写坏旧文件（原子写），但可能已经消耗了网络额度与时间。
- 建议在 `RenderChapterUseCase.render()` 入口（或 `BatchRunner.start` 前）按「预计 章节数 × 片段数 × 平均 100KB + 拼接输出」预检，不足直接返回 `PipelineError.Storage` 并给可操作文案。成本 ~2h（含测试），风险低。

---

## 5. 播放

### 5.1 Media3 队列内存

- **只把当前章**的 MediaItem 交给 ExoPlayer：`loadChapter()` 用 `PlaybackMediaItems.of(chapter, chapterIndex)`（`AudioForgeMediaSessionService.kt:245-256`），章末再查 `PlaybackQueueStore` 决定跨章。这避免了「整本书 6 万个 MediaItem 一次性进 Binder/播放器」，✅。
- 但**全书队列**会常驻一份 `List<PlayerTrack>`：
  - `PlayerViewModel.buildTrackQueue()` 把整本书展平成 `List<PlayerTrack>`（`PlayerViewModel.kt:340-355`）；
  - `PlayerControllerGateway.open(queue, index)` 只是**持有同一引用**（`PlayerControllerGateway.kt:56-60`），不复制列表；
  - 只有改走 `PlayerController.playQueue` 的路径才会再建一个 `PlaybackQueue` 放进 `PlaybackQueueStore`（`PlaybackQueue.kt:154-167`）；当前 Gateway 用的是 `playChapter`（单条），所以全书 Store 副本并未出现。
  - 按 2000 章 × 40 段 = 8 万条 `PlayerTrack` 估算：对象头+字段约 64–80B/条 → **~5–8MB**；若一章 100 段则翻 2.5 倍。**没有上限**，属于「随书规模线性增长」的常驻内存。
  - 好的一面：`title` 是同一 `String` 引用（`PlayerViewModel.kt:351`），不复制章节名；`audioPath` 是相对路径字符串。
- **建议（P2）**：`PlaybackQueueStore` 在 `onTaskRemoved`/`releaseSession` 时清空（现在 `onTaskRemoved` 只 stop + release，不 `PlaybackQueueStore.set(empty)`，`AudioForgeMediaSessionService.kt:181-188`）；`PlayerViewModel.onCleared` 清 `queue`（`:320-325` 目前只取消 job，不清列表）。低成本，能改善「退出播放后进程仍常驻大列表」。
- 播放器本身：`ExoPlayer.Builder(this).build()` 使用默认 `DefaultLoadControl`（`AudioForgeMediaSessionService.kt:142-160`），缓冲按音频轨计算，本地 mp3 场景通常 20–40MB；无 `SimpleCache`（不做磁盘缓存，符合本地文件场景）。

### 5.2 通知

- `PlaybackNotificationProvider` 复用 `DefaultMediaNotificationProvider`，只覆盖按钮（`PlaybackNotificationProvider.kt:32-120`）；**没有设置 LargeIcon/artwork**，因此通知不产生 Bitmap，内存友好。✅
- 通知在 media item transition/播放态变化时重建；片段通常 10–60s，重建频率 ≤ 1 次/10s，可接受。
- 自定义按钮（上一章/下一章/睡眠）走 `SessionCommand`，`onConnect` 里显式加入可用命令（`AudioForgeMediaSessionService.kt:82-120`），没有额外 Binder 大对象。

### 5.3 睡眠定时

- `SleepTimer` 用 `delay(minutes)` + `System.nanoTime`（`SleepTimer.kt:22-70`），纯 JVM、可测；到点回调 `pause()`。
- 播放中进程由 `mediaPlayback` FGS + `setWakeMode(C.WAKE_MODE_LOCAL)`（`AudioForgeMediaSessionService.kt:150-155`）保活，`delay` 不会被 Doze 冻死；**如果用户在睡眠定时期间暂停播放**，FGS 退出、进程可能被冻结，`delay` 只能在下一次进程调度时补触发（晚到但不会永不触发）。可接受，注释里已声明「服务侧定时器」。
- 双定时器：`PlayerController` 本地有一个（UI 剩余时间）+ 服务侧一个（真正暂停），`release()` 会取消本地但保留服务侧（`PlayerController.kt:274-293` 注释明确）。逻辑正确，但两处 `sleepMinutes` 状态要同步；`handleSleepTimer` 的 15/30/60 白名单限制保证了不会分叉。✅

### 5.4 播放位置刷新（P2）

- `PlayerController.startPositionUpdates()` 每 **500ms** `delay` 后读 `currentPosition` 并更新 `_state`（`PlayerController.kt:342-373,375`）。
- 该 `PlayerUiState` 被 `PlayerScreen` 顶层 `collectAsStateWithLifecycle()` 收集（`PlayerScreen.kt:76-90`），因此**播放页每 0.5s 重组一次顶层**。Compose 的稳定性会让参数未变的子组件跳过，但 `Scaffold`/`TopAppBar`/进度条都会重新求值；在低端机上这是「持续 2Hz 的 Jank 来源」。
- **建议**：把 `positionMs` 拆成独立的 `StateFlow`（或 `derivedStateOf` + 只在 `PlayerProgress`/时间文本内读），让整页状态只在切章/切段/播放态变化时发射。成本 ~1–2h + 测试，风险低。v1 可先接受（500ms 不算高），但要在 gfxinfo 里确认没有因此掉帧。

### 5.5 生命周期

- `onTaskRemoved`：stop + cancel 定时 + `releaseSession()` + `stopSelf()`（`AudioForgeMediaSessionService.kt:181-188`）——不做后台续播，行为明确。
- `PlayerControllerGateway.release()` 只取消观察、清自己的 `queue` 引用（`PlayerControllerGateway.kt:98-108`），不释放服务（设计如此）；`PlaybackQueueStore` 仍保留上一次装载的队列（Gateway 路径下是单条，`playQueue` 路径下是整章/整书）→ 见 5.1 建议。

---

## 6. 存储

### 6.1 一次「刷新」= 两遍文件遍历（P2）

`StorageMaintenanceGateway.scan()`（`StorageViewModel.kt:115-123`）：

```kotlin
val report = maintenance.scan(refs.audio, refs.imports)   // 遍历 1：分类 + 字节
return buildStorageOverview(
    stats = maintenance.stats(),                          // 遍历 2：再走一遍算分项
    ...
)
```

- `scan()` 自己遍历 `audio/ imports/ exports/`（`StorageMaintenance.kt:67-110`，内部 `scanArea` `:317-358`）；`stats()` 又用同样的 `scanArea` 再来一遍（`:204-228`）。
- `cleanOrphans()` 内部**再** `scan()` 一次（`:126-133`），然后 UI 清理完还会 `gateway.scan()` 刷新（`StorageViewModel.kt:345-348`）→ 一次「清孤儿」最多 **3 遍**遍历。
- `cleanExpired()` 先 `scan()` 取 `expired` 候选（`StorageViewModel.kt:142-151`），再逐候选删除（不再全扫），这一条是 2 遍（扫描 + 清理后刷新）。
- 遍历本身还包含：每个文件的 `Files.isSymbolicLink`（lstat）、`length()`、`lastModified()`（stat），以及 `relativeOf` 字符串拼接。
- **规模估算**：2000 章 × 40 段 ≈ 8 万音频 + 8 万 `.hash` = **16 万文件**；每遍 16 万 × ~3 次 syscall ≈ 50 万 syscall。中端机热缓存下 1–2s/遍，冷缓存/慢 eMMC 上 3–6s/遍 → 刷新 2 遍就是 **2–12s**，且全部在 IO 线程（不 ANR，但用户看到 loading 很久）。
- **建议**：`scan()` 返回的 `StorageReport` 里已经可以算出 `total/audio/imports/exports/perNovel` 分项（`entries` 里有 `kind`），直接复用，不要第二次遍历；`stats()` 只保留给 About 页的独立诊断，或改成可选。成本 ~2h，风险低。

### 6.2 清理的正确性（正面）

- 不跟随符号链接、只删普通文件、删除前 `resolveForDeletion` + 引用集合二次校验（`StorageMaintenance.kt:126-160,244-315`）；`.hash` 跟随其音频受保护（`isReferencedPath`，`:399-404`）。
- 保留期清理只接受 `audio/<novelId>/...` 三级以下（`isProjectAudioFile`，`:381-389`），不会误删 `imports/`、`exports/`。
- 失败逐条计入 `CleanupReport.failures`，不抛异常；UI 有部分失败/重试（`StorageViewModel.kt:334-360`）。
- 这部分是「性能与安全」里质量最高的，**不建议为性能改动删除逻辑**；要改的只是「少扫一遍」。

### 6.3 `cacheDir` 不纳管（P2）

- ffmpeg 中间文件在 `cacheDir/ffmpeg-work`（`ChapterStitcher.kt:111-115`），静音缓存在 `cacheDir/sil_*.mp3`（`SilenceGenerator.kt:35-60`），字节码/日志里没有「启动或定时清理 cacheDir」的代码。
- 正常路径 `finally` 会删 `concat.txt`/半成品；**进程被杀、系统断电**时残留的 `chapter_*.wav`（最大 ~159MB）只能等系统清 cache。
- 建议：在 `StorageMaintenance.scan` 里增加 `cache/ffmpeg-work` 的「孤儿/过期」统计（不参与 audio 引用判定），或在 App 启动后的 IO 协程里做一次「删除超过 24h 的 `ffmpeg-work/*`」。成本 ~1–2h。

### 6.4 清理与生成的竞态

- `StorageMaintenance` 的删除只保护「DB 引用集合」；但生成中的片段在写 `.hash` 前只有 `audioRelPath` 可能还没入库（`saveDone` 先写音频字段再 `setStatus`，`RenderChapterUseCase.kt:428-446`）。若用户在生成期间清理孤儿，理论上有极小窗口把「已落盘但未入库」的文件当孤儿删掉（`olderThanHours` 默认 6h 挡住了新建文件，因为 mtime 新）。**当前默认参数下是安全的**，但如果以后允许 `olderThanHours=0`，必须加「生成中项目排除」。→ 记录为约束，不是当下的风险。

---

## 7. 风险清单

> 等级：**P0** = 可能 OOM/让基线无法验收，发布前必须处理或明确降级；**P1** = 规模上来后必然掉帧/卡顿/N+1，v1 前应修；**P2** = 优化项，可排 v1.1。

| ID | 等级 | 问题 | 证据 | 影响 | 建议 | 验证 |
|---|---|---|---|---|---|---|
| **P0-1** | P0 | 关键链路未接线，P6-07 基线无法测量 | §1.3 全部事实；`AppNavHost.kt:54-83`、`AppContainer.kt:39-65`、`AppViewModelFactory.kt:31-35`、`GenerationWorker.kt:43-46` | 无法证明「达标」，也无法在 CI 上回归 | 由 P4-14/P5-15 负责人先合入「导航 + 容器 + GenerationGraph 装配」；P6-07 的验收表顺延为「接线后执行」 | 手动可达三条链路：项目库→章节→剧本→播放；生成按钮能入队并跑完 1 章 |
| **P0-2** | P0 | 64MB 导入瞬时堆峰值 ~300–400MB；20MB 也接近上限 | `ImportNovelUseCase.kt:135,149,153`；`TextDecoder.kt:61-110`；`ChapterSplitter.kt:23,40` | 低端机 OOM（§1.5 明确要求不 OOM）；错误表现为进程被杀，用户只看到「闪退」 | 短期把上限降到 20–24MB；中期采样式编码判定（§4.4） | Honor 20 上导 20MB/2000 章 txt（UTF-8/GBK），全程无 `OutOfMemoryError`，峰值 PSS ≤250MB（B6） |
| **P1-1** | P1 | 存储页 2000 个项目一次性组合 | `StorageScreen.kt:252-254,379-406` | 首帧卡顿/滚动掉帧；与「列表 60fps」目标冲突 | 拆 `items(projects, key=novelId)`，或 Top-N + 展开 | gfxinfo Janky <5%（2000 项目数据） |
| **P1-2** | P1 | 任务页 N 项目 × (observeActive + lastFinished) | `TasksViewModel.kt:88-138`；`JobDao.kt:198-207` | jobs 每次进度写入 → 2000 次查询；任务页/后台 CPU 抖动 | 改成一条「活跃任务」Flow + 一条 `recentFinished(limit)` | 2000 项目 + 生成中，`dumpsys gfxinfo`、DB 查询计数（Room 日志）下降 |
| **P1-3** | P1 | 磁盘空间预检从未被调用 | `AudioStorage.kt:159-172`；全仓无调用 | 生成到一半才因空间失败，浪费 API 与时间；§14.4 不成立 | `render()` 入口/`BatchRunner.start` 前预检 | 单测：剩余空间不足时返回 `PipelineError.Storage`，不启动网络 |
| **P2-1** | P2 | FFmpegKit session/log 历史未清理 | `FfmpegKitRunner.kt:31-52`；全仓无 `clearSessions` | 长批次 native/Java 内存单调增长 | 每会话 finally `FFmpegKitConfig.clearSessions()` | 30min 批次前后 `meminfo` native 不单调增长 |
| **P2-2** | P2 | 播放位置 500ms 推动整页重组 | `PlayerController.kt:342-373,375`；`PlayerScreen.kt:76` | 低端机持续 2Hz 重组 | 位置拆独立 Flow / `derivedStateOf` | 播放中 gfxinfo；Compose 重组计数（Layout Inspector） |
| **P2-3** | P2 | 全书队列常驻，`PlaybackQueueStore` 生命周期不清 | `PlayerViewModel.kt:340-355`；`PlayerControllerGateway.kt:56-60,98-108`；`PlaybackQueue.kt:154-167` | 8 万段 ≈ 5–8MB 常驻（`playQueue` 路径再 +5–8MB） | `onTaskRemoved`/`onCleared` 清空；或只保留当前章 + 章索引 | 退出播放后 `meminfo` 回落 |
| **P2-4** | P2 | `cacheDir` 中间文件无清理 | `ChapterStitcher.kt:111-115`；`SilenceGenerator.kt:35-60`；无清理代码 | 进程被杀后残留大 WAV（~159MB/次） | 启动/定时清理 24h 前 `ffmpeg-work/*` | 杀进程后重启，残留被清 |
| **P2-5** | P2 | 项目库 `GROUP BY` 在不可见时仍重跑 | `ProjectLibraryViewModel.kt:78-102`；`NovelDao.kt:34-50` | 生成期间后台 DB 抖动 | `stateIn(WhileSubscribed)` | 后台 5 分钟后 DB 查询数下降 |
| **P2-6** | P2 | 章节筛选在内存循环翻页 | `ChapterListViewModel.kt:143-165` | 极端筛选一次映射 2000 条 | `filter` 下推 SQL | 筛「已完成」空库时首屏时间不随章节数线性增长 |
| **P2-7** | P2 | 无 Macrobenchmark/baseline profile 基建 | `app/build.gradle.kts` 无 `benchmark` 模块；§15 v1.1 候选 | 每次优化靠手工、不可回归 | v1.1 引入（§8） | CI 有基线对比 |
| **P2-8** | P2 | 三个 startup initializer 无条件跑 | AAR 清单（§2.1） | 主线程 10–40ms（估） | 实测后再决定是否自定义 WorkManager 初始化 | Perfetto 启动切片 |

---

## 8. 监测/工具引入成本

> 约束：本仓库**不接第三方分析/崩溃 SDK**（`docs/security-review.md` §2 明确「无分析/广告/崩溃 SDK」），所以监测方案不能依赖线上遥测，只能是「本地命令 + CI artifact + 开发者工具」。

| 工具 | 能回答什么 | 引入成本（净工时） | 运行成本/风险 | v1 建议 |
|---|---|---|---|---|
| `adb dumpsys gfxinfo` + `meminfo` + `am start -W` | 掉帧率、PSS、冷启动 | **0**（已有 adb 即可） | 需真机；人工执行 | ✅ 立即用（§9 清单） |
| Perfetto（`adb shell perfetto` / Studio） | 主线程阻塞、锁竞争、启动切片、Whoops | 0.5–1 天（写抓取脚本 + 读 trace） | 抓 10s trace 约 10–50MB；需 `tracebox`/config | ✅ v1 前做一次启动 + 滚动 trace |
| StrictMode（debug only） | 主线程磁盘/网络 | 2–4h（`Application.onCreate` 里 `ThreadPolicy` + `VmPolicy`，release 关闭） | 只影响 debug；日志噪音 | ✅ 低成本，建议做 |
| LeakCanary（debug only） | Activity/Fragment/View/Compose 泄漏 | 1–2h（`debugImplementation` + 版本；需在 `docs/decisions.md` 记一笔测试依赖） | debug APK +~1MB；不影响 release | ✅ 建议 v1 前接（只 debug） |
| Macrobenchmark（`androidx.benchmark`） | 冷启动/滚动帧的**可回归**数字 | **2–3 天**：新建 `:benchmark` `com.android.test` 模块、`benchmark` build type（`profileable`/签名）、`BaselineProfileRule`、CI 设备（现 CI 只有 API35 emulator，共享 runner 的帧数据不可信） | CI 时间 +10–20min；模拟器数据不能当真机；版本矩阵需新增一行（本任务不允许） | ❌ v1.1；v1 用 §9 手工清单 |
| Baseline Profile（`androidx.baselineprofile` 插件 + `:baselineprofile` 模块） | 冷启动 −15~30% | **2–3 天** + 真机/模拟器生成 + CI 集成 | 需 `profileinstaller`（已有传递依赖）；生成需可控设备 | ❌ v1.1（§15 已列）；P6-07 只记录 |
| Compose 重组计数（Layout Inspector / `Recomposer` 统计） | 过度重组 | 0.5 天（人工） | 仅 debug | ✅ 针对 5.4/3.4 抽查 |

**优先级**：先做 §9 的手工基线（0 成本）→ StrictMode + LeakCanary（半天）→ 一次 Perfetto 启动/滚动 trace（1 天）→ v1.1 再上 Macrobenchmark/Baseline Profile。

---

## 9. 基线目标与验收清单

> 所有项在**接线完成后**、**release 包**、**真机**上执行；emulator 只用于功能验证，不填「通过」。

### 9.1 目标

| # | 指标 | 目标 | 计划出处 | 测量 | 设备 | 状态 |
|---|---|---|---|---|---|---|
| B1 | 冷启动 | **<2s** | 本任务；§14.5 为 `<2.5s` | `am start -W` ×5 中位数 | Pixel/arm64 中端机 | BLOCKED |
| B2 | 冷启动（老机） | <2.5s | §14.5 | 同上 | Honor 20 / EMUI（D1） | BLOCKED |
| B3 | 列表掉帧率 | **<5%** | 本任务；§14.5 为 `60fps` | `gfxinfo` Janky frames | 同上 | BLOCKED |
| B4 | 2000 章首帧 | <500ms | §14.5/§12.9 | `logcat` `Displayed` / 帧时间 | 同上 | BLOCKED |
| B5 | 生成峰值内存 | **<300MB** | 本任务；§14.5/§12.9 为 `<200MB` | 1Hz `meminfo` 最大 TOTAL PSS | 同上 | BLOCKED |
| B6 | 导入 20MB 峰值 | 不 OOM、峰值 <250MB | §1.5 | `meminfo` + `logcat` | 同上 | BLOCKED |
| B7 | 播放 30min 内存 | 稳定、无单调增长 | §15 P5-08 | `meminfo` 每 5min | 同上 | BLOCKED |
| B8 | 任务页 2000 项目 | 首帧 <500ms、滚动 <5% jank | §12.9 | gfxinfo | 同上 | BLOCKED |

### 9.2 口径冲突（必须写进 release notes 或先统一）

1. **冷启动 2s vs 2.5s**：本任务更严。建议把 2s 作为 Pixel 档目标、2.5s 作为 Honor 20 档目标；两档都测，不要只报一个数。
2. **生成内存 300MB vs 200MB**：§14.5 的 `<200MB` 原文是「30 分钟章节拼接 <30s；峰值内存 <200MB」，语义更像「**拼接阶段**峰值」；本任务的 `<300MB` 是「**生成整批**峰值」。建议 v1 验收写「整批 <300MB、拼接阶段 <200MB」，实测后再回写 §14.5。
3. **导入上限 64MB vs NFR 20MB**：见 P0-2。建议把两者对齐到 20–24MB，或明确「>20MB 允许拒绝」。

### 9.3 操作清单（接线后照做）

```bash
# 0) 准备：release 包、真机、关闭动画（仅测试机）
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0

# 1) 冷启动 ×5（每次 force-stop）
adb shell am force-stop com.estimatefaster.audioforge
adb shell am start -W -n com.estimatefaster.audioforge/.MainActivity

# 2) 列表掉帧
adb shell dumpsys gfxinfo com.estimatefaster.audioforge reset
# 手动：2000 章列表连续滑动 10 屏 + 回顶
adb shell dumpsys gfxinfo com.estimatefaster.audioforge | head -40

# 3) 生成/导入峰值内存（1Hz 采样，另开一个 shell）
while true; do adb shell dumpsys meminfo com.estimatefaster.audioforge | \
  grep -E "TOTAL PSS|TOTAL RSS"; sleep 1; done

# 4) 进程内 DB 查询/泄漏辅助
adb shell dumpsys meminfo --unreachable com.estimatefaster.audioforge
adb logcat -s AudioForgeRoomProbe AudioForgeMedia AudioForgePlayer
```

> `gfxinfo` 的 `Janky frames` 统计在 release 包可用；`framestats` 逐帧明细在部分 ROM 上只对 debuggable 包开放，取不到就只报聚合值。

---

## 10. 未决项与归属

| # | 事项 | 归属 | 说明 |
|---|---|---|---|
| 1 | 导航/容器/GenerationGraph 接线 | P4-14/P5-15 | P6-07 的前置；不接线则 B1–B8 全部 BLOCKED |
| 2 | 导入上限与解码策略 | P6-07 后续提交（或 v1.1） | 本文只给方案；改动会影响 `strings.xml` 与 5 编码 fixture，需单独任务 |
| 3 | 存储页懒加载 | P5-09 后续/独立 UI 修复 | 纯 UI，0.5–1h |
| 4 | 任务页 N+1 | P5-11/P3 后续 | 需 DAO + ViewModel + 测试 |
| 5 | 磁盘预检 | P3 生成链路 | 入口选择（BatchRunner vs render）需与单活跃/停止语义对齐 |
| 6 | FFmpegKit session 清理 | P6-07 后续/独立 | 需要真机验证 native 内存曲线 |
| 7 | Baseline Profile / Macrobenchmark | v1.1（§15 已列） | 需新增 test 模块与版本矩阵条目，不能在 P6-07 做 |
| 8 | 真机矩阵（Honor/Pixel/MIUI/ColorOS） | P6-05（已 BLOCKED） | B1–B8 的「设备」列复用 `docs/device-matrix.md` 的 D1/D3 |

---

## 11. 参考

- `docs/android-execution-plan.md`：§1.5（`:115-123`）、§12.9（`:1975-1982`）、§14.4–§14.5（`:2092-2111`）、§15 P6-07（`:2794-2798`）。
- `docs/r8-evaluation.md`：§2.2（debug/release 差异、`BuildConfig.DEBUG` 折叠）、§7（dex 体积与 R8 收益）。
- `docs/abi-split-evaluation.md`：§2.2–§2.3（FFmpeg `.so` 43.9MiB、APK 60.5MB 分项）。
- `docs/dependency-audit.md`：§0（版本一致性、paging 未使用）、§3（依赖清单）。
- `docs/device-matrix.md`：§1（D1 Honor 20 / D3 Pixel）、§4.4（真机 BLOCKED 的解除条件）。
- `docs/security-review.md`：§2（无分析/崩溃 SDK，决定监测只能用本地工具）。
- 代码证据：见各节行号；测试证据：`ChapterListUiStateTest.kt:285-301`、`ProjectLibraryUiStateTest.kt:236-271`、`ScriptUiStateTest.kt:379-394`、`CharactersUiStateTest.kt:280-305`、`SanitizerBenchmarkTest.kt`。
