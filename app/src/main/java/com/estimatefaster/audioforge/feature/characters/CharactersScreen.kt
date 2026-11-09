@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.estimatefaster.audioforge.feature.characters

import android.content.res.Configuration
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.core.model.Gender
import com.estimatefaster.audioforge.core.model.Importance
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.ui.components.ExpressiveChip
import com.estimatefaster.audioforge.ui.components.ExpressiveTonalButton
import com.estimatefaster.audioforge.ui.components.RoleAvatar
import com.estimatefaster.audioforge.ui.components.ScreenState
import com.estimatefaster.audioforge.ui.components.StatusPill
import com.estimatefaster.audioforge.ui.components.StatusTone
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing

/**
 * 人物音色页（P5-04 / 执行计划 §12.4 S08）。
 *
 * 有状态入口：收集 [CharactersViewModel.state] 后委托给无状态版本。
 * 页面本身不写库：点击角色换音色（P5-05 Sheet）、长按/“更多”的重命名、
 * 编辑别名、排除角色都通过回调交给宿主。
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
 * - 顶栏：返回、标题、“已用角色数 / 总数”；
 * - 搜索（名字/别名）+ 性别/重要性 `ExpressiveChip` 筛选（AND 组合）；
 * - 人物卡：头像、名字、别名、性别/重要性标签、音色类别、当前音色、锁定标记；
 *   主操作“更换音色”是卡内的 tonal 按钮，整卡点击同样打开音色 Sheet；
 * - 长按或“更多”打开重命名/编辑别名/调参/排除菜单；
 * - 加载、错误、空态、筛选无结果统一走 [ScreenState]。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val screenState = when {
        state.isInitialLoading -> ScreenState.Loading(stringResource(R.string.characters_loading))

        loadError != null -> ScreenState.Error(
            message = stringResource(R.string.characters_load_error, loadError),
            onRetry = onRetry,
        )

        state.isEmpty -> ScreenState.Empty(
            title = stringResource(R.string.characters_empty_title),
            description = stringResource(R.string.characters_empty_description),
            actionText = if (onParseNovel != null) {
                stringResource(R.string.characters_empty_action_parse)
            } else {
                null
            },
            onAction = onParseNovel,
        )

        state.isFilteredEmpty -> ScreenState.Empty(
            title = stringResource(R.string.characters_filtered_empty_title),
            description = stringResource(R.string.characters_filtered_empty_description),
            actionText = stringResource(R.string.characters_filtered_empty_action),
            onAction = onClearFilters,
        )

        else -> ScreenState.Content
    }

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
                    Column {
                        Text(
                            text = stringResource(R.string.characters_title),
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleLargeEmphasized,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.totalCount > 0) {
                            Text(
                                text = stringResource(
                                    R.string.characters_count,
                                    state.usedCount,
                                    state.totalCount,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.allCharacters.isNotEmpty()) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = MaterialTheme.spacing.lg,
                            end = MaterialTheme.spacing.lg,
                            top = MaterialTheme.spacing.xs,
                        ),
                    shape = MaterialTheme.shapes.large,
                    label = { Text(stringResource(R.string.characters_search_label)) },
                    placeholder = {
                        Text(stringResource(R.string.characters_search_placeholder))
                    },
                    singleLine = true,
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            TextButton(
                                onClick = { onQueryChange("") },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.characters_search_clear))
                            }
                        }
                    },
                )
                CharacterGenderFilterRow(
                    selected = state.genderFilter,
                    onSelect = onGenderFilterChange,
                )
                CharacterImportanceFilterRow(
                    selected = state.importanceFilter,
                    onSelect = onImportanceFilterChange,
                )
                if (state.hasActiveFilters) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = MaterialTheme.spacing.lg,
                                vertical = MaterialTheme.spacing.xxs,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                R.string.characters_count_filtered,
                                state.visibleCount,
                                state.totalCount,
                            ),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = onClearFilters,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.characters_filtered_empty_action))
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                ScreenState(
                    state = screenState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = MaterialTheme.spacing.lg,
                            end = MaterialTheme.spacing.lg,
                            top = MaterialTheme.spacing.sm,
                            bottom = MaterialTheme.spacing.xl,
                        ),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
                    ) {
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
                }
            }
        }
    }
}

@Composable
private fun CharacterGenderFilterRow(
    selected: Gender?,
    onSelect: (Gender?) -> Unit,
) {
    CharacterFilterChipRow(
        label = stringResource(R.string.characters_filter_gender),
        options = listOf<Pair<Gender?, String>>(
            null to stringResource(R.string.characters_filter_all),
            Gender.Male to stringResource(R.string.characters_gender_male),
            Gender.Female to stringResource(R.string.characters_gender_female),
            Gender.Unknown to stringResource(R.string.characters_gender_unknown),
        ),
        selected = selected,
        onSelect = onSelect,
    )
}

@Composable
private fun CharacterImportanceFilterRow(
    selected: Importance?,
    onSelect: (Importance?) -> Unit,
) {
    CharacterFilterChipRow(
        label = stringResource(R.string.characters_filter_importance),
        options = listOf<Pair<Importance?, String>>(
            null to stringResource(R.string.characters_filter_all),
            Importance.Protagonist to stringResource(R.string.characters_importance_protagonist),
            Importance.Supporting to stringResource(R.string.characters_importance_supporting),
            Importance.Minor to stringResource(R.string.characters_importance_minor),
        ),
        selected = selected,
        onSelect = onSelect,
    )
}

@Composable
private fun <T> CharacterFilterChipRow(
    label: String,
    options: List<Pair<T?, String>>,
    selected: T?,
    onSelect: (T?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(
                horizontal = MaterialTheme.spacing.lg,
                vertical = MaterialTheme.spacing.xxs,
            ),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = MaterialTheme.spacing.xs),
        )
        options.forEach { (value, text) ->
            ExpressiveChip(
                label = text,
                selected = selected == value,
                onClick = { onSelect(value) },
            )
        }
    }
}

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
    val shape = MaterialTheme.shapes.extraLarge
    val displayName = character.displayName.ifBlank {
        stringResource(R.string.role_avatar_unknown)
    }
    val clickLabel = stringResource(R.string.characters_voice_change_content_description)
    val moreDescription = stringResource(
        R.string.characters_more_content_description,
        displayName,
    )
    val voiceName = character.voiceLabel
        ?: character.category.takeIf { it.isNotEmpty() }
        ?: stringResource(R.string.characters_voice_unassigned)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClickLabel = clickLabel,
                onLongClickLabel = stringResource(R.string.action_more),
                onLongClick = { menuExpanded = true },
                onClick = onVoiceClick,
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                // 名字在右侧可见，头像只做装饰，避免 TalkBack 把同一人物读两遍。
                RoleAvatar(name = displayName, size = 56.dp, decorative = true)
                Spacer(Modifier.width(MaterialTheme.spacing.md))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayName,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (character.locked) {
                            Spacer(Modifier.width(MaterialTheme.spacing.sm))
                            StatusPill(
                                text = stringResource(R.string.characters_locked),
                                tone = StatusTone.Warning,
                            )
                        }
                    }
                    if (character.aliases.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.characters_alias,
                                character.aliases.joinToString(
                                    stringResource(R.string.characters_alias_separator),
                                ),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                    ) {
                        CharacterTag(text = stringResource(character.gender.labelRes()))
                        CharacterTag(text = stringResource(character.importance.labelRes()))
                    }
                }
            }

            Text(
                text = stringResource(R.string.characters_voice_current, voiceName),
                style = MaterialTheme.typography.bodyMediumEmphasized,
                color = if (character.hasVoice) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (character.category.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.characters_category, character.category),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExpressiveTonalButton(
                    text = stringResource(R.string.characters_action_change_voice),
                    onClick = onVoiceClick,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    TextButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = moreDescription },
                    ) {
                        Text(stringResource(R.string.action_more))
                    }
                    CharacterMenu(
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
                        onRename = {
                            menuExpanded = false
                            onRename()
                        },
                        onEditAliases = {
                            menuExpanded = false
                            onEditAliases()
                        },
                        onTune = {
                            menuExpanded = false
                            onTune()
                        },
                        onExclude = {
                            menuExpanded = false
                            onExclude()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CharacterMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onEditAliases: () -> Unit,
    onTune: () -> Unit,
    onExclude: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_rename)) },
            onClick = onRename,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.characters_action_edit_alias)) },
            onClick = onEditAliases,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.characters_action_tune)) },
            onClick = onTune,
        )
        DropdownMenuItem(
            text = {
                Text(
                    text = stringResource(R.string.characters_action_exclude),
                    color = MaterialTheme.colorScheme.error,
                )
            },
            onClick = onExclude,
        )
    }
}

@Composable
private fun CharacterTag(text: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(
                horizontal = MaterialTheme.spacing.sm,
                vertical = MaterialTheme.spacing.xxs,
            ),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun Gender.labelRes(): Int = when (this) {
    Gender.Male -> R.string.characters_gender_male
    Gender.Female -> R.string.characters_gender_female
    Gender.Unknown -> R.string.characters_gender_unknown
}

@Composable
private fun Importance.labelRes(): Int = when (this) {
    Importance.Protagonist -> R.string.characters_importance_protagonist
    Importance.Supporting -> R.string.characters_importance_supporting
    Importance.Minor -> R.string.characters_importance_minor
}

// ---------------------------------------------------------------------------
// Preview 数据：只用固定非动态色，分别检查正常列表、筛选空结果与空态（深色 200%）。
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

@Preview(name = "Characters", showBackground = true)
@Composable
private fun CharactersPreview() {
    AudioForgeTheme(dynamicColor = false) {
        CharactersScreen(
            state = previewCharactersState(),
            onParseNovel = {},
        )
    }
}

@Preview(name = "Characters Filtered Empty", showBackground = true)
@Composable
private fun CharactersFilteredEmptyPreview() {
    AudioForgeTheme(dynamicColor = false) {
        CharactersScreen(
            state = previewCharactersState().copy(
                query = "不存在的人物",
                genderFilter = Gender.Female,
                importanceFilter = Importance.Minor,
            ),
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
