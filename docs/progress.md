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
| P0-05 Room smoke | 🟡 in_progress | 代码+Robolectric 读写测试已提交，等 CI 绿 |
| P0-06 WorkManager 前台服务 smoke | ⏳ todo | 需要 Worker + 通知 + 真机 |
| P0-07 FFmpeg smoke | ⏳ todo | 真机/模拟器 `-version` + 静音/拼接 |
| P0-08 CI debug 构建与 APK 校验 | ✅ done | CI run 42442128213 绿；debug APK artifact 已上传 |
| P0-09 Release 签名 CI | ✅ done | v0.1.0 已发布；签名 release APK + checksums |
| P0-10 进度/决策文档 | ✅ done | 本文件 + plan |
| P0-11 真机/模拟器 smoke 清单 | ⏳ todo | 等 P0-05–P0-07 |
| P0-12 Phase 0 门禁 | ⏳ todo | 全部 smoke 后 |

## 任务记录

### P0-05 Room smoke — in_progress
- 开始：2026-11-08 11:55（Asia/Shanghai）
- 分支：`feat/p0-05-room-smoke`
- 目标：最小 `ProbeEntity` / `ProbeDao` / `AppDatabase`；Debug 启动写入+回读；KSP 导出 schema
- 依赖：P0-02
- 验收：CI 的 `assembleDebug`（含 KSP）无错；`testDebugUnitTest` 的 Room 读写用例绿；
  `app/schemas/com.estimatefaster.audioforge.data.db.AppDatabase/1.json` 生成并入库
- 说明：本 VM 不再跑本地 Gradle/CI（新约束），验证以 GitHub Actions 为准

## 已知实编坑（已处理）

- KSP + AGP 9 内置 Kotlin：`android.disallowKotlinSourceSets=false`。
- `kotlinx-serialization-json` 必须是 **1.9.0**（1.12.0 与 Kotlin 2.2.10 不兼容）。
- SDK 包名是 `platforms;android-37.0`。
- Expressive 组件需要 `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`。

## 已发布

- **v0.1.0**：https://github.com/EstimateFaster/android-audioforge/releases/tag/v0.1.0
  - `audioforge-0.1.0.apk`（约 60.5 MB）
  - `checksums.txt`
- CI：https://github.com/EstimateFaster/android-audioforge/actions

## 下一步

1. DSH 做 P0-05 / P0-06 / P0-07 真机 smoke。
2. 完成 P0-11 / P0-12 门禁。
3. 进入 P1-01（模型/枚举）与 P1-02（提示词/音色池）。

## 日志

- 2026-11-08：初始仓库/构建配置/CI/文档落地；本地实编 `assembleDebug` 通过。
- 2026-11-08：CI 首次失败（`android-actions/setup-android@v3` 的 `sdkmanager tools` 在新 cmdline-tools 下找不到包）；改为直接调用 runner 的 sdkmanager，CI 4m25s 绿。
- 2026-11-08：`v0.1.0` release workflow 绿；签名 APK + checksums 已发布。
