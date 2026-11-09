@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.estimatefaster.audioforge.feature.script

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.core.model.Emotion
import com.estimatefaster.audioforge.core.model.NARRATOR_ROLE_ID
import com.estimatefaster.audioforge.core.model.SegmentType
import com.estimatefaster.audioforge.ui.components.RoleAvatar
import com.estimatefaster.audioforge.ui.forge.ForgeCard
import com.estimatefaster.audioforge.ui.forge.ForgeChip
import com.estimatefaster.audioforge.ui.forge.ForgeEmpty
import com.estimatefaster.audioforge.ui.forge.ForgeError
import com.estimatefaster.audioforge.ui.forge.ForgeIcon
import com.estimatefaster.audioforge.ui.forge.ForgeIconAction
import com.estimatefaster.audioforge.ui.forge.ForgeIcons
import com.estimatefaster.audioforge.ui.forge.ForgeLoading
import com.estimatefaster.audioforge.ui.forge.ForgeSectionHeader
import com.estimatefaster.audioforge.ui.forge.ForgeTopBar
import com.estimatefaster.audioforge.ui.forge.ForgeWavyProgress
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing
import kotlinx.coroutines.launch

/** LazyColumn 中固定头部的 item 数：hero、角色筛选、搜索、段落标题。 */
private const val SCRIPT_HEADER_ITEMS = 4

/**
 * 剧本页（Forge Framework v1）。
 *
 * 有状态入口：收集 [ScriptViewModel.state] 后委托给无状态版本。
 * 播放/编辑/重生成都只是回调，页面不直接访问仓库或播放器。
 */
@Composable
fun ScriptScreen(
    viewModel: ScriptViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onParseChapter: (() -> Unit)? = null,
    onSegmentClick: (Int) -> Unit = {},
    onEditSegment: (Int) -> Unit = {},
    onRegenerateSegment: (Int) -> Unit = {},
    onPlaySegment: (Int) -> Unit = {},
    onPausePlayback: () -> Unit = {},
    onResumePlayback: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val followState by viewModel.followState.collectAsStateWithLifecycle()
    ScriptScreen(
        state = state,
        followState = followState,
        modifier = modifier,
        onBack = onBack,
        onQueryChange = viewModel::setQuery,
        onRoleFilterChange = viewModel::setRoleFilter,
        onClearFilters = viewModel::clearFilters,
        onRetry = viewModel::retry,
        onParseChapter = onParseChapter,
        onSegmentClick = onSegmentClick,
        onEditSegment = onEditSegment,
        onRegenerateSegment = onRegenerateSegment,
        onPlaySegment = { index ->
            // 由 ViewModel 决定真正要播哪一段（无音频段自动跳到下一段）。
            viewModel.playFrom(index)?.let(onPlaySegment)
        },
        onPlayPause = {
            when {
                state.playing -> onPausePlayback()
                state.playingSegmentIndex != null -> onResumePlayback()
                else -> state.segments.firstOrNull { it.hasAudio }?.index
                    ?.let { index -> viewModel.playFrom(index)?.let(onPlaySegment) }
            }
        },
        onPlayPrevious = { viewModel.playPrevious()?.let(onPlaySegment) },
        onPlayNext = { viewModel.playNext()?.let(onPlaySegment) },
        onContinuousChange = viewModel::setContinuous,
    )
}

/**
 * 无状态剧本页。
 *
 * 结构对齐 `docs/prototype/forge-framework.html` 的 Script：
 * topbar → 章节 hero（含生成进度）→ 角色 chips → 台词搜索 → 分段列表 → 迷你播放条。
 * 当前播放段高亮并自动滚动；筛选、搜索、连播、播放跟随状态机保持原语义。
 */
@Composable
fun ScriptScreen(
    state: ScriptUiState,
    modifier: Modifier = Modifier,
    followState: FollowState = FollowState(currentIndex = state.playingSegmentIndex),
    onBack: () -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onRoleFilterChange: (String?) -> Unit = {},
    onClearFilters: () -> Unit = {},
    onRetry: () -> Unit = {},
    onParseChapter: (() -> Unit)? = null,
    onSegmentClick: (Int) -> Unit = {},
    onEditSegment: (Int) -> Unit = {},
    onRegenerateSegment: (Int) -> Unit = {},
    onPlaySegment: (Int) -> Unit = {},
    onPlayPause: () -> Unit = {},
    onPlayPrevious: () -> Unit = {},
    onPlayNext: () -> Unit = {},
    onContinuousChange: (Boolean) -> Unit = {},
    playingSegmentIndex: Int? = state.playingSegmentIndex,
) {
    val loadError = state.loadError
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val scrollTarget = state.scrollTargetFor(playingSegmentIndex)

    // 播放段变化/重新出现在筛选结果里时，滚动到当前段；被筛掉时不强制跳转。
    LaunchedEffect(playingSegmentIndex, scrollTarget) {
        if (scrollTarget != null) {
            listState.animateScrollToItem(SCRIPT_HEADER_ITEMS + scrollTarget)
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ForgeTopBar(
                title = stringResource(R.string.ux2_script_title),
                subtitle = if (state.chapterTitle.isBlank()) {
                    null
                } else {
                    stringResource(
                        R.string.ux2_script_subtitle,
                        state.chapterTitle,
                        state.segments.size,
                    )
                },
                navigationIcon = ForgeIcons.Back,
                navigationDescription = stringResource(R.string.ux2_script_back),
                onNavigation = onBack,
                actions = {
                    if (state.segments.isNotEmpty()) {
                        ForgeIconAction(
                            icon = ForgeIcons.List,
                            contentDescription = stringResource(R.string.ux2_script_locate_current),
                            onClick = {
                                scope.launch {
                                    val target = state.scrollTargetFor(playingSegmentIndex)
                                    listState.animateScrollToItem(
                                        target?.let { SCRIPT_HEADER_ITEMS + it } ?: 0,
                                    )
                                }
                            },
                            container = false,
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (state.segments.isNotEmpty()) {
                ScriptMiniBar(
                    state = state,
                    followState = followState,
                    onPlayPause = onPlayPause,
                    onPlayPrevious = onPlayPrevious,
                    onPlayNext = onPlayNext,
                    onContinuousChange = onContinuousChange,
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.isInitialLoading -> ForgeLoading(
                    label = stringResource(R.string.ux2_script_loading),
                    modifier = Modifier.fillMaxSize(),
                )

                loadError != null -> ForgeError(
                    message = stringResource(R.string.ux2_script_load_error, loadError),
                    onRetry = onRetry,
                )

                state.isEmpty -> ForgeEmpty(
                    icon = ForgeIcons.FileText,
                    title = stringResource(R.string.ux2_script_empty_title),
                    description = stringResource(R.string.ux2_script_empty_description),
                    actionIcon = if (onParseChapter != null) ForgeIcons.Wand else null,
                    actionLabel = if (onParseChapter != null) {
                        stringResource(R.string.ux2_script_parse)
                    } else {
                        null
                    },
                    onAction = onParseChapter,
                )

                else -> ScriptContent(
                    state = state,
                    listState = listState,
                    playingSegmentIndex = playingSegmentIndex,
                    onQueryChange = onQueryChange,
                    onRoleFilterChange = onRoleFilterChange,
                    onClearFilters = onClearFilters,
                    onSegmentClick = onSegmentClick,
                    onEditSegment = onEditSegment,
                    onRegenerateSegment = onRegenerateSegment,
                    onPlaySegment = onPlaySegment,
                    onPlayPause = onPlayPause,
                )
            }
        }
    }
}

@Composable
private fun ScriptContent(
    state: ScriptUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    playingSegmentIndex: Int?,
    onQueryChange: (String) -> Unit,
    onRoleFilterChange: (String?) -> Unit,
    onClearFilters: () -> Unit,
    onSegmentClick: (Int) -> Unit,
    onEditSegment: (Int) -> Unit,
    onRegenerateSegment: (Int) -> Unit,
    onPlaySegment: (Int) -> Unit,
    onPlayPause: () -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = MaterialTheme.spacing.lg,
            end = MaterialTheme.spacing.lg,
            top = MaterialTheme.spacing.sm,
            bottom = MaterialTheme.spacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        item(key = "hero") {
            ScriptHero(state = state)
        }
        item(key = "role-filters") {
            ScriptRoleFilters(
                state = state,
                onRoleFilterChange = onRoleFilterChange,
            )
        }
        item(key = "search") {
            ScriptSearchField(
                state = state,
                onQueryChange = onQueryChange,
            )
        }
        item(key = "section") {
            ForgeSectionHeader(
                title = stringResource(R.string.ux2_script_section_title),
                subtitle = stringResource(
                    R.string.ux2_script_section_count,
                    state.visibleSegments.size,
                ),
                modifier = Modifier.padding(top = MaterialTheme.spacing.xs),
            )
        }

        if (state.isFilteredEmpty) {
            item(key = "filtered-empty") {
                ForgeEmpty(
                    icon = ForgeIcons.Search,
                    title = stringResource(R.string.ux2_script_filtered_title),
                    description = stringResource(R.string.ux2_script_filtered_description),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 320.dp),
                    actionIcon = ForgeIcons.Refresh,
                    actionLabel = stringResource(R.string.ux2_script_clear_filters),
                    onAction = onClearFilters,
                )
            }
        } else {
            items(
                items = state.visibleSegments,
                key = { segment -> segment.key },
                contentType = { segment -> segment.type.wire },
            ) { segment ->
                val isCurrent = state.isPlaying(segment, playingSegmentIndex)
                ScriptSegmentCard(
                    segment = segment,
                    isCurrent = isCurrent,
                    isPlaying = isCurrent && state.playing,
                    hasAudio = segment.hasAudio,
                    onSegmentClick = { onSegmentClick(segment.index) },
                    onEditSegment = { onEditSegment(segment.index) },
                    onRegenerateSegment = { onRegenerateSegment(segment.index) },
                    onPlayPause = onPlayPause,
                    onPlaySegment = { onPlaySegment(segment.index) },
                )
            }
        }

        item(key = "note") {
            ScriptNote()
        }
    }
}

@Composable
private fun ScriptHero(state: ScriptUiState) {
    val total = state.segments.size
    val generated = state.playableCount
    val progress = if (total == 0) 0f else generated.toFloat() / total.toFloat()

    ForgeCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
        ) {
            ForgeIcon(
                icon = ForgeIcons.BookOpen,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.ux2_script_eyebrow),
                style = MaterialTheme.typography.labelSmallEmphasized,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = state.chapterTitle.ifBlank {
                stringResource(R.string.ux2_script_title)
            },
            style = MaterialTheme.typography.headlineSmallEmphasized,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.ux2_script_summary, total, generated),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val progressDescription = stringResource(R.string.ux2_script_progress_description)
        ForgeWavyProgress(
            progress = progress,
            modifier = Modifier.semantics { contentDescription = progressDescription },
        )
        Text(
            text = stringResource(R.string.ux2_script_progress_caption, generated, total),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScriptRoleFilters(
    state: ScriptUiState,
    onRoleFilterChange: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
        Text(
            text = stringResource(R.string.ux2_script_filter_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            ForgeChip(
                label = stringResource(
                    R.string.ux2_script_filter_count,
                    stringResource(R.string.ux2_script_filter_all),
                    state.segments.size,
                ),
                selected = state.selectedRoleId == null,
                onClick = { onRoleFilterChange(null) },
                icon = ForgeIcons.Users,
            )
            state.characters.forEach { character ->
                ForgeChip(
                    label = stringResource(
                        R.string.ux2_script_filter_count,
                        characterName(character),
                        state.segments.count { segment -> segment.roleId == character.roleId },
                    ),
                    selected = state.selectedRoleId == character.roleId,
                    onClick = { onRoleFilterChange(character.roleId) },
                    icon = if (character.isNarrator) ForgeIcons.Mic else ForgeIcons.UserRound,
                )
            }
        }
    }
}

@Composable
private fun ScriptSearchField(
    state: ScriptUiState,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = state.query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        leadingIcon = {
            ForgeIcon(
                icon = ForgeIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = if (state.query.isNotEmpty()) {
            {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(48.dp),
                ) {
                    ForgeIcon(
                        icon = ForgeIcons.Close,
                        contentDescription = stringResource(R.string.ux2_script_search_clear),
                    )
                }
            }
        } else {
            null
        },
        placeholder = {
            Text(
                text = stringResource(R.string.ux2_script_search_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
    )
}

@Composable
private fun ScriptSegmentCard(
    segment: ScriptSegmentUi,
    isCurrent: Boolean,
    isPlaying: Boolean,
    hasAudio: Boolean,
    onSegmentClick: () -> Unit,
    onEditSegment: () -> Unit,
    onRegenerateSegment: () -> Unit,
    onPlayPause: () -> Unit,
    onPlaySegment: () -> Unit,
) {
    var menuExpanded by rememberSaveable(segment.key) { mutableStateOf(false) }
    val displayName = segmentName(segment)
    val playDescription = stringResource(
        if (isPlaying) R.string.ux2_script_segment_pause else R.string.ux2_script_segment_play,
        segment.number,
    )
    val editDescription = stringResource(R.string.ux2_script_segment_edit, segment.number)
    val moreDescription = stringResource(R.string.ux2_script_segment_more, segment.number)
    val statusText = when {
        segment.hasAudio -> stringResource(R.string.ux2_script_segment_has_audio)
        else -> stringResource(R.string.ux2_script_segment_no_audio)
    }

    ForgeCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { selected = isCurrent },
        onClick = onSegmentClick,
        containerColor = if (isCurrent) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        contentPadding = PaddingValues(MaterialTheme.spacing.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            SegmentNumberBadge(number = segment.number)
            RoleAvatar(name = displayName, size = 36.dp, decorative = true)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(emotionLabelRes(segment.emotion)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                ForgeIconAction(
                    icon = ForgeIcons.More,
                    contentDescription = moreDescription,
                    onClick = { menuExpanded = true },
                    container = false,
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ux2_script_segment_menu_edit)) },
                        onClick = {
                            menuExpanded = false
                            onEditSegment()
                        },
                        leadingIcon = {
                            ForgeIcon(ForgeIcons.Edit, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.ux2_script_segment_menu_regenerate))
                        },
                        onClick = {
                            menuExpanded = false
                            onRegenerateSegment()
                        },
                        leadingIcon = {
                            ForgeIcon(ForgeIcons.Refresh, contentDescription = null)
                        },
                    )
                }
            }
        }

        SegmentText(segment = segment)

        if (segment.hasError) {
            Text(
                text = segment.errorMessage?.let { message ->
                    stringResource(R.string.ux2_script_segment_error, message)
                } ?: stringResource(R.string.ux2_script_segment_error_generic),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs),
        ) {
            Text(
                text = stringResource(
                    R.string.ux2_script_segment_foot,
                    segment.number,
                    statusText,
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isCurrent) {
                CurrentSegmentTag()
            }
            ForgeIconAction(
                icon = if (isPlaying) ForgeIcons.Pause else ForgeIcons.Play,
                contentDescription = playDescription,
                onClick = {
                    if (hasAudio) {
                        if (isCurrent) onPlayPause() else onPlaySegment()
                    }
                },
                container = false,
                modifier = Modifier
                    .alpha(if (hasAudio) 1f else 0.4f)
                    .semantics { if (!hasAudio) disabled() },
            )
            ForgeIconAction(
                icon = ForgeIcons.Edit,
                contentDescription = editDescription,
                onClick = onEditSegment,
                container = false,
            )
        }
    }
}

@Composable
private fun SegmentText(segment: ScriptSegmentUi) {
    val quoteColor = MaterialTheme.colorScheme.onSurfaceVariant
    val body = buildAnnotatedString {
        if (segment.isNarration) {
            append(segment.text)
        } else {
            withStyle(SpanStyle(color = quoteColor)) { append("“") }
            append(segment.text)
            withStyle(SpanStyle(color = quoteColor)) { append("”") }
        }
    }
    Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun SegmentNumberBadge(number: Int) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = number.toString().padStart(2, '0'),
            modifier = Modifier.padding(
                horizontal = MaterialTheme.spacing.sm,
                vertical = MaterialTheme.spacing.xxs,
            ),
            style = MaterialTheme.typography.labelSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun CurrentSegmentTag() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ForgeIcon(
            icon = ForgeIcons.AudioLines,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.ux2_script_segment_current),
            style = MaterialTheme.typography.labelSmallEmphasized,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

@Composable
private fun ScriptNote() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        ForgeIcon(
            icon = ForgeIcons.Info,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.ux2_script_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScriptMiniBar(
    state: ScriptUiState,
    followState: FollowState,
    onPlayPause: () -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onContinuousChange: (Boolean) -> Unit,
) {
    val current = state.currentSegment
    val hasAudio = state.hasPlayableAudio
    val continuousLabel = stringResource(R.string.ux2_script_mini_continuous)
    val status = when {
        followState.nextChapterRequested ->
            stringResource(R.string.ux2_script_mini_next_chapter)

        followState.chapterFinished -> stringResource(R.string.ux2_script_mini_chapter_done)
        current != null -> stringResource(R.string.ux2_script_mini_now, current.number)
        !hasAudio -> stringResource(R.string.ux2_script_mini_no_audio)
        else -> stringResource(R.string.ux2_script_mini_idle)
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    horizontal = MaterialTheme.spacing.lg,
                    vertical = MaterialTheme.spacing.sm,
                ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = status,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                        style = MaterialTheme.typography.bodyMediumEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (current != null) {
                        Text(
                            text = stringResource(
                                R.string.ux2_script_mini_subtitle,
                                segmentName(current),
                                current.text,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
            ) {
                ForgeIconAction(
                    icon = ForgeIcons.SkipPrevious,
                    contentDescription = stringResource(R.string.ux2_script_mini_prev),
                    onClick = { if (hasAudio) onPlayPrevious() },
                    container = false,
                    modifier = Modifier
                        .alpha(if (hasAudio) 1f else 0.4f)
                        .semantics { if (!hasAudio) disabled() },
                )
                ForgeIconAction(
                    icon = if (state.playing) ForgeIcons.Pause else ForgeIcons.Play,
                    contentDescription = stringResource(
                        if (state.playing) {
                            R.string.ux2_script_mini_pause
                        } else {
                            R.string.ux2_script_mini_play
                        },
                    ),
                    onClick = { if (hasAudio) onPlayPause() },
                    container = true,
                    modifier = Modifier
                        .alpha(if (hasAudio) 1f else 0.4f)
                        .semantics { if (!hasAudio) disabled() },
                )
                ForgeIconAction(
                    icon = ForgeIcons.SkipNext,
                    contentDescription = stringResource(R.string.ux2_script_mini_next),
                    onClick = { if (hasAudio) onPlayNext() },
                    container = false,
                    modifier = Modifier
                        .alpha(if (hasAudio) 1f else 0.4f)
                        .semantics { if (!hasAudio) disabled() },
                )
                Spacer(Modifier.weight(1f))
                ForgeChip(
                    label = continuousLabel,
                    selected = state.continuousPlayback,
                    onClick = { if (hasAudio) onContinuousChange(!state.continuousPlayback) },
                    icon = ForgeIcons.Repeat,
                    modifier = Modifier.alpha(if (hasAudio) 1f else 0.5f),
                )
            }
            if (followState.skippedNoAudio && hasAudio) {
                Text(
                    text = stringResource(R.string.ux2_script_mini_skipped),
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun characterName(character: ScriptCharacterUi): String = when {
    character.name.isNotBlank() -> character.name
    character.isNarrator -> stringResource(R.string.ux2_script_narration)
    else -> stringResource(R.string.ux2_script_unknown_role)
}

@Composable
private fun segmentName(segment: ScriptSegmentUi): String = when {
    segment.roleName.isNotBlank() -> segment.roleName
    segment.roleId == NARRATOR_ROLE_ID -> stringResource(R.string.ux2_script_narration)
    else -> stringResource(R.string.ux2_script_unknown_role)
}

@StringRes
private fun emotionLabelRes(emotion: Emotion): Int = when (emotion) {
    Emotion.Neutral -> R.string.ux2_emotion_neutral
    Emotion.Happy -> R.string.ux2_emotion_happy
    Emotion.Sad -> R.string.ux2_emotion_sad
    Emotion.Angry -> R.string.ux2_emotion_angry
    Emotion.Anxious -> R.string.ux2_emotion_anxious
    Emotion.Excited -> R.string.ux2_emotion_excited
    Emotion.Surprised -> R.string.ux2_emotion_surprised
    Emotion.SoftTone -> R.string.ux2_emotion_soft_tone
    Emotion.Serious -> R.string.ux2_emotion_serious
    Emotion.Calm -> R.string.ux2_emotion_calm
    Emotion.Whispering -> R.string.ux2_emotion_whispering
}

// ---------------------------------------------------------------------------
// Preview：播放中（含当前段高亮）/ 空态（深色 200%）。数据只是固定夹具。
// ---------------------------------------------------------------------------

private fun previewSegment(
    id: Long,
    index: Int,
    type: SegmentType,
    roleId: String,
    roleName: String,
    emotion: Emotion,
    text: String,
): ScriptSegmentUi = ScriptSegmentUi(
    id = id,
    index = index,
    type = type,
    roleId = roleId,
    roleName = roleName,
    emotion = emotion,
    text = text,
    status = "done",
    hasAudio = true,
    errorMessage = null,
)

private fun previewScriptSegments(): List<ScriptSegmentUi> = listOf(
    previewSegment(
        id = 1,
        index = 0,
        type = SegmentType.Narration,
        roleId = NARRATOR_ROLE_ID,
        roleName = "",
        emotion = Emotion.Neutral,
        text = "山雨欲来，旧城的灯火在风里一盏盏暗下去。",
    ),
    previewSegment(
        id = 2,
        index = 1,
        type = SegmentType.Dialogue,
        roleId = "role_0001",
        roleName = "沈砚",
        emotion = Emotion.Serious,
        text = "这封信，你从哪里得来的？",
    ),
    previewSegment(
        id = 3,
        index = 2,
        type = SegmentType.Dialogue,
        roleId = "role_0002",
        roleName = "顾青",
        emotion = Emotion.Whispering,
        text = "渡口的老船夫交给我的。他说，写信的人已经等了三年。",
    ),
)

@Preview(name = "Script Forge", showBackground = true, backgroundColor = 0xFFF7F9F8)
@Composable
private fun ScriptForgePreview() {
    AudioForgeTheme(dynamicColor = false) {
        ScriptScreen(
            state = ScriptUiState(
                loading = false,
                chapterTitle = "第三章 长夜",
                segments = previewScriptSegments(),
                characters = listOf(
                    ScriptCharacterUi(roleId = NARRATOR_ROLE_ID, name = ""),
                    ScriptCharacterUi(roleId = "role_0001", name = "沈砚"),
                    ScriptCharacterUi(roleId = "role_0002", name = "顾青"),
                ),
                playingSegmentIndex = 1,
                continuousPlayback = true,
                playing = true,
            ),
            followState = FollowState(currentIndex = 1, shouldScroll = true),
        )
    }
}

@Preview(
    name = "Script Empty Dark 200%",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun ScriptEmptyPreview() {
    AudioForgeTheme(darkTheme = true, dynamicColor = false) {
        ScriptScreen(
            state = ScriptUiState(loading = false, chapterTitle = "第五章 入城"),
            onParseChapter = {},
        )
    }
}
