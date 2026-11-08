# Phase 6 门禁（P6-12）

日期：2026-11-09（Asia/Shanghai）
基线：main（af116a5 + release v1.0.0）
验证：GitHub Actions；未做本地构建。

## 发布产物

- Release：https://github.com/EstimateFaster/android-audioforge/releases/tag/v1.0.0
- APK：audioforge-1.0.0.apk，62,020,552 bytes，sha256 `353a7579fe6e83b3de47507d967fdda271724c553729eaaa89e5fe6dc27271ad`
- checksums.txt：sha256 `fb7c5cfebf4dfc7af21d41d02fc648103aa97b760041becaad12a8fe9ac0a8f1`
- output-metadata-1.0.0.json：已上传
- 签名校验：apksigner v2 true；包名/versionName/versionCode/ABI 校验通过（verify-apk: OK）
- 构建：GitHub Actions release run 42535376555（main，workflow_dispatch 1.0.0）

## 任务状态

| 任务 | 状态 | 说明 |
|---|---|---|
| P6-01 R8/混淆评估 | ✅ | docs/r8-evaluation.md；minify 未开启（残 risk，需实测） |
| P6-02 ABI split/体积 | ✅ | docs/abi-split-evaluation.md；splits 未开启（60MB universal） |
| P6-03 依赖校验/锁定 | ✅ | docs/dependency-audit.md；P0 verification metadata 未落地（残 risk） |
| P6-04 网络/重试/并发 | ✅ | 共享 OkHttp + Retry-After（PR #74） |
| P6-05 设备矩阵/后台 | 🚫 BLOCKED | 无 Honor/Pixel 真机；docs/device-matrix.md |
| P6-06 音频 A/B | ✅ | docs/audio-ab.md；pitch/限幅缺陷已修（PR #80）；真机 ABX BLOCKED |
| P6-07 性能/内存 | ✅ | docs/performance-review.md；真机基线 BLOCKED |
| P6-08 安全复查 | ✅ | docs/security-review.md；SEC-01 Actions pinning 待办 |
| P6-09 Release workflow | ✅ | 权限/版本校验/签名/verify/checksums/Release |
| P6-10 发布文档 | ✅ | README + release-notes |
| P6-11 v1.0.0 发布 | ✅ | 签名 APK + checksums 已发布 |
| P6-12 发布后清单 | ✅ | docs/post-release-checklist.md + 本文档 |

## 门禁结论

- CI：main Android CI 42535000986 success；emulator smoke 42535004973 success（含 UI/播放/导出/Room/FFmpeg）。
- 发布：v1.0.0 由 Actions 构建并签名，APK/checksum 可下载。
- BLOCKED：真机后台/设备矩阵（P3-10/P6-05/P6-06/P6-07）、真实 API smoke（P2-18）。解除条件为用户提供设备/Key；不影响已发布 APK 的安装与离线/浏览功能。
- 残余：R8 未开、ABI split 未开、Actions 未 pin SHA、依赖 verification metadata 未落地，均记录并在 release notes 声明。
