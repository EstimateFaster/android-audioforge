package com.estimatefaster.audioforge.core.model

/** LLM 输出里的「新角色」候选；`voice` 是音色池类别 id。 */
data class NewCharacter(
    val name: String,
    val gender: Gender = Gender.Unknown,
    val importance: Importance = Importance.Supporting,
    val aliases: List<String> = emptyList(),
    val note: String = "",
    val voice: String = "",
    val aliasOf: String = "",
)

/** 已注册角色；`category`/`voiceId`/`speed` 一旦分配即锁定，后续章节不得覆盖。 */
data class CharacterInfo(
    val roleId: String,
    val name: String,
    val gender: Gender,
    val importance: Importance,
    val aliases: List<String> = emptyList(),
    val note: String = "",
    val category: String,
    val voiceId: String,
    val speed: Double? = null,
    val locked: Boolean = true,
)
