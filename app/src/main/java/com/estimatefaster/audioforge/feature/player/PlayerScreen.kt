package com.estimatefaster.audioforge.feature.player

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.core.model.NARRATOR_ROLE_ID
import com.estimatefaster.audioforge.ui.components.EmptyState
import com.estimatefaster.audioforge.ui.components.ErrorState
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme

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
 * - 大封面占位（书名首字 + 主题渐变）、书名、当前章节标题；
 * - 当前段文本（角色名 + 台词）；
 * - 可拖动进度条、当前/总时长；
 * - 播放/暂停、上一章/下一章、上一句/下一句；
 * - 倍速 chips、睡眠定时 chips（选中后显示剩余）；
 * - 底部可折叠章节列表，当前章高亮，点击直接跳转；
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
                        Text(stringResource(R.string.action_back))
                    }
                },
                title = {
                    val bookTitle = state.bookTitle.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.player_book_untitled)
                    Text(
                        text = bookTitle,
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
    ) { padding ->
        when {
            state.isInitialLoading -> PlayerLoading(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )

            loadError != null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
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
                    .padding(horizontal = 12.dp),
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
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PlayerCover(bookTitle = state.bookTitle)
            Spacer(Modifier.height(16.dp))

            val bookTitle = state.bookTitle.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.player_book_untitled)
            Text(
                text = bookTitle,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))

            val chapterTitle = state.currentChapter?.title?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.player_chapter_untitled)
            Text(
                text = chapterTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(20.dp))
            PlayerSegmentCard(state = state)
            Spacer(Modifier.height(16.dp))
            PlayerProgress(state = state, onSeek = onSeek)
            Spacer(Modifier.height(8.dp))
            PlayerControls(
                state = state,
                onPlayPause = onPlayPause,
                onPreviousChapter = onPreviousChapter,
                onNextChapter = onNextChapter,
                onPreviousSegment = onPreviousSegment,
                onNextSegment = onNextSegment,
            )
            Spacer(Modifier.height(24.dp))
            PlayerSpeedSelector(state = state, onSpeedChange = onSpeedChange)
            Spacer(Modifier.height(16.dp))
            PlayerSleepSelector(state = state, onSleepTimerChange = onSleepTimerChange)
            Spacer(Modifier.height(16.dp))
            PlayerChapterHeader(state = state, onToggleChapters = onToggleChapters)
        }

        if (state.chaptersExpanded) {
            HorizontalDivider()
            PlayerChapterList(state = state, onChapterClick = onChapterClick)
        }
    }
}

@Composable
private fun PlayerCover(bookTitle: String) {
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
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(gradient)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = roleName,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
    val displayedPosition = dragFraction
        ?.let { fraction -> seekTargetMs(fraction, state.durationMs) }
        ?: state.positionMs
    val progressLabel = stringResource(R.string.player_progress_content_description)
    Slider(
        value = dragFraction ?: state.progress,
        onValueChange = { dragFraction = it },
        onValueChangeFinished = {
            dragFraction?.let(onSeek)
            dragFraction = null
        },
        enabled = state.durationMs > 0L,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = progressLabel },
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = formatPlaybackTime(displayedPosition),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatPlaybackTime(state.durationMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlayerControls(
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
        TextButton(
            onClick = onPreviousChapter,
            enabled = state.previousChapterIndex() != null,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.player_previous_chapter))
        }
        FilledTonalButton(
            onClick = onPlayPause,
            enabled = state.hasPlayableAudio,
            modifier = Modifier.heightIn(min = 56.dp),
        ) {
            Text(
                stringResource(
                    if (state.isPlaying) R.string.player_pause else R.string.player_play,
                ),
            )
        }
        TextButton(
            onClick = onNextChapter,
            enabled = state.nextChapterIndex() != null,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.player_next_chapter))
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onPreviousSegment,
            enabled = state.previousSegmentIndex() != null,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.player_previous_segment))
        }
        OutlinedButton(
            onClick = onNextSegment,
            enabled = state.nextSegmentIndex() != null,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.player_next_segment))
        }
    }
}

@Composable
private fun PlayerSpeedSelector(
    state: PlayerUiState,
    onSpeedChange: (PlayerSpeed) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.player_speed_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlayerSpeed.options.forEach { option ->
                FilterChip(
                    selected = state.speed == option,
                    onClick = { onSpeedChange(option) },
                    label = {
                        Text(
                            stringResource(
                                R.string.player_speed_option,
                                formatSpeedMultiplier(option.multiplier),
                            ),
                        )
                    },
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.player_sleep_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            val remaining = state.sleepRemainingMs
            if (remaining != null) {
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
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SleepTimerOption.options.forEach { option ->
                FilterChip(
                    selected = state.sleepTimer == option,
                    onClick = { onSleepTimerChange(option) },
                    label = { Text(sleepOptionLabel(option)) },
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
private fun PlayerChapterHeader(
    state: PlayerUiState,
    onToggleChapters: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.player_chapters_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.player_chapters_count, state.chapters.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onToggleChapters,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(
                stringResource(
                    if (state.chaptersExpanded) {
                        R.string.player_chapters_collapse
                    } else {
                        R.string.player_chapters_expand
                    },
                ),
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
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
        MaterialTheme.colorScheme.surface
    }
    val title = chapter.title.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.player_chapter_untitled)
    Card(
        onClick = onClick,
        enabled = chapter.hasAudio,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { selected = isCurrent },
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = (chapter.index + 1).toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
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
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun PlayerLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.player_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
