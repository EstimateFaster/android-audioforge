# Phase 1 门禁记录（P1-15）

> 结论：**Phase 1 核心域通过门禁**，可进入 Phase 2（数据/网络/音频）。
> 验证方式：所有构建/测试由 GitHub Actions 执行（本机不跑 Gradle/CI）。

## 1. 环境与版本

- AGP 9.5.1 / Gradle 9.9.0 / JDK 17 / Kotlin 2.2.10（AGP 内置）
- compileSdk 37 / targetSdk 36 / minSdk 26
- Compose BOM 2026.09.00 / material3 1.5.0-rc01
- Room 2.8.5 + KSP 2.2.10-2.0.2；FFmpegKit 8.1.9
- 自 P0 起未改版本矩阵（`build.gradle.kts` 只在 bootstrap 提交中定义）

## 2. 门禁项

| 门禁项 | 结果 | 证据 |
|---|---|---|
| 核心域单测 | ✅ 153 / 153，0 failures | CI [42459961060](https://github.com/EstimateFaster/android-audioforge/actions/runs/42459961060) `unit-test-reports` |
| main CI（lint / unit / assembleDebug / APK 校验） | ✅ | CI [42460233513](https://github.com/EstimateFaster/android-audioforge/actions/runs/42460233513) |
| P1-15 文档 PR CI | ✅ | CI P1_15_ID |
| TODO / FIXME | 0 | `grep -rn "TODO\|FIXME" app/src` |
| 硬编码 Key / 私钥 | 0 | 扫描 `sk-*`、`gho_*`、`AIza*`、`BEGIN PRIVATE KEY` |
| 版本矩阵 | 未改 | AGENTS.md 基线 |
| P1-12 的 50 条真实章节语料 | BLOCKED（外部数据） | `docs/progress.md` BLOCKED 记录 |

## 3. 测试分布（153）

| 测试套件 | 数量 |
|---|---:|
| EnumsTest | 8 |
| PromptAssetsTest | 2 |
| PromptRendererTest | 3 |
| VoicePoolTest | 14 |
| TextDecoderTest | 14 |
| ChapterSplitterTest | 9 |
| ScriptSanitizerTest | 17 |
| CoverageCheckerTest | 11 |
| CharacterRegistryTest | 10 |
| SettingsValidatorTest | 21 |
| TtsCacheKeyTest | 16 |
| PipelineErrorTest | 16 |
| GoldenFixturesTest | 5 |
| SanitizerBenchmarkTest | 3 |
| RoomProbeTest | 4 |

## 4. 遗留（不阻塞 Phase 2）

- P1-12：50 条脱敏真实章节语料待人工提供；补齐后直接扩 `app/src/test/resources/golden/`。
- `PipelineError.userMessage` 目前在 core 层为中文常量；UI 层在 P2 按 `code` 映射 `strings.xml`。
- `VoicePoolProvider` 在 `core/voice` 依赖 `AssetManager`；P2 可迁到 data/app 层。
