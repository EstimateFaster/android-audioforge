# 决策记录（ADR / 偏差）

> 任何偏离 `docs/android-execution-plan.md` §3 版本矩阵、§2 ADR 的改动，先在这里记录，再改代码。

## 已确认（来自计划）

- ADR-002：Material 3 Expressive `1.5.0-rc01`；`compileSdk 37`。
- ADR-005：只支持 `arm64-v8a` + `x86_64`。
- ADR-006：完全 BYOK，无服务器。
- ADR-007：单活跃任务 + 队列；默认 5 章/批，上限 20。
- ADR-008：只侧载 release，不上架 Google Play。
- ADR-009：FFmpegKit `dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.9`。
- ADR-010：单模块 `:app` + 包边界；手写 DI。
- ADR-012：v1 不做长章节分块；超长明确报错，v1.1 再做。

## 待记录（DSH 填写）

- 日期 / 变更 / 原因 / 验证命令 / 影响。

## 2026-11-08 TextDecoder 不使用 `android.icu.text.CharsetDetector`

- 变更：P1-03 的编码识别改为「BOM → UTF-8 严格 → GB18030/Big5 严格解码 + CJK 占比 + 标点评分」。
- 原因：`android.icu.text.CharsetDetector` 不在 Android 公开 SDK；compileSdk 37 的 `android.jar` 中不存在该类，
  计划 §7.7 给出的 API 在本基线不可编译（CI 报 `Unresolved reference 'CharsetDetector'`）。
- 验证：`./gradlew testDebugUnitTest`（CI）覆盖 UTF-8/BOM/GBK/GB18030/UTF-16LE/BE/Big5/非法字节；
  GB18030 与 Big5 互相都能严格解码时，用中文标点密度与异常字符评分选择。
- 影响：不新增依赖、不扩大 APK；无 BOM 的 UTF-16 自动识别能力弱于 ICU，失败时走手动选编码。

## 2026-11-08 Phase 1 与 Python 参考实现的有意差异

- **TtsCacheKey v2**：字段序 `e/m/p/s/t/v/vol`，SHA-1 前 12；与 Python v1 JSON 缓存键不同，直接使用 v2、不复用旧键；
  浮点用 `BigDecimal.valueOf(...).stripTrailingZeros().toPlainString()`。
- **PipelineError**：core 层持有 `code + retryable + 中文 userMessage`；UI 后续按 code 映射 `strings.xml`，
  `userMessage` 不直接出现在资源文件里。HTTP 400 统一映射 `MODEL_NOT_FOUND`（计划表口径）。
- **VoicePoolProvider**：为满足 P1-08 的缓存要求放在 `core/voice`，依赖 `AssetManager`；P2 可迁到 data/app 层。
- **P1-12 真实语料**：仓库只有上游 `test/sample_novel.txt`（2 章）与 fixture，50 条脱敏真实章节待人工提供（BLOCKED），
  已用现有 fixture + 上游 sample 建立 golden 脚手架。
