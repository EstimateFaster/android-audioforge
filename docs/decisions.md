# 决策记录（ADR / 偏差）

> 任何偏离 `docs/android-execution-plan.md` §3 版本矩阵、§2 ADR 的改动，先在这里记录，再改代码。

## 已确认（来自计划）

- ADR-002：Material 3 Expressive `1.5.0-rc01`；`compileSdk 37`。
- ADR-005：只支持 `arm64-v8a` + `x86_64`。
- ADR-006：完全 BYOK，无服务器。
- ADR-007：单活跃任务 + 队列；默认 5 章/批，上限 20。
- ADR-008：只侧载 release，不上架 Google Play。
- ADR-009：FFmpegKit `dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.9`。
- ADR-010：单模块 `:app` + 包边界；手写 DI。
- ADR-012：v1 不做长章节分块；超长明确报错，v1.1 再做。

## 待记录（DSH 填写）

- 日期 / 变更 / 原因 / 验证命令 / 影响。
