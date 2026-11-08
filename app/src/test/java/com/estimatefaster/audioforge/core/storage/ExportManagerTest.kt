package com.estimatefaster.audioforge.core.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * P2-15：导出文件名清洗、分享 Intent、源文件缺失时返回 Failure。
 *
 * 不触达 MediaStore：源文件缺失检查在分支选择之前，API 35 下也不会插入 pending 行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExportManagerTest {

    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var manager: ExportManager

    @Before
    fun setUp() {
        root = Files.createTempDirectory("export-manager-").toFile()
        context = ApplicationProvider.getApplicationContext<Context>()
        manager = ExportManager(context, AudioStorage(root))
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // 文件名清洗
    // ------------------------------------------------------------------

    @Test
    fun display_name_sanitizes_illegal_characters_and_keeps_cjk() {
        assertEquals(
            "我的_小说_第一卷-第001章.m4a",
            ExportManager.displayName("我的:小说?第一卷", 0),
        )
        assertEquals(
            "a_b_c_d_e_f_g_h_i_j-第001章.m4a",
            ExportManager.displayName("a\\b/c:d*e?f\"g<h>i|j", 0),
        )
    }

    @Test
    fun display_name_pads_chapter_number_and_appends_extension() {
        assertEquals("Book-第001章.m4a", ExportManager.displayName("Book", 0))
        assertEquals("Book-第042章.m4a", ExportManager.displayName("Book", 41))
        assertEquals("Book-第1000章.m4a", ExportManager.displayName("Book", 999))
    }

    @Test
    fun display_name_truncates_to_limit_but_keeps_chapter_suffix() {
        val name = ExportManager.displayName("长".repeat(200), 0)

        assertTrue("长度必须 ≤80：${name.length}", name.length <= ProjectFiles.MAX_FILE_NAME)
        assertTrue("必须保留章节后缀：$name", name.endsWith("-第001章.m4a"))
    }

    @Test
    fun blank_title_falls_back_to_export() {
        assertEquals("export-第001章.m4a", ExportManager.displayName("   ", 0))
    }

    @Test
    fun negative_chapter_index_is_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ExportManager.displayName("Book", -1)
        }

        val result = manager.exportChapter(1, -1, "Book")
        assertTrue("非法章节序号应返回 Failure：$result", result is ExportResult.Failure)
    }

    // ------------------------------------------------------------------
    // 分享 Intent
    // ------------------------------------------------------------------

    @Test
    fun share_intent_grants_read_permission_for_audio() {
        val uri = Uri.parse("content://${context.packageName}.fileprovider/export_music/book.m4a")

        val intent = manager.shareIntent(uri)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals(ExportManager.MIME_TYPE_AUDIO_MP4, intent.type)
        assertEquals(uri, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertTrue(
            "必须授予临时读权限",
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
        val clip = intent.clipData
        assertNotNull("ClipData 不能为空", clip)
        assertEquals(uri, clip!!.getItemAt(0).uri)
    }

    @Test
    fun share_intent_keeps_custom_mime() {
        val uri = Uri.parse("content://${context.packageName}.fileprovider/export_music/book.m4a")

        val intent = manager.shareIntent(uri, mime = "audio/aac")

        assertEquals("audio/aac", intent.type)
        assertEquals(uri, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    // ------------------------------------------------------------------
    // 失败路径
    // ------------------------------------------------------------------

    @Test
    fun missing_source_file_returns_failure() {
        val result = manager.exportChapter(7, 0, "书名")

        assertTrue("应返回 Failure：$result", result is ExportResult.Failure)
        val failure = result as ExportResult.Failure
        assertTrue("应说明源文件缺失：${failure.message}", failure.message.contains("不存在"))
        assertNull(failure.cause)
    }
}
