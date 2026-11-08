package com.estimatefaster.audioforge.core.model

/**
 * 角色 ID 约定（执行计划 §8.1 / 附录 G）：
 * - 旁白固定 `narrator`，不参与 LLM 选角；
 * - 未知/已删除角色用 `unknown`；
 * - 新角色按 `role_0001` 递增（1 基）。
 */
const val NARRATOR_ROLE_ID = "narrator"

const val UNKNOWN_ROLE_ID = "unknown"

/** 旁白音色类别；不在 `poolForLlm()` 里。 */
const val NARRATOR_CATEGORY = "narrator_male"

private const val ROLE_ID_PREFIX = "role_"
private const val ROLE_ID_WIDTH = 4

fun roleIdFor(index: Int): String {
    require(index > 0) { "角色序号从 1 开始，实际为 $index" }
    return ROLE_ID_PREFIX + index.toString().padStart(ROLE_ID_WIDTH, '0')
}
