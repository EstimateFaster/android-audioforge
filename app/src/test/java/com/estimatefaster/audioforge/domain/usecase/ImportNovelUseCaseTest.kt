package com.estimatefaster.audioforge.domain.usecase

import com.estimatefaster.audioforge.core.ingest.ChapterSplitter
import com.estimatefaster.audioforge.core.ingest.TextDecoder
import com.estimatefaster.audioforge.data.repository.ChapterInput
import com.estimatefaster.audioforge.data.repository.RepositoryFixture
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P2-06：导入用例的 6 个核心场景（执行计划 §7.7 / §7.8）。
 *
 * 使用 Robolectric + in-memory Room + 临时目录 AudioStorage；
 * 不依赖 SAF、Context 或真实文件管理器。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportNovelUseCaseTest {

    private lateinit var fixture: RepositoryFixture
    private lateinit var useCase: ImportNovelUseCase

    @Before
    fun setUp() {
        fixture = RepositoryFixture()
        useCase = ImportNovelUseCase(
            fixture.projects,
            fixture.audioStorage,
            TextDecoder(),
            ChapterSplitter(),
            Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun utf8_two_chapter_novel_is_persisted_with_source_file() = runTest {
        val text = "第一章 起\n\n正文一。\n\n第二章 承\n\n正文二二。"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val progress = mutableListOf<Long>()

        val result = useCase.import(
            displayName = "长夜.txt",
            input = ByteArrayInputStream(bytes),
            sizeBytes = bytes.size.toLong(),
            onProgress = { progress.add(it) },
        )

        assertTrue("实际是 $result", result is ImportResult.Success)
        val success = result as ImportResult.Success
        assertEquals(2, success.chapterCount)
        assertEquals(9, success.charCount)
        assertEquals(bytes.size.toLong(), progress.last())

        val novel = requireNotNull(fixture.projects.getProject(success.novelId))
        assertEquals("长夜", novel.title)
        assertEquals("长夜.txt", novel.sourceName)
        assertTrue(novel.sourceRelPath.startsWith("imports/"))
        assertTrue(novel.sourceRelPath.endsWith(".txt"))
        assertTrue("导入源文件必须存在", fixture.audioStorage.resolve(novel.sourceRelPath).isFile)
        assertEquals(9, novel.charCount)

        val briefs = fixture.chapters.observeBriefs(success.novelId).first()
        assertEquals(listOf(0, 1), briefs.map { it.idx })
        assertEquals(listOf("第一章 起", "第二章 承"), briefs.map { it.title })
        assertEquals("正文一。", fixture.chapters.getRawText(briefs[0].id))
        assertEquals("正文二二。", fixture.chapters.getRawText(briefs[1].id))
    }

    @Test
    fun non_txt_extension_fails_without_database_or_file_residue() = runTest {
        val result = useCase.import(
            displayName = "小说.pdf",
            input = ByteArrayInputStream("第一章 起\n\n正文。".toByteArray(Charsets.UTF_8)),
            sizeBytes = 30L,
        )

        assertFailureWithoutResidue(result, "失败原因是 $result")
    }

    @Test
    fun empty_file_declared_by_size_fails_without_residue() = runTest {
        val result = useCase.import(
            displayName = "空文件.txt",
            input = ByteArrayInputStream(ByteArray(0)),
            sizeBytes = 0L,
        )

        assertFailureWithoutResidue(result)
    }

    @Test
    fun empty_stream_with_unknown_size_fails_and_cleans_copied_file() = runTest {
        val result = useCase.import(
            displayName = "空文件.txt",
            input = ByteArrayInputStream(ByteArray(0)),
            sizeBytes = null,
        )

        assertFailureWithoutResidue(result)
    }

    @Test
    fun oversized_file_is_rejected_by_declared_size() = runTest {
        val result = useCase.import(
            displayName = "过大的小说.txt",
            input = ByteArrayInputStream("第一章 起\n\n正文。".toByteArray(Charsets.UTF_8)),
            sizeBytes = ImportNovelUseCase.MAX_IMPORT_BYTES + 1L,
        )

        assertFailureWithoutResidue(result)
    }

    @Test
    fun duplicate_title_returns_existing_id_without_creating_another_project() = runTest {
        val existingId = fixture.projects.createProject(
            title = "长夜",
            sourceName = "旧版.txt",
            sourceRelPath = "imports/old.txt",
            chapters = listOf(ChapterInput(title = "第一章", rawText = "旧正文")),
            createdAt = 1_000L,
        )
        val bytes = "第一章 起\n\n新正文。".toByteArray(Charsets.UTF_8)

        // 后缀大小写不敏感：.TXT 同样视为 txt。
        val result = useCase.import(
            displayName = "长夜.TXT",
            input = ByteArrayInputStream(bytes),
            sizeBytes = bytes.size.toLong(),
        )

        assertTrue("实际是 $result", result is ImportResult.Duplicate)
        val duplicate = result as ImportResult.Duplicate
        assertEquals(existingId, duplicate.existingNovelId)
        assertEquals("长夜", duplicate.title)

        assertEquals(1, fixture.countRows("novels"))
        assertEquals(1, fixture.countRows("chapters"))
        val existingChapter = fixture.chapters.observeBriefs(existingId).first().single()
        assertEquals("旧正文", fixture.chapters.getRawText(existingChapter.id))
        assertTrue("重名时不得残留新复制的源文件", importFiles().isEmpty())
    }

    @Test
    fun illegal_encoding_returns_candidates_then_manual_retry_succeeds() = runTest {
        // 中文占比很低，自动识别会因 CJK 比例不足而拒绝；手动 GBK 可以严格解码。
        val text = "第一章 起\n\n" + "abc ".repeat(200) + "终。"
        val bytes = text.toByteArray(Charset.forName("GBK"))

        val first = useCase.import(
            displayName = "低中文.txt",
            input = ByteArrayInputStream(bytes),
            sizeBytes = bytes.size.toLong(),
        )

        assertTrue("实际是 $first", first is ImportResult.NeedsEncoding)
        val needsEncoding = first as ImportResult.NeedsEncoding
        assertTrue(needsEncoding.candidates.contains("GB18030"))
        assertEquals(0, fixture.countRows("novels"))
        assertTrue("编码未定时不得残留导入文件", importFiles().isEmpty())

        val retry = useCase.import(
            displayName = "低中文.txt",
            input = ByteArrayInputStream(bytes),
            sizeBytes = bytes.size.toLong(),
            encoding = "GBK",
        )

        assertTrue("实际是 $retry", retry is ImportResult.Success)
        assertEquals(1, fixture.countRows("novels"))
    }

    @Test
    fun repository_failure_rolls_back_and_removes_import_file() = runTest {
        fixture.db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_chapter_insert
            BEFORE INSERT ON chapters
            BEGIN
                SELECT RAISE(ABORT, 'boom');
            END
            """.trimIndent(),
        )
        val bytes = "第一章 起\n\n正文一。\n\n第二章 承\n\n正文二二。".toByteArray(Charsets.UTF_8)

        val result = useCase.import(
            displayName = "回滚.txt",
            input = ByteArrayInputStream(bytes),
            sizeBytes = bytes.size.toLong(),
        )

        assertTrue("实际是 $result", result is ImportResult.Failure)
        assertNotNull("失败应保留底层异常", (result as ImportResult.Failure).cause)
        assertEquals(0, fixture.countRows("novels"))
        assertEquals(0, fixture.countRows("chapters"))
        assertTrue("事务回滚后不得残留导入文件", importFiles().isEmpty())
    }

    private fun assertFailureWithoutResidue(
        result: ImportResult,
        message: String = "实际是 $result",
    ) {
        assertTrue(message, result is ImportResult.Failure)
        assertEquals(0, fixture.countRows("novels"))
        assertEquals(0, fixture.countRows("chapters"))
        assertTrue("失败时不得残留导入文件", importFiles().isEmpty())
    }

    private fun importFiles(): List<File> =
        File(fixture.filesRoot, "imports").listFiles()?.toList().orEmpty()
}
