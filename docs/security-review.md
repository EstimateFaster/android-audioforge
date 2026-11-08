# 安全复查（P6-08）

> 任务：P6-08（执行计划 §13「安全、隐私与合规」/ §15 Phase 6）
> 注：任务描述里的「§14 安全/加固」对应现行计划的 **§13**；§14 是「测试与验收」。
> 分支：`docs/p6-08-security`（基于 `main` 8df1b89）
> 日期：2026-11-08（Asia/Shanghai）
> 方式：源码与配置的**静态审阅** + 只读命令（`git ls-files`、`grep`、读取 XML/Manifest）。
> 约束：按仓库规则**未在本机执行 Gradle / lint / CI**；动态验收项留在 §11，由 CI 与真机清单完成。

## 0. 结论

| 等级 | 数量 | 说明 |
|---|---|---|
| **P0**（阻断，立即修） | **0** | 未发现明文密钥落盘、明文 HTTP 放行、组件越权读写或可导出路径穿越 |
| **P1**（发布前应修） | **2** | CI Action 未 pin SHA（release 可读签名密钥）；`LlmConfig.toString()` 含 apiKey |
| **P2**（加固/后续） | **13**（其中 2 项本轮已修） | FLAG_SECURE、http 地址、错误体进日志、MediaSession 控制器、FileProvider 范围等 |

**总体判定：通过（无 P0），带 P1/P2 修复项。**
密钥链路（Keystore AES-GCM → 密文 DataStore → 无日志/无备份）与文件路径防护（相对路径 + 规范化 + 越界校验 + 导出名清洗）是本次复查中质量最高的两部分；主要缺口集中在 CI 供应链、Release 日志收敛与少数「能用但不够严」的默认值上。

## 1. 范围与方法

**审阅文件（任务指定 + 直接相关）**

- `docs/android-execution-plan.md` §13、§15 P6-08
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/.../core/settings/SecureKeyStore.kt`、`KeyCodec.kt`、`SettingsRepository.kt`、`SettingsValidator.kt`
- `app/src/main/res/xml/file_paths.xml`、`network_security_config.xml`、`data_extraction_rules.xml`
- `.github/workflows/ci.yml`、`release.yml`、`smoke.yml`
- 关联：`core/network/LlmClient.kt`/`LlmModels.kt`、`core/tts/FishTtsClient.kt`、`core/storage/{ProjectFiles,AudioStorage,ExportManager}.kt`、`core/model/{PipelineError,ErrorMapper}.kt`、`app/build.gradle.kts`、`app/proguard-rules.pro`、`scripts/verify-apk.sh`、`MainActivity.kt`、`ui/navigation/AppDeepLink.kt`、`AppNavHost.kt`、`playback/AudioForgeMediaSessionService.kt`、`domain/jobs/GenerationNotifications*.kt`、`feature/onboarding/*`、`feature/settings/*`、`.gitignore`

**已执行的只读检查**

- `git ls-files` 过滤 `*.jks` / `*.keystore` / `keystore.properties` / `.env` / `local.properties`：**无密钥类文件入库**（仅 `gradle.properties`、wrapper properties）。
- 全仓 `grep` `Log.` / `println` / `printStackTrace` / `Timber` / `BuildConfig` / `apiKey`：见 §7，未发现 Key/Authorization/请求体打印。
- 全仓 `grep` `TrustManager` / `hostnameVerifier` / `CertificatePinner` / `OkHttpClient`：未发现自定义 TLS 信任逻辑。
- 全仓 `grep` `FLAG_SECURE` / `PendingIntent` / `FileProvider` / `exported`：见 §4、§7。

## 2. 已通过项（正面结论）

| 领域 | 检查点 | 证据与结论 |
|---|---|---|
| 密钥 | Key 只以 Keystore AES-256-GCM 密文进 DataStore | `SecureKeyStore.kt:45-56`（先加密再写）、`KeyCodec.kt:50-85`（AES/GCM/NoPadding、12B 随机 IV、128-bit tag、AAD=key 名）；`Base64(iv‖ct)`，alias `audioforge_api_keys_v1` 不可导出 |
| 密钥 | 普通设置 DataStore 不存 Key | `SettingsRepository.kt:202-230` 只写 baseUrl/model/并发等；Key 字段不存在于 `AppSettings` |
| 密钥 | Key 不落日志 | `SecureKeyStore.kt:32` 明确不打印；`LlmClient.kt:130` 仅写入 Authorization 头；`FishTtsClient.kt:48-51` `toString()` 已掩码；ViewModel 无日志 |
| 密钥 | 无 BuildConfig / assets / strings 硬编码 Key | `buildConfig=true` 但无 `buildConfigField` 密钥；assets 仅提示词与音色池 |
| 密钥 | UI 只显示末 4 位、输入框密码化 | `SettingsUiState.kt:65-103`（≤4 位不显示）、`SettingsScreen.kt:117`（`remember` 非 `rememberSaveable`）、`SettingsScreen.kt:926`、`OnboardingScreen.kt:413` |
| 密钥 | release keystore 只在 CI secrets | `build.gradle.kts:10-15,35-45`（env 或本地 `keystore.properties`）；`.gitignore:12-16` 忽略 `*.jks`/`keystore.properties`/`.env`；`release.yml:42-58` 从 `secrets.ANDROID_KEYSTORE_*` 解码到 `$RUNNER_TEMP` 并 `chmod 600` |
| 备份 | 禁备份 | `AndroidManifest.xml:16-18` `allowBackup=false`、`fullBackupContent=false`、`dataExtractionRules`；规则排除 sharedpref/database/file；本轮补 `external` 与 `device-transfer` 的 `file`/`external` |
| 组件 | 非必要组件不导出 | `AndroidManifest.xml:68-70`（接收器 `exported=false`）、`72-80`（FileProvider `exported=false` + `grantUriPermissions`） |
| 深链 | 外部深链不可达且解析严格 | Manifest 无 `audioforge` scheme 的 intent-filter；`AppDeepLink.kt:32-46` 只接受 `audioforge://project/<正整数>`，拒绝 query/fragment/多余路径；`AppNavHost.kt:68-71` 仅供内部导航 |
| 网络 | 禁明文 | `network_security_config.xml:3-7` `cleartextTrafficPermitted=false` + 仅系统 CA；`AndroidManifest.xml:23` 显式 `usesCleartextTraffic="false"`（本轮补） |
| 网络 | 不自定义证书校验/不做 pinning | 无 `TrustManager`/`hostnameVerifier`/`CertificatePinner`；符合 §13.2「Fish/DashScope 轮换不做固定 pinning」 |
| 网络 | 超时/退避/取消 | LLM 10s/120s（`LlmModels.kt:16-17`）；Fish 10s/120s/150s（`FishTtsClient.kt:42-44`）；指数退避上限 30s + jitter（`LlmClient.kt:178-182,212`；`FishTtsClient.kt:118-145`）；取消时 `call.cancel()` |
| 网络 | 错误文案不含 Key/URL | `PipelineError.kt` 的 `userMessage` 均为固定中文；`ErrorMapper.kt:33-36` 规定 cause 只进日志；`SettingsViewModel.kt:209-210` 不拼 Key |
| 存储 | 路径穿越防护 | `ProjectFiles.kt:274-289` 拒绝绝对路径、`.`/`..`、空段、NUL、Windows 盘符；`291-298` canonical 前缀校验；DB 只存相对路径 |
| 存储 | 导出名清洗 | `ProjectFiles.kt:359-368` 替换 `\ / : * ? " < > \|` 与控制字符、去尾部点、截断 80 字符；`ExportManager.kt:107-108` 用清洗后的标题作 MediaStore 相对路径 |
| 存储 | 导出 MIME 与授权 | `ExportManager.kt:180` `audio/mp4`；`87-96` `ClipData` + `EXTRA_STREAM` + **只读** `FLAG_GRANT_READ_URI_PERMISSION` |
| 权限 | 无存储/通讯录等宽权限 | Manifest 仅 INTERNET、NETWORK_STATE、3 个 FGS、POST_NOTIFICATIONS、WAKE_LOCK、BOOT_COMPLETED；无 `MANAGE_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` |
| 调试 | release 不可调试、debug 包名隔离 | Manifest 无 `android:debuggable`；`build.gradle.kts:48-51` debug 仅加 `applicationIdSuffix=".debug"` 与 `-debug` 版本后缀 |
| 隐私 | 无分析/广告/崩溃 SDK | 依赖清单（`build.gradle.kts:109-162`）无此类 SDK |
| CI | secrets 不暴露给 PR 流程 | `ci.yml`、`smoke.yml` 无 `secrets.`；仅 `release.yml` 使用签名 secrets |

## 3. 密钥（Key）

**数据流（已核实）**

```text
输入(UI) → SecureKeyStore.set(name, value)
        → KeystoreKeyCodec.encrypt(value, aad=name)   // AES-256-GCM，随机 IV
        → DataStore["secure_<name>"] = Base64(iv‖ciphertext+tag)
读取     → DataStore["secure_<name>"] → decrypt(aad=name) → 失败返回 null（不崩溃）
```

- 与设置共用一个 DataStore 时以 `secure_` 前缀隔离，`clearAll()` 只删该前缀（`SecureKeyStore.kt:82-88,122`）。
- 解密失败（密文损坏、Keystore key 失效、AAD 不匹配）统一返回 `null`（`KeyCodec.kt:64-85`、`SecureKeyStore.kt:106-113`），不会把半解密内容交给 UI。
- 覆盖测试：`SecureKeyStoreTest`（JVM，假 codec）+ `SecureKeyStoreInstrumentedTest`（真 Keystore）。

**未发现的问题**

- 无明文 Key 写 DataStore；无 `BuildConfig` 字段；无 `strings.xml`/assets 密钥；无 Key 相关日志。
- UI 不把 Key 放进 `rememberSaveable`（`SettingsScreen.kt:114-118`），因此不会随 Activity saved state 落盘。
- 无剪贴板复制入口（计划中「复制按钮可选」未实现，不存在剪贴板泄露面）。

**接线注意（非缺陷，P4/P6 施工前必读）**

- 目前生产路径**尚未实例化** `SecureKeyStore`：`AppContainer` 没有该依赖，导航仍使用 `SettingsPlaceholderScreen`，`SettingsScreen`/`OnboardingScreen` 尚未挂到图上（`AppNavHost.kt:64-65`）。因此「用户现在能存 Key」尚不成立，风险窗口也尚未打开。
- 接线时必须复用**同一个** DataStore 实例（同一文件多个 `preferencesDataStore` 委托会抛异常，且两份实例可能互相覆盖）。建议给 `SettingsRepository` 暴露 `val preferencesDataStore`（当前委托是文件私有，`SettingsRepository.kt:291-293`），再 `SecureKeyStore.create(appContext, preferencesDataStore)`。
- 接线时不要把 Key 复制进 `AppSettings`/`SettingsUiState`/`SavedStateHandle`；`SecureKeyStore` 只对外给「是否已配置 + 末 4 位」。

## 4. 组件暴露

| 组件 | exported | 依据 | 结论 |
|---|---|---|---|
| `MainActivity` | `true`（`AndroidManifest.xml:31`） | LAUNCHER 必需；另声明 `SEND`/`VIEW`(`text/plain`)（`38-48`） | 可接受；额外 filter 未实现且有 `singleTask`（SEC-07） |
| `AudioForgeMediaSessionService` | `true`（`52-59`） | Media3 要求导出，系统媒体控件/蓝牙按键才能连接 | 可接受；未限制 controller（SEC-06） |
| `GenerationNotificationsReceiver` | `false`（`68-70`） | 仅本应用显式 `PendingIntent`（`getBroadcast`）触发 | 通过；`PendingIntent` 均带 `FLAG_IMMUTABLE`（`GenerationNotifications.kt:278-305`、`NotificationHelper.kt:127-134`） |
| `FileProvider` | `false`（`72-80`） | 分享导出音频 | 通过；`grantUriPermissions=true` 但仅按 URI 临时授权 |
| WorkManager `SystemForegroundService` | 未声明（`61-65` `tools:node="merge"`） | 无 intent-filter，默认 false | 通过；合并结果应由 CI `aapt2 dump xmltree` 复核（P0-06 已验收 dataSync） |

**深链 / intent-filter**

- Manifest 中没有 `<data android:scheme="audioforge" android:host="project">`，即 `AppDeepLink` 目前**不构成外部入口**；`parse` 的严格校验（数字、正数、无 query/fragment、无多余段）是未来接 App Links 的正确基础。
- `ACTION_VIEW` + `mimeType="text/plain"` 没有 scheme/host，任何 `text/plain` 的 VIEW Intent 都可能命中它；但 `MainActivity` 不读 `intent.data`（`MainActivity.kt:10-19`），当前无数据泄露。真正接导入时必须复用 §6 的校验（见 SEC-07）。

## 5. 网络

- **传输**：仅 HTTPS（NSC + `usesCleartextTraffic=false`）；信任锚只有 `system`，未添加用户 CA、无 `debug-overrides`，因此 release/debug 都不会信任用户安装的证书。
- **证书**：不做 pinning（符合 §13.2）；默认 OkHttp 主机名校验，无自签放行。跨主机重定向不会沿用 `Authorization`（OkHttp 行为），且重定向到 `http://` 会被 NSC 拦截。
- **超时/重试**：LLM `connect 10s / read 120s`，Fish 额外 `callTimeout 150s`；429/5xx/IO 才重试，401/402/400/404 不重试；退避 `min(2^n*2, 30s)+jitter`，`maxRetries` 默认 3，无无限重试（`LlmClient.kt:73-110,178-182,212`、`FishTtsClient.kt:118-153`）。
- **错误信息**：`PipelineError.userMessage` 全部为固定中文，不含 URL/Key；底层异常只在 `cause`（`ErrorMapper.kt:33-36`）。但 LLM 把服务端响应体前 500 字符拼进 cause（SEC-05），以及 FFmpeg 首行日志进 `userMessage`（SEC-13），是两处需要收敛的「诊断信息外溢」。
- **地址校验**：`SettingsValidator` 允许 `http://`（`SettingsValidator.kt:217-222`），与运行时禁明文不一致（SEC-04）。

## 6. 存储与导出

- 内部路径全部相对化，DB 只存相对路径；`ProjectFiles.resolve/normalizeRelative/requireInsideRoot` 对绝对路径、`..`、NUL、符号链接越界都有防护（`ProjectFiles.kt:111-116,274-298,383-389`）。
- 导入：只接受 `.txt`，单文件上限 64MB，落盘名用 `UUID.txt` 而非用户文件名（`ImportNovelUseCase.kt:108-120,195-202`、`ProjectFiles.kt:60`）；用户文件名只作项目标题，不参与路径。
- 导出：API 29+ 写 MediaStore（`IS_PENDING` 两阶段），API 26–28 写 `getExternalFilesDir(Music)/AudioForge` 并经 FileProvider 分享；文件名先清洗再使用，MIME `audio/mp4`，分享只授只读权限（`ExportManager.kt:87-176,180`）。
- FileProvider 暴露范围：`files/audio/`、`cache/`（整个 cacheDir）、`external-files/exports/`、`external-files/Music/AudioForge/`（`file_paths.xml:3-7`）。`cache-path path="."` 偏宽（SEC-08）。
- 备份：`allowBackup=false` + `fullBackupContent=false` + 提取规则（本轮已把 `device-transfer` 的 `file`/`external` 补齐，并给 `cloud-backup` 补 `external`）。即使规则失效，DataStore 里也只有 Keystore 绑定的密文，换机/换 Keystore 后无法解密。

## 7. 日志与崩溃

- 全仓无 `Timber`、无 `printStackTrace`、无 `println`；`Log.*` 集中在 Job/Worker/播放器/Phase 0 探针，内容为 ID、计数、错误码，未发现 Key、`Authorization`、完整请求/响应体或章节正文。
- Debug-only 行为（Room/FFmpeg 探针、首页调试卡）由 `BuildConfig.DEBUG` 控制（`AudioForgeApp.kt:17-20`、`HomeScreen.kt:99`）。
- 但 release **未开 R8**（`build.gradle.kts:53`），`Log.*` 不会被剥离；当前没有敏感内容，属「依赖开发纪律」而非技术约束（SEC-10）。
- 无崩溃上报/分析 SDK，崩溃信息只进系统 logcat 与用户手动分享，隐私面小。

## 8. CI / 供应链

- **secrets 使用**：只有 `release.yml` 读取 `ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`；`ci.yml`/`smoke.yml` 无 secrets；keystore 落 `$RUNNER_TEMP`、`chmod 600`、不上传 artifact；APK 用 `apksigner verify` 校验（`scripts/verify-apk.sh:16-17`），发布附带 `sha256sum`（`release.yml:73-78`）。
- **版本锁定**：Gradle 依赖全部精确版本（`build.gradle.kts:111-162`），无 `+`/动态版本；Compose BOM 固定 `2026.09.00`。
- **缺口**：所有 Action 用可变 tag（`@v4`/`@v2`），未 pin 到 commit SHA；`softprops/action-gh-release@v2` 是第三方 Action 且能接触 `contents: write` 与签名 secrets（SEC-01）。无 `gradle/verification-metadata.xml`（P6-03 计划项，SEC-11）。
- **权限**：`ci.yml`/`smoke.yml` 为 `contents: read`；`release.yml` 为 `contents: write`（发布必需），但作用域在 workflow 级、且 checkout 默认 `persist-credentials: true`（SEC-01 建议一并收紧）。
- **脚本注入**：`release.yml:61-64` 把 `workflow_dispatch` 输入与 `ref_name` 直接插进 `run` 脚本；只有写权限者可触发，风险低但应改为 env 传参（SEC-12）。
- **构建产物**：`smoke.yml` 上传的 `logcat-smoke.txt` 来自 debug 构建、无真实 Key；release artifact 仅 APK + checksums。

## 9. 发现清单

> 位置均为「仓库相对路径:行号」；行号以本分支最终文件为准。

| ID | 等级 | 位置 | 问题 | 建议 | 状态 |
|---|---|---|---|---|---|
| SEC-01 | **P1** | `.github/workflows/{ci,release,smoke}.yml`（`release.yml:22-27,40,89`；`ci.yml:22-25,42,54,63`） | Action 用可变 tag，release 流程同时持有签名 secrets 与 `contents: write`，tag 被移动/第三方 Action 被接管即可窃取签名密钥或篡改发布 | 全部 Action pin 到完整 commit SHA（Dependabot 更新）；`release.yml` 的 `permissions` 收到 job 级；checkout 加 `persist-credentials: false`；第三方 Action 优先替换为官方或自托管 | 待修 |
| SEC-02 | **P1** | `app/src/main/java/.../core/network/LlmModels.kt:12-19` | `LlmConfig` 是 data class 且含 `apiKey`，默认 `toString()` 会输出明文 Key；`FishTtsConfig` 已掩码（`FishTtsClient.kt:48-51`），两者不一致。当前无调用点打印，属预防性 | 覆写 `toString()`（`apiKey=***`）或把 apiKey 包成 `RedactedString`；加一个「禁止打印 config」的单测/约定 | 待修 |
| SEC-03 | P2 | `feature/settings/SettingsScreen.kt:117,926`；`feature/onboarding/OnboardingScreen.kt:413`；`MainActivity.kt:10-19` | 计划 §13.1 要求 Key 页 `FLAG_SECURE`，当前未设置：截图/录屏可捕获 Key 输入页（虽然密码化且只显示末 4 位） | 在含 Key 的页面/对话框 `window.addFlags(FLAG_SECURE)`（Compose 可用 `LocalView` + `DisposableEffect` 加/移除）；仅在 Key 相关界面启用，避免影响整机截图 | 待修 |
| SEC-04 | P2 | `core/settings/SettingsValidator.kt:217-222` | 允许保存 `http://` 地址，但运行时 NSC 禁明文，用户会得到「网络不可用」的误导性错误；若未来放宽 NSC 则直接变明文传输 | 校验只允许 `https://`（debug 可额外允许 `http://127.0.0.1`/MockWebServer）；错误文案明确「仅支持 HTTPS」 | 待修 |
| SEC-05 | P2 | `core/network/LlmClient.kt:189-199`（`ERROR_BODY_PREVIEW=500`，`:208`） | 服务端错误响应体前 500 字进入 `IOException` 消息，随后可能随 `Log.w/Log.e(..., throwable)` 进 logcat；部分网关会在错误体回显 Key 片段/内部信息 | 只保留状态码与 `error.code`；如需 body，先脱敏（去除 `sk-`/`Bearer`/`api_key`/`Authorization` 模式）并仅进 debug 日志；`TtsFailed` 同理 | 待修 |
| SEC-06 | P2 | `AndroidManifest.xml:52-59`；`playback/AudioForgeMediaSessionService.kt:58-59` | 服务必须导出（系统媒体控件），但 `onGetSession` 无条件返回 session：任意已安装应用可 bind 为 controller，读取媒体元数据（书名/章节名）并控制播放 | 在 `MediaSession.Callback.onConnect` 中只放行本应用与系统（`ControllerInfo.packageName` 白名单/`isTrusted`），拒绝其它 controller；媒体元数据不放敏感标题 | 待修 |
| SEC-07 | P2 | `AndroidManifest.xml:38-48`；`MainActivity.kt:10-19` | 声明了 `SEND`/`VIEW`(`text/plain`) 但 Activity 不处理 Intent；`VIEW` 无 scheme/host，任何 text/plain VIEW 都可能命中。当前不读数据故无泄露，但入口与实现不一致 | 二选一：① 移除两个 filter（等 P4-04 导入接线再加）；② 实现时校验 `content://`、MIME、64MB 上限、`OpenableColumns`，绝不信任文件名/路径；`VIEW` 建议加 `scheme=content`/`file` 限定 | 待修 |
| SEC-08 | P2 | `app/src/main/res/xml/file_paths.xml:4` | `<cache-path path=".">` 把整个 `cacheDir` 纳入 FileProvider 可授权范围（含 FFmpeg 中间产物、未来其他缓存）；虽然授权按 URI 且只读，但范围过大 | 收窄到实际需要的子目录（如 `cache/exports/`）或删除该行，等有明确分享需求再加 | 待修 |
| SEC-09 | P2 | `app/src/main/res/xml/data_extraction_rules.xml:3-14` | `device-transfer` 原先只排除 sharedpref/database，未排除 `file`（DataStore/audio/imports）与 `external`（导出）；与 `cloud-backup` 不一致。`allowBackup=false` 时实际不生效，属纵深防御缺口 | 已在本轮补齐：两个 block 均排除 sharedpref/database/file/external | **已修** |
| SEC-10 | P2 | `app/build.gradle.kts:52-55`；全仓 `Log.*`（如 `JobQueue.kt:98,110,119`、`GenerationWorker.kt:93-171`） | release 未开 R8/资源压缩，`Log.*` 保留；当前日志无 Key/URL，但「不出事」依赖开发纪律。P6-01 会改这里 | P6-01 打开 `isMinifyEnabled`/`isShrinkResources` 后，统一改用 debug-only logger 或 lint 规则禁止 `Log.*` 直接打印异常；保留 `Log.e` 时只打错误码 | 待修（随 P6-01） |
| SEC-11 | P2 | `gradle/`（无 `verification-metadata.xml`） | 依赖版本已锁定，但没有制品校验和，Gradle 侧供应链仍可被仓库投毒/中间人影响；FFmpeg AAR 是重点 | 按 P6-03 生成 `gradle/verification-metadata.xml`（sha256）并在 CI 校验；保留精确版本 | 待修（P6-03） |
| SEC-12 | P2 | `.github/workflows/release.yml:61-64` | `${{ github.event.inputs.version }}` / `github.ref_name` 直接插值进 `run` 脚本，存在 shell 注入面（仅写权限者可 dispatch，风险低） | 改为 `env: VERSION_INPUT: ${{ ... }}` 后在脚本里引用并校验 `^[0-9A-Za-z._-]+$`；`versionCode` 同样校验 | 待修 |
| SEC-13 | P2 | `core/storage/ExportManager.kt:145`；`core/model/ErrorMapper.kt:77-93` | 错误文案把绝对路径（`exportDir.absolutePath`）或 FFmpeg 日志首行拼进 `userMessage`，会出现在 UI/DB/截图里，属本地路径信息外溢 | 用户文案只给原因与建议；绝对路径与 FFmpeg 日志只进 debug 日志（并去掉应用私有目录前缀） | 待修 |
| SEC-14 | P2 | `feature/onboarding/OnboardingViewModel.kt:340-380` | 保存/跳过成功后未清空 `OnboardingUiState.llmKey/fishKey`，明文 Key 在 ViewModel 生命周期内持续驻留（仅内存，不落盘） | 成功后 `copy(llmKey="", fishKey="")`（保留「已配置」状态由 SecureKeyStore 判断）；跳过时同样清空；禁止放入 `SavedStateHandle` | 待修 |
| SEC-15 | P2 | `app/src/main/AndroidManifest.xml:23` | 未显式声明 `usesCleartextTraffic`，仅依赖 NSC 与 targetSdk 默认值；计划 §13.2/P0-03 要求该属性可见 | 已在本轮添加 `android:usesCleartextTraffic="false"`（API 24+ 有 NSC 时被忽略，无行为变化；作为纵深防御与 `aapt2` 验收证据） | **已修** |

## 10. 本轮已修（本分支）

| 文件 | 改动 | 说明 |
|---|---|---|
| `app/src/main/AndroidManifest.xml:23` | 新增 `android:usesCleartextTraffic="false"` | 与 `networkSecurityConfig` 双保险；满足 §13.2 / P0-03 验收（`aapt2 dump xmltree` 可见）；API 24+ 有 NSC 时该属性被忽略，**无行为变化** |
| `app/src/main/res/xml/data_extraction_rules.xml:3-14` | `cloud-backup` 增加 `external` 排除；`device-transfer` 增加 `file`、`external` 排除 | 与 `allowBackup=false` 一致的纵深防御；`allowBackup` 关闭时实际不生效，**无行为变化** |

**未修改**（按任务约束）：`core/**`、`data/**`、`ui/**`、`app/**` 源码、`docs/progress.md`、`.github/**`、`build.gradle.kts`、版本矩阵。P1/P2 的代码修复留到对应任务（P6-01/P6-03/P6-09、P4-04/P4-09/P4-10）。

## 11. 复验清单（在 CI / 真机执行，不在本机）

1. **静态（CI 常规）**：`lintDebug` 应无 `AllowBackup` / `CleartextTraffic*` / `Exported*` 高危项（`build.gradle.kts:80-84` 已 `abortOnError=true`）。
2. **合并 Manifest**：CI 构建后
   `aapt2 dump xmltree app/build/outputs/apk/debug/app-debug.apk --file AndroidManifest.xml`，
   确认：`allowBackup=false`、`usesCleartextTraffic=false`、`networkSecurityConfig`、`exported` 列表与 §4 一致、`SystemForegroundService` 为 `dataSync` 且未导出。
3. **签名**：`apksigner verify --print-certs app-release.apk`，确认证书 SHA-256 与 CI secrets 对应（不进仓库）。
4. **密钥页截图**：完成 SEC-03 后，在 Key 配置页截图/录屏应得到黑屏；设置页只显示 `••••` + 末 4 位。
5. **日志**：真机完成一次 401/超时/成功生成，`adb logcat` 中不得出现 `sk-`、`Bearer`、完整请求/响应体、章节正文。
6. **备份**：`adb backup`（旧系统）或系统备份面板确认无可备份数据；换机迁移不携带 DataStore/DB/audio 文件。
7. **FileProvider 越权**：用另一应用对 `content://<pkg>.fileprovider/...` 发起无授权读取，应 `SecurityException`；构造 `file_paths.xml` 未声明路径（如 `databases/`、`shared_prefs/`）的 URI 应被 provider 拒绝。
8. **路径穿越**：导入文件名含 `../`、`..\\`、`%2e%2e%2f`、超长中文、控制字符；导出标题同样覆盖，确认落盘仍在 `imports/`、`exports/`、`Music/AudioForge/` 内。
9. **供应链**：`grep -R "@v[0-9]" .github/workflows` 在 SEC-01 修复后应无命中（全部 SHA）。

## 12. 残余风险与任务映射

- **接受的风险**：不做证书 pinning（§13.2 明确）；Keystore key 在部分设备为软件实现且不要求用户认证（后台生成任务需要无人值守，权衡后接受）；Key 在 Kotlin `String` 中无法主动清零（JVM 限制，接受）。
- **待办映射**：SEC-01 → P6-09（release/供应链）；SEC-10 → P6-01（R8/日志）；SEC-11 → P6-03（依赖校验）；SEC-03/SEC-14 → P4-09/P4-10（设置/引导）；SEC-07 → P4-04（导入）；SEC-02/SEC-04/SEC-05/SEC-06/SEC-08/SEC-12/SEC-13 建议在下一次触碰对应文件时顺手修。
- **发布前门槛**：SEC-01 与 SEC-02 必须在 v1.0.0 前关闭；其余 P2 可随对应任务关闭，但 SEC-03（FLAG_SECURE）与 SEC-06（媒体控制器）建议在发布前完成。
- **文档维护**：本文件随修复更新「状态」列；P6-09 完成后补一次 Action pinning 复核。
