package com.estimatefaster.audioforge.uipreview.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.uipreview.R
import com.estimatefaster.audioforge.uipreview.design.IconBubble
import com.estimatefaster.audioforge.uipreview.design.PrimaryButton
import com.estimatefaster.audioforge.uipreview.design.SecondaryButton
import com.estimatefaster.audioforge.uipreview.design.SheetHeader
import com.estimatefaster.audioforge.uipreview.icons.Ms
import kotlinx.coroutines.delay

@Composable
fun ImportSheet(onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var fileName by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(importing) {
        if (!importing) return@LaunchedEffect
        while (progress < 1f) {
            delay(110)
            progress = (progress + 0.07f).coerceAtMost(1f)
        }
        importing = false
        onDone(fileName ?: "")
    }

    ModalBottomSheet(
        onDismissRequest = { if (!importing) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            SheetHeader(
                icon = Ms.UploadFile,
                title = stringResource(R.string.import_title),
                subtitle = stringResource(R.string.import_subtitle),
                onClose = { if (!importing) onDismiss() },
            )
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !importing) {
                        fileName = "山海食话.txt"
                        progress = 0f
                    },
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBubble(
                        icon = if (fileName == null) Ms.NoteAdd else Ms.Description,
                        size = 48.dp,
                        iconSize = 24.dp,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = fileName ?: stringResource(R.string.import_pick),
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (fileName == null) {
                                stringResource(R.string.import_pick_desc)
                            } else {
                                "1.2 MB · 已选择，可重新挑选"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (fileName != null) {
                        Icon(
                            painter = painterResource(Ms.Refresh),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            if (importing) {
                LinearWavyProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.import_progress, (progress * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLargeEmphasized,
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
            PrimaryButton(
                text = stringResource(R.string.import_start),
                icon = Ms.PlayArrow,
                enabled = fileName != null && !importing,
                onClick = {
                    progress = 0f
                    importing = true
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                text = stringResource(R.string.action_cancel),
                icon = Ms.Close,
                enabled = !importing,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.import_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
