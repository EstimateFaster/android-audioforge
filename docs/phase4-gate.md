# Phase 4 门禁（P4-15）

日期：2026-11-09（Asia/Shanghai）
基线：main（P5-13 合并后，b654e2e）
验证：GitHub Actions（Android CI / emulator smoke）；未做本地构建。

## 任务状态

| 任务 | 状态 | 证据 |
|---|---|---|
| P4-01 导航/自适应壳 | ✅ | 类型安全路由/底栏 rail/深链 |
| P4-02 设计系统 | ✅ | 6 组件 + 12 Preview |
| P4-03 项目库 | ✅ | 搜索/卡片/删除/空态 |
| P4-04 导入 Sheet | ✅ | SAF/编码重试/重名/进度 |
| P4-05 项目详情 | ✅ | 进度/控制/告警/人物 |
| P4-06 批次 Sheet | ✅ | 模式/范围/估算/无 Key |
| P4-07 章节列表 | ✅ | 搜索/筛选/分页/2000 章 |
| P4-08 任务页 | ✅ | 当前/历史/ETA/停止 |
| P4-09 全局设置 | ✅ | 7 组/校验/Key 掩码 |
| P4-10 Onboarding | ✅ | 3 步/Key 测试/跳过 |
| P4-11 空/加载/错误态 | ✅ | ScreenState/ErrorBanner 全屏补齐 |
| P4-12 无障碍/字体 | ✅ | 由 P5-13 覆盖（AdaptiveFieldRow/FlowRow/liveRegion） |
| P4-13 UI 测试 | ✅ | 由 P5-14 instrumented 覆盖 |
| P4-14 性能 | ✅ | docs/performance-review.md（基线待真机） |
| P4-15 门禁 | ✅ | 本文档 + 接线（PR #88） |

## 验收

- 三栏切换/返回栈/深链：NavWiringTest + AppNavigationTest。
- 2000 章项目卡/章节列表：状态测试 + 稳定 key。
- 所有 feature 页面经 AppNavHost 可达（PR #88）。
- 空/加载/错误：NoHardcodedChineseTest + 屏审计（PR #89）。
- CI：Android CI 绿；smoke 见 Actions。

## 例外

- P4-14 真机性能基线 BLOCKED（无设备）；P6-07 给出测量方法与目标。
- TalkBack 主流程走查需真机（P5-13 已修复可静态验证项）。
