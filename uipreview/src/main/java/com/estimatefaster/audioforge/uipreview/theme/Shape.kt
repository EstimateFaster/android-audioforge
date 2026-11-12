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

/** 封面统一圆角：所有尺寸共用，保证缩略图与播放器封面是同一种「书」的轮廓。 */
internal val CoverShape: CornerBasedShape = RoundedCornerShape(14.dp)
