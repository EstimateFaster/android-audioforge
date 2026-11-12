package com.estimatefaster.audioforge.uipreview.nav

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.uipreview.R
import com.estimatefaster.audioforge.uipreview.data.PlayerState
import com.estimatefaster.audioforge.uipreview.data.formatTime
import com.estimatefaster.audioforge.uipreview.design.CoverArt
import com.estimatefaster.audioforge.uipreview.design.ProgressBlock
import com.estimatefaster.audioforge.uipreview.icons.Ms
import com.estimatefaster.audioforge.uipreview.theme.CoverShape

/** 类型安全目的地；depth 只用于转场方向。 */
sealed interface Dest {
    val depth: Int

    data object Library : Dest {
        override val depth: Int = 0
    }

    data object Settings : Dest {
        override val depth: Int = 0
    }

    data class Project(val id: Long) : Dest {
        override val depth: Int = 1
    }

    data class Chapters(val id: Long) : Dest {
        override val depth: Int = 2
    }

    data class Script(val bookId: Long, val chapterId: Long) : Dest {
        override val depth: Int = 3
    }

    data object Player : Dest {
        override val depth: Int = 1
    }

    data object Lab : Dest {
        override val depth: Int = 1
    }
}

@Stable
class Navigator(initial: Dest = Dest.Library) {
    var stack by mutableStateOf(listOf(initial))
        private set

    val current: Dest get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun push(dest: Dest) {
        stack = stack + dest
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack = stack.dropLast(1)
        return true
    }

    fun selectTab(dest: Dest) {
        if (stack.size == 1 && stack.last() == dest) return
        stack = listOf(dest)
    }
}

/**
 * 页面外壳：顶栏 + 内容 + 迷你播放器 + 底部导航。
 *
 * 迷你播放器与底栏只在这里出现一次，页面内部不再各自画播放条。
 */
@Composable
fun AppScaffold(
    navigator: Navigator,
    player: PlayerState,
    showNavBar: Boolean,
    showMiniPlayer: Boolean,
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    bottomContent: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = {
            if (bottomContent != null) {
                bottomContent()
            } else {
                Column {
                    AnimatedVisibility(visible = showMiniPlayer, enter = fadeIn(), exit = fadeOut()) {
                        MiniPlayerBar(
                            player = player,
                            onOpen = { navigator.push(Dest.Player) },
                        )
                    }
                    AnimatedVisibility(visible = showNavBar, enter = fadeIn(), exit = fadeOut()) {
                        AppNavigationBar(navigator = navigator)
                    }
                }
            }
        },
        floatingActionButton = floatingActionButton,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}

@Composable
fun AppNavigationBar(navigator: Navigator) {
    val current = navigator.current
    ShortNavigationBar(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        ShortNavigationBarItem(
            selected = current == Dest.Library,
            onClick = { navigator.selectTab(Dest.Library) },
            icon = {
                Icon(
                    painter = painterResource(Ms.LibraryBooks),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            },
            label = { Text(stringResource(R.string.nav_library), style = MaterialTheme.typography.labelMedium) },
        )
        ShortNavigationBarItem(
            selected = current == Dest.Settings,
            onClick = { navigator.selectTab(Dest.Settings) },
            icon = {
                Icon(
                    painter = painterResource(Ms.Settings),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            },
            label = { Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.labelMedium) },
        )
    }
}

@Composable
fun MiniPlayerBar(
    player: PlayerState,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chapter = player.chapter
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
    ) {
        Column {
            ProgressBlock(
                progress = player.progress,
                modifier = Modifier.padding(horizontal = 0.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onOpen)
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverArt(
                    title = player.book.title,
                    modifier = Modifier.size(40.dp),
                    shape = CoverShape,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = player.book.title,
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${chapter.title} · ${chapter.number}/${player.chapters.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "  ${formatTime(player.elapsedSec.toInt())}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                FilledTonalIconButton(
                    onClick = { player.toggle() },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        painter = painterResource(if (player.isPlaying) Ms.Pause else Ms.PlayArrow),
                        contentDescription = stringResource(
                            if (player.isPlaying) R.string.action_pause else R.string.action_play,
                        ),
                        modifier = Modifier.size(22.dp),
                    )
                }
                IconButton(
                    onClick = { player.nextSegment() },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        painter = painterResource(Ms.SkipNext),
                        contentDescription = stringResource(R.string.action_next_segment),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** 屏幕内容切换：前进/后退使用共享轴转场，时长来自 MotionScheme。 */
@Composable
fun ScreenHost(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    content: @Composable (Dest) -> Unit,
) {
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    AnimatedContent(
        targetState = navigator.current,
        modifier = modifier,
        transitionSpec = {
            if (targetState.depth >= initialState.depth) {
                (slideInHorizontally(spatial) { it } + fadeIn()) togetherWith
                    (slideOutHorizontally(spatial) { -it / 5 } + fadeOut())
            } else {
                (slideInHorizontally(spatial) { -it / 5 } + fadeIn()) togetherWith
                    (slideOutHorizontally(spatial) { it } + fadeOut())
            }
        },
        label = "screen-host",
    ) { dest ->
        content(dest)
    }
}

/** 章节/角色列表里的小头像。 */
@Composable
fun RoleAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Int = 36,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1),
            style = MaterialTheme.typography.labelLarge,
            color = content,
        )
    }
}

@Composable
fun NavBarSpacer() = Spacer(Modifier.height(8.dp))
