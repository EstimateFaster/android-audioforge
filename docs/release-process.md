# Release 流程与签名（P6-09）

> **状态**：release workflow / 校验脚本加固完成；**未在本地构建、未在本地跑 CI**（按仓库硬规则）。真实验证由 tag 触发的 GitHub Actions 完成。
> **基线**：`main@2d3b8e6`；分支 `feat/p6-09-release-workflow`；日期 2026-11-08（Asia/Shanghai）。
> **文件**：`.github/workflows/release.yml`、`scripts/verify-apk.sh`、本文。
> **对照**：执行计划 §14.6（验收门）、§15 P6-09、§16.2–§16.6；`docs/security-review.md` SEC-01/SEC-09/SEC-12；`docs/abi-split-evaluation.md`（P6-02 未落地）；`docs/r8-evaluation.md`（P6-01 未落地）。
> **范围**：本文只描述发布链路。未改 `ci.yml`/`smoke.yml`、`app/build.gradle.kts`、版本矩阵、`docs/progress.md`。

---

## 0. 结论（TL;DR）

1. **唯一发布方式**：推送 `vX.Y.Z` tag（或手动 `workflow_dispatch`），由 GitHub Actions 构建**签名** release APK。**本地不构建 release APK**。
2. **权限**：workflow 默认 `contents: read`，仅 `release` job 提升为 `contents: write`；checkout `persist-credentials: false`，构建后的 git 凭证不会被后续步骤/第三方 Action 复用。
3. **版本输入**：tag 与手动输入都必须是 `v?X.Y.Z` 或 `v?X.Y.Z-<suffix>`；通过 `env:` 传给 shell（不做表达式插值），校验失败立即 `exit 1`。`versionCode` = `github.run_number`，只增不减。
4. **产物**：`audioforge-<version>.apk` + `checksums.txt`（sha256）；若存在 `mapping.txt` / `output-metadata.json` 一并归档。同一 Release 附带自动生成的 notes，可发布后手写覆盖。
5. **构建配置未变**：`isMinifyEnabled=false`、无 ABI split → 当前产物是 **universal APK**（`arm64-v8a` + `x86_64` 都必须在）。R8（P6-01）与 ABI split（P6-02）落地后需同步改本文与 `release.yml`。
6. **仍未关闭的供应链项**：Actions 仍用可变 tag（`@v4`/`@v2`），SEC-01 的「pin 到 commit SHA」未在本任务做；见 §9。

---

## 1. 触发方式

| 方式 | 条件 | 版本来源 | 说明 |
|---|---|---|---|
| tag push | tag 匹配 `v*` | tag 名（`v1.0.0`） | 主路径；tag 必须指向已通过 main CI 的提交 |
| 手动 dispatch | Actions → Android Release → Run workflow | `version` input | 用于补发/重跑；输入可带或不带 `v` 前缀 |

**版本规则**

- 允许：`v1.0.0`、`1.0.0`（tag 触发时实际只有 `v1.0.0` 会命中）、`v1.0.0-rc.1`、`1.0.0-beta.2`。
- 拒绝：空值、`v1.0`、`v1.0.0.0`、`1.0.0-`、带空格/`/`/`;`/`$()` 等任何其他字符。
- `versionName` = 去掉前导 `v` 的版本号；`tag` = `v<versionName>`；`versionCode` = 本次 workflow 的 `github.run_number`。
- `versionCode` 由 GitHub 保证只增；**不要**手动传 `-PversionCode` 覆盖，也不要复用旧 tag。

**并发**：`concurrency.group = android-release-${{ github.ref }}`、`cancel-in-progress: false`。同一 tag/ref 的发布串行执行，避免两个 run 同时写同一个 Release；不同 ref 可并行。

**手动 dispatch 的 ref**：请在 `main` 上运行。dispatch 会在所选 ref 的 `github.sha` 上创建/更新 tag 与 Release；从 feature 分支 dispatch 等于发布未合入的代码。

**权限**：workflow 级 `permissions: contents: read`；job 级 `contents: write`（`softprops/action-gh-release` 需要）。没有 `actions: write`、`packages: write` 等多余权限。

---

## 2. Secrets 清单（只列名称，不写值）

仓库 → **Settings → Secrets and variables → Actions → Repository secrets**：

| Secret | 用途 | 备注 |
|---|---|---|
| `ANDROID_KEYSTORE_BASE64` | release keystore 的 base64 内容 | `base64 -w0` 后的单行；解码到 `$RUNNER_TEMP/release.jks`，`chmod 600` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore store password | 与 keystore 一起离线备份 |
| `ANDROID_KEY_ALIAS` | 签名 key alias | 大小写敏感 |
| `ANDROID_KEY_PASSWORD` | 签名 key password | 可与 store password 相同，但不要假设 |

规则：

- **只放 GitHub Secrets**。禁止写入仓库、`keystore.properties`、`.env`、Issue、聊天、日志、截图。
- keystore 生成/备份命令见执行计划 §16.4；生成后**离线备份 `.jks` 与三个密码**。丢失 keystore = 无法覆盖升级，只能让用户卸载重装。
- 轮换：GitHub 界面更新 secret 即可，workflow 无需改；换 keystore 会改变签名证书，老用户必须卸载重装（见 §8）。
- workflow 启动时先检查四个 secret 是否存在、base64 能否解码、`keytool -list` 能否用密码/别名打开；失败在 1 分钟内报错，不必等 Gradle。

---

## 3. 构建与校验流程

`release` job 顺序：

1. **Checkout**（`persist-credentials: false`）。
2. **Validate version**：校验 tag/input 与 `run_number`；输出 `version` / `tag` / `version_code`，写入 Step Summary。
3. **JDK 17 + SDK 37.0 / Build Tools 37.0.0 + Gradle setup**。
4. **解码并验证 keystore**（见 §2）。
5. **`./gradlew assembleRelease`**：`-PversionName=<version> -PversionCode=<run_number>`；签名参数来自 Secrets 对应的环境变量（`ANDROID_KEYSTORE_PATH` 由解码步骤写入 `GITHUB_ENV`）。
6. **`scripts/verify-apk.sh`**：对 release APK 做严格校验（见 §4）。
7. **收集产物 + sha256**。
8. **上传 Actions artifact**（`audioforge-release-apk`，保留 30 天）。
9. **创建/更新 GitHub Release**，上传 `release/*`。

构建配置现状（**本任务未改**）：

- `isMinifyEnabled=false`、`isShrinkResources=false` → `mapping.txt` 目前不会生成；workflow 会跳过并打印 notice。
- 无 `splits.abi`/AAB → 单个 universal APK，包含 `arm64-v8a` + `x86_64`；32 位 `armeabi-v7a` 不支持。
- `assembleRelease` 会触发 `lintVitalRelease`（`checkReleaseBuilds=true`），release 级 lint 失败会阻断发布。

---

## 4. 校验脚本 `scripts/verify-apk.sh`

```bash
# CI release 调用（期望值全部显式传入）
bash scripts/verify-apk.sh app/build/outputs/apk/release/app-release.apk \
  --package com.estimatefaster.audioforge \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION" \
  --abi arm64-v8a \
  --abi x86_64

# CI debug 调用（ci.yml，未改）：只做默认合理性校验
bash scripts/verify-apk.sh app/build/outputs/apk/debug/app-debug.apk
```

检查项（任一失败即非 0 退出）：

| 项 | 工具 | 失败含义 |
|---|---|---|
| 签名 | `apksigner verify --verbose --print-certs` | 未签名/签名损坏/v1-v3 方案不兼容 |
| 包名 | `aapt2 dump badging` | 构建变体错、applicationId 被改 |
| `versionCode` | 同上 | 非正整数，或与 `run_number` 不一致 |
| `versionName` | 同上 | 格式非法，或与 tag/input 不一致 |
| ABI | 读 APK 内 `lib/<abi>/*.so` | 缺 `arm64-v8a`/`x86_64`，或出现未声明 ABI |
| 16 KB 对齐 | `zipalign -c -P 16 4` | 原生库未按 16 KB 页对齐 |
| 工具/路径 | `--help`、SDK 检查 | 缺 `ANDROID_SDK_ROOT`、build-tools 37.0.0、`aapt2`/`apksigner`/`zipalign`/`unzip` 时给出明确错误 |

默认值用于 debug APK：包名允许 `com.estimatefaster.audioforge` 或其 `.debug` 变体；`versionName` 允许 `X.Y.Z-debug`；ABI 默认要求两个都在。release job 通过显式 `--package/--version-name/--abi` 收紧，不接受 `.debug`。

可选环境变量：`BUILD_TOOLS_DIR`（默认 `<sdk>/build-tools/37.0.0`）、`ALLOWED_ABIS`（默认 `arm64-v8a x86_64`）。缺工具时不会静默跳过，直接报错并提示安装 `build-tools;37.0.0`。

---

## 5. 产物

| 文件 | 何时存在 | 说明 |
|---|---|---|
| `audioforge-<version>.apk` | 总是 | 已签名 universal APK（arm64-v8a + x86_64） |
| `checksums.txt` | 总是 | 对同目录下 APK/`mapping-*`/`output-metadata-*` 的 sha256，`sha256sum -c` 可用 |
| `mapping-<version>.txt` | 仅生成 `mapping.txt` 时（R8 开启后） | 崩溃栈 retrace 用；P6-01 落地前不存在 |
| `output-metadata-<version>.json` | AGP 生成时 | 记录 variant/versionCode/versionName/outputFile；目前通常存在 |

- **Actions artifact**：`audioforge-release-apk`（`release/*`，保留 30 天）。
- **GitHub Release**：同一组文件；`tag_name = v<version>`，`target_commitish = github.sha`；notes 默认自动生成（`generate_release_notes: true`），发布后可在 Release 编辑页手写覆盖/补充。带 `-` 后缀的版本（如 `1.0.0-rc.1`）自动标为 pre-release。
- 用户侧校验：

```bash
sha256sum -c checksums.txt
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --print-certs audioforge-<version>.apk
```

- 数据/备份：release 包 `allowBackup=false`，`data_extraction_rules.xml` 已排除 sharedpref/database/file/external（SEC-09 已修）；签名与包名相关，不涉及新增权限。

---

## 6. 本地不构建声明

- **release APK 只由 GitHub Actions 产出**。本地 Linux VM/开发机只做 `assembleDebug`、单测、lint、调试；不要本地执行 `assembleRelease` 作为发布产物，更不要把本地签名 APK 上传到 Release。
- 本地不持有 release keystore / Secrets。需要验证签名时，用 CI artifact 或 Release 资产，在干净环境里跑 `verify-apk.sh` / `apksigner verify`。
- 本文的「验收」以 GitHub Actions 的 run 日志与 artifact 为准；本地命令只用于阅读/静态检查。

---

## 7. 发布前检查清单

1. `main` CI 绿；tag 指向的 commit 就是通过 CI 的 commit（不要给旧 commit 补 tag）。
2. `versionName` 符合 `X.Y.Z[-suffix]`，且比上一个版本大（语义版本）；`versionCode` 会由 `run_number` 自动递增。
3. 四个 Secrets 存在且可用（可先在测试分支 dispatch 一个 `-rc` 版本验证）。
4. keystore 离线备份可读；签名证书 SHA-256 与上一版一致（覆盖升级的前提）。
5. 发布说明：自动生成的 notes 是否够用；需要手写时在 Release 编辑页补充「新增/修复/已知问题/升级说明」。
6. 下载 `audioforge-<version>.apk`，在真机执行安装/升级/启动/导入/1 章生成/播放/导出（§14.2、§16.5 手工清单）。
7. `checksums.txt` 与 APK 同名同版本；Release 资产没有缺文件。

---

## 8. 失败排查

| 现象 | 可能原因 | 处理 |
|---|---|---|
| `Validate version` 失败 | tag/input 不是 `v?X.Y.Z[-suffix]` | 改用合法 tag；删掉错误 tag 后重打（见回滚） |
| `缺少签名 secrets` | 仓库 Secrets 未配置/名字拼错 | 按 §2 配置；名字必须完全一致 |
| `keystore 无法用 ... 打开` | base64 截断、密码/别名错误 | 重新 `base64 -w0` 生成 `ANDROID_KEYSTORE_BASE64`；核对三个密码/别名 |
| `找不到 build-tools 37.0.0` | SDK 安装步骤失败/路径不对 | 看上一个 step 日志；确认 `build-tools;37.0.0` 已安装；本地可设 `BUILD_TOOLS_DIR` |
| Gradle 签名失败 | `ANDROID_KEYSTORE_PATH` 未传入、alias 不存在 | 确认解码步骤成功；`keytool -list` 本地核对（不要打印密码） |
| `包名不匹配` / `versionName 不匹配` | tag 与构建参数不一致、缓存旧 APK | 检查 `Validate version` 输出；清理 `app/build` 后重跑（CI 是干净 runner，通常是参数问题） |
| `缺少必需 ABI` | 构建配置被改成 split/单 ABI | 本阶段必须 universal；ABI split 属 P6-02，未落地前不要启用 |
| `zipalign 校验失败` | build-tools 不符/产物被重打包 | 用 build-tools 37.0.0；不要手工重签/重打包 |
| `apksigner verify` 失败 | 未签名、签名损坏、v1-only 与目标 SDK 不兼容 | 检查 Secrets 与 Gradle 日志；不要用 debug 签名发布 |
| GitHub Release 403 | job 缺 `contents: write` 或仓库策略限制 | 确认 job 级 permissions 未被改；检查仓库 Actions 权限 |
| `fail_on_unmatched_files` | `release/` 为空/只匹配到部分文件 | 看 `Collect artifacts` 日志；至少要有 APK + `checksums.txt` |
| 同一 tag 的 run 排队 | concurrency 串行 | 正常行为；等前一个结束，不要手动取消正在上传的 run |

---

## 9. 回滚

**发布前（run 失败/校验失败）**

- 直接修因后重跑；不要为了绕过校验改脚本。
- 如果 tag 打错但尚未发布：删除远端 tag（`git push origin :refs/tags/vX.Y.Z`）与本地 tag，修正后重打。删除 tag 前确认没有 Release/下载链接引用它。

**发布后（发现严重问题）**

1. **不要删除旧 tag/Release**（外部链接、checksums 会失效）；优先发新的 patch 版本（`vX.Y.(Z+1)`），用新 `versionCode` 覆盖升级。
2. 如需立刻止损：在 GitHub Release 编辑页把该版本标为 **pre-release** 或改为 draft，并从 Release 资产中移除 APK；保留 tag 与说明，避免用户继续下载。
3. 通知渠道（Release notes/README）注明问题版本与建议升级版本；不要指导用户安装来路不明的重打包 APK。
4. **禁止复用旧 `versionCode`/tag**；Android 不允许同版本号覆盖不同内容，复用会让升级/回滚不可预测。
5. 签名 keystore 丢失/泄露：视为最高优先级事件。泄露则旧证书作废、换新 keystore 发新版（用户需卸载重装）；丢失则无法再发可覆盖升级的版本，只能换包名/新证书并写清迁移说明。

---

## 10. 已知残余风险

| # | 风险 | 现状 | 后续 |
|---|---|---|---|
| 1 | Actions 仍用可变 tag（`actions/checkout@v4`、`softprops/action-gh-release@v2` 等），SEC-01 的 pin SHA 未完成 | 已做 job 级最小权限 + `persist-credentials: false`；第三方 Action 仍能接触签名 secrets 与 `contents: write` | 后续把全部 Action pin 到 commit SHA（Dependabot 更新），优先替换/审计 `softprops/action-gh-release` |
| 2 | `versionCode` 依赖 `github.run_number`；删除/重建 workflow 文件不会重置，但手动 dispatch 旧版本号可能指向已有 tag | 已校验格式；未校验 tag 是否已存在/指向当前 SHA | dispatch 补发前人工确认 tag/Release 状态 |
| 3 | 未开 R8（P6-01）：APK 较大、`Log.*` 保留、无 `mapping.txt` | 本任务刻意不改 `build.gradle.kts` | P6-01 落地后：`mapping.txt` 自动归档（workflow 已支持），补 retrace 文档 |
| 4 | 未开 ABI split（P6-02）：universal APK ≈ 60 MB | 校验脚本要求两个 ABI 都在 | P6-02 落地后需改 `release.yml`/`verify-apk.sh` 支持多 APK（当前脚本单 APK 入参） |
| 5 | Release notes 默认自动生成，质量取决于 PR 标题 | `generate_release_notes: true` | 发布前人工审阅；P6-10 建立 `docs/release-notes.md` 模板后可接入手写正文 |
| 6 | 构建未在本地复验（仓库硬规则） | 只做静态检查 | 首次 tag 发布时看 Actions 全绿与 artifact；失败按 §8 排查 |

---

## 11. 相关文档

- [执行计划 §15 P6-09 / §16 CI/CD 与发布](android-execution-plan.md)
- [安全复查（SEC-01/09/12）](security-review.md)
- [R8 评估（P6-01）](r8-evaluation.md)
- [ABI split 评估（P6-02）](abi-split-evaluation.md)
- [设备矩阵与真机验收（P6-05）](device-matrix.md)
- [进度与已发布版本](progress.md)
