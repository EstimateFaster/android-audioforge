package com.estimatefaster.audioforge.core.tts

import com.estimatefaster.audioforge.core.model.Emotion
import com.estimatefaster.audioforge.core.model.PipelineError
import com.estimatefaster.audioforge.core.storage.AudioStorage
import com.estimatefaster.audioforge.core.storage.InsufficientStorageException
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 一段 TTS 合成请求（执行计划 §8.8 / §8.9）。
 *
 * @param text 原始文本（不含情绪前缀）；情绪前缀由 [TtsSynthesizer] 调
 *   [Emotion.applyTo] 后只发给 transport，缓存键用 [emotion] + 原始 [text] 计算；
 * @param emotion 情绪，决定前缀并参与缓存键；
 * @param voiceId Fish Audio 音色 id；
 * @param speed 语速倍数，参与缓存键并作为 `prosody.speed` 传给 transport；
 * @param volume 音量倍数，参与缓存键，>1 放大、<1 衰减；`1` 表示不处理；
 * @param pitch 半音，正数升调、负数降调；`0` 表示不处理；
 * @param model TTS 模型名，参与缓存键；
 * @param outputRelative 目标文件的**相对路径**（如
 *   `audio/7/segments/ch_001/seg_000001.mp3`），由 [AudioStorage] 解析；
 * @param segmentIndex 真实段号（从 1 开始），只用于 [PipelineError.TtsFailed] 的文案。
 */
data class TtsSpec(
    val text: String,
    val emotion: Emotion,
    val voiceId: String,
    val speed: Double,
    val volume: Double,
    val pitch: Double,
    val model: String,
    val outputRelative: String,
    val segmentIndex: Int,
)

/**
 * 「文本 → mp3 字节」的传输抽象。
 *
 * 生产实现是 [FishTtsTransport]（包 [FishTtsClient]）；测试可注入假实现，
 * 不需要 MockWebServer，也不需要 Android 环境。
 */
interface TtsTransport {
    /**
     * @param text 已加好情绪前缀的文本，实现方不得重复加前缀；
     * @param voiceId Fish Audio 音色 id；
     * @param speed 语速倍数；`null` 表示不发送 `prosody`
     */
    suspend fun synthesize(text: String, voiceId: String, speed: Double?): ByteArray
}

/** 把 [FishTtsClient] 适配成 [TtsTransport]；不改变客户端行为。 */
class FishTtsTransport(private val client: FishTtsClient) : TtsTransport {
    override suspend fun synthesize(text: String, voiceId: String, speed: Double?): ByteArray =
        client.synthesize(text = text, voiceId = voiceId, speed = speed)
}

/**
 * 一次合成的结果。
 *
 * - [Cached]：目标文件存在且 `.hash` 与当前缓存键一致，未调用 transport；
 * - [Synthesized]：本次真的调用了 transport 并写好了音频 + `.hash`；
 * - [Failed]：合成失败，调用方不得把片段标记为 done；旧目标文件仍保留。
 */
sealed interface TtsResult {

    data class Cached(val relativePath: String) : TtsResult

    data class Synthesized(val relativePath: String) : TtsResult

    data class Failed(val error: PipelineError) : TtsResult
}

/**
 * TTS 合成器（执行计划 §8.9、§15 P2-09）。
 *
 * 一次 [synthesize] 的流程：
 * ```text
 * key = TtsCacheKey.v2(TtsCacheInput(text, emotion, voiceId, speed, volume, pitch, model))
 * if target.exists() && target.hash == key -> Cached
 * raw = <target>.raw.mp3
 * bytes = transport.synthesize(emotion.applyTo(text), voiceId, speed)
 * write raw   （AudioStorage 原子写，自动建目录）
 * if needsProcessing(pitch, volume):
 *     post = <target>.post.mp3
 *     AudioPostProcessor.process(raw, post)
 *     working = post
 * else working = raw
 * rename(working -> target)     // 原子替换；旧目标在失败时不受影响
 * write <target>.hash = key
 * -> Synthesized
 * ```
 *
 * 失败语义（§8.9、§10.2）：
 * - transport / 后处理 / 写文件失败：删除 `.raw.mp3`、`.post.mp3` 临时文件，
 *   旧目标与旧 `.hash` 保持原样，返回 [TtsResult.Failed]；
 * - [PipelineError.TtsFailed] 的 `segmentIndex` 一律改写成 [TtsSpec.segmentIndex]，
 *   因为 [FishTtsClient] 不知道段号（用 0 表示未知）；
 * - 其它 [PipelineError]（Auth / Quota / Network / Storage…）原样向上传递，
 *   便于调用方按 `retryable` 决定重试；
 * - 目标已 rename 成功但 `.hash` 写入失败时返回 `Failed`，新音频保留但没有哈希，
 *   下次会当作缓存未命中重新合成。
 *
 * 取消语义：每个关键步骤前调 `ensureActive()`；`CancellationException` 会先清理
 * 临时文件再原样抛出，调用方（工作池）据此停止且不标记 done。
 *
 * 同一 [TtsSpec.outputRelative] 的并发调用未做互斥；§8.9 的固定 worker 池保证
 * 一个片段同时只被一个 worker 处理。
 *
 * @param transport 可替换的 TTS 传输层
 * @param postProcessor pitch / volume 后处理器
 * @param storage P2-05 存储层（相对路径、原子写、`.hash`）
 * @param io 文件 IO 调度器，默认 [Dispatchers.IO]
 */
class TtsSynthesizer(
    private val transport: TtsTransport,
    private val postProcessor: AudioPostProcessor,
    private val storage: AudioStorage,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    constructor(
        client: FishTtsClient,
        postProcessor: AudioPostProcessor,
        storage: AudioStorage,
        io: CoroutineDispatcher = Dispatchers.IO,
    ) : this(FishTtsTransport(client), postProcessor, storage, io)

    /**
     * 合成一段语音；缓存命中时不发网络请求。
     *
     * @throws CancellationException 协程被取消（临时文件已清理）；其它失败都通过
     *   [TtsResult.Failed] 返回，不向外抛
     */
    suspend fun synthesize(spec: TtsSpec): TtsResult {
        currentCoroutineContext().ensureActive()

        val key = cacheKey(spec)
        val paths = try {
            storage.resolve(spec.outputRelative) to
                storage.resolve(storage.hashRelativeTo(spec.outputRelative))
        } catch (e: IllegalArgumentException) {
            return TtsResult.Failed(ttsFailed(spec, "输出路径非法：${e.message}", e))
        }
        val target = paths.first
        val hashFile = paths.second

        currentCoroutineContext().ensureActive()
        if (withContext(io) { isCacheHit(target, hashFile, key) }) {
            currentCoroutineContext().ensureActive()
            return TtsResult.Cached(spec.outputRelative)
        }

        val rawRelative = siblingRelative(spec.outputRelative, RAW_SUFFIX)
        val postRelative = siblingRelative(spec.outputRelative, POST_SUFFIX)
        val raw = storage.resolve(rawRelative)
        val post = storage.resolve(postRelative)

        try {
            currentCoroutineContext().ensureActive()
            val bytes = transport.synthesize(
                text = spec.emotion.applyTo(spec.text),
                voiceId = spec.voiceId,
                speed = spec.speed,
            )
            if (bytes.isEmpty()) {
                throw ttsFailed(spec, "合成结果为空")
            }

            currentCoroutineContext().ensureActive()
            withContext(io) { storage.writeBytes(rawRelative, bytes) }

            currentCoroutineContext().ensureActive()
            val working = if (postProcessor.needsProcessing(spec.pitch, spec.volume)) {
                val processed = postProcessor.process(raw, post, spec.pitch, spec.volume)
                if (!processed) {
                    throw ttsFailed(spec, "音调/音量后处理失败")
                }
                // post 已包含最终音频；raw 只是中间产物，成功后立即清掉。
                raw.delete()
                post
            } else {
                raw
            }

            currentCoroutineContext().ensureActive()
            withContext(io) {
                if (!working.isFile || working.length() <= 0L) {
                    throw IOException("合成结果为空：${spec.outputRelative}")
                }
                if (!working.renameTo(target)) {
                    throw IOException("无法原子替换目标文件：${spec.outputRelative}")
                }
            }
            // rename 成功后再兜底清一次 raw（后处理成功时上面已删，这里幂等）。
            deleteQuietly(raw)

            currentCoroutineContext().ensureActive()
            withContext(io) {
                storage.writeBytes(
                    storage.hashRelativeTo(spec.outputRelative),
                    key.toByteArray(Charsets.UTF_8),
                )
            }
            return TtsResult.Synthesized(spec.outputRelative)
        } catch (cancellation: CancellationException) {
            deleteQuietly(raw, post)
            throw cancellation
        } catch (t: Throwable) {
            deleteQuietly(raw, post)
            return TtsResult.Failed(toPipelineError(spec, t))
        }
    }

    /** 计算 §8.8 的 v2 缓存键；文本用原始文本，情绪单独入键。 */
    internal fun cacheKey(spec: TtsSpec): String = TtsCacheKey.v2(
        TtsCacheInput(
            text = spec.text,
            emotion = spec.emotion,
            voiceId = spec.voiceId,
            speed = spec.speed,
            volume = spec.volume,
            pitch = spec.pitch,
            model = spec.model,
        ),
    )

    /** 目标存在且 `.hash` 内容等于当前键才算命中；读哈希失败按未命中处理。 */
    private fun isCacheHit(target: File, hashFile: File, key: String): Boolean {
        if (!target.isFile || !hashFile.isFile) return false
        return try {
            hashFile.readText(Charsets.UTF_8).trim() == key
        } catch (e: Exception) {
            // 读不到 / 读一半都按缓存未命中处理，重新合成。
            false
        }
    }

    /**
     * 统一错误映射：
     * - `TtsFailed` 用真实段号重建（transport 不知道段号）；
     * - 其它 `PipelineError` 保留原始错误码（Auth / Quota / Network…）；
     * - 空间不足映射为 [PipelineError.Storage]；
     * - 其余（IO / 运行时异常）包成 `TtsFailed`。
     */
    private fun toPipelineError(spec: TtsSpec, t: Throwable): PipelineError = when (t) {
        is PipelineError.TtsFailed -> ttsFailed(
            spec = spec,
            detail = t.detail,
            cause = t.cause,
            retryable = t.retryable,
        )
        is PipelineError -> t
        is InsufficientStorageException -> PipelineError.Storage(t)
        else -> ttsFailed(
            spec = spec,
            detail = t.message ?: t.javaClass.simpleName,
            cause = t,
        )
    }

    private fun ttsFailed(
        spec: TtsSpec,
        detail: String,
        cause: Throwable? = null,
        retryable: Boolean = false,
    ): PipelineError.TtsFailed = PipelineError.TtsFailed(
        segmentIndex = spec.segmentIndex,
        detail = detail,
        cause = cause,
        retryable = retryable,
    )

    /** 用 `<name><suffix>.<ext>` 生成同目录临时文件的相对路径。 */
    private fun siblingRelative(relative: String, suffix: String): String {
        val slash = relative.lastIndexOf('/')
        val dot = relative.lastIndexOf('.')
        return if (dot > slash) {
            relative.substring(0, dot) + suffix + relative.substring(dot)
        } else {
            relative + suffix
        }
    }

    private fun deleteQuietly(vararg files: File) {
        for (file in files) {
            try {
                if (file.exists()) file.delete()
            } catch (ignored: SecurityException) {
                // 清理失败不应掩盖真正的失败/取消原因。
            }
        }
    }

    private companion object {
        /** 原始 mp3 临时文件后缀：`seg_000001.raw.mp3`。 */
        const val RAW_SUFFIX = ".raw"

        /** 后处理工作文件后缀：`seg_000001.post.mp3`（§10.2）。 */
        const val POST_SUFFIX = ".post"
    }
}
