# 现有 UI 功能盘点（重构基线）

> 日期：2026-11-12 · 对象：`main` @ `536d774`（Forge v2）
> 方法：逐文件只读盘点 4 组共 26 个 UI 文件 + 导航/主题/设计系统 + 8 个 strings 文件；
> 每条结论都能在源码定位（行号见引用）。**本文件只描述现状，不提出方案**（方案见 `02-design-spec.md`）。

---

## 0. 规模与结构

| 层 | 文件数 | 行数（约） | 说明 |
|---|---|---|---|
| `ui/navigation` | 8 | 1,300 | 单 Activity + `AppNavHost`，11 个目的地，3 tab 外壳 |
| `ui/forge` | 2 | 600 | `ForgeIcons`（Lucide）+ `ForgeComponents`（17 个组件，事实上的设计系统） |
| `ui/components` | 12 | 1,700 | 旧设计系统，约 2/3 已死（0 引用） |
| `ui/theme` | 5 | 300 | 手写 40 色 role + 30 字体 role + 8 shape + MotionScheme |
| `feature/*` | 34 屏 / Sheet | 26,000 | 11 页 + 6 Sheet + 各自 ViewModel/UiState |
| `res/values/strings*.xml` | 8 | 1,281 条 | `ux2_` 349 / `ux_` 105 / 其余为 Phase 0 遗留 |

---

## 1. 全局外壳

### 1.1 导航结构

```
Onboarding（首启全屏，3 步）
└─ 主壳（3 tabs，底部 NavigationBar；宽 ≥600dp 换 NavigationRail）
   ├─ 书架 Projects
   │   └─ ImportSheet
   ├─ 任务 Tasks
   └─ 设置 Settings
       ├─ 存储 Storage
       └─ 关于 About

项目详情 ProjectDetail（从书架/任务/深链进入）
├─ 章节 ChapterList
│   └─ 剧本 Script
│       └─ SegmentEditSheet
├─ 人物 Characters
│   ├─ VoicePickerSheet
│   └─ TuningSheet
├─ 批量生成 BatchSheet
├─ 导出 ExportSheet
└─ 播放器 Player（全屏）
```

- 路由用 `@Serializable` 类型安全路由；深链 `audioforge://project/{id}`、`audioforge://player`。
- **Manifest 只声明了 `player` host**，`project/*` 的 intent-filter 缺失 → 外部深链实际进不来。
- 二级页一律裸 `navigate()`，只有顶层做 `popUpTo + restoreState`；Settings → Storage → 返回会重复入栈。

### 1.2 外壳行为（问题最集中的地方）

1. **迷你播放器是假的**：`AppNavHost` 不传 `miniPlayer`/`onOpenPlayer`，永远渲染
   `MiniPlayerPlaceholder`，文案「未在播放」，播放按钮 `onOpenPlayer = {}` → 点了没反应。
2. **底栏不隐藏**：`AdaptiveScaffold` 只按 `selectedDestination` 高亮，不看是否为 null；
   Onboarding、Player、Project 详情页都保留 3 tab + 迷你播放器。
3. **三条底栏叠加**：Script 页自带 `ScriptMiniBar` + 壳层迷你播放器 + 底栏 = 屏高 1/4 被吃掉。
4. 断点硬编码 `600.dp`，未用 `WindowSizeClass`；expanded（≥840dp）无策略。

### 1.3 主题与设计系统

- **动态取色默认开**（`dynamicColor = true`），Android 12+ 时整个 `ForgeLight/DarkScheme`
  被系统 scheme 覆盖 → 品牌青绿只在关闭动态色或 <API31 时可见。这是「视觉不一致」的最大来源。
- 40 个 color role 中 **19 个 0 引用**；30 个字体 role 中 12 个 0 引用；3 个 shape 0 引用。
- 形状全部是对称 `RoundedCornerShape`，注释里说的「不对称流体感」没有实现。
- `ForgeMotion` 的 6 个 duration/easing 常量 0 引用；只有 `StatusPill` 感知系统「移除动画」。
- 设计系统 17 组件：`ForgeTopBar / ForgeIconAction / ForgePrimaryAction / ForgeSecondaryAction /
  ForgeCard / ForgeListRow / ForgeChip / ForgeFab / ForgeExtendedFab / ForgeSegmented /
  ForgeDialog / ForgeSectionHeader / ForgeWavyProgress / ForgeLoading / ForgeEmpty / ForgeError /
  ForgeSheetHeader`。
- **「禁止纯文字按钮」被遵守**：生产代码 0 处 `TextButton`、0 处 `Text + clickable`。
  但 `ForgeIconAction` 描述参数允许空串（`?: ""`），`ForgeDialog`/`ForgeError`/`ForgeSheetHeader`
  里 3 处中文硬编码（「取消 / 重试 / 关闭」）。
- 触控：现役 UI 未发现 <48dp 的可点目标；但 `ForgeCard(onClick)` 未加
  `minimumInteractiveComponentSize`，极短卡片可能 <48dp。

---

## 2. 逐页功能清单

### 2.1 书架（Projects）— `ProjectLibraryScreen.kt`

- **顶栏**：标题「书架」+ 副标题「把小说变成多人演播的有声书 · 全部数据留在本机」；动作=搜索、更多菜单。
- **结构**：2 列网格（fontScale≥1.5 → 1 列）：总览卡 → 搜索框 → 4 个状态 chip → 分区头 → 项目卡。
- **项目卡**：渐变封面 112dp + 首字、More 菜单、标题 2 行、`源.txt · 156,000 字`、波浪进度、百分比、状态胶囊、Chevron。
- **交互**：卡片→项目详情；More→重命名/删除对话框；FAB「导入」→ImportSheet；
  搜索图标→滚到搜索框并聚焦；筛选 chip；无结果→「清除筛选」；Snackbar 展示操作错误。
- **状态**：加载 / 错误+重试 / 空库（导流 Import）/ 搜索无结果。
- **问题**：导入入口 3 处重复；总览+搜索+筛选+分区头占满首屏；卡片同时有整卡点击与 Chevron 两个同义入口；
  状态胶囊与筛选 chip 文案重合；筛选在 Composable、query 在 ViewModel，两份状态不同步。

### 2.2 导入 Sheet — `ImportSheet.kt`

- 文件卡（未选/已选）→ 阶段分支：`Idle / Selected / Importing / NeedsEncoding / Duplicate / Error / Success`。
- 交互：SAF 选文件、重选、开始导入、取消、编码候选 chip（点击立即重试）、打开已有项目、重试。
- 状态：波浪进度 + 百分比；大小未知时 `LoadingIndicator`；重名弹窗；错误块。
- **问题**：关闭入口 3 类；`Duplicate` 关掉弹窗后只剩「取消导入」死角；步骤编号 1/3 缺 2；
  Sheet 里塞 300dp 整块错误页；成功自动关闭过快。

### 2.3 项目详情（ProjectDetail）— `ProjectScreen.kt`

- **顶栏**：标题「项目」+ `书名 · 12 章`；动作=播放全书、更多（8 项：章节/人物/播放器/导出/存储/关于/重命名/删除）。
- **结构**：Hero 卡（封面 84×112 + 状态 + 标题 + 来源 + 3 个标签）→ 进度卡（百分比 + 波浪 + 3 stat）
  → 告警卡 / 人物条 → 章节入口 → 底栏 `ContinueDock`（继续生成 + 停止生成 + hint）。
- **交互**：章节 3 个入口、人物 N+2 个入口全部指向同一目标；继续生成→BatchSheet；停止→静默；
  重命名/删除对话框；删除成功后返回。
- **状态**：loading / error / missing / empty（唯一 CTA 是「返回项目」，语义混乱）。
- **问题**：8 项下拉无分组、删除紧邻关于；静默失败（停止/重命名/删除无反馈）；
  字数格式与书架不一致（`156000` vs `156,000`）；`ContinueDock` 常驻 130dp 压缩内容。

### 2.4 章节列表（ChapterList）— `ChapterListScreen.kt`

- **顶栏**：`章节` + 「已加载 N 章」+ 返回 + 刷新。
- **结构**：搜索框 → 5 个筛选 chip（带计数）→ 分区头 → 章节行（序号块、标题、告警三角、状态、`n 段`、百分比）→ 分页 footer。
- **交互**：行内「打开剧本」、More 菜单（快速解析/重新生成音频/打开剧本）、滚到倒数第 3 行自动加载。
- **状态**：loading / error / 空 / 筛选空 / footer 四态（错误重试、加载中、已全部、继续上滑）。
- **问题**：**快速解析与重新生成音频未接线（点了零反馈）**；每行 6 类信息 + 2 个 48dp 图标；
  chip 计数只统计已加载项；「已加载 N 章」重复两处；「继续上滑」与实际自动触发不符。

### 2.5 剧本（Script）— `ScriptScreen.kt`

- **顶栏**：`剧本` + `章节名 · N 段` + 返回 + 定位当前。
- **结构**：Hero（台词本 / 章节 / `N 段 · M 段已生成` / 波浪 / 计数）→ 角色 chips → 搜索 → 段落卡列表 → 说明 → 底部 `ScriptMiniBar`。
- **段落卡**：角色头像 + 角色 + 情绪 + 段号 + 台词 + 播放/暂停 + 编辑 + 「已有音频/暂无音频」；当前段高亮。
- **交互**：整卡点击（未接线）、播放/暂停（未接线）、编辑→SegmentEditSheet（唯一可用）、
  角色筛选、搜索、定位当前、MiniBar 上一句/播放/下一句/连播（未接线）、自动跟随滚动。
- **状态**：loading / error / 空（有 `onParseChapter` 才出按钮）/ 筛选空 / 播放中 / 无音频。
- **问题**：整卡点击与播放回调在 `AppNavHost` 全部默认 `{}`；三底栏叠加；首屏 5 行非内容；
  无手势（滑/长按）；当前段被筛掉时无任何提示。

### 2.6 人物（Characters）— `CharactersScreen.kt`

- **顶栏**：`人物音色` + `N 个角色 · M 个已配音` + 返回 + 聚焦搜索。
- **结构**：常驻搜索 → 性别 chips → 重要性 chips → 分区头 → 角色卡（头像 56、名字、重要性、锁定、
  音色 · 性别、别名、note、类别标签、更换音色 + 调参 + More）。
- **交互**：整卡/按钮→VoicePickerSheet；滑杆→TuningSheet；More 4 项（改名/别名/调参/排除）。
- **问题**：改名/别名/排除**未接线**且排除是破坏性操作无确认；卡内 6 行信息；两组筛选常驻；
  chip 计数在 Composable 内 O(n×chips) 重算；搜索框与顶栏放大镜重复。

### 2.7 音色选择 Sheet — `VoicePickerSheet.kt`

- 角色 Hero → 锁定说明 → 搜索 → 档位 chips → 分组音色行（`voiceId · note`、当前标记）→ 底部保存。
- 交互：选音色、搜索、筛选、保存（locked 先二次确认）、关闭。
- **问题**：**不能试听（63 类音色盲选）**；不预览「将改为 X」；无脏数据守卫；行内两个同义点击区；
  保存按钮文案随状态跳变。

### 2.8 角色微调 Sheet — `TuningSheet.kt`

- 角色卡 → 3 个滑杆（语速 0.5–2.0 / 音量 0.0–2.0 / 音调 -12–12，步进 0.05/0.5）→ 恢复默认 → 保存。
- **问题**：单字段「跟随全局」一旦拖动就永久丢失；精度 10dp/档无法到 1.15；不能试听；
  无全局默认刻度；保存按钮在滚动末尾；`onDismiss` 不走 cancel。

### 2.9 播放器（Player）— `PlayerScreen.kt`

- **顶栏**：`正在播放` + `章节 · 本地音频` + 返回 + 章节列表开关。
- **结构**：封面（MaterialShapes.Cookie9Sided + 渐变 + 耳机 pill）→ 标题块（书名/章节/第 i/n 句）
  → 当前句卡 → 波浪进度 + 透明 Slider → 5 个传输按钮（上一句/上一章/播放/下一章/下一句）
  → 倍速 5 chips → 睡眠 4 chips + 剩余时间 → 章节列表（Column+forEach）。
- **问题**：**宿主传入静态空 state → 整页永远空态**；封面 260dp 把控制挤到折叠线下；
  seek 轨道透明只能盲拖；5 个图标语义含混；章节列表非 Lazy 且不自动定位。

### 2.10 任务（Tasks）— `TasksScreen.kt`

- 顶栏 `任务` + 刷新 → 当前任务 Hero（kicker/标题/开始时间/百分比/ETA/波浪/停止）→ 指标条
  → 队列（行尾 chevron）→ 历史卡（状态/结果/时间/错误摘要/chevron）。
- **问题**：只有当前任务能停，队列里其他 running/queued 无入口；历史固定 20 条无分页；
  不自动走时钟；历史卡整体不可点；ETA 在 0%/100% 显示「计算中」。

### 2.11 设置（Settings）— `SettingsScreen.kt`

- 单页 7 组 25 项：API（6）/ 生成（10）/ 网络（2）/ 设备（3）/ 外观（2）/ 存储（2）/ 关于（3）。
- 每项：整行点击 → 弹窗编辑（确认只写草稿）；Key 行 → 密码弹窗 + 清除二次确认；
  主题 `ForgeSegmented`；保存按钮在页面最底部。
- **问题**：25 项无搜索/折叠；改外观要滑 2–3 屏再保存才生效；弹窗按钮叫「保存」却只写草稿；
  「开源许可」与「关于」同一目标；重看引导无确认；数字参数全靠文本弹窗；返回直接丢弃改动；
  tab 页却有返回箭头。

### 2.12 存储（Storage）— `StorageScreen.kt`

- Hero（总占用/可用/波浪/三色分区条/图例）→ 项目占用列表（不可点）→ 保留期（7/30/90 chip + Slider + 输入 + 应用）
  → 清理（孤儿/过期，确认 → 进度 → 结果）。
- **问题**：保留期三种提交时机；项目行不可点；容量口径误导；`scannedAt` 不展示；
  孤儿清理按钮 `danger=false` 但会删文件；输入非法时 chip/滑块仍立即写入。

### 2.13 关于（About）— `AboutScreen.kt`

- Hero（logo/版本/包名/许可/GitHub）→ 诊断卡（Room/FFmpeg/WorkManager/存储 + 最近错误）
  → 7 条许可（可展开占位）→ 分享卡（复制诊断/导出日志）。
- **问题**：复制入口重复且无成功反馈；「导出日志」实际是分享纯文本；许可只有占位文案；
  不展示时间戳；刷新失败保留旧数据易误读。

### 2.14 首次引导（Onboarding）— `OnboardingScreen.kt`

- 3 步：欢迎 / 两套 Key 表单（Key、Base URL、模型、测试连接）/ 权限说明（纯说明，无申请按钮）。
- **问题**：第 3 步没有真正的权限申请；「跳过」（结束引导）与「稍后设置」（跳步）语义接近；
  已存在的 Key 不回显；保存失败只有 banner 无重试；Hero 212dp 在 200% 下挤压表单。

### 2.15 导出 Sheet — `ExportSheet.kt`

- 范围 chips（全部/当前/起止）→ 只读章节列表（≤50）→ 预估 → 开始 → 进度 → 结果（成功/失败/重试/分享）。
- **问题**：章节列表不可勾选；失败列表截断无提示；导出中关闭 X 是 no-op 无禁用样式；取消无确认。

### 2.16 批量生成 Sheet — `BatchSheet.kt`

- 范围/模式/并发/跳过已完成 + 章节勾选 + 预估 + 开始。
- **问题**：与 Project 的「继续生成」入口重叠；勾选状态与筛选不联动。

### 2.17 片段编辑 Sheet — `SegmentEditSheet.kt`

- 段 Hero → 类型（对白/旁白）→ 角色 chips → 情绪 chips（横向 11 项）→ 文本域（2000 字）→ 校验 → 底部三按钮。
- **问题**：脏数据无守卫；「重新生成」静默丢弃草稿；情绪 chip 无分组/无渐隐提示；
  重置与重新生成并排占一半主视觉；校验在输入框与底部卡两处。

---

## 3. 跨屏共性问题（重构必须解决的）

| # | 问题 | 证据 |
|---|---|---|
| 1 | **接线债**：Player 恒空、Script 播放回调空、Characters 三项菜单空 | `AppNavHost.kt:305/415/425` |
| 2 | **重复入口**：导入×3、章节×3、人物×3、打开×2、播放×2 | 4 个屏幕 |
| 3 | **底栏叠加**：Script 迷你条 + 壳层迷你条 + 底栏 | `AdaptiveScaffold` + `ScriptMiniBar` |
| 4 | **反馈不一致**：书架用 Snackbar，其他页静默 | `ProjectScreen`/`ChapterListScreen` |
| 5 | **数字格式不一致**：`156,000` vs `156000` | 书架 vs 项目 Hero |
| 6 | **危险操作确认不一致**：停止/清理/清 Key 有，取消导出/重看引导/丢弃设置无 | 6 处 |
| 7 | **主操作埋在长列表末尾**：保存/开始/应用 | Settings/Export/Storage |
| 8 | **无试听能力**：音色、调参、片段编辑都无法就地听 | 3 个 Sheet |
| 9 | **文案来源分裂**：`strings.xml` + core 硬编码校验文案 + 组件内 3 处硬编码 | `SettingsValidator`、`ForgeComponents` |
| 10 | **无障碍**：图标按钮可传空描述；标题未统一 `heading()`；动画不感知系统设置 | `ForgeComponents.kt:258/396` |

---

## 4. 旧 → 新 映射（本次重构的取舍）

| 旧 | 新 | 说明 |
|---|---|---|
| 3 tabs（书架/任务/设置） | **2 tabs（书架/设置）** | 任务收敛为「书架」顶部的进行中卡片 + TasksSheet |
| 迷你播放器占位 | 真实迷你播放器 | 与播放页共享同一 `PlayerState`，替换而非叠加 |
| 页面内各自画播放条 | 只在壳层画一次 | 删除 `ScriptMiniBar` 式重复 |
| 设置单页 25 项 | 设置根页 6 组入口 + 就地编辑外观 | 模型/生成/存储/关于拆为二级页 |
| 章节内联 More 菜单 | 长按进入多选 + 浮动工具条 | 去掉未接线的死菜单 |
| 导出只读章节列表 | 范围 chip + 预估（可勾选在下一批） | 本次先保证不误导 |
| Lucide 线性图标 | **Material Symbols（Rounded）** | 与 M3 Expressive 规范一致 |
| 手写青绿 40 role | **动态取色优先 + 暖中性/墨绿回退** | 见 `02-design-spec.md` |
| Forge* 17 组件 | 新的 `design/` 组件层 | 全部 icon-first、48dp 触控 |
