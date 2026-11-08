package com.estimatefaster.audioforge.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.R

/** 角色头像的可选色槽数量（主/次/第三容器 + 表面变体）。 */
const val ROLE_AVATAR_PALETTE_SIZE: Int = 4

/** 取名字的第一个 Unicode 码点；空名字用问号兜底。 */
fun avatarInitial(name: String): String {
    val normalized = name.trim()
    if (normalized.isEmpty()) return "?"
    val codePoint = normalized.codePointAt(0)
    return String(Character.toChars(codePoint))
}

/**
 * 按名字稳定选择头像色槽。使用 FNV-1a（按码点），不依赖平台默认 hash 实现；
 * 相同名字在任何进程/版本都会落到同一色槽。
 */
fun avatarColorIndex(
    name: String,
    paletteSize: Int = ROLE_AVATAR_PALETTE_SIZE,
): Int {
    require(paletteSize > 0) { "paletteSize must be positive" }
    val normalized = name.trim()
    if (normalized.isEmpty()) return 0
    var hash = -2128831035 // 0x811C9DC5
    var index = 0
    while (index < normalized.length) {
        val codePoint = normalized.codePointAt(index)
        hash = hash xor codePoint
        hash *= 0x01000193
        index += Character.charCount(codePoint)
    }
    return (hash and Int.MAX_VALUE) % paletteSize
}

/**
 * 首字头像：不加载图片，避免 Bitmap；容器色按名字哈希稳定选择。
 *
 * @param size 默认 48dp；若把头像放进可点区域，请保持 ≥48dp。
 * @param contentDescription 为 null 时按名字生成“角色头像：xxx”。
 */
@Composable
fun RoleAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    contentDescription: String? = null,
) {
    val displayName = name.trim()
    val (container, content) = avatarColors(avatarColorIndex(displayName))
    val description = contentDescription?.takeIf { it.isNotBlank() }
        ?: if (displayName.isEmpty()) {
            stringResource(R.string.role_avatar_unknown)
        } else {
            stringResource(R.string.role_avatar_content_description, displayName)
        }

    // 头像字号随尺寸固定，但用 toSp 抵消系统字体放大，避免 200% 时撑破圆形。
    val fontSize = with(LocalDensity.current) { (size.toPx() * 0.4f).toSp() }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .clearAndSetSemantics { this.contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = avatarInitial(displayName),
            color = content,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun avatarColors(index: Int): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (index % ROLE_AVATAR_PALETTE_SIZE) {
        0 -> scheme.primaryContainer to scheme.onPrimaryContainer
        1 -> scheme.secondaryContainer to scheme.onSecondaryContainer
        2 -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        else -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
}

@Composable
private fun RoleAvatarSamples() {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        RoleAvatar(name = "林黛玉", size = 56.dp)
        RoleAvatar(name = "Narrator", size = 48.dp)
        RoleAvatar(name = "🎙️主播", size = 64.dp)
    }
}

@Preview(name = "RoleAvatar Light", showBackground = true)
@Composable
private fun RoleAvatarLightPreview() {
    ComponentPreview(darkTheme = false) {
        RoleAvatarSamples()
    }
}

@Preview(
    name = "RoleAvatar Dark 200%",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun RoleAvatarDarkPreview() {
    ComponentPreview(darkTheme = true) {
        RoleAvatarSamples()
    }
}
