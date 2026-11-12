package com.estimatefaster.audioforge.uipreview.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * M3 Expressive 形状刻度。
 *
 * 比标准 M3 更「圆」：卡片/列表容器走 large(24) 与 extraLarge(32)，
 * Sheet 走 extraExtraLarge(40)，FAB 由组件自己按圆形处理。
 */
internal val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
    largeIncreased = RoundedCornerShape(20.dp),
    extraLargeIncreased = RoundedCornerShape(36.dp),
    extraExtraLarge = RoundedCornerShape(40.dp),
)

/** 封面/插图用的不对称圆角，避免所有容器都是同一个圆。 */
internal val CoverShape: CornerBasedShape = RoundedCornerShape(
    topStart = 28.dp,
    topEnd = 12.dp,
    bottomEnd = 28.dp,
    bottomStart = 12.dp,
)
