package com.estimatefaster.audioforge.core.storage

import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 章节导出/分享（执行计划 §10.5、P2-15）。
 *
 * 两个分支：
 * - **API 29+**：写入 [MediaStore.Audio.Media.EXTERNAL_CONTENT_URI]，
 *   `RELATIVE_PATH = Music/AudioForge/<safeTitle>`；先 `IS_PENDING=1`，
 *   写完再发布为 `IS_PENDING=0`，返回 `content://media/...` Uri。
 * - **API 26–28**：复制到 `getExternalFilesDir(DIRECTORY_MUSIC)/AudioForge/`，
 *   通过 [FileProvider]（authority 默认 `<applicationId>.fileprovider`）分享，
 *   不申请 `WRITE_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE`。
 *
 * 失败时清理 MediaStore pending 行或半成品 `.tmp` 文件，返回 [ExportResult.Failure]，
 * 不向调用方抛异常。源文件由 [AudioStorage] 解析，本类不接收绝对路径。
 */
class ExportManager(
    appContext: Context,
    private val storage: AudioStorage,
    authority: String = "${appContext.packageName}.fileprovider",
) {

    private val context: Context = appContext.applicationContext

    private val fileProviderAuthority: String = authority

    private val resolver: ContentResolver get() = context.contentResolver

    /**
     * 导出某一章成品音频。
     *
     * @param novelId 项目 id（正数）
     * @param chapterIdx 0 基章节序号（与 DB / [ProjectFiles] 一致）
     * @param title 书名/章节标题；只用于导出文件名与目录，不参与内部路径
     */
    fun exportChapter(novelId: Long, chapterIdx: Int, title: String): ExportResult {
        val relative = try {
            storage.chapterAudioRelative(novelId, chapterIdx)
        } catch (e: Exception) {
            return ExportResult.Failure("章节参数非法：${e.message}", e)
        }
        val source = try {
            storage.resolve(relative)
        } catch (e: Exception) {
            return ExportResult.Failure("章节路径非法：${e.message}", e)
        }
        if (!source.isFile) {
            return ExportResult.Failure("章节音频不存在：$relative")
        }
        val exportName = try {
            displayName(title, chapterIdx)
        } catch (e: Exception) {
            return ExportResult.Failure("导出文件名非法：${e.message}", e)
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportViaMediaStore(source, safeTitle(title), exportName)
            } else {
                exportViaAppExternalFiles(source, exportName)
            }
        } catch (e: Exception) {
            ExportResult.Failure("导出失败：${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /**
     * 分享 Intent：`ACTION_SEND` + 音频 MIME + 读 Uri 授权。
     *
     * 同时设置 `EXTRA_STREAM` 与 [ClipData]，保证 API 16+ 的接收方都能拿到 Uri
     * 并被授予临时读权限。
     */
    fun shareIntent(uri: Uri, mime: String = MIME_TYPE_AUDIO_MP4): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(CLIP_LABEL, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    // ------------------------------------------------------------------
    // API 29+
    // ------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportViaMediaStore(
        source: File,
        safeTitle: String,
        displayName: String,
    ): ExportResult {
        val pending = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, MIME_TYPE_AUDIO_MP4)
            put(MediaStore.Audio.Media.RELATIVE_PATH, musicRelativePath(safeTitle))
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, pending)
            ?: return ExportResult.Failure("MediaStore 未返回导出 Uri")
        return try {
            val output = resolver.openOutputStream(uri, "w")
                ?: throw IOException("MediaStore 输出流不可用")
            output.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            }
            val publish = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
            if (resolver.update(uri, publish, null, null) <= 0) {
                throw IOException("MediaStore 未能发布导出文件")
            }
            ExportResult.Success(uri, displayName)
        } catch (e: Exception) {
            // 删除 pending 行，同时清掉已写入的部分字节。
            runCatching { resolver.delete(uri, null, null) }
            ExportResult.Failure(
                "写入 Music 失败：${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }
    }

    // ------------------------------------------------------------------
    // API 26–28
    // ------------------------------------------------------------------

    private fun exportViaAppExternalFiles(source: File, displayName: String): ExportResult {
        val musicRoot = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: return ExportResult.Failure("外部存储不可用，无法导出")
        val exportDir = File(musicRoot, EXPORT_DIR_NAME)
        if (!exportDir.isDirectory && !exportDir.mkdirs() && !exportDir.isDirectory) {
            return ExportResult.Failure("无法创建导出目录：${exportDir.absolutePath}")
        }
        val target = File(exportDir, displayName)
        // 先算 Uri（不要求文件存在）；provider 配置错误时不会留下半文件。
        val uri = try {
            FileProvider.getUriForFile(context, fileProviderAuthority, target)
        } catch (e: Exception) {
            return ExportResult.Failure(
                "FileProvider 无法生成分享 Uri：${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }
        val temp = File(exportDir, displayName + ProjectFiles.TEMP_SUFFIX)
        return try {
            FileOutputStream(temp).use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
                out.flush()
                out.fd.sync()
            }
            // POSIX rename 原子替换目标；失败时旧文件保持原样。
            if (!temp.renameTo(target)) {
                throw IOException("无法重命名导出文件：${target.name}")
            }
            ExportResult.Success(uri, displayName)
        } catch (e: Exception) {
            temp.delete()
            ExportResult.Failure(
                "写入应用外部目录失败：${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }
    }

    companion object {
        /** 章节导出 MIME（m4a 在 Android 上映射为 `audio/mp4`）。 */
        const val MIME_TYPE_AUDIO_MP4: String = "audio/mp4"

        /** API 26–28 回退目录：`<external-files>/Music/AudioForge`。 */
        const val EXPORT_DIR_NAME: String = "AudioForge"

        private const val CHAPTER_DIGITS = 3
        private const val M4A_EXTENSION = ".m4a"
        private const val CLIP_LABEL = "audio"

        /**
         * 清洗后的书名/标题（≤ [ProjectFiles.MAX_FILE_NAME]，非法字符替换为 `_`）。
         *
         * 用作 `Music/AudioForge/<safeTitle>` 目录名，不参与内部文件路径。
         */
        fun safeTitle(title: String): String = ProjectFiles.sanitizeFileName(title)

        /**
         * 导出文件名：`<safeTitle>-第<idx+1:03d>章.m4a`，总长度 ≤80。
         *
         * 先给章节后缀预留长度再截断标题，保证章节号与 `.m4a` 扩展名不会被截掉；
         * 最后再走一次 [ProjectFiles.sanitizeFileName] 兜底。
         */
        fun displayName(title: String, chapterIdx: Int): String {
            require(chapterIdx >= 0) { "chapterIdx 不能为负：$chapterIdx" }
            val number = (chapterIdx.toLong() + 1L)
                .toString()
                .padStart(CHAPTER_DIGITS, '0')
            val suffix = "-第${number}章${M4A_EXTENSION}"
            val suffixLength = suffix.codePointCount(0, suffix.length)
            val titleBudget = (ProjectFiles.MAX_FILE_NAME - suffixLength).coerceAtLeast(1)
            val trimmedTitle = truncateToCodePoints(safeTitle(title), titleBudget)
            return ProjectFiles.sanitizeFileName(trimmedTitle + suffix)
        }

        private fun musicRelativePath(safeTitle: String): String =
            "${Environment.DIRECTORY_MUSIC}/$EXPORT_DIR_NAME/$safeTitle"

        private fun truncateToCodePoints(value: String, maxCodePoints: Int): String {
            if (maxCodePoints <= 0) return ""
            val count = value.codePointCount(0, value.length)
            if (count <= maxCodePoints) return value
            return value.substring(0, value.offsetByCodePoints(0, maxCodePoints))
        }
    }
}

/** 导出结果；[Failure] 不抛异常，[cause] 用于日志/诊断。 */
sealed interface ExportResult {

    data class Success(val uri: Uri, val displayName: String) : ExportResult

    data class Failure(val message: String, val cause: Throwable? = null) : ExportResult
}
