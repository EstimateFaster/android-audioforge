# P5-12 文案 / 字符串审计

本文件记录 P4-11 / P5-12 的扫描方法、字符串合并 / 删除清单与剩余风险。范围限定为
`feature/**`、`ui/components/**` 与 `res/values/strings.xml`；不涉及导航、`core/`、
`data/`、`domain/` 的改动。

## 1. 扫描方法

### 1.1 硬编码中文扫描

对 `app/src/main/java/com/estimatefaster/audioforge/feature/**` 与
`ui/components/**` 的 `.kt` 文件执行：

```bash
grep -rnP '"[^"\n]*[\x{4e00}-\x{9fff}]' \
  app/src/main/java/com/estimatefaster/audioforge/feature \
  app/src/main/java/com/estimatefaster/audioforge/ui/components \
  --include='*.kt'
```

人工分类后按以下规则排除，不把预览数据误判成正式界面文案：

- 行尾 / 块注释与 KDoc 中的中文；
- 文件末尾 `// Preview` 分隔注释之后的 `@Preview` 示例数据（`preview*` 辅助函数、
  `RoleAvatarSamples` 等只用于设计预览）；
- `require` / `check` / `error` / `IllegalArgumentException` /
  `IllegalStateException` 中的开发者断言；
- 纯状态层诊断导出标记 `DIAGNOSTIC_TRUNCATED_MARKER`（没有 `Context`，见 §4）。

同一规则固化在
`app/src/test/java/com/estimatefaster/audioforge/ui/NoHardcodedChineseTest.kt`：
扫描器自己实现注释 / 字符串状态机，保留行号；预览区整体跳过；断言文案按前 4 行
上下文识别；白名单只有诊断标记。测试还包含一条合成用例，证明注释、KDoc、
Preview 与 `require` 不会被误报。

### 1.2 引用与重复检查

- 用 `R.string.<name>` 全仓反查引用（`app/src/main/java`、`app/src/test`），确认
  删除项没有残留引用、新增项都有调用方。
- 解析 `strings.xml` 检查 `name` 唯一、XML 合法。
- 对 `<string>` 值做完全相等分组，合并“同名同义”的通用动作文案。

## 2. 新增 / 合并后的通用动作

新增 7 个通用 key，并保留原有格式化占位符：

| key | 值 | 合并自 |
|---|---|---|
| `action_dismiss` | `关闭` | `tasks_action_dismiss`（语义由“知道了”改为通用“关闭”） |
| `action_more` | `更多` | `project_more`、`chapter_list_item_more`、`script_segment_more`、`characters_more` |
| `action_rename` | `重命名` | `project_more_rename`、`characters_action_rename` |
| `action_refresh` | `刷新` | `storage_refresh`、`about_refresh` |
| `action_saving` | `保存中…` | `onboarding_action_saving`、`settings_saving`、`segment_edit_saving`、`tuning_saving`、`voice_picker_saving` |
| `action_failed` | `操作失败：%1$s` | `project_library_action_error`、`tasks_action_error` |
| `action_save_error` | `保存失败：%1$s` | `settings_save_error`、`segment_edit_save_error`、`tuning_save_error`、`voice_picker_save_error` |

另有 6 个既有通用 key 被复用到原上下文专用 key：

- `action_retry` ← `segment_edit_retry`、`voice_picker_retry`
- `action_cancel` ← `batch_action_cancel`、`segment_edit_cancel`、`voice_picker_cancel`
- `action_save` ← `segment_edit_save`
- `action_delete` ← `project_more_delete`
- `project_library_empty_title` / `project_library_empty_description` ←
  `empty_state_demo_title` / `empty_state_demo_description`（`EmptyState` 预览改用
  真实项目库空态文案）

## 3. 删除清单（29 项）

| 删除的 key | 原值 | 替代 |
|---|---|---|
| `empty_state_demo_title` | 还没有项目 | `project_library_empty_title` |
| `empty_state_demo_description` | 导入一本 txt 小说，开始生成多人演播有声书。 | `project_library_empty_description` |
| `project_more` | 更多 | `action_more` |
| `chapter_list_item_more` | 更多 | `action_more` |
| `script_segment_more` | 更多 | `action_more` |
| `characters_more` | 更多 | `action_more` |
| `project_more_rename` | 重命名 | `action_rename` |
| `characters_action_rename` | 重命名 | `action_rename` |
| `storage_refresh` | 刷新 | `action_refresh` |
| `about_refresh` | 刷新 | `action_refresh` |
| `onboarding_action_saving` | 正在保存… | `action_saving` |
| `settings_saving` | 正在保存… | `action_saving` |
| `segment_edit_saving` | 保存中… | `action_saving` |
| `tuning_saving` | 保存中… | `action_saving` |
| `voice_picker_saving` | 保存中… | `action_saving` |
| `project_library_action_error` | 操作失败：%1$s | `action_failed` |
| `tasks_action_error` | 操作失败：%1$s | `action_failed` |
| `settings_save_error` | 保存失败：%1$s | `action_save_error` |
| `segment_edit_save_error` | 保存失败：%1$s | `action_save_error` |
| `tuning_save_error` | 保存失败：%1$s | `action_save_error` |
| `voice_picker_save_error` | 保存失败：%1$s | `action_save_error` |
| `segment_edit_retry` | 重试 | `action_retry` |
| `voice_picker_retry` | 重试 | `action_retry` |
| `batch_action_cancel` | 取消 | `action_cancel` |
| `segment_edit_cancel` | 取消 | `action_cancel` |
| `voice_picker_cancel` | 取消 | `action_cancel` |
| `segment_edit_save` | 保存 | `action_save` |
| `project_more_delete` | 删除 | `action_delete` |
| `tasks_action_dismiss` | 知道了 | `action_dismiss` |

`strings.xml` 数量从 823 降到 801（新增 7、删除 29、净减 22）。全仓
`R.string.*` 反查结果为 0 个缺失引用、0 个未引用字符串。

## 4. 状态补齐清单（P4-11）

新增共享组件：

- `ui/components/ErrorBanner.kt`：行内错误条，支持重试与关闭，容器为
  `LiveRegionMode.Polite`，按钮触控高度 ≥48dp；用于“页面已有内容时的局部失败”。
- `ui/components/ScreenState.kt`：`LoadingState` 与
  `ScreenState.Loading/Empty/Error/Content` 统一包装；整页状态一次只显示一种，
  加载态占满可用空间，避免首帧白屏。

接入与补齐：

| 屏幕 | 处理 |
|---|---|
| 项目库 | 已具备 loading / 空态（导入按钮）/ 错误重试；操作错误继续用唯一 Snackbar，未叠加 ErrorBanner |
| 项目详情 | 已具备 loading / 空章节（返回项目库）/ 错误重试 |
| 章节列表 | 首屏 loading / 空态 / 错误重试已具备；加载更多的错误行改为 `ErrorBanner` + 重试 |
| 剧本 | 已具备 loading / 空态（解析本章）/ 筛选空态 / 错误重试 |
| 人物 | 已具备 loading / 空态 / 筛选空态 / 错误重试 |
| 任务 | loading 改用 `LoadingState`；空态新增“刷新”动作；操作错误改用 `ErrorBanner`（关闭），不再自绘错误卡 |
| 设置 | 整页 loading / 错误改用 `ScreenState`；保存失败改 `ErrorBanner` + 重试；Key 操作失败改 `ErrorBanner` |
| 播放器 | 已具备 loading / 空态（返回剧本）/ 错误重试 |
| 存储 | loading 改用 `LoadingState`；首屏错误保留整页 `ErrorState`；已有数据时的刷新失败改 `ErrorBanner` + 重试 |
| 关于 | loading 改用 `LoadingState`；首屏错误保留整页 `ErrorState`；已有数据时的刷新失败改 `ErrorBanner` + 重试 |
| 导出 | loading / 错误重试已具备；空态新增“重新加载”动作 |
| 导入 | 已具备进度、错误面板重试、编码选择、重名确认 |
| 批次 | 已具备启动/停止状态卡、校验错误、缺 Key、范围校验；开始按钮本身即重试入口 |
| 调参 | 已具备 loading / 错误重试 / 保存失败重试 |
| 音色 | 空音色库新增“关闭”动作；筛选空态与保存失败重试已具备 |
| 片段编辑 | 已具备校验错误与保存失败重试；数据在打开时已就绪，无首屏 loading |
| Onboarding | 保存中状态已具备；保存失败改为 `ErrorBanner`；Key 测试失败就地提示 |

`ErrorBanner` 与 Snackbar 没有同屏叠加：全仓只有项目库使用 Snackbar，其余局部
错误统一走 `ErrorBanner`；整页错误仍走 `ErrorState`，不与行内错误同时渲染。

## 5. 剩余风险

1. `AboutUiState.DIAGNOSTIC_TRUNCATED_MARKER` 仍是纯 Kotlin 常量
   （`\n…（诊断信息已截断）`）。它用于复制/导出诊断文本，所在状态层没有
   `Context`；迁入 `strings.xml` 需要把资源或标记注入 ViewModel，超出本次
   “只改 ui/components、feature、strings” 的最小改动范围。扫描器将其作为唯一
   白名单，若后续把诊断文本生成移到 Composable 层，应一并迁移。
2. `ui/HomeScreen.kt` 仍有一处硬编码“收起”。该文件不在本次允许修改的
   `ui/components/**` 范围内，且该页面只被自身 `@Preview` 引用、未接入
   `AppNavHost`；建议在后续清理 Phase 0 演示页时删除或迁入资源。
3. `data/` / `domain/` 返回的动态错误文本（如导入失败原因、任务 `lastError`、
   片段 `errorMessage`）可能是中文，但它们是运行时数据而非 `feature/**` 中的
   硬编码字面量。要完全资源化需要改用例/仓库异常契约，超出本次范围；界面继续
   原样展示并保留重试入口。
4. `@Preview` 示例数据、`require`/`check` 断言仍含中文是有意保留：前者只用于
   设计预览，后者只给开发者看。`NoHardcodedChineseTest` 已把两类排除，避免
   误报。
5. 字符串表中仍有大量同值但语义不同的 key（如“已知/未知”“状态标签”“章节”
   在不同页面的不同上下文）。本次只合并“同名同义”的通用动作，未强行合并
   语义可能分叉的 key，避免未来翻译或文案微调时互相牵连。
6. 本次禁止本地构建/CI，未运行 Gradle 编译与 lint；已做 XML 解析、全仓字符串
   引用反查、源码扫描器自测与括号平衡检查，最终以 CI 为准。
