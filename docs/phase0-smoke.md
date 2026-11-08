# Phase 0 真机/模拟器 smoke 记录（P0-11）

> 设备 smoke 全部在 GitHub Actions 的 Android emulator 上执行；本机不构建、不跑 CI。
> 工作流：`.github/workflows/smoke.yml`（PR 改动 `app/src/main/**` 或 `app/src/androidTest/**` 时自动跑，也可手动 dispatch）。

## 1. 环境

| 项 | 值 |
|---|---|
| 设备 | Android Emulator（`pixel_6` profile）；`model=sdk_gphone64_x86_64` |
| 系统 | Android 15 / API 35（`google_apis`，`x86_64`） |
| 运行器 | `ubuntu-latest`，JDK 17，KVM 加速 |
| 被测 APK | `debug`（`com.estimatefaster.audioforge.debug`） |
| 执行 | `connectedDebugAndroidTest` + `adb screencap` / `getprop` / `logcat` |
| 日期 | 2026-11-08 |
| 最近一次全绿 | run [42447687152](https://github.com/EstimateFaster/android-audioforge/actions/runs/42447687152) |

## 2. 检查项与结果

| # | 检查项 | 方式 | 结果 | 证据 |
|---|---|---|---|---|
| 1 | APK 安装并启动不崩 | `ActivityScenario.launch(MainActivity)` + 冷启动截图 | ✅ | run 42447687152 |
| 2 | Room 建库 + 写入 + 回读 | `RoomInstrumentedTest.probe_writes_and_reads_back_on_device`；截图卡片显示「通过（第 1 次，28 ms）」 | ✅ | run 42447687152 |
| 3 | WorkManager 前台任务完成 + 通知可见 | `ProbeWorkerInstrumentedTest.foreground_worker_runs_and_posts_notification` | ✅ | run 42447687152 |
| 4 | 取消任务不崩 | `ProbeWorkerInstrumentedTest.cancelling_worker_ends_with_cancelled_state` | ✅ | run 42447687152 |
| 5 | FFmpeg `-version` + 350ms 静音 + 两段拼接 | `FfmpegInstrumentedTest.version_silence_and_concat` | ✅ | run 42447687152 |
| 6 | 首页截图 | `adb exec-out screencap` → `smoke-home.png`（213 KB，1080×2400） | ✅ | artifact `smoke-reports/` |
| 7 | 设备/系统信息 | `adb shell getprop` → `device-info.txt` | ✅ | artifact `smoke-reports/` |
| 8 | logcat | `adb logcat -d` → `logcat-smoke.txt`（约 6 MB） | ✅ | artifact `smoke-reports/` |

四项核心 smoke（启动 / Room / Worker / FFmpeg）全过；失败时工作流会把 logcat 与测试报告作为 artifact 上传。
`connectedDebugAndroidTest` 结束后 APK 会被卸载，因此工作流在测试后重新 `installDebug` 再截图。

## 3. 如何复跑

```bash
# 对默认分支
gh workflow run smoke.yml

# 对某个分支（需要 workflow 已在默认分支）
gh workflow run smoke.yml --ref <branch>
```

本地不执行 Gradle；需要看设备行为时直接在 Actions 里看 run。

## 4. 未覆盖（需要真机 / 人工）

- 真机（非模拟器）与国产 ROM：Honor/EMUI、MIUI、ColorOS
- 锁屏 30 分钟不断流、切后台/杀进程/重启
- 断网/恢复、省电模式、低电量、热状态降级
- 真实 LLM / Fish Audio 请求与音频质量主观 A/B
- 通知「停止」action、Media3 播放与耳机事件

这些属于 P3 与发布前真机验收，不阻塞 Phase 0 门禁（执行计划 §15 P0-11）。

## 5. Phase 0 门禁（P0-12）

执行计划 §15 P0-12 的验收项：

| 门禁项 | 结果 | 证据 |
|---|---|---|
| main CI 绿（lint / unit / assembleDebug / APK 校验） | ✅ | run [42448085423](https://github.com/EstimateFaster/android-audioforge/actions/runs/42448085423) |
| debug APK 可安装并启动 | ✅ | emulator smoke |
| Room 建库/写入/回读 | ✅ | `RoomInstrumentedTest` |
| WorkManager `dataSync` 前台任务 + 通知 | ✅ | `ProbeWorkerInstrumentedTest`（2 用例） |
| FFmpeg `-version` + 静音 + 拼接 | ✅ | `FfmpegInstrumentedTest` |
| 文档更新 | ✅ | `docs/progress.md`、`docs/decisions.md`、本文件 |
| 可选 tag `v0.0.1-spike` | 未打 | 等 Phase 1 完成后的正式版本再打 tag，避免发布无功能价值的 release |

设备 smoke：run [42448103444](https://github.com/EstimateFaster/android-audioforge/actions/runs/42448103444)（main，API 35 emulator，4/4 通过）。

**结论：Phase 0 门禁通过，可进入 Phase 1（纯 Kotlin 核心）。**
