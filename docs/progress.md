# 进度（DSH 维护）

> 规则：每个任务完成即更新；格式见 `docs/android-execution-plan.md` §17。
> 验证方式（2026-11-08 起）：**本机不再跑 Gradle/CI**，所有构建与测试由 GitHub Actions 执行。
> 当前阶段：**Phase 0 — 基础与 smoke**

## Phase 0

| 任务 | 状态 | 备注 |
|---|---|---|
| P0-01 初始化仓库与 wrapper | ✅ done | 仓库、wrapper、docs 已就绪 |
| P0-02 Gradle 工程与版本矩阵 | ✅ done | AGP 9.5.1 / Kotlin 2.2.10 / compileSdk 37；本地实编通过 |
| P0-03 Manifest / 权限 / Service | ✅ done | WorkManager `dataSync` 合并；FileProvider；占位图标 |
| P0-04 Compose 空壳与 Expressive 主题 | ✅ done | `MaterialExpressiveTheme` + `MotionScheme.expressive()`；首页空壳 |
| P0-05 Room smoke | ✅ done | CI 42444925547 绿；`RoomProbeTest` 3/3；schema `1.json` 已入库 |
| P0-06 WorkManager 前台服务 smoke | ✅ done | Smoke 42446113920 绿；Worker SUCCEEDED + 通知可见 + 取消 CANCELLED |
| P0-07 FFmpeg smoke | ✅ done | Smoke 42446646033 绿；`-version`/静音/拼接/ffprobe 全过 |
| P0-08 CI debug 构建与 APK 校验 | ✅ done | CI run 42442128213 绿；debug APK artifact 已上传 |
| P0-09 Release 签名 CI | ✅ done | v0.1.0 已发布；签名 release APK + checksums |
| P0-10 进度/决策文档 | ✅ done | 本文件 + plan |
| P0-11 真机/模拟器 smoke 清单 | 🟡 in_progress | 清单 `docs/phase0-smoke.md`；补截图/设备信息 |
| P0-12 Phase 0 门禁 | ⏳ todo | 全部 smoke 后 |

## 任务记录

### P0-05 Room smoke — done
- 完成：2026-11-08 12:05（Asia/Shanghai）
- 分支：`feat/p0-05-room-smoke`（PR #1）
- 目标：最小 `ProbeEntity` / `ProbeDao` / `AppDatabase`；Debug 启动写入+回读；KSP 导出 schema
- 依赖：P0-02
- 提交：`487be46`（实现）、`531a693`（CI 回传测试报告）、schema 随本记录提交
- 验证：CI run [42444925547](https://github.com/EstimateFaster/android-audioforge/actions/runs/42444925547) 绿
  - `lintDebug testDebugUnitTest assembleDebug` 通过（58s）
  - `RoomProbeTest`：tests=3 / failures=0（Robolectric 真跑 Room 建库、写入、回读、失败态）
  - KSP 生成 `app/schemas/com.estimatefaster.audioforge.data.db.AppDatabase/1.json`
  - debug APK artifact 已上传
- 说明：本机不再跑本地 Gradle/CI（新约束），验证以 GitHub Actions 为准
- 遗留：真机/模拟器上的启动 smoke 归入 P0-11；P2-01 建正式实体时删除 `probe` 表并升 version

### P0-06 WorkManager / 前台服务 smoke — done
- 完成：2026-11-08 12:26（Asia/Shanghai）
- 分支：`feat/p0-06-workmanager-smoke`（PR #2）
- 目标：`ProbeWorker`（CoroutineWorker + `setForeground` dataSync + 进度）→ Room 写入 → 完成通知；
  首页 Debug 卡片可启动/停止并显示状态
- 依赖：P0-03、P0-05
- 提交：`2ceeb2b`（实现）、`6c42715`（smoke 触发）、`a523c89`（设备 smoke 发现的 PRAGMA 修复）
- 验证：
  - 普通 CI [42446113912](https://github.com/EstimateFaster/android-audioforge/actions/runs/42446113912) 绿；
    `RoomProbeTest` 4/4（新增 `create()` + PRAGMA 用例）
  - Smoke [42446113920](https://github.com/EstimateFaster/android-audioforge/actions/runs/42446113920) 绿，API 35 emulator：
    - `foreground_worker_runs_and_posts_notification`：Worker SUCCEEDED 且运行期间 `dataSync` 通知可见
    - `cancelling_worker_ends_with_cancelled_state`：取消后 CANCELLED，进程不崩
    - `probe_writes_and_reads_back_on_device`：设备 SQLite 读写通过
- 新增：`.github/workflows/smoke.yml`（PR 改动 `app/src/main`/`androidTest` 时自动跑，也可手动 dispatch）
- 遗留：通知「停止」action、锁屏 30 分钟、国产 ROM 归入 P0-11 / P3

### P0-07 FFmpeg smoke — done
- 完成：2026-11-08 12:40（Asia/Shanghai）
- 分支：`feat/p0-07-ffmpeg-smoke`（PR #3）
- 提交：`12e3e69`（实现，含测试与调试卡）
- 验证：
  - 普通 CI [42446646024](https://github.com/EstimateFaster/android-audioforge/actions/runs/42446646024) 绿（`assembleDebug` 打包 FFmpeg AAR 成功）
  - Smoke [42446646033](https://github.com/EstimateFaster/android-audioforge/actions/runs/42446646033) 绿，API 35 x86_64：
    `FfmpegInstrumentedTest.version_silence_and_concat` 通过（4/4，0.989s）
    - `-version` 成功
    - 350ms 静音 mp3 非空
    - 440/660Hz 两段拼接，ffprobe 时长在 600–2000ms
- 说明：APK 16KB 对齐由 `scripts/verify-apk.sh`（`zipalign -P 16`）在 P0-08 CI 覆盖
- 遗留：真实 TTS 音频后处理/长章节拼接归 P2-10；真机 A/B 归 P0-11

### P0-11 真机/模拟器 smoke 清单 — in_progress
- 开始：2026-11-08 12:50（Asia/Shanghai）
- 分支：`feat/p0-11-smoke-checklist`
- 目标：把 P0-05/06/07 的设备验证整理成可复跑清单，并补首页截图、设备/系统信息、logcat 证据
- 依赖：P0-04–P0-07
- 产出：`docs/phase0-smoke.md`；`smoke.yml` 增加 screencap/getprop/logcat artifact
- 验收：清单项全过；失败有日志

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

1. P0-11 真机/模拟器 smoke 清单（Room / Worker / FFmpeg / 启动均已由 emulator smoke 覆盖，补截图与设备记录）。
2. P0-12 Phase 0 门禁（CI + smoke 全绿、文档、可选 tag `v0.0.1-spike`）。
3. 进入 P1-01（模型/枚举）与 P1-02（提示词/音色池）。

## 日志

- 2026-11-08：初始仓库/构建配置/CI/文档落地；本地实编 `assembleDebug` 通过。
- 2026-11-08：CI 首次失败（`android-actions/setup-android@v3` 的 `sdkmanager tools` 在新 cmdline-tools 下找不到包）；改为直接调用 runner 的 sdkmanager，CI 4m25s 绿。
- 2026-11-08：`v0.1.0` release workflow 绿；签名 APK + checksums 已发布。
- 2026-11-08：新约束「不再本地构建/本地 CI」生效；验证改由 GitHub Actions 承担（CI 回传 schema 与单测报告 artifact）。
- 2026-11-08：P0-05 完成；Room Probe + Robolectric 读写测试 + KSP schema 入库。
- 2026-11-08：设备 smoke 暴露真问题——`AppDatabase.create()` 的 `onOpen` 用 `execSQL("PRAGMA busy_timeout=5000")`
  在真实 SQLite 上抛 `SQLiteException`（Robolectric 未覆盖到该回调）；改用 `query(...).close()`，并补 `create()` 单测。
- 2026-11-08：P0-06 完成；新增 emulator smoke workflow，设备上验证前台 Worker、通知可见与取消。
- 2026-11-08：P0-07 完成；FFmpegKit `-version`、350ms 静音、两段拼接与 ffprobe 时长在 API 35 模拟器通过。
