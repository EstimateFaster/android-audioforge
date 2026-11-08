package com.estimatefaster.audioforge.feature.script

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.core.model.Emotion
import com.estimatefaster.audioforge.core.model.NARRATOR_ROLE_ID
import com.estimatefaster.audioforge.core.model.SegmentType
import com.estimatefaster.audioforge.ui.components.EmptyState
import com.estimatefaster.audioforge.ui.components.ErrorState
import com.estimatefaster.audioforge.ui.components.RoleAvatar
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme

/**
 * 剧本页（P5-01 / 执行计划 §12.4 S06）。
 *
 * 有状态入口：收集 [ScriptViewModel.state] 后委托给无状态版本。
 * 播放/编辑/重生成都只是回调，页面不直接访问仓库或播放器。
 * 播放按钮先经过 [ScriptViewModel.playFrom]（跳过无音频段、更新跟随状态），
 * 再把解析出的整章 index 交给宿主 `onPlaySegment` 起播；自动前进由 ViewModel 的
 * `onPlaybackAdvanced` 回调通知宿主（或直接 collect [ScriptViewModel.followState]）。
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
 * - 顶部：返回 + 章节标题；
 * - 角色筛选（全部 + 人物 chips）+ 搜索台词；
 * - `LazyColumn` 稳定 key（片段主键/位置兜底），当前播放段高亮并自动滚动；
 * - 段落整行可点（编辑回调），长按/“更多”打开播放/编辑/重新生成菜单；
 * - 底部迷你播放条：播放/暂停、上一句/下一句、连续播放开关、跳过无音频提示；
 * - 空态区分“整章没有剧本”和“筛选后为空”，加载/错误复用 P4-02 组件。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.action_back))
                    }
                },
                title = {
                    val titleText = if (state.chapterTitle.isBlank()) {
                        stringResource(R.string.script_title)
                    } else {
                        state.chapterTitle
                    }
                    Text(
                        text = titleText,
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            if (state.segments.isNotEmpty()) {
                ScriptMiniPlayer(
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.segments.isNotEmpty()) {
                ScriptRoleFilterRow(
                    state = state,
                    onRoleFilterChange = onRoleFilterChange,
                )
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 4.dp),
                    label = { Text(stringResource(R.string.script_search_label)) },
                    placeholder = { Text(stringResource(R.string.script_search_placeholder)) },
                    singleLine = true,
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            TextButton(
                                onClick = { onQueryChange("") },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.script_search_clear))
                            }
                        }
                    },
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when {
                    state.isInitialLoading -> ScriptLoading(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                    )

                    loadError != null -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ErrorState(
                            message = stringResource(R.string.script_load_error, loadError),
                            onRetry = onRetry,
                        )
                    }

                    state.isEmpty -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            title = stringResource(R.string.script_empty_title),
                            description = stringResource(R.string.script_empty_description),
                            actionText = if (onParseChapter != null) {
                                stringResource(R.string.script_empty_action_parse)
                            } else {
                                null
                            },
                            onAction = onParseChapter,
                        )
                    }

                    state.isFilteredEmpty -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            title = stringResource(R.string.script_filtered_empty_title),
                            description = stringResource(R.string.script_filtered_empty_description),
                            actionText = stringResource(R.string.script_filtered_empty_action),
                            onAction = onClearFilters,
                        )
                    }

                    else -> ScriptSegmentList(
                        state = state,
                        playingSegmentIndex = playingSegmentIndex,
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
}

@Composable
private fun ScriptRoleFilterRow(
    state: ScriptUiState,
    onRoleFilterChange: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = state.selectedRoleId == null,
            onClick = { onRoleFilterChange(null) },
            label = { Text(stringResource(R.string.script_filter_all)) },
        )
        state.characters.forEach { character ->
            FilterChip(
                selected = state.selectedRoleId == character.roleId,
                onClick = { onRoleFilterChange(character.roleId) },
                label = { Text(characterName(character)) },
            )
        }
    }
}

@Composable
private fun ScriptSegmentList(
    state: ScriptUiState,
    playingSegmentIndex: Int?,
    onSegmentClick: (Int) -> Unit,
    onEditSegment: (Int) -> Unit,
    onRegenerateSegment: (Int) -> Unit,
    onPlaySegment: (Int) -> Unit,
    onPlayPause: () -> Unit,
) {
    val listState = rememberLazyListState()
    val scrollTarget = state.scrollTargetFor(playingSegmentIndex)

    // 播放段变化/重新出现在筛选结果里时，滚动到当前段；被筛掉时不强制跳转。
    LaunchedEffect(playingSegmentIndex, scrollTarget) {
        if (scrollTarget != null) {
            listState.animateScrollToItem(scrollTarget)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = state.visibleSegments,
            key = { segment -> segment.key },
            contentType = { segment -> segment.type.wire },
        ) { segment ->
            val isCurrent = state.isPlaying(segment, playingSegmentIndex)
            ScriptSegmentRow(
                segment = segment,
                isCurrent = isCurrent,
                isPlaying = isCurrent && state.playing,
                onSegmentClick = onSegmentClick,
                onEditSegment = onEditSegment,
                onRegenerateSegment = onRegenerateSegment,
                onPlaySegment = onPlaySegment,
                onPlayPause = onPlayPause,
            )
        }
    }
}

@Composable
private fun ScriptSegmentRow(
    segment: ScriptSegmentUi,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onSegmentClick: (Int) -> Unit,
    onEditSegment: (Int) -> Unit,
    onRegenerateSegment: (Int) -> Unit,
    onPlaySegment: (Int) -> Unit,
    onPlayPause: () -> Unit,
) {
    var menuExpanded by rememberSaveable(segment.key) { mutableStateOf(false) }

    val shape = MaterialTheme.shapes.medium
    val accent = if (segment.isNarration) {
        MaterialTheme.colorScheme.outline
    } else {
        MaterialTheme.colorScheme.primary
    }
    val container = if (isCurrent) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val displayName = segmentName(segment)
    val editClickLabel = stringResource(R.string.script_segment_click_edit)
    val moreClickLabel = stringResource(R.string.script_segment_long_click_more)
    val moreDescription = stringResource(
        R.string.script_segment_more_content_description,
        segment.number,
    )
    val playLabel = stringResource(
        if (isPlaying) R.string.script_segment_action_pause else R.string.script_segment_action_play,
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClickLabel = editClickLabel,
                onLongClickLabel = moreClickLabel,
                onLongClick = { menuExpanded = true },
                onClick = { onSegmentClick(segment.index) },
            )
            .semantics { selected = isCurrent },
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(
            modifier = Modifier
                .drawBehind {
                    drawRect(color = accent, size = Size(4.dp.toPx(), size.height))
                }
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            ScriptNumberBadge(number = segment.number)
            Spacer(Modifier.width(8.dp))
            // 名字在右侧可见，头像只做装饰，避免 TalkBack 把同一人物读两遍。
            RoleAvatar(name = displayName, size = 40.dp, decorative = true)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(
                        onClick = {
                            // 当前段：播放中→暂停，已暂停→恢复；其他段：从该段起播。
                            if (isCurrent) onPlayPause() else onPlaySegment(segment.index)
                        },
                        enabled = segment.hasAudio,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(playLabel)
                    }
                    Box {
                        TextButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics { contentDescription = moreDescription },
                        ) {
                            Text(stringResource(R.string.action_more))
                        }
                        ScriptSegmentMenu(
                            expanded = menuExpanded,
                            hasAudio = segment.hasAudio,
                            onDismiss = { menuExpanded = false },
                            onPlay = {
                                menuExpanded = false
                                onPlaySegment(segment.index)
                            },
                            onEdit = {
                                menuExpanded = false
                                onEditSegment(segment.index)
                            },
                            onRegenerate = {
                                menuExpanded = false
                                onRegenerateSegment(segment.index)
                            },
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(segment.type.labelRes()),
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                    EmotionTag(emotion = segment.emotion)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = segment.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
                val errorMessage = segment.errorMessage
                if (errorMessage != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.script_segment_error, errorMessage),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (!segment.hasAudio) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.script_segment_no_audio_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScriptSegmentMenu(
    expanded: Boolean,
    hasAudio: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.script_segment_action_play)) },
            onClick = onPlay,
            enabled = hasAudio,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.script_segment_action_edit)) },
            onClick = onEdit,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.script_segment_action_regenerate)) },
            onClick = onRegenerate,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScriptMiniPlayer(
    state: ScriptUiState,
    followState: FollowState,
    onPlayPause: () -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onContinuousChange: (Boolean) -> Unit,
) {
    val current = state.currentSegment
    val hasAudio = state.hasPlayableAudio
    val continuousLabel = stringResource(R.string.script_playback_continuous)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            followState.nextChapterRequested -> stringResource(
                                R.string.script_playback_chapter_done_next,
                            )

                            followState.chapterFinished -> stringResource(
                                R.string.script_playback_chapter_done,
                            )

                            current != null -> stringResource(
                                R.string.script_playback_now_playing,
                                current.number,
                            )

                            !hasAudio -> stringResource(R.string.script_playback_no_audio)
                            else -> stringResource(R.string.script_playback_idle)
                        },
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (current != null) {
                        Text(
                            text = stringResource(
                                R.string.script_playback_subtitle,
                                segmentName(current),
                                current.text,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = continuousLabel,
                    // 开关本身已有 contentDescription；可视化标签不重复进入读屏。
                    modifier = Modifier.clearAndSetSemantics { },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = state.continuousPlayback,
                    onCheckedChange = onContinuousChange,
                    enabled = hasAudio,
                    modifier = Modifier.semantics { contentDescription = continuousLabel },
                )
            }
            if (followState.skippedNoAudio && hasAudio) {
                Text(
                    text = stringResource(R.string.script_playback_skipped_no_audio),
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = onPlayPrevious,
                    enabled = hasAudio,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.script_playback_prev))
                }
                TextButton(
                    onClick = onPlayPause,
                    enabled = hasAudio,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(
                            if (state.playing) {
                                R.string.script_playback_pause
                            } else {
                                R.string.script_playback_play
                            },
                        ),
                    )
                }
                TextButton(
                    onClick = onPlayNext,
                    enabled = hasAudio,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.script_playback_next))
                }
            }
        }
    }
}

@Composable
private fun ScriptNumberBadge(number: Int) {
    Box(
        modifier = Modifier
            .sizeIn(minWidth = 32.dp, minHeight = 32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun EmotionTag(emotion: Emotion) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(emotion.labelRes()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
        )
    }
}

@Composable
private fun ScriptLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.script_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun characterName(character: ScriptCharacterUi): String =
    if (character.name.isNotBlank()) {
        character.name
    } else if (character.isNarrator) {
        stringResource(R.string.script_type_narration)
    } else {
        stringResource(R.string.script_role_unknown)
    }

@Composable
private fun segmentName(segment: ScriptSegmentUi): String =
    if (segment.roleName.isNotBlank()) {
        segment.roleName
    } else if (segment.roleId == NARRATOR_ROLE_ID) {
        stringResource(R.string.script_type_narration)
    } else {
        stringResource(R.string.script_role_unknown)
    }

@StringRes
private fun SegmentType.labelRes(): Int = when (this) {
    SegmentType.Dialogue -> R.string.script_type_dialogue
    SegmentType.Narration -> R.string.script_type_narration
}

@StringRes
private fun Emotion.labelRes(): Int = when (this) {
    Emotion.Neutral -> R.string.script_emotion_neutral
    Emotion.Happy -> R.string.script_emotion_happy
    Emotion.Sad -> R.string.script_emotion_sad
    Emotion.Angry -> R.string.script_emotion_angry
    Emotion.Anxious -> R.string.script_emotion_anxious
    Emotion.Excited -> R.string.script_emotion_excited
    Emotion.Surprised -> R.string.script_emotion_surprised
    Emotion.SoftTone -> R.string.script_emotion_soft_tone
    Emotion.Serious -> R.string.script_emotion_serious
    Emotion.Calm -> R.string.script_emotion_calm
    Emotion.Whispering -> R.string.script_emotion_whispering
}

// ---------------------------------------------------------------------------
// Preview：播放中（连播开）/ 播放中（连播关）/ 筛选空结果（深色 200%）/ 整章空态。
// 演示文本只是预览数据；所有 UI 文案来自 strings.xml。
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
    status = "pending",
    hasAudio = true,
    errorMessage = null,
)

private fun previewCharacters(): List<ScriptCharacterUi> = listOf(
    ScriptCharacterUi(roleId = "role_0001", name = "沈砚"),
    ScriptCharacterUi(roleId = "role_0002", name = "顾青"),
)

private fun previewSegments(): List<ScriptSegmentUi> = listOf(
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
    previewSegment(
        id = 4,
        index = 3,
        type = SegmentType.Dialogue,
        roleId = "role_0001",
        roleName = "沈砚",
        emotion = Emotion.Sad,
        text = "三年……原来他一直没有离开。",
    ),
)

@Preview(name = "Script Playing", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun ScriptPlayingPreview() {
    AudioForgeTheme(dynamicColor = false) {
        ScriptScreen(
            state = ScriptUiState(
                loading = false,
                chapterTitle = "第三章 旧信",
                segments = previewSegments(),
                characters = previewCharacters(),
                playingSegmentIndex = 1,
                continuousPlayback = true,
                playing = true,
            ),
            followState = FollowState(currentIndex = 1, shouldScroll = true),
        )
    }
}

@Preview(name = "Script Continuous Off", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun ScriptContinuousOffPreview() {
    AudioForgeTheme(dynamicColor = false) {
        ScriptScreen(
            state = ScriptUiState(
                loading = false,
                chapterTitle = "第三章 旧信",
                segments = previewSegments(),
                characters = previewCharacters(),
                playingSegmentIndex = 2,
                continuousPlayback = false,
                playing = true,
            ),
            followState = FollowState(currentIndex = 2, shouldScroll = true),
        )
    }
}

@Preview(
    name = "Script Filtered Empty Dark 200%",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun ScriptFilteredEmptyPreview() {
    AudioForgeTheme(darkTheme = true, dynamicColor = false) {
        ScriptScreen(
            state = ScriptUiState(
                loading = false,
                chapterTitle = "第三章 旧信",
                segments = previewSegments(),
                characters = previewCharacters(),
                selectedRoleId = "role_0002",
                query = "不存在的台词",
            ),
        )
    }
}

@Preview(name = "Script Empty", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun ScriptEmptyPreview() {
    AudioForgeTheme(dynamicColor = false) {
        ScriptScreen(
            state = ScriptUiState(
                loading = false,
                chapterTitle = "第五章 入城",
            ),
            onParseChapter = {},
        )
    }
}
