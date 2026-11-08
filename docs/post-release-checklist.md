# v1.0.0 发布后清单（P6-12）

## 立即（发布后 24h）

- [ ] 从 Release 下载 `audioforge-1.0.0.apk`，核对 `sha256sum` 与 `checksums.txt`
- [ ] `apksigner verify --print-certs` 核对证书指纹与 CI 记录
- [ ] arm64 真机安装：首启 Onboarding → 跳过 → 项目库 → 设置可打开
- [ ] 覆盖安装 v0.1.0（若已装）：项目/Key 保留、versionCode 升级
- [ ] 卸载重装：确认无云备份、提示会丢数据
- [ ] 记录安装失败/崩溃（logcat、ROM、Android 版本）

## 一周内

- [ ] 收集 401/402/429/超时错误分布（不含 Key/正文）
- [ ] 验证通知权限拒绝、后台 30min、锁屏播放（真机）
- [ ] 存储页扫描/清理不误删（先在测试项目）
- [ ] 检查 Release 下载量与 checksum 校验反馈
- [ ] 汇总 P6-05/P3-10 BLOCKED 项的设备结果；无设备则保持 BLOCKED

## 回滚

- 不提供降级包；严重问题发 v1.0.1（同签名、versionCode 递增）。
- 若签名/密钥问题：停止 Release 资产下载，修复 workflow 后重发；不引导第三方重打包。

## 已知限制（发布说明已声明）

- 32 位设备不支持；universal APK ~59MB；R8/ABI split 未开。
- 真实 LLM/Fish API smoke 未在 CI 执行；首次使用需自备 Key。
- 真机后台/国产 ROM/16KB 页未验收。
