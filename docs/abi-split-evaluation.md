# ABI split / 体积评估（P6-02）

> **状态**：评估完成；**构建配置未改**（本文是 P6-02 的唯一产物，`app/build.gradle.kts`、两个 workflow 均未动）
> **基线**：`main@7c4e363`；分支 `docs/p6-02-abi`；日期 2026-11-08（Asia/Shanghai）
> **对照**：执行计划 §3（版本矩阵 / ADR-005）、§6.4（构建配置）、§9（风险 6）、§14.6（验收门）、§15 P6-02、§16（CI/CD）；
> `docs/r8-evaluation.md`（P6-01，体积分项与 R8 收益）、`docs/device-matrix.md` §1.3（ABI 维度）、`.github/workflows/{ci,release}.yml`、`scripts/verify-apk.sh`
> **证据边界**：本文的原生库数字来自**解包本地 Gradle 缓存里的 AAR**（只读，不是构建）；APK 总量来自 v0.1.0 已发布记录（`docs/progress.md`）。
> 其余体积数字均为**估算**，必须用 §6.1 的实测表替换。按 AGENTS.md，本任务**未执行任何构建/CI**。

---

## 1. 结论（TL;DR）

1. **体积瓶颈是 `.so`，不是代码**。universal release ≈ 60.5 MB，其中 FFmpegKit 原生库 **43.9 MiB**（arm64 20.4 + x86_64 23.5），占 ~73%；dex 只有 14–16 MB。
   → 用户感知的下载体积几乎完全由「打进去几个 ABI」决定。
2. **推荐：`splits.abi` + `isUniversalApk = true`，产物 3 个**（`arm64-v8a` / `x86_64` / `universal`），**三者共用同一个 `versionCode`**。
   预期：arm64 单包 **36–37 MB（−38% ~ −41%）**，x86_64 单包 39–41 MB；叠加 P6-01 的 R8 后 arm64 约 **30–33 MB**。
3. **`isUniversalApk = true` 是刻意的**：GitHub Release 侧载场景下必须保留一个「谁都能装」的包做兜底，
   否则用户选错包（装到 `INSTALL_FAILED_NO_MATCHING_ABIS`）时我们没有退路，而一次 release 多存 60 MB 是可接受成本。
4. **不改 ABI 列表**：`arm64-v8a` + `x86_64` 原样保留（ADR-005）。split 只是把它们**拆开**打包，不新增也不删除 ABI，
   **不构成版本矩阵变更**。`armeabi-v7a` 依然不可行——AAR 里根本没有 v7a 的 `.so`（§2.4）。
5. **AAB 本阶段不推荐**：不上 Play，AAB 不能直接安装，必须额外引入 `bundletool` 才能产出侧载 APK，
   对「GitHub Release → 用户手动安装」这条唯一分发链路只增加步骤、不减少体积（体积收益来自 split，AAB 只是把它自动化）。
6. **最大的坑是配套改动，不是 Gradle**：`splits` 是**全 variant 生效**的，一旦启用，`app/build/outputs/apk/{debug,release}/app-{debug,release}.apk`
   这些路径就变了 → `ci.yml` 的 `verify-apk.sh app-debug.apk`、`release.yml` 的 `app-release.apk`、`smoke.yml` 的 x86_64 emulator 全部会被打破（§5.2）。
   → 因此建议用 **Gradle 属性开关**（默认关）隔离影响面，并在**同一个提交**里改完 workflow（§4.1）。
7. **给 v1 的建议时序**：v1.0.0 先不动（P6-09 的发布链路已经验证过 universal），把 split 作为 P6-09 之后的独立提交或 v1.1 第一项；
   如果一定要在 v1.0.0 用，就必须先改 `release.yml` 并在真机上验证「universal ↔ 分片互相覆盖安装」。

---

## 2. 现状

### 2.1 构建现状

| 项 | 当前值 | 位置 | 说明 |
|---|---|---|---|
| `ndk.abiFilters` | `arm64-v8a`, `x86_64` | `app/build.gradle.kts:29` | 全项目唯一的 ABI 真源；注释写明「ffmpeg AAR 只提供这两个 ABI」 |
| `splits { abi { … } }` | **不存在** | — | 所有 ABI 打进同一个 APK |
| `bundle { }` / `bundleRelease` | **不存在** | — | 从未产出 AAB |
| `jniLibs.useLegacyPackaging` | `false` | `app/build.gradle.kts:77` | **`.so` 以未压缩、页对齐方式存进 APK**（可直接 mmap，安装时不解压） |
| release 构建 | `assembleRelease` 单产物 | `release.yml:68` | 产物固定为 `app/build/outputs/apk/release/app-release.apk` |
| 版本码 | `-PversionCode=${{ github.run_number }}` | `release.yml:67` | 每次 release 一个整数，全 ABI 唯一 |
| 产物命名 | `audioforge-<version>.apk` + `checksums.txt` | `release.yml:73-77` | 单 APK，命名里没有 ABI 维度 |
| 校验 | `scripts/verify-apk.sh <apk>`（badging / `zipalign -P 16` / `apksigner` / 列 `lib/`） | 同上 | 只接受**单个** APK 参数 |

关键点：`useLegacyPackaging = false` 意味着 **APK 里的 `.so` 不压缩**，
所以「APK 体积 ≈ 各 ABI 的 `.so` 未压缩大小之和 + dex + 资源」。这正是 split 能一刀砍掉 20+ MB 的原因。

### 2.2 FFmpegKit 的 `.so` 实测（解包 AAR，只读）

`dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.9`，AAR 共 20.5 MiB（压缩态），内部只有两个 ABI：

| 库 | arm64-v8a (MiB) | x86_64 (MiB) | 占比说明 |
|---|---|---|---|
| `libavcodec.so` | **11.09** | **13.21** | 编解码器，单库占一多半 |
| `libavfilter.so` | 2.78 | 3.28 | 后处理滤镜 |
| `libavformat.so` | 3.00 | 3.16 | 封装/解封装 |
| `libavutil.so` | 0.53 | 0.66 | 基础工具 |
| `libswscale.so` | 0.52 | 0.81 | 像素/采样缩放 |
| `libswresample.so` | 0.20 | 0.33 | 重采样 |
| `libc++_shared.so` | 1.71 | 1.54 | C++ 运行时（**不能删**，ffmpegkit 动态链接它） |
| `libffmpegkit.so` | 0.45 | 0.46 | FFmpegKit 的 JNI 壳 |
| `libffmpegkit_abidetect.so` | 0.03 | 0.04 | ABI 探测 |
| `libavdevice.so` | 0.05 | 0.05 | 输入设备（实际用不到，但 AAR 打进来了） |
| **合计** | **20.35 MiB** | **23.54 MiB** | 两 ABI 合计 **43.9 MiB** |

> 这个 fork 的 audio 包并没有做「裁剪到只留 aac/mp3 + 最小 demuxer」的瘦身，`libavcodec` 仍是全量。
> **未来若要进一步瘦身，优先级是「换一个裁剪过的 ffmpeg 变体」，而不是继续折腾 ABI 维度**（ABI split 只能拿掉一半，且只有一次）。
> 该方向属于依赖替换（需 `docs/decisions.md` + ADR），不在 P6-02 范围。

### 2.3 APK 结构估算

基线：v0.1.0 已发布 universal APK **≈ 60.5 MB**（`docs/progress.md`；执行计划 §9 早期估的是 ~58 MB，实测偏高）。

| 部分 | universal（当前） | 占总量 | 来源 |
|---|---|---|---|
| `lib/arm64-v8a/**` | 20.4 MiB | ~34% | §2.2 实测 |
| `lib/x86_64/**` | 23.5 MiB | ~39% | §2.2 实测 |
| dex（classes*.dex） | 14–16 MB | ~25% | `docs/r8-evaluation.md` §7 估算 |
| `res/` + `resources.arsc` | ~100 KB 级 | <1% | 同上（无 PNG/XML 布局） |
| assets（声音池/提示词） | 36 KB | <1% | 同上 |
| 其他（签名、manifest、META-INF、对齐填充） | 数百 KB | ~1% | — |
| **合计** | **≈ 60.5 MB** | 100% | `docs/progress.md` |

由此推出的三个候选产物（**估算，待 §6.1 实测替换**）：

| 产物 | 估算下载体积 | 相对 universal | 依据 |
|---|---|---|---|
| universal（arm64 + x86_64） | 60.5 MB（已知） | — | 已发布 |
| **arm64-v8a 单包** | **36–37 MB** | **−23 ~ −24.5 MB（−38% ~ −41%）** | 60.5 − 23.5 |
| x86_64 单包 | 39–41 MB | −19 ~ −21 MB（−32% ~ −35%） | 60.5 − 20.4 |
| arm64 单包 + P6-01 R8 生效 | ~30–33 MB | −45% ~ −50% | 再减 4–6.5 MB（`r8-evaluation` §7） |

> 已有 APK 的「下载体积」以 Release asset 字节数为准（GitHub 不做二次压缩），不是 Play 的下载体积口径；
> 侧载用户看到的永远是文件大小，所以这张表就是用户感知。

### 2.4 为什么现在没有 `armeabi-v7a`（以及为什么 split 也造不出来）

- §2.2 的 AAR **只有 `jni/arm64-v8a/` 与 `jni/x86_64/`**，没有 `jni/armeabi-v7a/`。所以「加 v7a split」在本仓库是**空 split**：
  Gradle 能生成 `app-armeabi-v7a-release.apk`，但里面一个 `.so` 都没有 → 装上就 `UnsatisfiedLinkError`，比装不上更难排查。
- 32 位设备（D8）因此**无论如何都装不上**：`INSTALL_FAILED_NO_MATCHING_ABIS`。
- **important**：`isUniversalApk` 也救不了 32 位设备——universal 只是多带 x86_64，不含 v7a。
  「发 universal 就兼容所有设备」是常见误解，必须写进 README（§5.1）。
- 真要支持 32 位，需要换/补一个含 v7a 的 ffmpeg 变体（社区 fork 是否提供待核实，上游 ffmpeg-kit 已停止维护），
  这属于**依赖与版本矩阵变更**，必须走 `docs/decisions.md` + V1–V8 验证，不在 P6-02 范围（device-matrix §7 建议 #5 已记同一条）。

---

## 3. 方案对比

### 3.1 三个候选

| 方案 | 形态 | 产物数 |
|---|---|---|
| **A. universal（现状）** | 一个 APK 含全部 ABI | 1 |
| **B. ABI splits** | 每个 ABI 一个 APK，可选再产 1 个 universal；共用 `versionCode`，用 `splits { abi { … } }` | 2–3 |
| **C. AAB** | `bundleRelease` 出 `.aab`，由 `bundletool`/Play 生成并按设备下发 split APK | 1 个 aab + N 个派生 apk |

### 3.2 横向对比

| 维度 | A. universal | B. ABI splits | C. AAB |
|---|---|---|---|
| arm64 用户下载体积 | 60.5 MB | **36–37 MB** | 36–37 MB（Play 下发）；自行 `bundletool` 时同 B |
| 构建命令 | `assembleRelease`（不变） | `assembleRelease`（产物变 2–3 个） | `bundleRelease` + `bundletool build-apks` |
| 能否直接安装 | ✅ | ✅ | ❌（必须先 `bundletool`） |
| 依赖新工具 | 无 | 无（AGP 内置） | **需要 bundletool 版本化管理**（CI 多一个下载/校验步骤） |
| 对 `ci.yml` / `smoke.yml` 影响 | 无 | **有**（debug 产物路径变，emulator 装错包风险） | 有（要加 bundle job） |
| 对 `release.yml` 影响 | 无 | 中（产物名/校验循环/Release 多文件） | 大（重写产物生成与校验链路） |
| 用户选包心智负担 | 无 | **有**（3 个文件选哪个） | 无（Play 自动）——但我们没有 Play |
| 版本码 | 单值 | 单值（推荐） | 由 bundletool 自动派生 |
| 回退成本 | — | 低（属性开关一行） | 高（要回退 workflow + 工具链） |
| 与 ADR-005（只两个 ABI）冲突 | 无 | 无 | 无 |
| 适合本项目（只侧载、不上 Play） | 兼容但不必要地大 | **最匹配** | ✗ 为 Play 设计的形态 |

### 3.3 逐项分析

**A. universal（保持现状）**
- 优点：链路已被 v0.1.0 验证；用户零选择成本；`verify-apk.sh` 与 workflow 不用改。
- 缺点：arm64 用户为 x86_64 的 23.5 MB 买单（占总量 39%），而 x86_64 的 `.so` 只有 emulator/CI 用得到。
- 结论：**不作为最终形态**，但**必须作为分片方案的兜底产物保留**（`isUniversalApk = true`）。

**B. ABI splits（推荐）**
- 优点：一次构建得到 2–3 个产物；arm64 用户少下 23.5 MB；无需新工具；Gradle 原生能力。
- 缺点：`splits` 是**全局**的（AGP DSL 没有 per-buildType 的 split 开关），会同时改变 debug 产物路径 → 必须同步改 CI；
  Release 页出现多个文件，用户可能下错。
- 结论：**推荐**，但要带属性开关 + workflow 同步改 + README 选包表。

**C. AAB（本阶段不推荐）**
- 优点：Play 场景下自动 ABI split + 按设备下发；`bundletool build-apks --mode=universal` 也能产出可侧载的 universal APK。
- 缺点：**AAB 本身不可安装**。本项目 100% 靠 GitHub Release 侧载 → 要么给用户发 `.apks`（普通用户根本不会装，
  还需要 `bundletool install-apks`），要么在 CI 里跑 `bundletool` 生成 APK 集合再挑文件发 → 等于用更复杂的链路得到 B 的产物。
  另外 `.aab` 会诱导「以后上 Play」，而上 Play 会带进 targetSdk/64 位/隐私政策等一整套新约束，与 AGENTS.md「只侧载、不上架」直接冲突。
- 结论：**不采用**；仅当未来决定上 Play 时，把 §4.2 的 `splits` 换成 `bundle`，且**不要两者同时开**（AGP 会忽略 splits 或报错）。

### 3.4 结论

`B（splits + universal 兜底）` 是唯一同时满足「体积真的降」「侧载链路不变形」「不引入新工具」的方案。
`C` 留作 Play 设想，`A` 保留为兜底产物。

---

## 4. 推荐配置

> ⚠️ 本节是**建议**，本任务**不落盘**。落地时必须单独提交，并在 `docs/decisions.md` 记一条（产物形态变更，不属于版本矩阵变更）。

### 4.1 设计要点（四个决定）

1. **属性开关，默认关**：`splits` 会同时影响 debug 与 release，而 `ci.yml`/`smoke.yml` 依赖 `app-debug.apk` 这个固定路径。
   用 `-PabiSplits=true` 显式开启（只在 `release.yml` 里传），**默认构建行为与今天完全一致**——满足「一次只改一个变量」。
2. **`isUniversalApk = true`**：保留兜底包。代价是每次 release 多 60 MB 资产（GitHub Release 单文件上限 2 GB，无压力）。
3. **`include("arm64-v8a", "x86_64")` 与 `ndk.abiFilters` 保持一致**：ABI 列表不变（ADR-005），split 只是拆包。
   两处列表必须完全一致，否则会出现「某个 split 产物为空」或「universal 里悄悄少了 ABI」这类难查问题（§6.2 有验证命令）。
4. **`versionCode` 在三个产物间完全相同**，不使用 `versionCodeOverride`（理由与陷阱见 §4.4）。

### 4.2 可粘贴片段（`app/build.gradle.kts`）

```kotlin
// 顶层：默认关闭，只有 release workflow 显式传 -PabiSplits=true 才拆分
val abiSplitsEnabled = (project.findProperty("abiSplits") as String?)?.toBoolean() ?: false

android {
    defaultConfig {
        // 保持不变：AAR 只提供这两个 ABI（ADR-005）；此处与下方 splits.include 必须一致
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    splits {
        abi {
            isEnableSplit = abiSplitsEnabled
            reset()                                  // 清空默认值，避免依赖隐式行为
            include("arm64-v8a", "x86_64")           // 只拆这两维
            isUniversalApk = true                    // 额外产 1 个含全部 ABI 的兜底包
        }
        // 不启用 density split：本项目 res/ 只有 ~100 KB、无位图资源，拆了没收益还会多出产物
    }
}
```

产物（`-PabiSplits=true` 时）：

```
app/build/outputs/apk/release/app-arm64-v8a-release.apk     # 36–37 MB，真机主推
app/build/outputs/apk/release/app-x86_64-release.apk        # 39–41 MB，emulator 用
app/build/outputs/apk/release/app-universal-release.apk     # 60.5 MB，兜底
```

> 命名由 AGP 决定（`app-<abi>-<buildType>.apk` / `app-universal-<buildType>.apk`），**未签名时**会带 `-unsigned` 后缀。
> `isEnableSplit = false` 时产物回到单个 `app-release.apk`，与今天一致（这是回退路径的关键）。

**若要改用 AAB（不推荐，仅记录）**：

```kotlin
// 与 splits 互斥：启用 bundle 时必须删掉上面的 splits 块
bundle {
    language { enableSplit = false }   // 本项目单语言（中文），语言拆分无意义
    density { enableSplit = false }    // 无位图资源
    abi { enableSplit = true }         // 仅 ABI 拆分
}
```

命令变为 `./gradlew :app:bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`，
之后必须 `bundletool build-apks --bundle=… --output=…apks --ks=… --mode=universal` 才能得到可侧载的 APK。

### 4.3 发布产物与命名（`release.yml` 配套，不在本任务做）

| 现名 | 建议名 | 说明 |
|---|---|---|
| `audioforge-0.1.0.apk` | `audioforge-<ver>-arm64-v8a.apk` | **Release 首推**（90%+ 用户） |
| — | `audioforge-<ver>-x86_64.apk` | 仅 emulator/CI/少数 x86 平板 |
| — | `audioforge-<ver>-universal.apk` | 兜底；不确定机型时下载这个 |
| `checksums.txt` | 不变，但**包含全部 3 行** | 现有 `sha256sum *.apk > checksums.txt` 已天然支持多文件，无需改 |
| — | `audioforge-<ver>-aab` 附注 | 若将来出 AAB，**不要**让普通用户看到 `.aab` |

`verify-apk.sh` 目前只接受一个参数 → 需改成循环（或新增 `verify-apks.sh`）：

```bash
for f in release/*.apk; do bash scripts/verify-apk.sh "$f"; done
```

> `scripts/verify-apk.sh` 里的 `zipalign -c -P 16 4` 与 `apksigner verify` **必须对每个产物各跑一次**；
> split 不改变对齐策略，但多产物时最容易漏掉某个包（尤其 universal）。

### 4.4 版本码策略（明确结论：三包同码）

**结论：三个产物使用完全相同的 `versionCode`（当前就是 `github.run_number`），不写 `versionCodeOverride`。**

理由与陷阱：

| 方案 | 行为 | 评价 |
|---|---|---|
| **同 versionCode（推荐）** | 三个包都是 `run_number`；用户装哪个都一样 | 侧载只装一个包 → 版本码唯一，升级/换包语义最简单 |
| 分片偏移（如 arm64=+0、x86_64=+1、universal=+9） | 不同 ABI 版本码不同 | **不要用**：用户从 universal 换到 arm64 分片时版本码变小 → `INSTALL_FAILED_VERSION_DOWNGRADE`，用户只能**先卸载**再装，而卸载会**连带清掉 API Key（Keystore）与本地 DB**。这是最贵的失败模式 |
| Play 自动派生 | AAB 由 bundletool/Play 生成 `base*1000 + abiOffset` | 上 Play 才需要；本项目不涉及 |

推论与注意事项：

1. **跨 release 必须单调递增**（现状 `run_number` 已满足）；**同一 release 内三包必须相等**。
2. **换包覆盖安装**（universal → arm64，或反向）在同 `versionCode` + 同签名下是允许的，
   `adb install -r` 与「点 APK 安装」都能直接覆盖，`/data/data`（DB、加密 Key、缓存）全部保留。
3. 覆盖安装**要求签名一致**：三个包必须由 `release.yml` 用同一个 keystore 一次构建产出，**绝不能**「本地 debug 签名发一个、CI 签名发另一个」。
4. 若某天真的用了 `versionCodeOverride`，就必须在 release notes 里写清「换包前请先备份 Key」——这是一个不该存在的坑，别开。

---

## 5. 风险清单

### 5.1 32 位设备（不可修复，只能声明）

| 项 | 内容 |
|---|---|
| 触发 | 用户设备 `abilist` 只有 `armeabi-v7a`/`armeabi`（2017–2018 低端机、部分电视盒子） |
| 症状 | `INSTALL_FAILED_NO_MATCHING_ABIS`；用户会以为是「应用坏了」 |
| 概率 / 影响 | 中 / 高（用户直接流失，且无法自行解决） |
| 处置 | **universal 也救不了**（不含 v7a）。README/Release notes 必须写「仅支持 64 位 ARM（Android 8.0+，2018 年后机型）」，并给出自查方法：`adb shell getprop ro.product.cpu.abilist` 含 `arm64-v8a` 才可装。长期方案见 device-matrix §7 #5（需 v7a ffmpeg 变体 + ADR） |

### 5.2 x86_64 模拟器 / CI（最容易被 split 打破的一环）

| 项 | 内容 |
|---|---|
| 触发 | 启用 `splits` 后，**debug 变体也会拆**；`ci.yml` 里 `verify-apk.sh app/build/outputs/apk/debug/app-debug.apk` 路径不存在 |
| 症状 | CI 红：`test -f "$APK"` 失败 / `upload-artifact` `if-no-files-found: error` |
| 概率 / 影响 | 高（必然发生）/ 中（阻塞所有 PR） |
| 处置 | ① 默认 `isEnableSplit=false`，只有 `release.yml` 传 `-PabiSplits=true`（§4.1）→ CI 完全不受影响；② 若坚持 debug 也拆，则 `ci.yml` 改为 glob + 循环校验，`smoke.yml` 需装 `app-x86_64-debug.apk`；③ **不要**在 `smoke.yml` 里装 universal（它含 arm64，emulator 能装但体积/行为与目标不一致，且会掩盖「x86_64 分片缺失」的问题） |

### 5.3 产物命名 / artifact / 校验

| 风险 | 说明 | 处置 |
|---|---|---|
| 路径硬编码 | `release.yml`、`ci.yml`、`scripts/verify-apk.sh` 都写死了单 APK 路径 | 同提交内全部改为 glob/循环（§4.3） |
| artifact 膨胀 | `audioforge-release-apk` 从 1 个文件变 3 个（~136 MB），artifact 上限 500 MB/2 GB，仍安全但保留期成本↑ | 保持 `retention-days: 30`；不要额外再传 universal 的第二份 |
| 校验漏包 | 只校验了第一个文件 | 循环 + 每个包 `apksigner verify`；`checksums.txt` 行数应为 3 |
| 命名不一致 | Release 资产名与 README 表格不一致 → 用户下错 | README（P6-10）与 release notes 用同一张表 |

### 5.4 GitHub Release 多 APK

| 风险 | 说明 | 处置 |
|---|---|---|
| 用户选错包 | 首要风险。三个文件并列，用户不知道自己的 ABI | ① Release notes 第一行放「不确定就下 universal」；② 资产名把 `arm64-v8a` 放最前但**把 universal 置顶**（GitHub 按上传顺序展示）；③ README 加「如何查 ABI」一节 |
| 存量链接失效 | 老用户/外部文章指向 `audioforge-0.1.0.apk` | **不要删除旧 release 资产**；沿用「每版新增文件」策略；v1.0.0 若改名，在 release notes 说明旧名已弃用 |
| `softprops/action-gh-release` | `files: release/*` 会把 `checksums.txt` 一起传（符合预期） | 无需改；`fail_on_unmatched_files: true` 保证 3 个包都在才成功 |
| Release 体积 | 每版 ~136 MB 资产 | 可接受；GitHub Release 无总容量配额，单文件上限 2 GB |

### 5.5 升级路径 / 切换矩阵

| 从 → 到 | 是否可覆盖安装 | 说明 |
|---|---|---|
| universal(旧版) → universal(新版) | ✅ | 现状路径，最稳 |
| universal → arm64 分片（同版本） | ✅ | 同 `versionCode` + 同签名；`lib/` 少一个 ABI，无残留（`useLegacyPackaging=false` 不解压） |
| arm64 分片 → universal（同版本） | ✅ | 同上 |
| arm64 分片 → arm64 分片（新版本） | ✅ | `versionCode` 递增 |
| 分片/universal → 若误用 `versionCodeOverride` 的下降组合 | ❌ `INSTALL_FAILED_VERSION_DOWNGRADE` | **本方案已排除**（§4.4） |
| 换签名（本地 debug 签名 → CI 签名） | ❌ `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 必须卸载；而卸载会丢 Key/DB → 只在验证时用，且验证机可清 |

**必须真机验证的三条**（`device-matrix` G3 的扩展）：

1. 旧 universal → 新 arm64 分片：覆盖安装成功，打开后**已有项目/Key 仍在**，FFmpeg 自检通过。
2. 新 arm64 分片 → 新 universal：同上反向覆盖。
3. 新 arm64 分片在**未安装过**的设备上全新安装，跑「导入 → 解析 → 渲染 1 章 → 播放」。

### 5.6 其他

| 风险 | 说明 | 处置 |
|---|---|---|
| 16 KB 页 | split 不改变 `.so` 对齐，但多产物时易漏检 | 每个产物都跑 `zipalign -c -P 16 4`；16 KB 设备实测仍归 P6-05/D3 |
| `abiFilters` 与 `splits.include` 不同步 | 静默少包/空 split | §6.2 的 `aapt2 dump badging` 检查；改一处必须改另一处（代码注释已标注） |
| `splits` 与 `bundle` 同时开 | AGP 行为不确定/报错 | 二选一；本仓库只留 `splits` |
| 体积收益低于预期 | 若 R8 未开（P6-01 开关仍未提交），arm64 单包只能到 ~37 MB | 两个任务独立回退，互不阻塞 |
| `libavdevice.so` 等无用库 | 45–56 KB，拆不出来（在 AAR 内） | 忽略；真要省，换裁剪版 ffmpeg（v1.1+） |

---

## 6. 验收

### 6.1 体积对比表模板（实测后填；每列都必须有数字）

> 口径：**下载体积 = Release 资产的字节数**（`du -b` / GitHub 显示值）；`lib/` 体积 = `unzip -l` 中 `lib/` 合计。
> 同一 commit、同一 keystore、同一 `versionCode` 下横向比较，否则无意义。

| # | 产物 | 构建命令/开关 | 下载体积 | `lib/` 合计 | 其中 arm64 | 其中 x86_64 | dex 合计 | 相对 universal | 是否发布 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | universal（基线，v0.1.0 已发布） | `assembleRelease`（现状） | 60.5 MB | 43.9 MiB | 20.4 | 23.5 | — | — | 是（历史） |
| 2 | universal + R8（P6-01 开关后） | `assembleRelease` | | | | | | | 候选 |
| 3 | **arm64-v8a 分片** | `-PabiSplits=true` | 估 36–37 MB | 估 20.4 | 20.4 | 0 | | 估 −38% ~ −41% | **是（首推）** |
| 4 | x86_64 分片 | `-PabiSplits=true` | 估 39–41 MB | 估 23.5 | 0 | 23.5 | | 估 −32% ~ −35% | 是（emulator） |
| 5 | universal（isUniversalApk） | `-PabiSplits=true` | 估 60.5 MB | 43.9 | 20.4 | 23.5 | | 0% | 是（兜底） |
| 6 | arm64 分片 + R8 | `-PabiSplits=true` | 估 30–33 MB | | | | | 估 −45% ~ −50% | 目标态 |
| 7 | （可选）AAB universal via bundletool | `bundleRelease` + bundletool | — | — | — | — | — | 与 #5 同 | 否 |

填表要求：
- 第 3/5/6 行必须来自**同一次 CI 运行**（`release.yml` 一次构建出 3 个包）；
- 若第 3 行相对第 1 行降幅 **< 30%**，先怀疑「`.so` 被压缩了」（检查 `jniLibs.useLegacyPackaging`）或「split 实际没生效（产物里仍有 `lib/x86_64/`）」，不要直接宣布达标。

### 6.2 检查命令（只读，不需要构建）

```bash
SDK="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
BT="$SDK/build-tools/37.0.0"
APK=app/build/outputs/apk/release/app-arm64-v8a-release.apk

# 0) 摘要（包名/版本/ABI）
"$SDK/cmdline-tools/latest/bin/apkanalyzer" apk summary "$APK"
"$SDK/cmdline-tools/latest/bin/apkanalyzer" files list "$APK" | grep '^/lib/'

# 1) 关键：产物里到底有哪些 ABI（arm64 分片应只有 arm64-v8a；universal 应两个都有）
"$BT/aapt2" dump badging "$APK" | grep -E "package:|native-code|sdkVersion|targetSdkVersion"

# 2) 每个 .so 的体积（未压缩大小 + 名称；确认没有混进别的 ABI）
unzip -v "$APK" | grep 'lib/' | awk '{printf "%10s  %s\n", $1, $8}'

# 3) 总量与压缩方式（Stored 说明 .so 未压缩，符合 useLegacyPackaging=false）
unzip -l "$APK" | grep 'lib/' | head -3
unzip -l "$APK" | tail -1
du -h "$APK"; stat -c '%s bytes' "$APK"

# 4) 页面对齐（16 KB）+ 签名：每个产物都要跑
"$BT/zipalign" -c -P 16 4 "$APK"
"$BT/apksigner" verify --verbose "$APK"

# 5) 三包版本码必须相同、且 ABI 集合符合预期
for f in app/build/outputs/apk/release/*.apk; do
  printf '%-40s ' "$(basename "$f")"
  "$BT/aapt2" dump badging "$f" | grep -E "^package:" | sed -E "s/.*versionCode='([0-9]+)'.*/versionCode=\1/" | tr -d '\n'
  printf '  abis='
  "$BT/aapt2" dump badging "$f" | grep -o "native-code: .*" | tr -d '\n'
  echo
done

# 6) 与基线对比（可选，需已装 cmdline-tools；旧版本无此子命令则跳过）
"$SDK/cmdline-tools/latest/bin/apkanalyzer" apk compare \
  /tmp/baseline-universal.apk "$APK" || echo "apkanalyzer apk compare 不可用，改用上面 1)–3)"

# 7) 设备侧：这个包能不能装
adb shell getprop ro.product.cpu.abilist   # 例：arm64-v8a,armeabi-v7a,armeabi
adb install -r "$APK"
adb shell dumpsys package com.estimatefaster.audioforge | grep -E "versionCode|primaryCpuAbi"
```

判读要点：

| 观察 | 含义 |
|---|---|
| `native-code: 'arm64-v8a'` 且 `files list` 只有 `/lib/arm64-v8a/` | 分片正确 |
| universal 里 `native-code` 两个都在 | 兜底包正确 |
| `unzip -v` 里 `.so` 的 Method 是 `Stored`（不是 `Defl:N`） | 符合 `useLegacyPackaging=false`；若被压缩，说明配置被改过 |
| arm64 分片大小 ≈ universal − 23.5 MiB | 降幅符合预期 |
| 三包 `versionCode` 不同 | **违规**，回到 §4.4 |
| `zipalign -P 16` 失败 | 该产物不可发布（16 KB 设备会崩） |

### 6.3 通过判据（全部满足）

1. `-PabiSplits=true` 后一次 `assembleRelease` 产出 3 个 APK，ABI 集合分别为 `{arm64}`、`{x86_64}`、`{arm64,x86_64}`；
2. 三包 `versionCode` 相同、签名相同（`apksigner verify` 全绿）；
3. arm64 单包相对 universal **下降 ≥ 30%**（预期 ~39%）；
4. 每个产物 `zipalign -c -P 16 4` 通过；
5. 真机：arm64 分片全新安装 + 跑完「导入→解析→渲染 1 章→播放」；
6. 真机：universal → arm64 分片覆盖安装，数据与 Key 保留；
7. x86_64 分片可在 CI emulator（API 35、`x86_64`）安装并通过 `FfmpegInstrumentedTest`；
8. `checksums.txt` 3 行、Release 资产名与 README 表一致；
9. 不传 `-PabiSplits` 时行为与今天完全相同（`app-release.apk` 单产物）——这一条保证回退可用。

### 6.4 回滚

| 层级 | 操作 | 影响 |
|---|---|---|
| **一行回退（首选）** | `release.yml` 去掉 `-PabiSplits=true`（或 `isEnableSplit = false`） | 回到单 universal APK；产物名恢复 `app-release.apk`；签名/包名/`versionCode` 语义不变 → **已安装用户无需任何迁移** |
| 代码回退 | 删掉 `splits { }` 块与 `abiSplitsEnabled` | 同上；保留文档与本评估 |
| 发布回退 | Release 中保留旧版资产；必要时在 release notes 指向上一版 `audioforge-<oldver>-universal.apk` | 已装分片的用户装下一版 universal 可覆盖（同签名 + 更高 versionCode） |
| 不变量 | ① 任何时候都必须有一个 ABI 为 `{arm64,x86_64}` 的 universal 产物可下载；② 不删旧 Release 资产；③ 不用 `versionCodeOverride`，所以回退永远不会制造「降级」；④ 回退不删本文（下次直接复用） | — |

回退触发条件（任一）：三包命名/Release 页造成实际用户下错包且无法用文案缓解；分片覆盖安装出现数据丢失（不可接受，属 P0）；
arm64 单包在真机上出现 universal 没有的崩溃（说明 split 逻辑影响了运行，虽然理论上不可能，但要按事实走）。

---

## 7. 未决项与归属

| 项 | 说明 | 归属 |
|---|---|---|
| 落地 `splits` + 属性开关 | 按 §4.2 提交；**同提交**改 `release.yml` 产物路径/校验循环/改名 | P6-02 后续提交（需先过 P6-01 的 R8 开关，避免两个变量同时动） |
| `ci.yml` / `smoke.yml` 是否受影响 | 本方案默认关，**不受影响**；若将来 debug 也拆，必须同步改 | 同上 |
| README/Release 选包表 + 「如何查 ABI」 | 用户下错包是最大体验风险 | P6-10 |
| 32 位（v7a）支持 | 需要新的 ffmpeg 变体 + 版本矩阵评审 | `docs/decisions.md`（ADR 级别），不在 v1 |
| 更激进的体积优化 | 裁剪版 ffmpeg（去掉 `libavdevice`/无用 codec）、baseline profile | v1.1 候选（执行计划 §15「v1.1 候选」已列） |
| `docs/progress.md` 勾选 | 按 r8-evaluation 的同样约定，本任务**不改** progress，避免与并行任务冲突 | 汇总任务 |
| 真机验证（§5.5 三条） | 本环境无 adb 设备（同 device-matrix §4.3 的 BLOCKED），未执行 | P6-09 / P6-11 一并做 |

---

## 8. 参考

- Android：Configure APK splits（`splits.abi` / `universalApk` / `versionCodeOverride`）— <https://developer.android.com/build/configure-apk-splits>
- Android：About AAB（为何 AAB 不能直接安装、bundletool 的作用）— <https://developer.android.com/guide/app-bundle>
- Android：`bundletool build-apks`（`--mode=universal`）— <https://developer.android.com/tools/bundletool>
- Android：`apkanalyzer`（`apk summary` / `files list` / `apk compare`）— <https://developer.android.com/tools/apkanalyzer>
- Android：16 KB 页大小与 `zipalign -P 16` — <https://developer.android.com/guide/practices/page-sizes>
- 项目内：`docs/android-execution-plan.md` §3（ADR-005）、§6.4、§14.6、§15 P6-02/§16.2；`docs/r8-evaluation.md` §2.1/§7；`docs/device-matrix.md` §1.3/§7；`scripts/verify-apk.sh`；`.github/workflows/release.yml`
