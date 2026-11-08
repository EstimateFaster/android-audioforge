# 设备矩阵与后台验收（P6-05）

> **状态**：文档完成；**真机验证 BLOCKED（本环境无真机）**。本文是 P6-05 的唯一产物，未改 workflow / 代码 / `docs/progress.md`。
> **日期**：2026-11-09（Asia/Shanghai）。**基线**：`main@42364e6`；分支 `docs/p6-05-devices`。
> **对照**：执行计划 §14.2（必测清单）/§14.3（真机矩阵）/§14.4（失败演练）/§14.5（性能）/§15 P6-05；§3.7（后台执行模型）、§11（后台任务）；`docs/phase3-gate.md`（P3-10 BLOCKED）；`.github/workflows/smoke.yml`；`docs/android-port-feasibility.md` §3.7 / §5.1.4。
> **记录规则**：本文所有「通过」只指有证据（日志/截图/issue）的项。**emulator 结果不得记为真机通过**；没跑过的项写 `BLOCKED`/`N/A`，不写「应该没问题」。

---

## 0. 结论（TL;DR）

1. **目标矩阵：8 行设备（6 类可支持 + 可选平板 + 明确不支持的 32 位）、7 个 Android 版本档（26→36）、4 个 ABI 档**（§1）。P0 只有两类：**Honor/EMUI（用户主力老机）** 与 **Pixel/AOSP（Android 15/16）**；MIUI/HyperOS、ColorOS/OriginOS 为 P1（拿不到设备就记 BLOCKED，不阻塞发布但要在 release notes 声明未验证）。
2. **CI 只覆盖 emulator**：API 35 / `google_apis` / `x86_64` / `pixel_6`，instrumented **15 项**（§4）。它证明「代码路径能跑」，**不证明** OEM 后台存活、锁屏、重启、Doze、真机 ARM64、16 KB 页。
3. **真机项全部 BLOCKED**，与 P3-10 同因：无 adb 设备、无厂商 ROM。解除条件见 §4.4。
4. **发布门槛（§6）**：P3-10（后台续跑）、P5-08（播放/通知后台）、P6-09（签名 APK 安装/升级）三项**必须真机通过**才能发 v1.0.0；其余为尽力项。
5. **两个从代码读出来、必须写进矩阵的事实**（不是计划里写的）：
   - **没有 `armeabi-v7a`**：`app/build.gradle.kts` 的 `abiFilters` 只有 `arm64-v8a` + `x86_64`（注释：ffmpeg AAR 只提供这两个 ABI）。→ **32 位 ARM 设备装不上**，矩阵按「不支持」记录，不改版本/构建（§1.3）。
   - **电池白名单只有文案、没有跳转**：`onboarding_battery_body` 只是文字，全仓库没有 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 调用。→ Honor/MIUI 上「引导用户加白名单」这一步只能靠用户自己找设置，验证时按「手动指路」执行，并记一条后续 issue（§7）。

---

## 1. 目标设备矩阵

### 1.1 设备 / ROM 维度

「获取」列说明本仓库当前能否验证：`无` = 本环境不可得，全部 BLOCKED。

| # | 类别 | 代表设备 | ROM / 系统 | Android / API | ABI | 优先级 | 用途 | 获取 |
|---|---|---|---|---|---|---|---|---|
| D1 | Honor 老机（主力） | Honor 20 | EMUI 10.x / Magic UI 3.x | 10 / 29 | arm64-v8a | **P0** | 后台、存储、通知、媒体通知 | 无 |
| D2 | Honor 新机 | Honor 中端/旗舰 | MagicOS 7 / 8 / 9 | 13 / 14 / 15 | arm64-v8a | P1 | MagicOS 后台策略、FGS 类型与 6h 上限 | 无 |
| D3 | Pixel / AOSP | Pixel 6–9、AOSP 模拟器 | 原生 | 13 / 15 / 16 | arm64-v8a | **P0** | 新系统行为：FGS、通知权限、16 KB 页 | 无（emulator 可代 API 35 部分） |
| D4 | Android 8 边界机 | 任意 2017–2018 机 | 厂商 8.0/8.1 | 8.0–8.1 / 26–27 | arm64-v8a | P1 | minSdk 边界：通知渠道、导出回退、无 FGS 类型 | 无 |
| D5 | 国产 ROM | Redmi / 小米 | MIUI 12–14 / HyperOS | 11–15 | arm64-v8a | P1 | 自启动/省电策略、后台清理 | 无 |
| D6 | 国产 ROM | OPPO / vivo | ColorOS 12–15 / OriginOS | 11–15 | arm64-v8a | P1 | 同上（ColorOS「耗电管理」） | 无 |
| D7 | 平板/折叠（可选） | Pixel Tablet 等 | 原生 | 14–16 | arm64-v8a | P2 | 自适应布局，不阻塞发布 | 无 |
| D8 | 32 位老机 | 任意 armeabi-v7a 机 | 8.x | 26–27 | armeabi-v7a | — | **不支持**（见 §1.3） | 无 |

> MagicOS 7/8/9 ↔ Android 13/14/15 的对应以设备实际 OTA 为准；**上机第一件事是记录真实值**，不要照抄本表。

### 1.2 Android 版本维度（minSdk 26 → targetSdk 36）

`compileSdk 37 / targetSdk 36 / minSdk 26`（ADR-004）。target 36 意味着下面所有「target ≥ N」的行为限制都生效。

| Android | API | 该档的关键差异 | 对本项目的影响 | 代码侧现状 | 真机状态 |
|---|---|---|---|---|---|
| 8.0 / 8.1 | 26 / 27 | 通知渠道必需；后台 `startService` 受限；无 FGS 类型概念；`POST_NOTIFICATIONS` 不存在 | 导出走 app-specific external + FileProvider（MediaStore `RELATIVE_PATH` 是 29+） | 已实现（见执行计划 §7 导出回退） | BLOCKED |
| 10 | 29 | 分区存储；后台启动 FGS 有豁免条件；媒体通知可用 | Honor 20 的主场景；scoped storage 下缓存/导出路径 | 已实现 | BLOCKED |
| 12 / 12L | 31 / 32 | **禁止从后台启动 FGS**（除豁免）；`PendingIntent` 必须显式可变性；通知 trampoline 受限 | 只能由用户在前台点「开始」入队；停止 action 用 `PendingIntent` | 已实现（测试内显式前台启动） | BLOCKED |
| 13 | 33 | `POST_NOTIFICATIONS` 变运行时权限；拒绝后 FGS 通知不进抽屉（任务管理器仍可见） | 首页请求权限；拒绝后任务继续、通知静默跳过（只有日志）；onboarding 文案说明 | 已实现（Home 请求 + 设置开关 + `canPostNotifications()` 守门） | BLOCKED |
| 14 | 34 | **FGS 必须声明类型 + 对应权限**，否则启动即崩 | `dataSync`（生成）/ `mediaPlayback`（播放）两类型 + 两条权限 | 已声明（manifest 两个 service） | BLOCKED |
| 15 | 35 | `dataSync`/`mediaPlayback` **6 小时/24 小时**上限，同类型共享额度，App 回前台重置；**target ≥ 35 禁止从 `BOOT_COMPLETED` 启动这些 FGS**；edge-to-edge 强制 | 5 章一批 + checkpoint + 5h 看门狗；**重启后靠 WorkManager 重新调度，不是 BOOT 起 FGS** | 看门狗默认 5h（`JobWatchdog`），批次默认 5/上限 20 | BLOCKED |
| 16 | 36（target） | 行为变更随 target 36 生效；16 KB 页设备普及中；FGS 6h/24h 继续适用 | 冷启动/前台服务/通知/16 KB 原生库都要在新系统复测 | 未在 API 36 上跑过 emulator | BLOCKED |

**16 KB 页**：Android 15+ 的新设备可能用 16 KB 内存页，原生库（ffmpeg AAR）必须 16 KB 对齐。可行性文档记录该 AAR 实测 `Align=0x4000`，但**从未在 16 KB 设备上安装运行**。→ 列为 D3 的必测子项，失败即视为发布阻塞。

### 1.3 ABI 维度（以构建文件为准）

| ABI | 是否打进 APK | 设备/用途 | 结论 |
|---|---|---|---|
| `arm64-v8a` | ✅ | 所有目标真机（D1–D3、D5、D6） | **P0 必须验证** |
| `x86_64` | ✅ | 仅 emulator / CI（`smoke.yml`） | 已覆盖，但**不代表 ARM 行为** |
| `armeabi-v7a` | ❌ | 32 位 Android 8/9 老机 | **不支持**：安装报 `INSTALL_FAILED_NO_MATCHING_ABIS`；v1.1 候选（ABI split / 32 位 ffmpeg） |
| `x86` | ❌ | 老模拟器 | 不支持 |

验证命令（**只读，不构建**；APK 用 CI artifact）：

```bash
# 期望 native-code 只含 'arm64-v8a' 'x86_64'
aapt2 dump badging app-debug.apk | grep -E "native-code|sdkVersion|targetSdkVersion"
unzip -l app-debug.apk | grep 'lib/'
# 设备侧：确认 abilist 是否含 arm64-v8a
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.product.cpu.abi
```

> 约束提醒：ABI 列表属于 §3 版本矩阵的一部分，**本任务不改**。若要支持 32 位，走 §7 的后续 issue + `docs/decisions.md`。

---

## 2. 各 ROM 的后台 / 省电 / 自启动 / 通知差异

### 2.1 横向对比

| 维度 | Honor / EMUI（D1、D2） | Pixel / AOSP（D3） | MIUI / HyperOS（D5） | ColorOS / OriginOS（D6） |
|---|---|---|---|---|
| 后台限制强度 | **高**（「应用启动管理」自动管理会杀后台） | 低（标准 Doze/App Standby） | **高**（省电策略默认「智能限制」） | **高**（耗电管理默认限制后台） |
| 自启动开关 | 应用启动管理 → 允许自启动/关联启动/后台活动 | 无此概念 | 自启动管理 | 自启动管理 |
| 省电/后台白名单 | 电池优化白名单 + 手机管家耗电管理 | 电池优化「不优化」 | 省电策略「无限制」+ 锁定最近任务 | 耗电管理「允许后台运行」+ 锁定最近任务 |
| 锁屏后是否断网 | 可配「休眠时保持网络连接」，默认可能断 | 标准 Doze 维护窗 | 锁屏清理可能杀进程 | 锁屏清理可能杀进程 |
| 清理后台（最近任务划掉） | 可能连带杀 FGS | 任务移除通常不杀进程 | **常连带杀进程** | **常连带杀进程** |
| 通知权限 | 13+ 运行时权限；另有「锁屏通知」「横幅」二级开关 | 13+ 运行时权限 | 13+ 运行时权限 + 通知样式/重要程度 | 13+ 运行时权限 + 通知类别 |
| 通知渠道可用性 | 渠道可被用户单独关闭 | 渠道可单独关闭 | 渠道可单独关闭 | 渠道可单独关闭 |
| 本项目已知风险 | 老机 EMUI 10 无 FGS 类型；新机 6h 上限 + 自启动 | 6h/24h 上限、16 KB 页 | 任务移除杀进程 | 任务移除杀进程 |

### 2.2 通用准备（所有真机，先做一遍）

1. 记录设备指纹（与 `smoke.yml` 同口径，粘进结果表）：
   ```bash
   adb shell getprop ro.product.model
   adb shell getprop ro.build.version.release
   adb shell getprop ro.build.version.sdk
   adb shell getprop ro.product.cpu.abi
   adb shell getprop ro.build.display.id      # ROM 版本，如 MagicOS/EMUI 串
   ```
2. 安装**与 CI 同一 commit** 的 APK（`applicationId` 带 `.debug`），并记录 `sha256sum`。
3. 授予通知权限（Android 13+）：首页弹窗 → 允许；或 `adb shell pm grant <pkg> android.permission.POST_NOTIFICATIONS`。
4. 打开应用 → 设置 → 确认：通知开、并发默认值、低电量阈值 15%、过热降级开。
5. 配好 Key（`SecureKeyStore`），跑一次 2–3 章的短批次，确认基线可用，**再**做后台专项。
6. 关闭会干扰的开发者选项：`Don't keep activities`、`Suspend execution for cached apps`。

### 2.3 Honor / EMUI / MagicOS（P0，D1 必做）

| 项 | 内容 |
|---|---|
| 必做设置 | ①「应用启动管理」里把 AudioForge 从「自动管理」改为手动，勾选 **允许自启动 / 允许关联启动 / 允许后台活动**（EMUI 10 在「手机管家 → 应用启动管理」或「设置 → 应用 → 应用启动管理」；MagicOS 7+ 在「设置 → 应用和服务 → 应用启动管理」）。②「设置 → 电池 → 更多电池设置 → 休眠时始终保持网络连接」打开。③电池优化里把 AudioForge 设为「不优化」（设置 → 电池 → 启动管理/更多）。④通知：设置 → 通知 → AudioForge → 允许通知 + 锁屏通知。 |
| 验证步骤 | 1) 起 5 章批次 → 锁屏 30 min（`adb shell input keyevent KEYCODE_SLEEP`）→ 唤醒后看进度是否推进。2) 再锁屏 30 min，期间 `adb shell dumpsys activity services <pkg>` 确认 `dataSync` 前台服务在。3) 最近任务划掉 → 现象记录（预期：部分版本会杀进程，重开应恢复）。4) 开「省电模式」，重复一次短批次。 |
| 预期 | 通知常驻、进度递增、无 ANR；不白名单时**允许失败**，但必须能「重开 → 批量继续」；失败要能在 issue 里复现并给出引导文案改进点。 |
| 记录要点 | EMUI 版本、是否开了自启动、是否白名单、失败时的 `logcat` 片段（见 §2.7）。 |

### 2.4 Pixel / AOSP（P0，D3 必做）

| 项 | 内容 |
|---|---|
| 必做设置 | ①设置 → 应用 → 特殊应用权限 → 电池优化 → AudioForge「不优化」（仅为验证 Doze 外的情况；Doze 场景另测）。②通知权限允许。③开发者选项里不要开「暂停执行已缓存的应用」。 |
| 验证步骤 | 1) 5 章批次 + 锁屏 30 min。2) `adb shell dumpsys deviceidle force-idle` 进 Doze（失败则 `adb shell cmd deviceidle force-idle`；再失败改用锁屏 30 min 替代），确认 FGS 是否继续；`dumpsys deviceidle unforce` 退出。3) Android 15/16：连续跑接近 5h（或用缩短看门狗的 debug 构建）验证「5h 收尾 → 下一批继续」文案与状态。4) 16 KB 页设备（如 Pixel 9 系列，以厂商说明为准）：安装 + FFmpeg 拼接 + 播放，确认原生库加载不崩。 |
| 预期 | Doze 下前台服务通常继续；6h 上限不会撞到（5h 主动收尾）；16 KB 设备可正常跑 FFmpeg。 |
| 记录要点 | 是否 Doze、`dumpsys deviceidle` 输出、6h/24h 相关日志、16 KB 是否开启（`adb shell getconf PAGE_SIZE`）。 |

### 2.5 MIUI / HyperOS（P1）

| 项 | 内容 |
|---|---|
| 必做设置 | ①设置 → 应用设置 → 应用管理 → AudioForge → **自启动**打开、**省电策略**改「无限制」。②最近任务里下拉/长按**锁定** AudioForge。③通知：允许通知 + 重要程度。 |
| 验证步骤 | 1) 5 章批次 → 锁屏 30 min。2) 最近任务划掉（不锁定 vs 锁定各一次），记录是否被杀。3) 省电模式再跑一次短批次。 |
| 预期 | 锁定 + 无限制后可续跑；不锁定时允许被杀，但重开能恢复。 |

### 2.6 ColorOS / OriginOS（P1）

| 项 | 内容 |
|---|---|
| 必做设置 | ①设置 → 电池 → 应用耗电管理 → AudioForge → 允许后台运行 / 允许自启动 / 允许关联启动。②最近任务锁定。③通知：设置 → 通知与状态栏 → 应用通知 → 允许。 |
| 验证步骤 | 同 §2.5：锁屏 30 min、划掉任务、省电模式。 |
| 预期 | 同 §2.5。 |

### 2.7 后台诊断命令与日志（只读，不需要 root 的优先）

```bash
PKG=com.estimatefaster.audioforge.debug
# 前台服务是否在跑
adb shell dumpsys activity services "$PKG" | grep -iE "ServiceRecord|foreground|dataSync"
# WorkManager / JobScheduler 是否还有任务
adb shell dumpsys jobscheduler | grep -A 8 "$PKG"
# 应用 standby bucket（部分 ROM 拒绝执行）
adb shell am get-standby-bucket "$PKG"
# 通知渠道（4 个：generation / generation_done / generation_failed / playback）
adb shell dumpsys notification --noredact | grep -A 3 "$PKG"
# 电量/省电状态
adb shell dumpsys battery | grep -E "level|powered|status"
# 模拟低电（部分 ROM 需 root；失败就手动放低电量）
adb shell dumpsys battery set level 10; adb shell dumpsys battery reset
# 模拟省电模式
adb shell settings put global low_power 1; adb shell settings put global low_power 0
# 断网 / 恢复
adb shell svc wifi disable; adb shell svc data disable
adb shell svc wifi enable;  adb shell svc data enable
# 弱网（emulator 控制台，真机不可用）
adb emu network delay gprs; adb emu network speed gsm
# 关键日志（应用 tag 全为 AudioForge*，另有系统侧 WM-/JobScheduler）
adb logcat -v time -s AudioForgeGenerationWorker:* AudioForgeJobQueue:* AudioForgeGenNotify:* AudioForgeNotify:* WorkManager:* JobScheduler:* ActivityManager:E
```

**杀进程的两种语义**（验证「杀进程恢复」时必须区分，否则结论会错）：

| 操作 | 命令 | 系统语义 | 预期 |
|---|---|---|---|
| 轻微杀进程 | `adb shell am kill "$PKG"`（App 需在后台） | 只杀进程，包不进入 stopped 状态 | WorkManager 可重新调度；重开 App 应 `sweepRunning` 恢复 |
| 强制停止 | `adb shell am force-stop "$PKG"` | 等价系统「强行停止」，任务/作业被清 | **不会自动跑**；重开 App 后能继续（这是正确预期，不要记成 bug） |

---

## 3. 必测清单（P6-05，15 项）

`覆盖`列：`CI` = emulator/单测已覆盖；`真机` = 只能在真机做，本环境 BLOCKED。
失败判定统一参考执行计划 §14.4（失败演练）与 §14.5（性能目标）。

| ID | 场景 | 操作要点 | 通过标准 | 覆盖 | 关联 |
|---|---|---|---|---|---|
| M01 | 冷启动 | 杀进程后点图标，`am start -W` 记 `TotalTime` | 中端机 < 2.5s；无白屏/崩溃；上次状态正确渲染 | 部分 CI（启动 MainActivity） | §14.5 |
| M02 | 导入 | TXT：UTF-8/BOM/GBK/UTF-16LE/BE/Big5/非法字节；大字文件 | 编码识别正确；章节切分符合预期；错误有明确提示；不 OOM | JVM 单测 | P2-05/06 |
| M03 | 解析 | 5 章真实文本；含「第X章/卷」；无标题单章 | 角色/片段结构正确；覆盖率达标；失败可重试 | JVM 单测 | P2-11 |
| M04 | 渲染 | 选音色 → 5 章渲染 | 每章产出可播文件；失败段落可重试；进度与通知一致 | JVM 单测 | P2-12 |
| M05 | 播放/通知 | 播放章节；通知栏控制；耳机插拔/蓝牙；音量键 | 通知显示章节与进度；暂停/继续/停止可用；音频焦点正确；锁屏媒体控制可用 | emulator（2 项） | P5-08 |
| M06 | 后台批次 30min | 5 章批次 → 立即锁屏 30 min，中途不解锁 | 进度持续推进或按预期暂停；无 ANR；通知存活 | **真机**（BLOCKED） | P3-10 |
| M07 | 锁屏 | 锁屏 30 min（同 M06 的独立记录：锁屏前后状态） | 唤醒后状态与通知一致；无「假运行」 | **真机** | P3-10 |
| M08 | 杀进程恢复 | 批次中途 `am kill`；再 `am force-stop` 各一次 | `am kill` 后重开能继续；`force-stop` 后重开能继续；已完成章节不重做 | **真机** + WorkManager 单测 | P3-08 |
| M09 | 重启恢复 | 批次中途 `adb reboot`；开机后打开 App | 状态不丢；running 被 sweep 回 pending；可一键继续；不重复计费 | **真机** + 单测 | P3-08 |
| M10 | 断网/弱网 | 飞行模式中途断网 → 恢复；弱网限速（emulator `network delay`） | 断网暂停、恢复续传；429/503 退避不烧 API；错误可见 | 单测（MockWebServer） | P3-06/P6-04 |
| M11 | 低电/省电 | 电量 10% + 省电模式 | 低于阈值暂停并提示；省电模式降并发或不崩；恢复后继续 | 单测（约束） | P3-06 |
| M12 | 热降级 | 高负载/`thermalservice override` 到 SEVERE/CRITICAL | SEVERE 降并发；CRITICAL 暂停并提示；不 ANR | 单测（映射） | P3-06 |
| M13 | 导出 | 导出单章/多章；API 26–28 与 29+ 各一台 | 29+ 出现在 `Music/AudioForge/<title>`；26–28 走 app-specific + 分享；文件可播 | emulator 1 项 | P2-15 |
| M14 | Key 配置 | 输入 Key → 重启 App → 清除 Key → 再请求 | 重启后仍可用（加密存储）；清除后请求 401 并有引导；日志无明文 Key | emulator 6 项（Keystore） | P2-04 |
| M15 | 存储清理 | 设置保留天数 → 触发清理；造孤儿片段；剩 200 MB | 保留期内不删；孤儿被清；空间不足预检拒绝、不写坏文件 | 单测（13 项） | P3-11 |

**附加（不占 15 项，但真机上顺手记）**

| ID | 场景 | 通过标准 |
|---|---|---|
| S01 | 通知权限拒绝（13+） | 任务仍运行、不崩；通知静默跳过（`logcat` 可见 `skip ... POST_NOTIFICATIONS`）；当前无独立横幅提示（见 §7 建议） |
| S02 | 最近任务移除（划掉） | 记录是否被杀；重开能继续 |
| S03 | 单独关闭通知渠道（playback/generation） | 不影响生成；播放控制缺失时有提示 |
| S04 | 蓝牙耳机播放 + 来电打断 | 恢复播放；不炸音频焦点 |
| S05 | 2000 章列表滚动 | 首帧 < 500 ms；滚动 60fps（可用 `gfxinfo` 粗测） |

---

## 4. CI 覆盖现状与缺口

### 4.1 现有覆盖：emulator API 35，15 项 instrumented

`.github/workflows/smoke.yml`：`api-level: 35` / `target: google_apis` / `arch: x86_64` / `profile: pixel_6`，跑 `connectedDebugAndroidTest`；无论成败都上传 `logcat-smoke.txt`、`smoke-home.png`、`device-info.txt`。触发条件是 `app/src/main/**`、`app/src/androidTest/**`、workflow 自身——**纯文档 PR 不会触发**。

| 测试类 | 项数 | 证明什么 | 证明不了什么 |
|---|---|---|---|
| `PlaybackInstrumentedTest` | 2 | ExoPlayer 能解 WAV 并起播；`MediaSessionService` 在 manifest 中声明 | 后台/锁屏播放、蓝牙、音频焦点、通知按钮 |
| `ProbeWorkerInstrumentedTest` | 2 | 前台 Worker 能跑并发通知；取消后状态为 CANCELLED 且不崩 | 30 min 长跑、OEM 杀进程、Doze、重启恢复 |
| `SecureKeyStoreInstrumentedTest` | 6 | 加解密往返、清除、AAD 错误返回 null、随机 IV、DataStore 无明文 | 真机 Keystore 差异（部分 ROM）、生物识别/备份排除 |
| `FfmpegInstrumentedTest` | 1 | FFmpeg 可执行：version + 静音 + 拼接 | ARM64 执行、16 KB 页、长音频内存峰值 |
| `ChapterStitcherInstrumentedTest` | 2 | 3 段拼接带停顿；空列表抛错 | 350/700 段规模、真实 TTS 音频、AAC 回退 |
| `RoomInstrumentedTest` | 1 | 设备上 Room 读写 | 迁移、并发、2000 章规模 |
| `ExportManagerInstrumentedTest` | 1 | 导出文件可读 + pending 行发布 | MediaStore 真机路径、API 26–28 回退、相册可见性 |

另有 CI 的 `lintDebug testDebugUnitTest assembleDebug`（`.github/workflows/ci.yml`）与 JVM/Robolectric 用例，覆盖纯逻辑（约束、看门狗、恢复、清理等），但**不产生任何真机证据**。

### 4.2 覆盖不到（缺口）

| 缺口 | 原因 | 影响 |
|---|---|---|
| OEM ROM 后台策略 | CI 只有 AOSP 镜像 | P0 风险：Honor/MIUI 上「看起来在跑、其实被杀」不会在 CI 暴露 |
| ARM64 真机 | CI 用 x86_64 | 原生库/性能/功耗差异未验证 |
| 锁屏 30 min / Doze | 测试无法长跑，也不该在 CI 做 | P3-10 无法解除 |
| 杀进程 / 重启 | 会打断测试进程 | P3-08 只有单测证据 |
| 弱网/断网 | CI 网络稳定 | 退避/续传只在 MockWebServer 层验证 |
| 通知权限拒绝路径 | smoke 里用 `pm grant` 直接授权 | S01 未验证 |
| 16 KB 页设备 | CI 镜像非 16 KB | 原生库加载风险 |
| 6h/24h FGS 上限 | 时长不可行 | 只有 5h 看门狗单测 |
| 低电/省电/热 | 模拟器可部分模拟，未纳入 CI | 只有约束单测 |

### 4.3 BLOCKED 记录（与 P3-10 同因）

- **现象**：无法在真实 Honor/EMUI、Pixel、MIUI、ColorOS 设备上执行 M06–M09、M10（真机弱网）、M11/M12（真实电量与温度）、M13（MediaStore 真机路径）。
- **命令/日志**：本环境无 adb 设备（`adb devices` 为空）、无厂商 ROM、无用户 Key；按任务约束也**禁止本地构建/CI**，不能自造 emulator 长跑证据。
- **已尝试/已有替代**：emulator smoke 15 项、WorkManager TestDriver、约束/看门狗/恢复/清理单测、MockWebServer 重试测试。
- **怀疑点**：OEM 自启动与省电策略只能真机判定；代码侧已具备通知、FGS、约束、恢复、看门狗、清理，缺的是设备与证据。
- **结论**：**P6-05 未完成（文档部分完成，验收部分 BLOCKED）**，与 `docs/phase3-gate.md` 的 P3-10 同一阻塞。

### 4.4 解除条件

1. 提供至少 1 台 **Honor/EMUI（或 MagicOS）** + 1 台 **Pixel（Android 15/16）**；MIUI/ColorOS 尽力。
2. 可 `adb` 连接、允许安装 debug APK、能跑 30 min 锁屏测试。
3. 按 §3 的 M01–M15 跑一遍，结果填 §5 模板；失败项开 issue（带 logcat 片段）。
4. 若只能拿到云真机：确认支持 30 min 会话、可 `adb shell`、可跑 FGS；否则仍记 BLOCKED。

---

## 5. 结果记录表模板

### 5.1 单轮记录（每设备 × 每轮一行；场景逐项填）

| 字段 | 示例 / 取值 |
|---|---|
| 日期 | 2026-11-10（Asia/Shanghai） |
| 设备型号 | Honor 20（`adb getprop ro.product.model`） |
| ROM / 版本串 | EMUI 10.0.0 / Magic UI 3.1（`ro.build.display.id`） |
| Android / API | 10 / 29 |
| ABI | arm64-v8a（`ro.product.cpu.abilist`） |
| 构建 | `main@42364e6` + APK `sha256:…` |
| 关键设置 | 自启动开 / 白名单开 / 通知允许 / 省电模式关 |
| 场景 | M06 |
| 结果 | 通过 / 失败 / 阻塞（BLOCKED）/ 不适用（N/A） |
| 证据 | `p6-05-honor20-M06-logcat.txt`、`p6-05-honor20-M06-1.png` |
| issue | `#123`（失败才有） |

### 5.2 汇总表（贴到 issue / release notes；列可裁剪，但 P0 项 M06/M07/M08/M09 必须在）

| 设备 | ROM | API | M01 | M05 | M06 | M07 | M08 | M09 | M10 | M11 | M13 | M14 | 结论 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Honor 20 | EMUI 10 | 29 |  |  |  |  |  |  |  |  |  |  |  |
| Pixel 9 | Android 16 | 36 |  |  |  |  |  |  |  |  |  |  |  |
| Redmi | HyperOS | 35 |  |  |  |  |  |  |  |  |  |  |  |

每一格只填 `P`（通过）/ `F`（失败，必须有 issue）/ `B`（BLOCKED）/ `-`（N/A）。**空格 = 未测，不能当作通过。**

### 5.3 证据与脱敏

- 默认放 **GitHub issue 附件 / Actions artifact**，不入库。
- 若必须入库：只允许**纯文本**、已脱敏、单文件 < 100 KB，放 `docs/fixtures/device-logs/`；不提交 APK/截图大图。
- 命名：`p6-05-<device>-<Mxx>-<logcat|1.png|dumpsys.txt>`。
- **禁止**：Key、Base URL 里的 token、小说正文、用户音频、`local.properties`、keystore。日志里若出现 token，先删行再贴。

---

## 6. 发布门槛

### 6.1 必须真机通过（阻塞 v1.0.0）

| 门槛 | 任务 | 必须的设备 | 具体项 | 证据 | 不通过后果 |
|---|---|---|---|---|---|
| G1 后台续跑 | **P3-10** | Honor/EMUI（P0）+ Pixel（P0） | M06、M07、M08、M09、M10；通知可见；停止能收尾 | 汇总表 + logcat | 不发版：核心卖点是「长任务后台跑」 |
| G2 播放/通知后台 | **P5-08** | 任一 P0 设备（建议 +1 台国产 ROM） | M05、S02、S04：后台播放、通知控制、耳机、任务移除 | 截图 + logcat | 不发版：播放是主流程 |
| G3 签名 APK 安装/升级 | **P6-09** | 任一 P0 设备 | 签名 release APK 安装、覆盖升级（versionCode+1）、`checksum` 校验、卸载重装 | 版本页截图 + sha256 | 不发版：装不上/升不上去 |
| G4 真机主链路 | P6-11 | 任一 P0 设备 | M01–M04、M13、M14：导入→解析→渲染→播放→导出→Key | 截图 + 结果表 | 不发版 |

### 6.2 尽力项（不阻塞，但要在 release notes 声明）

| 项 | 任务 | 说明 |
|---|---|---|
| MIUI/HyperOS、ColorOS | P6-05 | 拿不到设备 → 写「未验证」，并保留电池白名单引导文案 |
| Android 8 / API 26 边界 | P6-05 | 若无 8.x 机，标注「minSdk 26 仅声明、未实机验证」 |
| 16 KB 页 | P6-05 | 无 16 KB 设备 → 标注风险，不静默忽略 |
| 热降级 M12 | P3-06 | 只有单测；真机高温不易复现 |
| 平板/折叠、32 位 | — | v1 明确不支持 32 位；平板为 v1.1 |

### 6.3 门槛判定规则

- G1–G4 全绿才算 P6-05/P6-11 可推进；任一项 BLOCKED 时，**P6-05 保持 BLOCKED**，不写「已完成」。
- 允许「带已知问题发布」的唯一情形：失败项有 issue、有用户可操作的 workaround（如手动加白名单/手动继续），且写进 release notes。
- emulator 绿 + 无真机 = **BLOCKED**，不是「通过」。

---

## 7. 未决项与后续 issue 建议（本任务不实施）

| # | 建议 | 为什么 | 影响面 |
|---|---|---|---|
| 1 | 电池白名单**一键跳转**（`Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，带 `package:` URI），失败时回退到应用详情页 | 现在只有 `onboarding_battery_body` 文字；Honor/MIUI 用户找不到入口，后台失败会被当成 bug | 代码（onboarding/settings）+ 文案；需真机验证跳转是否被 ROM 拦截 |
| 2 | Honor/MagicOS 专属引导（自启动三开关） | MagicOS 的「应用启动管理」比原生多两级；通用文案不够 | 文案/引导页 |
| 3 | smoke 加 API 26 + 36 矩阵（或至少 36） | 现在只有 API 35；target 36 的新行为没人测 | `.github/workflows/smoke.yml`（本任务不改） |
| 4 | smoke 里补「通知权限拒绝」用例 | S01 目前无覆盖 | androidTest |
| 5 | 32 位/ABI split 决策 | 现无 armeabi-v7a；若用户群有 32 位机需明确不支持或补构建 | `docs/decisions.md` + 构建（需版本矩阵评审） |
| 6 | 16 KB 页安装验证 | ffmpeg AAR 对齐有静态证据，但无设备实测 | 真机/云真机 |

> 以上都不属于 P6-05 的「文档」范围，留给后续任务；本任务只记录，不改代码/CI。

---

## 8. 参考

- 执行计划：`docs/android-execution-plan.md` §3.7、§11.2、§14.2–§14.5、§15 P3-10/P5-08/P6-05/P6-09
- 可行性：`docs/android-port-feasibility.md` §3.7（后台模型）、§5.1.4（16 KB）
- 门禁：`docs/phase3-gate.md`（P3-10 BLOCKED）、`docs/phase2-gate.md`
- CI：`.github/workflows/smoke.yml`、`.github/workflows/ci.yml`
- Android 官方：FGS 超时（6h/24h）、FGS 类型（dataSync/mediaPlayback）、`POST_NOTIFICATIONS`、Doze/App Standby、16 KB 页
