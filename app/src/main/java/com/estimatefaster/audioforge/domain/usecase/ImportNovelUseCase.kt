package com.estimatefaster.audioforge.domain.usecase

import com.estimatefaster.audioforge.core.ingest.ChapterSplitter
import com.estimatefaster.audioforge.core.ingest.TextDecodeResult
import com.estimatefaster.audioforge.core.ingest.TextDecoder
import com.estimatefaster.audioforge.core.storage.AudioStorage
import com.estimatefaster.audioforge.data.repository.ChapterInput
import com.estimatefaster.audioforge.data.repository.ProjectRepository
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 导入结果（P2-06，执行计划 §7.7）。
 *
 * 除 [Success] 外都不会新建项目；[NeedsEncoding] 表示文件已读取但编码无法自动识别，
 * 调用方让用户选择编码后，重新打开源文件并调用
 * [ImportNovelUseCase.import]（传 `encoding`）即可重试。
 */
sealed interface ImportResult {

    /** 导入成功；[charCount] 是各章正文的 Unicode code point 数（与 `novels.charCount` 一致）。 */
    data class Success(
        val novelId: Long,
        val chapterCount: Int,
        val charCount: Int,
    ) : ImportResult

    /** 同名项目已存在；不覆盖，调用方提示「打开已有 / 另存为副本」。 */
    data class Duplicate(
        val existingNovelId: Long,
        val title: String,
    ) : ImportResult

    /** 自动识别编码失败；[candidates] 可直接展示给用户手动选择。 */
    data class NeedsEncoding(
        val candidates: List<String>,
    ) : ImportResult

    /** 用户可读的失败原因；[cause] 仅用于日志/诊断。 */
    data class Failure(
        val message: String,
        val cause: Throwable? = null,
    ) : ImportResult
}

/**
 * 导入 txt 小说（P2-06，执行计划 §7.7）。
 *
 * 流程：校验后缀/大小 → 原子复制到 `imports/<uuid>.txt` → 解码 → 切章 →
 * 重名检查 → 单事务写入 novel + chapters。
 *
 * 与 SAF 解耦：调用方从 `ContentResolver` 取到 [displayName]、[InputStream] 与
 * 可选的 [sizeBytes] 后调用即可。文件复制、大小限制与进度都在 IO 线程完成；
 * [import] 会消费并关闭 [InputStream]。
 *
 * 清理策略：任何非 [ImportResult.Success] 结果（[ImportResult.Failure]、
 * [ImportResult.Duplicate]、[ImportResult.NeedsEncoding]）都会删除本次复制到
 * `imports/` 的文件，不留下半项目；编码重试需要重新打开源流。数据库写入失败由
 * [ProjectRepository.createProject] 的事务回滚负责。
 */
class ImportNovelUseCase(
    private val projects: ProjectRepository,
    private val audioStorage: AudioStorage,
    private val textDecoder: TextDecoder,
    private val chapterSplitter: ChapterSplitter,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * 导入一本 txt 小说。
     *
     * @param displayName 源文件名（来自 `OpenableColumns.DISPLAY_NAME`），必须以
     *   `.txt` 结尾（大小写不敏感）；去掉后缀后作为项目标题。
     * @param input 源文件流；本方法负责关闭。调用方不要并发读取。
     * @param sizeBytes 源文件字节数（`OpenableColumns.SIZE`）；未知时传 `null`，
     *   复制过程中仍会按 [MAX_IMPORT_BYTES] 边读边限制。
     * @param encoding 手动指定编码（如 `GBK`）；`null` 表示自动识别。
     *   在收到 [ImportResult.NeedsEncoding] 后，用户选中编码再调用一次即可。
     * @param onProgress 复制进度回调，参数为**累计已读字节数**；在 IO 线程调用。
     * @return 成功、重名、需要手动编码或失败，均以 [ImportResult] 返回，不抛异常
     *   （协程取消除外）。
     */
    suspend fun import(
        displayName: String,
        input: InputStream,
        sizeBytes: Long? = null,
        encoding: String? = null,
        onProgress: ((bytesRead: Long) -> Unit)? = null,
    ): ImportResult = withContext(io) {
        var importRelative: String? = null
        var committed = false
        try {
            val fileName = displayName.trim()
            if (fileName.isEmpty()) {
                return@withContext ImportResult.Failure("文件名不能为空")
            }
            if (!fileName.endsWith(TXT_SUFFIX, ignoreCase = true)) {
                return@withContext ImportResult.Failure("只支持 .txt 文件")
            }

            val title = fileName.dropLast(TXT_SUFFIX.length).trim()
            if (title.isEmpty()) {
                return@withContext ImportResult.Failure("文件名缺少标题")
            }

            if (sizeBytes != null) {
                if (sizeBytes < 0L) {
                    return@withContext ImportResult.Failure("文件大小无效")
                }
                if (sizeBytes == 0L) {
                    return@withContext ImportResult.Failure(EMPTY_FILE_MESSAGE)
                }
                if (sizeBytes > MAX_IMPORT_BYTES) {
                    return@withContext ImportResult.Failure(TOO_LARGE_MESSAGE)
                }
            }

            // 先确定目标相对路径再写：失败时 finally 能准确清理本次复制。
            val uuid = UUID.randomUUID()
            val relative = audioStorage.importRelative(uuid)
            importRelative = relative
            SizeLimitedInputStream(input, MAX_IMPORT_BYTES, onProgress).use { source ->
                audioStorage.writeImport(source, uuid)
            }

            val copied = audioStorage.resolve(relative)
            if (!copied.isFile || copied.length() == 0L) {
                return@withContext ImportResult.Failure(EMPTY_FILE_MESSAGE)
            }
            val bytes = copied.readBytes()

            val text = when (val decoded = textDecoder.decode(bytes, manualCharset = encoding)) {
                is TextDecodeResult.Success -> decoded.text
                is TextDecodeResult.ManualEncodingRequired ->
                    return@withContext ImportResult.NeedsEncoding(decoded.candidates)

                is TextDecodeResult.TooLarge ->
                    return@withContext ImportResult.Failure(TOO_LARGE_MESSAGE)

                is TextDecodeResult.Failure ->
                    return@withContext ImportResult.Failure(decoded.reason)
            }

            val chapters = chapterSplitter.split(text)
            if (chapters.isEmpty() || chapters.all { it.text.isBlank() }) {
                return@withContext ImportResult.Failure("文件中没有可导入的章节")
            }
            val chapterInputs = chapters.map { chapter ->
                ChapterInput(title = chapter.title, rawText = chapter.text)
            }

            val existing = projects.findByTitle(title)
            if (existing != null) {
                return@withContext ImportResult.Duplicate(existingNovelId = existing.id, title = title)
            }

            val novelId = projects.createProject(
                title = title,
                sourceName = fileName,
                sourceRelPath = relative,
                chapters = chapterInputs,
            )
            // 项目已提交，源文件必须保留。
            committed = true
            ImportResult.Success(
                novelId = novelId,
                chapterCount = chapterInputs.size,
                charCount = ProjectRepository.totalCharCount(chapterInputs),
            )
        } catch (e: ImportTooLargeException) {
            ImportResult.Failure(TOO_LARGE_MESSAGE, e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportResult.Failure(
                message = "导入失败：${e.message ?: e::class.java.simpleName}",
                cause = e,
            )
        } finally {
            if (!committed) {
                importRelative?.let { relative ->
                    runCatching { audioStorage.deleteFile(relative) }
                }
            }
            runCatching { input.close() }
        }
    }

    companion object {
        /** 单次导入上限：64MB（与 [TextDecoder.MAX_IMPORT_BYTES] 保持一致）。 */
        const val MAX_IMPORT_BYTES: Long = TextDecoder.MAX_IMPORT_BYTES

        /** 只接受 txt（大小写不敏感）。 */
        const val TXT_SUFFIX: String = ".txt"

        const val EMPTY_FILE_MESSAGE: String = "文件为空"
        const val TOO_LARGE_MESSAGE: String = "文件超过 64MB 限制"
    }
}

/**
 * 边读边限制大小并上报进度的输入流；超过上限立即抛 [ImportTooLargeException]，
 * 让上层在复制阶段就失败，而不是先把超大文件落盘。
 */
private class SizeLimitedInputStream(
    private val delegate: InputStream,
    private val maxBytes: Long,
    private val onProgress: ((bytesRead: Long) -> Unit)?,
) : InputStream() {

    private var totalBytes: Long = 0L

    override fun read(): Int {
        val value = delegate.read()
        if (value < 0) return -1
        record(1L)
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = delegate.read(b, off, len)
        if (read > 0) record(read.toLong())
        return read
    }

    private fun record(count: Long) {
        totalBytes += count
        if (totalBytes > maxBytes) throw ImportTooLargeException(totalBytes)
        onProgress?.invoke(totalBytes)
    }

    override fun available(): Int = delegate.available()

    override fun close() {
        delegate.close()
    }
}

private class ImportTooLargeException(observedBytes: Long) : IOException(
    "读取字节数超过上限：$observedBytes",
)
