package com.estimatefaster.audioforge.core.voice

import android.content.res.AssetManager

/** 三段提示词的原文；逐字来自 `docs/fixtures/porting-vectors.json`，禁止翻译/改写。 */
data class PromptTemplates(
    val script: String,
    val pass0: String,
    val repair: String,
)

class PromptAssets(private val assets: AssetManager) {

    val templates: PromptTemplates by lazy {
        PromptTemplates(
            script = read("prompts/script.txt"),
            pass0 = read("prompts/pass0.txt"),
            repair = read("prompts/repair.txt"),
        )
    }

    private fun read(path: String): String =
        assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
}

/**
 * 简单字符串替换（不能 `String.format`：提示词里本身有 `{}`）。
 * 占位符：script `{registry}/{emotions}/{voice_pool}/{text}`；pass0 `{title}/{voice_pool}/{text}`；repair `{missing}`。
 */
class PromptRenderer(private val templates: PromptTemplates) {

    fun script(registry: String, emotions: String, voicePool: String, text: String): String =
        templates.script
            .replace("{registry}", registry)
            .replace("{emotions}", emotions)
            .replace("{voice_pool}", voicePool)
            .replace("{text}", text)

    fun pass0(title: String, voicePool: String, text: String): String =
        templates.pass0
            .replace("{title}", title)
            .replace("{voice_pool}", voicePool)
            .replace("{text}", text)

    fun repair(basePrompt: String, missing: List<String>): String =
        basePrompt + templates.repair.replace(
            "{missing}",
            missing.joinToString("\n") { "- $it" },
        )
}
