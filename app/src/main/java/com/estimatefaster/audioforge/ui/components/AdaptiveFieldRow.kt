package com.estimatefaster.audioforge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** 字体放大到该倍数后，并排表单改为纵向，避免输入框标签被截断。 */
const val STACKED_FORM_FONT_SCALE: Float = 1.5f

/** 纯函数：字体缩放是否已大到需要把并排表单改为纵向。 */
fun shouldStackFormFields(fontScale: Float): Boolean =
    fontScale >= STACKED_FORM_FONT_SCALE

/**
 * 自适应表单行：字体正常时并排、每个槽位等宽；字体放大（大于等于 1.5x）时纵向堆叠。
 * 只影响布局，不改变字段顺序与无障碍语义。
 *
 * @param fields 每个槽位的字段内容；顺序即 TalkBack 的朗读顺序。
 */
@Composable
fun AdaptiveFieldRow(vararg fields: @Composable () -> Unit) {
    if (shouldStackFormFields(LocalDensity.current.fontScale)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            fields.forEach { field ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    field()
                }
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            fields.forEach { field ->
                Box(modifier = Modifier.weight(1f)) {
                    field()
                }
            }
        }
    }
}
