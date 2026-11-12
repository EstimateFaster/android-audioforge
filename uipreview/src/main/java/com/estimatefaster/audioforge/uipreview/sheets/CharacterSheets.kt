package com.estimatefaster.audioforge.uipreview.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.uipreview.R
import com.estimatefaster.audioforge.uipreview.data.Character
import com.estimatefaster.audioforge.uipreview.data.FakeData
import com.estimatefaster.audioforge.uipreview.design.InlineSearchField
import com.estimatefaster.audioforge.uipreview.design.ListRow
import com.estimatefaster.audioforge.uipreview.design.PrimaryButton
import com.estimatefaster.audioforge.uipreview.design.RowDivider
import com.estimatefaster.audioforge.uipreview.design.SecondaryButton
import com.estimatefaster.audioforge.uipreview.design.SheetHeader
import com.estimatefaster.audioforge.uipreview.design.StatusChip
import com.estimatefaster.audioforge.uipreview.design.Tone
import com.estimatefaster.audioforge.uipreview.icons.Ms
import com.estimatefaster.audioforge.uipreview.nav.RoleAvatar
import kotlin.math.roundToInt

@Composable
fun VoicePickerSheet(
    character: Character,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(character.voice?.substringBefore(" ·") ?: "") }
    val voices = remember(query) {
        FakeData.voices.filter { (name, desc) ->
            query.isBlank() || name.contains(query, ignoreCase = true) || desc.contains(query, ignoreCase = true)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SheetHeader(
                icon = Ms.RecordVoiceOver,
                title = "选择音色",
                subtitle = "为 ${character.name} 选择声音",
                onClose = onDismiss,
            )
            Row(
                modifier = Modifier.padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoleAvatar(name = character.name, size = 36)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(character.name, style = MaterialTheme.typography.titleSmallEmphasized)
                    Text(
                        text = character.voice ?: "当前未分配音色",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (character.locked) {
                    StatusChip(text = "已锁定", tone = Tone.Warn, icon = Ms.Lock)
                }
            }
            Spacer(Modifier.height(12.dp))
            InlineSearchField(
                value = query,
                onValueChange = { query = it },
                hint = "搜索音色",
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.height(320.dp)) {
                items(voices, key = { it.first }) { (name, desc) ->
                    ListRow(
                        title = name,
                        subtitle = desc,
                        onClick = { selected = name },
                        showChevron = false,
                        trailingContent = {
                            Icon(
                                painter = painterResource(if (selected == name) Ms.CheckCircle else Ms.RadioButtonUnchecked),
                                contentDescription = null,
                                tint = if (selected == name) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(22.dp),
                            )
                        },
                    )
                    RowDivider()
                }
            }
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                PrimaryButton(
                    text = if (selected.equals(character.voice?.substringBefore(" ·"), ignoreCase = true)) {
                        "与当前相同"
                    } else {
                        "使用这个音色"
                    },
                    icon = Ms.Check,
                    enabled = selected.isNotBlank() &&
                        !selected.equals(character.voice?.substringBefore(" ·"), ignoreCase = true),
                    onClick = { onSave(selected) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun TuningSheet(
    character: Character,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var speed by remember { mutableFloatStateOf(1.0f) }
    var volume by remember { mutableFloatStateOf(1.0f) }
    var pitch by remember { mutableFloatStateOf(0f) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
        ) {
            SheetHeader(
                icon = Ms.Tune,
                title = "角色微调",
                subtitle = "${character.name} · ${character.voice ?: "未分配音色"}",
                onClose = onDismiss,
            )
            Spacer(Modifier.height(8.dp))
            TuningSlider(
                label = "语速",
                value = speed,
                range = 0.5f..2f,
                valueText = String.format(java.util.Locale.US, "%.2f", speed),
                onChange = { speed = it },
            )
            TuningSlider(
                label = "音量",
                value = volume,
                range = 0f..2f,
                valueText = String.format(java.util.Locale.US, "%.2f", volume),
                onChange = { volume = it },
            )
            TuningSlider(
                label = "音调",
                value = pitch,
                range = -12f..12f,
                valueText = String.format(java.util.Locale.US, "%+.1f", pitch),
                onChange = { pitch = it },
            )
            Spacer(Modifier.height(12.dp))
            Row {
                SecondaryButton(
                    text = "恢复默认",
                    icon = Ms.RestartAlt,
                    onClick = {
                        speed = 1f
                        volume = 1f
                        pitch = 0f
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = "保存参数",
                icon = Ms.Check,
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TuningSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
