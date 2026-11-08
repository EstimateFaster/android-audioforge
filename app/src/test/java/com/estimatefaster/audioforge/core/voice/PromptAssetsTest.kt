package com.estimatefaster.audioforge.core.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P1-02：提示词必须逐字等于 fixture（用 SHA-256 锁定）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PromptAssetsTest {

    private lateinit var templates: PromptTemplates

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        templates = PromptAssets(context.assets).templates
    }

    @Test
    fun prompts_match_fixture_hashes() {
        assertEquals(SCRIPT_SHA256, sha256(templates.script))
        assertEquals(PASS0_SHA256, sha256(templates.pass0))
        assertEquals(REPAIR_SHA256, sha256(templates.repair))
    }

    @Test
    fun prompts_have_expected_lengths_and_no_bom() {
        assertEquals(1_882, templates.script.length)
        assertEquals(476, templates.pass0.length)
        assertEquals(98, templates.repair.length)
        assertFalse("不应有 UTF-8 BOM", templates.script.startsWith("\uFEFF"))
        assertFalse("不应有 UTF-8 BOM", templates.pass0.startsWith("\uFEFF"))
        assertFalse("不应有 UTF-8 BOM", templates.repair.startsWith("\uFEFF"))
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val SCRIPT_SHA256 = "38a24cc1f93524b020b8e207a31b107051b9f8d54b4ede4838a0634622baa9cb"
        const val PASS0_SHA256 = "30aca6d2eae1e9d1a718929a9f84081a9aab2d39cdfa716ccb4ab48b93e2291f"
        const val REPAIR_SHA256 = "98b78e5aae07aef99e699c0d0a2a3cbe2893bb6df55109b02c71dc6d3044ac34"
    }
}
