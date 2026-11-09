@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.estimatefaster.audioforge.feature.player

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
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
import com.estimatefaster.audioforge.ui.forge.ForgeCard
import com.estimatefaster.audioforge.ui.forge.ForgeChip
import com.estimatefaster.audioforge.ui.forge.ForgeEmpty
import com.estimatefaster.audioforge.ui.forge.ForgeError
import com.estimatefaster.audioforge.ui.forge.ForgeIcon
import com.estimatefaster.audioforge.ui.forge.ForgeIconAction
import com.estimatefaster.audioforge.ui.forge.ForgeIcons
import com.estimatefaster.audioforge.ui.forge.ForgeLoading
import com.estimatefaster.audioforge.ui.forge.ForgeTopBar
import com.estimatefaster.audioforge.ui.forge.ForgeWavyProgress
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing

/**
 * 播放器页（P5-07 / 执行计划 §15 P5-07，UX v2 重设计）。
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
 * 无状态播放器页（视觉对应 `docs/prototype/forge-framework.html` 的 Player）。
 *
 * 布局：
 * - 顶栏：返回 + 「正在播放」+ 当前章副标题 + 章节列表图标开关；
 * - 大封面：MaterialShapes 有机形状 + 渐变 + 首字 + Lucide 耳机角标；
 * - 书名 / 章节 / 第 N 句，当前台词卡；
 * - Wavy 进度 + 透明 Slider 拖动 + 时间；
 * - 播放控制全部使用 [ForgeIconAction]（Play/Pause/Skip）；
 * - 倍速 / 睡眠 chips（Lucide 图标 + 文案）；
 * - 可展开章节列表：整行是图标+文字的可点击操作。
 *
 * 加载 / 错误 / 无音频空态复用 `ui/forge` 的 ForgeLoading / ForgeError / ForgeEmpty。
 */
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
    val chapterTitle = state.currentChapter?.title?.takeIf { it.isNotBlank() }
    val topBarSubtitle = chapterTitle?.let { title ->
        stringResource(R.string.ux_v2_player_topbar_subtitle, title)
    }
    val chaptersToggleLabel = stringResource(
        if (state.chaptersExpanded) {
            R.string.player_chapters_collapse
        } else {
            R.string.player_chapters_expand
        },
    )

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ForgeTopBar(
                title = stringResource(R.string.ux_v2_player_topbar_title),
                subtitle = topBarSubtitle,
                navigationIcon = ForgeIcons.Back,
                navigationDescription = stringResource(R.string.action_back),
                onNavigation = onBack,
                actions = {
                    if (state.chapters.isNotEmpty()) {
                        ForgeIconAction(
                            icon = ForgeIcons.List,
                            contentDescription = chaptersToggleLabel,
                            onClick = onToggleChapters,
                            container = false,
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isInitialLoading -> ForgeLoading(
                label = stringResource(R.string.player_loading),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )

            loadError != null -> ForgeError(
                message = stringResource(R.string.player_load_error, loadError),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onRetry = onRetry,
                retryLabel = stringResource(R.string.action_retry),
            )

            state.isEmpty -> ForgeEmpty(
                icon = ForgeIcons.Headphones,
                title = stringResource(R.string.player_empty_title),
                description = stringResource(R.string.player_empty_description),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                actionIcon = ForgeIcons.ChevronLeft,
                actionLabel = stringResource(R.string.player_empty_action),
                onAction = onBack,
            )

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
    Column(
        modifier = modifier
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
            onPreviousSegment = onPreviousSegment,
            onNextSegment = onNextSegment,
        )
        PlayerSpeedCard(state = state, onSpeedChange = onSpeedChange)
        PlayerSleepCard(state = state, onSleepTimerChange = onSleepTimerChange)
        PlayerChapterHeader(state = state, onToggleChapters = onToggleChapters)
        if (state.chaptersExpanded) {
            PlayerChapterList(state = state, onChapterClick = onChapterClick)
        }
    }
}

@Composable
private fun PlayerCover(bookTitle: String) {
    val coverShape = MaterialShapes.Cookie9Sided.toShape(startAngle = 12)
    val badgeShape = MaterialShapes.Pill.toShape()
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
            .fillMaxWidth(0.7f)
            .widthIn(max = 260.dp)
            .aspectRatio(1f)
            // 封面是装饰性视觉，书名与章节已经由下方文本朗读。
            .clearAndSetSemantics { },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(coverShape)
                .background(gradient),
        )
        Text(
            text = letter,
            modifier = Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(MaterialTheme.spacing.lg)
                .size(44.dp),
            shape = badgeShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(ForgeIcons.Headphones),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
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
        if (position >= 0) {
            Text(
                text = stringResource(
                    R.string.ux_v2_player_segment_indicator,
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

    ForgeCard(
        modifier = Modifier.fillMaxWidth(),
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            ForgeWavyProgress(
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
    onPreviousSegment: () -> Unit,
    onNextSegment: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportIconAction(
            icon = ForgeIcons.SkipPrevious,
            contentDescription = stringResource(R.string.player_previous_segment),
            enabled = state.previousSegmentIndex() != null,
            onClick = onPreviousSegment,
        )
        TransportIconAction(
            icon = ForgeIcons.ChevronLeft,
            contentDescription = stringResource(R.string.player_previous_chapter),
            enabled = state.previousChapterIndex() != null,
            onClick = onPreviousChapter,
        )
        TransportIconAction(
            icon = if (state.isPlaying) ForgeIcons.Pause else ForgeIcons.Play,
            contentDescription = stringResource(
                if (state.isPlaying) R.string.player_pause else R.string.player_play,
            ),
            enabled = state.hasPlayableAudio,
            onClick = onPlayPause,
            container = true,
        )
        TransportIconAction(
            icon = ForgeIcons.ChevronRight,
            contentDescription = stringResource(R.string.player_next_chapter),
            enabled = state.nextChapterIndex() != null,
            onClick = onNextChapter,
        )
        TransportIconAction(
            icon = ForgeIcons.SkipNext,
            contentDescription = stringResource(R.string.player_next_segment),
            enabled = state.nextSegmentIndex() != null,
            onClick = onNextSegment,
        )
    }
}

/**
 * 播放控制统一入口：始终是 [ForgeIconAction]（纯图标 + contentDescription）。
 *
 * [ForgeIconAction] 没有 enabled 参数，因此禁用时降透明度、标记 disabled 语义，
 * 并在 onClick 内兜底拦截，保持原状态机“不可用按钮不触发回调”的行为。
 */
@Composable
private fun TransportIconAction(
    @DrawableRes icon: Int,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Boolean = false,
) {
    ForgeIconAction(
        icon = icon,
        contentDescription = contentDescription,
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .semantics { if (!enabled) disabled() },
        container = container,
    )
}

@Composable
private fun PlayerSpeedCard(
    state: PlayerUiState,
    onSpeedChange: (PlayerSpeed) -> Unit,
) {
    ForgeCard(modifier = Modifier.fillMaxWidth()) {
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
                ForgeChip(
                    label = stringResource(
                        R.string.player_speed_option,
                        formatSpeedMultiplier(option.multiplier),
                    ),
                    selected = state.speed == option,
                    onClick = { onSpeedChange(option) },
                    icon = ForgeIcons.Gauge,
                )
            }
        }
    }
}

@Composable
private fun PlayerSleepCard(
    state: PlayerUiState,
    onSleepTimerChange: (SleepTimerOption) -> Unit,
) {
    ForgeCard(modifier = Modifier.fillMaxWidth()) {
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ForgeIcon(
                        icon = ForgeIcons.Clock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(
                            R.string.player_sleep_remaining,
                            formatPlaybackTime(remaining),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            SleepTimerOption.options.forEach { option ->
                val minutes = option.minutes
                ForgeChip(
                    label = if (minutes == null) {
                        stringResource(R.string.player_sleep_off)
                    } else {
                        stringResource(R.string.player_sleep_minutes, minutes)
                    },
                    selected = state.sleepTimer == option,
                    onClick = { onSleepTimerChange(option) },
                    icon = if (option.isEnabled) ForgeIcons.Timer else ForgeIcons.Moon,
                )
            }
        }
    }
}

@Composable
private fun PlayerChapterHeader(
    state: PlayerUiState,
    onToggleChapters: () -> Unit,
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
                style = MaterialTheme.typography.titleSmallEmphasized,
            )
            Text(
                text = stringResource(R.string.player_chapters_count, state.chapters.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ForgeIconAction(
            icon = ForgeIcons.ChevronDown,
            contentDescription = stringResource(
                if (state.chaptersExpanded) {
                    R.string.player_chapters_collapse
                } else {
                    R.string.player_chapters_expand
                },
            ),
            onClick = onToggleChapters,
            modifier = Modifier.rotate(if (state.chaptersExpanded) 180f else 0f),
            container = false,
        )
    }
}

@Composable
private fun PlayerChapterList(
    state: PlayerUiState,
    onChapterClick: (Long) -> Unit,
) {
    ForgeCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(MaterialTheme.spacing.sm),
    ) {
        state.chapters.forEach { chapter ->
            PlayerChapterRow(
                chapter = chapter,
                isCurrent = state.isCurrentChapter(chapter),
                isPlaying = state.isPlaying,
                onClick = { onChapterClick(chapter.chapterId) },
            )
        }
    }
}

@Composable
private fun PlayerChapterRow(
    chapter: PlayerChapterUi,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    val title = chapter.title.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.player_chapter_untitled)
    val notReadyLabel = stringResource(R.string.player_chapter_no_audio)
    val currentLabel = stringResource(R.string.player_chapter_current)
    val container = if (isCurrent) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    val leadingColor = if (isCurrent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val leadingTint = if (isCurrent) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(container)
            .clickable(
                enabled = chapter.hasAudio,
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                selected = isCurrent
                if (!chapter.hasAudio) {
                    stateDescription = notReadyLabel
                }
            }
            .padding(
                horizontal = MaterialTheme.spacing.sm,
                vertical = MaterialTheme.spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = MaterialTheme.shapes.medium,
            color = leadingColor,
            contentColor = leadingTint,
        ) {
            Box(contentAlignment = Alignment.Center) {
                ForgeIcon(
                    icon = if (isCurrent) ForgeIcons.AudioLines else ForgeIcons.Music,
                    contentDescription = null,
                    tint = leadingTint,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.ux_v2_player_chapter_meta,
                    chapter.playableSegments.size,
                    formatPlaybackTime(chapter.durationMs),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            isCurrent -> Row(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ForgeIcon(
                    icon = if (isPlaying) ForgeIcons.Pause else ForgeIcons.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = currentLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            !chapter.hasAudio -> Row(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ForgeIcon(
                    icon = ForgeIcons.Clock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = notReadyLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val DISABLED_ALPHA = 0.38f

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
