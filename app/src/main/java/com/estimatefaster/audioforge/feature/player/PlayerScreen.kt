@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.estimatefaster.audioforge.feature.player

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.core.model.NARRATOR_ROLE_ID
import com.estimatefaster.audioforge.ui.components.EmptyState
import com.estimatefaster.audioforge.ui.components.ErrorState
import com.estimatefaster.audioforge.ui.components.ExpressiveCard
import com.estimatefaster.audioforge.ui.components.ExpressiveChip
import com.estimatefaster.audioforge.ui.components.ExpressiveTonalButton
import com.estimatefaster.audioforge.ui.components.ExpressiveWavyProgress
import com.estimatefaster.audioforge.ui.components.LoadingState
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing

/**
 * 播放器页（P5-07 / 执行计划 §15 P5-07）。
 *
 * 有状态入口：收集 [PlayerViewModel.state] 后委托给无状态版本。页面不直接访问播放器、
 * 仓库或数据库；播放/暂停/seek/切章/切句/倍速/睡眠定时都只是 ViewModel 回调。
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PlayerScreen(
        state = state,
        modifier = modifier,
        onBack = onBack,
        onPlayPause = viewModel::togglePlayPause,
        onSeek = viewModel::seekTo,
        onPreviousChapter = viewModel::previousChapter,
        onNextChapter = viewModel::nextChapter,
        onPreviousSegment = viewModel::previousSegment,
        onNextSegment = viewModel::nextSegment,
        onSpeedChange = viewModel::setSpeed,
        onSleepTimerChange = viewModel::setSleepTimer,
        onChapterClick = viewModel::selectChapter,
        onToggleChapters = viewModel::toggleChapterList,
        onRetry = viewModel::retry,
    )
}

/**
 * 无状态播放器页。
 *
 * 布局：
 * - 顶部：返回 + 书名；
 * - MaterialShapes 大封面（书名首字 + 主题渐变）、书名、章节与句序；
 * - 当前段文本（角色名 + 台词）；
 * - Wavy 进度条 + 当前/总时长；
 * - 大尺寸播放/暂停 FAB，上一章/下一章 IconButton，上一句/下一句；
 * - 倍速 chips、睡眠定时 chips（选中后显示剩余）；
 * - 底部可折叠章节卡，当前章高亮，点击直接跳转；
 * - 加载/错误/无音频空态复用 P4-02 组件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onPlayPause: () -> Unit = {},
    onSeek: (Float) -> Unit = {},
    onPreviousChapter: () -> Unit = {},
    onNextChapter: () -> Unit = {},
    onPreviousSegment: () -> Unit = {},
    onNextSegment: () -> Unit = {},
    onSpeedChange: (PlayerSpeed) -> Unit = {},
    onSleepTimerChange: (SleepTimerOption) -> Unit = {},
    onChapterClick: (Long) -> Unit = {},
    onToggleChapters: () -> Unit = {},
    onRetry: () -> Unit = {},
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
                        Text(
                            text = stringResource(R.string.action_back),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                },
                title = {
                    val bookTitle = state.bookTitle.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.player_book_untitled)
                    Text(
                        text = bookTitle,
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        when {
            state.isInitialLoading -> LoadingState(
                message = stringResource(R.string.player_loading),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )

            loadError != null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = MaterialTheme.spacing.lg),
                contentAlignment = Alignment.Center,
            ) {
                ErrorState(
                    message = stringResource(R.string.player_load_error, loadError),
                    onRetry = onRetry,
                )
            }

            state.isEmpty -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = MaterialTheme.spacing.lg),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    title = stringResource(R.string.player_empty_title),
                    description = stringResource(R.string.player_empty_description),
                    actionText = stringResource(R.string.player_empty_action),
                    onAction = onBack,
                )
            }

            else -> PlayerReadyContent(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onPlayPause = onPlayPause,
                onSeek = onSeek,
                onPreviousChapter = onPreviousChapter,
                onNextChapter = onNextChapter,
                onPreviousSegment = onPreviousSegment,
                onNextSegment = onNextSegment,
                onSpeedChange = onSpeedChange,
                onSleepTimerChange = onSleepTimerChange,
                onChapterClick = onChapterClick,
                onToggleChapters = onToggleChapters,
            )
        }
    }
}

@Composable
private fun PlayerReadyContent(
    state: PlayerUiState,
    modifier: Modifier,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onPreviousSegment: () -> Unit,
    onNextSegment: () -> Unit,
    onSpeedChange: (PlayerSpeed) -> Unit,
    onSleepTimerChange: (SleepTimerOption) -> Unit,
    onChapterClick: (Long) -> Unit,
    onToggleChapters: () -> Unit,
) {
    Column(modifier = modifier) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = MaterialTheme.spacing.lg,
                    vertical = MaterialTheme.spacing.md,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg),
        ) {
            PlayerCover(bookTitle = state.bookTitle)
            PlayerTitleBlock(state = state)
            PlayerSegmentCard(state = state)
            PlayerProgress(state = state, onSeek = onSeek)
            PlayerTransport(
                state = state,
                onPlayPause = onPlayPause,
                onPreviousChapter = onPreviousChapter,
                onNextChapter = onNextChapter,
            )
            PlayerSegmentNav(
                state = state,
                onPreviousSegment = onPreviousSegment,
                onNextSegment = onNextSegment,
            )
            PlayerSpeedSelector(state = state, onSpeedChange = onSpeedChange)
            PlayerSleepSelector(state = state, onSleepTimerChange = onSleepTimerChange)
            PlayerChapterHeaderCard(state = state, onToggleChapters = onToggleChapters)
        }

        if (state.chaptersExpanded) {
            ExpressiveCard(
                modifier = Modifier.padding(
                    start = MaterialTheme.spacing.lg,
                    end = MaterialTheme.spacing.lg,
                    top = MaterialTheme.spacing.sm,
                    bottom = MaterialTheme.spacing.md,
                ),
                contentPadding = PaddingValues(MaterialTheme.spacing.sm),
            ) {
                PlayerChapterList(state = state, onChapterClick = onChapterClick)
            }
        }
    }
}

@Composable
private fun PlayerCover(bookTitle: String) {
    val coverShape = MaterialShapes.Cookie9Sided.toShape(startAngle = 12)
    val gradient = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.tertiaryContainer,
        ),
    )
    val trimmedTitle = bookTitle.trim()
    val letter = if (trimmedTitle.isEmpty()) {
        stringResource(R.string.player_cover_fallback)
    } else {
        trimmedTitle.substring(0, 1)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth(0.72f)
            .widthIn(max = 280.dp)
            .aspectRatio(1.05f)
            .clip(coverShape)
            .background(gradient)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun PlayerTitleBlock(state: PlayerUiState) {
    val bookTitle = state.bookTitle.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.player_book_untitled)
    val chapterTitle = state.currentChapter?.title?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.player_chapter_untitled)
    val playable = state.currentChapter?.playableSegments.orEmpty()
    val position = playable.indexOfFirst { it.index == state.currentSegmentIndex }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs),
    ) {
        Text(
            text = bookTitle,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmallEmphasized,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = chapterTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (position >= 0 && playable.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.player_segment_indicator,
                    position + 1,
                    playable.size,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlayerSegmentCard(state: PlayerUiState) {
    val segment = state.currentSegment
    val fallbackRole = if (segment?.roleId == NARRATOR_ROLE_ID) {
        stringResource(R.string.script_type_narration)
    } else {
        stringResource(R.string.script_role_unknown)
    }
    val roleName = segment?.roleName?.takeIf { it.isNotBlank() } ?: fallbackRole
    val text = segment?.text?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.player_no_segment)
    ExpressiveCard(
        contentPadding = PaddingValues(
            horizontal = MaterialTheme.spacing.lg,
            vertical = MaterialTheme.spacing.md,
        ),
    ) {
        Text(
            text = roleName,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelLargeEmphasized,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PlayerProgress(
    state: PlayerUiState,
    onSeek: (Float) -> Unit,
) {
    // 拖动过程中用本地值渲染，松手才下发 seek；切段后自动清空。
    var dragFraction by remember(state.currentChapterIndex, state.currentSegmentIndex) {
        mutableStateOf<Float?>(null)
    }
    val displayedFraction = dragFraction ?: state.progress
    val displayedPosition = dragFraction
        ?.let { fraction -> seekTargetMs(fraction, state.durationMs) }
        ?: state.positionMs
    val progressLabel = stringResource(R.string.player_progress_content_description)
    val positionText = formatPlaybackTime(displayedPosition)
    val durationText = formatPlaybackTime(state.durationMs)
    val progressState = stringResource(
        R.string.player_progress_state_description,
        positionText,
        durationText,
    )

    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            ExpressiveWavyProgress(
                progress = displayedFraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { },
            )
            Slider(
                value = displayedFraction,
                onValueChange = { dragFraction = it },
                onValueChangeFinished = {
                    dragFraction?.let(onSeek)
                    dragFraction = null
                },
                enabled = state.durationMs > 0L,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = progressLabel
                        stateDescription = progressState
                    },
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                ),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = positionText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = durationText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlayerTransport(
    state: PlayerUiState,
    onPlayPause: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
) {
    val canPlay = state.hasPlayableAudio
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onPreviousChapter,
            enabled = state.previousChapterIndex() != null,
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                imageVector = PreviousChapterIcon,
                contentDescription = stringResource(R.string.player_previous_chapter),
                modifier = Modifier.size(28.dp),
            )
        }

        FloatingActionButton(
            onClick = { if (canPlay) onPlayPause() },
            modifier = Modifier
                .size(84.dp)
                .alpha(if (canPlay) 1f else 0.38f)
                .semantics { if (!canPlay) disabled() },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(
                imageVector = if (state.isPlaying) PauseIcon else PlayIcon,
                contentDescription = stringResource(
                    if (state.isPlaying) R.string.player_pause else R.string.player_play,
                ),
                modifier = Modifier.size(36.dp),
            )
        }

        IconButton(
            onClick = onNextChapter,
            enabled = state.nextChapterIndex() != null,
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                imageVector = NextChapterIcon,
                contentDescription = stringResource(R.string.player_next_chapter),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun PlayerSegmentNav(
    state: PlayerUiState,
    onPreviousSegment: () -> Unit,
    onNextSegment: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        ExpressiveTonalButton(
            text = stringResource(R.string.player_previous_segment),
            onClick = onPreviousSegment,
            enabled = state.previousSegmentIndex() != null,
            modifier = Modifier.weight(1f),
        )
        ExpressiveTonalButton(
            text = stringResource(R.string.player_next_segment),
            onClick = onNextSegment,
            enabled = state.nextSegmentIndex() != null,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PlayerSpeedSelector(
    state: PlayerUiState,
    onSpeedChange: (PlayerSpeed) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.player_speed_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmallEmphasized,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            PlayerSpeed.options.forEach { option ->
                ExpressiveChip(
                    label = stringResource(
                        R.string.player_speed_option,
                        formatSpeedMultiplier(option.multiplier),
                    ),
                    selected = state.speed == option,
                    onClick = { onSpeedChange(option) },
                )
            }
        }
    }
}

@Composable
private fun PlayerSleepSelector(
    state: PlayerUiState,
    onSleepTimerChange: (SleepTimerOption) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.player_sleep_title),
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
                style = MaterialTheme.typography.titleSmallEmphasized,
            )
            val remaining = state.sleepRemainingMs
            if (remaining != null) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Text(
                        text = stringResource(
                            R.string.player_sleep_remaining,
                            formatPlaybackTime(remaining),
                        ),
                        modifier = Modifier.padding(
                            horizontal = MaterialTheme.spacing.sm,
                            vertical = MaterialTheme.spacing.xxs,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            SleepTimerOption.options.forEach { option ->
                ExpressiveChip(
                    label = sleepOptionLabel(option),
                    selected = state.sleepTimer == option,
                    onClick = { onSleepTimerChange(option) },
                )
            }
        }
    }
}

@Composable
private fun sleepOptionLabel(option: SleepTimerOption): String {
    val minutes = option.minutes
    return if (minutes == null) {
        stringResource(R.string.player_sleep_off)
    } else {
        stringResource(R.string.player_sleep_minutes, minutes)
    }
}

@Composable
private fun PlayerChapterHeaderCard(
    state: PlayerUiState,
    onToggleChapters: () -> Unit,
) {
    ExpressiveCard(
        onClick = onToggleChapters,
        contentPadding = PaddingValues(MaterialTheme.spacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.player_chapters_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                Text(
                    text = stringResource(R.string.player_chapters_count, state.chapters.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(
                    if (state.chaptersExpanded) {
                        R.string.player_chapters_collapse
                    } else {
                        R.string.player_chapters_expand
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun PlayerChapterList(
    state: PlayerUiState,
    onChapterClick: (Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
    ) {
        items(items = state.chapters, key = { chapter -> chapter.key }) { chapter ->
            PlayerChapterRow(
                chapter = chapter,
                isCurrent = state.isCurrentChapter(chapter),
                onClick = { onChapterClick(chapter.chapterId) },
            )
        }
    }
}

@Composable
private fun PlayerChapterRow(
    chapter: PlayerChapterUi,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val container = if (isCurrent) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = if (isCurrent) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val title = chapter.title.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.player_chapter_untitled)
    Surface(
        onClick = onClick,
        enabled = chapter.hasAudio,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { selected = isCurrent },
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(
                    horizontal = MaterialTheme.spacing.md,
                    vertical = MaterialTheme.spacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            Text(
                text = (chapter.index + 1).toString(),
                style = MaterialTheme.typography.labelMedium,
                color = content.copy(alpha = 0.7f),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!chapter.hasAudio) {
                    Text(
                        text = stringResource(R.string.player_chapter_no_audio),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (isCurrent) {
                Text(
                    text = stringResource(R.string.player_chapter_current),
                    style = MaterialTheme.typography.labelSmall,
                    color = content,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 本地图标：项目不引入 material-icons-extended，播放控制图标用 ImageVector 绘制。
// ---------------------------------------------------------------------------

private val PlayIcon: ImageVector = playerIcon("player_play") {
    moveTo(8f, 5f)
    lineTo(19f, 12f)
    lineTo(8f, 19f)
    close()
}

private val PauseIcon: ImageVector = playerIcon("player_pause") {
    moveTo(7f, 5f)
    lineTo(11f, 5f)
    lineTo(11f, 19f)
    lineTo(7f, 19f)
    close()
    moveTo(13f, 5f)
    lineTo(17f, 5f)
    lineTo(17f, 19f)
    lineTo(13f, 19f)
    close()
}

private val PreviousChapterIcon: ImageVector = playerIcon("player_previous_chapter") {
    moveTo(6f, 5f)
    lineTo(8f, 5f)
    lineTo(8f, 19f)
    lineTo(6f, 19f)
    close()
    moveTo(19f, 5f)
    lineTo(10f, 12f)
    lineTo(19f, 19f)
    close()
}

private val NextChapterIcon: ImageVector = playerIcon("player_next_chapter") {
    moveTo(16f, 5f)
    lineTo(18f, 5f)
    lineTo(18f, 19f)
    lineTo(16f, 19f)
    close()
    moveTo(5f, 5f)
    lineTo(14f, 12f)
    lineTo(5f, 19f)
    close()
}

private fun playerIcon(name: String, pathBuilder: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            pathBuilder.invoke(this)
        }
    }.build()

// ---------------------------------------------------------------------------
// Preview：播放中（含睡眠定时）/ 暂停 / 无音频空态 / 错误态（深色 200%）。
// 演示书名、章节与台词只是预览数据；所有 UI 文案来自 strings.xml。
// ---------------------------------------------------------------------------

private fun previewSegment(
    index: Int,
    roleName: String,
    text: String,
    audioPath: String? = "audio/7/segments/ch_001/seg_000001.mp3",
    durationMs: Long = 60_000L,
): PlayerSegmentUi = PlayerSegmentUi(
    index = index,
    roleName = roleName,
    text = text,
    audioPath = audioPath,
    durationMs = durationMs,
)

private fun previewChapters(): List<PlayerChapterUi> = listOf(
    PlayerChapterUi(
        chapterId = 11L,
        index = 0,
        title = "第一章 山雨",
        segments = listOf(
            previewSegment(0, "旁白", "山雨欲来，旧城的灯火在风里一盏盏暗下去。"),
            previewSegment(1, "沈砚", "这封信，你从哪里得来的？"),
            previewSegment(2, "顾青", "渡口的老船夫交给我的。他说，写信的人已经等了三年。", null, 0L),
        ),
    ),
    PlayerChapterUi(
        chapterId = 12L,
        index = 1,
        title = "第二章 旧信",
        segments = listOf(
            previewSegment(0, "旁白", "灯下的信纸已经泛黄，落款处却仍清晰。"),
            previewSegment(1, "沈砚", "三年……原来他一直没有离开。"),
        ),
    ),
)

private fun previewState(
    isPlaying: Boolean,
    speed: PlayerSpeed = PlayerSpeed.X100,
    sleepTimer: SleepTimerOption = SleepTimerOption.Off,
    sleepRemainingMs: Long? = null,
    chaptersExpanded: Boolean = false,
): PlayerUiState = PlayerUiState(
    loading = false,
    bookTitle = "雾城旧事",
    chapters = previewChapters(),
    currentChapterIndex = 1,
    currentSegmentIndex = 1,
    positionMs = 83_000L,
    durationMs = 245_000L,
    isPlaying = isPlaying,
    speed = speed,
    sleepTimer = sleepTimer,
    sleepRemainingMs = sleepRemainingMs,
    chaptersExpanded = chaptersExpanded,
)

@Preview(name = "Player Playing", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun PlayerPlayingPreview() {
    AudioForgeTheme(dynamicColor = false) {
        PlayerScreen(
            state = previewState(
                isPlaying = true,
                speed = PlayerSpeed.X125,
                sleepTimer = SleepTimerOption.Minutes15,
                sleepRemainingMs = 14L * 60_000L,
                chaptersExpanded = true,
            ),
        )
    }
}

@Preview(name = "Player Paused", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun PlayerPausedPreview() {
    AudioForgeTheme(dynamicColor = false) {
        PlayerScreen(state = previewState(isPlaying = false))
    }
}

@Preview(name = "Player Empty", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun PlayerEmptyPreview() {
    AudioForgeTheme(dynamicColor = false) {
        PlayerScreen(
            state = PlayerUiState(
                loading = false,
                bookTitle = "雾城旧事",
                chapters = listOf(
                    PlayerChapterUi(
                        chapterId = 11L,
                        index = 0,
                        title = "第一章 山雨",
                        segments = listOf(
                            previewSegment(0, "旁白", "还没有生成音频。", null, 0L),
                        ),
                    ),
                ),
            ),
        )
    }
}

@Preview(
    name = "Player Error Dark 200%",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun PlayerErrorPreview() {
    AudioForgeTheme(darkTheme = true, dynamicColor = false) {
        PlayerScreen(
            state = PlayerUiState(
                loading = false,
                bookTitle = "雾城旧事",
                loadError = "数据库被锁定",
            ),
        )
    }
}
