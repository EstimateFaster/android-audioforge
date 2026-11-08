package com.estimatefaster.audioforge.feature.characters

import android.content.res.Configuration
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.core.model.Gender
import com.estimatefaster.audioforge.core.model.Importance
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.ui.components.EmptyState
import com.estimatefaster.audioforge.ui.components.ErrorState
import com.estimatefaster.audioforge.ui.components.RoleAvatar
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme

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
        onParseNovel = onParseNovel,
    )
}

/**
 * 无状态人物音色页。
 *
 * - 顶部：返回、标题、“已用角色数 / 总数”；
 * - 搜索（名字/别名）+ 性别/重要性筛选（AND 组合）；
 * - 角色卡：头像、名字、别名、性别/重要性标签、音色类别、当前音色、锁定标记；
 * - 点击整卡换音色；长按或“更多”打开重命名/编辑别名/排除菜单；
 * - 空态、筛选无结果、首屏加载、错误态复用 P4-02 组件。
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
    onParseNovel: (() -> Unit)? = null,
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
                    Column {
                        Text(
                            text = stringResource(R.string.characters_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
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
                        .padding(start = 20.dp, end = 20.dp, top = 4.dp),
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
                    Text(
                        text = stringResource(
                            R.string.characters_count_filtered,
                            state.visibleCount,
                            state.totalCount,
                        ),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when {
                    state.isInitialLoading -> CharactersLoading(
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
                            message = stringResource(R.string.characters_load_error, loadError),
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
                            title = stringResource(R.string.characters_empty_title),
                            description = stringResource(R.string.characters_empty_description),
                            actionText = if (onParseNovel != null) {
                                stringResource(R.string.characters_empty_action_parse)
                            } else {
                                null
                            },
                            onAction = onParseNovel,
                        )
                    }

                    state.isFilteredEmpty -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            title = stringResource(R.string.characters_filtered_empty_title),
                            description = stringResource(
                                R.string.characters_filtered_empty_description,
                            ),
                            actionText = stringResource(R.string.characters_filtered_empty_action),
                            onAction = onClearFilters,
                        )
                    }

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 20.dp,
                            end = 20.dp,
                            top = 8.dp,
                            bottom = 24.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
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
            .padding(horizontal = 20.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        options.forEach { (value, text) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(text) },
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
) {
    var menuExpanded by rememberSaveable(character.key) { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.medium
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClickLabel = clickLabel,
                onLongClickLabel = stringResource(R.string.characters_more),
                onLongClick = { menuExpanded = true },
                onClick = onVoiceClick,
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RoleAvatar(name = displayName, size = 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (character.locked) {
                        Spacer(Modifier.width(6.dp))
                        CharacterTag(
                            text = stringResource(R.string.characters_locked),
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                if (character.aliases.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
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
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CharacterTag(
                        text = stringResource(character.gender.labelRes()),
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CharacterTag(
                        text = stringResource(character.importance.labelRes()),
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (character.category.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.characters_category,
                                character.category,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.characters_voice_current, voiceName),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (character.hasVoice) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (character.note.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = character.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(
                    onClick = onVoiceClick,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.characters_voice_change))
                }
                Box {
                    TextButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = moreDescription },
                    ) {
                        Text(stringResource(R.string.characters_more))
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
    onExclude: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.characters_action_rename)) },
            onClick = onRename,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.characters_action_edit_alias)) },
            onClick = onEditAliases,
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
private fun CharacterTag(
    text: String,
    container: Color,
    content: Color,
) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = container,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
        )
    }
}

@Composable
private fun CharactersLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.characters_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
// Preview 数据：只用固定非动态色，分别检查正常列表与空态。
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

@Preview(name = "Characters", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun CharactersPreview() {
    AudioForgeTheme(dynamicColor = false) {
        CharactersScreen(
            state = previewCharactersState(),
            onParseNovel = {},
        )
    }
}

@Preview(name = "Characters Filtered Empty", showBackground = true, backgroundColor = 0xFFF7F8FC)
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
