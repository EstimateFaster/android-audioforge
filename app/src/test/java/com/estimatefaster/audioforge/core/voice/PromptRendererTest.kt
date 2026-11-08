package com.estimatefaster.audioforge.core.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/** 纯字符串替换：验证占位符只替换已知键，提示词里的 `{}` 不被误伤。 */
class PromptRendererTest {

    private val renderer = PromptRenderer(
        PromptTemplates(
            script = "SCRIPT registry={registry} emotions={emotions} pool={voice_pool} text={text} keep={unknown}",
            pass0 = "PASS0 title={title} pool={voice_pool} text={text}",
            repair = "\n\n补回：{missing}",
        ),
    )

    @Test
    fun script_replaces_all_known_placeholders() {
        assertEquals(
            "SCRIPT registry=R emotions=E pool=- a: A text=正文 keep={unknown}",
            renderer.script(registry = "R", emotions = "E", voicePool = "- a: A", text = "正文"),
        )
    }

    @Test
    fun pass0_replaces_all_known_placeholders() {
        assertEquals(
            "PASS0 title=书名 pool=P text=开头",
            renderer.pass0(title = "书名", voicePool = "P", text = "开头"),
        )
    }

    @Test
    fun repair_appends_missing_segments_as_bullets() {
        assertEquals(
            "BASE\n\n补回：- 第一段\n- 第二段",
            renderer.repair("BASE", listOf("第一段", "第二段")),
        )
    }
}
