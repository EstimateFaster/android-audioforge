@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.estimatefaster.audioforge.ui.forge

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.ui.theme.spacing

/** Lucide 图标唯一入口；装饰性图标必须传 null。 */
@Composable
fun ForgeIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        modifier = modifier.size(24.dp),
        tint = tint,
    )
}

@Composable
fun ForgeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @DrawableRes navigationIcon: Int? = null,
    navigationDescription: String? = null,
    onNavigation: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    modifier = Modifier.semantics { heading() },
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        navigationIcon = {
            if (navigationIcon != null && onNavigation != null) {
                IconButton(onClick = onNavigation, modifier = Modifier.size(48.dp)) {
                    ForgeIcon(navigationIcon, navigationDescription)
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
fun ForgeIconAction(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Boolean = true,
) {
    if (container) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = modifier.size(48.dp),
            content = { ForgeIcon(icon, contentDescription) },
        )
    } else {
        IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
            ForgeIcon(icon, contentDescription)
        }
    }
}

/** 唯一主操作：图标 + 文案的标准 Button，不再用可点击文字冒充按钮。 */
@Composable
fun ForgePrimaryAction(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled && !loading,
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(
            horizontal = MaterialTheme.spacing.lg,
            vertical = MaterialTheme.spacing.md,
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        if (loading) {
            LoadingIndicator(modifier = Modifier.size(20.dp))
        } else {
            ForgeIcon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(MaterialTheme.spacing.sm))
        Text(label, style = MaterialTheme.typography.labelLargeEmphasized)
    }
}

@Composable
fun ForgeSecondaryAction(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(
            horizontal = MaterialTheme.spacing.lg,
            vertical = MaterialTheme.spacing.sm,
        ),
        colors = if (danger) {
            ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
        },
    ) {
        ForgeIcon(icon, contentDescription = null)
        Spacer(Modifier.width(MaterialTheme.spacing.xs))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun ForgeCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentPadding: PaddingValues = PaddingValues(MaterialTheme.spacing.lg),
    content: @Composable ColumnScope.() -> Unit,
) {
    val clickModifier = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(clickModifier)
            .animateContentSize(),
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            content = content,
        )
    }
}

/** 带 Lucide leading icon 的列表行；右侧操作必须是 IconButton/Button，不是文字。 */
@Composable
fun ForgeListRow(
    @DrawableRes icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailingText: String? = null,
    onClick: (() -> Unit)? = null,
    @DrawableRes trailingIcon: Int? = null,
    trailingDescription: String? = null,
    onTrailingClick: (() -> Unit)? = null,
) {
    ForgeCard(modifier = modifier, onClick = onClick, contentPadding = PaddingValues(MaterialTheme.spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(48.dp),
            ) {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    ForgeIcon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmallEmphasized)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (trailingText != null) {
                Text(trailingText, style = MaterialTheme.typography.labelMedium)
            }
            if (trailingIcon != null && onTrailingClick != null) {
                ForgeIconAction(trailingIcon, trailingDescription ?: "", onTrailingClick, container = false)
            }
        }
    }
}

@Composable
fun ForgeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        modifier = modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.large,
        leadingIcon = icon?.let { { ForgeIcon(it, contentDescription = null, modifier = Modifier.size(18.dp)) } },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    )
}

@Composable
fun ForgeFab(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.size(64.dp),
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        ForgeIcon(icon, contentDescription, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
fun ForgeExtendedFab(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        icon = { ForgeIcon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary) },
        text = { Text(label, style = MaterialTheme.typography.labelLargeEmphasized) },
    )
}

@Composable
fun ForgeSegmented(
    options: List<Pair<Int, String>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (icon, label) ->
            androidx.compose.material3.SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(index, options.size),
                icon = { ForgeIcon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun ForgeDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    @DrawableRes icon: Int = ForgeIcons.Info,
    danger: Boolean = false,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        icon = { ForgeIcon(icon, contentDescription = null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) },
        title = { Text(title, style = MaterialTheme.typography.titleLargeEmphasized) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            if (danger) {
                ForgeSecondaryAction(ForgeIcons.Trash, confirmLabel, onConfirm, danger = true)
            } else {
                ForgePrimaryAction(ForgeIcons.Check, confirmLabel, onConfirm)
            }
        },
        dismissButton = {
            ForgeSecondaryAction(ForgeIcons.Close, "取消", onDismiss)
        },
    )
}

@Composable
fun ForgeSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @DrawableRes actionIcon: Int? = null,
    actionDescription: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.semantics { heading() })
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (actionIcon != null && onAction != null) {
            ForgeIconAction(actionIcon, actionDescription ?: "", onAction, container = false)
        }
    }
}

@Composable
fun ForgeWavyProgress(progress: Float, modifier: Modifier = Modifier) {
    LinearWavyProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
}

@Composable
fun ForgeLoading(label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(MaterialTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LoadingIndicator(modifier = Modifier.size(48.dp))
        Spacer(Modifier.width(MaterialTheme.spacing.md))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ForgeEmpty(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    @DrawableRes actionIcon: Int? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(MaterialTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = MaterialTheme.shapes.extraExtraLarge, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(96.dp)) {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                ForgeIcon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.width(MaterialTheme.spacing.lg))
        Text(title, style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center)
        Spacer(Modifier.width(MaterialTheme.spacing.sm))
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionIcon != null && actionLabel != null && onAction != null) {
            Spacer(Modifier.width(MaterialTheme.spacing.lg))
            ForgePrimaryAction(actionIcon, actionLabel, onAction)
        }
    }
}

@Composable
fun ForgeError(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    retryLabel: String = "重试",
) {
    Column(
        modifier = modifier.fillMaxSize().padding(MaterialTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = MaterialTheme.shapes.extraExtraLarge, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.size(96.dp)) {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                ForgeIcon(ForgeIcons.AlertCircle, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        Spacer(Modifier.width(MaterialTheme.spacing.lg))
        Text(message, style = MaterialTheme.typography.bodyLargeEmphasized, textAlign = TextAlign.Center)
        if (onRetry != null) {
            Spacer(Modifier.width(MaterialTheme.spacing.lg))
            ForgePrimaryAction(ForgeIcons.Refresh, retryLabel, onRetry)
        }
    }
}

@Composable
fun ForgeSheetHeader(
    @DrawableRes icon: Int,
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
    ) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(48.dp)) {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                ForgeIcon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLargeEmphasized)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ForgeIconAction(ForgeIcons.Close, "关闭", onClose, container = false)
    }
}
