@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.estimatefaster.audioforge.feature.characters

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.core.model.Gender
import com.estimatefaster.audioforge.core.model.Importance
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.ui.components.RoleAvatar
import com.estimatefaster.audioforge.ui.forge.ForgeCard
import com.estimatefaster.audioforge.ui.forge.ForgeChip
import com.estimatefaster.audioforge.ui.forge.ForgeEmpty
import com.estimatefaster.audioforge.ui.forge.ForgeError
import com.estimatefaster.audioforge.ui.forge.ForgeIcon
import com.estimatefaster.audioforge.ui.forge.ForgeIconAction
import com.estimatefaster.audioforge.ui.forge.ForgeIcons
import com.estimatefaster.audioforge.ui.forge.ForgeLoading
import com.estimatefaster.audioforge.ui.forge.ForgePrimaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSectionHeader
import com.estimatefaster.audioforge.ui.forge.ForgeTopBar
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing

/**
 * 人物音色页（Forge Framework v1）。
 *
 * 有状态入口：收集 [CharactersViewModel.state] 后委托给无状态版本。
 * 页面本身不写库：点击角色换音色（VoicePicker Sheet）、重命名 / 编辑别名 /
 * 排除角色 / 调参都通过回调交给宿主。
 */
@Composable
fun CharactersScreen(
    viewModel: CharactersViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onVoiceClick: (String) -> Unit = {},
    onRenameCharacter: (String) -> Unit = {},
    onEditAliases: (String) -> Unit = {},
    onExcludeCharacter: (String) -> Unit = {},
    onTuneCharacter: (String) -> Unit = {},
    onParseNovel: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CharactersScreen(
        state = state,
        modifier = modifier,
        onBack = onBack,
        onQueryChange = viewModel::setQuery,
        onGenderFilterChange = viewModel::setGenderFilter,
        onImportanceFilterChange = viewModel::setImportanceFilter,
        onClearFilters = viewModel::clearFilters,
        onRetry = viewModel::retry,
        onVoiceClick = onVoiceClick,
        onRenameCharacter = onRenameCharacter,
        onEditAliases = onEditAliases,
        onExcludeCharacter = onExcludeCharacter,
        onTuneCharacter = onTuneCharacter,
        onParseNovel = onParseNovel,
    )
}

/**
 * 无状态人物音色页。
 *
 * 结构对齐 prototype 的 Characters：topbar → 搜索 → 性别/重要性 chips →
 * “角色与音色”列表 → 锁定说明。搜索 + 两维筛选仍是 AND 组合，空态区分
 * “本书无角色”和“筛选无结果”。
 */
@Composable
fun CharactersScreen(
    state: CharactersUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onGenderFilterChange: (Gender?) -> Unit = {},
    onImportanceFilterChange: (Importance?) -> Unit = {},
    onClearFilters: () -> Unit = {},
    onRetry: () -> Unit = {},
    onVoiceClick: (String) -> Unit = {},
    onRenameCharacter: (String) -> Unit = {},
    onEditAliases: (String) -> Unit = {},
    onExcludeCharacter: (String) -> Unit = {},
    onTuneCharacter: (String) -> Unit = {},
    onParseNovel: (() -> Unit)? = null,
) {
    val loadError = state.loadError
    val searchFocus = remember { FocusRequester() }
    val hasSearchField = !state.isInitialLoading && loadError == null && !state.isEmpty

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ForgeTopBar(
                title = stringResource(R.string.ux2_characters_title),
                subtitle = if (state.totalCount == 0) {
                    null
                } else {
                    stringResource(
                        R.string.ux2_characters_subtitle,
                        state.totalCount,
                        state.usedCount,
                    )
                },
                navigationIcon = ForgeIcons.Back,
                navigationDescription = stringResource(R.string.ux2_characters_back),
                onNavigation = onBack,
                actions = {
                    if (hasSearchField) {
                        ForgeIconAction(
                            icon = ForgeIcons.Search,
                            contentDescription = stringResource(R.string.ux2_characters_search),
                            onClick = { searchFocus.requestFocus() },
                            container = false,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.isInitialLoading -> ForgeLoading(
                    label = stringResource(R.string.ux2_characters_loading),
                    modifier = Modifier.fillMaxSize(),
                )

                loadError != null -> ForgeError(
                    message = stringResource(R.string.ux2_characters_load_error, loadError),
                    onRetry = onRetry,
                )

                state.isEmpty -> ForgeEmpty(
                    icon = ForgeIcons.Users,
                    title = stringResource(R.string.ux2_characters_empty_title),
                    description = stringResource(R.string.ux2_characters_empty_description),
                    actionIcon = if (onParseNovel != null) {
                        ForgeIcons.Wand
                    } else {
                        ForgeIcons.Refresh
                    },
                    actionLabel = if (onParseNovel != null) {
                        stringResource(R.string.ux2_characters_parse)
                    } else {
                        stringResource(R.string.ux2_characters_reload)
                    },
                    onAction = onParseNovel ?: onRetry,
                )

                else -> CharactersContent(
                    state = state,
                    searchFocus = searchFocus,
                    onQueryChange = onQueryChange,
                    onGenderFilterChange = onGenderFilterChange,
                    onImportanceFilterChange = onImportanceFilterChange,
                    onClearFilters = onClearFilters,
                    onVoiceClick = onVoiceClick,
                    onRenameCharacter = onRenameCharacter,
                    onEditAliases = onEditAliases,
                    onExcludeCharacter = onExcludeCharacter,
                    onTuneCharacter = onTuneCharacter,
                )
            }
        }
    }
}

@Composable
private fun CharactersContent(
    state: CharactersUiState,
    searchFocus: FocusRequester,
    onQueryChange: (String) -> Unit,
    onGenderFilterChange: (Gender?) -> Unit,
    onImportanceFilterChange: (Importance?) -> Unit,
    onClearFilters: () -> Unit,
    onVoiceClick: (String) -> Unit,
    onRenameCharacter: (String) -> Unit,
    onEditAliases: (String) -> Unit,
    onExcludeCharacter: (String) -> Unit,
    onTuneCharacter: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        CharacterSearchField(
            state = state,
            focusRequester = searchFocus,
            onQueryChange = onQueryChange,
            modifier = Modifier.padding(
                start = MaterialTheme.spacing.lg,
                end = MaterialTheme.spacing.lg,
                top = MaterialTheme.spacing.sm,
            ),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(
                start = MaterialTheme.spacing.lg,
                end = MaterialTheme.spacing.lg,
                top = MaterialTheme.spacing.xs,
                bottom = MaterialTheme.spacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        ) {
            item(key = "gender-filter") {
                CharacterFilterGroup(
                    label = stringResource(R.string.ux2_characters_filter_gender),
                ) {
                    ForgeChip(
                        label = filterLabel(
                            label = stringResource(R.string.ux2_characters_filter_all),
                            count = state.totalCount,
                        ),
                        selected = state.genderFilter == null,
                        onClick = { onGenderFilterChange(null) },
                        icon = ForgeIcons.Users,
                    )
                    Gender.entries.forEach { gender ->
                        ForgeChip(
                            label = filterLabel(
                                label = stringResource(gender.labelRes()),
                                count = state.allCharacters.count { it.gender == gender },
                            ),
                            selected = state.genderFilter == gender,
                            onClick = { onGenderFilterChange(gender) },
                            icon = ForgeIcons.UserRound,
                        )
                    }
                }
            }
            item(key = "importance-filter") {
                CharacterFilterGroup(
                    label = stringResource(R.string.ux2_characters_filter_importance),
                ) {
                    ForgeChip(
                        label = filterLabel(
                            label = stringResource(R.string.ux2_characters_filter_all),
                            count = state.totalCount,
                        ),
                        selected = state.importanceFilter == null,
                        onClick = { onImportanceFilterChange(null) },
                        icon = ForgeIcons.Users,
                    )
                    Importance.entries.forEach { importance ->
                        ForgeChip(
                            label = filterLabel(
                                label = stringResource(importance.labelRes()),
                                count = state.allCharacters.count { it.importance == importance },
                            ),
                            selected = state.importanceFilter == importance,
                            onClick = { onImportanceFilterChange(importance) },
                            icon = ForgeIcons.UserRound,
                        )
                    }
                }
            }
            item(key = "section") {
                ForgeSectionHeader(
                    title = stringResource(R.string.ux2_characters_section_title),
                    subtitle = stringResource(
                        R.string.ux2_characters_section_count,
                        state.visibleCount,
                    ),
                    modifier = Modifier.padding(top = MaterialTheme.spacing.xs),
                )
            }

            if (state.isFilteredEmpty) {
                item(key = "filtered-empty") {
                    ForgeEmpty(
                        icon = ForgeIcons.Search,
                        title = stringResource(R.string.ux2_characters_filtered_title),
                        description = stringResource(R.string.ux2_characters_filtered_description),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 320.dp),
                        actionIcon = ForgeIcons.Refresh,
                        actionLabel = stringResource(R.string.ux2_characters_clear_filters),
                        onAction = onClearFilters,
                    )
                }
            } else {
                items(
                    items = state.visibleCharacters,
                    key = { character -> character.key },
                    contentType = { _ -> "character" },
                ) { character ->
                    CharacterCard(
                        character = character,
                        onVoiceClick = { onVoiceClick(character.roleId) },
                        onRename = { onRenameCharacter(character.roleId) },
                        onEditAliases = { onEditAliases(character.roleId) },
                        onExclude = { onExcludeCharacter(character.roleId) },
                        onTune = { onTuneCharacter(character.roleId) },
                    )
                }
            }

            if (state.allCharacters.isNotEmpty()) {
                item(key = "lock-note") {
                    CharacterLockNote()
                }
            }
        }
    }
}

@Composable
private fun CharacterSearchField(
    state: CharactersUiState,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = state.query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
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
                        contentDescription = stringResource(R.string.ux2_characters_search_clear),
                    )
                }
            }
        } else {
            null
        },
        placeholder = {
            Text(
                text = stringResource(R.string.ux2_characters_search_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
    )
}

@Composable
private fun CharacterFilterGroup(
    label: String,
    chips: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            chips()
        }
    }
}

@Composable
private fun filterLabel(label: String, count: Int): String =
    stringResource(R.string.ux2_characters_filter_count, label, count)

@Composable
private fun CharacterCard(
    character: CharacterUi,
    onVoiceClick: () -> Unit,
    onRename: () -> Unit,
    onEditAliases: () -> Unit,
    onExclude: () -> Unit,
    onTune: () -> Unit,
) {
    var menuExpanded by rememberSaveable(character.key) { mutableStateOf(false) }
    val displayName = character.displayName.ifBlank {
        stringResource(R.string.ux2_characters_unknown_name)
    }
    val voiceName = character.voiceLabel
        ?: character.category.takeIf { it.isNotEmpty() }
        ?: stringResource(R.string.ux2_characters_voice_unassigned)
    val tuneDescription = stringResource(R.string.ux2_characters_tune, displayName)
    val moreDescription = stringResource(R.string.ux2_characters_more, displayName)

    ForgeCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onVoiceClick,
        contentPadding = PaddingValues(MaterialTheme.spacing.md),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        ) {
            RoleAvatar(name = displayName, size = 56.dp, decorative = true)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                ) {
                    ImportanceTag(importance = character.importance)
                    if (character.locked) {
                        CharacterTag(
                            text = stringResource(R.string.ux2_characters_locked),
                            container = MaterialTheme.colorScheme.tertiaryContainer,
                            content = MaterialTheme.colorScheme.onTertiaryContainer,
                            icon = ForgeIcons.Lock,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                ) {
                    ForgeIcon(
                        icon = ForgeIcons.AudioLines,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(
                            R.string.ux2_characters_voice_line,
                            voiceName,
                            stringResource(character.gender.labelRes()),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (character.aliases.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.ux2_characters_aliases,
                            character.aliases.joinToString(
                                stringResource(R.string.ux2_characters_alias_separator),
                            ),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (character.note.isNotEmpty()) {
                    Text(
                        text = character.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (character.category.isNotEmpty()) {
                    CharacterTag(
                        text = character.category,
                        container = MaterialTheme.colorScheme.surfaceContainerHigh,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                        icon = ForgeIcons.Tag,
                    )
                }
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
                        text = { Text(stringResource(R.string.ux2_characters_menu_rename)) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                        leadingIcon = {
                            ForgeIcon(ForgeIcons.Edit, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ux2_characters_menu_alias)) },
                        onClick = {
                            menuExpanded = false
                            onEditAliases()
                        },
                        leadingIcon = {
                            ForgeIcon(ForgeIcons.Tag, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ux2_characters_menu_tune)) },
                        onClick = {
                            menuExpanded = false
                            onTune()
                        },
                        leadingIcon = {
                            ForgeIcon(ForgeIcons.Sliders, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.ux2_characters_menu_exclude),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onExclude()
                        },
                        leadingIcon = {
                            ForgeIcon(
                                icon = ForgeIcons.Trash,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            ForgePrimaryAction(
                icon = ForgeIcons.AudioLines,
                label = stringResource(R.string.ux2_characters_voice_change),
                onClick = onVoiceClick,
                modifier = Modifier.weight(1f),
            )
            ForgeIconAction(
                icon = ForgeIcons.Sliders,
                contentDescription = tuneDescription,
                onClick = onTune,
                container = true,
            )
        }
    }
}

@Composable
private fun ImportanceTag(importance: Importance) {
    val container = when (importance) {
        Importance.Protagonist -> MaterialTheme.colorScheme.primaryContainer
        Importance.Supporting -> MaterialTheme.colorScheme.secondaryContainer
        Importance.Minor -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = when (importance) {
        Importance.Protagonist -> MaterialTheme.colorScheme.onPrimaryContainer
        Importance.Supporting -> MaterialTheme.colorScheme.onSecondaryContainer
        Importance.Minor -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    CharacterTag(
        text = stringResource(importance.labelRes()),
        container = container,
        content = content,
    )
}

@Composable
private fun CharacterTag(
    text: String,
    container: Color,
    content: Color,
    icon: Int? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MaterialTheme.spacing.sm,
                vertical = MaterialTheme.spacing.xxs,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs),
        ) {
            if (icon != null) {
                ForgeIcon(
                    icon = icon,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = content,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = content,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CharacterLockNote() {
    ForgeCard(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            ForgeIcon(
                icon = ForgeIcons.Lock,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = stringResource(R.string.ux2_characters_lock_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@StringRes
private fun Gender.labelRes(): Int = when (this) {
    Gender.Male -> R.string.ux2_characters_gender_male
    Gender.Female -> R.string.ux2_characters_gender_female
    Gender.Unknown -> R.string.ux2_characters_gender_unknown
}

@StringRes
private fun Importance.labelRes(): Int = when (this) {
    Importance.Protagonist -> R.string.ux2_characters_importance_protagonist
    Importance.Supporting -> R.string.ux2_characters_importance_supporting
    Importance.Minor -> R.string.ux2_characters_importance_minor
}

// ---------------------------------------------------------------------------
// Preview：正常列表 / 空态（深色 200%）。数据只是固定夹具。
// ---------------------------------------------------------------------------

private val previewVoiceLabels: Map<String, String> = mapOf(
    "female_lead_gentle" to "【女主】温柔清亮",
    "male_lead_steady" to "【男主】沉稳磁性",
    "male_gentle_young" to "【男配】青年柔和",
)

private fun previewCharactersState(): CharactersUiState {
    val entities = listOf(
        CharacterEntity(
            id = 1,
            novelId = 7,
            roleId = "role_0001",
            name = "林黛玉",
            gender = "female",
            importance = "protagonist",
            aliasesJson = "[\"黛玉\",\"颦儿\"]",
            note = "体弱多病，心思细腻",
            category = "female_lead_gentle",
            voiceId = "voice-female-lead",
            locked = true,
        ),
        CharacterEntity(
            id = 2,
            novelId = 7,
            roleId = "role_0002",
            name = "贾宝玉",
            gender = "male",
            importance = "protagonist",
            aliasesJson = "[\"宝玉\",\"怡红公子\"]",
            category = "male_lead_steady",
            voiceId = "voice-male-lead",
            locked = true,
        ),
        CharacterEntity(
            id = 3,
            novelId = 7,
            roleId = "role_0003",
            name = "袭人",
            gender = "female",
            importance = "supporting",
            aliasesJson = "[\"花袭人\"]",
            category = "male_gentle_young",
            voiceId = "voice-support",
            locked = false,
        ),
        CharacterEntity(
            id = 4,
            novelId = 7,
            roleId = "role_0004",
            name = "茗烟",
            gender = "male",
            importance = "minor",
            aliasesJson = "[]",
            category = "",
            voiceId = "",
            locked = false,
        ),
    )
    return buildCharactersUiState(
        entities = entities,
        voiceLabelFor = { category -> previewVoiceLabels[category] },
    )
}

@Preview(name = "Characters Forge", showBackground = true, backgroundColor = 0xFFF7F9F8)
@Composable
private fun CharactersForgePreview() {
    AudioForgeTheme(dynamicColor = false) {
        CharactersScreen(
            state = previewCharactersState(),
            onParseNovel = {},
        )
    }
}

@Preview(
    name = "Characters Empty Dark 200%",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun CharactersEmptyPreview() {
    AudioForgeTheme(darkTheme = true, dynamicColor = false) {
        CharactersScreen(
            state = CharactersUiState(loading = false),
            onParseNovel = {},
        )
    }
}
