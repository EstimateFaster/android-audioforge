# AudioForge — Android

[![Android CI](https://github.com/EstimateFaster/android-audioforge/actions/workflows/ci.yml/badge.svg)](https://github.com/EstimateFaster/android-audioforge/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/EstimateFaster/android-audioforge)](https://github.com/EstimateFaster/android-audioforge/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> 把小说 txt 变成多人演播有声书。**StoryForge 的 Android 原生重构，不是套壳。**
>
> Kotlin + Jetpack Compose + Material 3 Expressive + FFmpegKit + WorkManager + Room + Media3。

- 仓库：**https://github.com/EstimateFaster/android-audioforge**
- 下载：**[GitHub Releases](https://github.com/EstimateFaster/android-audioforge/releases/latest)**（只侧载，不上架 Google Play）
- 发布说明模板：[docs/release-notes.md](docs/release-notes.md)
- 当前文档对应 **v1.0.0**；实际可安装版本以 Releases 页为准。

> **已知未验证项**：32 位设备不支持、真机后台/国产 ROM 未完成验收、真实 LLM/Fish API 链路未在 CI 跑过（均按 BLOCKED/不支持记录）。安装前请先看 [已知问题与限制](#10-已知问题与限制) 和 [docs/release-notes.md](docs/release-notes.md)。

---

## 1. 产品简介

AudioForge 在手机上完成「小说 txt → 章节 → 角色/剧本 → 多人语音 → 章节音频」的整条链路：

- **导入**：读取 txt，自动识别 UTF-8/BOM、GBK/GB18030、UTF-16、Big5 等编码并切章；
- **解析**：调用你配置的 LLM，把旁白与角色台词拆成可编辑剧本，并做覆盖率校验；
- **配音**：调用 Fish Audio 合成语音，按角色音色与语速/音量/音调做后处理；
- **批量**：WorkManager + `dataSync` 前台服务跑长任务，支持停止、断点续跑、通知与恢复；
- **播放**：Media3 播放器，支持章节队列、倍速、睡眠定时、后台/通知控制；
- **导出**：把章节成品导出为 `.m4a`，或通过系统分享给其他应用；
- **管理**：项目库、任务页、人物音色、存储统计/清理、设置、关于/诊断/开源许可。

它不依赖 AudioForge 的服务器：LLM 与 TTS 请求从手机直连**你自己配置的**服务。

### 不是什么

- **不是 WebView 套壳**：界面、任务、音频处理均为原生实现。
- **不是在线服务**：没有账号体系、没有云同步、没有云端转码。
- **不是应用商店产品**：只从 GitHub Releases 侧载，不提供 Google Play 自动更新。

---

## 2. BYOK：Key 自备

AudioForge 不内置、不转售、不代理任何 API Key。你需要自己准备两类 Key：

| Key | 用途 | 默认地址 / 模型（可改） |
|---|---|---|
| **LLM Key** | 解析小说、拆分角色与生成剧本；需要 OpenAI 兼容接口 | `https://dashscope.aliyuncs.com/compatible-mode/v1` / `qwen3.7-flash` |
| **Fish Audio Key** | 把剧本合成为语音（TTS） | `https://api.fish.audio` / `s2.1-pro-free` |

- Key 只保存在本机；LLM/TTS 请求直接从手机发往你填写的 Base URL。
- 费用、额度、限流、服务条款由你与对应服务商结算，AudioForge 不参与计费。
- 你可以换用其他 OpenAI 兼容网关或其他 Fish 兼容地址；只要 Base URL、模型名与 Key 匹配即可。
- **没有 Key 也能安装、浏览、导入 txt**；解析与生成会在开始时提示补充 Key。

### Key 如何保存

- Key 经 Android Keystore 保护的 **AES-256-GCM** 加密后写入本机 DataStore，磁盘上只有密文。
- 设置页只显示 `••••` + 末 4 位；输入框不会进入 `SavedStateHandle`，也不会写入日志。
- 应用关闭备份（`allowBackup=false`），换机/云备份不会带走 Key。
- 清除 Key 后，相关请求会立即失败，直到重新设置。

### 「测试连接」说明

Onboarding / 设置里的「测试连接」**只做本地 Key 格式校验，不会发起真实计费请求**。所以：

- 测试通过 ≠ Key 一定有效、额度一定够；
- 真正的 401/402/429 会在第一次解析或生成时暴露；
- 遇到失败先看 [常见问题](#8-常见问题) 里的 401/402/429 处理。

---

## 3. 只侧载，不上架

AudioForge **不上架 Google Play**，也不提供应用内自动更新。

- 正式 APK 只由 GitHub Actions 的 Release workflow 构建并签名；
- 每个 Release 附带 `audioforge-<version>.apk` 与 `checksums.txt`（SHA-256）；
- 更新方式是下载新 APK 覆盖安装，不是商店推送；
- **只从官方仓库下载**：`https://github.com/EstimateFaster/android-audioforge/releases`。不要安装第三方网盘、论坛或“破解/重打包”APK——签名和内容都可能被篡改。

---

## 4. 隐私与数据

### 数据留在本机

小说原文、章节、剧本、角色、生成音频、任务记录与设置都在应用私有目录（`filesDir` / Room / DataStore）中：

- 没有账号、没有云同步、没有广告、没有分析/崩溃上报 SDK；
- 卸载应用或「清除数据」会删除项目、音频与 Key，**且无法恢复**；
- 需要保留的内容请先导出音频，或自行保留原始 txt。

### 会联网，但只连你配置的服务

- 解析时，相关章节文本会发送到你配置的 **LLM Base URL**；
- 合成时，剧本片段会发送到你配置的 **Fish Audio Base URL**；
- 请求不经过 AudioForge 的中转服务器；
- 这些服务商如何处理文本/音频，取决于它们各自的隐私政策，请自行评估。

应用运行时只允许 HTTPS（`usesCleartextTraffic=false` + network security config），`http://` 地址会网络失败。

### 权限

| 权限 | 用途 |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | 调用 LLM/TTS、判断网络 |
| `POST_NOTIFICATIONS` | 生成进度、完成/失败、播放控制通知（Android 13+ 运行时权限） |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | 后台生成长任务 |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | 后台播放 |
| `WAKE_LOCK` | 任务执行期间保持 CPU |
| `RECEIVE_BOOT_COMPLETED` | 重启后由 WorkManager 重新调度（不是开机自启前台服务） |

没有存储、通讯录、定位等敏感权限；导出走系统 MediaStore 或 FileProvider 临时授权。

### 导出的音频是“共享”的

Android 10+ 导出的 `.m4a` 会写入系统 `Music/AudioForge/<书名>/`，属于共享媒体目录，其他应用可能读取。敏感内容请自行管理或导出后移走。

---

## 5. 安装

### 5.1 系统要求

| 项 | 要求 |
|---|---|
| Android | **8.0（API 26）及以上**；targetSdk 36 |
| ABI | **arm64-v8a**（绝大多数手机）或 **x86_64**（模拟器/部分 x86 设备） |
| 存储 | 建议至少保留 1 GB；生成前会做空间预检（写入后至少保留 500 MB） |
| 网络 | 能访问你配置的 LLM/TTS 服务；运行时仅 HTTPS |
| 账号 | 无需注册；需要自备 LLM 与 Fish Audio Key |

**32 位不支持**：APK 不含 `armeabi-v7a` / `x86`。32 位设备安装会报 `INSTALL_FAILED_NO_MATCHING_ABIS`，请换 arm64 设备。详见 [docs/device-matrix.md](docs/device-matrix.md) §1.3。

### 5.2 下载

1. 打开 **[Releases](https://github.com/EstimateFaster/android-audioforge/releases/latest)**；
2. 下载 `audioforge-<version>.apk` 与 `checksums.txt`；
3. 历史版本 `v0.1.0` 只是早期工程骨架，**不建议日常使用**；请优先使用最新正式版。

> Actions 里的 debug artifact 是 CI 产物（包名带 `.debug`，保留 14 天），不是给普通用户的安装包。

### 5.3 校验（推荐）

```bash
# Linux
sha256sum -c checksums.txt

# macOS
shasum -a 256 -c checksums.txt

# Windows PowerShell
Get-FileHash .\audioforge-<version>.apk -Algorithm SHA256
```

把输出与 `checksums.txt`、Release 说明里的 SHA-256 对比。还可以校验签名：

```bash
# apksigner 在 Android SDK build-tools 里
apksigner verify --print-certs audioforge-<version>.apk
```

签名证书 SHA-256 应与上一版**一致**（覆盖升级的前提）。具体命令与产物说明见 [docs/release-process.md](docs/release-process.md) §5。

### 5.4 允许未知来源

Android 不允许直接安装浏览器/文件管理器下载的 APK，需要为对应应用授权：

1. 点击下载好的 APK；
2. 系统提示「不允许从此来源安装」时，进入设置；
3. 打开 **允许来自此来源**（不同 ROM 路径：设置 → 应用 → 特殊权限 → 安装未知应用 → 选择浏览器/文件管理器）；
4. 返回安装器，确认安装。

只对你信任的来源（浏览器/文件管理器）授权；安装完成后可以关掉。

### 5.5 安装 / ADB

```bash
# 覆盖安装（保留数据，需同签名）
adb install -r audioforge-<version>.apk

# 首次安装
adb install audioforge-<version>.apk
```

也可以直接在文件管理器里点 APK 安装。debug 包名是 `com.estimatefaster.audioforge.debug`，与正式包**互相独立**，可以共存，但数据不互通。

### 5.6 安装失败排查

| 现象 | 可能原因 | 处理 |
|---|---|---|
| `INSTALL_FAILED_NO_MATCHING_ABIS` | 32 位设备/模拟器 | 换 arm64-v8a 真机；模拟器用 x86_64 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / 签名不一致 | 旧包来自不同签名（debug/第三方重打包/换过 keystore） | 先导出数据，再卸载旧包重装；不要混装第三方包 |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | 安装的版本比已装版本旧 | 安装更新版本；降级必须卸载（会丢数据） |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | 空间不足 | 清理空间；APK 本身约 60 MB |
| 解析包错误/文件损坏 | 下载不完整 | 重新下载并核对 SHA-256 |
| Play Protect 提示风险 | 侧载应用常见提示 | 确认来源、校验和、签名后再决定是否继续 |

---

## 6. 首次配置

### 6.1 Onboarding（3 步）

首次启动会进入「首次设置」：

1. **欢迎**：说明 BYOK、只侧载、数据本地；
2. **配置 API Key**：LLM 与 Fish Audio 两组 Key / Base URL / 模型；
3. **通知与电池**：说明权限与后台限制，不强制申请。

可以点「跳过」或「稍后设置」，之后在 **设置 → API** 补配。没有 Key 不影响浏览和导入。

### 6.2 配置 LLM

- **Base URL**：OpenAI 兼容地址。默认是 DashScope 兼容模式；换其他网关时填写到 `/v1` 这一层。
- **模型**：默认 `qwen3.7-flash`；必须与你的 Key/网关支持的模型一致。
- **API Key**：粘贴后保存，只显示末 4 位。
- 点「测试连接」只校验本地格式；真实可用性在第一次解析时验证。

### 6.3 配置 Fish Audio

- **Base URL**：默认 `https://api.fish.audio`；使用兼容代理时改成代理地址。
- **TTS 模型**：默认 `s2.1-pro-free`；按你的账号可用模型填写。
- **API Key**：保存后同样只显示末 4 位。

### 6.4 模型与音色

- 内置 **63 个音色**，按旁白 / 男声 / 女声 / 其他分组，可按类别、label 或 voice ID 搜索。
- 每个角色可以单独换音色；音色可「锁定」，锁定后重渲染会替换该角色全部片段，应用会二次确认。
- 角色微调支持语速（0.5–2.0）、音量（0.5–2.0）、音调（-12–12）；角色设置优先于全局默认值。
- 全局默认音色类别是 `narrator_male`；可在 **设置 → 生成** 修改。

### 6.5 通知与电池优化

- **通知**：Android 13+ 需要通知权限（应用会提示；也可在系统设置里允许）。拒绝后任务仍会运行，但通知栏不显示进度。
- **电池优化**：部分厂商（Honor/EMUI、MIUI/HyperOS、ColorOS/OriginOS）会限制后台。建议把 AudioForge 加入「不优化/允许后台运行/允许自启动」。
- 应用内目前只提供**文字指引**，没有一键跳转电池白名单；需要手动到系统设置里找。详见 [docs/device-matrix.md](docs/device-matrix.md) §7。

### 6.6 默认网络/生成策略

| 设置 | 默认值 | 说明 |
|---|---|---|
| 仅 Wi-Fi | **开** | 移动网络下暂停合成/下载；要用流量请在设置里关闭 |
| 仅充电 | 关 | 未充电时暂停生成 |
| 低电量阈值 | 15% | 低于阈值时暂停 |
| 过热降级 | 开 | 设备过热时降并发或暂停 |
| 每批章节数 | 5 | 范围 1–20 |
| LLM 并发 | 1 | 范围 1–4 |
| TTS 并发 | 4 | 范围 1–8 |
| 片段缓存保留 | 7 天 | 0 表示不自动删除 |
| 最低覆盖率 | 0.97 | 低于阈值的解析会告警/失败 |

这些设置应用到**新批次**；已经生成的音频不受影响。

---

## 7. 使用流程

底部导航是 **项目 / 任务 / 设置**。主流程：

> 导入 → 解析 → 检查剧本/角色 → 生成 → 播放 / 导出

### 7.1 导入小说

1. 打开「项目」→「导入」；
2. 选择 `.txt` 文件（单文件最大 **64 MB**）；
3. 应用会复制文件到私有目录，并自动识别编码、切分章节；
4. 编码识别失败时，按提示手动选择编码重试（选错会再次失败）；
5. 同名项目不会覆盖：会提示打开已有项目，或改名后重新导入。

支持 UTF-8、UTF-8 BOM、GBK/GB18030、UTF-16LE/BE、Big5 等常见编码；只接受 txt。

### 7.2 解析

- 从项目页开始批次，模式可选 **「解析 + 生成」** 或 **「仅解析」**；
- 也可以在章节列表对单章「快速解析」；
- LLM 会把旁白/角色台词拆成剧本片段，并做覆盖率校验；
- 失败或告警的章节会在章节列表标出，可重试；
- 超长章节会被守卫拒绝并提示（避免一次请求过大）。

### 7.3 检查剧本与角色

- **剧本页**：按角色筛选/搜索、查看片段、编辑台词、播放跟随；
- **人物音色页**：查看角色列表、换音色、微调语速/音量/音调；
- 解析告警通常意味着原文有未覆盖内容或模型输出不完整；先重试，再考虑调整模型或覆盖率。

### 7.4 生成

- 在项目页选择批次/范围后「开始」；
- 默认每批 5 章（最大 20），TTS 并发默认 4；
- 每个片段成功后会写缓存；失败按 429/5xx 重试，401/402 不重试；
- 停止会在当前片段收尾时停下，已完成章节保留；
- 生成期间有前台服务通知，可显示进度/停止；锁屏后继续跑（受厂商后台策略影响）；
- 进程被杀或重启后，WorkManager 会重新调度；重开应用可在任务页继续。

### 7.5 播放

- 播放器支持播放/暂停、上一章/下一章、上一句/下一句、倍速、睡眠定时；
- 章节列表可展开/收起，显示当前播放章节；
- 后台播放有媒体通知；支持锁屏/蓝牙媒体控制（取决于系统）；
- 没有音频的章节会显示「暂无音频」，先回剧本页生成。

### 7.6 导出 / 分享

- 在章节操作里导出单章成品音频，文件为 `.m4a`；
- **Android 10+**：写入 `Music/AudioForge/<书名>/<书名>-第001章.m4a`；
- **Android 8/9**：写入应用外部目录 `Android/data/com.estimatefaster.audioforge/files/Music/AudioForge/`，通过系统分享面板发给其他应用；
- 分享使用只读 URI 授权，不会给对方写权限。

### 7.7 任务与存储

- **任务页**：当前任务、队列、最近结果、进度/预计剩余、停止、失败原因；
- **存储页**：应用私有目录的空间概览、按项目占用、保留期（7–365 天）、清理孤儿（>6 小时）与过期音频；
- 清理只删除**不再被任何项目引用**的文件；仍被引用的会跳过；
- 清理不可恢复，确认前先导出需要保留的音频。

---

## 8. 常见问题

### 没有 Key 能用吗？

可以安装、浏览、导入 txt、查看已有项目；**解析和生成**需要对应 Key。Onboarding 可以跳过，之后在 **设置 → API** 里补。开始生成时如果缺 Key，会提示补充。

### “测试连接”通过了，为什么生成还是失败？

「测试连接」只做本地 Key 格式校验，不发起真实请求。真实失败常见于：

- **401**：Key 错误、过期、被撤销，或 Base URL 与 Key 不匹配；
- **402**：额度/余额不足；
- **429**：限流或并发过高；
- **5xx / 超时**：服务商侧问题或网络问题。

先核对服务商控制台的 Key、额度、模型名与 Base URL，再重试。

### 解析失败 / 覆盖率不达标怎么办？

按顺序排查：

1. 确认 LLM Key、Base URL、模型名可用（用服务商控制台或 curl 验证）；
2. 在章节列表重试该章；
3. 换一个更稳定的模型/网关；
4. 如果章节超长，先拆分原文（v1 对超长章节有守卫，不会静默降级）；
5. 只有在确认是覆盖率阈值导致误判时，才考虑降低 **设置 → 生成 → 最低覆盖率**；降低会增加漏句风险。

### 后台任务被杀 / 停止？

- 生成使用 `dataSync` 前台服务 + 常驻通知；Android 15+ 有 6 小时/24 小时上限，应用会在约 5 小时主动收尾，下一批继续。
- OEM 的「自启动/省电/后台清理」是主要原因。请把 AudioForge 加入电池优化白名单，并在最近任务里锁定（MIUI/ColorOS 等）。
- 「强行停止」会清掉任务，**不会自动恢复**；重新打开应用后可继续。
- 普通杀进程/重启后，WorkManager 会重新调度；重开应用可在任务页继续。
- 默认「仅 Wi-Fi」开着；用移动网络时任务会暂停，请在设置里关闭。

### 通知不显示？

- Android 13+ 需要 `POST_NOTIFICATIONS` 运行时权限；拒绝后任务仍运行，只是没有通知进度；
- 检查系统设置里 AudioForge 的通知总开关和「生成/播放」渠道是否被单独关闭；
- 部分 ROM 的「锁屏通知」「横幅」是二级开关。

### 存储占用大 / 怎么清理？

- 在 **设置 → 存储** 查看应用私有目录占用、按项目分布；
- 可清理孤儿文件（无引用且超过 6 小时）和超过保留期的项目音频；
- 仍被项目引用的文件不会被删；
- 导出的 `Music/AudioForge/` 副本不在应用私有目录统计内，需要自行在系统文件管理器删除；
- 空间不足时生成前会拒绝并提示（要求写入后至少保留 500 MB）。

### 网络错误 / 429 限流？

- 应用只允许 HTTPS；`http://` 地址会失败，请改成 `https://`；
- 429/5xx/超时会按指数退避重试（尊重 `Retry-After`；LLM 默认重试 3 次、Fish 默认 4 次，均不含首次请求）；401/402 不重试；
- 限流时降低 **LLM 并发 / TTS 并发**，或错峰重试；
- 检查服务商状态页、账号额度与网络代理。

### 能自动更新 / 上架吗？

不会。只从 GitHub Releases 手动下载新 APK 覆盖安装。没有 Google Play 自动更新，也没有应用内更新器。

### 数据能备份 / 换机吗？

- 应用关闭了系统备份（`allowBackup=false`），云备份/换机迁移不会带走项目、音频或 Key；
- 需要保留的章节请先导出 `.m4a`，原始 txt 请自行保留；
- 换机后重新安装、重新配置 Key，再导入 txt。

### 支持 32 位 / 平板 / Android 8 吗？

- **32 位（armeabi-v7a/x86）：不支持**，安装会报 `NO_MATCHING_ABIS`；
- **Android 8.0+**：声明支持，但 API 26/27 边界没有真机验收（见设备矩阵）；
- **平板/折叠**：界面有自适应壳，但 v1 未做平板专项验收，按手机体验为准。

### APK 为什么这么大？

当前是 **universal APK**，同时包含 `arm64-v8a` + `x86_64` 原生库（FFmpegKit），约 60 MB。R8 与 ABI split 仍在评估（P6-01/P6-02），落地后体积会变化；以 Release 实际文件为准。

---

## 9. 升级、回滚与卸载

### 9.1 覆盖升级

1. 从 Releases 下载**更新版本**的 APK；
2. 直接点 APK 安装，选择「更新」；或 `adb install -r`；
3. 覆盖安装要求：**同一签名**，且新 `versionCode` ≥ 已安装版本；
4. 覆盖安装会保留项目、音频与 Key；仍建议先导出重要音频。

`versionCode` 由 GitHub Actions 的 `run_number` 自动递增，官方不会复用旧版本号。

### 9.2 卸载会丢数据

卸载或「清除数据」会删除：

- 项目、章节、剧本、角色；
- 生成音频与缓存；
- 加密保存的 LLM/Fish Key。

**没有云备份，无法恢复。** 卸载前请先导出需要的章节音频。

### 9.3 降级限制

Android 不允许安装比已装版本旧的 APK（`INSTALL_FAILED_VERSION_DOWNGRADE`）。官方不提供降级包：

- 发现新版本有问题时，正确做法是等/装**更新的 patch 版本**；
- 如果必须回旧版，只能卸载后重装，**数据会丢**；
- 不要为了降级去装第三方重打包 APK。

### 9.4 签名不一致

如果覆盖安装提示签名不一致：

- 旧包可能来自 debug 构建、第三方重打包，或维护者更换过签名 keystore；
- 只能先导出数据 → 卸载旧包 → 安装新包；
- 不要混装 debug 包（`com.estimatefaster.audioforge.debug`）与正式包，二者数据独立。

### 9.5 发布回滚（维护者/用户须知）

- 发现严重问题时，维护者会发布新的 patch 版本，并在 Release 说明中标注问题版本与建议版本；
- 不会指导用户安装来路不明的旧包/重打包包；
- 旧 Release 的 tag、APK 与 `checksums.txt` 保持可追溯；如某版本被标记为 pre-release/draft，请以最新正式版为准。

---

## 10. 已知问题与限制

| # | 问题 | 状态 | 说明 / 绕过 |
|---|---|---|---|
| 1 | **32 位设备不支持** | 不支持（BLOCKED） | APK 只含 `arm64-v8a` + `x86_64`，无 `armeabi-v7a`/`x86`。32 位机会报 `INSTALL_FAILED_NO_MATCHING_ABIS`；见 [device-matrix §1.3](docs/device-matrix.md) |
| 2 | **真机后台/国产 ROM 未完成验收** | BLOCKED（P3-10 / P6-05） | 只在 emulator 验证过；Honor/EMUI、Pixel、MIUI/HyperOS、ColorOS 的锁屏/杀进程/重启/Doze 未实测。请加电池白名单；失败请带 logcat 反馈。见 [phase3-gate](docs/phase3-gate.md)、[device-matrix](docs/device-matrix.md) |
| 3 | **真实 LLM/Fish API 链路未在 CI 跑过** | BLOCKED（P2-18） | CI 没有用户 Key；Onboarding「测试连接」只做本地格式校验。首次真实请求可能遇到 401/402/429。见 [phase2-gate](docs/phase2-gate.md) |
| 4 | **APK 较大（约 60 MB）** | 已知限制（P6-01/P6-02 未落地） | 当前 universal APK，未开 R8/资源压缩、未做 ABI split；评估见 [r8-evaluation](docs/r8-evaluation.md)、[abi-split-evaluation](docs/abi-split-evaluation.md) |
| 5 | **电池白名单没有一键跳转** | 已知 UX 缺口 | 应用内只有文字说明，需要手动去系统设置；见 [device-matrix §7](docs/device-matrix.md) |
| 6 | **无云同步/无系统备份/无自动更新** | 产品设计 | 数据只在本机；卸载即丢失；更新靠手动下载 APK |
| 7 | **MIUI/HyperOS、ColorOS、16 KB 页设备未验证** | 未验证（BLOCKED） | 属于设备矩阵未验证项；没有设备/证据，不写成“通过” |

> 已知问题的最新证据与解除条件以 [docs/release-notes.md](docs/release-notes.md)、[docs/device-matrix.md](docs/device-matrix.md)、[docs/phase2-gate.md](docs/phase2-gate.md)、[docs/phase3-gate.md](docs/phase3-gate.md) 为准。

---

## 11. 反馈与安全

- **Bug / 功能请求**：[GitHub Issues](https://github.com/EstimateFaster/android-audioforge/issues)
- 反馈请附：应用版本（设置 → 关于）、构建类型（release/debug）、Android/ROM 版本、设备型号、复现步骤、相关 logcat 片段。
- **不要**在 issue/截图里贴 API Key、Authorization 头、小说正文或未脱敏的音频。
- 安全问题请不要公开贴利用细节；优先通过仓库的 Security 渠道（若启用）私下联系维护者，并说明影响版本。

---

## 12. 开发与构建

> 本仓库硬规则：**release APK 只由 GitHub Actions 产出**；本地只用于编译、单测、lint、调试。不要本地构建 release 作为发布产物，也不要在本地跑 CI。

### 12.1 版本矩阵（摘要）

- AGP 9.5.1 / Gradle 9.9.0 / JDK 17 / Kotlin 2.2.10
- `compileSdk 37` / `targetSdk 36` / `minSdk 26`
- Compose BOM 2026.09.00 / material3 1.5.0-rc01
- Room 2.8.5 + KSP 2.2.10-2.0.2、WorkManager 2.12.0、Media3 1.11.1、OkHttp 5.5.1、DataStore 1.3.0、Paging 3.5.3
- FFmpegKit `dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.9`
- ABI：`arm64-v8a` + `x86_64`（32 位不支持）

不要擅自升级/降级版本矩阵；变更先写 [docs/decisions.md](docs/decisions.md) 并跑验收。必须保留 `android.disallowKotlinSourceSets=false`；`kotlinx-serialization-json` 用 1.9.0。

### 12.2 本地构建（仅 debug）

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

要求 SDK：`platforms;android-37.0`、`build-tools;37.0.0`。`local.properties`、keystore、`.env`、API Key **一律不入库**。

### 12.3 CI / Release

- `.github/workflows/ci.yml`：push/PR → lint + unit test + debug APK + APK 校验；
- `.github/workflows/release.yml`：push tag `v*` 或手动 dispatch → 签名 release APK + `checksums.txt` + GitHub Release；
- 发布需要 4 个 Secrets（**只写名称**）：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`；配置方法见 [docs/DSH-HANDOFF.md](docs/DSH-HANDOFF.md)；
- 发布/签名/回滚细节见 [docs/release-process.md](docs/release-process.md)；安全复查见 [docs/security-review.md](docs/security-review.md)。

### 12.4 文档与 AI 入口

把仓库地址丢给 AI，并让它先读：

1. [AGENTS.md](AGENTS.md) — 硬规则与入口
2. [docs/android-execution-plan.md](docs/android-execution-plan.md) — 唯一执行计划（101 个任务）
3. [docs/android-port-feasibility.md](docs/android-port-feasibility.md) — 勘探/可行性
4. [docs/progress.md](docs/progress.md) — 进度记录（可能滞后，以提交/CI 为准）
5. [docs/DSH-HANDOFF.md](docs/DSH-HANDOFF.md) — 可直接复制的提示词与交接

### 12.5 贡献

- 一个任务一个提交，提交信息用 Conventional Commits；
- 不提交密钥、`*.jks`、`keystore.properties`、`.env`、`local.properties`、构建产物或大文件；
- 改代码/配置后确保 CI 绿，并同步更新相关文档（README、发布说明、进度等）；
- 新用户可见文案应进 `res/values/strings.xml`（中文）。

---

## 13. 许可

- 本仓库：**MIT**，见 [LICENSE](LICENSE)；
- 上游 [StoryForge](https://github.com/EstimateFaster/storyforge)：MIT；
- FFmpegKit：**LGPL-3.0**；应用内「关于 → 开源许可」提供第三方组件与许可文本；
- LLM / Fish Audio 服务由其各自提供方授权与计费，与本仓库许可无关。
