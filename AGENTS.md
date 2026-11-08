# AGENTS.md — AI 编码 Agent 入口

本仓库是 **StoryForge 的 Android 原生重构**（不是套壳）：

- Kotlin + Jetpack Compose + Material 3 Expressive
- LLM / Fish Audio 直连，BYOK
- FFmpegKit 做音频后处理与拼接
- WorkManager + `dataSync` 前台服务做长任务
- Release APK 只由 GitHub Actions 构建；只侧载，不上架 Google Play

## 你先读这三份

1. **[docs/android-execution-plan.md](docs/android-execution-plan.md)** — 唯一执行计划（3,400+ 行，101 个任务，含可复制配置）
2. **[docs/android-port-feasibility.md](docs/android-port-feasibility.md)** — 勘探/可行性/风险
3. **[docs/progress.md](docs/progress.md)** — 当前进度与下一步

## 硬规则（违反即停）

- 不提交任何密钥：`*.jks`、`keystore.properties`、`.env`、`local.properties`、API Key。
- release APK 只由 GitHub Actions 产出；本地 Linux VM 只用于编译/单测/调试。
- 不擅自升级/降级 §3 的版本矩阵；变更先写 `docs/decisions.md` 并跑 V1–V8。
- 一次只改一个变量；一个任务一个提交。
- 不先写业务 UI；先 P0/P1/P2 核心链路与真机 smoke。
- 所有用户可见文案进 `res/values/strings.xml`，中文。
- 每个任务结束更新 `docs/progress.md` 并跑验收命令。
- 遇到阻塞：写清现象/命令/日志/已尝试/怀疑点，再停。

## 常用命令

```bash
# 配置（本地 VM；CI 用同样的 Gradle 任务）
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export ANDROID_USER_HOME=/tmp/android-home/.android
export GRADLE_USER_HOME=/tmp/gradle-home
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest --no-daemon
```

当前构建基线：

- AGP 9.5.1 / Gradle 9.9.0 / JDK 17 / 内置 Kotlin 2.2.10
- compileSdk 37（SDK 包 `platforms;android-37.0`）/ targetSdk 36 / minSdk 26
- Compose BOM 2026.09.00 / material3 1.5.0-rc01
- Room 2.8.5 + KSP 2.2.10-2.0.2
- FFmpegKit 8.1.9
- 必须保留 `android.disallowKotlinSourceSets=false`；serialization 用 1.9.0

## 开始

从 **[docs/android-execution-plan.md §15](docs/android-execution-plan.md#15-任务清单dsh-按顺序执行)** 的 **P0-01** 开始。当前 Phase 0 初始配置已完成一部分，先看 `docs/progress.md` 的勾选状态。

如果任务要求创建 GitHub 仓库/Secrets，先读 `docs/DSH-HANDOFF.md`。
