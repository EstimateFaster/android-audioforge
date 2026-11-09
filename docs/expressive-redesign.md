# Material 3 Expressive 全量 UI/UX 重做

日期：2026-11-09（Asia/Shanghai）
基线：`feat/expressive-redesign`（基于 main f28d8e7 + 7 个 UI 分支）
验证：GitHub Actions（CI/emulator smoke）；未做本地构建。

## 对照表（旧 → 新）

| 区域 | 旧实现 | 新实现 |
|---|---|---|
| 主题 | 少量颜色 + `Typography()` 默认 + 默认 `Shapes()` | seed 推导 48 个 color role（light/dark）、30 role typography（含 emphasized）、8 级 shapes、`MotionScheme.expressive()`、`MaterialExpressiveTheme`、动态取色 + 设置即时切换 |
| 间距/动效 | 屏幕内裸 dp、无统一动效 | `MaterialTheme.spacing`（ForgeSpacing）、`ForgeMotion` duration/easing 常量 |
| 共享组件 | 基础 Card/Button/自定义插画 | `ExpressiveCard`、`ExpressivePrimaryButton/Tonal/Outlined/Text`、`ExpressiveChip`、`ExpressiveFab`、`ExpressiveWavyProgress`、`ExpressiveLoading`、`ExpressivePill`、`ExpressiveSectionHeader`；Empty/Error/Section/Loading 统一 expressive |
| 导航外壳 | NavigationSuiteScaffold 默认样式 | tonal 容器、secondaryContainer indicator、选中 label emphasized、expressive pill mini player |
| 项目库/导入 | 顶栏 + 普通列表 + AlertDialog 重名 | primaryContainer hero + 统计、ExpressiveCard 项目卡、Wavy/圆环进度、ExpressiveFab；导入 Sheet 大圆角、各态卡片、唯一主按钮、内联重名卡 |
| 项目详情/章节/批次/调参 | 顶栏 + 普通卡片 | hero 头图、双进度卡、唯一主行动、ExpressiveChip 筛选、tonal 章节卡、Sheet 分组卡 + 主按钮 |
| 剧本/片段编辑/人物/音色 | 普通列表/Sheet | 章节 hero、当前播放段 secondaryContainer 高亮、ExpressivePill 迷你条、人物 tonal 卡、音色分组卡 + 锁定确认 |
| 任务/设置 | 普通卡片/顶栏保存 | 任务 hero + 大百分比 Wavy、历史 tonal 卡、danger 停止；设置七组 ExpressiveCard、底部唯一主保存 |
| 存储/关于 | 普通统计卡/列表 | 总览 hero + 三色 Wavy 分区条、项目 tonal 卡、清理结果 pill；诊断状态行、可展开许可证卡 |
| Onboarding/播放器/导出 | 普通步骤/封面/进度 | `MaterialShapes.Cookie9Sided` hero、动画步骤条、Wavy 进度、84dp 播放 FAB、倍速/睡眠 chips、导出范围 chips + 结果 pill |
| HomeScreen | 普通 Card 诊断页 | ExpressiveCard + primary/tonal 按钮 + hero |

## 使用的 Expressive 元素

- `MaterialExpressiveTheme` + `MotionScheme.expressive()`（主题层唯一入口）
- seed → tonal palette 生成的完整 `ColorScheme`；`surfaceContainer*` 分层表面
- `Typography` 30 role（standard + `*Emphasized`）
- `Shapes` 8 role（extraSmall…extraExtraLarge + largeIncreased/extraLargeIncreased）
- `ExpressiveCard`（大圆角 tonal 表面）、`ExpressiveChip`（FilterChip 大圆角）、`ExpressiveFab`、`ExpressivePill`
- `LinearWavyProgressIndicator`、`LoadingIndicator`
- `MaterialShapes.Cookie9Sided.toShape()`（Onboarding/Player hero）
- `animateContentSize`/`animateFloatAsState` + MotionScheme 规格做形状/进度过渡

## 约束检查（静态）

- `Color(0xFF` 在 `ui/theme` 之外：**0**（`grep -rn "Color(0xFF" app/src/main/java/... | grep -v /ui/theme/`）
- `MaterialTheme(` 在 `ui/theme` 之外：**0**
- `MaterialExpressiveTheme`/`MotionScheme` 仅在主题层引用
- 11 个 feature 模块 + `HomeScreen` 均已重做（合并 7 个分支，17 个 feature 文件变更）
- 新增文案走 `strings_ux_*.xml`，主 `strings.xml` 未改

## 每屏截图位

| 屏幕 | Before | After |
|---|---|---|
| 项目库 | BLOCKED（无重做前截图存档） | BLOCKED（需 emulator 截图工作流） |
| 项目详情/章节 | BLOCKED | BLOCKED |
| 剧本/人物/音色 | BLOCKED | BLOCKED |
| 任务/设置 | BLOCKED | BLOCKED |
| 存储/关于 | BLOCKED | BLOCKED |
| Onboarding/播放器/导出 | BLOCKED | BLOCKED |
| Home | BLOCKED | BLOCKED |

截图缺失原因：本环境禁止本地构建/启动 emulator；CI smoke 目前只抓 `smoke-home.png`（启动首页），未按屏抓图。后续可在 smoke 中增加 `adb pull` 截图步骤，或人工在 emulator 上补图。

## 人工核验记录

- **深色模式**：主题提供完整 dark scheme；设置页“主题=深色”保存后 `MainActivity` 读取 settings Flow 立即重组；`NavigationSmokeTest.theme_switch_applies_immediately_and_keeps_navigation` 覆盖切换。
- **200% 字体**：统一 `AdaptiveFieldRow`（≥1.5x 纵向堆叠）、FlowRow、标题 2–3 行、关键卡片无固定高；各屏 Preview 含 fontScale=2.
- **TalkBack**：图标按钮均有 contentDescription（装饰性为 null）；标题 heading、错误/进度 liveRegion、选中项 selected 语义保留；完整 TalkBack 走查 **BLOCKED**（无真机）。

## 验收命令

- CI：`Android CI`（assembleDebug + lintDebug + testDebugUnitTest）
- Smoke：`Android Smoke (emulator)`（instrumented，含导航 + 主题切换）
- 未运行本地 `./gradlew`（遵循仓库“禁止本地构建/CI”约束）。
