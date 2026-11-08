# 音频 A/B 评估（P6-06）

> **状态**：文档完成；**真机试听 / 真实 Fish 片段全部 BLOCKED（本环境无真机、无 API Key）**。本文是 P6-06 的唯一产物，未改 `core/**`、`build.gradle.kts`、`docs/progress.md`，未构建、未跑 CI、未改版本矩阵。
> **日期**：2026-11-09（Asia/Shanghai）。**基线**：`main@7c4e363`；分支 `docs/p6-06-audio`。
> **对照**：执行计划 §10（音频层）、§14.2/§14.5/§14.6、§15 P6-06；`docs/android-port-feasibility.md` §3.8 / §5.1.1 / 附录 C；ADR-009（FFmpegKit）。
> **证据规则**：`[代码]` 指本仓库源码行；`[计划]`/`[可行性]` 指上述文档；`[AAR静态]` 指解包 `ffmpeg-kit-audio:8.1.9` 后 `strings` 的静态检查；`[host实测]` 指 Linux x86_64 + **系统 ffmpeg 4.4.2** 对**合成音频**的验证（不是设备、不是 Fish 输出）。没有证据的项写 `BLOCKED`，不写「应该没问题」。

---

## 0. 结论（TL;DR）

1. **当前默认参数可以保持**：上游 mp3 64 kbps + `normalize=true`；本地 `asetrate+aresample+atempo+volume` 链、后处理 64 kbps；静音 350/700 ms；拼接 AAC 96 kbps / 44.1 kHz / 单声道 / `+faststart` + WAV 回退。没有一项有足够证据要求现在改。
2. **但发现两个「正确性」问题（不是音质偏好）**，已用 host 合成音频复现，建议后续单独提 issue，本任务不改代码：
   - **采样率硬编码 44100**：pitch 链只在输入为 44.1 kHz 时正确；输入 24 kHz 时 `pitch=+12` 实测得到 **808.5 Hz / 1.097 s**（期望 440 Hz / 2.0 s），输入 48 kHz 时仅调 volume 也会被改成 **202.5 Hz / 2.177 s**（期望 220 Hz / 2.0 s）。
   - **volume > 1 没有限幅**：`volume=2.0` 实测峰值 **+2.03 dBFS**，会削波；同链路加 `alimiter` 后 −1.34 dBFS。
3. **96 kbps 不能直接作为上游候选**：Fish SDK schema 的 `mp3_bitrate ∈ {64,128,192}`（`[可行性]` §5.1.5），96 不在其中；要测上游码率请用 **128**。本地的 `-b:a 96k` 没有这个限制（LAME 在 AAR 内，`[AAR静态]`）。
4. **`librubberband` 不可用**：当前 AAR 是 LGPL 的 `audio` 包，`[AAR静态]` 无 `rubberband`、配置串无 `--enable-gpl`/`--enable-librubberband`；rubberband 是 GPL 库，只在 GPL 变体启用。换依赖会动 §3 版本矩阵与整个 App 的许可姿态，**不列入当前基线**（host ffmpeg 仅作参考）。
5. **BLOCKED（解除条件见 §5.3）**：真机 ABX 主观听感；Fish 实际输出采样率/LUFS；ARM64 真机执行 encoder/filter；16 KB 页设备；服务器 vs Android 同片段对比。
6. **下一步**：先做 §5.2 的两个 P1 后续（采样率修正、volume 限幅）；缓存键 v3 只在真的改请求/滤镜语义时再做。先客观验证，再决定是否动默认参数。**本任务不改代码。**

---

## 1. 当前实现基线

### 1.1 参数与证据

| 环节 | 当前实现 | 关键参数 | 证据 |
|---|---|---|---|
| 上游 TTS 请求 | Fish Audio `POST /v1/tts` | `format=mp3`、`mp3_bitrate=64`、`normalize=true`、仅 `prosody.speed` | `app/src/main/java/com/estimatefaster/audioforge/core/tts/FishTtsClient.kt:161-163` |
| 缓存键 | v2：text/emotion/voice/speed/volume/pitch/model 七字段 | **不含**码率、采样率、滤镜版本 | `app/src/main/java/com/estimatefaster/audioforge/core/tts/TtsCacheKey.kt:62-70` |
| 后处理触发 | `pitch != 0 || volume != 1` 才跑 | 否则直接复制 raw | `AudioPostProcessor.kt:61-62`；`TtsSynthesizer.kt:180-190` |
| pitch/volume 滤镜 | `asetrate=44100*2^(p/12),aresample=44100,atempo=2^(-p/12),volume=v` | 采样率写死 44100；pitch 单位半音 | `AudioPostProcessor.kt:68-76` |
| 后处理编码 | mp3 64 kbps；失败时仅 volume 重试一次 | 中间文件 `.post.mp3`，成功后 rename | `AudioPostProcessor.kt:104-128,187` |
| 静音 | `anullsrc=r=44100:cl=mono` → `libmp3lame -b:a 64k` | 350 ms / 700 ms，缓存 `sil_<ms>.mp3` | `SilenceGenerator.kt:69-74` |
| 拼接计划 | `Dialogue → Narration` 700 ms，其余 350 ms，末尾不加 | 与设置 `narrator_silence_ms` / `paragraph_silence_ms` 对应 | `ChapterStitcher.kt:213-235` |
| 拼接编码 | concat demuxer → AAC 96 kbps、44.1 kHz、单声道、`+faststart`；失败回退 WAV→AAC | 直接 AAC 优先，WAV 仅回退 | `ChapterStitcher.kt:177-200` |
| 时长 | `MediaMetadataRetriever` 读容器时长写 DB | 与是否回退无关 | `ChapterStitcher.kt:278-289` |
| 播放 | Media3 ExoPlayer / MediaSession | 未做客户端响度归一 | 执行计划 §10.4 |

### 1.2 参数范围（UI/设置侧）

| 参数 | 范围 | 默认 | 证据 |
|---|---|---|---|
| `speed` | 0.5–2.0 | 1.0 | `SettingsValidator.kt:40,45` |
| `volume` | 0.5–2.0 | 1.0 | `SettingsValidator.kt:41,46` |
| `pitch` | −12.0…+12.0 半音 | 0.0 | `SettingsValidator.kt:42,47` |
| 旁白静音 | 0–3000 ms | 350 ms | `SettingsValidator.kt:31-32`；执行计划 §7 设置表 |
| 段落静音 | 0–5000 ms | 700 ms | `SettingsValidator.kt:33-34`；执行计划 §7 设置表 |

> 注意：`pitch = ±12` 正好落在 `atempo` 的边界 0.5 / 2.0；`[host实测]` 可接受，但边界处 WSOLA 伪影最重（§2.3）。

### 1.3 现有测试覆盖 vs A/B 缺口

| 已有 | 覆盖 | 不覆盖 |
|---|---|---|
| `AudioPostProcessorTest` | 滤镜字符串、pitch=0/volume=1 跳过、失败回退、原子写 | 采样率非 44.1 kHz、真实听感、峰值/削波 |
| `ChapterStitcherPlanTest` | 350/700 顺序、末段无静音、清单转义 | 实际 gap 精度、长章节累计误差 |
| `ChapterStitcherInstrumentedTest`（emulator） | 真跑静音+拼接，时长 1.5–2.1 s，清理临时文件 | 时长容差 ±300 ms，**测不出**每段 ~40 ms 的容器时长偏差；无 ARM64 真机 |
| `FfmpegProbe` / `FfmpegInstrumentedTest` | `-version`、静音、concat copy | encoder/filter 清单、LUFS、真实 TTS 片段 |

---

## 2. A/B 矩阵

### 2.1 总表

| ID | 变量 | A（当前） | B（候选） | 固定项 | 主指标 | 状态 |
|---|---|---|---|---|---|---|
| A1 | 上游段码率 | mp3 64 kbps | 96 kbps（schema 外，先探测）/ **128 kbps**（schema 内） | 同文本/音色/情绪/模型 | ABX、HF 带宽、字节/分钟 | BLOCKED（无 Key） |
| A2 | 本地后处理码率 | mp3 64 kbps | mp3 96 kbps（仅 pitch/volume 段） | 同输入、同滤镜 | ABX、代际损失 | BLOCKED（无真实片段） |
| A3 | pitch 算法 | `asetrate+aresample+atempo` | `rubberband` / 仅 `asetrate` / 加 `soxr` | 44.1 kHz、p=±2/±12 | F0、时长、机械感 | rubberband **N/A**；其余可 host 验证 |
| A4 | volume 归一 | 上游 `normalize=true` + 本地 `volume`；无章节归一 | 章节 `loudnorm` 单遍/两遍；上游 `normalize=false` | 同章节 | LUFS、TP、泵动 | BLOCKED（无 Key/真机试听） |
| A5 | 静音 | 350/700 ms | 250/500、400/800、固定 500 | 同片段 | 自然度、总时长、gap 误差 | BLOCKED（主观） |
| A6 | 拼接编码 | AAC 96k / 44.1 / mono / faststart；WAV 回退 | AAC 128k；48 kHz；stereo；直接 vs WAV | 同输入 | 大小、ABX、容器时长 | 96k 可保持；其余待测 |
| A7 | 输入采样率处理 | 硬编码 44100 | 探测输入 SR；pitch=0 时只走 `volume` | 24k/48k 输入 | F0、时长 | **客观已复现错误**（§2.7） |

### 2.2 mp3 64 kbps vs 96 kbps

**先分清两个 64 kbps**：上游请求 `mp3_bitrate=64`（`FishTtsClient.kt:162`）与本地后处理 `-b:a 64k`（`AudioPostProcessor.kt:187`）是两件事。

- **上游**：Fish SDK schema 只列 `{64,128,192}`，96 不在其中（`[可行性]` §5.1.5）。把 96 当既定目标有 400/被忽略的风险；**若一定要抬上游，测 128**。96 只能作为「探测服务端是否接受任意值」的附加实验，不能写进默认。
- **本地**：`-b:a 96k` 无上游限制，LAME 已编入 AAR（`[AAR静态]` 配置串含 `--enable-libmp3lame`）。但只有 pitch/volume ≠ 默认的片段才会重编码，影响面小。
- **代际损失**：pitch/volume 段是 64k mp3 → 解码 → 滤镜 → 64k mp3（第二代）→ 解码 → AAC 96k（第三代）。后处理抬到 96k 只作用于这些片段，是成本最低的改善点。

`[host实测]` 7.28 s 语音 + 5 s 白噪声（合成素材，仅看趋势）：

| 编码 | 7.28 s 语音字节 | 约 MB/分钟 | 白噪声 5 s 字节 | 12 kHz 相对衰减* | 16 kHz 相对衰减* |
|---|---|---|---|---|---|
| 源 WAV | 642,174 | — | 441,078 | 0（参考） | 0（参考） |
| mp3 64k | 58,767 | ≈0.48 | 40,586 | −2.3 dB | −5.9 dB |
| mp3 96k | 88,129 | ≈0.73 | 60,858 | −0.5 dB | −1.2 dB |
| mp3 128k | 117,490 | ≈0.97 | 81,128 | −0.6 dB | −1.3 dB |
| AAC 96k | 90,091 | ≈0.74 | 62,142 | −1.0 dB | −2.5 dB |
| AAC 128k | 119,388 | ≈0.98 | 81,991 | −0.5 dB | −1.2 dB |

\* 相对衰减 =（该编码器 12/16 kHz 相对 full-band 的差值）−（源 WAV 同口径差值）；白噪声法，只说明**带宽/高频保留**，不等于语音清晰度。96k 相对 64k 在 16 kHz 多保留约 4.7 dB，128k 与 96k 几乎无差别。

**决策规则**：30 分钟章节 64k 段约 14.5 MB、128k 约 29 MB；在最终 AAC 96k 的前提下，若 ABX 未达到「≥10/12 偏好 96/128」，**保持 64k**。若只有齿音/气声问题，优先把**本地后处理**抬到 96k（影响面小），而不是抬上游。

### 2.3 pitch 算法

| 方案 | 时长 | 音高 | 音色/共振峰 | 可用性 |
|---|---|---|---|---|
| `asetrate` 单独 | **改变**（pitch 越高越短） | 变 | 随速度变 | 淘汰（破坏章节时长） |
| `atempo` 单独 | 不变 | **不变** | 不变 | 不是变调 |
| `asetrate+aresample+atempo`（当前） | 基本不变 | 正确 | 共振峰随音高移动（“花栗鼠/低沉”） | ✅ 当前 |
| `rubberband` | 不变 | 正确 | 可保共振峰/瞬态，通常优于 WSOLA | ❌ AAR 无；GPL 变体才有 |
| 当前链 + `aresample=resampler=soxr` | 基本不变 | 正确 | 重采样更干净，音色差异小 | ✅ AAR 含 libsoxr |

- `[AAR静态]`：`audio` 8.1.9 的 `libavfilter.so` 精确匹配到 `atempo/asetrate/anullsrc/aresample/loudnorm/ebur128`，**没有 `rubberband`**；`libavcodec.so` 配置串无 `--enable-gpl`、无 `--enable-librubberband`。rubberband 是 GPL 库，只在 GPL 变体启用；换 `full-gpl` 会同时改 §3 依赖矩阵、APK 体积和整个 App 的 GPL 义务，**本任务不提议**。
- `[host实测]`（ffmpeg 4.4.2，`--enable-librubberband`，仅参考）：220 Hz 正弦 +3 半音，rubberband 得 262.0 Hz，当前链得 261.5 Hz，时长都约 2.0 s；**音高与时长都正确，差异在共振峰/瞬态，本环境无法量化**。所以「rubberband 更好」只能作为待真机 ABX 的假设，不能作为结论。
- `atempo` 边界：`pitch=±12` → `atempo=0.5/2.0`；`[host实测]` 可执行，但 +12 的解码时长 1.972 s（源 2.0 s，−28 ms），边界处时长/伪影最差。UI 已把 pitch 夹在 ±12（`SettingsValidator.kt:42`），不会越界。
- **关键缺陷（A7）**：`asetrate=44100*...` 的倍率是相对**输入实际采样率**的。输入不是 44.1 kHz 时，pitch 与时长同时错。host 证据见 §2.7。这不是「音质偏好」，是正确性问题。

### 2.4 volume 归一 vs 不归一

当前是**两级**：

1. 上游每段 `normalize=true`（`FishTtsClient.kt:163`）。`[可行性]` §5.1.5 记录上游注释：`prosody.pitch/volume` 实测不生效，且 `normalize=true` 会归一化音量。也就是说**逐段**已经被服务端归一，但归一目标、是否逐段独立、是否受参考音色影响，均未验证。
2. 本地 `volume=0.5–2.0` 乘在每段上，表达「角色音量」这一产品意图；默认 1 时不处理。

候选：

| 方案 | 做法 | 优点 | 风险/成本 | 建议 |
|---|---|---|---|---|
| V0（当前） | 上游 normalize + 本地 volume，无章节归一 | 简单、保留角色音量意图 | 段间/角色间 LUFS 可能不齐；volume>1 会削波 | **保持默认** |
| V1 | 上游 `normalize=false` | 保留原始动态 | 需要改请求与缓存键；**无 Key 无法验证** | 不采用（BLOCKED） |
| V2 | 章节级 `loudnorm` 单遍（dynamic） | 一遍完成，可挂在现有 concat 命令 | 动态增益会「泵动」，改变角色音量意图；内部升到 192 kHz，必须保留 `-ar 44100` | 仅实测有问题时再考虑 |
| V3 | 章节级 `loudnorm` 两遍（`linear=true`） | 以线性增益为主，必要时 TP 限制，泵动小 | 多一遍纯解码；仍会削弱角色音量差异 | 若 V2 有效，优先 V3 |

`[host实测]`（7.28 s 合成语音）：单遍 `loudnorm=I=-18:TP=-1.5` 得 I=−18.2 LUFS、TP=−3.9 dBTP、时长 7.280 s（输入容器 7.314 s，差在 MP3 padding）；两遍 linear 得 I=−17.9、TP=−3.6。**这只能证明命令可用、时长基本不变，不能证明真实章节需要归一**——合成源本身已接近 −18 LUFS，增益几乎为 0。

**削波风险（已复现）**：`volume=2.0` 时峰值 +2.03 dBFS；`volume=1.5` 时 −0.48 dBFS。当前滤镜链没有限幅器，`volume > 1` 可能削波，且随后还要经过 AAC 编码。建议（仅建议）在 `volume > 1` 时追加 `alimiter=limit=0.891:level=false`（≈−1 dBFS）；host 实测可把 +2.03 dBFS 压到 −1.34 dBFS。

**判定**：默认保持 V0；只有真实章节实测「章节间 I 极差 > 3 LU」或「TP > −1 dBTP」时才考虑 V3，并且要接受角色音量差异被削弱。

### 2.5 静音 350 / 700 ms

- 350/700 是**语义**选择（普通换段 vs 对白→旁白），不是编码参数；改它会改变产品节奏，必须真机试听。
- 总静音占比：`T_silence = n350×0.35 + n700×0.70` 秒。100 段、全 350 ms ≈ 35 s；若 30 分钟章节，约 1.9%；200 段约 3.9%。700 ms 全用则翻倍。这个量级说明「不要更长」是合理的，但「是否 350 偏慢」只能靠听。
- `[host实测]` 静音文件：`sil_350.mp3` 解码后恰好 **0.3500 s**（容器 0.3918 s，size 3,387 B）；`sil_700.mp3` 解码 **0.7000 s**（容器 0.7314 s，size 6,104 B）。MP3 帧长 26.12 ms，350/700 都不是整数帧；**ffmpeg 解码会按 LAME gapless 信息裁剪**，所以 ffmpeg 侧 gap 是准的；容器时长会多出约 30–42 ms（见 §2.6/§4.5）。
- 若真机听感「停顿太长」，先试 250/500（不要只改一个，保持 2:1 语义），但这是产品决策，本任务不给结论（BLOCKED）。

### 2.6 拼接编码参数

| 子项 | 当前 | 候选 | host 证据/判断 | 建议 |
|---|---|---|---|---|
| 最终编码 | AAC 96 kbps | AAC 128 kbps | 白噪声 5 s：96k 62,142 B vs 128k 81,991 B（+32%）；HF 差异 <1.5 dB | 保持 96k；128k 仅在 ABX 胜出时 |
| 采样率 | 44.1 kHz | 48 kHz | 源与静音都是 44.1 kHz；48k 只会多一次重采样，语音无收益 | 保持 44.1 |
| 声道 | 单声道 | 立体声 | 语音无空间信息，体积翻倍 | 保持 mono |
| faststart | 开 | 关 | `[host实测]` 短文件容器时长/字节完全相同（9,559 B） | 保持开（导出/分享 seek 友好） |
| 路径 | 直接 AAC | 先 WAV 再 AAC | 直接 AAC 解码时长准确（20 gap：13.328 s vs 期望 13.300 s），但**容器时长**膨胀；WAV 回退容器精确（1.950 s） | 保持「直接优先 + WAV 回退」 |
| 容器时长 | MMR 读容器 | 解码 PCM 计数 | 3 段+2 gap：期望 1.95 s，直接 AAC 容器 **2.128 s**，解码 1.974 s；20 gap：期望 13.3 s，容器 **14.954 s**，解码 13.328 s | 见 §4.5；先确认 FFmpeg 8.1/MMR 是否同样膨胀（BLOCKED） |

> `[host实测]` 的容器膨胀约为**每个输入文件 +40 ms**（MP3 编码 padding 被 concat 时间线计入），解码音频本身是准的。按每 100 个输入文件约 +4 s 估算：200 段章节（约 400 个输入文件）容器时长可能多出 ~16 s，影响进度条/时长显示，不影响实际音频。WAV 回退没有这个问题，但 30 分钟 44.1k/16-bit/mono 中间 WAV 约 158 MB（`[可行性]` §3.8），不能作为默认。

### 2.7 额外变量：输入采样率（A7）

当前 pitch 链把 44100 写死（`AudioPostProcessor.kt:68-76`）。`[host实测]`：

| 输入 | 变体 | 期望 | 实测 | 结论 |
|---|---|---|---|---|
| 44.1 kHz / 220 Hz / 2.0 s | pitch +12 | 440 Hz / 2.0 s | 440.5 Hz / 1.972 s（解码） | 合格 |
| 44.1 kHz | pitch +2 | 246.9 Hz / 2.0 s | 246.5 Hz / 1.997 s | 合格 |
| 44.1 kHz | pitch −3.5 | 179.7 Hz / 2.0 s | 179.5 Hz / 1.998 s | 合格 |
| 24 kHz | pitch +12 | 440 Hz / 2.0 s | **808.5 Hz / 1.097 s** | 错 |
| 24 kHz | pitch +2 | 246.9 Hz / 2.0 s | **453.5 Hz / 1.123 s** | 错 |
| 48 kHz，pitch=0 | volume 1.2（当前全链） | 220 Hz / 2.0 s | **202.5 Hz / 2.177 s** | 错 |
| 48 kHz，pitch=0 | 仅 `volume=1.2` | 220 Hz / 2.0 s | 220.0 Hz / 2.000 s | 正确参考 |

结论：**只要 Fish 返回的不是 44.1 kHz，pitch 与「仅 volume」都会错**；且 `volume` 单独调整时根本不需要 `asetrate/aresample/atempo`。修复方向（仅建议）：先 `FFprobeKit` 读输入采样率，`asetrate=<in_rate>*2^(p/12)`、`aresample=<in_rate>`；`pitch==0` 时只拼 `volume`。或上游请求 `sample_rate=44100`（schema 有可选字段，`[可行性]` §5.1.5，但 API 是否接受未验证）。任何请求字段变化都要 bump 缓存键（§4.4）。

---

## 3. 指标与测试方法

### 3.1 客观指标

| 指标 | 工具/口径 | 建议阈值 | 备注 |
|---|---|---|---|
| 时长 | `ffprobe format=duration` **和**解码 PCM 样本数/采样率 | 与服务器同片段差 <100 ms（§10.6）；长章节 ≤1% | 容器时长会被 MP3/AAC padding 污染，**以解码 PCM 为准** |
| 响度 | `ebur128=peak=true`：I / TP / LRA | 目标 I=−18 LUFS ±1；TP ≤−1 dBTP；章节间 I 极差 ≤3 LU | 语音/有声书常用 −18…−23 LUFS；不要把音乐流媒体 −14 直接套用 |
| 短时响度波动 | `ebur128` 的 short-term（S）最大−最小 | ≤6 LU（暂定） | 判断「泵动/忽大忽小」 |
| 文件大小 | 字节数 + 字节/分钟 | 默认 64k 段 ≈0.48 MB/min；AAC 96k ≈0.74 MB/min | 任何 +25% 以上要有 ABX 收益 |
| 音高 | 正弦 220 Hz → 零交叉/自相关；语音用 Praat/librosa 取 F0 | 与 `2^(p/12)` 的误差 ≤1% | 正弦测试是确定性的，先过它 |
| 高频/带宽 | 白噪声 + `highpass` + `astats`，或频谱图 | 仅作辅助，不单独作为接受标准 | 见 §2.2 表 |
| 削波 | `astats` 的 Peak level / Flat factor | Peak ≤−0.5 dBFS；无连续 flat | volume>1 必测 |
| 可播放/seek | Media3 播放、MMR 时长、拖动 | 能播、时长>0、seek 不崩 | 真机项 |

### 3.2 主观指标（BLOCKED）

| 维度 | 怎么听 | 记录 |
|---|---|---|
| 清晰度 | 中文齿音（s/sh/x）、气声、轻声是否糊 | 1–5 分 + 具体字/时间点 |
| 机械感 | pitch ±2/±12、volume 1.5/2.0 下是否金属声、颤音、双音 | 有/无 + 程度 |
| 自然度/停顿 | 350 与 700 是否合适；对白→旁白是否像换段 | 偏快/合适/偏慢 |
| 角色区分 | 不同音色/情绪是否仍可辨；volume 归一后是否被拉平 | 1–5 分 |
| 响度跳变 | 段间、章间是否忽大忽小；有无泵动 | 有/无 + 位置 |
| 音高方向 | + 是否升、− 是否降；±12 是否过冲 | 正确/错误 |

**ABX 规则**：同一文本/音色/情绪，A/B 响度先对齐（用 I 或 RMS），随机顺序、盲听，12 次；**≥10/12 才认为有显著偏好**（二项分布单侧 p≈0.019）；9/12（p≈0.073）只能算「趋势」。至少 2 名听者，耳机 + 手机外放各一轮。听不出差异就保持当前参数——这是默认结论。

### 3.3 测试素材

- **真实 Fish 片段（BLOCKED，无 Key）**：3 个章节片段——10 段（短）、50 段（中）、200 段（长）；覆盖男/女/童声各 1、情绪 Neutral/Happy/Sad/Angry 至少 4 种、pitch −3/+2/+12、volume 0.8/1.25/2.0、speed 0.9/1.15。
- **合成素材（本任务已用，只验证算法）**：220 Hz 正弦、白噪声、`flite` 英文语音；**不能**用于中文清晰度/机械感结论。
- 固定项：同一段文本、同一 voice/model、同一 `normalize` 设置；A/B 只改一个变量（AGENTS.md 硬规则）。

### 3.4 命令模板（host，Linux；已验证其中标注项）

> 设备侧没有 ffmpeg CLI（AAR 是库，`--disable-programs`，`[AAR静态]`）；以下命令在开发机跑，对**同一批文件**做测量。用 `ffmpeg -version` 记录版本，host 与设备 FFmpeg 版本不同，结论要标注。

```bash
# 0) 版本与基本信息
ffmpeg -version | head -1
ffprobe -v error -show_entries stream=codec_name,sample_rate,channels,bit_rate \
  -show_entries format=duration,size -of json in.mp3

# 1) 响度：I / TP / LRA（[host实测] 可用）
ffmpeg -hide_banner -nostats -i in.m4a -af ebur128=peak=true -f null - 2>&1 \
  | sed -n '/Integrated loudness/,/True peak/p'

# 2) loudnorm 测量（两遍法的第一遍）
ffmpeg -hide_banner -nostats -i in.m4a \
  -af loudnorm=I=-18:TP=-1.5:LRA=11:print_format=json -f null - 2>&1 \
  | sed -n '/{/,/}/p'

# 3) 解码 PCM 的真实时长（绕开容器 padding；[host实测] 可用）
ffmpeg -v error -i in.m4a -f s16le -ac 1 -ar 44100 - \
  | wc -c | awk '{printf "decoded=%.4fs\n", $1/2/44100}'

# 4) 正弦音高验证（把 p 换成 12/2/-3.5；期望 F0=220*2^(p/12)）
ffmpeg -y -v error -f lavfi -i "sine=frequency=220:duration=2" -ar 44100 -ac 1 tone.wav
ffmpeg -y -v error -i tone.wav \
  -af "asetrate=44100*2^(2/12),aresample=44100,atempo=2^(-2/12),volume=1" \
  -b:a 64k tone_p2.mp3
ffmpeg -v error -i tone_p2.mp3 -f f32le -ac 1 -ar 44100 - \
  | python3 -c 'import sys,struct; d=sys.stdin.buffer.read(); n=len(d)//4; x=struct.unpack("<%df"%n,d[:n*4]); r=44100; s=x[n//2-r//2:n//2+r//2]; c=sum(1 for i in range(1,len(s)) if (s[i-1]<=0<s[i]) or (s[i-1]>=0>s[i])); print("F0~%.1f Hz"%(c/2))'

# 5) 静音真实长度（对 sil_350.mp3 / sil_700.mp3）
ffmpeg -v error -i sil_350.mp3 -f s16le -ac 1 -ar 44100 - | wc -c \
  | awk '{printf "samples=%d dur=%.4fs\n", $1/2, $1/2/44100}'

# 6) 章节拼接（与 ChapterStitcher 同参，替换路径）
printf "file '%s'\n" "$PWD/seg0.mp3" "$PWD/sil350.mp3" "$PWD/seg1.mp3" > concat.txt
ffmpeg -y -v error -f concat -safe 0 -i concat.txt \
  -ar 44100 -ac 1 -c:a aac -b:a 96k -movflags +faststart chapter.m4a

# 7) 削波检查（volume 变体）
ffmpeg -hide_banner -nostats -i vol2.mp3 -af astats=metadata=1:reset=0 -f null - 2>&1 \
  | grep -E "Peak level dB|Flat factor"

# 8) 频谱图（人工看 64k/96k 的高频截止）
ffmpeg -y -v error -i in.mp3 -lavfi showspectrumpic=s=1024x512:legend=1 spec.png
```

### 3.5 设备侧采集模板（BLOCKED，无设备）

```bash
# 仅 debug 构建可用 run-as；release 需通过 App 内导出功能
adb shell run-as com.estimatefaster.audioforge \
  ls -l files/audio/<novelId>/segments/ch_001/
adb exec-out run-as com.estimatefaster.audioforge \
  cat files/audio/<novelId>/chapter_001.m4a > chapter_001.m4a
adb logcat -s AudioForgeFfmpegProbe AudioForgeTts
```

在 Android 侧只能用 `MediaMetadataRetriever` / Media3 验证「能播、时长、seek」；LUFS/频谱需把文件拉到开发机测。**当前无设备，整节 BLOCKED。**

### 3.6 记录表

| 编号 | 章节/段数 | 变体 | 时长(解码) | I(LUFS) | TP(dBTP) | 大小 | ABX 结果 | 听感问题 | 结论 |
|---|---|---|---|---|---|---|---|---|---|
| A1-64 |  | 64k |  |  |  |  |  |  |  |
| A1-128 |  | 128k |  |  |  |  |  |  |  |
| A3-rb |  | rubberband |  | — | — | — |  |  | N/A（设备无） |

---

## 4. 风险

### 4.1 Fish TTS 输出采样率（高，BLOCKED）

- 客户端**不发送** `sample_rate`（`FishTtsClient.kt:157-170`）；schema 里它是可选字段（`[可行性]` §5.1.5）。实际输出可能是 44.1 kHz，也可能随音色/服务端变化，**未观测**。
- 影响：pitch 链硬编码 44100（§2.7），非 44.1 kHz 时音高/时长全错；`concat` 虽然能在 host 上按各文件原生采样率解码（`[host实测]` 24k+44.1k 混合拼接解码后 330/440 Hz 都正确），但**每段都先被后处理改错**才是主问题。此外混采样率会让输出时间戳/容器时长更难核对。
- 缓解：请求 `sample_rate=44100`（若 API 接受）；或 `FFprobeKit` 探测后按实际采样率算 `asetrate`；或先把 raw 归一到 44.1 kHz。三者都改代码，**本任务只记录**。
- 解除：拿到 Key，连续抽 20 段 `ffprobe` 记录 `sample_rate` 分布；至少覆盖 3 个音色。

### 4.2 FFmpegKit 编码器/滤镜可用性（中，部分已静态确认）

`[AAR静态]`（`dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.9`，arm64-v8a，libavcodec 62.28.103）：

- 有：`libmp3lame`、`AAC encoder`、`AAC decoder`、`mp4a`/`ipod`、`atempo`、`asetrate`、`anullsrc`、`aresample`、`volume`、`concat`、`loudnorm`、`ebur128`、`soxr`（配置串 `--enable-libmp3lame`、`--enable-libsoxr`、`--enable-mediacodec`）。
- 没有：`rubberband`、`--enable-gpl`、`--enable-librubberband`。→ pitch 的 rubberband 分支**不可发布**。
- 版本：AAR 原生按 `aarch64-linux-android24` 构建，App `minSdk 26`；`--disable-programs`，APK 内没有 ffmpeg 可执行文件。
- 未验证：ARM64 **真机执行**（emulator x86_64 的 P2-10 拼接测试通过，但 ABI 不同）；16 KB 页设备上的运行（只有对齐证据，`[可行性]` §5.1.1）。
- 解除：真机跑 `FfmpegProbe` + 一次带 pitch/volume 的章节拼接，记录 `-version`/encoder 实际输出。

### 4.3 minSdk 26 解码兼容（低–中，BLOCKED）

- 产物是 MP3（段）与 AAC-LC in MP4/M4A（章节）；两者都是 Android 平台长期支持的格式，Media3/MediaCodec 走平台解码器，API 26 起没有已知的格式门槛。
- 仍需真机确认：OEM 是否缺 AAC 解码器（极少数）、`MediaMetadataRetriever` 对 `+faststart` M4A 的时长、导出到 MediaStore 后其他 App 能否播、蓝牙/耳机切采样率。
- 16 KB 页：AAR 的 `libavcodec.so` 有 `Align=0x4000`（`[可行性]` §5.1.1），但**从未在 16 KB 设备上运行**。
- 解除：设备矩阵 D1（Honor/Android 10）与 D3（Pixel/Android 16）各跑一次播放/seek/导出。

### 4.4 缓存键与参数变更（高，若改参数）

- v2 键只含七个字段（`TtsCacheKey.kt:62-70`），**不含** `mp3_bitrate`、`normalize`、`sample_rate`、滤镜版本/算法。
- 如果将来改上游码率（64→128）、加 `sample_rate`、或换 pitch 算法，旧缓存仍会命中，出现「新旧音质混在一起」且无法解释。
- 建议：任何音频请求/滤镜语义变化都 bump 到 `v3`（或在键里加 `audio=...` 段），并写迁移说明；本任务不改。

### 4.5 时长元数据膨胀（中，host 复现，设备待确认）

- `[host实测]`：直接 concat→AAC 的**容器时长**约每个输入文件多 40 ms；3 段+2 gap 为 2.128 s（期望 1.95），20 gap 为 14.954 s（期望 13.3）；**解码 PCM 分别为 1.974 s / 13.328 s，基本准确**。WAV 回退容器精确（1.950 s）。
- 影响：`ChapterStitcher` 用 `MediaMetadataRetriever` 读容器时长写 DB（`ChapterStitcher.kt:278-289`）；200 段章节（约 400 个输入文件）可能多出 ~16 s，影响进度条/剩余时间/睡眠定时显示。
- 版本差异：host 是 ffmpeg 4.4.2，设备是 FFmpeg 8.x；concat 对 MP3 padding 的处理可能不同。**必须真机确认**。
- 缓解（若复现）：优先 WAV 回退（代价 158 MB 临时文件）；或按「期望时长 = Σ段时长 + Σgap」校正；或播放器侧用实际解码时长。

### 4.6 其他

- **代际损失**：pitch/volume 段 64k→64k→AAC 96k 三代；见 §2.2，建议后处理 96k（仅影响调参段）。
- **volume 削波**：见 §2.4，建议 `alimiter`。
- **静音 padding**：解码准确、容器偏大（§2.5/§4.5）；不要用容器时长判断 gap 对错。
- **服务器基线缺失**：§15 P6-06 要求「同片段服务器 vs Android」；本仓库没有服务器侧可执行基线，且无 Key/无设备 → 整项 BLOCKED。解除后按 §3.1 的 100 ms 门槛对比。

---

## 5. 推荐默认 vs 当前实现

### 5.1 可以保持（不改）

| 项 | 当前 | 推荐默认 | 理由 |
|---|---|---|---|
| 上游格式/码率 | mp3 64 kbps | **保持** | 96 不在 schema；128 体积翻倍且最终只到 AAC 96k；无 ABX 证据 |
| 上游 `normalize=true` | 开 | **保持** | 逐段归一是现状；关掉需要 Key 验证，且本地没有替代 |
| 本地后处理码率 | 64 kbps | **保持**（后续可只对调参段升 96） | 影响面小；先解决正确性问题 |
| pitch 链 | asetrate+aresample+atempo | **保持算法**，但依赖 44.1 kHz | 44.1k 输入下 host 验证音高/时长合格；rubberband 不可用 |
| volume | 0.5–2.0 乘子 | **保持**，>1 加限幅（后续） | 保持角色音量意图；削波是缺陷不是偏好 |
| 静音 | 350/700 ms | **保持** | 语义清晰、总量可控；主观项 BLOCKED |
| 拼接 | AAC 96k/44.1/mono/faststart + WAV 回退 | **保持** | 大小/兼容性平衡；WAV 仅回退 |
| 章节响度归一 | 无 | **保持关闭** | 避免泵动/拉平角色；仅实测超标时才上 V3 |

### 5.2 建议改动（只写建议，本任务不改代码）

| 优先级 | 建议 | 触发证据 | 影响面 | 验收 |
|---|---|---|---|---|
| **P1** | pitch 链不再硬编码 44100：`FFprobeKit` 读输入采样率，`asetrate=<in>*2^(p/12)`、`aresample=<in>`；`pitch==0` 时只走 `volume` | `[host实测]` 24k/48k 错误（§2.7） | `AudioPostProcessor.buildFilter/process`；滤镜语义变，建议同时 bump 缓存键 | 24k/48k 输入 p=±2/±12 的 F0/时长误差 ≤1%/≤30 ms |
| **P1** | `volume > 1` 追加 `alimiter=limit=0.891:level=false`（或把 UI 上限降到 1.5） | `[host实测]` volume=2.0 峰值 +2.03 dBFS | 滤镜链；真实片段需 ABX 确认无泵动 | 峰值 ≤−1 dBTP；听感无泵动 |
| P2 | 若 ABX 显示 64k 齿音损失：本地后处理升 96k（只影响调参段）；仍不够再评估上游 128k + 缓存键 v3 | §2.2 表；需真实中文片段 | 存储/网络；上游改动需 Key | 96k 变体 ≥10/12 偏好且体积可接受 |
| P2 | 拼接时长以解码 PCM 为准或校正容器 padding；先确认 FFmpeg 8.1/MMR 是否复现 §4.5 | host 复现 +1.65 s（20 gap） | `ChapterStitcher` 时长读取/DB | 30 分钟章节时长误差 ≤1%（或 ≤100 ms） |
| P2 | 章节响度：仅当实测 I 极差 >3 LU 或 TP >−1 dBTP 时上两遍 `loudnorm`（linear） | 需真实章节测量（BLOCKED） | 多一遍解码；角色音量差异被削弱 | I=−18±1、TP≤−1、无泵动 ABX |
| P3 | 试 `aresample=resampler=soxr`（AAR 含 soxr） | 无（音质微调） | 仅 pitch 链；CPU 略增 | ABX ≥10/12 才采纳 |
| — | **不采纳** rubberband | AAR 无、GPL、需改版本矩阵/许可 | 依赖 + 全 App GPL | — |

### 5.3 BLOCKED 清单与解除条件

| # | 项 | 为什么 BLOCKED | 解除条件 | 影响 |
|---|---|---|---|---|
| B1 | 真机 ABX（清晰度/机械感/停顿/响度） | 无 adb 设备 | 借到 D1（Honor/Android 10）+ D3（Pixel/Android 15/16） | 主观结论、是否改 350/700、是否上 loudnorm |
| B2 | Fish 实际采样率/LUFS/真实片段 | 无 API Key | 提供 Key + 至少 20 段真实输出 | §2.7 修复验证、A1/A4 |
| B3 | ARM64 真机执行 encoder/filter | emulator 只有 x86_64 | 真机跑 `FfmpegProbe` + 章节拼接 | 确认 LAME/AAC/soxr 在 ARM64 可用 |
| B4 | 16 KB 页设备 | 无设备 | Pixel/Android 15+ 16 KB 设备 | 发布阻断项（与 P6-05 共用） |
| B5 | 服务器 vs Android 同片段 | 无服务器基线/Key | 提供服务器输出或 Python 管线可执行环境 | §10.6 的 100 ms 验收 |
| B6 | 缓存键 v3 迁移 | 依赖是否真的改参数 | 先决定 §5.2 P2 是否采纳 | 避免新旧音频混用 |

---

## 6. 结论

- **可保持当前参数**：64k mp3 上游、`normalize=true`、现行 pitch/volume 链、350/700 ms、AAC 96k/44.1/mono/faststart、WAV 回退。没有客观证据要求改默认值；所有主观项都还没做（BLOCKED）。
- **建议后续修两个正确性问题**（P1，只写建议）：采样率不再硬编码 44100；`volume>1` 加限幅。它们与「A/B 音质偏好」无关，属于可复现的缺陷。
- **不建议**引入 rubberband（GPL/依赖矩阵/许可），也不建议把上游 96k 当目标（schema 只支持 64/128/192）。
- **本任务没有改任何代码/构建/版本矩阵**；下一步按 §5.3 借设备/Key，先做 B1+B2，再回填 §3.6 记录表。

---

## 附录 A：host 实测记录（2026-11-09，仅合成音频）

- 环境：Ubuntu 22.04 x86_64，**ffmpeg 4.4.2**（`--enable-libmp3lame --enable-librubberband --enable-libsoxr`），与设备 FFmpeg 8.x 不同；**设备侧未执行**。
- 素材：`flite` 英文语音 7.28 s / 220 Hz 正弦 2 s / 白噪声 5 s；**不代表中文 Fish 输出**。
- AAR 静态检查：`ffmpeg-kit-audio-8.1.9.aar` 下载到 `/tmp`，`unzip jni/arm64-v8a` 后 `strings`；未提交任何 AAR/产物。

| 编号 | 测试 | 结果 | 用途 |
|---|---|---|---|
| H1 | 44.1k 正弦 p=+12/+2/−3.5 | F0 440.5/246.5/179.5 Hz；解码 1.972/1.997/1.998 s | 证明 44.1k 下链正确 |
| H2 | 24k 正弦 p=+12/+2 | 808.5 Hz/1.097 s；453.5 Hz/1.123 s | 证明采样率硬编码缺陷 |
| H3 | 48k 正弦 pitch=0 volume=1.2 | 全链 202.5 Hz/2.177 s；仅 volume 220.0 Hz/2.000 s | 证明「仅 volume」被误重采样 |
| H4 | sil_350/sil_700 | 解码 0.3500/0.7000 s；容器 0.3918/0.7314 s | gap 精度与容器 padding |
| H5 | 3 段+2 gap→AAC | 期望 1.95 s；容器 2.128 s；解码 1.974 s | 容器膨胀 |
| H6 | 20 gap→AAC | 期望 13.3 s；容器 14.954 s；解码 13.328 s | 长章节累计膨胀 |
| H7 | WAV→AAC 回退 | 容器 1.950 s | 回退路径时长准确 |
| H8 | volume 1.5/2.0 | 峰值 −0.48/+2.03 dBFS；+alimiter −1.34 dBFS | 削波风险 |
| H9 | loudnorm 单遍/两遍 | I −18.2/−17.9；TP −3.9/−3.6；时长 7.28 s | 命令可用性，非必要性 |
| H10 | rubberband +3 半音（host） | 262.0 Hz / 约 2.0 s；当前链 261.5 Hz | 仅参考，设备无此滤镜 |
| H11 | faststart 开/关 | 容器 2.128 s、9,559 B 均相同 | 保持开启 |
| H12 | 白噪声 64/96/128k + AAC | 见 §2.2 表 | 带宽/体积趋势 |
