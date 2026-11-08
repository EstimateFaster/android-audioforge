# Phase 2 门禁（P2-20）

日期：2026-11-09（Asia/Shanghai）
基线：main（P5-01 合并后，bf2a42b）
统一 CI：Android CI（GitHub Actions）绿色；未做任何本地构建。

## 任务状态

| 任务 | 状态 | 证据 |
|---|---|---|
| P2-01 Room 实体/DAO/DB | ✅ | schema 1.json 提交；RoomDaoTest |
| P2-02 仓库层 + AppContainer | ✅ | 仓库测试 6 个文件 |
| P2-03 DataStore 设置 | ✅ | SettingsRepositoryTest 13 |
| P2-04 SecureKeyStore | ✅ | JVM + instrumented（emulator smoke） |
| P2-05 AudioStorage/ProjectFiles | ✅ | ProjectFilesTest 27 + AudioStorageTest 10 |
| P2-06 ImportNovelUseCase | ✅ | 8 用例 |
| P2-07 LlmClient | ✅ | MockWebServer 15 |
| P2-08 FishTtsClient | ✅ | MockWebServer 10 |
| P2-09 TtsSynthesizer/后处理 | ✅ | 24 用例 |
| P2-10 ChapterStitcher/Silence | ✅ | JVM + instrumented |
| P2-11 AnalyzeChapterUseCase | ✅ | 8 + 长章节 4 |
| P2-12 RenderChapterUseCase | ✅ | 9 用例 |
| P2-13 CharacterRegistryAdapter | ✅ | 9 用例 |
| P2-14 Media3 播放 | ✅ | SleepTimer + instrumented（主线程修复） |
| P2-15 导出/分享 | ✅ | Robolectric + instrumented（MIME 放宽） |
| P2-16 ErrorMapper | ✅ | 13 用例 |
| P2-17 端到端 fake | ✅ | PipelineE2ETest 7（导入→解析→渲染→拼接/断点/失败） |
| P2-18 真实 API smoke | 🚫 BLOCKED | 无用户 Key；规则禁止提交密钥 |
| P2-19 长章节守卫 | ✅ | TooLong + allowLongChapter，4 用例 |
| P2-20 门禁 | ✅ | 本文档 + main CI 绿 |

## 验收命令（CI 等价）

- `:app:lintDebug :app:testDebugUnitTest :app:assembleDebug` → GitHub Actions Android CI
- emulator smoke（API 35）：instrumented 15 项通过（含 Room/FFmpeg/播放/导出/Keystore/Worker）

## 例外与风险

- P2-18：需要用户提供 LLM/Fish Key 才能真机跑真实链路；已按规则记 BLOCKED，不写入任何密钥。解除条件：用户提供临时 Key（只存本地 SecureKeyStore/CI secret，不入库）。
- P2-09 后处理失败采用 volume-only 兜底；真机 FFmpeg 滤镜仅 emulator 覆盖。
- P2-12 片段扩展字段写入依赖 `db`（Repository 缺 markDone），后续可补仓库方法。
