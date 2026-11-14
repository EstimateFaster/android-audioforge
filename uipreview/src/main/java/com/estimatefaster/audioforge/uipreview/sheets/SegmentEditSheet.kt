package com.estimatefaster.audioforge.uipreview.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.uipreview.R
import com.estimatefaster.audioforge.uipreview.data.FakeData
import com.estimatefaster.audioforge.uipreview.data.Segment
import com.estimatefaster.audioforge.uipreview.design.PrimaryButton
import com.estimatefaster.audioforge.uipreview.design.SecondaryButton
import com.estimatefaster.audioforge.uipreview.design.SheetHeader
import com.estimatefaster.audioforge.uipreview.icons.Ms

private val emotions = listOf("平静", "低沉", "克制", "疲惫", "严厉", "讥讽", "追忆", "温柔")

@Composable
fun SegmentEditSheet(
    segment: Segment,
    onDismiss: () -> Unit,
    onSave: (role: String, emotion: String?, text: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var role by remember { mutableStateOf(segment.role) }
    var emotion by remember { mutableStateOf(segment.emotion ?: "平静") }
    var text by remember { mutableStateOf(segment.text) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            SheetHeader(
                icon = Ms.Edit,
                title = "编辑片段",
                subtitle = "第 ${segment.index + 1} 段 · ${segment.role}",
                onClose = onDismiss,
            )
            Spacer(Modifier.height(8.dp))
            Text("角色", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FakeData.characters.map { it.name }.forEach { name ->
                    FilterChip(
                        shape = CircleShape,
                        selected = role == name,
                        onClick = { role = name },
                        label = { Text(name) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("情绪", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                emotions.forEach { item ->
                    FilterChip(
                        shape = CircleShape,
                        selected = emotion == item,
                        onClick = { emotion = item },
                        label = { Text(item) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(2000) },
                label = { Text("台词") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 8,
                shape = MaterialTheme.shapes.large,
                supportingText = { Text("${text.length} / 2000") },
                isError = text.isBlank(),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(
                    text = stringResource(R.string.action_cancel),
                    icon = Ms.Close,
                    onClick = onDismiss,
                )
                Spacer(Modifier.width(4.dp))
                PrimaryButton(
                    text = stringResource(R.string.action_save),
                    icon = Ms.Check,
                    enabled = text.isNotBlank(),
                    onClick = { onSave(role, emotion, text.trim()) },
                )
            }
        }
    }
}
