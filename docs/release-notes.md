# AudioForge v1.0.0 发布说明（模板）

> **这是模板，不是已发布版本的最终说明。** 发布时把 `{{...}}` 占位符替换为实际值；没有证据的功能/修复不得写进「新增」「修复」，未完成项必须移到「已知问题」并标 `BLOCKED`。
>
> - 模板创建：2026-11-09（Asia/Shanghai）
> - 基线：`main@e3df050`；分支：`docs/p6-10-release-docs`
> - 对照：执行计划 §15 P6-10、[release-process.md](release-process.md)、[security-review.md](security-review.md)、[device-matrix.md](device-matrix.md)
> - 用户安装/升级总说明：[README.md](../README.md)

---

## 1. 版本信息

| 项目 | 值 |
|---|---|
| 版本名（versionName） | `1.0.0` |
| 版本号（versionCode） | `{{VERSION_CODE}}`（= GitHub Actions `run_number`） |
| Tag | `v1.0.0` |
| 发布日期 | `{{RELEASE_DATE}}`（Asia/Shanghai） |
| 包名 | `com.estimatefaster.audioforge` |
| APK | `audioforge-1.0.0.apk` |
| 最低系统 | Android 8.0（API 26） |
| ABI | `arm64-v8a`、`x86_64`（**32 位不支持**） |
| 分发 | GitHub Releases 侧载；不上架 Google Play |
| APK SHA-256 | `{{APK_SHA256}}`（以 `checksums.txt` 为准） |
| 签名证书 SHA-256 | `{{CERT_SHA256}}`（必须与上一版一致） |

---

## 2. 新增

> 发布前逐项核对；只保留在 v1.0.0 实际可用、已验证的内容。未完成项移到第 4 节。

### 导入与解析

- 导入 `.txt`（单文件 ≤ 64 MB），自动识别 UTF-8/BOM、GBK/GB18030、UTF-16LE/BE、Big5 等编码；识别失败可手动选择编码重试。
- 章节切分：支持「第 X 章」「第 X 卷」「Chapter N」、无标题单章等；同名项目不覆盖，提示打开已有项目。
- LLM 解析：OpenAI 兼容接口，旁白/角色台词拆分、角色注册与别名复用、剧本生成；覆盖率校验与解析告警。
- 长章节守卫：超长章节明确报错，不静默降级。

### 剧本与音色

- 剧本页：角色筛选/搜索、片段编辑、播放跟随。
- 63 音色池：旁白/男声/女声/其他分组，按类别、label、voice ID 搜索；角色音色锁定与二次确认。
- 角色微调：语速（0.5–2.0）、音量（0.5–2.0）、音调（-12–12）；角色设置优先于全局默认值。

### 语音生成与音频

- Fish Audio TTS 直连；429/5xx/超时按指数退避重试，尊重 `Retry-After`；401/402 不重试。
- 片段缓存键、原子写、失败保留原文件；TTS 工作池与停止/续跑。
- 后处理（语速/音量/音调）与章节拼接：旁白 350 ms、段落 700 ms 停顿；AAC 直出/回退 WAV。

### 批量、后台与恢复

- WorkManager + `dataSync` 前台服务；批量生成、任务队列、单活跃任务、停止收尾。
- 前台通知：进度、完成/失败、停止入口；Android 13+ 通知权限被拒绝时任务继续、通知降级。
- 进程死亡/重启恢复：sweep running、重排、幂等；重开应用继续。
- 约束：仅 Wi-Fi（默认开）、仅充电、低电量阈值、过热降级；约 5 小时看门狗（Android 15+ 前台服务 6 小时限制前主动收尾）。

### 播放与导出

- Media3 播放器：播放/暂停、上一章/下一章、上一句/下一句、倍速、睡眠定时、章节列表。
- 后台播放与媒体通知；锁屏/蓝牙媒体控制（取决于系统）。
- 章节导出 `.m4a`：Android 10+ 写入 `Music/AudioForge/<书名>/`；Android 8/9 写应用外部目录并通过 FileProvider 分享。
- 分享只授予只读 URI 权限。

### 管理与隐私

- 项目库：搜索、进度、删除（二次确认）、空态/错误态。
- 任务页：当前任务、队列、最近结果、进度/预计剩余、停止、失败原因。
- 存储页：空间概览、按项目占用、保留期 7–365 天、孤儿（>6 小时）/过期音频清理；仍被引用的文件跳过。
- 设置：API、生成、网络、设备、外观、存储、关于；Onboarding 3 步（欢迎/Key/通知与电池）。
- 关于/诊断：版本、构建类型、包名、Room/FFmpeg/WorkManager/存储诊断、开源许可、导出日志。
- Key 安全：Android Keystore AES-256-GCM 加密、UI 只显示末 4 位、不写日志、关闭系统备份。
- 网络：仅 HTTPS（禁明文），无自定义信任/不做证书 pinning；无分析/广告/崩溃 SDK。

### 发布工程

- GitHub Actions 构建签名 release APK；tag/手动 dispatch 校验版本格式；`versionCode = run_number` 只增。
- `scripts/verify-apk.sh` 校验签名、包名、versionCode/versionName、ABI、16 KB 对齐。
- Release 附带 `audioforge-<version>.apk` 与 `checksums.txt`（SHA-256）；失败即阻断发布。
- `allowBackup=false`、数据提取规则排除 sharedpref/database/file/external。

---

## 3. 修复

> v0.1.0 只是工程骨架；v1.0.0 是首个功能版本，因此没有“上一功能版”的完整修复清单。以下为开发周期内已修复、会体现在本版中的典型问题（发布前按提交记录核对）。

- **Key 泄露面**：`LlmConfig.toString()` 不再输出明文 Key；Fish 配置同样掩码。
- **音频后处理**：音调链先重采样，音量 >1 做限制，避免异常/削波。
- **进程恢复**：进程死亡后 `running` 任务被 sweep 回收并重排，避免已完成工作被重复执行。
- **通知降级**：Android 13+ 通知权限被拒绝时，生成任务继续运行、不崩。
- **数据库初始化**：修正部分设备上 Room `onOpen` 的 `PRAGMA busy_timeout` 崩溃。
- **导出兼容**：导出/分享使用 `audio/mp4` MIME，提升接收方兼容性。
- **发布链路**：版本输入校验、keystore 可用性检查、APK 签名/包名/版本/ABI 校验、checksums 归档。

---

## 4. 已知问题（BLOCKED）

> 原则：没有真机/真实 Key/CI 证据的项，只能写 `BLOCKED`，不能写“通过”或“应该没问题”。

| ID | 问题 | 状态 | 影响 | 依据 / 绕过 |
|---|---|---|---|---|
| 1 | **32 位设备不支持** | 不支持（BLOCKED） | `armeabi-v7a` / `x86` 设备安装报 `INSTALL_FAILED_NO_MATCHING_ABIS`；APK 只含 `arm64-v8a` + `x86_64` | [device-matrix §1.3](device-matrix.md)；换 arm64 真机 |
| 2 | **真机后台未验收** | BLOCKED（P3-10 / P6-05） | 锁屏 30 min、杀进程、重启、Doze、OEM 自启动/省电策略未在真机验证；后台可能被 Honor/EMUI、MIUI/HyperOS、ColorOS 等杀掉 | [phase3-gate](phase3-gate.md)、[device-matrix](device-matrix.md)；加电池白名单/自启动，失败带 logcat 反馈 |
| 3 | **真实 API smoke 未执行** | BLOCKED（P2-18） | CI 没有用户 Key，未跑真实 LLM/Fish 链路；Onboarding「测试连接」只做本地格式校验，真实 401/402/429 可能在首次生成才暴露 | [phase2-gate](phase2-gate.md)；用服务商控制台核对 Key/额度/模型，先跑短章节 |
| 4 | **MIUI/HyperOS、ColorOS、16 KB 页设备未验证** | BLOCKED（P6-05） | 没有设备/证据；不写成通过 | [device-matrix](device-matrix.md) §4.3、§6.2；有设备后补 M06–M09/M13 与 16 KB 安装 |
| 5 | **电池白名单没有一键跳转** | 已知 UX 缺口 | 应用内只有文字说明，用户需手动去系统设置；后台失败容易被误认为 bug | [device-matrix §7](device-matrix.md)；按 ROM 手动设置 |

### 已知限制（非缺陷）

- **APK 较大（约 60 MB）**：当前 universal APK，未开 R8/资源压缩、未做 ABI split（P6-01/P6-02 只有评估，未落地）。见 [r8-evaluation](r8-evaluation.md)、[abi-split-evaluation](abi-split-evaluation.md)。
- **无云同步 / 无系统备份 / 无自动更新**：数据只在本机；`allowBackup=false`；更新靠手动下载 APK 覆盖安装。
- **平板/折叠未专项验收**：有自适应壳，但 v1 以手机体验为准。
- **Android 8/9 边界未真机验收**：minSdk 26 已声明，API 26/27 的通知渠道、导出回退、无 FGS 类型行为未有真机证据。

---

## 5. 升级注意

### 5.1 从 v0.1.0 升级

- v0.1.0 是早期工程骨架，v1.0.0 是首个功能版本；包名相同；**若两版使用同一签名，可覆盖安装**。
- 覆盖安装会保留应用数据；仍建议先导出重要章节音频。
- 如提示签名不一致：旧包可能来自 debug/第三方重打包/换过 keystore，只能先导出 → 卸载 → 重装（**数据会丢**）。
- 如覆盖后启动异常，请记录版本、ROM、logcat 并反馈；必要时卸载重装（会清空项目/音频/Key）。

### 5.2 通用升级规则

- `versionCode` 由 CI `run_number` 自动递增；官方**不复用旧版本号**。
- 覆盖安装要求：**同一签名**，新 `versionCode` ≥ 已安装版本。
- **降级被 Android 阻止**（`INSTALL_FAILED_VERSION_DOWNGRADE`）。官方不提供降级包；必须回旧版时只能卸载重装，数据会丢。
- 不要混装 debug 包（`com.estimatefaster.audioforge.debug`）与正式包；二者数据独立。
- 升级后如通知/权限被系统重置，请到系统设置重新允许通知、检查电池白名单。
- 没有 Google Play 自动更新；请关注 Releases 页手动更新。

### 5.3 卸载 / 备份

- 卸载或「清除数据」会删除：项目、章节、剧本、角色、生成音频、缓存、加密 Key。
- `allowBackup=false`：系统云备份/换机迁移不会带走数据；`adb backup` 也不可用。
- 需要保留的内容请先导出 `.m4a`，并自行保留原始 txt。
- 导出的 `Music/AudioForge/` 副本属于共享媒体目录，卸载应用不会删除；需要时自行清理。

### 5.4 签名与完整性

- 覆盖升级的前提是签名证书一致；证书 SHA-256 见第 6 节。
- Release APK 只由 GitHub Actions 产出；不要安装第三方重打包 APK。
- 下载后先核对 `checksums.txt`，再安装。

---

## 6. 校验和与签名

发布时由 CI 生成 `checksums.txt`；以下值必须来自实际产物，**不得编造**：

| 项目 | 值 |
|---|---|
| APK SHA-256 | `{{APK_SHA256}}` |
| 签名证书 SHA-256 | `{{CERT_SHA256}}` |
| 签名证书 DN | `{{CERT_DN}}` |
| checksums.txt | `{{CHECKSUMS_URL}}` |

```bash
# 1) 校验下载文件
sha256sum -c checksums.txt
# macOS: shasum -a 256 -c checksums.txt

# 2) 校验签名（apksigner 在 Android SDK build-tools 中）
apksigner verify --print-certs audioforge-1.0.0.apk

# 3) 可选：核对包名/版本/ABI
aapt2 dump badging audioforge-1.0.0.apk | grep -E "package|sdkVersion|native-code"
```

预期：

- `apksigner` 输出 `Verifies`，证书 SHA-256 与上一版一致；
- `checksums.txt` 中的 APK 文件名与本版一致；
- `native-code` 只包含 `arm64-v8a` 和 `x86_64`。

---

## 7. 安装（简版）

1. 打开 [GitHub Releases](https://github.com/EstimateFaster/android-audioforge/releases/latest)，下载 `audioforge-1.0.0.apk` 与 `checksums.txt`；
2. 按第 6 节校验 SHA-256 / 签名；
3. 为浏览器或文件管理器允许「安装未知应用」；
4. 点击 APK 安装；或 `adb install -r audioforge-1.0.0.apk`；
5. 首次启动按 Onboarding 配置 LLM/Fish Key，或跳过稍后配置。

要求：Android 8.0+，`arm64-v8a`（或 x86_64 模拟器），建议 ≥1 GB 可用空间。**32 位设备不支持。**

---

## 8. 致谢

- 上游 [StoryForge](https://github.com/EstimateFaster/storyforge)（MIT）与所有上游测试/文档贡献者；
- [FFmpegKit](https://github.com/arthenica/ffmpeg-kit) 及 FFmpeg 社区（LGPL-3.0）；
- Android Jetpack / Compose / Media3 / Room / WorkManager / OkHttp / Kotlin 社区；
- Fish Audio、DashScope 及所有兼容服务提供方；
- 参与内测、提供设备/日志/反馈的贡献者。

> 以上为技术与社区致谢，不表示任何服务商对 AudioForge 的赞助或背书。

---

## 9. 反馈与相关文档

- Bug/功能请求：[GitHub Issues](https://github.com/EstimateFaster/android-audioforge/issues)
- 反馈请附：版本（设置 → 关于）、构建类型、Android/ROM、设备型号、复现步骤、logcat。
- **不要**贴 API Key、Authorization、小说正文或未脱敏音频。
- 安全问题请通过仓库 Security 渠道（若启用）私下报告，不要在公开 issue 贴利用细节。

相关文档：

- [README.md](../README.md) — 安装、首次配置、使用流程、FAQ、升级/回滚
- [release-process.md](release-process.md) — 发布流程、签名、Secrets、回滚
- [security-review.md](security-review.md) — 安全复查与 SEC 清单
- [device-matrix.md](device-matrix.md) — 设备矩阵、后台验收、BLOCKED 记录
- [phase2-gate.md](phase2-gate.md) / [phase3-gate.md](phase3-gate.md) — 阶段门禁与 BLOCKED 原因
- [r8-evaluation.md](r8-evaluation.md) / [abi-split-evaluation.md](abi-split-evaluation.md) — 体积/ABI 评估
- [android-execution-plan.md](android-execution-plan.md) — 执行计划

---

## 10. 发布检查清单（维护者）

- [ ] `main` CI 绿；tag 指向通过 CI 的提交，未给旧 commit 补 tag。
- [ ] `versionName=1.0.0`，`versionCode` 由 CI `run_number` 生成且大于上一版。
- [ ] 4 个签名 Secrets 存在且可用；keystore 离线备份可读。
- [ ] 签名证书 SHA-256 与上一版一致（覆盖升级前提）。
- [ ] release workflow 绿；`verify-apk.sh` 通过（签名/包名/版本/ABI/16 KB 对齐）。
- [ ] `checksums.txt` 与 APK 同名同版本；Release 资产无缺文件。
- [ ] 本文件所有 `{{...}}` 已替换；未完成功能未写入「新增」。
- [ ] 已知问题与 BLOCKED 状态与实际证据一致（32 位、真机后台、真实 API smoke）。
- [ ] 未提交密钥/大文件/构建产物；未改版本矩阵。
- [ ] 真机安装/覆盖升级/启动/导入/短章节生成/播放/导出已执行；若无法执行，明确标 `BLOCKED`，不写成通过。
