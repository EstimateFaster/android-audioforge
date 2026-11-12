package com.estimatefaster.audioforge.uipreview.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.uipreview.design.PrimaryButton
import com.estimatefaster.audioforge.uipreview.design.SheetHeader
import com.estimatefaster.audioforge.uipreview.icons.Ms

@Composable
fun EditValueSheet(
    title: String,
    subtitle: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    icon: Int = Ms.Edit,
    password: Boolean = false,
    placeholder: String = "",
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var value by remember { mutableStateOf(initial) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            SheetHeader(icon = icon, title = title, subtitle = subtitle, onClose = onDismiss)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(title) },
                placeholder = { Text(placeholder) },
                singleLine = true,
                visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                shape = MaterialTheme.shapes.large,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "只保存在本机；换设备需要重新填写。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = "保存",
                icon = Ms.Check,
                enabled = value.isNotBlank(),
                onClick = { onSave(value) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
