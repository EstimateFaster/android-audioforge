@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.estimatefaster.audioforge.feature.characters

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.ui.components.RoleAvatar
import com.estimatefaster.audioforge.ui.forge.ForgeCard
import com.estimatefaster.audioforge.ui.forge.ForgeChip
import com.estimatefaster.audioforge.ui.forge.ForgeEmpty
import com.estimatefaster.audioforge.ui.forge.ForgeIcon
import com.estimatefaster.audioforge.ui.forge.ForgeIcons
import com.estimatefaster.audioforge.ui.forge.ForgeListRow
import com.estimatefaster.audioforge.ui.forge.ForgePrimaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSecondaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSheetHeader
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing
import java.util.Locale

/**
 * 音色选择 Sheet（Forge Framework v1）。
 *
 * 有状态入口：收集 [VoicePickerViewModel.state]，把一次性事件转成宿主回调：
 * - `Assigned` → `onAssigned(roleId, category, voiceId)`，随后关闭 Sheet；
 * - `Cancelled` → `onDismiss`。
 *
 * @param onDismiss 用户取消/下滑/返回时关闭 Sheet
 * @param onAssigned 保存成功；宿主要据此刷新角色卡
 */
@Composable
fun VoicePickerSheet(
    viewModel: VoicePickerViewModel,
    onDismiss: () -> Unit,
    onAssigned: (roleId: String, category: String, voiceId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnAssigned by rememberUpdatedState(onAssigned)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is VoicePickerEvent.Assigned -> {
                    currentOnAssigned(event.roleId, event.category, event.voiceId)
                    currentOnDismiss()
                }
                VoicePickerEvent.Cancelled -> currentOnDismiss()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = viewModel::cancel,
        modifier = modifier,
        sheetState = sheetState,
    ) {
        VoicePickerSheetContent(
            state = state,
            onQueryChange = viewModel::setQuery,
            onGenderFilterChange = viewModel::setGenderFilter,
            onSelectVoice = viewModel::selectVoice,
            onClearFilters = viewModel::clearFilters,
            onSave = viewModel::save,
            onRetry = viewModel::retry,
            onCancel = viewModel::cancel,
            onConfirmLockedSave = viewModel::confirmLockedSave,
            onDismissLockedConfirm = viewModel::dismissLockedConfirm,
        )
    }
}

/**
 * 无状态 Sheet 内容；Preview 与 UI 测试直接渲染它。
 *
 * 布局对齐 prototype 的 Voice Sheet：固定 Sheet 头 + 角色 hero + 搜索 + 档位 chips +
 * 按“语言 · 性别”分组的音色行（ForgeListRow）+ 固定保存栏。锁定角色保存时二次确认。
 */
@Composable
fun VoicePickerSheetContent(
    state: VoicePickerUiState,
    modifier: Modifier = Modifier,
    onQueryChange: (String) -> Unit = {},
    onGenderFilterChange: (VoiceGenderGroup?) -> Unit = {},
    onSelectVoice: (String) -> Unit = {},
    onClearFilters: () -> Unit = {},
    onSave: () -> Unit = {},
    onRetry: () -> Unit = {},
    onCancel: () -> Unit = {},
    onConfirmLockedSave: () -> Unit = {},
    onDismissLockedConfirm: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        ForgeSheetHeader(
            icon = ForgeIcons.AudioLines,
            title = stringResource(R.string.ux2_voice_title),
            onClose = onCancel,
            subtitle = stringResource(R.string.ux2_voice_subtitle),
            modifier = Modifier.padding(
                start = MaterialTheme.spacing.lg,
                end = MaterialTheme.spacing.sm,
                top = MaterialTheme.spacing.sm,
                bottom = MaterialTheme.spacing.xs,
            ),
        )

        VoiceHero(
            state = state,
            modifier = Modifier.padding(
                start = MaterialTheme.spacing.lg,
                end = MaterialTheme.spacing.lg,
                bottom = MaterialTheme.spacing.sm,
            ),
        )

        if (state.locked) {
            VoiceLockNote(
                modifier = Modifier.padding(
                    start = MaterialTheme.spacing.lg,
                    end = MaterialTheme.spacing.lg,
                    bottom = MaterialTheme.spacing.xs,
                ),
            )
        }

        if (state.hasNoVoices) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                ForgeEmpty(
                    icon = ForgeIcons.Inbox,
                    title = stringResource(R.string.ux2_voice_empty_title),
                    description = stringResource(R.string.ux2_voice_empty_description),
                    actionIcon = ForgeIcons.Close,
                    actionLabel = stringResource(R.string.ux2_voice_empty_action),
                    onAction = onCancel,
                )
            }
        } else {
            VoiceSearchField(
                state = state,
                onQueryChange = onQueryChange,
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.lg),
            )
            VoiceGroupFilters(
                state = state,
                onGenderFilterChange = onGenderFilterChange,
                modifier = Modifier.padding(
                    start = MaterialTheme.spacing.lg,
                    end = MaterialTheme.spacing.lg,
                    top = MaterialTheme.spacing.sm,
                ),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (state.isFilteredEmpty) {
                    ForgeEmpty(
                        icon = ForgeIcons.Search,
                        title = stringResource(R.string.ux2_voice_filtered_title),
                        description = stringResource(R.string.ux2_voice_filtered_description),
                        actionIcon = ForgeIcons.Refresh,
                        actionLabel = stringResource(R.string.ux2_voice_filtered_action),
                        onAction = onClearFilters,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = MaterialTheme.spacing.lg,
                            end = MaterialTheme.spacing.lg,
                            top = MaterialTheme.spacing.xs,
                            bottom = MaterialTheme.spacing.sm,
                        ),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                    ) {
                        state.visibleSections.forEach { section ->
                            item(key = "section:${section.key}") {
                                VoiceSectionHeader(section = section)
                            }
                            items(
                                items = section.voices,
                                key = { voice -> voice.category },
                                contentType = { _ -> "voice" },
                            ) { voice ->
                                VoiceRow(
                                    voice = voice,
                                    selected = voice.category == state.selectedCategory,
                                    current = voice.category == state.currentCategory,
                                    enabled = !state.saving,
                                    onSelect = { onSelectVoice(voice.category) },
                                )
                            }
                        }
                    }
                }
            }
        }

        VoiceFooter(
            state = state,
            onSave = onSave,
            onRetry = onRetry,
        )
    }

    if (state.pendingLockedConfirm) {
        LockedVoiceConfirmDialog(
            saving = state.saving,
            onConfirm = onConfirmLockedSave,
            onDismiss = onDismissLockedConfirm,
        )
    }
}

@Composable
private fun VoiceHero(
    state: VoicePickerUiState,
    modifier: Modifier = Modifier,
) {
    val roleName = state.roleName.ifBlank { state.roleId }
    val currentName = state.currentDisplayName.ifBlank {
        stringResource(R.string.ux2_voice_unknown)
    }
    val currentCategory = state.currentCategory.ifBlank {
        stringResource(R.string.ux2_voice_unknown)
    }

    ForgeCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        ) {
            RoleAvatar(name = roleName, size = 48.dp, decorative = true)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.ux2_voice_hero_title, roleName),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.ux2_voice_hero_subtitle,
                        currentName,
                        currentCategory,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.locked) {
                VoiceTag(
                    text = stringResource(R.string.ux2_voice_locked),
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                    icon = ForgeIcons.Lock,
                )
            } else {
                VoiceTag(
                    text = stringResource(R.string.ux2_voice_unlocked),
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = ForgeIcons.Check,
                )
            }
        }
    }
}

@Composable
private fun VoiceLockNote(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.spacing.xs),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
    ) {
        ForgeIcon(
            icon = ForgeIcons.Lock,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            text = stringResource(R.string.ux2_voice_locked_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VoiceSearchField(
    state: VoicePickerUiState,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = state.query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        enabled = !state.saving,
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
                    enabled = !state.saving,
                    modifier = Modifier.size(48.dp),
                ) {
                    ForgeIcon(
                        icon = ForgeIcons.Close,
                        contentDescription = stringResource(R.string.ux2_voice_search_clear),
                    )
                }
            }
        } else {
            null
        },
        placeholder = {
            Text(
                text = stringResource(R.string.ux2_voice_search_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
    )
}

@Composable
private fun VoiceGroupFilters(
    state: VoicePickerUiState,
    onGenderFilterChange: (VoiceGenderGroup?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
    ) {
        Text(
            text = stringResource(R.string.ux2_voice_group_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            ForgeChip(
                label = stringResource(R.string.ux2_voice_group_all),
                selected = state.genderFilter == null,
                onClick = { if (!state.saving) onGenderFilterChange(null) },
                icon = ForgeIcons.AudioLines,
            )
            state.availableGroups.forEach { group ->
                ForgeChip(
                    label = group.label(),
                    selected = state.genderFilter == group,
                    onClick = { if (!state.saving) onGenderFilterChange(group) },
                    icon = group.icon(),
                )
            }
        }
    }
}

@Composable
private fun VoiceSectionHeader(section: VoiceSectionUi) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MaterialTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
    ) {
        ForgeIcon(
            icon = ForgeIcons.Mic,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(
                R.string.ux2_voice_section_header,
                localeLabel(section.locale),
                section.gender.label(),
            ),
            style = MaterialTheme.typography.titleSmallEmphasized,
        )
    }
}

@Composable
private fun VoiceRow(
    voice: VoiceCategoryUi,
    selected: Boolean,
    current: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val label = voice.displayLabel.ifBlank {
        stringResource(R.string.ux2_voice_unknown)
    }
    val selectionDescription = stringResource(
        if (selected) R.string.ux2_voice_selected else R.string.ux2_voice_select,
        label,
    )

    ForgeListRow(
        icon = ForgeIcons.AudioLines,
        title = label,
        subtitle = voiceSubtitle(voice),
        trailingText = if (current) stringResource(R.string.ux2_voice_current) else null,
        trailingIcon = if (selected) ForgeIcons.Check else ForgeIcons.Circle,
        trailingDescription = selectionDescription,
        onTrailingClick = { if (enabled) onSelect() },
        onClick = { if (enabled) onSelect() },
    )
}

@Composable
private fun voiceSubtitle(voice: VoiceCategoryUi): String {
    val voiceId = voice.displayVoiceId.ifBlank {
        stringResource(R.string.ux2_voice_unknown_id)
    }
    val note = voice.note?.trim().orEmpty()
    val speed = voice.speed?.let { value ->
        stringResource(R.string.ux2_voice_speed, formatSpeed(value))
    }
    val extra = note.ifBlank { speed.orEmpty() }
    return if (extra.isBlank()) {
        voiceId
    } else {
        stringResource(R.string.ux2_voice_row_subtitle, voiceId, extra)
    }
}

@Composable
private fun VoiceFooter(
    state: VoicePickerUiState,
    onSave: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = MaterialTheme.spacing.lg,
                end = MaterialTheme.spacing.lg,
                top = MaterialTheme.spacing.sm,
                bottom = MaterialTheme.spacing.lg,
            ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        state.saveError?.let { message ->
            VoiceSaveError(
                message = message,
                enabled = !state.saving,
                onRetry = onRetry,
            )
        }
        if (state.hasSelection && state.isSameAsCurrent) {
            Text(
                text = stringResource(R.string.ux2_voice_same),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ForgePrimaryAction(
            icon = ForgeIcons.Check,
            label = stringResource(
                when {
                    state.saving -> R.string.ux2_voice_saving
                    state.saveError != null -> R.string.ux2_voice_retry
                    else -> R.string.ux2_voice_save
                },
            ),
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canSave,
            loading = state.saving,
        )
    }
}

@Composable
private fun VoiceSaveError(
    message: String,
    enabled: Boolean,
    onRetry: () -> Unit,
) {
    ForgeCard(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentPadding = PaddingValues(MaterialTheme.spacing.md),
    ) {
        Text(
            text = stringResource(R.string.ux2_voice_save_error, message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        ForgeSecondaryAction(
            icon = ForgeIcons.Refresh,
            label = stringResource(R.string.ux2_voice_retry),
            onClick = onRetry,
            enabled = enabled,
        )
    }
}

@Composable
private fun LockedVoiceConfirmDialog(
    saving: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        shape = MaterialTheme.shapes.extraLarge,
        icon = {
            ForgeIcon(
                icon = ForgeIcons.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
            )
        },
        title = { Text(stringResource(R.string.ux2_voice_locked_confirm_title)) },
        text = { Text(stringResource(R.string.ux2_voice_locked_confirm_message)) },
        confirmButton = {
            ForgePrimaryAction(
                icon = ForgeIcons.Check,
                label = stringResource(R.string.ux2_voice_locked_confirm_action),
                onClick = onConfirm,
                enabled = !saving,
            )
        },
        dismissButton = {
            ForgeSecondaryAction(
                icon = ForgeIcons.Close,
                label = stringResource(R.string.ux2_voice_cancel),
                onClick = onDismiss,
                enabled = !saving,
            )
        },
    )
}

@Composable
private fun VoiceTag(
    text: String,
    container: Color,
    content: Color,
    @DrawableRes icon: Int? = null,
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
private fun localeLabel(locale: String): String = when (locale.trim().lowercase()) {
    "zh", "zh-cn", "zh_cn" -> stringResource(R.string.ux2_voice_locale_zh)
    else -> locale
}

@Composable
private fun VoiceGenderGroup.label(): String = stringResource(
    when (this) {
        VoiceGenderGroup.Narrator -> R.string.ux2_voice_group_narrator
        VoiceGenderGroup.Male -> R.string.ux2_voice_group_male
        VoiceGenderGroup.Female -> R.string.ux2_voice_group_female
        VoiceGenderGroup.Other -> R.string.ux2_voice_group_other
    },
)

@DrawableRes
private fun VoiceGenderGroup.icon(): Int = when (this) {
    VoiceGenderGroup.Narrator -> ForgeIcons.BookOpen
    VoiceGenderGroup.Male -> ForgeIcons.UserRound
    VoiceGenderGroup.Female -> ForgeIcons.UserRound
    VoiceGenderGroup.Other -> ForgeIcons.Mic
}

private fun formatSpeed(value: Double): String =
    String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')

// ---------------------------------------------------------------------------
// Preview：正常（未锁定）/ 已锁定 / 搜索无结果（深色 200%）。
// ---------------------------------------------------------------------------

private fun previewVoices(): List<VoiceCategoryUi> = listOf(
    VoiceCategoryUi(
        category = "narrator_male",
        label = "【旁白】沉稳大气播音腔",
        voiceId = "59cb5986671546eaa6ca8ae6f29f6d22",
    ),
    VoiceCategoryUi(
        category = "male_lead_steady",
        label = "【男主】青年·沉稳口语",
        voiceId = "voice-male-lead-steady",
    ),
    VoiceCategoryUi(
        category = "male_brother",
        label = "【男配】浑厚郑重·兄长/挚友型",
        voiceId = "voice-male-brother",
        note = "样音语速偏慢，已默认按 1.15 倍速合成",
        speed = 1.15,
    ),
    VoiceCategoryUi(
        category = "female_lead_gentle",
        label = "【女主】清冷温柔",
        voiceId = "voice-female-lead",
    ),
    VoiceCategoryUi(
        category = "female_young",
        label = "【女配/女主】青年女性·傲娇知性",
        voiceId = "voice-female-young",
    ),
    VoiceCategoryUi(
        category = "male_elder",
        label = "【老年】苍劲·不限身份",
        voiceId = "voice-male-elder",
    ),
)

private fun previewVoiceState(
    locked: Boolean = false,
    query: String = "",
    selectedCategory: String? = "male_brother",
    pendingLockedConfirm: Boolean = false,
): VoicePickerUiState = buildVoicePickerUiState(
    roleId = "role_0001",
    roleName = "沈砚",
    currentCategory = "male_lead_steady",
    currentVoiceId = "voice-male-lead-steady",
    locked = locked,
    voices = previewVoices(),
    query = query,
    selectedCategory = selectedCategory,
).copy(pendingLockedConfirm = pendingLockedConfirm)

@Preview(name = "VoicePicker Forge", showBackground = true, backgroundColor = 0xFFF7F9F8)
@Composable
private fun VoicePickerForgePreview() {
    AudioForgeTheme(dynamicColor = false) {
        Surface {
            VoicePickerSheetContent(state = previewVoiceState())
        }
    }
}

@Preview(
    name = "VoicePicker Locked Dark 200%",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun VoicePickerLockedPreview() {
    AudioForgeTheme(darkTheme = true, dynamicColor = false) {
        Surface {
            VoicePickerSheetContent(state = previewVoiceState(locked = true))
        }
    }
}

@Preview(name = "VoicePicker No Results", showBackground = true, backgroundColor = 0xFFF7F9F8)
@Composable
private fun VoicePickerNoResultsPreview() {
    AudioForgeTheme(dynamicColor = false) {
        Surface {
            VoicePickerSheetContent(
                state = previewVoiceState(query = "不存在的声音", selectedCategory = null),
            )
        }
    }
}
