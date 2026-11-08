# AudioForge Android 重构执行计划书（DSH 执行版）

> **文档版本**：1.0
> **日期**：2026-11-08
> **上游**：[EstimateFaster/storyforge](https://github.com/EstimateFaster/storyforge) `main@f1d262b`
> **执行者**：DSH（编码 Agent）
> **目标产物**：可侧载的原生 Android APK（Jetpack Compose / Material 3 Expressive），release 由 GitHub Actions 构建
> **配套文档**：[android-port-feasibility.md](android-port-feasibility.md)（勘探与可行性报告）、[porting-vectors.json](fixtures/porting-vectors.json)、[voice-pool.json](fixtures/voice-pool.json)

---

**阅读顺序**：先读 §0–§3（铁律/范围/已验证基线），再读 §6（构建配置），然后按 §15 的任务清单执行；实现细节查 §7–§14，发布查 §16。

## 目录

- [0. 给 DSH 的十条铁律](#0-给-dsh-的十条铁律)
- [1. 项目目标与范围](#1-项目目标与范围)
- [2. 已确认决策（ADR）](#2-已确认决策adr)
- [3. 已验证基线（不要随意改）](#3-已验证基线不要随意改)
- [4. 环境与工具链](#4-环境与工具链)
- [5. 仓库布局（必须按此创建）](#5-仓库布局必须按此创建)
- [6. 构建配置（可直接复制，已实编）](#6-构建配置可直接复制已实编)
- [7. 数据层（Room + DataStore）](#7-数据层room--datastore)
- [8. 领域核心](#8-领域核心)
- [9. 网络层](#9-网络层)
- [10. 音频层](#10-音频层)
- [11. 后台任务](#11-后台任务)
- [12. UI / UX](#12-ui--ux)
- [13. 安全、隐私与合规](#13-安全隐私与合规)
- [14. 测试与验收](#14-测试与验收)
- [15. 任务清单（DSH 按顺序执行）](#15-任务清单dsh-按顺序执行)
- [16. CI/CD 与发布](#16-cicd-与发布)
- [17. DSH 执行规约](#17-dsh-执行规约)
- [18. 附录](#18-附录)
- [19. 交付清单（最终检查）](#19-交付清单最终检查)

---

## Quick Start（DSH 前 30 分钟）

1. `pwd`、`git status`，确认工作区干净；没有 git 就先 `git init`。
2. 确认 `docs/fixtures/porting-vectors.json` 与 `voice-pool.json` 存在；不要修改它们。
3. 读 §0–§3，确认版本矩阵与已验证基线；把不理解的点记到 `docs/progress.md`，不要猜。
4. 建 `docs/progress.md`、`docs/decisions.md`；建分支 `feat/p0-01-bootstrap`。
5. 按 §15 从 **P0-01** 开始；P0 阶段不要写业务 UI。
6. 每完成一个任务：跑验收命令 → 提交 → 更新 progress → 再开始下一个。

---

## 0. 给 DSH 的十条铁律

1. **先读完整份计划再动手**。不要跳读；每个任务都有依赖和验收标准。
2. **一次只改一个变量**。版本、构建插件、架构模式不得在同一提交里同时变更。
3. **release APK 只由 GitHub Actions 产出**。本地 Linux VM 只用于编译、单测、调试；不得把本地构建产物当作交付物。
4. **不提交任何密钥**：API Key、keystore、`keystore.properties`、`.env`、`local.properties`、CI secrets 一律不进仓库。
5. **不放宽验收标准**。测试不过就停下来修，不要用 `@Suppress`、`-Xskip-metadata-version-check`、`android.disallowKotlinSourceSets=false` 之外的“绕过”来让构建变绿；该开关是已验证的必要项，不是万能药。
6. **不擅自升级/降级版本**。§3 的版本矩阵是实编验证过的；变更必须写 ADR 并跑完 V1–V8。
7. **不先写 UI**。P0/P1/P2 的核心链路与真机 smoke 通过后，再进入 P4。
8. **所有用户可见文案用中文**；代码标识符用英文；注释可用中文。文案集中在 `strings.xml`，不要在 Composable 里散落硬编码。
9. **每个任务结束必须更新 `docs/progress.md`**：任务 ID、状态、验证命令、结果、遗留问题。
10. **遇到阻塞先记录再停**：写清现象、命令、日志、已尝试方案、怀疑点；不要连续盲改超过 2 小时。

---

## 1. 项目目标与范围

### 1.1 产品目标

把一个中文小说 `.txt` 变成多人演播的有声书：

1. 导入 txt（UTF-8 / GBK / UTF-16 等），按「第X章」切分；
2. LLM 把每章转成朗读剧本（旁白/台词、说话人、情绪）；
3. 角色首次注册时从 63 类音色池选角并**锁定**；
4. Fish Audio 逐段合成，内容哈希缓存，只重跑改动片段；
5. ffmpeg 按剧本顺序拼接、插入停顿、输出 m4a；
6. 在 App 内试听、逐句编辑、换音色、批量生成、导出/分享。

### 1.2 与 Web 版的差异（必须实现）

- **无服务器**：不实现 FastAPI / 登录 / Caddy / systemd；LLM 与 Fish 由 App 直连。
- **无 WebView**：全部 Compose。
- **单活跃任务**：同一时间只跑一本书；其余排队。默认 5 章/批，最多 20 章/批。
- **BYOK**：用户在设置页填 LLM/Fish Key；Keystore 加密存储。
- **移动端存储**：app 私有目录 + MediaStore 导出；相对路径入库。
- **移动端后台**：WorkManager 长任务 + `dataSync` 前台服务 + 通知；处理 6h/24h 上限。
- **响应式/无障碍**：手机、折叠屏、平板；TalkBack；字体缩放。

### 1.3 范围内（v1 必须交付）

- 项目库：导入、重命名、删除、进度。
- 章节列表：搜索、状态筛选、分页/滚动、批量起止章、单章快速开始/继续。
- 解析：Pass 0 角色预识别、逐章 LLM 剧本化、清洗、覆盖率校验、补写重试、告警。
- 剧本页：逐片段查看/编辑角色/情绪/文本、单句试听、连播、重新生成。
- 人物音色：63 类音色试听/搜索/筛选/更换；角色音色锁定；每角色语速/音量/音调微调。
- TTS：Fish 合成、缓存、后处理（pitch/volume）、并发/重试、停止/续传。
- 拼接：静音、顺序、AAC m4a、时长/元数据、导出/分享。
- 播放器：Media3、后台播放、媒体通知、音频焦点、倍速、睡眠定时、上下章。
- 任务中心：当前批次、进度、停止、历史/错误、恢复。
- 设置：Key、模型、并发、切分、停顿、默认音色参数、主题、存储、诊断、许可。
- 安全：Keystore、禁备份、TLS、日志脱敏、FileProvider。
- CI：debug 构建、单测、lint、release 签名 APK、Artifact/Release。

### 1.4 范围外（v1 不做）

- Google Play 上架（只侧载 release；targetSdk 仍按 36 准备）。
- 多用户/账号/云同步/服务端 relay。
- WebView/Flutter/React Native/小程序外壳。
- 用户声音克隆、自定义音色训练。
- 在线书城、内容分发、社交。
- 服务器旧数据导入（v1.1 再评估）。
- 长章节自动分块解析（v1 明确报错；v1.1 做，见 P2.9）。
- 多书并行生成（只排队）。
- 32 位 ABI（ffmpeg AAR 不提供）。

### 1.5 成功标准（用户视角）

- 导入一本 20 MB、2000+ 章的中文小说不 ANR、不 OOM。
- 选择 5 章，能稳定完成“解析 → 合成 → 拼接 → 播放/导出”。
- 锁屏 30 分钟任务不中断；杀进程/重启后能继续。
- 改一句台词只重合成该段；换一个角色音色只重置该角色相关片段。
- 失败有明确原因和可操作按钮，不静默。
- 2000 章列表滚动 60fps；首帧 < 500ms（中端机）。
- APK 可安装、可升级（同一签名）、可离线播放已生成内容。

---

## 2. 已确认决策（ADR）

| ADR | 决策 | 理由 | 影响/备注 |
|---|---|---|---|
| ADR-001 | 原生 Kotlin + Compose，无 WebView、无本地 Python 服务 | 用户明确“完整重构，不是套壳” | 全部 UI/流程重写 |
| ADR-002 | Material 3 Expressive，`material3 1.5.0-rc01` | 用户接受 rc | 必须 `compileSdk 37`、AGP ≥ 9.1；实验 API 需 opt-in |
| ADR-003 | AGP 9.5.1 + Gradle 9.9.0 + JDK 17 + 内置 Kotlin 2.2.10 | 本地实编通过 | 不要 apply `org.jetbrains.kotlin.android` |
| ADR-004 | `compileSdk 37` / `targetSdk 36` / `minSdk 26` | Expressive 要求 37；Play 要求 36；26 覆盖 Android 8+ | SDK 包名 `platforms;android-37.0` |
| ADR-005 | 只发布 `arm64-v8a` + `x86_64` | ffmpeg AAR 只提供这两个 ABI | 32 位设备装不上；模拟器用 x86_64 |
| ADR-006 | 完全 BYOK，无服务器 | 用户未要求 relay；降低部署/隐私复杂度 | Key 由用户填；Keystore 加密 |
| ADR-007 | 单活跃任务 + 队列；默认 5 章、上限 20 章 | 6h/24h FGS 上限、电量、成本、恢复 | 不做整本一键 |
| ADR-008 | 只侧载 release，不上架 | 用户明确 | 仍需签名稳定；不接 Play 审核 |
| ADR-009 | FFmpegKit `dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.9` | 本地实编通过；含 LAME/AAC/MP4/滤镜 | 非 GPL 变体；锁版本+校验和 |
| ADR-010 | 单模块 `:app` + 包边界；手写 DI（AppContainer） | CI-only 迭代，减少构建复杂度 | 后续可拆模块；不上 Hilt |
| ADR-011 | 工作名 `AudioForge`，包名 `com.estimatefaster.audioforge` | 与工作区一致；可改显示名不改包名 | 如需中文名只改 `strings.xml` |
| ADR-012 | v1 不做长章节分块；超长明确报错 | 控制 v1 范围 | v1.1 任务 P2.9 预留 |
| ADR-013 | 本地 Linux VM 可编译/测试；release 只走 Actions | 用户允许 VM | 本地不产出交付 APK |
| ADR-014 | 测试以纯 JVM 单测 + 真机手动清单为主 | CI 无模拟器；Robolectric 可选 | instrumented 测试放 P6 |

---

## 3. 已验证基线（不要随意改）

### 3.1 版本矩阵（本地实编通过）

| 组件 | 版本 | 备注 |
|---|---|---|
| JDK | **17**（本地验证 17.0.2） | AGP 9.5 最低/默认 17 |
| Gradle | **9.9.0** | AGP 9.5 最低 9.6 |
| AGP | **9.5.1** | 最高 API 37 |
| Kotlin | **2.2.10**（AGP 内置） | 不 apply `org.jetbrains.kotlin.android` |
| Compose 编译器插件 | **2.2.10** | `org.jetbrains.kotlin.plugin.compose` |
| Compose BOM | **2026.09.00** | Compose 1.12.1 |
| Material3 | **1.5.0-rc01** | Expressive；覆盖 BOM |
| material3-adaptive-navigation-suite | **1.5.0-rc01** | 自适应导航 |
| compileSdk | **37** | SDK 包 `platforms;android-37.0` |
| targetSdk | **36** | Play 要求；侧载也按此 |
| minSdk | **26** | Android 8.0 |
| Room | **2.8.5** | KSP2 |
| KSP | **2.2.10-2.0.2** | 需 `android.disallowKotlinSourceSets=false` |
| WorkManager | **2.12.0** | 长任务/前台服务 |
| Media3 | **1.11.1** | 注意：不是 1.12.0（不存在） |
| DataStore | **1.3.0** | 设置/密钥密文 |
| Paging | **3.5.3** | 章节列表 |
| kotlinx.serialization | **1.9.0** | ⚠️ 1.12.0 与 Kotlin 2.2.10 不兼容 |
| kotlinx.coroutines | **1.11.0** | 结构化并发 |
| OkHttp | **5.5.1** | HTTP/2、超时、取消 |
| FFmpegKit | **8.1.9** | `dev.ffmpegkit-maintained:ffmpeg-kit-audio` |
| AndroidX Activity | **1.13.0** | |
| Lifecycle | **2.11.0** | |
| Navigation Compose | **2.10.2** | |

### 3.2 实编结果（2026-11-08）

- `assembleDebug` ✅；universal APK 约 **63 MB**。
- `assembleRelease` ✅（`isMinifyEnabled=false`）；universal APK 约 **58 MB**。
- 测试 keystore 签名后 `apksigner verify` ✅（v2 scheme）。
- `zipalign -c -P 16` ✅（debug/release）。
- `aapt2 dump badging`：`minSdk=26`、`targetSdk=36`、`compileSdk=37`。
- `SystemForegroundService` 合并后 `foregroundServiceType=0x1`（dataSync）✅。
- FFmpeg native libs 只含 `arm64-v8a` + `x86_64`；`libavcodec.so` ELF `Align=0x4000` ✅。
- `MaterialExpressiveTheme` + `MotionScheme.expressive()` + `LoadingIndicator` 编译 ✅（需 opt-in）。

### 3.3 实编踩到的坑（必须遵守）

1. **KSP + AGP 9 内置 Kotlin**：`gradle.properties` 必须有
   ```properties
   android.disallowKotlinSourceSets=false
   ```
   否则配置阶段报 “Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin”。
2. **serialization 版本**：用 **1.9.0**。1.12.0 依赖 Kotlin 2.4.10 的 stdlib/metadata，2.2.10 编译器报 metadata 2.4.0 不兼容。
3. **SDK 包名**：`sdkmanager "platforms;android-37.0"`，不是 `android-37`。
4. **Expressive API**：`MaterialExpressiveTheme`、`MotionScheme.expressive()`、`LoadingIndicator` 等都要 `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`。
5. **本地 Android home 可写**：Gradle/AGP 会写 debug keystore 到 `ANDROID_USER_HOME`；本地 VM 设 `/tmp/android-home`，CI runner 默认可写。
6. **APK 体积**：不混淆+双 ABI 的 universal release ~58 MB；需要更小再做 ABI split（P6.8）。

### 3.4 仍未验证（不要当成已通过）

- FFmpegKit 在真实 Android 上执行 `-version` 与滤镜。
- Room 运行时读写/迁移。
- WorkManager `dataSync` 在真机/国产 ROM 的后台存活。
- Media3 播放/通知/音频焦点。
- 真实 LLM/Fish 请求、限流、错误码。
- 音频质量（pitch/volume/拼接/响度）。
- Compose 大列表/大文本性能。
- 无障碍/TalkBack、字体缩放。

---

## 4. 环境与工具链

### 4.1 本地 Linux VM（可选，用于快速编译/单测）

> 用户已允许“如果必要，可以运行 Linux 虚拟机”。但**正式 APK 只能由 GitHub Actions 产出**。

#### 4.1.1 安装

```bash
# 目录（不要放在仓库内）
export AF_HOME=/tmp/audioforge-toolchain
mkdir -p "$AF_HOME"

# JDK 17：Huawei 镜像（比 Adoptium 快）
curl -L -o "$AF_HOME/jdk17.tar.gz" \
  https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_linux-x64_bin.tar.gz
mkdir -p "$AF_HOME/jdk"
tar -xzf "$AF_HOME/jdk17.tar.gz" -C "$AF_HOME/jdk" --strip-components=1

# Gradle 9.9.0：腾讯镜像
curl -L -o "$AF_HOME/gradle.zip" \
  https://mirrors.cloud.tencent.com/gradle/gradle-9.9.0-bin.zip
unzip -q "$AF_HOME/gradle.zip" -d "$AF_HOME"

# Android cmdline-tools（Google）
curl -L -o "$AF_HOME/cmdline.zip" \
  https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
mkdir -p "$AF_HOME/android-sdk/cmdline-tools"
unzip -q "$AF_HOME/cmdline.zip" -d "$AF_HOME/android-sdk/cmdline-tools"
mv "$AF_HOME/android-sdk/cmdline-tools/cmdline-tools" \
   "$AF_HOME/android-sdk/cmdline-tools/latest"
```

#### 4.1.2 接受许可并安装 SDK

```bash
export JAVA_HOME="$AF_HOME/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$AF_HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$AF_HOME/android-home/.android"
export JAVA_OPTS="-Duser.home=$AF_HOME/android-home"
mkdir -p "$ANDROID_USER_HOME"

yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  --sdk_root="$ANDROID_HOME" --licenses

"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  --sdk_root="$ANDROID_HOME" \
  "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"
```

#### 4.1.3 本地构建命令

```bash
cd <repo>
cat > local.properties <<LOCAL
sdk.dir=$ANDROID_HOME
LOCAL

export JAVA_HOME="$AF_HOME/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$AF_HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$AF_HOME/android-home/.android"
export GRADLE_USER_HOME="$AF_HOME/gradle-home"
export GRADLE_OPTS="-Duser.home=$AF_HOME/android-home"

"$AF_HOME/gradle-9.9.0/bin/gradle" \
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon
```

> `local.properties` 已在 `.gitignore`；不要提交。

### 4.2 GitHub Actions

- Runner：`ubuntu-latest`。
- JDK：Temurin 17。
- SDK：`android-actions/setup-android@v3` + `sdkmanager "platforms;android-37.0" "build-tools;37.0.0" "platform-tools"`。
- Gradle：`gradle/actions/setup-gradle@v4`。
- 缓存：Gradle caches + Android SDK（可选）。
- Release secrets：
  - `ANDROID_KEYSTORE_BASE64`
  - `ANDROID_KEYSTORE_PASSWORD`
  - `ANDROID_KEY_ALIAS`
  - `ANDROID_KEY_PASSWORD`
- 首次没有 secrets：release job 明确失败并提示；不要用 debug 签名冒充 release。

### 4.3 `.gitignore`（必须包含）

```gitignore
# Gradle / Android
.gradle/
build/
local.properties
*.iml
.idea/
captures/
.externalNativeBuild/
.cxx/

# 签名/密钥
*.jks
*.keystore
keystore.properties
.env

# 系统
.DS_Store
```

---

## 5. 仓库布局（必须按此创建）

```text
android-audioforge/
├── .github/
│   └── workflows/
│       ├── ci.yml                 # push/PR：lint + unit test + debug APK
│       └── release.yml            # tag v* / 手动：签名 release APK + GitHub Release
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   ├── schemas/                   # Room 导出的 schema（提交）
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/
│       │   │   ├── prompts/
│       │   │   │   ├── script.txt
│       │   │   │   ├── pass0.txt
│       │   │   │   └── repair.txt
│       │   │   └── voice_pool.json
│       │   ├── java/com/estimatefaster/audioforge/
│       │   │   ├── AudioForgeApp.kt
│       │   │   ├── MainActivity.kt
│       │   │   ├── app/
│       │   │   │   ├── AppContainer.kt
│       │   │   │   └── AppViewModelFactory.kt
│       │   │   ├── core/
│       │   │   │   ├── model/        # Novel, Chapter, Character, Segment, Voice, Emotion, 状态
│       │   │   │   ├── ingest/       # TextDecoder, ChapterSplitter, NovelImporter
│       │   │   │   ├── script/       # PromptAssets, LlmScriptAnalyzer, ScriptSanitizer, CoverageChecker
│       │   │   │   ├── voice/        # VoicePool, VoiceSelection
│       │   │   │   ├── tts/          # FishTtsClient, TtsCacheKey, TtsSynthesizer, AudioPostProcessor
│       │   │   │   ├── audio/        # ChapterStitcher, SilenceGenerator, AudioProbe, FfmpegKitRunner
│       │   │   │   ├── storage/      # AudioStorage, ProjectFiles, ExportManager, StorageMaintenance
│       │   │   │   ├── network/      # OkHttpProvider, RetryPolicy, ConnectivityMonitor
│       │   │   │   ├── settings/     # SettingsRepository, SecureKeyStore
│       │   │   │   └── util/         # AppLogger, Diff, Text, Time, Dispatchers
│       │   │   ├── data/
│       │   │   │   ├── db/           # AppDatabase, Entities, DAOs, Converters, Migrations
│       │   │   │   └── repository/   # Project/Chapter/Character/Segment/Job repositories
│       │   │   ├── domain/
│       │   │   │   ├── pipeline/     # ChapterPipeline, CharacterRegistry, AnalyzeChapter, RenderChapter
│       │   │   │   ├── jobs/         # BatchRunner, GenerationWorker, JobQueue, SweepRunning
│       │   │   │   └── usecase/      # StartBatch, StopBatch, UpdateSegment, ChangeVoice, Export...
│       │   │   ├── ui/
│       │   │   │   ├── theme/        # Color, Type, Shape, Motion, Theme
│       │   │   │   ├── components/   # StatusPill, RoleAvatar, ProgressRing, JobCard, EmptyState...
│       │   │   │   └── navigation/   # Routes, AppNavHost, AdaptiveScaffold
│       │   │   ├── feature/
│       │   │   │   ├── onboarding/
│       │   │   │   ├── projects/
│       │   │   │   ├── project/
│       │   │   │   ├── script/
│       │   │   │   ├── voices/
│       │   │   │   ├── settings/
│       │   │   │   ├── tasks/
│       │   │   │   ├── player/
│       │   │   │   └── storage/
│       │   │   └── playback/         # MediaSessionService, PlayerController
│       │   └── res/
│       │       ├── drawable/         # 图标（Material Symbols 导出的 VectorDrawable）
│       │       ├── mipmap-*/         # 启动图标
│       │       ├── values/           # strings, colors, themes
│       │       ├── values-night/
│       │       └── xml/              # network_security_config, file_paths, backup rules
│       ├── test/                     # JVM 单测 + fixtures
│       └── androidTest/              # 可选：Room/Worker/Compose instrumented
├── docs/
│   ├── android-port-feasibility.md
│   ├── android-execution-plan.md     # 本文件
│   ├── progress.md                   # DSH 维护
│   ├── decisions.md                  # ADR/偏差记录
│   └── fixtures/
│       ├── porting-vectors.json
│       └── voice-pool.json
├── gradle/
│   ├── libs.versions.toml            # 可选；若用，坐标必须与 §6 一致
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── gradlew
├── gradlew.bat
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── README.md
└── .gitignore
```

### 5.1 包职责（不要越层）

- `feature/*`：只依赖 `domain` 用例/`ui`，**不得直接依赖 DAO/OkHttp/FFmpeg**。
- `domain/*`：纯 Kotlin + 协程；可依赖 `core/*` 接口；不得依赖 Android UI。
- `data/*`：Room/DataStore/Repository 实现；不得依赖 Compose。
- `core/*`：模型、算法、协议、音频、存储、网络；尽量纯 Kotlin，Android 相关放 `storage`/`audio`/`network`。
- `app/*`：Application、Activity、DI 容器、导航壳；只做组装。

---

## 6. 构建配置（可直接复制，已实编）

### 6.1 `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "audioforge"
include(":app")
```

### 6.2 根 `build.gradle.kts`

```kotlin
plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.2.10-2.0.2" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
}
```

### 6.3 `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
android.nonTransitiveRClass=true
# KSP 2.2.10-2.0.2 + AGP 9 内置 Kotlin 的必需开关；KSP 升级后重新评估
android.disallowKotlinSourceSets=false
kotlin.code.style=official
```

### 6.4 `app/build.gradle.kts`

```kotlin
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
fun secret(key: String, default: String = ""): String =
    System.getenv(key) ?: keystoreProps.getProperty(key, default)

android {
    namespace = "com.estimatefaster.audioforge"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.estimatefaster.audioforge"
        minSdk = 26
        targetSdk = 36
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"

        // ffmpeg AAR 只提供这两个 ABI；不支持 32 位
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            val path = secret("ANDROID_KEYSTORE_PATH")
            if (path.isNotBlank()) {
                storeFile = file(path)
                storePassword = secret("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = secret("ANDROID_KEY_ALIAS")
                keyPassword = secret("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false        // v1 先不混淆；P6 再评估
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (secret("ANDROID_KEYSTORE_PATH").isNotBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
        jniLibs.useLegacyPackaging = false
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite:1.5.0-rc01")
    implementation("androidx.compose.material3:material3-window-size-class:1.5.0-rc01")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // AndroidX
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.paging:paging-compose:3.5.3")
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")

    // Room
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // Network / JSON
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // FFmpeg
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.9")

    // Tests
    testImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.1")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("androidx.room:room-testing:2.8.5")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.work:work-testing:2.12.0")
}
```

> 如果 Robolectric 4.16 / androidx.test 版本在 CI 解析失败，先移除 Robolectric 相关依赖，改用纯 JVM 测试 + 真机 instrumented；不要因此改 AGP/Kotlin。
>
> 如果 `lintDebug` 报 `UnsafeOptInUsageError`，给具体使用点加 `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`；不要全局 disable lint 检查。

### 6.5 `AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <!-- P6: 换成正式自适应图标；P0 先用系统占位图标 -->
    <application
        android:name=".AudioForgeApp"
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="false"
        android:icon="@android:drawable/sym_def_app_icon"
        android:label="@string/app_name"
        android:networkSecurityConfig="@xml/network_security_config"
        android:roundIcon="@android:drawable/sym_def_app_icon"
        android:supportsRtl="true"
        android:theme="@style/Theme.AudioForge"
        android:enableOnBackInvokedCallback="true"
        tools:targetApi="36">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:mimeType="text/plain" />
            </intent-filter>
        </activity>

        <!-- WorkManager 前台服务：必须合并 dataSync 类型 -->
        <service
            android:name="androidx.work.impl.foreground.SystemForegroundService"
            android:foregroundServiceType="dataSync"
            tools:node="merge" />

        <!-- Media3 后台播放 -->
        <service
            android:name=".playback.AudioForgeMediaSessionService"
            android:exported="true"
            android:foregroundServiceType="mediaPlayback">
            <intent-filter>
                <action android:name="androidx.media3.session.MediaSessionService" />
            </intent-filter>
        </service>

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
    </application>
</manifest>
```

### 6.6 `res/xml/network_security_config.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

### 6.7 `res/xml/file_paths.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <files-path name="audio" path="audio/" />
    <cache-path name="cache" path="." />
    <external-files-path name="exports" path="exports/" />
</paths>
```

### 6.8 `res/xml/data_extraction_rules.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="sharedpref" path="." />
        <exclude domain="database" path="." />
        <exclude domain="file" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="." />
        <exclude domain="database" path="." />
    </device-transfer>
</data-extraction-rules>
```

### 6.9 `res/values/themes.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.AudioForge" parent="android:style/Theme.Material.Light.NoActionBar">
        <item name="android:fontFamily">sans</item>
        <item name="android:windowLightStatusBar">true</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:windowActionModeOverlay">true</item>
    </style>
</resources>
```

### 6.10 `proguard-rules.pro`（v1 不混淆也要保留）

```proguard
# FFmpegKit JNI
-keep class com.arthenica.ffmpegkit.** { *; }
-keepclassmembers class com.arthenica.ffmpegkit.FFmpegKitConfig {
    native <methods>;
    void log(long, int, byte[]);
    void statistics(long, int, float, float, long, double, double, double);
    int safOpen(int);
    int safClose(int);
}

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
```

### 6.11 Gradle Wrapper

在本地 VM 生成并提交：

```bash
cd <repo>
"$AF_HOME/gradle-9.9.0/bin/gradle" wrapper --gradle-version 9.9.0 --distribution-type bin
# 提交：gradlew, gradlew.bat, gradle/wrapper/gradle-wrapper.jar, gradle/wrapper/gradle-wrapper.properties
```

> `gradle-wrapper.jar` 是二进制文件，必须提交；CI 不预装 Gradle 也能构建。不要提交 `gradle-9.9.0-bin.zip`。

### 6.12 构建自检

```bash
./gradlew --version
./gradlew :app:assembleDebug --no-daemon --stacktrace
./gradlew :app:lintDebug :app:testDebugUnitTest --no-daemon
```

通过标准：

- `assembleDebug` 成功；
- `lintDebug` 无 error；
- 单测全绿；
- `app/build/outputs/apk/debug/app-debug.apk` 存在；
- `aapt2 dump badging` 显示 `minSdk=26`、`targetSdk=36`、`compileSdk=37`；
- `zipalign -c -P 16` 通过。

---

## 7. 数据层（Room + DataStore）

### 7.1 实体（`data/db/Entities.kt`）

```kotlin
@Entity(
    tableName = "novels",
    indices = [Index(value = ["title"], unique = true)]
)
data class NovelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val sourceName: String,
    val sourceRelPath: String,          // imports/<uuid>.txt
    val charCount: Int,
    val createdAt: Long,
    val lastOpenedAt: Long? = null,
)

@Entity(
    tableName = "chapters",
    foreignKeys = [ForeignKey(
        entity = NovelEntity::class,
        parentColumns = ["id"],
        childColumns = ["novelId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index(value = ["novelId", "idx"], unique = true),
        Index(value = ["novelId", "audioStatus"]),
        Index(value = ["novelId", "parseStatus"]),
    ]
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val novelId: Long,
    val idx: Int,
    val title: String,
    val rawText: String,
    val parseStatus: String = "pending",
    val parseError: String? = null,
    val audioStatus: String = "pending",
    val audioError: String? = null,
    val parseWarn: String? = null,
    val segCount: Int = 0,
    val segDone: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "characters",
    foreignKeys = [ForeignKey(
        entity = NovelEntity::class,
        parentColumns = ["id"],
        childColumns = ["novelId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index(value = ["novelId", "roleId"], unique = true),
        Index(value = ["novelId", "name"]),
    ]
)
data class CharacterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val novelId: Long,
    val roleId: String,                  // role_0001 / narrator
    val name: String,
    val gender: String = "unknown",
    val importance: String = "supporting",
    val aliasesJson: String = "[]",
    val note: String = "",
    val category: String = "",
    val voiceId: String = "",
    val speed: Double? = null,
    val locked: Boolean = true,
)

@Entity(
    tableName = "segments",
    foreignKeys = [ForeignKey(
        entity = ChapterEntity::class,
        parentColumns = ["id"],
        childColumns = ["chapterId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index(value = ["chapterId", "seq"], unique = true),
        Index(value = ["roleId"]),
        Index(value = ["status"]),
        Index(value = ["chapterId", "status"]),
    ]
)
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chapterId: Long,
    val seq: Int,
    val type: String,                    // narration / dialogue
    val roleId: String,                  // narrator / role_XXXX
    val emotion: String,
    val text: String,
    val status: String = "pending",
    val audioRelPath: String? = null,    // audio/<nid>/segments/ch_001/seg_000001.mp3
    val durationMs: Int? = null,
    val errorMsg: String? = null,
    val ttsKey: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "character_tuning",
    primaryKeys = ["novelId", "roleId", "key"],
    foreignKeys = [ForeignKey(
        entity = NovelEntity::class,
        parentColumns = ["id"],
        childColumns = ["novelId"],
        onDelete = ForeignKey.CASCADE,
    )]
)
data class CharacterTuningEntity(
    val novelId: Long,
    val roleId: String,
    val key: String,                     // speed / volume / pitch
    val value: String,
)

@Entity(
    tableName = "novel_meta",
    foreignKeys = [ForeignKey(
        entity = NovelEntity::class,
        parentColumns = ["id"],
        childColumns = ["novelId"],
        onDelete = ForeignKey.CASCADE,
    )]
)
data class NovelMetaEntity(
    @PrimaryKey val novelId: Long,
    val lastGeneratedChapter: Int = 0,
)

@Entity(
    tableName = "jobs",
    foreignKeys = [ForeignKey(
        entity = NovelEntity::class,
        parentColumns = ["id"],
        childColumns = ["novelId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["novelId", "status"])]
)
data class JobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val novelId: Long,
    val mode: String,                    // full / parse / render
    val status: String,                  // queued / running / done / error / cancelled
    val requestedIdsJson: String,        // 本次批次的 chapterId JSON
    val currentChapterId: Long? = null,
    val currentTitle: String = "",
    val startedAt: Long,
    val finishedAt: Long? = null,
    val lastError: String? = null,
    val statsJson: String = "{}",
    val stopRequested: Boolean = false,
)
```

### 7.2 DAO 要点

- `NovelDao`：
  - `observeProjects()`：一条聚合 SQL，按 novel 统计章节/片段（不要逐项目子查询）。
  - `listNovels()`、`getNovel(id)`、`insert`、`rename`、`delete`。
- `ChapterDao`：
  - `observeBriefs(novelId)`：投影 `id, idx, title, parseStatus, audioStatus, parseWarn, segCount, segDone`，**不含 `rawText`**。
  - `getChapter(id)`（含正文）、`getRawText(id)`、`updateStatus`、`updateWarn`、`updateCounters`。
  - Paging：`@Query("SELECT ... WHERE novelId=:nid AND (:q='' OR title LIKE '%'||:q||'%') ... ORDER BY idx")`。
- `CharacterDao`：
  - `list(novelId)`、`getByRoleId`、`getByNameOrAlias`（在仓库层遍历别名，或维护 alias 表）、`insert`、`updateVoice`、`updateAliases`。
- `SegmentDao`：
  - `list(chapterId)`、`get(id)`、`update`、`setStatus`、`resetByRole`、`resetByChapter`、`countDone`。
  - 批量状态更新用 `UPDATE ... WHERE id IN (:ids)`，不要逐条。
- `JobDao`：
  - `insert`、`updateStatus`、`observeActive`、`lastFinished(novelId)`、`sweepRunning`。

### 7.3 数据库

```kotlin
@Database(
    entities = [
        NovelEntity::class, ChapterEntity::class, CharacterEntity::class,
        SegmentEntity::class, CharacterTuningEntity::class, NovelMetaEntity::class,
        JobEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun novelDao(): NovelDao
    abstract fun chapterDao(): ChapterDao
    abstract fun characterDao(): CharacterDao
    abstract fun segmentDao(): SegmentDao
    abstract fun jobDao(): JobDao
}
```

- Builder：`Room.databaseBuilder(context, AppDatabase::class.java, "audioforge.db")`
  - `.setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)`
  - `.addCallback(object : RoomDatabase.Callback() { override fun onOpen(db: SupportSQLiteDatabase) { db.execSQL("PRAGMA foreign_keys=ON"); db.execSQL("PRAGMA busy_timeout=5000") } })`
- 迁移：v1 不做迁移；后续 schema 变更必须写 `Migration` + `MigrationTestHelper`。
- 事务：所有多表写用 `db.withTransaction { }`；片段状态批量更新、章节计数维护、删除项目必须原子。
- 外键：显式 `PRAGMA foreign_keys=ON`；删除项目时仍按顺序删除并清理文件。
- 性能：不要在主线程查询；列表/进度用 `Flow`，`distinctUntilChanged` + `debounce(100)`。

### 7.4 设置（DataStore Preferences）

`SettingsRepository` 暴露 `Flow<AppSettings>` + `suspend fun update(...)`。Key 与默认值：

| Key | 默认 | 范围/说明 |
|---|---|---|
| `llm_base_url` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | 可改 |
| `llm_model` | `qwen3.7-flash` | 可改 |
| `llm_concurrency` | 1 | 1–4 |
| `fish_base_url` | `https://api.fish.audio` | 可改 |
| `fish_tts_model` | `s2.1-pro-free` | 可改 |
| `tts_concurrency` | 4 | 1–8 |
| `segment_max_chars` | 120 | 20–300 |
| `narrator_silence_ms` | 350 | 0–3000 |
| `paragraph_silence_ms` | 700 | 0–5000 |
| `default_voice_category` | `narrator_male` | 63 类之一 |
| `default_speed` | 1.0 | 0.5–2.0 |
| `default_volume` | 1.0 | 0.5–2.0 |
| `default_pitch` | 0.0 | -12–+12 |
| `min_coverage` | 0.97 | 0.5–1.0 |
| `excluded_characters` | `""` | 空格分隔 |
| `batch_size` | 5 | 1–20 |
| `wifi_only` | true | 仅 Wi-Fi 合成/下载 |
| `charging_only` | false | 仅充电时生成 |
| `battery_pause_threshold` | 15 | 0–50 |
| `thermal_throttle` | true | 过热降并发/暂停 |
| `theme_mode` | `system` | system/light/dark |
| `dynamic_color` | true | Android 12+ |
| `keep_segment_cache_days` | 7 | 0=不自动删 |
| `notifications_enabled` | true | |
| `onboarding_completed` | false | |

验证：`SettingsValidator` 返回 `Result<AppSettings>` 或字段错误；UI 即时校验；保存前再次校验。

### 7.5 安全密钥（`SecureKeyStore`）

- Android Keystore 生成 AES-256-GCM key（alias `audioforge_api_keys_v1`）。
- DataStore 只存 `Base64(iv + ciphertext)`；AAD 用 key 名（`llm_api_key` / `fish_api_key`）。
- 支持 `set/get/clear`；`get` 失败返回 null，不崩溃。
- 不写日志；UI 只显示末 4 位；密钥页可 `FLAG_SECURE`。
- `allowBackup=false`；`dataExtractionRules` 排除。

### 7.6 文件存储（`AudioStorage`）

```text
filesDir/
├── imports/<uuid>.txt
├── audio/<novelId>/
│   ├── chapter_001.m4a
│   └── segments/ch_001/
│       ├── seg_000001.mp3
│       └── seg_000001.hash
├── logs/app.log
└── exports/            # 临时导出/分享
cacheDir/
├── voice_preview/
└── ffmpeg/
```

- DB 只存**相对路径**（`audio/<nid>/...`），运行时 `resolve(rel)`。
- 文件命名只用 ASCII（项目 id / 章节序号 / 片段序号），用户标题只进 DB 和导出名。
- 原子写：`.tmp` → `renameTo`；`.hash` 只在音频文件存在且成功后写。
- 空间预检：开始批次前估算 `segCount * 50KB + 成品`；低于 500MB 拒绝并提示清理。
- 清理：删除项目 → 删 DB 行 + `audio/<nid>` 目录 + 源文件；孤儿清理扫描 DB 引用。
- 导出：API 29+ 用 MediaStore `Audio` + `RELATIVE_PATH=Music/AudioForge/<title>`；API 26–28 用 app-specific external + FileProvider 分享。不要申请 `MANAGE_EXTERNAL_STORAGE`。

### 7.7 导入流程（`ImportNovelUseCase`）

1. `ActivityResultContracts.OpenDocument`（MIME `text/plain`，也接受 `*/*` + 后缀 `.txt`）。
2. 读取 `displayName`；校验后缀/大小（≤64MB）；复制到 `imports/<uuid>.txt`，显示进度。
3. `TextDecoder`：
   - BOM（UTF-16LE/BE）→ UTF-8 严格 → `CharsetDetector`（`android.icu.text.CharsetDetector`）→ GB18030 → Big5；
   - CJK 占比校验（前 20k 字符 >15%）；
   - 失败让用户手动选编码；不静默乱码。
4. `ChapterSplitter`：
   - 正则 `^\s*(?:第\s*([零一二三四五六七八九十百千万两\d]+)\s*[章卷节回部]|Chapter\s+(\d+))\s*(.*)$`，`IGNORE_CASE + UNICODE_CHARACTER_CLASS`；
   - 行 `trim().length < 50` 才算标题；无标题 → 单章“全文”；跳过无正文假标题。
5. 标题冲突：同名项目已存在 → 提示“打开已有 / 另存为副本”，**不覆盖**。
6. 单事务插入 novel + chapters；更新 `charCount`；返回 novelId。
7. 失败：回滚 DB、删临时文件、给可读错误。

### 7.8 数据层测试

- `TextDecoderTest`：UTF-8/BOM/GBK/UTF-16/Big5/非法字节。
- `ChapterSplitterTest`：样例小说、无标题、假标题、超长标题、CRLF。
- `ImportNovelUseCaseTest`：重名、超大、空文件、非 txt、编码失败。
- `RoomDaoTest`（Robolectric 或 instrumented）：项目聚合、章节 brief 不含正文、片段唯一、事务回滚、外键级联。
- `SettingsValidatorTest`：每个范围边界。
- `SecureKeyStoreTest`（instrumented）：set/get/clear、错误密钥不崩溃。

---

## 8. 领域核心

### 8.1 模型与枚举（`core/model`）

```kotlin
enum class ParseStatus(val wire: String) { Pending("pending"), Running("running"), Done("done"), Error("error") }
enum class AudioStatus(val wire: String) { Pending("pending"), Running("running"), Done("done"), Error("error") }
enum class SegmentStatus(val wire: String) { Pending("pending"), Running("running"), Done("done"), Error("error") }
enum class SegmentType(val wire: String) { Narration("narration"), Dialogue("dialogue") }
enum class Importance(val wire: String) { Protagonist("protagonist"), Supporting("supporting"), Minor("minor") }
enum class Gender(val wire: String) { Male("male"), Female("female"), Unknown("unknown") }
enum class BatchMode(val wire: String) { Full("full"), Parse("parse"), Render("render") }
```

**Emotion**（11 个，wire 必须与 DB/API 一致，不能翻译）：

```kotlin
enum class Emotion(val wire: String, val ttsPrefix: String?) {
    Neutral("neutral", null),
    Happy("happy", "[happy]"),
    Sad("sad", "[sad]"),
    Angry("angry", "[angry]"),
    Anxious("anxious", "[anxious]"),
    Excited("excited", "[excited]"),
    Surprised("surprised", "[surprised]"),
    SoftTone("soft tone", "[soft tone]"),
    Serious("serious", "[serious]"),
    Calm("calm", "[calm]"),
    Whispering("whispering", "[whispering]");

    companion object {
        fun fromWire(value: String): Emotion = entries.firstOrNull { it.wire == value } ?: Neutral
    }
}
```

### 8.2 提示词资产

把 `docs/fixtures/porting-vectors.json` 里的 `prompts.PROMPT / PASS0_PROMPT / REPAIR_HINT` 原样写入：

- `app/src/main/assets/prompts/script.txt` ← `PROMPT`
- `app/src/main/assets/prompts/pass0.txt` ← `PASS0_PROMPT`
- `app/src/main/assets/prompts/repair.txt` ← `REPAIR_HINT`

> **逐字复制**，不翻译、不改标点、不调整规则顺序。`PromptAssetsTest` 校验 SHA-256 与 fixture 一致。

占位符替换用简单字符串替换（不要 `String.format`，提示词里有 `{}`）：

```kotlin
object PromptRenderer {
    fun script(registry: String, emotions: String, voicePool: String, text: String): String =
        asset("prompts/script.txt")
            .replace("{registry}", registry)
            .replace("{emotions}", emotions)
            .replace("{voice_pool}", voicePool)
            .replace("{text}", text)

    fun pass0(title: String, voicePool: String, text: String): String =
        asset("prompts/pass0.txt")
            .replace("{title}", title)
            .replace("{voice_pool}", voicePool)
            .replace("{text}", text)

    fun repair(basePrompt: String, missing: List<String>): String =
        basePrompt + asset("prompts/repair.txt").replace("{missing}", missing.joinToString("\n") { "- $it" })
}
```

### 8.3 音色池

- 数据源：`docs/fixtures/voice-pool.json` → `app/src/main/assets/voice_pool.json`。
- 63 个 key 是锁定契约；`VoicePoolTest` 锁定 key 集合、voice_id 映射、`male_brother.speed=1.15`、8 个 note、旁白不参与 LLM 选角。
- `VoicePool` 提供：
  - `get(category): VoiceCategory?`
  - `valid(category): Boolean`
  - `voiceIdFor(category): String`
  - `speedFor(category): Double?`
  - `poolForLlm(includeNarrator=false): String`（`- key: label`，**不含 note**）
  - `fallbackCategory(gender, importance)`
- 兜底映射（与 Python 一致）：
  - male protagonist → `male_lead_steady`；supporting → `male_gentle_young`；minor → `male_young_support`
  - female protagonist → `female_lead_gentle`；supporting → `female_young`；minor → `female_mature`

### 8.4 剧本清洗（`ScriptSanitizer`）

这是**最高风险**的移植点，必须逐函数、逐正则、逐测试对齐。函数清单：

| Python | Kotlin | 说明 |
|---|---|---|
| `_strip_speech_tail` | `stripSpeechTail` | 三层迭代剥离引导语 |
| `_is_residue` | `isResidue` | 短人名/残渣 |
| `_find_quoted` | `findQuoted` | 单边引号兼容 |
| `_split_dialogue_mixed` | `splitDialogueMixed` | 混合段拆旁白/台词 |
| `_split_mixed` | `splitMixed` | 去重、剥引导语、合并旁白 |
| `_split_long` | `splitLong` | 按句末标点/硬切 |
| `_sanitize` | `sanitize` | 情绪归位、空段过滤 |

正则迁移要求：

- 用 `Pattern.UNICODE_CHARACTER_CLASS`（Kotlin：`Regex(pattern, setOf(RegexOption.IGNORE_CASE))` 不够；用 `Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS)` 再 `Pattern.toRegex()`，或 `Regex(pattern, RegexOption.UNICODE_CASE)` + 显式 `\p{...}`）。
- 三个 `_SPEECH_*` 正则的词表**逐字**照搬；不要“优化”交替顺序。
- `strip()`/`trim { it in chars }` 对全角/半角引号逐字符核对。
- `$` 与末尾换行：输入先 `trim()`；测试包含 `\n`。
- 性能：对 20k 字符段落做基准；若回溯明显，先按句切分再清洗，不改变输出。

黄金测试：

- `docs/fixtures/porting-vectors.json` 的 `sanitizer.strip_speech_tail` / `split_long` 全部逐条断言。
- 原 Python `tests/test_analyzer.py` 的 20+ 用例逐条搬。
- 额外 50 条真实章节的输入/输出快照（脱敏，放 `app/src/test/resources/golden/`）。

### 8.5 覆盖率（`CoverageChecker`）

- 归一化：去掉空白与引号（`\s“”"‘’「」『』`）。
- diff：优先用 `java-diff-utils` 或自写 Myers，输出 `delete/replace` 区间（长度 ≥3）。
- 过滤“设计内丢失”：`isResidue` / `stripSpeechTail` 后为空或 ≤3 字。
- 覆盖率 = `1 - 有效缺失字符数 / 原文长度`；默认阈值 0.97。
- 低于阈值：把缺失片段（最多 6 条）喂回 `repair.txt` 重试一次；取覆盖率更高版本；仍低则写 `parseWarn`。
- 测试：fixture `coverage` 三条 + 原 Python 用例；Kotlin 与 Python 覆盖率误差 ≤0.001。

### 8.6 角色注册（`CharacterRegistry`）

- 输入：`new_characters`（name/gender/importance/aliases/note/voice/alias_of）。
- 已存在（按 name 或 alias）→ 复用 role_id，**绝不改音色**；只合并别名。
- `alias_of` → 并入已有角色，不新建。
- 新角色：`role_%04d` 递增；category = LLM 给的合法 category，否则兜底；voiceId 从池取；speed 取类别建议值。
- `excludedCharacters`（设置）命中 → 不注册，台词降级旁白。
- 全部在一个 Room 事务里完成。

### 8.7 解析流水线（`AnalyzeChapterUseCase`）

```text
if parse_status == done && !force: return
set parse_status=running
prompt = PromptRenderer.script(registryBlock, emotions, voicePool, rawText)
raw = llm.chat(prompt)          // 失败/非法 JSON/截断 → 记录 warn，必要时降级整章旁白
script = parseAndValidate(raw)
registry.register(script.newCharacters)
segments = mapSpeakers(script.segments)   // speaker → roleId；unknown 降级旁白
segments = sanitizer.sanitize(segments)
(coverage, missing) = coverageChecker.check(rawText, segments)
if coverage < minCoverage && fixable.isNotEmpty():
    retry with repair prompt; keep better
replaceSegments(chapterId, segments)      // 单事务；维护 segCount/segDone
set parse_status=done; parse_warn=...
```

规则：
- `finish_reason=length` → 明确报错“本章过长/输出截断”，不重试 6 次。
- 非法 JSON → 降级整章旁白 + warn（不静默）。
- 对话 speaker 未识别：短拟声词（≤4 字）归旁白；较长台词标 `unknown` 并计数。
- 章节 `parse_warn` 显示覆盖率、未识别数、降级原因。

### 8.8 TTS 缓存键（`TtsCacheKey`）

v2 规范（推荐，避免跨端 JSON 格式陷阱）：

```text
"v2|" + sha1(
  "e=" + emotion.wire + "\n" +
  "m=" + model + "\n" +
  "p=" + pitchPlain + "\n" +
  "s=" + speedPlain + "\n" +
  "t=" + text + "\n" +
  "v=" + voiceId + "\n" +
  "vol=" + volumePlain
).hex().take(12)
```

- 浮点用 `BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()`；避免 `1.0E-4`。
- 输入必须包含：文本、情绪、音色、语速、音量、音调、TTS 模型。
- 如果 v1.1 要导入服务器数据，再加 `LegacyCacheKey`（Python JSON 口径），不要改 v2。
- 测试：同输入稳定；任一输入变化 → 键变化；模型变化 → 键变化。

### 8.9 渲染流水线（`RenderChapterUseCase`）

```text
items = segments.map { seg ->
  vp = voiceParams(roleId)   // 角色微调 > 角色锁定 > 全局默认
  key = cacheKey(seg.text, seg.emotion, vp.voiceId, vp.speed, vp.volume, vp.pitch, model)
  if seg.status == done && seg.ttsKey in (key, legacy) && file exists -> cached
  else -> pending(output path)
}
workers = min(ttsConcurrency, pending.size)
工作池：固定 N 个 worker 抢队列，无批屏障；每段检查 stop
each pending:
  set status=running
  synthesize -> tmp file
  postProcess (pitch/volume) -> tmp -> replace
  write .hash
  set status=done, audioRelPath, ttsKey, duration
all done -> stitch(files, types, outM4a)
set chapter audio_status=done; novel_meta.lastGeneratedChapter = max(...)
```

- 错误：单段失败 → 段 status=error，章节 error；保留已完成段；重跑只补失败段。
- 停止：worker 每段前检查 `shouldStop`；已入队但未开始的段保持 pending；`sweepRunning` 复位。
- 空片段章节：明确报错，不调用 ffmpeg。
- 换音色：只重置该角色片段 + 含该角色片段的章节 audio_status；不要全书 pending。

### 8.10 长章节守卫（v1）

- 解析前：`rawText.length > 12000` 时弹提示“章节较长，可能输出截断；建议 v1.1 分块”，允许用户继续或取消。
- LLM 返回 `finish_reason=length` → 章节 `parse_status=error`，错误文案包含“本章过长/输出截断，可手动分段或等待分块解析”。
- 不静默降级为整章旁白。
- v1.1（P2.9）再做按段落窗口分块 + overlap + 覆盖率合并；v1 不实现。

### 8.11 领域测试

- `EmotionTest`：11 个 wire/prefix。
- `PromptAssetsTest`：三段提示词 SHA-256 与 fixture 一致；占位符替换不破坏 `{}`。
- `VoicePoolTest`：63 key、voice_id、speed/note、旁白排除、兜底。
- `ScriptSanitizerTest`：Python 用例逐条 + fixtures + golden。
- `CoverageCheckerTest`：fixtures + 误差阈值。
- `CharacterRegistryTest`：新角色、复用、alias_of、excluded、音色锁定。
- `TtsCacheKeyTest`：稳定性/敏感性/模型。
- `AnalyzeChapterUseCaseTest`：非法 JSON、截断、未知说话人、覆盖率重试（fake LLM）。
- `RenderChapterUseCaseTest`：缓存命中、模型变化、工作池无屏障、停止、空片段（fake TTS/ffmpeg）。

---

## 9. 网络层

### 9.1 OkHttp 配置（`OkHttpProvider`）

```kotlin
val client = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(600, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .callTimeout(660, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()
```

- 全 App 共享一个 `OkHttpClient`（连接池）。
- TTS 用单独的 `OkHttpClient`（read 120s / connect 10s），避免长 LLM 请求占满连接。
- `HttpLoggingInterceptor` 仅 debug，`redactHeader("Authorization")`；release 不打印请求体/文本。
- 证书：只用系统信任；不做 pinning（Fish/DashScope 证书轮换风险）。`usesCleartextTraffic=false`。

### 9.2 LLM 客户端（`LlmClient`）

**请求**

```http
POST {llm_base_url}/chat/completions
Authorization: Bearer {llm_api_key}
Content-Type: application/json
```

```json
{
  "model": "qwen3.7-flash",
  "messages": [{"role": "user", "content": "<prompt>"}],
  "temperature": 0,
  "thinking": {"type": "disabled"},
  "response_format": {"type": "json_object"}
}
```

**响应**：取 `choices[0].message.content`；检查 `choices[0].finish_reason`。

**重试策略**

| 情况 | 动作 |
|---|---|
| 连接/读超时、5xx、429 | 最多 6 次；`sleep(min(2^attempt*5, 60))` + jitter |
| 400 且错误提到 `response_format` | 去掉该字段重试一次 |
| 401/402/403/404 | 不重试；映射为“Key 无效/额度不足/模型不存在” |
| `finish_reason=length` | 不重试；抛 `ChapterTooLongException` |
| JSON 解析失败 | 最多 2 次追加“只输出严格 JSON”；仍失败 → 降级整章旁白 + warn |

**超时**：`callTimeout=660s`、`readTimeout=600s`、`connectTimeout=30s`。

**Provider 兼容**：`thinking` 只在 DashScope 域名默认发送；其他 base URL 可关闭。`response_format` 失败自动回退。设置页提供“测试连接”按钮。

### 9.3 Fish TTS 客户端（`FishTtsClient`）

**请求**

```http
POST {fish_base_url}/v1/tts
Authorization: Bearer {fish_api_key}
model: {fish_tts_model}
Content-Type: application/json
```

```json
{
  "text": "[happy] 你好。",
  "reference_id": "<voice_id>",
  "format": "mp3",
  "mp3_bitrate": 64,
  "normalize": true,
  "prosody": { "speed": 1.15 }
}
```

- `prosody` 只在 speed 非空时发送；**不要**依赖服务端 pitch/volume（实测不生效）。
- 响应：二进制 mp3；先写 `.tmp`，成功后 rename；再 post-process。
- 错误：
  - 401 → Key 无效；402 → 额度不足；429/503 → 退避重试；400 → 请求/文本问题，不重试。
  - 响应非音频/空 body → 抛 `TtsProtocolException`。
- 重试 4 次：`sleep(min(2^attempt*2, 30))` + jitter；尊重 `Retry-After`。
- 超时：`connect=10s`、`read=120s`、`call=150s`。

### 9.4 并发与自适应

- 全局 TTS 信号量：`Semaphore(settings.ttsConcurrency)`；同一时间只允许一个批次，但仍保留全局限流。
- LLM 信号量：`Semaphore(settings.llmConcurrency)`；默认 1。
- 自适应（P2.7）：
  - 初始并发 2；
  - 连续 20 段无 429/超时且 p95 < 8s → +1，直到设置上限；
  - 任一 429/超时 → 减半（最低 1），冷却 60s；
  - 记录到 `jobs.statsJson`，诊断页可见。
- 网络：`ConnectivityMonitor` 监听 `NET_CAPABILITY_NOT_METERED`；`wifi_only=true` 时蜂窝下暂停/提示。

### 9.5 错误码（`PipelineError`）

| code | 场景 | UI 文案 |
|---|---|---|
| `AUTH_LLM` | 401 | “LLM Key 无效，请在设置中检查” |
| `AUTH_FISH` | 401 | “Fish Key 无效，请在设置中检查” |
| `QUOTA` | 402/429 持续 | “额度不足或被限流，稍后再试” |
| `MODEL_NOT_FOUND` | 404/400 | “模型不存在，请检查模型名” |
| `NETWORK` | 超时/断网 | “网络不可用，已暂停，恢复后继续” |
| `TRUNCATED` | finish_reason=length | “本章过长，输出被截断” |
| `BAD_JSON` | 解析失败 | “模型输出不是合法 JSON，已降级为旁白” |
| `TTS_FAILED` | 合成失败 | “第 N 段合成失败：…” |
| `STORAGE` | 空间不足 | “存储空间不足，请清理后重试” |
| `FFMPEG` | 拼接失败 | “音频拼接失败：…” |
| `NO_SEGMENTS` | 空章节 | “本章没有可拼接的片段” |

### 9.6 网络测试

- `MockWebServer`：LLM 200/429/500/400/截断/非法 JSON；Fish 200/401/429/503/空 body。
- `RetryPolicyTest`：次数、退避、不可重试码。
- `AdaptiveConcurrencyTest`：成功升、429 降、冷却。
- 真实 API smoke：只在用户提供 Key 时手动跑；CI 不跑。

---

## 10. 音频层

### 10.1 FFmpegKit 封装（`FfmpegKitRunner`）

```kotlin
data class FfmpegResult(val sessionId: Long, val output: String)

suspend fun run(command: String): FfmpegResult = suspendCancellableCoroutine { cont ->
    val session = FFmpegKit.executeAsync(command) { s ->
        if (ReturnCode.isSuccess(s.returnCode)) {
            cont.resume(FfmpegResult(s.sessionId, s.output ?: ""))
        } else {
            cont.resumeWithException(
                FfmpegException(s.returnCode?.value ?: -1, s.failStackTrace ?: s.output ?: "")
            )
        }
    }
    cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
}
```

- 同一时间只允许一个 ffmpeg 任务（`Mutex`），避免 CPU/内存峰值。
- 错误日志截断到 800 字符；UI 只显示可读摘要。
- 启动时 `FFmpegKit.executeAsync("-version")` 做自检（Debug 页），失败提示 ABI/安装问题。

### 10.2 后处理（pitch / volume）

```bash
ffmpeg -y -i <in.mp3> \
  -af "asetrate=44100*2^(pitch/12),aresample=44100,atempo=2^(-pitch/12),volume=<volume>" \
  -b:a 64k <out.mp3>
```

- `pitch=0`、`volume=1` 时跳过。
- 先写 `.post.mp3`，成功 `replace`；失败保留原文件，尝试仅 volume 兜底；再失败保留原音频并 warn，不标记 done。
- 文件存在且 `.hash` 匹配 → 跳过。

### 10.3 静音与拼接

```bash
# 350ms / 700ms 静音（需要 LAME，故用 ffmpeg-kit-audio）
ffmpeg -y -f lavfi -i anullsrc=r=44100:cl=mono -t 0.35 -b:a 64k _sil350.mp3
ffmpeg -y -f lavfi -i anullsrc=r=44100:cl=mono -t 0.70 -b:a 64k _sil700.mp3

# 直接 AAC（推荐；省中间 WAV）
ffmpeg -y -f concat -safe 0 -i concat.txt \
  -ar 44100 -ac 1 -c:a aac -b:a 96k -movflags +faststart chapter.m4a

# 回退：先 WAV 再 AAC
ffmpeg -y -f concat -safe 0 -i concat.txt -ar 44100 -ac 1 -c:a pcm_s16le _chapter.wav
ffmpeg -y -i _chapter.wav -c:a aac -b:a 96k -movflags +faststart chapter.m4a
```

- 拼接顺序：`files[i]`，若 `types[i]==dialogue && types[i+1]==narration` → 700ms；否则 350ms（最后一段不加）。
- `concat.txt` 每行 `file '<absolute path>'`；路径只含 ASCII；单引号转义 `'\''`。
- 成功后删除 `_chapter.wav`、`concat.txt`；静音文件按设置哈希缓存，不要每章重建。
- 输出 `chapter_<idx+1:03d>.m4a`；用 `MediaMetadataRetriever` 读 duration 存 DB。
- 失败：章节 `audio_status=error`，保留已完成片段；不要误写 done。

### 10.4 播放（Media3）

- `AudioForgeMediaSessionService : MediaSessionService`：
  - `ExoPlayer.Builder(this).build()`；
  - `MediaSession.Builder(this, player).build()`；
  - `onGetSession` 返回 session；
  - `onTaskRemoved` 按需停止。
- `PlayerController`（AppContainer 单例）：
  - `playChapter(novelId, chapterId, file)`；
  - `playSegments(list)`（片段试听队列）；
  - `play/pause/seek/next/prev/setSpeed/sleepTimer`；
  - `StateFlow<PlayerUiState>`（current title/sub/position/duration/playing）。
- `AudioAttributes`：`USAGE_MEDIA`、`CONTENT_TYPE_SPEECH`、`handleAudioFocus=true`。
- 通知：Media3 `DefaultMediaNotificationProvider`；channel `playback`；`FOREGROUND_SERVICE_MEDIA_PLAYBACK`。
- 耳机/蓝牙：MediaSession 自动处理。
- 播放位置：按章节存 DataStore（`last_position_<chapterId>`），下次续播。
- 片段试听：`MediaItem` 队列；不要与章节播放混在一个队列。
- 睡眠定时：协程 `delay(minutes)` → pause；UI 显示剩余。

### 10.5 导出/分享

- API 29+：
  - `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`；
  - `RELATIVE_PATH = Environment.DIRECTORY_MUSIC + "/AudioForge/<safeTitle>"`；
  - `IS_PENDING=1` → 写入 → `IS_PENDING=0`；
  - 文件名 `<safeTitle>-第NNN章.m4a`。
- API 26–28：
  - 复制到 `getExternalFilesDir(DIRECTORY_MUSIC)/AudioForge/`；
  - 通过 `FileProvider` 分享；不申请公共存储权限。
- 分享：`Intent.ACTION_SEND`，`type=audio/mp4`，`FLAG_GRANT_READ_URI_PERMISSION`。
- 文件名清洗：`\ / : * ? " < > |` 替换为 `_`；长度 ≤80。

### 10.6 音频测试

- `TtsCommandBuilderTest`：pitch/volume 0 跳过、滤镜字符串、路径转义。
- `StitchListBuilderTest`：350/700 顺序、最后一段无静音、空列表报错。
- `FfmpegKitRunnerTest`（instrumented/emulator）：`-version`、静音生成、拼接 3 个短 mp3，输出可播放。
- 手动 A/B：同一组片段，服务器 vs Android；时长误差 <100ms；顺序/停顿正确；可播放；音高方向正确。
- 音频质量主观检查：中文清晰、无爆音、无截断、角色区分明显。

---

## 11. 后台任务

### 11.1 任务模型

- 同一本书同一时间只有一个 `GenerationWorker`；不同书排队（v1 不并行）。
- `BatchRunner.start(novelId, ids, mode, force)`：
  - 校验参数/Key/章节范围；
  - 若已有运行任务 → 先优雅停止（≤30s）或拒绝；
  - 插入 `JobEntity(status=queued)`；
  - `WorkManager.enqueueUniqueWork("generation-$novelId", KEEP, request)`。
- `GenerationWorker`：
  - `CoroutineWorker`；
  - `setForeground(createForegroundInfo(...))`；
  - `doWork` 逐章：parse/render；每章更新 job 进度；
  - `isStopped` 检查；`Result.retry()` 用于可恢复网络错误；`Result.failure()` 用于配置/Key/参数；
  - `finally`：`sweepRunning(novelId)`；更新 job 状态；发完成/失败通知。

### 11.2 前台服务与通知

```kotlin
private fun createForegroundInfo(title: String, progress: Int): ForegroundInfo {
    val notification = NotificationCompat.Builder(context, CHANNEL_GENERATION)
        .setSmallIcon(R.drawable.ic_stat_audioforge)
        .setContentTitle(title)
        .setContentText("$progress%")
        .setOngoing(true)
        .setProgress(100, progress, false)
        .addAction(0, "停止", stopPendingIntent)
        .build()
    return ForegroundInfo(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
}
```

- 渠道：
  - `generation`：低重要性，进度；
  - `generation_done`：默认，完成/失败；
  - `playback`：低，媒体通知。
- Android 13+ 首次进入生成页请求 `POST_NOTIFICATIONS`；拒绝时应用内提示“通知不可见，但任务仍会运行”。
- 停止：通知 action / 应用内按钮 → `stopRequested=true` + `WorkManager.cancelUniqueWork`；worker 检查 `isStopped`。
- 6h/24h：默认 5 章、上限 20 章；`JobWatchdog` 记录开始时间，>5h 主动收尾并提示“下一批继续”；处理 `isStopped`。
- 约束：
  - `NetworkType.CONNECTED`；`wifi_only` → `UNMETERED`；
  - `charging_only` → `requiresCharging(true)`；
  - 电量低于阈值 → 暂停/延迟（worker 内检查，不靠 constraint）；
  - 热状态 `THERMAL_STATUS_SEVERE` → 降并发/暂停；
  - 省电模式 → 降并发。
- 唤醒锁：worker 内 `PARTIAL_WAKE_LOCK`（超时 6h），`finally` 释放；不要常驻。
- 进程死亡/重启：WorkManager 持久化；App 启动 `sweepRunning`；`jobs` 表保留上次结果；`RECEIVE_BOOT_COMPLETED` 由 WorkManager 处理。

### 11.3 批次进度

- `JobEntity.statsJson` 存：
  ```json
  {"chaptersDone":1,"chaptersTotal":5,"segDone":42,"segTotal":150,
   "ttsOk":40,"ttsFailed":2,"retries":3,"startedAt":...}
  ```
- UI 不轮询：`JobDao.observeActive(novelId)` + `WorkManager.getWorkInfosForUniqueWorkFlow`（或 `job` 表更新）。
- 进度更新节流：每段写 DB 太频繁 → 每 5 段或 1s 批量更新一次；章节结束强制更新。

### 11.4 后台测试

- `GenerationWorkerTest`（WorkManager TestDriver + fake pipeline）：成功、重试、停止、sweep。
- 手动：
  - 锁屏 30 分钟；
  - 切后台/杀进程/重启；
  - 断网/恢复；
  - 省电模式/低电量；
  - 国产 ROM（Honor/EMUI、MIUI、ColorOS）各 1 台。
- 通过标准：任务继续或可恢复；通知可见；停止能收尾；无 ANR/Crash。

---

## 12. UI / UX

### 12.1 设计原则

- **手机优先**：单手可达；主要操作在底部/右下；危险操作二次确认。
- **过程可见**：长任务显示当前章节/片段、进度、预计；失败原因可读、可操作。
- **少层级**：项目 → 章节 → 剧本，最多三层；设置/人物/任务从项目页或底栏进入。
- **Expressive 只用在“有情绪”的地方**：主按钮、进度、空态、播放器；表格/表单保持标准 M3，避免花哨影响可读性。
- **中文排版**：正文 15–16sp、行高 1.8；标题不过度加粗；长文本可换行，不截断关键信息。
- **深色/浅色/动态色**：默认跟随系统；动态色 Android 12+ 可关。
- **无障碍**：TalkBack、48dp、字体 200%、对比度 4.5:1、减少动画。

### 12.2 主题（`ui/theme`）

```kotlin
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AudioForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 ->
            if (darkTheme) dynamicDarkColorScheme(LocalContext.current)
            else dynamicLightColorScheme(LocalContext.current)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = AudioForgeTypography,
        shapes = AudioForgeShapes,
        content = content,
    )
}
```

- 品牌色：主色靛蓝 `#4F46E5`（浅）/`#818CF8`（深）；强调琥珀 `#D97706`/`#FBBF24`（音频/进度）。
- 语义色：成功绿、警告琥珀、错误红、信息蓝；不要只靠颜色传达状态（配图标/文字）。
- 字体：系统字体；`Typography` 只调字号/行高/字重；不要引入只含拉丁字形的字体。
- 形状：12/16/24/28dp；Expressive 大形状只用于 Hero/播放器/空态。
- 动效：`MotionScheme.expressive()`；页面转场 250–350ms；尊重系统“移除动画”。
- 图标：Material Symbols 导出 VectorDrawable（约 30–40 个），不要引入 `material-icons-extended`（体积大且已不推荐）。

### 12.3 导航

```kotlin
@Serializable sealed interface Route {
    @Serializable data object Projects : Route
    @Serializable data class Project(val novelId: Long) : Route
    @Serializable data class Script(val novelId: Long, val chapterId: Long) : Route
    @Serializable data class Voices(val novelId: Long) : Route
    @Serializable data class ProjectSettings(val novelId: Long) : Route
    @Serializable data object Settings : Route
    @Serializable data object Tasks : Route
    @Serializable data object Storage : Route
    @Serializable data object Diagnostics : Route
    @Serializable data class Player(val novelId: Long?, val chapterId: Long?) : Route
}
```

- 根部 `NavigationSuiteScaffold`：
  - 手机：底部 `NavigationBar`（项目 / 任务 / 设置）；
  - 平板/折叠：`NavigationRail`；
  - 使用 `WindowSizeClass` 判断。
- 项目详情、剧本、人物、项目设置、播放器为二级页面；进入时隐藏底栏（或保留，视空间）。
- 迷你播放器固定在底栏上方；点击进入全屏播放器。
- 预测式返回：`enableOnBackInvokedCallback=true`；编辑未保存时拦截返回并确认。

### 12.4 屏幕规格

#### S01 Onboarding

- **目的**：首次启动，解释产品、配置 Key、权限。
- **布局**：3 步 Pager/水平翻页：
  1. 欢迎 + 产品一句话 + “开始”；
  2. LLM Key/Base/Model + “测试连接”；
  3. Fish Key/Model + “测试连接”；通知权限、电池优化说明。
- **状态**：Key 为空也可“稍后设置”，但开始任务时拦截。
- **动作**：保存并继续、跳过、测试连接、请求通知权限、打开电池优化设置。
- **文案**：见 §12.7。
- **验收**：无 Key 能进项目库；有 Key 能测试成功；拒绝通知有解释；不崩溃。

#### S02 Projects（项目库）

- **目的**：管理所有书。
- **布局**：
  - `LargeFlexibleTopAppBar`：标题“项目”，副标题“把小说变成多人有声书”；
  - 搜索框（书名）；
  - `LazyVerticalGrid`（手机 1 列，平板 2–3 列）；
  - 项目卡：书名、章节数、进度环/条、音频完成数、片段数、最近打开；长按菜单：重命名/删除/导出全部；
  - `FloatingActionButtonMenu`：导入 txt。
- **状态**：空态（插图 + “导入第一本小说”）、加载骨架、错误重试。
- **动作**：点击进入项目；FAB 导入；搜索；长按重命名/删除。
- **验收**：2000 章项目卡不卡；导入后自动进入项目页；删除二次确认。

#### S03 Import Sheet

- **目的**：导入 txt，预览切章。
- **布局**：全屏/大 BottomSheet：
  - 文件选择（SAF）；
  - 文件信息：名称、大小、编码（自动检测，可手动改）；
  - 切章预览：章节数、前 3 个标题、总字数；
  - 项目名（默认文件名，可改）；
  - 导入按钮 + 进度。
- **状态**：解析中、编码失败、空文件、超大文件、重名。
- **动作**：选择文件、改编码、改标题、导入、取消。
- **验收**：UTF-8/GBK/UTF-16 都能导入；重名不覆盖；失败有原因。

#### S04 Project Detail（项目详情）

- **目的**：一本书的控制台。
- **布局**：
  - 顶部 `LargeFlexibleTopAppBar`：书名、返回、更多（重命名/删除/项目设置/导出）；
  - 进度卡：总章数、音频完成、片段 done/total、进度环、上次生成到第几章；
  - 运行控制卡：
    - `ButtonGroup` 模式：解析+生成 / 仅解析 / 仅生成；
    - 起止章输入（数字键盘）；
    - 强制重新解析 Switch；
    - 开始 `SplitButton` / 停止；
    - 批次估算：“本次 5 章 · 约 150 段 · 约 60 MB”；
    - 上次错误横幅（可关闭）。
  - 筛选栏：搜索标题、解析状态、音频状态、清除；
  - 章节列表：序号、标题、告警图标、状态 Pill、片段进度、快速开始/继续、更多（剧本/播放/下载/重新解析）；
  - 人物条：角色 chips + “管理音色”；
  - 底部批量操作栏（可选）。
- **状态**：无章节、全部完成、有失败、运行中、停止中。
- **动作**：开始/停止、筛选、搜索、打开剧本、单章快速开始、换音色、重命名/删除。
- **验收**：默认 5 章；开始前有估算；运行中进度实时；停止能收尾；失败可重试。

#### S05 Batch Sheet（批次设置）

- **目的**：设置起止章/模式/强制。
- **布局**：BottomSheet：
  - 模式 SegmentedButton；
  - 起止章 NumberPicker/输入；
  - “上次生成到第 N 章，默认下一批”提示；
  - 强制重新解析 Switch；
  - 估算：章节数、预计片段、预计流量/存储；
  - 开始/取消。
- **验收**：起止校验；超过 20 章提示分批；无 Key 时跳转设置。

#### S06 Script（剧本页）

- **目的**：查看/编辑/试听一章的剧本。
- **布局**：
  - 顶部：返回、章节标题、解析/音频状态、更多（重新解析/生成本章/下载/分享）；
  - 告警横幅（覆盖率/未识别/JSON 降级/截断）；
  - 片段列表 `LazyColumn`：
    - 角色头像（首字+颜色）、角色名、情绪 chip、序号、状态 Pill；
    - 文本（旁白/台词不同左边框色）；
    - 底部：播放/暂停、编辑、重新生成、错误信息；
    - 当前播放片段高亮 + 自动滚动；
  - 底部浮动工具栏：连播、暂停、下一句（可选）；
  - 空态：未解析 → 原文预览 + “解析本章”。
- **状态**：加载、未解析、解析中、空片段、播放中、编辑中、失败。
- **动作**：播放单句/连播、编辑、重新生成、重新解析、生成本章、下载/分享、返回。
- **验收**：播放跟随；编辑只重合成该段；告警可读；无音频片段禁用播放。

#### S07 Segment Edit Sheet

- **目的**：编辑单段角色/情绪/文本。
- **布局**：BottomSheet：
  - 角色下拉（旁白 + 当前项目角色，可搜索）；
  - 情绪下拉（11 个，显示中文标签 + wire）；
  - 文本多行（≤2000 字，计数器）；
  - 提示“保存后仅该片段标记待重生成”；
  - 取消/保存。
- **验收**：空文本/超长/未知角色/未知情绪拦截；保存后 DB/UI 同步；键盘不遮挡。

#### S08 Voices（人物音色）

- **目的**：管理角色音色与试听。
- **布局**：
  - 顶部标题 + 角色数/音色数；
  - 角色卡列表：头像、名字、role_id、重要度、性别、别名、note、当前音色、试听按钮、更换按钮；
  - 音色库：搜索、档位筛选（旁白/男主/男配/反派/老年/女性/龙套）、63 类列表、试听、note 警告。
- **状态**：无角色（去解析）、试听加载、失败。
- **动作**：试听当前/候选、更换音色、搜索/筛选。
- **验收**：63 类可搜；换音色提示“相关片段将重生成”；试听缓存不重复扣费。

#### S09 Voice Picker

- **目的**：为角色选音色。
- **布局**：BottomSheet/全屏：
  - 搜索框；
  - 档位 FilterChip；
  - 列表：label、key、note、试听按钮、选中勾；
  - 底部保存/取消。
- **验收**：选中高亮；保存后角色卡更新；不误触试听。

#### S10 Project Settings / Character Tuning

- **目的**：每角色语速/音量/音调微调。
- **布局**：角色列表 + 滑杆/输入：
  - 语速 0.5–2.0；
  - 音量 0.5–2.0；
  - 音调 -12–+12；
  - “留空跟随全局”；
  - 试听按钮（用当前草稿参数）；
  - 保存。
- **验收**：范围校验；保存后重新生成生效；试听不写 DB。

#### S11 Global Settings

- **分组**：
  1. API：LLM Base/Model/Key（掩码）、Fish Base/Model/Key、测试连接；
  2. 生成：LLM 并发、TTS 并发、分段字数、最低覆盖率、批次大小；
  3. 音频：对白停顿、段落停顿、默认音色、默认语速/音量/音调；
  4. 网络/电量：仅 Wi-Fi、仅充电、低电量阈值、热降频；
  5. 外观：主题、动态色；
  6. 存储：占用、清理缓存、清理孤儿、保留天数；
  7. 诊断/关于：日志、版本、许可、隐私。
- **验收**：范围校验；保存即生效；Key 不回显；测试连接有结果。

#### S12 Tasks

- **目的**：当前/历史任务。
- **布局**：
  - 当前任务卡：书名、模式、当前章节/片段、进度条、ETA、停止/暂停；
  - 队列：等待中的书（可取消）；
  - 历史：完成/失败时间、错误、重试；
  - 空态。
- **验收**：切页/锁屏不丢；停止有反馈；失败可重试。

#### S13 Player

- **目的**：全屏播放。
- **布局**：
  - 封面/波形占位、书名/章节名；
  - 进度条（可拖动）、时间；
  - 播放/暂停、上一章/下一章、±15s；
  - 倍速 0.75–2.0；
  - 睡眠定时；
  - 队列/章节列表；
  - 分享/导出。
- **验收**：后台/锁屏/蓝牙；音频焦点；切页不中断；位置记忆。

#### S14 Storage

- **目的**：管理空间。
- **布局**：总占用、分类（成品/片段/缓存/日志）、按项目列表、清理孤儿、清理缓存、保留策略。
- **验收**：删除前确认；显示释放量；不误删 DB 引用文件。

#### S15 Diagnostics / About

- **目的**：排查与合规。
- **布局**：版本/构建号、设备信息、最近错误、日志导出/清除、FFmpeg 自检、开源许可、隐私说明、上游 attribution。
- **验收**：日志不含 Key/全文；导出通过 FileProvider。

### 12.5 组件清单

| 组件 | 用途 | 要点 |
|---|---|---|
| `StatusPill` | 状态 | 图标+文字+颜色；running 有动画；error 可点 |
| `RoleAvatar` | 角色头像 | 首字+哈希色；contentDescription |
| `ProgressRing` | 项目进度 | 可访问性 `progressBarRangeInfo` |
| `JobCard` | 任务进度 | 当前标题、进度、停止 |
| `EmptyState` | 空态 | 图标+标题+行动按钮 |
| `ErrorBanner` | 错误 | 可关闭、重试 |
| `WarningBanner` | 解析告警 | 覆盖率/说话人/截断 |
| `SectionHeader` | 设置分组 | 标题+说明 |
| `NumberField` | 起止章 | 数字键盘、范围校验 |
| `LabeledSlider` | 语速/音量/音调 | 当前值、重置 |
| `PlayerBar` | 迷你播放器 | 点击展开、播放/暂停 |
| `ConfirmDialog` | 删除/重解析 | 危险色、明确后果 |

### 12.6 M3 Expressive 使用映射

| 场景 | 组件 | opt-in |
|---|---|---|
| 主题/动效 | `MaterialExpressiveTheme` + `MotionScheme.expressive()` | 是 |
| 项目详情头 | `LargeFlexibleTopAppBar` | 是 |
| 模式选择 | `ButtonGroup` / `SegmentedButton` | 是（ButtonGroup） |
| 开始/停止 | `SplitButton` / `FilledTonalButton` | 是（SplitButton） |
| 导入/新建 | `FloatingActionButtonMenu` | 是 |
| 长任务 | `LoadingIndicator` / `LinearWavyProgressIndicator` | 是 |
| 剧本操作 | `HorizontalFloatingToolbar` | 是 |
| 筛选 | `FilterChip`（shape morphing） | 是 |
| 列表 | `ListItem`（Expressive） | 是 |
| 自适应导航 | `NavigationSuiteScaffold` | 视版本 |

> 每个使用点写 `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`，或在一个 `Expressive.kt` 包装层集中 opt-in；不要用模块级编译器参数掩盖所有实验 API（本计划 §6.4 已加了 opt-in 参数，但仍建议包装层便于升级）。

### 12.7 文案（中文，集中在 `strings.xml`）

至少包含：

- 通用：`确定`、`取消`、`保存`、`删除`、`重试`、`停止`、`继续`、`返回`、`关闭`、`加载中`、`空`、`失败`。
- 项目：`导入小说`、`书名`、`章节`、`进度`、`上次生成到第 %1$d 章`、`删除项目`、`重命名`。
- 批次：`解析+生成`、`仅解析`、`仅生成`、`起始章`、`结束章`、`强制重新解析`、`开始`、`停止`、`本次 %1$d 章`。
- 解析：`解析本章`、`重新解析`、`覆盖率 %1$d%%`、`%1$d 句台词说话人未识别`、`输出被截断`、`已降级为整章旁白`。
- 剧本：`旁白`、`台词`、`编辑片段`、`重新生成此片段`、`连播`、`试听成品`、`下载`。
- 人物：`人物音色`、`当前音色`、`更换`、`试听`、`音色库`、`档位`。
- 设置：`API Key`、`测试连接`、`并发`、`分段字数`、`停顿`、`默认音色`、`仅 Wi-Fi`、`仅充电`、`清理`。
- 任务：`任务中心`、`运行中`、`已排队`、`完成`、`失败`、`预计剩余`。
- 播放器：`播放`、`暂停`、`上一章`、`下一章`、`倍速`、`睡眠定时`。
- 错误：见 §9.5。

### 12.8 无障碍

- 所有图标按钮有 `contentDescription`；装饰性图标 `null`。
- 标题用 `Modifier.semantics { heading() }`。
- 进度条 `progressBarRangeInfo`；任务进度 `liveRegion = Polite`。
- 触控 ≥48dp；列表项整行可点；不要只靠颜色。
- 字体 200%：关键按钮不截断；长文本可滚动。
- TalkBack 能完成：导入 → 开始 → 停止 → 打开剧本 → 播放 → 换音色 → 导出。
- 对比度：用 M3 colorScheme；深色单独检查。
- 动画：尊重 `Settings.Global.ANIMATOR_DURATION_SCALE`；不要无限动画（LoadingIndicator 除外）。

### 12.9 性能

- `LazyColumn`/`LazyVerticalGrid` 使用稳定 `key`；避免在 item 内做 DB/网络。
- 项目/章节用 `Paging 3` 或 `Flow` + `collectAsLazyPagingItems`；不要一次加载 2000 章正文。
- 章节列表只查 brief 投影；正文按需 `getChapter`。
- 大文本 `Text` 可能很重：剧本页每段独立 `Text`，不要一个 10 万字 `Text`；原文预览分页/限制。
- 图片：本项目基本无图片；头像用 Canvas/首字，避免 Bitmap。
- 基线：2000 章首帧 <500ms；滚动 60fps；剧本 200 段滚动流畅；内存 <200MB。

### 12.10 UI 测试

- Compose UI test（Robolectric 或 instrumented）：导入空态、项目卡点击、开始批次确认、剧本编辑保存、设置范围错误、播放器按钮。
- 截图/预览：每个屏幕提供 `@Preview`（手机/深色/字体 1.3x）。
- 手动：TalkBack、字体 200%、深色、折叠屏/平板。

---

## 13. 安全、隐私与合规

### 13.1 密钥

- 仅存 Keystore 加密后的密文；DataStore 不存明文。
- UI 掩码：只显示末 4 位；复制按钮可选；截图时密钥页 `FLAG_SECURE`。
- 日志：`Authorization`、Key、完整请求体/响应体一律不打印；文本只打印长度/哈希前 8 位。
- `android:allowBackup=false`；`dataExtractionRules` 排除数据库/SharedPreferences/文件。
- 不在 `BuildConfig` 里写 Key；不在 `strings.xml`/assets 里写 Key。
- CI 不配置真实 Key；单测用 MockWebServer。

### 13.2 网络

- 仅 HTTPS；`usesCleartextTraffic=false` + `networkSecurityConfig`。
- 不做证书 pinning（Fish/DashScope 轮换会导致全量失败）；如未来需要，做“可选严格模式”。
- 超时/重试/取消；不要在后台无限重试。
- 代理：不默认使用；如用户系统代理，OkHttp 自动遵循。

### 13.3 文件

- 不申请 `MANAGE_EXTERNAL_STORAGE`；SAF 导入、MediaStore/FileProvider 导出。
- 导出文件名清洗；不把用户标题直接拼进路径。
- 删除项目/清理孤儿前二次确认；只删 DB 引用之外的文件。
- `FileProvider` 只暴露必要的 `files/audio`、`cache`、`external-files/exports`。

### 13.4 许可

- 上游 StoryForge 是 MIT；保留版权与许可文本，App “关于/开源许可”页展示。
- FFmpegKit `audio` 是 LGPL-3.0；只使用非 GPL 变体；保留 AAR 自带 `license*` 资源；提供“开源许可”页。
- Fish Audio / DashScope 服务条款由用户承担；隐私说明写清“文本会发送到用户配置的第三方服务”。
- 小说版权由用户负责；App 不内置书源、不分发内容。
- 音色样音按需合成，不随 APK 分发第三方样音（除非确认授权）。

### 13.5 隐私文案（要点）

- 收集：无账号、无分析 SDK、无广告；本地保存项目/剧本/音频/设置。
- 发送：用户配置的 LLM（章节文本）与 Fish（片段文本+音色 id）；不发送设备通讯录/位置/照片。
- 密钥：加密存本机；不上传。
- 删除：删除项目/卸载 App 会删除本地数据（导出除外）。
- 第三方：LLM/Fish 的隐私政策由各自提供。

### 13.6 安全测试

- 静态：`lint` 无 `ExportedService`/`CleartextTraffic`/`AllowBackup` 高危项。
- 手动：Key 页截图、日志导出检查、FileProvider 越权、路径穿越（导入文件名）。
- 依赖：Gradle 校验和；关注 ffmpeg AAR 供应链。

---

## 14. 测试与验收

### 14.1 测试分层

| 层 | 工具 | 范围 | 是否 CI |
|---|---|---|---|
| 纯 JVM 单测 | JUnit4 + coroutines-test + MockWebServer | 清洗/覆盖率/缓存键/设置/重试/命令构建/仓库 fake | ✅ |
| Robolectric | Robolectric + Room in-memory + WorkManager TestDriver | DAO/事务/Worker 逻辑/Compose 基础 | ✅（若依赖稳定） |
| Instrumented | AndroidX Test + Compose UI Test | FFmpeg 真执行、Media3、SAF、Keystore、通知 | 可选/真机 |
| 手动 | 真机清单 | 后台/ROM/音频/无障碍/性能 | ✅（人工） |

### 14.2 必测清单（至少）

**导入/切章**
- UTF-8、UTF-8 BOM、GBK、UTF-16LE/BE、Big5、非法字节。
- 「第X章」「第X卷」「Chapter N」；无标题单章；假标题；CRLF；超长标题；空文件；重名。

**清洗/覆盖率**
- 原 Python `test_analyzer.py` 全部用例。
- fixture `strip_speech_tail` / `split_long` / `coverage`。
- 50 条真实章节 golden（脱敏）。
- 20k 字符性能基准。

**TTS/缓存**
- 情绪前缀；缓存键稳定/敏感；模型变化失效；旧键兼容（如做）。
- 429/503/超时重试；401/402 不重试；空 body；非音频。
- 原子写：后处理失败保留原文件；`.hash` 只在成功后写。
- 工作池：无批屏障；停止；单段失败。

**拼接**
- 350/700 顺序；最后一段无静音；空列表报错；路径含空格/单引号；直接 AAC/回退 WAV；输出可播放。

**后台**
- 成功/失败/重试/停止/sweep；锁屏/杀进程/重启；断网/恢复；低电量/省电/热；6h 软上限。

**UI**
- 空态/加载/错误；导入流程；开始批次确认；剧本编辑；换音色；设置范围；播放器；TalkBack/字体 200%。

**安全**
- Key 加密/清除；日志脱敏；备份排除；FileProvider 越权；路径穿越。

### 14.3 真机矩阵（最低）

| 设备/系统 | 用途 |
|---|---|
| Honor 20 / Android 10（EMUI） | 用户主力老设备；后台/存储 |
| Pixel / Android 15 或 16 | 新系统 FGS/16KB/通知 |
| 小米/Redmi MIUI 或 HyperOS | 国产 ROM 后台 |
| OPPO/ColorOS 或 vivo | 国产 ROM 后台 |
| 平板/折叠（可选） | 自适应布局 |

### 14.4 失败演练

- 飞行模式中途断网 → 恢复续传。
- 杀进程 → 重开恢复。
- 重启手机 → WorkManager 恢复/可手动继续。
- 存储剩 200MB → 预检拒绝，不写坏文件。
- 电量 10% / 省电模式 → 降并发/暂停提示。
- 429/503 → 退避、不烧 API。
- 401/402 → 明确提示，不重试。
- 超长章节 → 明确错误，不静默降级。
- 0 片段章节 → 明确错误，不调用 ffmpeg。

### 14.5 性能目标

- 冷启动 <2.5s（中端机）。
- 项目列表 2000 章首帧 <500ms；滚动 60fps。
- 剧本 200 段滚动流畅；编辑保存 <100ms。
- 30 分钟章节拼接 <30s（中端机）；峰值内存 <200MB。
- TTS 吞吐 ≥2 段/s（Wi-Fi，并发 4，服务端允许）。
- DB 批量更新 100 段 <200ms。

### 14.6 验收门（每个 PR/批次）

- `./gradlew lintDebug testDebugUnitTest assembleDebug` 绿。
- 新增/修改逻辑有对应测试。
- 无新增 `TODO`/`FIXME`（除非 issue 编号）。
- 无密钥/大文件/构建产物入库。
- `docs/progress.md` 更新。
- 关键 UI 有 `@Preview`。
- 手动 smoke（涉及后台/音频/UI 时）。

---

## 15. 任务清单（DSH 按顺序执行）

> 规则：任务 ID 不可跳；依赖未完成不得开始；每个任务完成即提交（Conventional Commits），并更新 `docs/progress.md`。
> 估时是净编码时间，不含等待 CI。每天结束前确保 CI 绿、工作区干净。

### Phase 0 — 基础与 smoke（1–2 天）

#### P0-01 初始化仓库与 CI 骨架
- 依赖：无。
- 文件：`.gitignore`、`README.md`、`docs/progress.md`、`gradlew`、`gradle/wrapper/*`。
- 步骤：创建目录；写入 `.gitignore`（§4.3）；用本地 VM 的 Gradle 9.9.0 执行 `gradle wrapper --gradle-version 9.9.0`，提交 wrapper；先不接 CI（P0-08 再接）。
- 验收：`./gradlew --version` 显示 9.9.0；仓库无 `local.properties`/keystore。
- 估时：0.5–1h。

#### P0-02 Gradle 工程与版本矩阵
- 依赖：P0-01。
- 文件：`settings.gradle.kts`、根 `build.gradle.kts`、`gradle.properties`、`app/build.gradle.kts`、`gradle/wrapper/*`、`gradlew`。
- 步骤：按 §6.1–§6.4、§6.11 写入；`gradle wrapper --gradle-version 9.9.0`；`local.properties` 只本地。
- 验收：`./gradlew help` 配置成功；`android.disallowKotlinSourceSets=false` 存在；`serialization 1.9.0`；`assembleDebug` 在 P0-04 后通过。
- 估时：1–2h。

#### P0-03 Manifest / 权限 / Service
- 依赖：P0-02。
- 文件：`app/src/main/AndroidManifest.xml`、`res/xml/*`、`res/values/themes.xml`。
- 步骤：按 §6.5–§6.9；确认 `SystemForegroundService` merge、`foregroundServiceType=dataSync`；创建 `res/drawable/ic_stat_audioforge.xml`（简单 vector，通知栏用）。
- 验收：`aapt2 dump xmltree` 能看到 dataSync；`usesCleartextTraffic=false`；无多余权限。
- 估时：0.5h。

#### P0-04 Compose 空壳与 Expressive 主题
- 依赖：P0-02。
- 文件：`AudioForgeApp.kt`、`MainActivity.kt`、`ui/theme/*`、`res/values/strings.xml`。
- 步骤：创建 `Application`（后续挂 AppContainer）；`MaterialExpressiveTheme` + `MotionScheme.expressive()` + 一个 `LoadingIndicator`；`@OptIn`；`enableEdgeToEdge()`。
- 验收：`assembleDebug` 成功；APK 可安装启动（真机/模拟器）；无 Crash。
- 估时：1h。

#### P0-05 Room smoke
- 依赖：P0-02。
- 文件：`data/db/Entities.kt`（最小 Probe）、`ProbeDao.kt`、`AppDatabase.kt`。
- 步骤：建库、插入/查询；确认 KSP 生成；`app/schemas` 输出。
- 验收：Debug 启动后 Room 读写成功（logcat/调试页）；KSP 无错。
- 估时：1h。

#### P0-06 WorkManager / 前台服务 smoke
- 依赖：P0-03、P0-05。
- 文件：`work/ProbeWorker.kt`、`MainActivity` 入队。
- 步骤：`CoroutineWorker` + `setForeground`（dataSync）；通知渠道；`enqueueUniqueWork`。
- 验收：真机/模拟器通知可见；Worker 执行成功；停止不崩。
- 估时：1–2h。

#### P0-07 FFmpeg smoke（真机/模拟器）
- 依赖：P0-02。
- 文件：`core/audio/FfmpegKitRunner.kt`（最小）、`DiagnosticsScreen` 或调试按钮。
- 步骤：`FFmpegKit.executeAsync("-version")`；生成 350ms 静音；拼接 2 个短 mp3（可用 assets 测试音频）。
- 验收：`-version` 成功；静音/拼接文件存在且可播放；16KB 校验通过。
- 估时：2h。

#### P0-08 CI debug 构建与 APK 校验
- 依赖：P0-02–P0-07。
- 文件：`.github/workflows/ci.yml`。
- 步骤：checkout、JDK 17、setup-android、`platforms;android-37.0`、`build-tools;37.0.0`、`./gradlew lintDebug testDebugUnitTest assembleDebug`、aapt2/zipalign、upload artifact。
- 验收：CI 绿；artifact 可下载安装；`aapt2` min/target 正确；`zipalign -P 16` 通过。
- 估时：1h。

#### P0-09 Release 签名 CI
- 依赖：P0-08。
- 文件：`.github/workflows/release.yml`、`scripts/verify-apk.sh`。
- 步骤：secrets 解码 keystore；`assembleRelease`；`apksigner verify`；上传 artifact；tag 触发 Release。
- 验收：用测试 secrets 跑通；APK 已签名；`apksigner` v2 通过；无 secrets 时明确失败。
- 估时：1–2h。

#### P0-10 进度与决策文档
- 依赖：P0-01。
- 文件：`docs/progress.md`、`docs/decisions.md`。
- 步骤：建表；记录版本矩阵、已知坑、P0 结果。
- 验收：DSH 后续每个任务能更新。
- 估时：0.5h。

#### P0-11 真机/模拟器 smoke 清单
- 依赖：P0-04–P0-07。
- 步骤：安装 APK；启动；Room 读写；Worker；FFmpeg；截图/录屏；记录设备/系统。
- 验收：四项 smoke 全过；失败有日志。
- 估时：1–2h。

#### P0-12 Phase 0 门禁
- 依赖：P0-01–P0-11。
- 步骤：跑 CI；整理问题；打 tag `v0.0.1-spike`（可选）。
- 验收：CI 绿；APK 可安装；FFmpeg `-version` 成功；Room/Worker 成功；文档更新。
- 估时：0.5h。

---

### Phase 1 — 纯 Kotlin 核心（3–5 天）

#### P1-01 模型与枚举
- 依赖：P0-02。
- 文件：`core/model/*.kt`。
- 步骤：按 §8.1；`Emotion` 11 个 wire/prefix；`ParseStatus/AudioStatus/SegmentStatus/SegmentType/Importance/Gender/BatchMode`；`RoleId` 常量。
- 验收：枚举值与 Python 一致；单测覆盖。
- 估时：1h。

#### P1-02 Assets：提示词 + 音色池
- 依赖：P1-01。
- 文件：`app/src/main/assets/prompts/*.txt`、`assets/voice_pool.json`、`PromptAssets.kt`、`VoicePool.kt`。
- 步骤：从 `docs/fixtures/porting-vectors.json` 提取三段提示词；复制 `voice-pool.json`；实现加载/校验/查询/`poolForLlm`/fallback。
- 验收：63 key/voice_id/speed/note 快照通过；提示词 SHA-256 与 fixture 一致；旁白不在 LLM 池。
- 估时：1–2h。

#### P1-03 TextDecoder
- 依赖：P1-01。
- 文件：`core/ingest/TextDecoder.kt`。
- 步骤：BOM → UTF-8 严格 → ICU CharsetDetector → GB18030 → Big5；CJK 占比；手动编码回退；64MB 上限。
- 验收：UTF-8/BOM/GBK/UTF-16/Big5/非法字节测试通过；不抛未捕获异常。
- 估时：2h。

#### P1-04 ChapterSplitter
- 依赖：P1-01、P1-03。
- 文件：`core/ingest/ChapterSplitter.kt`。
- 步骤：正则（Unicode/IGNORE_CASE）；`<50` 标题；假标题；无标题单章；`idx` 连续。
- 验收：fixture sample + 6 个边界用例通过；与 Python 输出一致。
- 估时：1–2h。

#### P1-05 ScriptSanitizer
- 依赖：P1-01。
- 文件：`core/script/ScriptSanitizer.kt`。
- 步骤：逐函数迁移 §8.4；三层引导语剥离；混合段拆分；去重；合并；`splitLong`；`sanitize`。
- 验收：fixture `strip_speech_tail`/`split_long` 全过；Python 20+ 用例全过；20k 字符 <200ms。
- 估时：1–2 天（最高风险）。

#### P1-06 CoverageChecker
- 依赖：P1-05。
- 文件：`core/util/Diff.kt`、`core/script/CoverageChecker.kt`。
- 步骤：归一化；diff；设计内丢失过滤；覆盖率/缺失；修复列表。
- 验收：fixture coverage 误差 ≤0.001；Python 用例通过。
- 估时：4–6h。

#### P1-07 CharacterRegistry
- 依赖：P1-01、P1-02。
- 文件：`core/script/CharacterRegistry.kt`。
- 步骤：新角色/复用/alias_of/excluded/音色锁定/兜底；事务由 data 层注入接口。
- 验收：5 个场景测试通过；音色不被覆盖。
- 估时：2–3h。

#### P1-08 VoicePool 完整
- 依赖：P1-02。
- 文件：`core/voice/VoicePool.kt`。
- 步骤：63 条解析；`valid/voiceIdFor/speedFor/poolForLlm/fallback`；缓存。
- 验收：与 fixture 全量一致；测试锁定。
- 估时：1h。

#### P1-09 设置模型与校验
- 依赖：P1-01。
- 文件：`core/settings/AppSettings.kt`、`SettingsValidator.kt`。
- 步骤：§7.4 全部 key/default/range；`validate` 返回字段错误；`effectiveVoiceParams`。
- 验收：边界/非法值测试；默认值与 Web 一致（除 Android 并发 4）。
- 估时：2h。

#### P1-10 TTS 缓存键
- 依赖：P1-01。
- 文件：`core/tts/TtsCacheKey.kt`。
- 步骤：v2 规范；浮点格式化；SHA-1 前 12；测试向量。
- 验收：稳定性/敏感性/模型变化；与 fixture Python 键**不要求相同**（v2），但同输入稳定。
- 估时：1h。

#### P1-11 错误模型
- 依赖：P1-01。
- 文件：`core/model/PipelineError.kt`。
- 步骤：sealed error + code + retryable + userMessage；映射表 §9.5。
- 验收：单元测试；UI 可读。
- 估时：1h。

#### P1-12 Golden fixtures 与测试脚手架
- 依赖：P1-02、P1-05、P1-06。
- 文件：`app/src/test/resources/golden/*`、`TestFixtures.kt`。
- 步骤：复制 fixture；写参数化测试；50 条真实章节快照（脱敏）。
- 验收：全部通过；测试不依赖网络/Android。
- 估时：3–4h。

#### P1-13 性能基准
- 依赖：P1-05、P1-06。
- 文件：`app/src/test/.../SanitizerBenchmarkTest.kt`（JMH 可选；JUnit 计时也可）。
- 步骤：20k 字符、100 段清洗/覆盖率；记录耗时。
- 验收：清洗 <200ms；覆盖率 <500ms（中端机目标，JVM 参考）。
- 估时：1–2h。

#### P1-14 文档与进度
- 依赖：P1-01–P1-13。
- 步骤：更新 progress/decisions；记录与 Python 的差异（v2 缓存键、错误码）。
- 验收：后来者能看懂。
- 估时：0.5h。

#### P1-15 Phase 1 门禁
- 依赖：P1-01–P1-14。
- 步骤：`./gradlew testDebugUnitTest`；CI 绿；检查覆盖率。
- 验收：核心域测试全绿；无 TODO；无硬编码 Key。
- 估时：0.5h。

---

### Phase 2 — 数据/网络/音频（4–6 天）

#### P2-01 Room 实体/DAO/DB
- 依赖：P1-01。
- 文件：`data/db/*`。
- 步骤：§7.1–§7.3；schema 导出；外键/WAL；聚合查询；brief 投影。
- 验收：Robolectric/instrumented DAO 测试；事务回滚；外键级联；`app/schemas/1.json`。
- 估时：1 天。

#### P2-02 仓库层 + AppContainer
- 依赖：P2-01。
- 文件：`data/repository/*`、`app/AppContainer.kt`、`app/AppViewModelFactory.kt`。
- 步骤：项目/章节/角色/片段/任务仓库；Flow；事务；批量更新；字符计数维护；建立手写 DI 容器（懒加载 DB/仓库/客户端/用例）与 ViewModel 工厂。
- 验收：仓库测试；UI 不直接碰 DAO；ViewModel 可通过工厂拿到用例。
- 估时：1 天。

#### P2-03 DataStore 设置
- 依赖：P1-09、P2-02。
- 文件：`core/settings/SettingsRepository.kt`。
- 步骤：Preferences DataStore；Flow；update/validate；默认值；迁移占位。
- 验收：读写/默认/范围测试；进程重启保持。
- 估时：3–4h。

#### P2-04 SecureKeyStore
- 依赖：P2-03。
- 文件：`core/settings/SecureKeyStore.kt`。
- 步骤：Keystore AES-GCM；DataStore 密文；set/get/clear；错误不崩。
- 验收：instrumented 测试；日志无明文。
- 估时：3–4h。

#### P2-05 AudioStorage / ProjectFiles
- 依赖：P2-01。
- 文件：`core/storage/AudioStorage.kt`、`ProjectFiles.kt`。
- 步骤：相对路径；resolve；创建目录；原子写；空间预检；删除；导出路径。
- 验收：路径/删除/空间测试；无绝对路径入库。
- 估时：3–4h。

#### P2-06 ImportNovelUseCase
- 依赖：P1-03、P1-04、P2-02、P2-05。
- 文件：`domain/usecase/ImportNovelUseCase.kt`。
- 步骤：SAF URI → 复制 → 解码 → 切章 → 重名 → 事务插入 → 返回。
- 验收：6 个导入用例；重名不覆盖；失败回滚。
- 估时：4–6h。

#### P2-07 LlmClient
- 依赖：P1-11、P2-03、P2-04。
- 文件：`core/network/LlmClient.kt`、`LlmModels.kt`。
- 步骤：OkHttp；请求/响应；超时；重试；finish_reason；response_format 回退；错误映射。
- 验收：MockWebServer 7 个场景；不重试 4xx；截断异常。
- 估时：1 天。

#### P2-08 FishTtsClient
- 依赖：P1-10、P2-03、P2-04。
- 文件：`core/tts/FishTtsClient.kt`。
- 步骤：请求；二进制；错误；重试；超时；`prosody.speed`。
- 验收：MockWebServer 6 个场景；401/402 不重试；429 退避。
- 估时：4–6h。

#### P2-09 TtsSynthesizer + 后处理
- 依赖：P2-05、P2-08、P0-07。
- 文件：`core/tts/TtsSynthesizer.kt`、`AudioPostProcessor.kt`。
- 步骤：缓存命中；临时文件；post-process；原子替换；`.hash`；取消。
- 验收：缓存/失败保留/取消测试；真机短句。
- 估时：1 天。

#### P2-10 ChapterStitcher + Silence
- 依赖：P2-05、P0-07。
- 文件：`core/audio/ChapterStitcher.kt`、`SilenceGenerator.kt`。
- 步骤：静音缓存；concat list；直接 AAC + WAV 回退；duration；清理临时文件。
- 验收：3 段拼接可播放；顺序/停顿；空列表报错；真机。
- 估时：1 天。

#### P2-11 AnalyzeChapterUseCase
- 依赖：P1-05、P1-06、P1-07、P2-02、P2-07。
- 文件：`domain/pipeline/AnalyzeChapterUseCase.kt`。
- 步骤：§8.7；Pass0 可选；重试修复；告警；落库；事务。
- 验收：fake LLM 覆盖非法 JSON/截断/未知/低覆盖率；真机 1 章。
- 估时：1 天。

#### P2-12 RenderChapterUseCase
- 依赖：P2-09、P2-10、P2-02。
- 文件：`domain/pipeline/RenderChapterUseCase.kt`。
- 步骤：§8.9；工作池；缓存；停止；错误；拼接；meta。
- 验收：fake TTS/ffmpeg；缓存/停止/空章节；真机 3 段。
- 估时：1 天。

#### P2-13 角色注册接线
- 依赖：P1-07、P2-02。
- 文件：`domain/pipeline/CharacterRegistryAdapter.kt`。
- 步骤：DB 事务；别名合并；音色锁定；excluded。
- 验收：真机解析一章后角色/音色正确；重跑不变。
- 估时：3–4h。

#### P2-14 Media3 播放
- 依赖：P2-05。
- 文件：`playback/*`、`feature/player/*`（最小）。
- 步骤：ExoPlayer；MediaSessionService；通知；音频焦点；播放本地 m4a。
- 验收：真机播放；后台/锁屏；通知控制。
- 估时：1 天。

#### P2-15 导出/分享
- 依赖：P2-05、P2-14。
- 文件：`core/storage/ExportManager.kt`。
- 步骤：MediaStore/FileProvider；文件名清洗；分享。
- 验收：真机导出到 Music/AudioForge；可分享；API 26–28 回退。
- 估时：4h。

#### P2-16 错误映射/UI 文案
- 依赖：P1-11、P2-07、P2-08。
- 文件：`core/model/ErrorMapper.kt`。
- 步骤：HTTP/IO/FFmpeg/DB → 用户文案；重试按钮。
- 验收：每个错误码有测试/文案。
- 估时：2h。

#### P2-17 端到端 fake 测试
- 依赖：P2-06、P2-11、P2-12。
- 文件：`app/src/test/.../PipelineE2ETest.kt`。
- 步骤：内存 DB + MockWebServer + fake TTS/ffmpeg；导入→解析→合成→拼接（fake file）。
- 验收：完整链路通过；断点续传。
- 估时：4–6h。

#### P2-18 真实 API 手动 smoke
- 依赖：P2-06–P2-12。
- 步骤：用户提供 Key；真机导入 1 章；解析/合成/拼接/播放；记录耗时/错误。
- 验收：真实链路成功；无 Key 泄露；费用可控。
- 估时：1–2h。

#### P2-19 长章节守卫
- 依赖：P2-11。
- 文件：`AnalyzeChapterUseCase`、UI。
- 步骤：>12000 字提示；截断错误；不静默。
- 验收：超长章节有明确 UI；不崩溃。
- 估时：2h。

#### P2-20 Phase 2 门禁
- 依赖：P2-01–P2-19。
- 步骤：真机完成 1 章全链路；CI 绿；文档更新。
- 验收：音频可播放/导出；缓存/断点；无 Crash。
- 估时：0.5h。

---

### Phase 3 — 后台任务（3–5 天）

#### P3-01 JobEntity/DAO/仓库
- 依赖：P2-01。
- 文件：`data/db/JobEntity.kt`、`JobDao.kt`、`JobRepository.kt`。
- 步骤：§7.1 jobs；active/last；stats；stopRequested。
- 验收：DAO 测试；状态机。
- 估时：4h。

#### P3-02 GenerationWorker
- 依赖：P2-11、P2-12、P3-01。
- 文件：`domain/jobs/GenerationWorker.kt`。
- 步骤：CoroutineWorker；foreground；逐章；错误分类；Result.retry/failure。
- 验收：WorkManager TestDriver 成功/失败/重试。
- 估时：1 天。

#### P3-03 前台通知
- 依赖：P3-02、P0-03。
- 文件：`domain/jobs/GenerationNotifications.kt`。
- 步骤：3 渠道；进度；停止 action；POST_NOTIFICATIONS。
- 验收：真机通知可见/可停止；渠道设置。
- 估时：4h。

#### P3-04 BatchRunner / 队列
- 依赖：P3-01、P3-02。
- 文件：`domain/jobs/BatchRunner.kt`、`JobQueue.kt`。
- 步骤：start/stop；唯一 work；单活跃；默认 5/上限 20；参数校验。
- 验收：重复 start 不并行；队列；停止。
- 估时：1 天。

#### P3-05 Sweep / 停止 / 收尾
- 依赖：P3-04。
- 文件：`domain/jobs/SweepRunning.kt`。
- 步骤：App 启动/任务结束把 running→pending；取消 await；错误汇总。
- 验收：杀进程/停止后无假 running；job 状态正确。
- 估时：4h。

#### P3-06 约束/电量/温度
- 依赖：P3-04。
- 文件：`domain/jobs/JobConstraints.kt`。
- 步骤：网络/充电/Wi-Fi；电量阈值；热状态；降并发；提示。
- 验收：真机断网/低电/省电；行为符合设置。
- 估时：4h。

#### P3-07 6 小时看门狗
- 依赖：P3-04。
- 文件：`domain/jobs/JobWatchdog.kt`。
- 步骤：记录开始；>5h 收尾；提示“下一批”；处理 isStopped。
- 验收：模拟长任务（缩短阈值）能收尾；文档说明。
- 估时：3h。

#### P3-08 进程死亡/重启恢复
- 依赖：P3-05。
- 步骤：杀进程/重启/WorkManager reschedule；App 启动 sweep；job 历史。
- 验收：真机恢复；不重复合成已完成段。
- 估时：4h。

#### P3-09 后台测试
- 依赖：P3-02–P3-08。
- 文件：`app/src/test/.../GenerationWorkerTest.kt`。
- 步骤：TestDriver + fake pipeline；成功/重试/停止/sweep。
- 验收：全绿；CI 可跑（不依赖设备）。
- 估时：4h。

#### P3-10 真机后台 smoke
- 依赖：P3-02–P3-08。
- 步骤：锁屏 30min、杀进程、重启、断网；Honor/EMUI。
- 验收：任务继续/可恢复；通知；无 ANR。
- 估时：2–4h。

#### P3-11 存储清理
- 依赖：P2-05。
- 文件：`core/storage/StorageMaintenance.kt`。
- 步骤：孤儿扫描；项目删除；保留策略；统计。
- 验收：不误删引用文件；释放量正确。
- 估时：4h。

#### P3-12 Phase 3 门禁
- 依赖：P3-01–P3-11。
- 验收：5 章批次端到端；后台/恢复；CI 绿。
- 估时：0.5h。

---

### Phase 4 — 主 UI（5–7 天）

#### P4-01 导航与自适应壳
- 依赖：P0-04。
- 文件：`ui/navigation/*`、`MainActivity.kt`。
- 步骤：类型安全路由；NavigationSuiteScaffold；底栏/rail；迷你播放器占位。
- 验收：三栏切换；返回栈；深链。
- 估时：4h。

#### P4-02 设计系统组件
- 依赖：P0-04。
- 文件：`ui/theme/*`、`ui/components/*`。
- 步骤：§12.2/§12.5；StatusPill/RoleAvatar/ProgressRing/Empty/Error/Section。
- 验收：Preview；深色；字体 200%。
- 估时：1 天。

#### P4-03 项目库
- 依赖：P2-02、P4-01、P4-02。
- 文件：`feature/projects/*`。
- 步骤：列表/搜索/卡片/长按菜单/FAB；空/加载/错误。
- 验收：2000 章项目卡；导入入口；删除确认。
- 估时：1 天。

#### P4-04 导入 Sheet
- 依赖：P2-06、P4-03。
- 文件：`feature/projects/ImportSheet.kt`。
- 步骤：SAF；编码；预览；重名；进度。
- 验收：UTF-8/GBK/UTF-16；失败提示；不覆盖。
- 估时：1 天。

#### P4-05 项目详情
- 依赖：P2-02、P4-02。
- 文件：`feature/project/ProjectScreen.kt`。
- 步骤：头部/进度/控制卡/告警/人物条；更多菜单。
- 验收：默认 5 章；开始/停止；进度。
- 估时：1–2 天。

#### P4-06 批次 Sheet
- 依赖：P4-05、P3-04。
- 文件：`feature/project/BatchSheet.kt`。
- 步骤：模式/起止/强制/估算/校验。
- 验收：范围/上限；无 Key 跳设置。
- 估时：4h。

#### P4-07 章节列表/筛选/分页
- 依赖：P2-02、P4-05。
- 文件：`feature/project/ChapterList.kt`。
- 步骤：Paging/搜索/状态筛选/快速开始/更多。
- 验收：2000 章滚动；筛选；空态。
- 估时：1 天。

#### P4-08 任务页
- 依赖：P3-01、P4-02。
- 文件：`feature/tasks/*`。
- 步骤：当前/队列/历史；停止；错误。
- 验收：切页/锁屏；停止；重试。
- 估时：4–6h。

#### P4-09 全局设置
- 依赖：P2-03、P2-04。
- 文件：`feature/settings/SettingsScreen.kt`。
- 步骤：§12.4 S11 分组；校验；Key 掩码；测试连接。
- 验收：范围/保存/生效；无明文。
- 估时：1 天。

#### P4-10 Onboarding
- 依赖：P2-03、P2-04。
- 文件：`feature/onboarding/*`。
- 步骤：3 步；Key 测试；通知；电池说明；可跳过。
- 验收：首次引导；无 Key 可浏览；权限解释。
- 估时：4–6h。

#### P4-11 空/加载/错误态
- 依赖：P4-02–P4-10。
- 步骤：每个屏幕补齐；统一 ErrorBanner/Snackbar。
- 验收：无白屏；错误可操作。
- 估时：4h。

#### P4-12 无障碍/字体
- 依赖：P4-02–P4-11。
- 步骤：contentDescription/heading/liveRegion/48dp；字体 200%。
- 验收：TalkBack 主流程；不截断。
- 估时：4h。

#### P4-13 UI 测试
- 依赖：P4-03–P4-10。
- 文件：`app/src/test/.../ui/*`（Robolectric）或 `androidTest`。
- 步骤：空态、导入、开始确认、设置校验。
- 验收：关键路径自动；稳定。
- 估时：1 天。

#### P4-14 性能
- 依赖：P4-03–P4-07。
- 步骤：基准/布局检查/大列表；优化 key/重 composition。
- 验收：2000 章首帧 <500ms；滚动 60fps。
- 估时：4h。

#### P4-15 Phase 4 门禁
- 依赖：P4-01–P4-14。
- 验收：日常操作可用；CI 绿；真机 smoke。
- 估时：0.5h。

---

### Phase 5 — 深度 UI（4–6 天）

#### P5-01 剧本页
- 依赖：P2-11、P4-05。
- 文件：`feature/script/ScriptScreen.kt`。
- 步骤：§12.4 S06；片段卡；告警；播放/编辑/重生成。
- 验收：200 段滚动；播放跟随；无音频禁用。
- 估时：1–2 天。

#### P5-02 片段编辑 Sheet
- 依赖：P5-01、P2-02。
- 文件：`feature/script/SegmentEditSheet.kt`。
- 步骤：角色/情绪/文本；校验；保存；提示。
- 验收：只重合成该段；键盘不遮挡。
- 估时：4–6h。

#### P5-03 播放跟随/连播
- 依赖：P5-01、P2-14。
- 步骤：当前句高亮；自动滚动；队列；暂停。
- 验收：长列表跟随；切页不中断。
- 估时：4h。

#### P5-04 人物音色页
- 依赖：P2-02、P4-02。
- 文件：`feature/voices/VoicesScreen.kt`。
- 步骤：角色卡；试听；音色库；搜索/筛选。
- 验收：63 类；试听缓存；换音色提示。
- 估时：1 天。

#### P5-05 音色选择 Sheet
- 依赖：P5-04。
- 文件：`feature/voices/VoicePickerSheet.kt`。
- 步骤：搜索/档位/试听/选中/保存。
- 验收：选中；保存；不误触。
- 估时：4h。

#### P5-06 项目设置/角色微调
- 依赖：P2-03、P5-04。
- 文件：`feature/settings/ProjectSettingsScreen.kt`。
- 步骤：滑杆/输入/留空/试听/保存。
- 验收：范围；试听；重生成生效。
- 估时：6h。

#### P5-07 播放器页
- 依赖：P2-14。
- 文件：`feature/player/PlayerScreen.kt`。
- 步骤：封面/波形；进度；控制；倍速；睡眠；队列。
- 验收：前后台/锁屏/蓝牙；音频焦点。
- 估时：1 天。

#### P5-08 播放通知/后台
- 依赖：P5-07。
- 步骤：MediaSessionService；通知；耳机；任务移除。
- 验收：后台播放；通知控制；不崩。
- 估时：4–6h。

#### P5-09 存储页
- 依赖：P3-11。
- 文件：`feature/storage/StorageScreen.kt`。
- 步骤：占用/分类/清理/保留策略。
- 验收：释放量；不误删。
- 估时：4h。

#### P5-10 诊断/关于/许可
- 依赖：P2-03。
- 文件：`feature/settings/DiagnosticsScreen.kt`、`AboutScreen.kt`。
- 步骤：版本/设备/错误/日志/FFmpeg 自检/许可/隐私。
- 验收：日志脱敏；导出；许可页。
- 估时：4h。

#### P5-11 导出/分享 UI
- 依赖：P2-15。
- 步骤：章节/整书导出；分享；进度。
- 验收：MediaStore/FileProvider；文件名。
- 估时：3–4h。

#### P5-12 文案/字符串统一
- 依赖：P4/P5 屏幕。
- 步骤：所有硬编码中文迁 `strings.xml`；复数/占位符；英文 fallback（可选）。
- 验收：无硬编码；lint。
- 估时：4h。

#### P5-13 无障碍复查
- 依赖：P5-01–P5-12。
- 步骤：TalkBack/字体/对比度/焦点顺序。
- 验收：主流程可完成。
- 估时：4h。

#### P5-14 UI 测试补充
- 依赖：P5-01–P5-11。
- 步骤：剧本编辑、换音色、播放器、设置。
- 验收：稳定。
- 估时：1 天。

#### P5-15 Phase 5 门禁
- 依赖：P5-01–P5-14。
- 验收：功能对齐 Web（除服务端）；真机 smoke；CI 绿。
- 估时：0.5h。

---

### Phase 6 — 硬化与发布（3–5 天）

#### P6-01 R8/混淆评估
- 依赖：P5-15。
- 文件：`proguard-rules.pro`、`app/build.gradle.kts`。
- 步骤：开 `isMinifyEnabled=true` + `isShrinkResources=true`；跑 release；修 keep 规则；测 FFmpeg/Room/Serialization。
- 验收：release 安装/启动/FFmpeg 正常；体积下降；不崩。
- 估时：1 天。

#### P6-02 ABI split/体积
- 依赖：P6-01。
- 步骤：`splits.abi` 或 universal；生成 arm64 单包；比较体积；保留 universal。
- 验收：arm64 安装；x86_64 模拟器；artifact 命名。
- 估时：4h。

#### P6-03 依赖校验/锁定
- 依赖：P0-02。
- 文件：`gradle/verification-metadata.xml`。
- 步骤：生成校验和；`--write-verification-metadata sha256`；CI 校验。
- 验收：CI 不因校验失败；无动态版本。
- 估时：3–4h。

#### P6-04 网络/重试/并发调优
- 依赖：P2-07、P2-08、P3-06。
- 步骤：真机弱网/429；自适应并发；超时；日志。
- 验收：弱网可恢复；不烧 API。
- 估时：4h。

#### P6-05 设备矩阵/后台
- 依赖：P3-10。
- 步骤：Honor/EMUI、Pixel、MIUI、ColorOS；锁屏/杀进程/重启。
- 验收：记录结果；必要引导。
- 估时：1 天。

#### P6-06 音频 A/B
- 依赖：P2-10。
- 步骤：同片段服务器 vs Android；时长/顺序/响度/音高；主观听感。
- 验收：差异可接受；问题记录。
- 估时：4h。

#### P6-07 性能/内存
- 依赖：P4-14、P5-15。
- 步骤：启动/列表/剧本/拼接；内存；ANR。
- 验收：目标达成或记录。
- 估时：4h。

#### P6-08 安全复查
- 依赖：P5-15。
- 步骤：Key/备份/日志/FileProvider/路径；lint。
- 验收：无高危；清单。
- 估时：3h。

#### P6-09 Release workflow/签名
- 依赖：P0-09、P6-01。
- 文件：`.github/workflows/release.yml`、`scripts/verify-apk.sh`。
- 步骤：secrets；tag；assembleRelease；verify；upload；GitHub Release。
- 验收：tag 产出签名 APK；checksums；release notes。
- 估时：4h。

#### P6-10 发布文档/安装说明
- 依赖：P6-09。
- 文件：`README.md`、`docs/release-notes.md`。
- 步骤：安装/升级/已知问题/回滚/Key 配置。
- 验收：新用户能按文档安装。
- 估时：3h。

#### P6-11 v1.0.0 发布
- 依赖：P6-01–P6-10。
- 步骤：版本号；tag `v1.0.0`；CI；下载 APK 验证；发布 Release。
- 验收：签名/版本/安装/升级；checksum。
- 估时：1h。

#### P6-12 发布后清单
- 依赖：P6-11。
- 步骤：记录已知问题；监控日志；收集反馈；计划 v1.1（长章节分块、服务器导入）。
- 验收：issue 列表；下一版范围。
- 估时：1h。

### v1.1 候选（不在 v1 验收内）

- **长章节分块解析**：按段落边界切 ~6000 字窗口（overlap 200 字）；每块用同一 registry 解析；合并 segments；用全文覆盖率校验；边界去重；`finish_reason=length` 时自动降窗口。需要 10+ 真实长章节回归。
- **服务器数据导入**：从 StoryForge 导出的 ZIP（DB JSON + 音频）导入；复用 `LegacyCacheKey`；只导入项目/章节/角色/设置，音频按文件复制。
- **ABI split / R8 进一步瘦身**：arm64 单包；baseline profile。
- **平板/折叠双栏**：`ListDetailPaneScaffold`；拖拽排序片段。
- **批量导出/分享**：整书 ZIP/逐章导出；后台导出进度。
- **播放增强**：章节队列、睡眠定时渐弱、Android Auto（可选）。

---

## 16. CI/CD 与发布

### 16.1 `.github/workflows/ci.yml`

```yaml
name: Android CI

on:
  push:
    branches: [main]
  pull_request:
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: android-ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 40
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"

      - name: Android SDK
        uses: android-actions/setup-android@v3

      - name: Install SDK 37.0 / Build Tools 37
        run: |
          yes | sdkmanager --licenses >/dev/null
          sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"

      - name: Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Lint + Unit tests + Debug APK
        run: |
          ./gradlew --no-daemon --stacktrace \
            lintDebug testDebugUnitTest assembleDebug

      - name: Verify APK
        run: bash scripts/verify-apk.sh app/build/outputs/apk/debug/app-debug.apk

      - name: Upload debug APK
        uses: actions/upload-artifact@v4
        with:
          name: audioforge-debug-apk
          path: app/build/outputs/apk/debug/*.apk
          if-no-files-found: error
          retention-days: 14
```

### 16.2 `.github/workflows/release.yml`

```yaml
name: Android Release

on:
  push:
    tags:
      - "v*"
  workflow_dispatch:
    inputs:
      version:
        description: "Version name, e.g. 1.0.0"
        required: true
        default: "1.0.0"

permissions:
  contents: write

jobs:
  release:
    runs-on: ubuntu-latest
    timeout-minutes: 40
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"

      - uses: android-actions/setup-android@v3
      - run: |
          yes | sdkmanager --licenses >/dev/null
          sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"

      - uses: gradle/actions/setup-gradle@v4

      - name: Decode keystore
        env:
          KEYSTORE_BASE64: ${{ secrets.ANDROID_KEYSTORE_BASE64 }}
        run: |
          if [ -z "$KEYSTORE_BASE64" ]; then
            echo "::error::ANDROID_KEYSTORE_BASE64 未配置；release 必须签名"
            exit 1
          fi
          echo "$KEYSTORE_BASE64" | base64 --decode > "$RUNNER_TEMP/release.jks"
          chmod 600 "$RUNNER_TEMP/release.jks"
          echo "ANDROID_KEYSTORE_PATH=$RUNNER_TEMP/release.jks" >> "$GITHUB_ENV"

      - name: Build signed release
        env:
          ANDROID_KEYSTORE_PASSWORD: ${{ secrets.ANDROID_KEYSTORE_PASSWORD }}
          ANDROID_KEY_ALIAS: ${{ secrets.ANDROID_KEY_ALIAS }}
          ANDROID_KEY_PASSWORD: ${{ secrets.ANDROID_KEY_PASSWORD }}
        run: |
          VERSION="${{ github.event.inputs.version || github.ref_name }}"
          VERSION="${VERSION#v}"
          ./gradlew --no-daemon --stacktrace \
            -PversionName="$VERSION" \
            -PversionCode="${{ github.run_number }}" \
            assembleRelease

      - name: Verify signed APK
        run: bash scripts/verify-apk.sh app/build/outputs/apk/release/app-release.apk

      - name: Rename + checksum
        run: |
          mkdir -p release
          VERSION="${{ github.event.inputs.version || github.ref_name }}"
          VERSION="${VERSION#v}"
          cp app/build/outputs/apk/release/app-release.apk "release/audioforge-${VERSION}.apk"
          (cd release && sha256sum *.apk > checksums.txt)
          cat release/checksums.txt

      - name: Upload artifact
        uses: actions/upload-artifact@v4
        with:
          name: audioforge-release-apk
          path: release/*
          if-no-files-found: error
          retention-days: 30

      - name: GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          files: release/*
          generate_release_notes: true
          fail_on_unmatched_files: true
```

### 16.3 `scripts/verify-apk.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

APK="${1:?usage: verify-apk.sh <apk>}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?ANDROID_SDK_ROOT/ANDROID_HOME not set}}"
BT="$SDK/build-tools/37.0.0"

test -f "$APK"

echo "== badging =="
"$BT/aapt2" dump badging "$APK" | sed -n '1,20p'

echo "== zipalign 16KB =="
"$BT/zipalign" -c -P 16 4 "$APK"

echo "== signature =="
"$BT/apksigner" verify --verbose "$APK"

echo "== native libs =="
unzip -l "$APK" | grep 'lib/' | awk '{print $4}' | cut -d/ -f2 | sort -u

echo "== size =="
du -h "$APK"
```

### 16.4 签名密钥（一次性，仓库外执行）

```bash
keytool -genkeypair \
  -keystore ~/audioforge-release.jks \
  -alias audioforge \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass '<强密码>' -keypass '<强密码>' \
  -dname "CN=AudioForge, OU=Mobile, O=EstimateFaster, L=Shanghai, ST=Shanghai, C=CN"

base64 -w0 ~/audioforge-release.jks > ~/audioforge-release.jks.b64
```

GitHub Secrets：

- `ANDROID_KEYSTORE_BASE64` = `.b64` 内容
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

> 备份 keystore 与密码；丢失后无法覆盖升级。不要提交到仓库/聊天/日志。

### 16.5 版本规则

- `versionCode` = GitHub `run_number`（只增不减）；本地默认 1。
- `versionName` = tag `v1.0.0` → `1.0.0`。
- APK 命名：`audioforge-<version>.apk`。
- 每次 release 附 `checksums.txt`。
- 同一签名才能覆盖安装；换签名必须卸载旧版。
- 发布前手工验证：安装、升级、启动、导入、1 章生成、播放、导出。

### 16.6 Release Notes 模板

```markdown
## AudioForge vX.Y.Z

### 新增
- ...

### 修复
- ...

### 已知问题
- 长章节（>12000 字）可能输出截断，v1.1 分块解析
- 32 位设备不支持（ffmpeg ABI 限制）

### 安装
下载 `audioforge-X.Y.Z.apk`，允许“安装未知来源应用”。
升级需使用同一签名；首次使用请在设置中填入 LLM / Fish Key。
```

---

## 17. DSH 执行规约

### 17.1 工作方式

- 分支：`feat/p{phase}-{taskId}-{slug}`，例如 `feat/p2-07-llm-client`。
- 提交：Conventional Commits，中文描述可以：
  - `feat(script): port sanitizer and golden tests`
  - `fix(jobs): sweep running rows after cancel`
  - `test(tts): add cache key vectors`
- 每个任务一个 PR 或至少一个独立提交；不要“大爆炸”提交。
- 每天结束：CI 绿、`docs/progress.md` 更新、无未提交实验代码。
- 不提交：`build/`、`.gradle/`、`local.properties`、`*.jks`、`*.keystore`、API Key、日志、APK。
- 不改：`docs/fixtures/*`、上游提示词/音色池（除非任务明确要求且有校验和更新）。

### 17.2 任务开始模板（写到 `docs/progress.md`）

```markdown
### P2-07 LLM Client — in_progress
- 开始：2026-11-08 14:00
- 分支：feat/p2-07-llm-client
- 目标：...
- 依赖：P1-11, P2-03, P2-04
- 验收：...
```

### 17.3 任务完成模板

```markdown
### P2-07 LLM Client — done
- 完成：2026-11-08 18:30
- 提交：abc1234
- 验证：`./gradlew testDebugUnitTest` 绿；MockWebServer 7 场景
- 遗留：无 / 见 #issue
```

### 17.4 阻塞模板

```markdown
### BLOCKED P2-09 TtsSynthesizer
- 现象：...
- 复现：命令/步骤
- 日志：...（脱敏）
- 已尝试：...
- 怀疑：...
- 需要：...
```

### 17.5 Definition of Done（每个任务）

- [ ] 代码/资源已提交，遵循包边界。
- [ ] 新增/修改逻辑有测试；测试全绿。
- [ ] `lintDebug` 无 error。
- [ ] 无密钥/大文件/构建产物入库。
- [ ] 用户可见文案在 `strings.xml`。
- [ ] 错误路径有可读提示/重试。
- [ ] `docs/progress.md` 更新。
- [ ] 涉及 UI 有 `@Preview`；涉及后台/音频有真机 smoke 记录。

### 17.6 禁止事项

- 禁止把 `android.disallowKotlinSourceSets=false` 当成“随便加”的开关；它只用于 KSP sourceSets，不要用它掩盖其他构建问题。
- 禁止升级 Kotlin/AGP/Compose/material3 而不跑 V1–V8。
- 禁止用 `runBlocking` 在主线程做 DB/网络/ffmpeg。
- 禁止把绝对路径写 DB；禁止把用户标题拼进文件路径。
- 禁止在日志/崩溃上报里放原文、Key、完整响应。
- 禁止在 CI 里使用真实 Key 或真实付费 API。
- 禁止用 debug 签名冒充 release；禁止跳过 `apksigner`/`zipalign`。
- 禁止为了“先跑起来”删除错误处理/告警/覆盖率校验。

### 17.7 升级/回退决策

遇到以下情况，先写 `docs/decisions.md`，再改：

- KSP 升级到 2.3.x 并移除 sourceSets 开关；
- Kotlin 升级到 2.4.x / 关闭内置 Kotlin；
- material3 1.5.0 稳定版替换 rc；
- ffmpeg-kit fork 版本/换 Media3 方案；
- minSdk/ABI/targetSdk 变更；
- 单模块拆多模块 / 引入 Hilt。

---

## 18. 附录

### A. 提示词资产

来源：`docs/fixtures/porting-vectors.json` 的 `prompts` 字段。

| 文件 | 字段 | 字符数 | SHA-256 |
|---|---|---|---|
| `app/src/main/assets/prompts/script.txt` | `PROMPT` | 1882 | `38a24cc1f93524b020b8e207a31b107051b9f8d54b4ede4838a0634622baa9cb` |
| `app/src/main/assets/prompts/pass0.txt` | `PASS0_PROMPT` | 476 | `30aca6d2eae1e9d1a718929a9f84081a9aab2d39cdfa716ccb4ab48b93e2291f` |
| `app/src/main/assets/prompts/repair.txt` | `REPAIR_HINT` | 214 | `98b78e5aae07aef99e699c0d0a2a3cbe2893bb6df55109b02c71dc6d3044ac34` |

提取命令（在仓库根执行）：

```bash
python3 - <<'PY'
import json, pathlib
d = json.load(open('docs/fixtures/porting-vectors.json', encoding='utf-8'))
out = pathlib.Path('app/src/main/assets/prompts')
out.mkdir(parents=True, exist_ok=True)
(out/'script.txt').write_text(d['prompts']['PROMPT'], encoding='utf-8')
(out/'pass0.txt').write_text(d['prompts']['PASS0_PROMPT'], encoding='utf-8')
(out/'repair.txt').write_text(d['prompts']['REPAIR_HINT'], encoding='utf-8')
print('ok')
PY
```

### B. 音色池

- 文件：`docs/fixtures/voice-pool.json`（63 条，含 `voice_id/label/note/speed`）。
- 复制到 `app/src/main/assets/voice_pool.json`。
- 测试必须锁定：
  - 63 个 key 集合；
  - 每个 key 的 `voice_id`；
  - `male_brother.speed = 1.15`；
  - 8 个带 `note` 的 key；
  - `narrator_male` 不在 `poolForLlm()`。
- 类别 id 是契约，**永不改名**。

### C. 黄金测试向量

- `docs/fixtures/porting-vectors.json`：
  - `ingest.sample_novel.chapters`；
  - `tts.emotion_prefix` / `cache_key_vectors` / `tts_text_vectors`；
  - `sanitizer.strip_speech_tail` / `split_long`；
  - `coverage`；
  - `voices` 摘要；
  - `prompts` 全文。
- 复制到 `app/src/test/resources/fixtures/porting-vectors.json`。
- 测试逐条断言；不允许“为了让测试过而改期望”。

### D. API → 用例映射

| Web API | Android 用例 | 说明 |
|---|---|---|
| `GET /api/projects` | `ObserveProjectsUseCase` | Room 聚合 |
| `POST /api/projects` | `ImportNovelUseCase` | SAF |
| `PATCH /api/projects/{id}` | `RenameProjectUseCase` | 唯一标题 |
| `DELETE /api/projects/{id}` | `DeleteProjectUseCase` | DB+文件 |
| `GET /api/status` | `ObserveJobsUseCase` | Room/WorkManager |
| `GET /api/projects/{id}/progress` | `ObserveProjectProgressUseCase` | Flow |
| `GET /api/projects/{id}/chapters` | `ObserveChaptersUseCase` | Paging/Flow |
| `POST /api/projects/{id}/start` | `StartBatchUseCase` | Worker |
| `POST /api/projects/{id}/stop` | `StopBatchUseCase` | cancel |
| `GET /api/chapters/{id}` | `GetChapterUseCase` | 含 segments |
| `POST /api/chapters/{id}/parse` | `AnalyzeChapterUseCase` | force |
| `POST /api/chapters/{id}/render` | `RenderChapterUseCase` | |
| `PATCH /api/segments/{id}` | `UpdateSegmentUseCase` | 校验 |
| `POST /api/segments/{id}/regen` | `RegenerateSegmentUseCase` | |
| `GET /api/projects/{id}/characters` | `ObserveCharactersUseCase` | |
| `PATCH /api/characters/{id}` | `ChangeVoiceUseCase` | 重置相关段 |
| `GET /api/voice-pool` | `VoicePool` | assets |
| `GET/PUT /api/settings` | `SettingsRepository` | DataStore |
| `PUT /api/projects/{id}/char-tunings` | `SaveCharacterTuningUseCase` | Room |
| `POST /api/settings/preview` | `PreviewVoiceUseCase` | TTS 缓存 |
| `DELETE /api/audio/orphan` | `CleanOrphanAudioUseCase` | 存储页 |
| `/audio/*` | `AudioStorage` + Media3 | 本地文件 |

### E. 数据库 DDL（等价 Room）

```sql
CREATE TABLE novels (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  title TEXT NOT NULL UNIQUE,
  sourceName TEXT NOT NULL,
  sourceRelPath TEXT NOT NULL,
  charCount INTEGER NOT NULL,
  createdAt INTEGER NOT NULL,
  lastOpenedAt INTEGER
);
CREATE TABLE chapters (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  novelId INTEGER NOT NULL REFERENCES novels(id) ON DELETE CASCADE,
  idx INTEGER NOT NULL,
  title TEXT NOT NULL,
  rawText TEXT NOT NULL,
  parseStatus TEXT NOT NULL DEFAULT 'pending',
  parseError TEXT,
  audioStatus TEXT NOT NULL DEFAULT 'pending',
  audioError TEXT,
  parseWarn TEXT,
  segCount INTEGER NOT NULL DEFAULT 0,
  segDone INTEGER NOT NULL DEFAULT 0,
  updatedAt INTEGER NOT NULL,
  UNIQUE(novelId, idx)
);
CREATE INDEX idx_chapters_novel_audio ON chapters(novelId, audioStatus);
CREATE INDEX idx_chapters_novel_parse ON chapters(novelId, parseStatus);
CREATE TABLE characters (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  novelId INTEGER NOT NULL REFERENCES novels(id) ON DELETE CASCADE,
  roleId TEXT NOT NULL,
  name TEXT NOT NULL,
  gender TEXT NOT NULL DEFAULT 'unknown',
  importance TEXT NOT NULL DEFAULT 'supporting',
  aliasesJson TEXT NOT NULL DEFAULT '[]',
  note TEXT NOT NULL DEFAULT '',
  category TEXT NOT NULL DEFAULT '',
  voiceId TEXT NOT NULL DEFAULT '',
  speed REAL,
  locked INTEGER NOT NULL DEFAULT 1,
  UNIQUE(novelId, roleId)
);
CREATE INDEX idx_characters_novel_name ON characters(novelId, name);
CREATE TABLE segments (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  chapterId INTEGER NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
  seq INTEGER NOT NULL,
  type TEXT NOT NULL,
  roleId TEXT NOT NULL,
  emotion TEXT NOT NULL,
  text TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',
  audioRelPath TEXT,
  durationMs INTEGER,
  errorMsg TEXT,
  ttsKey TEXT,
  updatedAt INTEGER NOT NULL,
  UNIQUE(chapterId, seq)
);
CREATE INDEX idx_segments_role ON segments(roleId);
CREATE INDEX idx_segments_status ON segments(status);
CREATE INDEX idx_segments_chapter_status ON segments(chapterId, status);
CREATE TABLE character_tuning (
  novelId INTEGER NOT NULL REFERENCES novels(id) ON DELETE CASCADE,
  roleId TEXT NOT NULL,
  key TEXT NOT NULL,
  value TEXT NOT NULL,
  PRIMARY KEY(novelId, roleId, key)
);
CREATE TABLE novel_meta (
  novelId INTEGER PRIMARY KEY REFERENCES novels(id) ON DELETE CASCADE,
  lastGeneratedChapter INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE jobs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  novelId INTEGER NOT NULL REFERENCES novels(id) ON DELETE CASCADE,
  mode TEXT NOT NULL,
  status TEXT NOT NULL,
  requestedIdsJson TEXT NOT NULL,
  currentChapterId INTEGER,
  currentTitle TEXT NOT NULL DEFAULT '',
  startedAt INTEGER NOT NULL,
  finishedAt INTEGER,
  lastError TEXT,
  statsJson TEXT NOT NULL DEFAULT '{}',
  stopRequested INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_jobs_novel_status ON jobs(novelId, status);
```

### F. FFmpeg 命令速查

```bash
# 变调（半音）+ 音量
ffmpeg -y -i in.mp3 \
  -af "asetrate=44100*2^(p/12),aresample=44100,atempo=2^(-p/12),volume=v" \
  -b:a 64k out.mp3

# 静音
ffmpeg -y -f lavfi -i anullsrc=r=44100:cl=mono -t 0.35 -b:a 64k sil350.mp3
ffmpeg -y -f lavfi -i anullsrc=r=44100:cl=mono -t 0.70 -b:a 64k sil700.mp3

# 直接 AAC
ffmpeg -y -f concat -safe 0 -i concat.txt \
  -ar 44100 -ac 1 -c:a aac -b:a 96k -movflags +faststart chapter.m4a

# 回退 WAV
ffmpeg -y -f concat -safe 0 -i concat.txt -ar 44100 -ac 1 -c:a pcm_s16le _chapter.wav
ffmpeg -y -i _chapter.wav -c:a aac -b:a 96k -movflags +faststart chapter.m4a

# 自检
ffmpeg -version
```

### G. 关键常量

```kotlin
const val NARRATOR_ROLE_ID = "narrator"
const val UNKNOWN_ROLE_ID = "unknown"
const val NARRATOR_CATEGORY = "narrator_male"
const val DEFAULT_SEGMENT_MAX_CHARS = 120
const val DEFAULT_MIN_COVERAGE = 0.97
const val DEFAULT_NARRATOR_SILENCE_MS = 350
const val DEFAULT_PARAGRAPH_SILENCE_MS = 700
const val DEFAULT_BATCH_SIZE = 5
const val MAX_BATCH_SIZE = 20
const val MAX_SINGLE_PASS_CHARS = 12_000
const val MAX_SEGMENT_CHARS = 2_000
const val IMPORT_MAX_BYTES = 64L * 1024 * 1024
const val AUDIO_SAMPLE_RATE = 44_100
const val AUDIO_CHANNELS = 1
const val AAC_BITRATE = "96k"
const val MP3_BITRATE = 64
```

### H. 常见问题/回退

| 现象 | 原因 | 处理 |
|---|---|---|
| `kotlin.sourceSets` 报错 | KSP + AGP 9 内置 Kotlin | 保留 `android.disallowKotlinSourceSets=false` |
| metadata 2.4.0 报错 | serialization 1.12.0 | 降 1.9.0 |
| 找不到 `platforms;android-37` | 包名错误 | 用 `platforms;android-37.0` |
| Expressive API 未 opt-in | 实验 API | `@OptIn(ExperimentalMaterial3ExpressiveApi::class)` |
| `apksigner` 报 `java not found` | PATH 缺 JDK | 设 `JAVA_HOME`/PATH |
| 本地 AGP 写 `/root/.android` 失败 | home 不可写 | 设 `ANDROID_USER_HOME`/`-Duser.home` |
| release 未签名 | 无 keystore secrets | 配 4 个 secrets；不要用 debug 签名 |
| APK 太大 | 双 ABI/未混淆 | ABI split；P6 再评估 R8 |
| 32 位设备装不上 | ffmpeg AAR 无 v7a | 明确不支持；64 位设备 |
| 后台任务被杀 | OEM/电耗 | 电池白名单引导；缩短批次；前台通知 |
| 6h 上限 | Android 15+ | 5 章/批；watchdog；下一批 |
| FFmpeg 拼接失败 | 路径/编码/格式 | 检查 concat 路径、采样率、回退 WAV |
| 音频无声/错位 | 顺序/静音/缓存 | 检查 files/types 对齐；清理缓存重跑 |

### I. 参考

- [可行性报告](android-port-feasibility.md)
- [上游 StoryForge](https://github.com/EstimateFaster/storyforge)
- [Compose Material3 releases](https://developer.android.com/jetpack/androidx/releases/compose-material3)
- [AGP 9.5 releases](https://developer.android.com/build/releases/gradle-plugin)
- [Built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [FGS timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout)
- [FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [WorkManager long-running](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)
- [ffmpeg-kit-maintained](https://github.com/ffmpegkit-maintained/ffmpeg)
- [Fish Audio SDK](https://pypi.org/project/fish-audio-sdk/)

---

## 19. 交付清单（最终检查）

- [ ] `./gradlew lintDebug testDebugUnitTest assembleDebug` 绿。
- [ ] `./gradlew assembleRelease` 由 CI 产出签名 APK。
- [ ] `zipalign -c -P 16`、`apksigner verify` 通过。
- [ ] 真机：安装、启动、导入、解析 5 章、合成、拼接、播放、导出。
- [ ] 锁屏/杀进程/重启/断网恢复。
- [ ] 2000 章项目列表性能。
- [ ] 63 音色/角色锁定/换音色只重生成相关片段。
- [ ] 设置 Key 加密、日志脱敏、备份排除。
- [ ] 无障碍主流程/TalkBack/字体 200%。
- [ ] 许可页（MIT/LGPL/第三方）、隐私说明。
- [ ] `docs/progress.md` 完整；`docs/decisions.md` 无未决项。
- [ ] Release notes/checksums/安装说明。
- [ ] 无密钥/构建产物/大文件入库。

---

**计划结束。** 如果本计划与代码现状冲突，以 `docs/decisions.md` 记录的原因为准；不要静默偏离。
