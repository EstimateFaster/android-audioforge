# Phase 3 门禁（P3-12）

日期：2026-11-09（Asia/Shanghai）
基线：main（含 P3-11 合并）
统一 CI：GitHub Actions 绿色；未做本地构建。

## 任务状态

| 任务 | 状态 | 证据 |
|---|---|---|
| P3-01 Job 状态机/仓库 | ✅ | guarded transitions + stats/sweep |
| P3-02 GenerationWorker/Executor | ✅ | 12 用例；通知/停止/重试 |
| P3-03 前台通知 | ✅ | 3 渠道 + 停止 receiver + 权限降级（13 用例） |
| P3-04 BatchRunner/JobQueue | ✅ | 单活跃/唯一 work/停止（8 用例） |
| P3-05 Sweep/await/错误汇总 | ✅ | 19 用例（时钟截止修复） |
| P3-06 约束/电量/温度 | ✅ | 29 纯 JVM 用例 |
| P3-07 6 小时看门狗 | ✅ | 16 用例 |
| P3-08 进程死亡/恢复 | ✅ | sweep + 重排 + 幂等（11 用例） |
| P3-09 Worker 测试 | ✅ | WorkManager TestDriver 6 用例 |
| P3-10 真机后台 smoke | 🚫 BLOCKED | 无 Honor/EMUI 真机；emulator smoke 已覆盖基础链路 |
| P3-11 存储清理 | ✅ | 13 用例（孤儿/保留期/路径安全） |
| P3-12 门禁 | ✅ | 本文档 + main CI 绿 |

## P3-10 BLOCKED 记录

- 现象：无法在真实 Honor/EMUI 设备上验证锁屏 30min、杀进程、重启、断网恢复。
- 命令/日志：本环境无设备（无 adb 设备、无厂商 ROM）；CI emulator（API 35）只能覆盖进程内/WorkManager 单测。
- 已尝试：emulator smoke + WorkManager TestDriver + 恢复协调器/看门狗/约束单测。
- 怀疑点：厂商后台限制（自启动/省电策略）只能真机验证；代码侧已提供通知、前台服务、约束、恢复与看门狗。
- 解除条件：用户提供真机或厂商云真机；按 §11 清单跑一遍。

## 验收

- 5 章批次端到端：P2-17 E2E + P3-02 执行器测试覆盖（fake 网络/TTS/FFmpeg）。
- 后台/恢复：P3-05/08 + P3-09 覆盖；真机部分 BLOCKED。
- CI：Android CI 绿；smoke 绿（见 Actions）。
