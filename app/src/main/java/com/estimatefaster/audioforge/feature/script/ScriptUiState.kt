package com.estimatefaster.audioforge.feature.script

import com.estimatefaster.audioforge.core.model.Emotion
import com.estimatefaster.audioforge.core.model.NARRATOR_ROLE_ID
import com.estimatefaster.audioforge.core.model.SegmentType
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.data.db.SegmentEntity

/**
 * 剧本页单段的不可变投影。
 *
 * 所有展示字段在 ViewModel 收到数据库 Flow 时一次算好（类型/情绪枚举、角色名、
 * 是否可用音频、错误、稳定 key），`LazyColumn` 的 item 内不再查库或解析 wire 值。
 * 2000 段时滚动只做渲染。
 *
 * [index] 是该段在整章片段列表中的 0 基位置，与筛选无关；播放高亮/自动滚动都用它
 * 作为唯一标识，筛选变化不会改变含义。[key] 优先用 Room 主键，预览/测试数据没有
 * 主键时退化为 `-(index + 1)`，仍保证同一列表内稳定且唯一。
 */
data class ScriptSegmentUi(
    val id: Long,
    val index: Int,
    val type: SegmentType,
    val roleId: String,
    val roleName: String,
    val emotion: Emotion,
    val text: String,
    val status: String,
    val hasAudio: Boolean,
    val errorMessage: String?,
) {
    /** 展示序号（1 起）。 */
    val number: Int
        get() = index + 1

    /** `LazyColumn` 稳定 key：主键优先，其次用位置兜底。 */
    val key: Long
        get() = if (id > 0L) id else -(index.toLong() + 1L)

    val isNarration: Boolean
        get() = type == SegmentType.Narration

    val hasError: Boolean
        get() = errorMessage != null || status.isWireStatus("error")

    companion object {
        /** 把一条片段映射成 UI 行；[roleNames] 是本次章节角色的 roleId → 名字快照。 */
        fun from(
            entity: SegmentEntity,
            position: Int,
            roleNames: Map<String, String>,
        ): ScriptSegmentUi {
            require(position >= 0) { "position 不能为负：$position" }
            val roleId = entity.roleId.trim()
            return ScriptSegmentUi(
                id = entity.id,
                index = position,
                type = SegmentType.fromWire(entity.type),
                roleId = roleId,
                roleName = roleNames[roleId].orEmpty().trim(),
                emotion = Emotion.fromWire(entity.emotion),
                text = entity.text,
                status = entity.status.trim(),
                hasAudio = !entity.audioRelPath.isNullOrBlank(),
                errorMessage = entity.errorMsg?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

/**
 * 角色筛选 chip 的投影。
 *
 * [name] 可能为空（旁白/未注册角色）：UI 用 `strings.xml` 里的兜底文案显示，
 * 保证纯 JVM 状态层不出现硬编码中文。
 */
data class ScriptCharacterUi(
    val roleId: String,
    val name: String,
) {
    val key: String
        get() = roleId

    val isNarrator: Boolean
        get() = roleId == NARRATOR_ROLE_ID
}

/**
 * 剧本页状态。
 *
 * [segments] 是整章（未筛选）的投影；[visibleSegments] 在构造时按角色/关键词一次
 * 过滤好，避免 Composable 每帧重复过滤。筛选是纯内存操作，不重新订阅数据库。
 *
 * [playingSegmentIndex] 是整章 0 基位置（不是筛选后的行号）。它可能被筛掉，
 * 此时 [scrollTargetFor] 返回 null，不强制滚动。
 *
 * [continuousPlayback]/[playing] 由 P5-03 的
 * [PlaybackFollowController][FollowState] 同步，只影响迷你播放条与行内按钮，
 * 不参与筛选。
 */
data class ScriptUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val chapterTitle: String = "",
    val segments: List<ScriptSegmentUi> = emptyList(),
    val characters: List<ScriptCharacterUi> = emptyList(),
    val selectedRoleId: String? = null,
    val query: String = "",
    val playingSegmentIndex: Int? = null,
    val continuousPlayback: Boolean = true,
    val playing: Boolean = false,
) {
    /** 已按角色/关键词过滤后的行；构造时预计算，item 内不再过滤。 */
    val visibleSegments: List<ScriptSegmentUi> =
        filterScriptSegments(segments, selectedRoleId, query)

    /** 本章有音频的段数；迷你播放条据此决定控制按钮是否可用。 */
    val playableCount: Int = segments.count { it.hasAudio }

    /** 本章是否至少有一段可播放音频。 */
    val hasPlayableAudio: Boolean
        get() = playableCount > 0

    /** 首屏加载中：显示居中加载而不是空态。 */
    val isInitialLoading: Boolean
        get() = loading && segments.isEmpty() && loadError == null

    /** 整章还没有片段（未解析，或解析结果为空）。 */
    val isEmpty: Boolean
        get() = !loading && loadError == null && segments.isEmpty()

    /** 有片段，但当前角色/关键词组合没有命中。 */
    val isFilteredEmpty: Boolean
        get() = !loading && loadError == null && segments.isNotEmpty() && visibleSegments.isEmpty()

    val hasActiveFilters: Boolean
        get() = selectedRoleId != null || query.isNotBlank()

    /** 当前播放段（按整章位置查）；被筛掉或越界时为 null。 */
    val playingSegment: ScriptSegmentUi?
        get() = playingSegmentIndex?.let { index ->
            visibleSegments.firstOrNull { it.index == index }
        }

    /** 当前播放段在**整章**里的投影；即使被筛选掉也能给迷你播放条显示标题。 */
    val currentSegment: ScriptSegmentUi?
        get() = playingSegmentIndex?.let { index ->
            segments.firstOrNull { it.index == index }
        }

    /**
     * 自动滚动目标：播放段在**筛选后列表**中的行号；不在列表里返回 null。
     * 这是 [androidx.compose.foundation.lazy.LazyListState.animateScrollToItem] 的入参。
     */
    fun scrollTargetFor(index: Int? = playingSegmentIndex): Int? {
        if (index == null || index < 0) return null
        return visibleSegments
            .indexOfFirst { it.index == index }
            .takeIf { it >= 0 }
    }

    /** 当前行是否正在播放。 */
    fun isPlaying(segment: ScriptSegmentUi, index: Int? = playingSegmentIndex): Boolean =
        index != null && segment.index == index
}

/** 把整章实体映射成 UI 行，角色名来自本次快照。 */
fun mapScriptSegments(
    entities: List<SegmentEntity>,
    characters: List<CharacterEntity>,
): List<ScriptSegmentUi> {
    if (entities.isEmpty()) return emptyList()
    val roleNames = characters
        .asSequence()
        .filter { it.roleId.isNotBlank() }
        .associate { it.roleId to it.name.trim() }
    return entities.mapIndexed { position, entity ->
        ScriptSegmentUi.from(entity, position, roleNames)
    }
}

/**
 * 生成角色 chips：项目内全部角色按数据库顺序，旁白只在有旁白片段且未注册为角色时
 * 补在首位。旁白名字留空，由 UI 统一显示“旁白”。
 */
fun buildScriptCharacters(
    characters: List<CharacterEntity>,
    segments: List<ScriptSegmentUi>,
): List<ScriptCharacterUi> {
    val known = characters
        .asSequence()
        .filter { it.roleId.isNotBlank() }
        .distinctBy { it.roleId }
        .map { ScriptCharacterUi(roleId = it.roleId, name = it.name.trim()) }
        .toList()
    val hasNarratorCharacter = known.any { it.roleId == NARRATOR_ROLE_ID }
    val needsNarrator = !hasNarratorCharacter && segments.any { it.roleId == NARRATOR_ROLE_ID }
    return if (needsNarrator) {
        listOf(ScriptCharacterUi(roleId = NARRATOR_ROLE_ID, name = "")) + known
    } else {
        known
    }
}

/**
 * 角色 + 台词关键词过滤（纯函数）。
 *
 * - [roleId] 为 null/空白表示“全部”；
 * - [query] trim 后忽略大小写匹配片段文本，空查询匹配全部；
 * - 两个条件同时生效（AND）。
 */
fun filterScriptSegments(
    segments: List<ScriptSegmentUi>,
    roleId: String?,
    query: String,
): List<ScriptSegmentUi> {
    val role = roleId?.trim()?.takeIf { it.isNotEmpty() }
    val keyword = query.trim()
    if (role == null && keyword.isEmpty()) return segments
    return segments.filter { segment ->
        segment.matches(role, keyword)
    }
}

/** 单段是否命中角色/关键词条件；[keyword] 应已 trim。 */
internal fun ScriptSegmentUi.matches(roleId: String?, keyword: String): Boolean {
    if (roleId != null && this.roleId != roleId) return false
    if (keyword.isEmpty()) return true
    return text.contains(keyword, ignoreCase = true)
}

/** wire 状态大小写/空白不敏感；未知值按“不匹配”处理。 */
private fun String.isWireStatus(expected: String): Boolean =
    trim().equals(expected, ignoreCase = true)
