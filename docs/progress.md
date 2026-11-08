# 进度（DSH 维护）

> 规则：每个任务完成即更新；格式见 `docs/android-execution-plan.md` §17。
> 验证方式（2026-11-08 起）：**本机不再跑 Gradle/CI**，所有构建与测试由 GitHub Actions 执行。
> 当前阶段：**Phase 1 — 纯 Kotlin 核心**（Phase 0 门禁已通过，见 `docs/phase0-smoke.md` §5）

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
| P0-11 真机/模拟器 smoke 清单 | ✅ done | Smoke 42447687152 绿；截图/设备信息/logcat 证据齐全 |
| P0-12 Phase 0 门禁 | ✅ done | main CI 42448085423 + smoke 42448103444 全绿；tag 留待正式版本 |

## Phase 1

| 任务 | 状态 | 备注 |
|---|---|---|
| P1-01 模型与枚举 | ✅ done | CI 42448583320 绿；`EnumsTest` 8/8 |
| P1-02 Assets：提示词 + 音色池 | ✅ done | CI 42448901696 绿；Prompt/VoicePool 11 个用例 + 快照 |
| P1-03 TextDecoder | 🟡 in_progress | BOM/UTF-8/ICU/GB18030/Big5 + CJK 占比；等 CI |
| P1-04 ChapterSplitter | ⏳ todo | |
| P1-05 ScriptSanitizer | ⏳ todo | |
| P1-06 CoverageChecker | ⏳ todo | |
| P1-07 CharacterRegistry | ⏳ todo | |
| P1-08 VoicePool 完整 | ⏳ todo | |
| P1-09 设置模型与校验 | ⏳ todo | |
| P1-10 TTS 缓存键 | ⏳ todo | |
| P1-11 错误模型 | ⏳ todo | |
| P1-12 Golden fixtures 与测试脚手架 | ⏳ todo | |
| P1-13 性能基准 | ⏳ todo | |
| P1-14 文档与进度 | ⏳ todo | |
| P1-15 Phase 1 门禁 | ⏳ todo | |

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

### P0-11 真机/模拟器 smoke 清单 — done
- 完成：2026-11-08 12:47（Asia/Shanghai）
- 分支：`feat/p0-11-smoke-checklist`（PR #4）
- 产出：`docs/phase0-smoke.md`；`smoke.yml` 增加截图/设备信息/logcat artifact
- 验证：Smoke [42447687152](https://github.com/EstimateFaster/android-audioforge/actions/runs/42447687152) 绿
  - 4/4 instrumented tests（启动/Room/Worker/FFmpeg）
  - `smoke-home.png`（1080×2400）：首页 + P0-05 卡片「通过（第 1 次，28 ms）」
  - `device-info.txt`：`sdk_gphone64_x86_64` / Android 15 / API 35 / x86_64
  - `logcat-smoke.txt` + 测试报告 artifact
- 说明：`connectedDebugAndroidTest` 结束后会卸载 APK，工作流已改为测试后 `installDebug` 再截图

### P0-12 Phase 0 门禁 — done
- 完成：2026-11-08 12:57（Asia/Shanghai）
- 分支：`feat/p0-12-phase0-gate`
- 验证（main）：
  - CI [42448085423](https://github.com/EstimateFaster/android-audioforge/actions/runs/42448085423) 绿（lint / unit / assembleDebug / APK 校验）
  - Smoke [42448103444](https://github.com/EstimateFaster/android-audioforge/actions/runs/42448103444) 绿（4/4）
- 门禁表见 `docs/phase0-smoke.md` §5；四项 smoke（启动/Room/Worker/FFmpeg）全过
- 可选 tag `v0.0.1-spike` 未打：等 Phase 1 有可演示功能后再发正式版本
- 结论：Phase 0 完成，进入 Phase 1

### P1-01 模型与枚举 — done
- 完成：2026-11-08 13:00（Asia/Shanghai）
- 分支：`feat/p1-01-model-enums`（PR #6）
- 提交：`f591c5b`
- 验证：CI [42448583320](https://github.com/EstimateFaster/android-audioforge/actions/runs/42448583320) 绿；
  `EnumsTest` 8/8（wire 精确列表、11 情绪前缀、`applyTo`、`fromWire` 往返/兜底、RoleId 格式与非法入参）
- 产出：`core/model/Enums.kt`、`core/model/RoleId.kt`

### P1-02 Assets：提示词 + 音色池 — in_progress
- 开始：2026-11-08 13:12（Asia/Shanghai）
- 分支：`feat/p1-02-assets-voice-pool`
- 目标：`core/voice/PromptAssets.kt`（加载 + PromptRenderer）、`core/voice/VoicePool.kt`（63 类/查询/LLM 池/兜底）
- 依赖：P1-01 ✅
- 验收：提示词 SHA-256 与 fixture 一致；63 key/voice_id/speed/note 快照一致；旁白不进 `poolForLlm()`
- 现状：`assets/prompts/*.txt` 与 `assets/voice_pool.json` 启动时已从 fixture 拷入，本次只加代码与锁定测试

### P1-02 Assets：提示词 + 音色池 — done
- 完成：2026-11-08 13:16（Asia/Shanghai）
- 分支：`feat/p1-02-assets-voice-pool`（PR #7）
- 提交：`c52ef62`（实现 + 测试）
- 验证：CI [42448901696](https://github.com/EstimateFaster/android-audioforge/actions/runs/42448901696) 绿
  - `PromptAssetsTest` 2/2：三段提示词 SHA-256 与 fixture 一致（1882/476/98 字符，无 BOM）
  - `PromptRendererTest` 3/3：占位符字面量替换、`{}` 不误伤、repair 追加
  - `VoicePoolTest` 6/6：63 类别与 fixture 逐项一致、`male_brother=1.15`、8 个 note、旁白不进 LLM 池、兜底全覆盖
  - 合计 23 个单测全绿
- 产出：`core/voice/PromptAssets.kt`、`core/voice/VoicePool.kt`、`app/src/test/resources/voice-pool.json`

### P1-03 TextDecoder — in_progress
- 开始：2026-11-08 13:24（Asia/Shanghai）
- 分支：`feat/p1-03-text-decoder`
- 目标：`core/ingest/TextDecoder.kt`：BOM → UTF-8 严格 → ICU CharsetDetector → GB18030 → Big5；
  每种候选严格解码 + 前 2 万字 CJK ≥15%；失败返回可手动选编码；64MB 上限；不抛未捕获异常
- 依赖：P1-01 ✅
- 验收：UTF-8/UTF-8 BOM/GBK/GB18030/UTF-16LE/BE/Big5/非法字节用例全过

## 已知实编坑（已处理）

- KSP + AGP 9 内置 Kotlin：`android.disallowKotlinSourceSets=false`。
- `kotlinx-serialization-json` 必须是 **1.9.0**（1.12.0 与 Kotlin 2.2.10 不兼容）。
- SDK 包名是 `platforms;android-37.0`。
- Expressive 组件需要 `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`。
- Room 2.8.5 的 `onOpen` 里 `execSQL("PRAGMA busy_timeout=...")` 在真机必崩，必须用 `query(...)`。
- `connectedDebugAndroidTest` 跑完会卸载 APK；要在模拟器截图需重新 `installDebug`。
- `reactivecircus/android-emulator-runner` 按行执行 `script`，多行 `{}` / 跨行变量不生效。

## 已发布

- **v0.1.0**：https://github.com/EstimateFaster/android-audioforge/releases/tag/v0.1.0
  - `audioforge-0.1.0.apk`（约 60.5 MB）
  - `checksums.txt`
- CI：https://github.com/EstimateFaster/android-audioforge/actions

## 下一步

1. P1-03 TextDecoder（编码探测：BOM/UTF-8/GB18030/Big5/UTF-16）。
2. P1-04 ChapterSplitter。
3. P1-05 ScriptSanitizer（最高风险移植点）。

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
- 2026-11-08：P0-11 完成；smoke 增加冷启动截图/设备信息/logcat；截图确认首页与 Room 卡片「通过（第 1 次，28 ms）」。
- 2026-11-08：P0-12 门禁通过；Phase 0 完成（main CI + emulator smoke 全绿）。
- 2026-11-08：P1-01 完成；枚举 wire/情绪前缀/RoleId 契约与 Python 对齐，`EnumsTest` 8/8。
- 2026-11-08：P1-02 完成；提示词 SHA-256、63 音色快照、旁白排除全部锁定，23 个单测全绿。
