package com.estimatefaster.audioforge.uipreview.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.uipreview.data.PlayerState
import com.estimatefaster.audioforge.uipreview.design.AppTopBar
import com.estimatefaster.audioforge.uipreview.design.SecondaryButton
import com.estimatefaster.audioforge.uipreview.design.SectionLabel
import com.estimatefaster.audioforge.uipreview.icons.Ms
import com.estimatefaster.audioforge.uipreview.nav.AppScaffold
import com.estimatefaster.audioforge.uipreview.nav.Navigator
import java.util.Locale
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun GenerationScreen(navigator: Navigator, player: PlayerState) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var llmConcurrency by remember { mutableFloatStateOf(1f) }
    var ttsConcurrency by remember { mutableFloatStateOf(4f) }
    var segmentLimit by remember { mutableFloatStateOf(120f) }
    var coverage by remember { mutableFloatStateOf(0.97f) }
    var pause by remember { mutableFloatStateOf(350f) }

    AppScaffold(
        navigator = navigator,
        player = player,
        showNavBar = false,
        showMiniPlayer = true,
        snackbarHostState = snackbarHostState,
        topBar = {
            AppTopBar(
                title = "生成默认值",
                subtitle = "新建项目时的初始参数",
                scrollBehavior = scrollBehavior,
                onBack = { navigator.pop() },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item(key = "concurrency") {
                SectionLabel(text = "并发", spec = "Slider · 48dp touch")
                ParamSlider(
                    label = "文本并发",
                    value = llmConcurrency,
                    range = 1f..4f,
                    steps = 2,
                    valueText = "${llmConcurrency.roundToInt()} 路",
                    onChange = { llmConcurrency = it.roundToInt().toFloat() },
                )
                ParamSlider(
                    label = "语音并发",
                    value = ttsConcurrency,
                    range = 1f..8f,
                    steps = 6,
                    valueText = "${ttsConcurrency.roundToInt()} 路",
                    onChange = { ttsConcurrency = it.roundToInt().toFloat() },
                )
            }
            item(key = "segment") {
                SectionLabel(text = "分段与覆盖率")
                ParamSlider(
                    label = "分段字数上限",
                    value = segmentLimit,
                    range = 20f..300f,
                    steps = 27,
                    valueText = "${(segmentLimit / 10).roundToInt() * 10} 字",
                    onChange = { segmentLimit = (it / 10).roundToInt() * 10f },
                )
                ParamSlider(
                    label = "最低覆盖率",
                    value = coverage,
                    range = 0.5f..1f,
                    valueText = String.format(Locale.US, "%.2f", coverage),
                    onChange = { coverage = (it * 100).roundToInt() / 100f },
                )
            }
            item(key = "pause") {
                SectionLabel(text = "停顿")
                ParamSlider(
                    label = "旁白后停顿",
                    value = pause,
                    range = 0f..3000f,
                    steps = 59,
                    valueText = "${pause.roundToInt()} ms",
                    onChange = { pause = (it / 50).roundToInt() * 50f },
                )
            }
            item(key = "reset") {
                SecondaryButton(
                    text = "恢复默认值",
                    icon = Ms.RestartAlt,
                    onClick = {
                        llmConcurrency = 1f
                        ttsConcurrency = 4f
                        segmentLimit = 120f
                        coverage = 0.97f
                        pause = 350f
                        scope.launch { snackbarHostState.showSnackbar("已恢复默认值") }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
    steps: Int = 0,
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
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
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
