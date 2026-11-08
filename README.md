# AudioForge — Android

> StoryForge 的 Android 原生重构：把小说 txt 变成多人演播有声书。
> **不是套壳**：Kotlin + Jetpack Compose + Material 3 Expressive + FFmpegKit + WorkManager。

- 仓库：**https://github.com/EstimateFaster/android-audioforge**
- 状态：**Phase 0 初始配置完成**（可编译、CI 就绪；业务功能按执行计划逐步实现）
- 分发：只 GitHub Release / Artifact 侧载，不上架 Google Play

## 给 AI 的入口

把仓库地址直接丢给 AI，并让它先读：

1. **[AGENTS.md](AGENTS.md)** — 硬规则与入口
2. **[docs/android-execution-plan.md](docs/android-execution-plan.md)** — 唯一执行计划（3,400+ 行，101 个任务）
3. **[docs/android-port-feasibility.md](docs/android-port-feasibility.md)** — 勘探/可行性
4. **[docs/progress.md](docs/progress.md)** — 当前进度

可直接复制给 AI 的提示词在 **[docs/DSH-HANDOFF.md](docs/DSH-HANDOFF.md)**。

## 当前已落地

- Gradle 9.9.0 wrapper，AGP 9.5.1，JDK 17，内置 Kotlin 2.2.10
- `compileSdk 37` / `targetSdk 36` / `minSdk 26`；只支持 `arm64-v8a` + `x86_64`
- Compose BOM 2026.09.00 + material3 1.5.0-rc01（`MaterialExpressiveTheme` / `MotionScheme.expressive()`）
- Room 2.8.5 + KSP 2.2.10-2.0.2、WorkManager 2.12.0、Media3 1.11.1、OkHttp 5.5.1、DataStore 1.3.0、Paging 3.5.3
- FFmpegKit `dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.9`
- Manifest：WorkManager `dataSync` 前台服务合并、FileProvider、txt 分享/打开入口、禁备份、禁明文
- 首页空壳（Material 3 Expressive）
- CI：lint + unit test + debug APK + APK 校验；Release：签名 APK + Artifact + GitHub Release
- 63 音色池与三段提示词已放入 `app/src/main/assets/`
- 黄金测试向量：[docs/fixtures/porting-vectors.json](docs/fixtures/porting-vectors.json)

## 本地构建（可选）

本地 Linux VM 仅用于编译/单测；正式 APK 由 Actions 产出。

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export ANDROID_USER_HOME=/tmp/android-home/.android
export GRADLE_USER_HOME=/tmp/gradle-home

cat > local.properties <<LOCAL
sdk.dir=$ANDROID_HOME
LOCAL

./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest --no-daemon
```

要求 SDK：`platforms;android-37.0`、`build-tools;37.0.0`。安装命令见 [docs/android-execution-plan.md §4](docs/android-execution-plan.md#4-环境与工具链)。

## CI / Release

- push 到 `main`：`.github/workflows/ci.yml` → debug APK artifact。
- push tag `v*` 或手动：`.github/workflows/release.yml` → 签名 release APK + GitHub Release。
- Release 需要 4 个 Secrets（keystore base64 / password / alias / key password），见 [docs/DSH-HANDOFF.md](docs/DSH-HANDOFF.md)。

## 下一步

按 [docs/android-execution-plan.md §15](docs/android-execution-plan.md#15-任务清单dsh-按顺序执行) 从 **P0-05**（Room smoke）继续；当前进度见 [docs/progress.md](docs/progress.md)。

## 许可

MIT。上游 [StoryForge](https://github.com/EstimateFaster/storyforge) 也是 MIT；FFmpegKit 为 LGPL-3.0，App 内会提供开源许可页。
