# Forge Framework v1 · 审阅说明

状态：**待审阅**（2026-11-09）  
文件：`docs/prototype/forge-framework.html`  
性质：单文件、自包含、离线可打开的 **HTML 视觉 / 交互框架稿**。不改 Kotlin / Compose / Gradle / CI / tests / strings / `docs/progress.md`；也不改旧稿 `ui-review.html`。

> 本稿只做视觉与交互框架，不代表业务已完成。所有数据、进度、任务、存储数字都是占位；所有“生成 / 导入 / 导出 / 复制 / 测试连接”都不产生网络请求或真实文件。

---

## 1. 怎么打开

直接双击 `forge-framework.html`，或拖进 Chrome / Edge / Firefox。

- 不需要本地服务器、不需要安装依赖、不需要 Gradle。
- 没有 CDN、外部字体、外部图片、外部图标库请求。
- 手机外框内是 360×780 的 App 画面；框外的工具条与备注面板是**审阅外壳**，不是 App UI。
- 如果文件卡片里的预览不能点，请把 HTML 下载后用浏览器标签页直接打开（沙箱预览通常会禁用脚本）。

---

## 2. 页面 / Sheet 清单

11 个页面 + 6 个 Sheet = **17 个可达视图**。

### 页面

| # | 页面 | 路由 | 主操作 / 关键点 |
|---|---|---|---|
| 1 | 首启 Onboarding | `#/onboarding` | 3 步（欢迎 / Key / 权限），唯一主按钮；全屏无底栏 |
| 2 | 书架 Projects | `#/projects` | 导入 FAB；项目卡 2 列；搜索、状态 chips |
| 3 | 项目详情 Project | `#/project/1` | 封面 hero、进度、人物条、章节预览；底部唯一「继续生成」 |
| 4 | 章节 Chapters | `#/chapters/1` | 搜索、状态 chips、行内播放 / 更多 |
| 5 | 剧本 Script | `#/script/1/3` | 角色 chips、台词卡、当前段高亮、底部迷你播放条 |
| 6 | 人物 Characters | `#/characters/1` | 角色卡、锁定、更换音色、调参 |
| 7 | 播放器 Player | `#/player/1` | 大封面、Wavy 进度、播放 FAB、倍速 / 睡眠 / 连续、章节列表；全屏无底栏 |
| 8 | 任务 Tasks | `#/tasks` | 当前任务百分比 + Wavy + 停止；历史任务 |
| 9 | 设置 Settings | `#/settings` | 7 组卡片、主题 segmented、保存 |
| 10 | 存储 Storage | `#/storage` | 总占用、分区条、项目占用、保留期、清理 |
| 11 | 关于 About | `#/about` | 应用 hero、诊断、许可、复制 / 导出 |

### Sheet

| # | Sheet | 直达示例 | 主操作 |
|---|---|---|---|
| 1 | 导入 | `#/projects?sheet=import` | 开始导入 |
| 2 | 批量生成 | `#/project/1?sheet=batch` | 开始批量生成 |
| 3 | 调参 | `#/characters/1?sheet=tuning` | 保存参数 |
| 4 | 片段编辑 | `#/script/1/3?sheet=segment` | 保存修改 |
| 5 | 音色选择 | `#/characters/1?sheet=voice` | 使用此音色 |
| 6 | 导出 | `#/project/1?sheet=export` | 开始导出 |

---

## 3. 审阅外壳怎么用

- **左侧页面清单**：11 页按「首启 / 主导航 / 项目内 / 系统」分组；6 个 Sheet 单独列出。点 Sheet 会先跳到所属页面再打开弹层。
- **外观**：浅色 / 深色；100% / 200% 字号；减少动效。
- **页面状态**：正常 / 空态 / 加载 / 错误。默认全局生效。
  - 勾选「仅钉住当前页面」后，再切状态只覆盖当前页；其他页仍跟随全局。
  - 「清除本页覆盖」让当前页重新跟随全局。
  - Sheet 继承所属页面的状态；Sheet 内不单独切换状态。
- **审阅备注**：按页面 / Sheet 自动保存到浏览器 `localStorage`。
  - 「复制全部备注」输出 `【页面名】+ 备注`，可直接粘贴回传。
  - 可单条删除或清空；清除浏览器数据会丢失备注。
- **键盘**：`Tab` / `Shift+Tab` 遍历，`Enter` / `Space` 触发；`Esc` 关闭 Sheet，无 Sheet 时返回上一页；浏览器前进 / 后退可用（hash 路由）。
- **交互自检**：工具条左上角显示「已启用」表示脚本正常。若不响应，先 `Ctrl+F5` 硬刷新。
- **直链审阅参数**（方便定点走查 / 截图，不影响正常使用）：
  - `?theme=light|dark`
  - `?font=100|200`
  - `?motion=reduced`
  - `?state=normal|empty|loading|error`
  - 例：`forge-framework.html?theme=dark&font=200&state=empty#/chapters/1`

---

## 4. 推荐走查路径

1. 首启 Onboarding：欢迎 → Key → 权限 → 开始使用。
2. 书架：搜索 / 筛选 / 导入 Sheet → 项目详情。
3. 项目详情：进度、人物条、章节预览 →「继续生成」。
4. 章节 → 剧本 → 片段编辑 Sheet；点台词播放 / 暂停 / 上一句 / 下一句。
5. 人物 → 更换音色 Sheet / 调参 Sheet。
6. 播放器：大封面、Wavy 进度、倍速 / 睡眠 / 连续、章节切换。
7. 任务：停止 / 从断点继续 / 历史任务。
8. 设置：7 组、主题 segmented、保存；进入存储 / 关于 / 重新引导。
9. 切深色 + 200% + 减少动效，抽查全部页面与 6 个 Sheet。
10. 用右上「审阅备注」逐页记录，最后「复制全部备注」。

---

## 5. 设计口径

- **色彩**：Seed `#0D9488`（青绿），石墨 `#18181B`；secondary `#0891B2`（青蓝），tertiary `#D97706`（音频琥珀），error 红橙 `#DC2626`。提供 light / dark 两套完整 role：
  - primary / onPrimary / primaryContainer / onPrimaryContainer
  - secondary / tertiary / error 全套
  - surface / surfaceDim / surfaceBright + surfaceContainerLowest/Low/Container/High/Highest 5 级分层
  - onSurface / onSurfaceVariant / outline / outlineVariant / inverseSurface
- **字体**：中文系统字体栈；display 31 / h1 23 / h2 17 / body 14 / label 9.5（100% 基准），200% 时只放大文字，不缩放 dp 与图标。
- **形状**：16 / 18 / 20 / 24 / 26 / 30 圆角；封面 / 播放器大图用 CSS 形状（squircle / 有机圆）占位。
- **图标**：全部为 **Lucide** 内联 SVG `<symbol>`，共 95 个；来源为本地 `lucide-android.aar`（`com.composables:icons-lucide-android:2.2.1`）的 vector drawable pathData。无 emoji、无文字图标、无手绘。
- **按钮规则**：
  - 主操作 = Lucide 图标 + 文字，实心；
  - 次操作 = 图标 + 文字，tonal / outlined；
  - 图标操作 = 仅图标 + `aria-label`，触控区 ≥44×44；
  - 没有无图标的纯文字按钮；普通标题 / 文本不可点击。
- **动效**：CSS transition / keyframes；统一 150–500ms；减少动效时只保留淡入或直接静态。
- **可访问性**：Sheet 有 `role="dialog"`、`aria-modal`、焦点管理、Esc 与 Tab 焦点循环；图标按钮有中文 `aria-label`；进度有 `role="progressbar"`。

---

## 6. 已知假设 / 占位（请重点审）

1. **IA 默认 3 tabs**：书架 / 任务 / 设置。项目、章节、剧本、人物、播放器是项目内二级页；存储 / 关于挂在设置下。**可以改**，组件骨架不受影响。
2. **Project 主操作是底部固定 action dock**（不是 FAB），避免遮挡内容；书架页的「导入」仍是 extended FAB。
3. **Onboarding 与 Player 全屏**，隐藏底栏；其他页面保留底栏。迷你播放器只在有播放会话后出现；Script 页底部是专用的上一句 / 播放 / 下一句 / 连续迷你条。
4. 播放器、导入、批量、导出、清理、保存、测试连接、复制诊断都是**本地状态模拟**：不请求网络、不读本地文件、不写系统文件、不发通知。
5. **200% 字号**用 `--ts:2` 只放大文字；部分网格在 200% 下改为单列、Project 底栏按钮变为整行、Sheet footer 隐藏说明文字，这是有意为之。
6. **主题有两层**：左侧工具条控制手机画面；设置页的主题 segmented 也只改手机画面。审阅外壳的浅 / 深色由工具条控制，二者可能不同步，属于审阅工具行为。
7. **Sheet 不进入浏览器历史**（用 `replaceState`）；Esc / 关闭按钮 / 遮罩都能关；页面跳转使用 hash，前进后退可用。
8. **备注存 localStorage**，不写回 Android 工程；文件名 `forge-framework-shell-v1` / `forge-framework-notes-v1`。
9. 高密度列表里图标按钮的**点击区为 44×44**，视觉图标保持 17–21px；这是触控规范与信息密度的折中。
10. 未实现 / 待定的业务：Key 真实校验、Saf 文件选择、WorkManager 真实任务、TTS 试听、导出写文件、系统分享、删除二次确认、锁定角色换音色二次确认、章节批量选择。
11. 数据（书名 / 章节 / 角色 / 台词 / 任务 / 存储）是静态占位，统一放在 HTML 内的 `DATA` 常量里，方便替换。
12. 本稿不含任何真实或形似完整的 API Key；设置页显示的是 `sk-…7f2a` 之类的掩码样式。

---

## 7. 想请你拍板的地方

1. **3 tabs 还是 4 tabs**（是否把 Player 提为第 4 个 tab）。
2. **Project 主操作**：当前是底部整行 dock；是否换成右下 FAB。
3. **Characters 的「更换音色」**：当前是 tonal（每张卡都有，避免过强）；是否要升为实心 primary。
4. **Storage / About 是否隐藏底栏**：当前隐藏以突出内容；是否要保留 3 tabs。
5. **200% 下 Project 的章节预览**只露出 1 行，是否接受以纵向滚动换密度。
6. **Sheet 的底部说明文字**：100% 保留；200% 隐藏。是否需要改成可展开的说明。

---

## 8. 反馈方式

1. 在右侧「审阅备注」按页面 / Sheet 写意见（自动保存）。
2. 点「复制全部备注」，把文本粘贴回传；或直接指出「页面 + 控件 + 期望」。
3. 需要定点讨论时，把直链（含 `?theme/font/state` 与 `#/route?sheet=`）一并附上。

## 9. 文件

- `docs/prototype/forge-framework.html` — 唯一交付物，单文件自包含，约 285 KB（含内联 Lucide 图标）
- `docs/prototype/README-Forge.md` — 本说明

本分支只新增以上两个文件，未修改 `docs/prototype/ui-review.html` 及任何 Kotlin / Gradle / CI / tests / strings / progress 文档。
