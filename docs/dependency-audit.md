# 依赖审计与锁定建议（P6-03 依赖校验/锁定）

> 状态：**审计完成；未改动任何版本、构建脚本或 CI**（本文是唯一产物）
> 基线：`main@bb7f41a`；分支 `docs/p6-03-deps`
> 对照：执行计划 §3.1（版本矩阵）、§3.3（实编坑）、§14.1/§14.6（测试分层/验收门）、§15 P6-03；`docs/security-review.md`（SEC-01 Actions pinning、SEC-11 依赖校验）
> 方法：静态读取仓库文件 + 上游 POM / Gradle Module 元数据交叉核对。**未执行任何 Gradle 任务**（本项目硬约束：禁本地构建/CI），因此下文凡涉及「解析后的依赖图」的结论都标注了需要 CI 证据的位置。

---

## 0. 结论（TL;DR）

1. **版本矩阵 9/9 全部一致，0 项版本偏差**：AGP 9.5.1、Gradle 9.9.0、Kotlin 2.2.10、Compose BOM 2026.09.00、material3 1.5.0-rc01、Room 2.8.5、KSP 2.2.10-2.0.2、FFmpegKit 8.1.9、serialization 1.9.0 都能在构建文件里逐字找到。§3.1 的补充项（compileSdk/targetSdk/minSdk、WorkManager、Media3、DataStore、Paging、coroutines、OkHttp、Activity、Lifecycle、Navigation）同样一致。**按硬规则，本任务不提出任何版本升降级。**
2. **但 P6-03 的「校验/锁定」两项都没做**：仓库没有 `gradle/verification-metadata.xml`，也没有任何 `dependencyLocking`/lockfile。Gradle 目前跑在「不校验、不锁定」的默认模式，P6-03 的验收条件（"CI 不因校验失败"）此刻无从谈起。→ **P0**
3. **供应链有 5 处开口**：Actions 全部 tag pinning（SEC-01）、wrapper 无 `distributionSha256Sum` 且 CI 无 `wrapper-validation`、FFmpegKit 为单人维护的原生 AAR 且无校验和、Robolectric 的 `android-all` 在测试运行时绕过 Gradle 自行下载、CI 每次用 `sdkmanager` 拉 SDK 平台包无 checksum。→ **P1**
4. **3 处「声明值 ≠ 解析值」需要 CI 证据**（都不是手写版本错，而是 BOM/传递依赖叠加）：
   - `kotlin-stdlib` 预期被 material3 1.5.0-rc01 拉到 **2.2.20**，而矩阵写的是 2.2.10（编译器确实还是 2.2.10）；
   - Compose BOM 2026.09.00 实际管控 material3 = **1.4.0**，项目显式 1.5.0-rc01 靠「最高者胜」覆盖；
   - material3 1.5.0-rc01 对 `material3-ripple-android` 用的是**严格版本范围** `[1.5.0-rc01]`，BOM 未收录该构件。
5. **4 个依赖声明了但 0 引用**（`paging-compose`、`room-testing`、`espresso-core`、`ui-test-junit4`/`ui-test-manifest`），其中 paging 会实打实进 release APK。→ **P2**
6. **任务书的前提需要修正**：仓库**不存在** `gradle/libs.versions.toml`（详见 §1.1），版本来源是根 `build.gradle.kts` 的 `plugins` 块 + `app/build.gradle.kts` 的内联坐标。本次审计以这两处为事实来源。

---

## 1. 核对范围与方法

### 1.1 前提修正：仓库没有 version catalog

| 检查 | 结果 |
|---|---|
| `git ls-files` 中是否有 `libs.versions.toml` | 无（0 条） |
| `find . -name "libs.versions.toml"` | 无命中 |
| `settings.gradle.kts` 中 `versionCatalogs` | 无（`settings.gradle.kts:1-16` 只有 `pluginManagement` / `dependencyResolutionManagement`） |
| 构建脚本引用 `libs.` | 无 |

执行计划 §6.1–§6.4 给出的就是内联版本写法，所以这不是「偏离计划」，而是**计划本身没有 version catalog**。后果：

- 版本散落在 2 个文件、约 40 处坐标里，没有单一版本来源；升级一个库要全仓 grep（P2-5）。
- `compose-bom` 出现在 3 处（`app/build.gradle.kts:111,149,150`），三处必须同版本，靠人眼保证。
- 对 P6-03 的直接影响：verification-metadata 与 lockfile 一旦引入，**任何版本改动都会同时产生校验和/锁文件 diff**，这在没有 catalog 的情况下会放大人为失误面。引入 catalog 属于构建改动（本任务禁止），建议放在 P6-03 落地 PR 之后单独立项。

### 1.2 方法

- 静态读取：`gradle/wrapper/gradle-wrapper.properties`、根 `build.gradle.kts`、`app/build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`、`.github/workflows/{ci,release,smoke}.yml`、`.gitignore`。
- 代码使用面：对 `app/src/{main,test,androidTest}` 做 `import` 前缀统计，用来判断「声明了但没用」。
- 上游元数据：拉取 Compose BOM 2026.09.00 的 POM（Google Maven）、material3-android 1.5.0-rc01 POM、`dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.9` 的 POM 与目录清单、`com.arthenica:smart-exception-java:0.2.1` POM、okhttp 5.5.1 POM/Module。
- **未做**：`./gradlew dependencies`、`dependencyInsight`、任何编译/测试/CI 触发。凡「解析结果」均为按 Gradle 默认策略（最高版本胜、BOM 提供约束）的推导，标记 **[需 CI 核实]**。

---

## 2. 版本矩阵核对（§3.1 ⇄ 实际声明）

### 2.1 任务指定的 9 项

| 组件 | §3.1 矩阵 | 仓库实际 | 位置 | 判定 |
|---|---|---|---|---|
| AGP | 9.5.1 | `com.android.application` 9.5.1 | `build.gradle.kts:2` | ✅ |
| Gradle | 9.9.0 | `gradle-9.9.0-bin.zip` | `gradle/wrapper/gradle-wrapper.properties:3` | ✅ |
| Kotlin | 2.2.10（AGP 内置） | compose 插件 2.2.10、serialization 插件 2.2.10；未 apply `org.jetbrains.kotlin.android` | `build.gradle.kts:3,5` | ✅（运行时 stdlib 见 §3.3） |
| Compose BOM | 2026.09.00 | `androidx.compose:compose-bom:2026.09.00` ×3 | `app/build.gradle.kts:111,149,150` | ✅ |
| material3 | 1.5.0-rc01 | material3 / adaptive-navigation-suite / window-size-class 均 1.5.0-rc01 | `app/build.gradle.kts:115-117` | ✅ |
| Room | 2.8.5 | runtime / ktx / compiler(KSP) / testing 均 2.8.5 | `app/build.gradle.kts:136-138,157` | ✅ |
| KSP | 2.2.10-2.0.2 | `com.google.devtools.ksp` 2.2.10-2.0.2 | `build.gradle.kts:4` | ✅ |
| FFmpegKit | 8.1.9 | `dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.9` | `app/build.gradle.kts:146` | ✅ |
| serialization | 1.9.0 | `kotlinx-serialization-json:1.9.0` | `app/build.gradle.kts:142` | ✅ |

### 2.2 矩阵的其余项（一并核对）

| 组件 | §3.1 | 实际 | 位置 | 判定 |
|---|---|---|---|---|
| JDK | 17 | `source/targetCompatibility 17`、`jvmTarget 17`；CI `java-version: "17"` | `app/build.gradle.kts:68-69,94`；三个 workflow | ✅ |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 | 37 / 36 / 26 | `app/build.gradle.kts:19,24,23` | ✅ |
| WorkManager | 2.12.0 | `work-runtime-ktx:2.12.0`、`work-testing:2.12.0` | `app/build.gradle.kts:130,158,162` | ✅ |
| Media3 | 1.11.1 | exoplayer / session / ui 均 1.11.1 | `app/build.gradle.kts:131-133` | ✅ |
| DataStore | 1.3.0 | `datastore-preferences:1.3.0` | `app/build.gradle.kts:129` | ✅ |
| Paging | 3.5.3 | `paging-compose:3.5.3` | `app/build.gradle.kts:128` | ✅（但 0 引用，§3.5） |
| kotlinx.coroutines | 1.11.0 | android 1.11.0 / test 1.11.0 | `app/build.gradle.kts:143,152` | ✅ |
| OkHttp | 5.5.1 | okhttp 5.5.1 / mockwebserver 5.5.1 | `app/build.gradle.kts:141,153` | ✅ |
| Activity | 1.13.0 | `activity-compose:1.13.0` | `app/build.gradle.kts:122` | ✅ |
| Lifecycle | 2.11.0 | runtime-compose / viewmodel-compose / runtime-ktx 均 2.11.0 | `app/build.gradle.kts:124-126` | ✅ |
| Navigation | 2.10.2 | `navigation-compose:2.10.2` | `app/build.gradle.kts:127` | ✅ |
| `android.disallowKotlinSourceSets=false` | 必须保留 | 存在 | `gradle.properties:7` | ✅ |

**结论：A 类（版本偏差）= 0 项。** 不需要在本文之外做任何版本变更。

### 2.3 B 类：声明与实际解析可能不一致（3 项，只记录，不改版本）

| 编号 | 现象 | 证据 | 风险 | 处置建议 |
|---|---|---|---|---|
| B-1 | `kotlin-stdlib` 预期解析为 **2.2.20**，矩阵写 2.2.10 | material3-android 1.5.0-rc01 的 POM 声明 `kotlin-stdlib 2.2.20`；AGP 内置 Kotlin 提供 2.2.10；Gradle 默认取最高 → 2.2.20 **[需 CI 核实]** | 低（同为 2.2.x，metadata 兼容，CI 已绿）；若将来 material3 要求 2.3.x，而编译器仍是 2.2.10，会出现 metadata 版本高于编译器的报错 | **不要 force 降级**（material3 声明 2.2.20 可能有实际 API 依赖）；在文档表述上写清「编译器 2.2.10 / 运行时 stdlib 2.2.20（传递）」；CI 加 `dependencyInsight` 断言并把输出存 artifact |
| B-2 | BOM 管控 material3 = **1.4.0**，项目显式 1.5.0-rc01 | BOM 2026.09.00 POM：material3 / adaptive-navigation-suite / window-size-class / material3-lint 均 1.4.0；BOM 的 ui/foundation/runtime = 1.12.1 | 低（§3.1 已写明「material3 覆盖 BOM」，属预期）；真实风险是 **三个 material3 构件必须同时改**，漏改一个会被 BOM 静默拉回 1.4.0 或反向升版 | 保持现状；CI 断言三个 material3 构件解析版本一致 |
| B-3 | `material3-ripple-android` 用严格版本 `[1.5.0-rc01]` | material3-android 1.5.0-rc01 POM 中该依赖版本写作 `[1.5.0-rc01]`（唯一版本范围）；BOM 未收录 `material3-ripple` | 低但会「硬失败」：任何把 ripple 挪到别的版本的约束都会让解析直接报错（fail-fast）。目前该构件唯一来源就是 material3 本身 | 记录在案；升级 material3 时先看这条，避免同时加 version constraint |

> 说明：B-1 不是「版本写错」，而是矩阵把「编译器版本」和「依赖图里的 stdlib 版本」混为一谈。是否要改矩阵表述属于文档决策，本任务未改 §3。

---

## 3. 依赖清单与重复/冲突/传递风险

### 3.1 分类清单（含代码引用证据）

引用数为 `app/src` 下 `import` 该前缀的文件数（`main` + `test` + `androidTest` 合计，统计口径见 §1.2）。

| 类别 | 构件 | 版本 | 代码引用 | 判定 |
|---|---|---|---|---|
| **compose** | BOM | 2026.09.00 | — | ✅ 3 处同版本 |
| | `ui` / `ui-graphics` / `ui-tooling-preview` | BOM 管控 | 广泛 | ✅ 不带版本，正确姿势 |
| | `ui-tooling`、`ui-test-manifest`（debug） | BOM 管控 | 0 | ⚠️ `ui-test-manifest` 目前无用（§6） |
| | `ui-test-junit4`（androidTest） | BOM 管控 | 0 | ⚠️ P5-14 预留（§6） |
| **material3** | `material3` | 1.5.0-rc01 | 24 | ✅ 主力 UI |
| | `material3-adaptive-navigation-suite` | 1.5.0-rc01 | 1（`ui/navigation/AdaptiveScaffold.kt`） | ✅ |
| | `material3-window-size-class` | 1.5.0-rc01 | 0 | ⚠️ 冗余声明（NavigationSuiteScaffold 内部已用） |
| **androidx** | `activity-compose` | 1.13.0 | 3 | ✅ |
| | `core-ktx` | 1.19.1 | 5 | ✅ |
| | `lifecycle-*` ×3 | 2.11.0 | 22 | ✅ 三件套同版本 |
| | `navigation-compose` | 2.10.2 | 3 | ✅ |
| | `paging-compose` | 3.5.3 | **0**（仅注释提到 "Paging 3"） | ❌ 未使用，进 release APK |
| | `datastore-preferences` | 1.3.0 | 5 | ✅ |
| | `work-runtime-ktx` | 2.12.0 | 15 | ✅ |
| **media3** | exoplayer / session / ui | 1.11.1 | 3 | ✅ 三件套同版本，无 BOM |
| **room** | runtime / ktx | 2.8.5 | 19 | ✅ |
| | compiler（KSP） | 2.8.5 | — | ✅ `room.generateKotlin=true`、schema 入库（`app/schemas/.../1.json`） |
| **network** | `okhttp` | 5.5.1 | 2（main）+ 3（test） | ✅ |
| | `kotlinx-serialization-json` | 1.9.0 | 14（main）+ 12（test） | ✅ 1.9.0 是 Kotlin 2.2.10 的硬约束（§3.3 坑 #2） |
| | `kotlinx-coroutines-android` | 1.11.0 | 广泛 | ✅ |
| **ffmpeg** | `dev.ffmpegkit-maintained:ffmpeg-kit-audio` | 8.1.9 | 2（`FfmpegKitRunner`、`FfmpegProbe`） | ✅ 版本对；供应链见 §4.6 |
| **test** | junit 4.13.2 / coroutines-test 1.11.0 / mockwebserver 5.5.1 | — | 52 个 Robolectric 测试 | ✅ 版本与 main 对齐 |
| | robolectric 4.16 | — | 55 处引用 | ✅ 但运行时下载不受控（§4.1、§6） |
| | `androidx.test:core` 1.7.0、`ext:junit` 1.3.0 | — | 13 / 全部 | ✅ |
| | `room-testing` 2.8.5 | — | **0** | ❌ 未使用 |
| | `work-testing` 2.12.0（test + androidTest） | — | 2 | ✅ 与 runtime 一致（§6） |
| | `espresso-core` 3.7.0 | — | **0** | ❌ 未使用 |
| | `ui-test-junit4`（androidTest） | BOM | **0** | ⚠️ 预留 |

### 3.2 BOM 与显式版本的叠加（硬证据）

`platform("androidx.compose:compose-bom:2026.09.00")` 只提供约束；显式坐标一旦带版本就与约束取高者。拉取 BOM POM 后逐项对照：

| 构件 | BOM 2026.09.00 管控 | 项目显式声明 | 预期解析 |
|---|---|---|---|
| `compose.ui` / `foundation` / `runtime` | 1.12.1 | 无（跟随 BOM） | **1.12.1** ✅ 与 §3.1「Compose 1.12.1」一致 |
| `material3` | 1.4.0 | 1.5.0-rc01 | **1.5.0-rc01**（最高者胜，符合 §3.1 的「覆盖 BOM」） |
| `material3-adaptive-navigation-suite` | 1.4.0 | 1.5.0-rc01 | 1.5.0-rc01 |
| `material3-window-size-class` | 1.4.0 | 1.5.0-rc01 | 1.5.0-rc01 |
| `material3-lint` | 1.4.0 | 由 material3 传递带入 1.5.0-rc01 | 1.5.0-rc01 |
| `material3-ripple` | **未收录** | 严格 `[1.5.0-rc01]`（传递） | 1.5.0-rc01 |
| `kotlin-stdlib` | 不管控 | 2.2.20（material3 传递） | 2.2.20 **[需 CI 核实]** |

补充：material3 1.5.0-rc01 自己声明的是 foundation/ui/runtime **1.12.0**（低于 BOM 的 1.12.1），所以 BOM 胜出，最终组合是「material3 1.5.0-rc01 + Compose 1.12.1」。这个组合是当前 CI 跑通的组合，**升级 BOM 时需要重新核对 material3 的三件套**。

### 3.3 重复 / 冲突

- **同一构件没有多版本声明**：每个坐标在 `app/build.gradle.kts` 只出现一次（material3 系列是不同 artifactId，不算重复）。
- **无双 BOM 冲突**：test 与 androidTest 各自 `platform(compose-bom)`，版本相同（`:149`、`:150`）。
- **成对版本一致（这是本仓库做得好的地方）**：okhttp 5.5.1 ⇄ mockwebserver 5.5.1；coroutines-android 1.11.0 ⇄ coroutines-test 1.11.0；work-runtime-ktx 2.12.0 ⇄ work-testing 2.12.0（test **和** androidTest）；room 2.8.5 ⇄ room-testing 2.8.5。
- **需要「同改」的组合（无 BOM 兜底，靠人眼）**：
  - media3 三件套（1.11.1 ×3）——无 BOM，漏改一个就是混合版本；
  - Room 四件套 + KSP——KSP 版本必须与 Kotlin 精确配对（`2.2.10-2.0.2` ↔ `2.2.10`），Kotlin 一升 KSP 必须跟着升，Room 也要核对 KSP2 兼容；
  - material3 三件套——见 B-2。
- **`android.disallowKotlinSourceSets=false`** 是 KSP 2.2.10-2.0.2 + AGP 9 内置 Kotlin 的必需开关（`gradle.properties:7`），删除或升级 KSP 前必须重新验证，属版本联动的一部分。

### 3.4 传递依赖风险

| 传递项 | 来源 | 风险 | 说明 |
|---|---|---|---|
| `kotlin-stdlib 2.2.20` | material3-android 1.5.0-rc01 | 中 | 见 B-1；矩阵与解析图表述不一致，且无锁文件固定 |
| `com.arthenica:smart-exception-java 0.2.1` → `smart-exception-common 0.2.1` | ffmpeg-kit-audio POM | 低 | Taner Sener 的老库（BSD-3），仅异常基类；但无人维护、无版本上限 |
| `com.squareup.okio:okio 3.18.1` | okhttp 5.5.1 | 低 | 版本由 okhttp 决定，无锁则随 okhttp 变动 |
| `material3-ripple-android [1.5.0-rc01]` | material3 | 低 | 严格范围，升级时最先暴露（见 B-3） |
| `compose foundation/animation/ui 1.12.x` | BOM | 低 | 由 BOM 统一，是当前最稳的一部分 |
| `org.robolectric:android-all-instrumented`（API 35） | Robolectric 4.16 **在测试运行时自行下载** | **中** | 不经过 Gradle 解析 → verification-metadata 与 lockfile **都覆盖不到**；52 个测试都 `@Config(sdk = [35])` |
| `androidx.test:runner`（instrumented 入口） | `ext:junit 1.3.0` 传递 | 低 | `testInstrumentationRunner`（`app/build.gradle.kts:31`）依赖它，但未显式声明 → 版本可能随 ext:junit 漂移 |

### 3.5 未使用依赖（P2 清理清单）

| 构件 | 证据 | 建议 |
|---|---|---|
| `androidx.paging:paging-compose:3.5.3` | 全仓 `import androidx.paging` = 0；只有 `ChapterDao.kt:36`、`ChapterRepository.kt:25` 的注释提到 "Paging 3"，实际是手写分页 | 删除（减少 release APK 与传递面）；若打算上 Paging 3 再连同实现一起加回 |
| `androidx.room:room-testing:2.8.5` | `room.testing` / `MigrationTestHelper` = 0 | 删除，或在写迁移测试（P6-03 之后、Room 版本升级前）时再加回 |
| `androidx.test.espresso:espresso-core:3.7.0` | `espresso` = 0 | 删除（androidTest 全是 JUnit + InstrumentationRegistry）；Compose 测试用 `ui-test-junit4` 即可 |
| `androidx.compose.ui:ui-test-junit4` + `ui-test-manifest` | `androidx.compose.ui.test` = 0 | P5-14 计划写 Compose UI 测试 → 保留但**标注预留**；若不打算在 v1 做，一并删除 |
| `androidx.compose.material3:material3-window-size-class` | 直接引用 = 0（`AdaptiveScaffold.kt:16` 注释说明交给 `NavigationSuiteScaffold`） | 保留亦可（API 备用），但要知道它是冗余声明 |

---

## 4. 供应链

### 4.1 依赖校验：`gradle/verification-metadata.xml` 不存在（P6-03 核心）

| 检查 | 结果 |
|---|---|
| `find . -name verification-metadata.xml` | 无 |
| `gradle/` 目录内容 | 只有 `wrapper/gradle-wrapper.jar`、`wrapper/gradle-wrapper.properties` |
| `gradle.properties` 中的校验开关 | 无（无 `org.gradle.dependency.verification*`） |
| CI 是否传 `--dependency-verification` | 无（三个 workflow 都没有） |

后果与边界：

- 当前所有模块（含 AGP/KSP/Compose 插件等 buildscript classpath）**只靠 HTTPS + 仓库信任**，任何一次上游被接管或被投毒都无法在构建时发现。
- 一旦加入该文件，Gradle 默认即为 `strict`，缺失/不匹配条目会直接让构建失败——这正是 P6-03 验收要的行为。
- **该文件覆盖不到**：Robolectric 运行时下载的 `android-all`（§3.4）、CI 里 `sdkmanager` 安装的 `platforms;android-37.0` / `build-tools;37.0.0`、`actions/*` 自身。
- 生成时的坑：`--write-verification-metadata` 只记录**本次解析到**的构件，必须同时覆盖 `debugRuntimeClasspath`、`releaseRuntimeClasspath`、`ksp`、`testDebugUnitTest`、`androidTest` 等配置，否则 CI 会在第一次跑没覆盖到的配置时报缺失。建议在 CI 里跑一次「全量」生成（`help` + `assembleDebug` + `testDebugUnitTest`），把产物提交。

### 4.2 Actions tag pinning（SEC-01，P1）

| 文件 | 行 | Action | 当前 | 风险 |
|---|---|---|---|---|
| `ci.yml` | 22 / 25 / 42 / 54 / 63 / 71 | checkout / setup-java / setup-gradle / upload-artifact ×3 | `@v4` | tag 可被移动到任意 commit |
| `release.yml` | 22 / 24 / 40 / 81 | checkout / setup-java / setup-gradle / upload-artifact | `@v4` | 同上 |
| `release.yml` | 89 | `softprops/action-gh-release@v2`（第三方） | `@v2` | 同 job 持有签名 secrets + `contents: write`（`:14-15`） → tag 被移动即可窃取签名密钥或篡改发布 |
| `smoke.yml` | 24 / 27 / 44 / 77 | checkout / setup-java / setup-gradle / upload-artifact | `@v4` | 同上 |
| `smoke.yml` | 53 | `reactivecircus/android-emulator-runner@v2`（第三方） | `@v2` | 在 PR 上下文执行脚本，能读仓库 token |

全仓 `grep -R "uses: .*@v[0-9]"` = 16 处命中（ci 6 / release 5 / smoke 5），**0 处 SHA pin**。仓库也没有 `.github/dependabot.yml`（`ls .github/` 只有 `workflows/`），所以修成 SHA 后没有自动升级通道。→ 与 SEC-01 一致，仍为 P1。

### 4.3 动态版本

- `+`、`latest.release`/`latest.integration`、`-SNAPSHOT`：**全仓 0 命中** ✅。
- 但「没有动态版本」≠「可复现」：传递依赖的版本没有锁（`okio`、`kotlin-stdlib`、`smart-exception-common` 等），上游 POM 一改，同一份代码解析出的图就变了。
- `plugins` 块的插件版本是硬编码 ✅；`gradlePluginPortal()` 与 `google()`、`mavenCentral()` 有重叠解析路径，同 group 的插件理论上可能来自不同仓库（P2）。

### 4.4 仓库来源

| 项 | 现状 | 评价 |
|---|---|---|
| `dependencyResolutionManagement.repositories` | `google()` + `mavenCentral()`（`settings.gradle.kts:10-13`） | ✅ 只走官方两仓 |
| `repositoriesMode` | `FAIL_ON_PROJECT_REPOS`（`:9`） | ✅ 防止模块里偷偷加仓库 |
| `pluginManagement.repositories` | `google()` + `mavenCentral()` + `gradlePluginPortal()`（`:2-6`） | ✅ 合理 |
| `mavenLocal()` / jitpack / 本地 `file://` 仓库 | 无 | ✅ |
| content filter（`includeGroup`/`excludeGroup`） | 无 | ⚠️ 未把 group 与仓库绑定，P2 |

### 4.5 Wrapper（P1）

| 项 | 现状 | 风险 |
|---|---|---|
| `distributionUrl` | `gradle-9.9.0-bin.zip`（`:3`） | 版本对 |
| `distributionSha256Sum` | **缺失** | 分发包无内容校验；Gradle 9 的 `validateDistributionUrl`（`:7`）只校验 URL 形态，不校验内容 |
| CI 的 `gradle/actions/wrapper-validation` | 未使用 | `gradle-wrapper.jar`（49 KB）已入库，但 CI 不校验它与声明版本是否一致 |
| `gradle-wrapper.jar` 本身 | 入库 | 需要 SHA 校验或 wrapper-validation 兜底 |

### 4.6 FFmpegKit 供应链（P1，风险面最大的单点）

本次拉取了上游元数据，结论：

| 项 | 事实 |
|---|---|
| 坐标 | `dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.9`（Maven Central） |
| 体积 | AAR **21.5 MB**（Central 目录实测 `21537944` 字节）；APK 里双 ABI 的 `.so` 约 44 MB |
| 签名文件 | 目录中有 `.asc` / `.sha1` / `.sha256` / `.sha512`（可支持 pgp 校验） |
| 维护者 | POM `developers` 只有 **Jokobee（jokobee.com）** 一人；SCM 指向 `github.com/ffmpegkit-maintained/ffmpeg`；`inceptionYear 2026` |
| 定位 | 自述为 Arthenica 官方 `com.arthenica:ffmpeg-kit-audio`（2025-04 退役）的 **drop-in 替代**，保留 `com.arthenica.ffmpegkit` 包名 |
| 传递依赖 | `com.arthenica:smart-exception-java:0.2.1`（→ `smart-exception-common:0.2.1`，BSD-3，已停更） |
| 许可 | **LGPL-3.0**（AAR 内含原生 `.so`） |

风险与缓解：

- 这是**唯一会把原生可执行代码装进 APK** 的依赖，且由单人维护、无校验和、无锁 → 一旦上游被接管/篡改，影响是「设备上执行任意原生代码」，比其他依赖高一个量级。
- 包名保留 `com.arthenica.ffmpegkit` 是**好事**：代码 `import` 不用改，将来若有官方/更可信的 fork，只需换 groupId。
- LGPL-3.0 + 侧载分发：发布页/许可页需要声明来源与许可（与 P5-10 许可页、P6-10 发布文档联动），这是 P2 的合规项，不是技术项。
- 缓解优先级：verification-metadata（P0-1）覆盖 AAR 的 SHA-256 → 锁版本（P0-2）→ 记录到许可页（P2-9）。

---

## 5. 构建可复现建议（片段，本任务均**未落地**）

### 5.1 依赖锁定（P0-2）

```kotlin
// app/build.gradle.kts
dependencyLocking { lockAllConfigurations() }
```

```bash
# 生成/更新（联网；建议在 CI 的 workflow_dispatch 里做，产物提交）
./gradlew :app:dependencies --write-locks
```

要点：锁文件（`app/gradle.lockfile`）必须入库（`.gitignore` 目前不排除它，✅）；`ksp*` 与 `androidTest*` 配置也要一起锁；升级依赖时 `--write-locks` 会产生可 review 的 diff，正好补上「没有 version catalog」的缺口。

### 5.2 校验和（P0-1）

```bash
# 先 sha256（Google Maven 的 .asc 覆盖不全，pgp 可后续再加）
./gradlew --write-verification-metadata sha256 help
# 有文件后默认即 strict；CI 显式写出来更清楚
./gradlew --dependency-verification=strict --no-daemon lintDebug testDebugUnitTest assembleDebug
```

要点：生成时要把 `assembleDebug`/`testDebugUnitTest`/`ksp` 相关配置都解析一遍，否则漏条目；提交 `gradle/verification-metadata.xml` 后，`--write-verification-metadata` 不要留在 CI 常驻流程里（否则等于自己给自己发证书）。

### 5.3 版本守卫与约束（P2-7）

```kotlin
// app/build.gradle.kts —— 只守卫「必须同版本」的组合与动态版本，不 force 任何版本
configurations.all {
    resolutionStrategy.eachDependency {
        val v = requested.version ?: return@eachDependency
        if (v.endsWith("+") || v.startsWith("latest.") || v.endsWith("-SNAPSHOT")) {
            throw GradleException("禁止动态版本: ${requested.group}:${requested.name}:$v")
        }
    }
}
```

要点：**不要**用 `force("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")` 去「对齐」B-1——material3 声明 2.2.20 可能确有 API 依赖，降级风险高于收益；用 `dependencyInsight` 断言 + 文档标注即可。media3 三件套 / Room 四件套的「同版本」建议用 CI 脚本比对解析结果，而不是在构建里硬约束。

### 5.4 仓库 content filter（P2-6）

```kotlin
// settings.gradle.kts → dependencyResolutionManagement.repositories
google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("androidx\\..*"); includeGroupByRegex("com\\.google\\..*") } }
mavenCentral { content { excludeGroupByRegex("com\\.android.*"); excludeGroupByRegex("androidx\\..*") } }
```

要点：`pluginManagement` 保持现状（KSP 等插件在 Portal/Google 双源），不要给 pluginManagement 加同样的过滤器，否则可能解析不到插件。

### 5.5 CI 侧（P1 配套）

| 建议 | 作用 |
|---|---|
| `gradle/actions/wrapper-validation@<sha>` | 校验 `gradle-wrapper.jar` 与声明版本一致（补 §4.5） |
| `gradle/actions/dependency-submission@<sha>` | 把依赖图送 GitHub 依赖图（后续 CVE/告警的基础） |
| 把 `dependencies` 输出存 artifact | 给 P6-03 验收、B-1/B-2 核实留下证据（本任务无法本地生成） |
| Actions 全部 pin 到 commit SHA + `dependabot.yml`（github-actions 生态） | 关闭 SEC-01 |

---

## 6. 测试依赖一致性

| 项 | 声明 | 判定 |
|---|---|---|
| `work-runtime-ktx` | 2.12.0（`:130`） | — |
| `work-testing`（test） | 2.12.0（`:158`） | ✅ 与 runtime 完全一致 |
| `work-testing`（androidTest） | 2.12.0（`:162`） | ✅ **androidTest 也一致**（常见遗漏点，本项目做对了） |
| `kotlinx-coroutines-test` | 1.11.0（`:152`）⇄ coroutines-android 1.11.0（`:143`） | ✅ |
| `mockwebserver` | 5.5.1（`:153`）⇄ okhttp 5.5.1（`:141`） | ✅ |
| `room-testing` | 2.8.5（`:157`）⇄ room 2.8.5 | ✅ 版本对，但 0 引用（§3.5） |
| `androidx.test:core` / `ext:junit` | 1.7.0 / 1.3.0 | ⚠️ ext:junit 会传递自己的 core 与 runner，需 `dependencyInsight` 核实是否与显式的 1.7.0 冲突（预期取高者，但无锁） |
| `espresso-core` / `ui-test-junit4` | 3.7.0 / BOM | ⚠️ 0 引用（androidTest 只有 JUnit + InstrumentationRegistry 7 个文件） |
| `robolectric` | 4.16（`:154`），52 个测试 `@Config(sdk = [35])`，**无 `robolectric.properties`** | ⚠️ SDK 版本散在注解里；`android-all-instrumented` 由 Robolectric 运行时下载，**Gradle 校验/锁定都覆盖不到** |
| `testInstrumentationRunner` | `androidx.test.runner.AndroidJUnitRunner`（`:31`），runner 靠 ext:junit 传递 | ⚠️ 建议显式声明 `androidx.test:runner`（P2，避免传递漂移导致 instrumented 启动失败） |

补充：Robolectric 的运行时下载是**测试可复现性**的独立缺口——即使做了 lockfile + verification-metadata，`android-all` 仍然每次由 Robolectric 自行抓取。若要收紧：`robolectric.properties` 固定 `sdk=35`，CI 预置 `robolectric.dependency.dir` 并校验 SHA（涉及测试基建，属于 P6-03 之后的独立小任务，不在本任务范围）。

---

## 7. 结论：P0 / P1 / P2

### P0（不做则 P6-03 无法验收）

| ID | 项 | 证据 | 建议动作 |
|---|---|---|---|
| P0-1 | 无 `gradle/verification-metadata.xml` | `gradle/` 下只有 wrapper；CI 无 `--dependency-verification` | 生成 sha256 元数据并提交；CI 显式 `--dependency-verification=strict`；全配置覆盖（§5.2） |
| P0-2 | 无依赖锁定 | 无 `dependencyLocking`、无 `*.lockfile` | `lockAllConfigurations()` + `--write-locks`，锁文件入库（§5.1） |
| P0-3 | P6-03 验收证据缺失 | 计划要求「CI 不因校验失败」，但当前没有校验、CI 也不 dump 依赖图 | 先做 P0-1/P0-2，再把 `dependencies` 输出存 artifact 作为验收证据 |

### P1

| ID | 项 | 证据 | 建议动作 |
|---|---|---|---|
| P1-1 | Actions 全部 tag pinning（SEC-01） | 16 处 `@v4`/`@v2`，0 处 SHA；无 dependabot | 全部 pin 到 commit SHA；`release.yml` 的第三方 Action 优先替换/收紧；加 `dependabot.yml` |
| P1-2 | Wrapper 无 `distributionSha256Sum` | `gradle-wrapper.properties:3` | 补 SHA-256；CI 加 `gradle/actions/wrapper-validation` |
| P1-3 | FFmpegKit 单人维护原生 AAR、无校验 | POM：单维护者、`inceptionYear 2026`、21.5 MB AAR、LGPL-3.0 | 由 P0-1 覆盖 SHA-256；锁版本；许可页声明（P2-9）；保留换 groupId 的回退路径 |
| P1-4 | Robolectric 运行时下载不受 Gradle 校验/锁定 | 52 个测试 `@Config(sdk = [35])`，无 `robolectric.properties` | CI 预置/缓存 `android-all` 并校验；`robolectric.properties` 固定 SDK |
| P1-5 | CI 每次 `sdkmanager` 装 SDK 平台包无 checksum | 三个 workflow 都执行 `sdkmanager ... platforms;android-37.0 build-tools;37.0.0` | 记录为残余风险；可改用固定 runner 镜像或对下载物做 SHA 校验 |

### P2

| ID | 项 | 证据 | 建议动作 |
|---|---|---|---|
| P2-1 | `kotlin-stdlib` 解析值 2.2.20 ≠ 矩阵 2.2.10 | material3 POM | 只改文档表述（编译器 2.2.10 / 运行时 2.2.20 传递）；CI `dependencyInsight` 断言 |
| P2-2 | BOM material3 1.4.0 与显式 1.5.0-rc01 叠加 | BOM POM | 维持现状；CI 断言 material3 三件套解析版本一致 |
| P2-3 | `material3-ripple` 严格范围 | material3 POM | 记录；升级 material3 时优先检查 |
| P2-4 | 4 个未使用依赖 | 0 引用（§3.5） | 删除 paging/room-testing/espresso（ui-test 二选一保留或删） |
| P2-5 | 无 version catalog | §1.1 | 立项：迁移到 `gradle/libs.versions.toml`（涉及构建改动，需单独任务） |
| P2-6 | 仓库无 content filter | `settings.gradle.kts:10-13` | 按 §5.4 绑定 group 与仓库 |
| P2-7 | 无版本守卫 | 无 `resolutionStrategy` | 按 §5.3 拒绝动态版本；media3/Room/KSP 同版本用 CI 比对 |
| P2-8 | `androidx.test:core`/`runner` 未显式声明 | `:155-156,31` | 显式声明并与 ext:junit 版本对齐 |
| P2-9 | FFmpegKit LGPL-3.0 合规 | POM licenses | 在许可页/发布文档声明来源与许可 |

---

## 8. 建议命令（不要求本地执行）

```bash
# 1) 校验和元数据（联网；建议 CI workflow_dispatch 执行后提交产物）
./gradlew --write-verification-metadata sha256 help

# 2) 锁文件
./gradlew :app:dependencies --write-locks

# 3) 核实 B-1/B-2（把输出存 artifact 作为 P6-03 验收证据）
./gradlew :app:dependencyInsight --dependency kotlin-stdlib --configuration debugRuntimeClasspath
./gradlew :app:dependencyInsight --dependency material3 --configuration debugRuntimeClasspath
./gradlew :app:dependencies --configuration releaseRuntimeClasspath > build/reports/deps-release.txt

# 4) 引入元数据后的 CI 命令（strict 是默认值，显式写出便于审计）
./gradlew --dependency-verification=strict --no-daemon lintDebug testDebugUnitTest assembleDebug

# 5) 动态版本自查（当前应为空）
grep -RnE "\+['\"]|latest\.(release|integration)|-SNAPSHOT" --include="*.kts" --include="*.toml" .

# 6) Actions pinning 自查（修好后应无命中）
grep -RnE "uses: [^@]+@v[0-9]" .github/workflows
```

---

## 9. 未做 / 待办

- **未实际解析依赖图**（禁本地构建）→ B-1/B-2/B-3 以及「media3/Room 是否真的单一版本」都需要 CI 产物确认。
- 未审计 FFmpeg AAR 内部（`.so` 的 ABI、构建元数据、是否含 16 KB 对齐）——P6-02/P6-06 已在做体积与音频验证，本文不重复。
- 未做 CVE/SCA 扫描（可用 `osv-scanner` / `gradle/actions/dependency-submission` + GitHub 依赖图，属 P6-08 安全复查范围）。
- 本任务**未修改** `libs.versions.toml`（不存在）、`build.gradle.kts`、`.github/**`、`docs/progress.md`；P6-03 的落地（P0-1/P0-2 + CI 校验）建议单独 PR，一次只改一个变量。
