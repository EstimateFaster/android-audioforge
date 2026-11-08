# 交给 AI（DSH）执行：操作说明

## 1. 仓库地址

```
https://github.com/EstimateFaster/android-audioforge
```

公开仓库，AI 可直接读取。签名/密钥/API Key **不在仓库里**。

## 2. 直接把这段丢给 AI

```text
你是 DSH，负责 StoryForge 的 Android 原生重构。
仓库：https://github.com/EstimateFaster/android-audioforge

先读：
1) AGENTS.md
2) docs/android-execution-plan.md（唯一执行计划）
3) docs/android-port-feasibility.md
4) docs/progress.md

从 docs/android-execution-plan.md §15 的 P0-01 开始，按任务 ID 顺序执行。
每个任务：先读依赖和验收标准 → 实现 → 跑验收命令 → 更新 docs/progress.md → 提交 → 再下一个。
不要提交密钥；release APK 只由 GitHub Actions 产出；不要擅自改版本矩阵。
遇到阻塞先写 BLOCKED 记录，不要连续盲改。
```

## 3. 你（人类）要做的三件事

### 3.1 加 Release 签名 Secrets

在 GitHub 仓库 **Settings → Secrets and variables → Actions** 添加：

| Secret | 值 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | release keystore 的 base64（单行） |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key 密码 |

生成一次（在仓库外执行，备份好）：

```bash
keytool -genkeypair \
  -keystore ~/audioforge-release.jks \
  -alias audioforge \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass '<强密码>' -keypass '<强密码>' \
  -dname "CN=AudioForge, OU=Mobile, O=EstimateFaster, L=Shanghai, ST=Shanghai, C=CN"

base64 -w0 ~/audioforge-release.jks > ~/audioforge-release.jks.b64
# 把 .b64 内容填入 ANDROID_KEYSTORE_BASE64
```

> **不要**把 `.jks` / 密码提交到仓库、Issue、聊天或日志。丢失 keystore 后无法覆盖升级。

### 3.2 让 AI 有写权限

- 如果 AI 在本地/容器里跑：给它一个对该仓库有 `repo` 权限的 GitHub token（或让它用 `gh auth login`）。
- 如果 AI 只在网页里读：它可以读代码并给 patch，但需要你/另一个有权限的 Agent 推送。

### 3.3 第一次构建

- push 到 `main` 会自动跑 `.github/workflows/ci.yml`（lint + unit test + debug APK）。
- 打 tag 会跑 `.github/workflows/release.yml`（签名 release APK + GitHub Release）：

```bash
git tag v0.1.0
git push origin v0.1.0
```

下载：仓库 **Actions** 页的 `audioforge-release-apk`，或 **Releases** 页的 `audioforge-0.1.0.apk`。

## 4. 当前初始配置包含什么

- Gradle 9.9.0 wrapper、AGP 9.5.1、Kotlin 2.2.10、compileSdk 37 / target 36 / min 26。
- Compose + Material 3 Expressive 主题与首页空壳。
- Room / WorkManager / Media3 / OkHttp / DataStore / Paging / FFmpegKit 依赖。
- Manifest：权限、WorkManager `dataSync` 前台服务合并、FileProvider、txt 分享/打开入口。
- Assets：63 音色池、三段提示词（来自 `docs/fixtures`）。
- CI：lint + unit test + debug APK + APK 校验；Release：签名 + Artifact + GitHub Release。
- 文档：执行计划、可行性报告、进度、DSH 操作说明。

## 5. 验收（Phase 0）

- [ ] CI 绿；debug APK 可下载。
- [ ] release APK 已签名，`apksigner verify` / `zipalign -P 16` 通过。
- [ ] 真机/模拟器：启动不崩。
- [ ] FFmpegKit `-version` 成功。
- [ ] Room 读写成功。
- [ ] WorkManager `dataSync` 通知可见、任务能跑。
- [ ] 锁屏 30 分钟不中断（或可恢复）。

完成后按 `docs/android-execution-plan.md` 进入 P1。
