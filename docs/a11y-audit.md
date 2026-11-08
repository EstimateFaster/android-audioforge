# P5-13 无障碍 / 字体复查

执行计划：`docs/android-execution-plan.md` §12.8（无障碍、字体）、§15 P5-13。
基线：`main` a99cd6b；本文只记录已核对项、已修复项与仍需真机确认的项。

## 1. 范围与方法

- 审计范围：`ui/components/**`、`feature/**` 全部屏幕与 Sheet、`res/values/strings.xml`。
- 方法：静态检查（`contentDescription` / 语义属性 / `maxLines` / 硬编码颜色 / 触控尺寸）
  + 组件纯逻辑单测；不新增 Gradle 依赖，不在本机跑构建或 CI。
- 不修改 `ui/navigation/**`、`app/**`、`core/**`、`data/**`、`domain/**`、`build.gradle.kts`、
  `docs/progress.md`、`.github/**`。

## 2. 检查项与结论

### 2.1 图标 / 头像 / 进度 / 状态语义

| 检查项 | 结论 | 处理 |
| --- | --- | --- |
| 装饰性 Canvas / 色块不进入读屏 | 通过 | `EmptyStateGlyph`、`ErrorStateGlyph`、`PlayerCover`、`StatusPill` 圆点、`Onboarding` 步骤色条均 `clearAndSetSemantics {}`。 |
| 有意义的头像提供描述 | 已修复 | `RoleAvatar` 默认生成“角色头像：名字”；新增 `decorative` 参数，旁边已有可见名字时不再重复朗读。 |
| 头像与可见姓名重复朗读 | 已修复 | `ScriptScreen`、`CharactersScreen`、`ProjectScreen` 的人物条改为装饰头像 + 姓名文本（人物条合并语义）。 |
| 环形进度语义 | 已增强 | `ProgressRing` 是单一 `progressBarRangeInfo` 节点；中心百分比不重复朗读；新增 `liveRegion` 选项。 |
| 任务进度实时播报 | 已修复 | `TasksScreen` 当前任务 `ProgressRing(liveRegion = true)`，进度变化以 Polite 播报。 |
| 状态胶囊语义 | 通过 | `StatusPill` 合并后代语义，文案由 `strings.xml` 提供；可点时使用 `Role.Button` 且触控区 ≥48dp。 |
| 线性进度/百分比重复 | 已修复 | 导入页视觉百分比清除语义，进度条本身保留 `progressBarRangeInfo`；存储页按区域补充“区域 + 百分比”描述。 |
| 警告/加载/失败图标 | 通过 | 章节告警图标有描述；加载环由相邻状态文案 + 进度语义覆盖；纯装饰字形已清空语义。 |

### 2.2 触控目标 ≥48dp

| 检查项 | 结论 | 处理 |
| --- | --- | --- |
| 按钮 / 文字按钮 | 通过 | Material3 按钮自带 `minimumInteractiveComponentSize()`；项目内主要按钮另有 `heightIn(min = 48.dp)`。 |
| 图标按钮 / 菜单项 | 通过 | 菜单项由 Material3 保证最小高度；“更多”使用 `TextButton + heightIn(48.dp)`。 |
| 滑杆 / 开关 / 单选 | 通过 | `Slider`、`Switch`、`RadioButton`、`FilterChip` 均走 Material3 最小触控区。 |
| 整行可点 | 通过 | 项目卡、章节卡、剧本片段、任务行、导出章节行、音色行均整行可点/可选。 |
| 小尺寸纯展示元素 | 不需要 | 章节号角标、表情标签、头像不是交互控件，不强制 48dp。 |

### 2.3 标题、阅读顺序、liveRegion

| 检查项 | 结论 | 处理 |
| --- | --- | --- |
| 标题 `heading()` | 已补 | `SectionHeader` 原有；本轮为各主屏顶栏标题、设置页小节标签/主题标题、播放器倍速/睡眠/章节标题、音色分组标题补 `heading()`。 |
| 列表项阅读顺序 | 已修复 | 头像装饰化避免“头像描述 + 姓名”重复；人物条、导出章节行、设置开关行使用合并语义。 |
| 错误 liveRegion | 已补 | `ErrorBanner` 原有 Polite；整页 `ErrorState`、设置保存状态/校验汇总、导入错误、引导测试结果、音色保存错误、片段校验、存储清理结果补 Polite。 |
| 进度 liveRegion | 已补 | 任务当前进度（Polite）、导出进度条（Polite）、章节列表加载更多、存储清理中。 |
| 播放状态 liveRegion | 已补 | 剧本迷你播放条“正在播放/本章完成/跳过无音频”使用 Polite。 |
| 重复的可视标签 | 已修复 | 剧本连续播放开关：文字标签清空语义，由开关的 `contentDescription` 统一朗读。 |

### 2.4 200% 字体不截断

| 检查项 | 结论 | 处理 |
| --- | --- | --- |
| 进度环中心百分比 | 已修复 | 正常字体保持标题字号；放大后用直径比例封顶（`toSp` 抵消系统放大），200% 不再溢出圆环。 |
| 并排输入框标签 | 已修复 | 新增 `AdaptiveFieldRow`：字体 ≥1.5x 时改为纵向堆叠；设置页、批次范围、导出范围使用。 |
| 播放器当前台词 | 已修复 | 去掉 `maxLines = 4`，由页面纵向滚动承载长台词。 |
| 列表/卡片标题 | 已改进 | 任务书名、章节标题、人物名、当前音色、导出章节名、剧本迷你条状态等由 1 行放宽到 2–3 行。 |
| 顶栏标题 | 保留单行 | `TopAppBar` 高度固定，视觉上仍可能省略；TalkBack 读取的是完整文本，不影响无障碍。 |
| 播放器/迷你播放条按钮 | 已改进 | 播放器章节控制、句子控制与剧本迷你播放条改用 `FlowRow`，200% 时换行而不是溢出屏幕。 |
| 长正文 | 通过 | 剧本片段正文、错误原因、统计正文不设 `maxLines`；编辑框内部可滚动。 |

### 2.5 对比度 / 颜色

| 检查项 | 结论 | 处理 |
| --- | --- | --- |
| 硬编码品牌色 | 通过 | `ui/components/**`、`feature/**` 无 `Color(0x...)` / `Color.Red` 等硬编码；全部取 `MaterialTheme.colorScheme`。 |
| 状态语义色 | 通过 | `StatusPill` 的容器/内容色成对取自 colorScheme（如 `errorContainer` / `onErrorContainer`）。 |
| 不只靠颜色 | 已改进 | 状态胶囊同时有文字；任务/章节状态有文案；引导步骤有“第 x / y 步”文字，色条仅作辅助。 |
| 深色模式 | 通过（静态） | 主要组件预览含深色 200% 变体；实际对比度仍需真机/深色截图确认。 |

### 2.6 动效

| 检查项 | 结论 | 处理 |
| --- | --- | --- |
| `StatusPill` 呼吸动画尊重“移除动画” | 已修复 | 新增 `rememberSystemAnimationsEnabled()` 读取并监听 `Settings.Global.ANIMATOR_DURATION_SCALE`；为 0 时不再创建无限动画。 |
| 其他无限动画 | 通过 | 仅状态胶囊使用无限动画；加载指示器属于允许的例外。 |
| 一次性过渡 | 通过 | 由 Compose `MotionDurationScale` 跟随系统动画缩放。 |

### 2.7 播放器 / 滑杆 / 开关

| 检查项 | 结论 | 处理 |
| --- | --- | --- |
| 播放进度滑杆 | 已增强 | `contentDescription = 播放进度`，`stateDescription = 当前 / 总时长`。 |
| 倍速 / 睡眠定时 | 通过 | `FilterChip` 文本标签 + 选中状态由 Material3 提供；分组标题补 heading。 |
| 播放/暂停、上/下一章、上/下一句 | 通过 | 全部为文字按钮，文案随状态切换，触控 ≥48dp。 |
| 微调滑杆 | 通过 | 已有“参数名 + 当前值 + 范围”描述。 |
| 批次并发滑杆 | 已修复 | 新增“并发数，当前 N”描述。 |
| 设置开关 | 通过 | 整行 `toggleable(role = Switch)`，标题 + 说明 + 开关状态一次朗读。 |
| 批次“强制重跑”开关 | 已修复 | 改为整行 `toggleable(role = Switch)`，不再出现无标签开关。 |
| 音色单选行 | 通过 | 整卡 `selectable(role = RadioButton)`，RadioButton 自身不重复响应。 |
| 导出章节勾选 | 已修复 | 章节行合并语义并标记选中状态；复选框仅作视觉指示，不再产生空语义节点。 |

## 3. 修复清单（按文件）

- `ui/components/AnimationPolicy.kt`（新增）：系统动画开关读取/监听 + `animationsEnabled()` 纯函数。
  运行时直接读取系统设置，不依赖额外的协程策略注入。
- `ui/components/AdaptiveFieldRow.kt`（新增）：200% 字体下表单纵向重排 + `shouldStackFormFields()` 纯函数。
- `ui/components/RoleAvatar.kt`：新增 `decorative`，装饰头像不产生读屏节点。
- `ui/components/ProgressRing.kt`：新增 `liveRegion`；中心百分比字号封顶。
- `ui/components/StatusPill.kt`：呼吸动画遵循系统“移除动画”。
- `ui/components/ErrorState.kt`：整页错误原因补 Polite liveRegion。
- `feature/player/PlayerScreen.kt`：滑杆状态描述；台词不截断；分组标题 heading；控制按钮 `FlowRow` 换行。
- `feature/script/ScriptScreen.kt`：头像装饰化；迷你播放条 liveRegion、按钮换行；连续播放标签去重。
- `feature/characters/CharactersScreen.kt`、`VoicePickerSheet.kt`：头像装饰化、标题换行、分组 heading、保存错误 liveRegion。
- `feature/project/ProjectScreen.kt`、`ChapterListScreen.kt`、`BatchSheet.kt`：人物条合并语义、标题换行、强制开关整行可切换、并发滑杆描述、范围输入自适应。
- `feature/project/ImportSheet.kt`：导入进度描述 + 百分比去重；错误 liveRegion。
- `feature/settings/SettingsScreen.kt`：200% 表单重排、字段错误语义、保存/校验 liveRegion、小节 heading。
- `feature/storage/StorageScreen.kt`：区域进度描述、清理进度/结果 liveRegion。
- `feature/tasks/TasksScreen.kt`：任务进度 liveRegion、标题换行。
- `feature/onboarding/OnboardingScreen.kt`：测试状态 liveRegion、步骤色条装饰化。
- `feature/script/SegmentEditSheet.kt`：校验卡片 liveRegion。
- `res/values/strings.xml`：新增播放进度状态、并发描述、存储区域进度 3 条文案。
- `app/src/test/.../ui/components/A11ySemanticsTest.kt`（新增）：动效判定、字体阈值、圆环字号、朗读文案占位符。

## 4. 未修复 / 需真机 TalkBack 的项

以下项无法在静态审计或纯 JVM 单测中确认，需真机（建议 Pixel + Honor 各一台）执行：

1. **主流程 TalkBack 走查**：导入 → 开始批次 → 停止 → 打开剧本 → 播放 → 换音色 → 导出。
   重点确认焦点顺序、Sheet 打开/关闭后的焦点回归、进度 Polite 播报频率是否过吵。
2. **顶栏标题 200% 视觉截断**：`TopAppBar` 固定高度下仍单行省略；读屏读完整文本。若产品要求视觉完整，需要改导航/顶栏布局，超出本任务允许改动范围。
3. **动态色对比度**：Android 12+ 动态取色由系统生成，本仓库未做运行时对比度测量；需在深色/浅色、默认壁纸下用 Accessibility Scanner 或对比度工具抽查。
4. **真实语义树断言**：`androidTest` 已声明 `ui-test-junit4`，但本轮为避免未验证的测试 API 进入 CI，只提交纯逻辑单测；建议后续补 `onNodeWithContentDescription` / `assertIsHeading` / `assertIsSelected` 的组件级断言。
5. **滑杆步进朗读**：`Slider` 在部分 TalkBack 版本上按百分比朗读；微调/并发滑杆的“参数名 + 值”描述已提供，但步进粒度是否合适需真机听感确认。
6. **Canvas 图标辨识度**：空态/错误态/告警图标为自绘，已有文字兜底；高对比度/大字体下是否仍清晰需截图确认。
7. **RTL / 英文**：当前仅中文；未验证 RTL 布局镜像（产品暂不做）。
8. **通知/媒体控制的无障碍**：播放通知由系统媒体控件渲染，`PlaybackNotification` 未在本任务范围（`playback/**` 不在允许改动列表）。

## 5. 测试与验证

已提交：

```bash
# 纯逻辑单测（与现有 ComponentsTest 同套 Robolectric 配置）
./gradlew :app:testDebugUnitTest --tests "com.estimatefaster.audioforge.ui.components.A11ySemanticsTest"
```

测试覆盖：

- `animationsEnabled()`：0 / 负数关闭，正数开启。
- `shouldStackFormFields()`：1.0x、1.3x 并排，1.5x、2.0x 堆叠。
- `progressRingMaxTextSize()`：随直径缩放且小于直径一半。
- 新增朗读文案占位符：播放进度、并发、存储区域进度、头像/进度默认描述。

未执行（按硬性约束）：本地 `assembleDebug` / `lint` / CI。合并前建议在 CI 跑
`:app:testDebugUnitTest` 与 `:app:lintDebug`。

## 6. 风险

- `rememberSystemAnimationsEnabled()` 通过 `ContentObserver` 监听系统设置；部分 ROM 可能延迟回调，但首次组合一定读取当前值，最坏情况是切换后需一次重组才生效。
- `AdaptiveFieldRow` 在 1.5x 阈值处切换布局，切换瞬间会有一次重排；不影响状态与焦点顺序。
- `ProgressRing` 的百分比字号上限按直径比例计算，极小圆环（<48dp）下会偏小；当前最小使用直径为 72dp。
- 装饰化头像后，若未来某处没有可见姓名，需要显式保持 `decorative = false`（默认值），否则会丢失人物描述。
