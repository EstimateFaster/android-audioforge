# 交付说明（纯 UI 预览构建）

> 分支：`ui-builder` · 模块：`uipreview/` · APK：见下方直链
> 更新方式：每次 push 到 `ui-builder`，CI 重新构建并覆盖同一个 Release 资产（链接不变）。

## 1. 下载与安装

```
https://github.com/EstimateFaster/android-audioforge/releases/download/ui-preview/AudioForge-UI-Preview.apk
```

- 包名 `com.estimatefaster.audioforge.uipreview.debug`，与正式 app 共存，不会覆盖数据。
- minSdk 26 / target 36；arm64-v8a、x86_64（模拟器可用）。
- 首次安装需要允许「安装未知来源应用」。

选择 GitHub Release 直链而不是 Actions Artifacts 的原因：Artifacts 需要登录 + zip 解包，国内下载慢；
Release 资产是单文件直链，可断点续传，链接永久稳定。

截图包（模拟器逐屏）：`releases/download/ui-preview-shots/ui-preview-shots.zip`

## 2. 这一版做了什么

**设计语言**：Material 3 Expressive（`MaterialExpressiveTheme` + `MotionScheme.expressive`），
Material Symbols（Rounded）图标，动态取色优先、暖中性 + 墨绿回退，全部中文文案。

**页面（15 个 + 8 个 Sheet）**

| # | 页面 | 关键交互 |
|---|---|---|
| 1 | 书架 | 继续收听卡、任务行、项目列表、搜索展开、排序、导入 |
| 2 | 项目详情 | 进度、继续生成、章节前 4 行、人物条、导出/删除 |
| 3 | 章节 | 状态筛选、搜索、长按多选 + 浮动工具条 |
| 4 | 剧本 | 角色筛选、跟随播放、定位当前、点句播放、长按复制、编辑 |
| 5 | 播放器 | 拖动进度、上一句/下一句、倍速、睡眠定时、连播、章节切换 |
| 6 | 人物与音色 | 角色列表、音色选择（搜索）、角色微调（三滑杆） |
| 7 | 任务 | 当前任务进度、停止确认、队列、历史 |
| 8 | 存储 | 占用分区、项目占用、保留期、清理确认 |
| 9 | 关于 | 版本、诊断项、许可、复制诊断 |
| 10 | 设置 | 主题/字号/动态取色即时生效，5 个二级入口 |
| 11 | 模型与密钥 | Base URL / 模型 / Key 编辑（Key 掩码）、测试连接 |
| 12 | 生成默认值 | 5 个参数滑杆 + 恢复默认 |
| 13 | 网络与设备 | 4 个开关 + 电量阈值 |
| 14 | 首次引导 | 3 步（欢迎 / 连接模型 / 权限） |
| 15 | 设计实验室 | 主题、字号、规范标注、逐页四态 |

Sheet：导入、导出、批量生成、音色选择、角色微调、片段编辑、值编辑、任务（已并入任务页）。

## 3. 这一版刻意「减掉」的东西

| 减掉 | 原因 |
|---|---|
| 书架 FAB | 会压住列表与筛选 chip；导入改为顶栏图标 + 空态主按钮 |
| 书架总览/统计卡 | 一屏四块非内容区，用户看不到项目 |
| 书架常驻筛选 chip | 收进搜索态，减少常驻噪声 |
| 项目页统计网格 + Split Button 下拉 | 一屏两个「进度层」；范围选择移入批量生成 Sheet |
| 项目页任务进度块 | 与书架任务行重复，任务只在书架一行 + 任务页 |
| 播放器副标题、底部说明、上一章/下一章常驻 | 叠在封面上、纯噪声；章节切换收进章节 Sheet |
| 剧本底部迷你播放条 | 与全局迷你播放器重复（旧版三条底栏） |
| 常驻迷你播放器 / 「未在播放」占位 | 只在开始播放后的内容页出现；设置/人物/任务/存储/关于不再挂一条播放器 |
| 设置页 25 项平铺 | 改为 5 个二级页 + 3 个外观控件 |
| 纯文字按钮 | 全部动作都有图标；图标按钮 48dp |

## 4. 验收走查（10 分钟）

1. 书架：点「继续收听」→ 播放器；返回；点任务行 → 任务页；返回。
2. 顶栏搜索 → 输入「明」→ 清空；右上菜单 → 切换排序。
3. 打开「明朝那些事儿」→ 继续生成（批量 Sheet）→ 选范围 → 开始生成。
4. 章节 → 长按任意行（多选工具条）→ 取消；筛选「失败」→ 打开那一章。
5. 剧本：点第 5 句 → 从该句播放；关闭「跟随播放」→ 手动滚动；长按一句 → 复制提示。
6. 播放器：拖动进度 → 时间跟随；倍速 1.5×；睡眠 15 分钟 → chip 显示倒计时；章节 → 切章。
7. 设置：切深色 → 立即生效；字号 200% → 回书架看排版；动态取色开关。
8. 设置 → 模型与密钥：改 Base URL / Key（掩码）→ 保存提示。
9. 设置 → 存储：清理孤儿文件 → 确认；保留期切 30 天。
10. 设置 → 设计实验室：逐页切「加载/空/错误」→ 回到对应页面查看；打开「显示设计规范」。

## 5. 边界（重要）

- **不含业务**：所有数据是 `FakeData` 的占位内容；没有网络、没有文件读写、没有 WorkManager。
- 少数动作以 Snackbar 说明「预览构建：…」而不是真的执行（导出、清理、分享、测试连接）。
- 播放是本地 ticker 模拟，不产生声音。
- 设计实验室的「页面状态」只影响渲染，不影响数据。
- 200% 字号下部分页面会变长，这是预期行为（不裁切为优先）。

## 6. 与正式 app 的关系

`uipreview` 是独立 applicationId 的模块，不引用 `:app` 的任何代码：
只依赖 Compose + material3 + Material Symbols 资源。正式接线时按页搬运：

1. 把 `design/`（组件）与 `theme/`（token）复制到 `:app` 的 `ui/` 下；
2. 每个屏幕用真实 `UiState` 替换 `FakeData`，保留 `Composable` 结构与交互；
3. `Dest` → 换成现有的 `Route` 类型安全路由（字段一一对应）；
4. `PlayerState` → 换成现有 `PlayerViewModel`/`PlayerController`。

## 7. CI

`.github/workflows/ui-preview.yml`：

- `build`：`:uipreview:assembleDebug` → 覆盖 `ui-preview` Release 的 `AudioForge-UI-Preview.apk`。
- `screenshots`（可选）：模拟器逐屏 `screencap` → 覆盖 `ui-preview-shots` Release。
- 提交信息含 `[no-shots]` 时跳过截图任务。
- 本机 github.com 不可达，分支通过 GitHub Git Data API 推送（`/tmp/ghpush.py`，一次性脚本）。
