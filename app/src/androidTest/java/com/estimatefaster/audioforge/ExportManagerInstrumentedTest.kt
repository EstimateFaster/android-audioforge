package com.estimatefaster.audioforge

import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.estimatefaster.audioforge.core.storage.AudioStorage
import com.estimatefaster.audioforge.core.storage.ExportManager
import com.estimatefaster.audioforge.core.storage.ExportResult
import com.estimatefaster.audioforge.core.storage.ProjectFiles
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File

/**
 * P2-15 真机/模拟器 smoke（API 35）：
 * [AudioStorage] 写一个临时 `.m4a`（任意字节）→ `exportChapter` →
 * Uri 可被 ContentResolver 打开、字节一致、MediaStore 行已发布（IS_PENDING=0）；
 * 结束后删除导出行/文件与临时源目录。
 */
@RunWith(AndroidJUnit4::class)
class ExportManagerInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var storageRoot: File
    private lateinit var manager: ExportManager

    private var exportedUri: Uri? = null
    private var exportedName: String? = null

    @Before
    fun setUp() {
        storageRoot = File(context.filesDir, "p215-export-it").apply {
            deleteRecursively()
            mkdirs()
        }
        manager = ExportManager(context, AudioStorage(storageRoot))
    }

    @After
    fun tearDown() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportedUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
        } else {
            exportFallbackFile()?.delete()
        }
        storageRoot.deleteRecursively()
    }

    @Test(timeout = 60_000L)
    fun export_chapter_writes_readable_file_and_publishes_pending_row() {
        val audioBytes = ByteArray(4_096) { index -> ((index * 31 + 7) % 251).toByte() }
        val storage = AudioStorage(storageRoot)
        val relative = storage.writeChapterAudio(
            NOVEL_ID,
            CHAPTER_IDX,
            ByteArrayInputStream(audioBytes),
        )
        assertTrue("测试源文件应存在：$relative", storage.resolve(relative).isFile)

        // 时间戳保证重复运行不会因 MediaStore 重名而改名。
        val title = "P215 导出:测试?${System.nanoTime()}"
        val result = manager.exportChapter(NOVEL_ID, CHAPTER_IDX, title)

        assertTrue("导出应成功：$result", result is ExportResult.Success)
        val success = result as ExportResult.Success
        exportedUri = success.uri
        exportedName = success.displayName

        assertEquals(ExportManager.displayName(title, CHAPTER_IDX), success.displayName)
        assertTrue(
            "文件名应 ≤${ProjectFiles.MAX_FILE_NAME}：${success.displayName}",
            success.displayName.length <= ProjectFiles.MAX_FILE_NAME,
        )
        assertTrue("应保留 .m4a：${success.displayName}", success.displayName.endsWith(".m4a"))
        assertTrue(
            "应保留章节号：${success.displayName}",
            success.displayName.endsWith("-第001章.m4a"),
        )
        assertFalse(
            "不能含非法字符：${success.displayName}",
            success.displayName.any { it in ILLEGAL_FILE_NAME_CHARS },
        )

        assertEquals("content", success.uri.scheme)
        val opened = context.contentResolver.openInputStream(success.uri)
        assertNotNull("ContentResolver 应能打开导出 Uri", opened)
        opened!!.use {
            assertArrayEquals("导出的字节应与源文件一致", audioBytes, it.readBytes())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertEquals("media", success.uri.authority)
            assertMediaStoreRow(success.uri, success.displayName)
        } else {
            assertEquals("${context.packageName}.fileprovider", success.uri.authority)
            val file = exportFallbackFile()
            assertNotNull("应能定位回退导出文件", file)
            assertTrue("应落到应用外部目录：$file", file!!.isFile)
        }
    }

    private fun assertMediaStoreRow(uri: Uri, displayName: String) {
        val projection = arrayOf(
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.IS_PENDING,
        )
        val cursor = context.contentResolver.query(uri, projection, null, null, null)
        assertNotNull("MediaStore 应能查询导出 Uri", cursor)
        cursor!!.use {
            assertTrue("应有一行导出记录", it.moveToFirst())
            assertEquals(displayName, it.getString(0))
            val relativePath = it.getString(1) ?: ""
            assertTrue(
                "RELATIVE_PATH 应在 Music/AudioForge：$relativePath",
                relativePath.startsWith("Music/AudioForge/"),
            )
            val mime = it.getString(2)
            assertTrue("MIME 应为音频类型，实际 $mime", mime != null && mime.startsWith("audio/"))
            assertEquals("IS_PENDING 应已发布为 0", 0, it.getInt(3))
        }
    }

    /** API 26–28 的回退文件；API 29+ 或名称未知时返回 null。 */
    private fun exportFallbackFile(): File? {
        val name = exportedName ?: return null
        val musicRoot = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return null
        return File(File(musicRoot, ExportManager.EXPORT_DIR_NAME), name)
    }

    private companion object {
        const val NOVEL_ID = 7L
        const val CHAPTER_IDX = 0
        const val ILLEGAL_FILE_NAME_CHARS = "\\/:*?\"<>|"
    }
}
