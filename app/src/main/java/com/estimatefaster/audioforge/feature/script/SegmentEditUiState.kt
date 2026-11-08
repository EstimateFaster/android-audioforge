package com.estimatefaster.audioforge.feature.script

import com.estimatefaster.audioforge.core.model.Emotion
import com.estimatefaster.audioforge.core.model.NARRATOR_ROLE_ID
import com.estimatefaster.audioforge.core.model.SegmentType
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.data.db.SegmentEntity

/**
 * 片段编辑 Sheet 的角色下拉项。
 *
 * 旁白始终排在第一项且 [name] 为空：UI 用 `strings.xml` 的“旁白”兜底，
 * 纯 JVM 状态层不出现硬编码中文。角色名可能为空（数据异常），UI 同样兜底。
 */
data class SegmentRoleOption(
    val roleId: String,
    val name: String,
) {
    val isNarrator: Boolean
        get() = roleId == NARRATOR_ROLE_ID
}

/**
 * 片段编辑的校验错误（P5-02 / 执行计划 §12.4 S07）。
 *
 * 只放类型、不拼中文：文案由 `SegmentEditSheet` 从 `strings.xml` 取，
 * 状态与 ViewModel 保持纯 JVM 可测。
 */
enum class SegmentEditError {
    /** 文本 trim 后为空。 */
    EmptyText,

    /** 文本原始长度超过 [SegmentEditUiState.MAX_TEXT_LENGTH]。 */
    TextTooLong,

    /** 类型是旁白，但角色不是 narrator。 */
    NarrationRequiresNarrator,

    /** 类型是对白，但角色为空（或误选旁白）。 */
    DialogueRequiresRole,

    /** 对白角色不在本书角色列表里。 */
    UnknownRole,
}

/**
 * 片段编辑 Sheet 的不可变 UI 状态（P5-02）。
 *
 * 状态同时保存“当前草稿”和“原值”：
 * - `type/roleId/emotion/text` 是正在编辑的草稿；
 * - `originalType/originalRoleId/originalEmotion/originalText` 是打开 Sheet 时的原值，
 *   “重置”动作用它们恢复，[isDirty] 用它们判断是否有未保存修改；
 * - 保存成功后 ViewModel 会把原值同步为刚写入的值，因此 Sheet 保持打开时也恢复为
 *   “干净”状态，不会误提示还有未保存修改。
 *
 * 校验是纯派生值：文本 trim 后为空报空文本，原始长度超过 [MAX_TEXT_LENGTH] 报超长
 * （与输入框计数器一致）；旁白必须 `narrator`；对白必须是本书角色（旁白不能当对白
 * 角色）；对白角色不在 [roles] 里时报 [SegmentEditError.UnknownRole]。
 *
 * 角色下拉候选由 [buildSegmentRoleOptions] 生成：旁白 + 本书角色（去重、旁白优先）。
 * 对白不展示旁白，旁白只展示旁白，避免选出类型与角色矛盾的组合。
 */
data class SegmentEditUiState(
    val segmentId: Long,
    val segmentNumber: Int,
    val type: SegmentType,
    val roleId: String,
    val emotion: Emotion,
    val text: String,
    val roles: List<SegmentRoleOption> = listOf(SegmentRoleOption(NARRATOR_ROLE_ID, "")),
    val originalType: SegmentType = type,
    val originalRoleId: String = roleId,
    val originalEmotion: Emotion = emotion,
    val originalText: String = text,
    val saving: Boolean = false,
    val saveError: String? = null,
) {

    val isNarration: Boolean
        get() = type == SegmentType.Narration

    /** 输入框计数器使用原始长度；空文本校验使用 trim 后的文本。 */
    val characterCount: Int
        get() = text.length

    /** 与打开 Sheet 时的原值相比是否有未保存修改。 */
    val isDirty: Boolean
        get() = type != originalType ||
            roleId != originalRoleId ||
            emotion != originalEmotion ||
            text != originalText

    /** 当前草稿的全部阻塞项；空列表表示可以保存。 */
    val validationErrors: List<SegmentEditError>
        get() {
            val errors = mutableListOf<SegmentEditError>()
            if (text.trim().isEmpty()) errors += SegmentEditError.EmptyText
            if (text.length > MAX_TEXT_LENGTH) errors += SegmentEditError.TextTooLong

            val normalizedRole = roleId.trim()
            if (type == SegmentType.Narration) {
                if (normalizedRole != NARRATOR_ROLE_ID) {
                    errors += SegmentEditError.NarrationRequiresNarrator
                }
            } else {
                when {
                    normalizedRole.isEmpty() || normalizedRole == NARRATOR_ROLE_ID ->
                        errors += SegmentEditError.DialogueRequiresRole

                    roles.none { it.roleId == normalizedRole && !it.isNarrator } ->
                        errors += SegmentEditError.UnknownRole
                }
            }
            return errors
        }

    /** 保存按钮是否可用：校验通过且没有在途保存。 */
    val canSave: Boolean
        get() = validationErrors.isEmpty() && !saving

    /** 当前类型下可选的角色；旁白只给旁白，对白只给本书角色。 */
    val roleOptions: List<SegmentRoleOption>
        get() = if (isNarration) {
            roles.filter { it.isNarrator }
        } else {
            roles.filterNot { it.isNarrator }
        }

    /** 当前 roleId 对应的下拉项；未知角色返回 null，由 UI 显示兜底文案。 */
    val selectedRole: SegmentRoleOption?
        get() {
            val normalizedRole = roleId.trim()
            return roles.firstOrNull { it.roleId == normalizedRole }
        }

    companion object {
        /** 文本上限（原始字符数）；与 §12.4 S07 一致。 */
        const val MAX_TEXT_LENGTH: Int = 2000

        /**
         * 从 Room 实体 + 本书角色构造初始状态。
         *
         * 旁白实体即使脏数据里带了别的角色，也收敛为 `narrator`，避免打开 Sheet
         * 就处于“永远无法保存”的状态；对白角色保持原值，未知角色交给校验拦截，
         * 让用户主动重选。
         */
        fun from(
            segment: SegmentEntity,
            characters: List<CharacterEntity>,
        ): SegmentEditUiState {
            val type = SegmentType.fromWire(segment.type)
            val roleId = if (type == SegmentType.Narration) {
                NARRATOR_ROLE_ID
            } else {
                segment.roleId.trim()
            }
            val emotion = Emotion.fromWire(segment.emotion)
            val text = segment.text
            return SegmentEditUiState(
                segmentId = segment.id,
                segmentNumber = segment.seq + 1,
                type = type,
                roleId = roleId,
                emotion = emotion,
                text = text,
                roles = buildSegmentRoleOptions(characters),
                originalType = type,
                originalRoleId = roleId,
                originalEmotion = emotion,
                originalText = text,
            )
        }
    }
}

/**
 * 角色下拉候选：`narrator` 固定第一，其后是本书角色。
 *
 * 按 roleId 去重（保留数据库顺序的第一个），并过滤空 roleId；旁白角色即使被
 * 注册成普通角色也不会重复出现。
 */
fun buildSegmentRoleOptions(characters: List<CharacterEntity>): List<SegmentRoleOption> =
    buildList {
        add(SegmentRoleOption(roleId = NARRATOR_ROLE_ID, name = ""))
        val seen = mutableSetOf(NARRATOR_ROLE_ID)
        characters.forEach { character ->
            val roleId = character.roleId.trim()
            if (roleId.isEmpty() || !seen.add(roleId)) return@forEach
            add(SegmentRoleOption(roleId = roleId, name = character.name.trim()))
        }
    }
