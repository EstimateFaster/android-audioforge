# 进度（DSH 维护）

> 规则：每个任务完成即更新；格式见 `docs/android-execution-plan.md` §17。
> 当前阶段：**Phase 0 — 基础与 smoke**

## Phase 0

| 任务 | 状态 | 备注 |
|---|---|---|
| P0-01 初始化仓库与 wrapper | ✅ done | 仓库、wrapper、docs 已就绪 |
| P0-02 Gradle 工程与版本矩阵 | ✅ done | AGP 9.5.1 / Kotlin 2.2.10 / compileSdk 37；本地实编通过 |
| P0-03 Manifest / 权限 / Service | ✅ done | WorkManager `dataSync` 合并；FileProvider；占位图标 |
| P0-04 Compose 空壳与 Expressive 主题 | ✅ done | `MaterialExpressiveTheme` + `MotionScheme.expressive()`；首页空壳 |
| P0-05 Room smoke | ⏳ todo | 需要 Entity/DAO/Database + 真机读写 |
| P0-06 WorkManager 前台服务 smoke | ⏳ todo | 需要 Worker + 通知 + 真机 |
| P0-07 FFmpeg smoke | ⏳ todo | 真机/模拟器 `-version` + 静音/拼接 |
| P0-08 CI debug 构建与 APK 校验 | ⏳ in_progress | workflow 已写；等首次 CI 结果 |
| P0-09 Release 签名 CI | ⏳ todo | 需要 4 个 Secrets |
| P0-10 进度/决策文档 | ✅ done | 本文件 + plan |
| P0-11 真机/模拟器 smoke 清单 | ⏳ todo | 等 P0-05–P0-07 |
| P0-12 Phase 0 门禁 | ⏳ todo | 全部 smoke 后 |

## 已知实编坑（已处理）

- KSP + AGP 9 内置 Kotlin：`android.disallowKotlinSourceSets=false`。
- `kotlinx-serialization-json` 必须是 **1.9.0**（1.12.0 与 Kotlin 2.2.10 不兼容）。
- SDK 包名是 `platforms;android-37.0`。
- Expressive 组件需要 `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`。

## 下一步

1. 等 CI 绿（P0-08）。
2. DSH 做 P0-05 / P0-06 / P0-07 真机 smoke。
3. 配 Secrets，打 `v0.1.0` 验证 release（P0-09）。
4. 进入 P1-01。

## 日志

- 2026-11-08：初始仓库/构建配置/CI/文档落地；本地实编 `assembleDebug` 通过。
