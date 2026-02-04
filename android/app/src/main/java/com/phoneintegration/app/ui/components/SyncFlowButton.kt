package com.phoneintegration.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.ui.theme.Spacing
import com.phoneintegration.app.ui.theme.SyncFlowTypography

/**
 * Button size variants
 */
enum class ButtonSize(val height: Dp, val horizontalPadding: Dp, val iconSize: Dp) {
    Small(32.dp, 12.dp, 16.dp),
    Medium(40.dp, 16.dp, 20.dp),
    Large(48.dp, 24.dp, 24.dp)
}

/**
 * Primary filled button
 */
@Composable
fun SyncFlowPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    size: ButtonSize = ButtonSize.Medium
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val containerColor = MaterialTheme.colorScheme.primary
    val pressedColor = containerColor.copy(alpha = 0.85f)

    val animatedColor by animateColorAsState(
        targetValue = if (isPressed) pressedColor else containerColor,
        animationSpec = tween(100),
        label = "button_color"
    )

    Button(
        onClick = { if (!loading) onClick() },
        modifier = modifier.height(size.height),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(Spacing.radiusSm),
        colors = ButtonDefaults.buttonColors(
            containerColor = animatedColor,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        ),
        contentPadding = PaddingValues(
            horizontal = size.horizontalPadding,
            vertical = 0.dp
        ),
        interactionSource = interactionSource
    ) {
        ButtonContent(
            text = text,
            loading = loading,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            iconSize = size.iconSize
        )
    }
}

/**
 * Secondary outlined button
 */
@Composable
fun SyncFlowSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    size: ButtonSize = ButtonSize.Medium
) {
    OutlinedButton(
        onClick = { if (!loading) onClick() },
        modifier = modifier.height(size.height),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(Spacing.radiusSm),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        ),
        border = BorderStroke(
            1.dp,
            if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outline.copy(alpha = 0.38f)
        ),
        contentPadding = PaddingValues(
            horizontal = size.horizontalPadding,
            vertical = 0.dp
        )
    ) {
        ButtonContent(
            text = text,
            loading = loading,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            iconSize = size.iconSize,
            contentColor = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * Ghost/text button
 */
@Composable
fun SyncFlowGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    size: ButtonSize = ButtonSize.Medium,
    contentColor: Color = MaterialTheme.colorScheme.primary
) {
    TextButton(
        onClick = { if (!loading) onClick() },
        modifier = modifier.height(size.height),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(Spacing.radiusSm),
        colors = ButtonDefaults.textButtonColors(
            contentColor = contentColor,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        ),
        contentPadding = PaddingValues(
            horizontal = size.horizontalPadding,
            vertical = 0.dp
        )
    ) {
        ButtonContent(
            text = text,
            loading = loading,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            iconSize = size.iconSize,
            contentColor = contentColor
        )
    }
}

/**
 * Danger button for destructive actions
 */
@Composable
fun SyncFlowDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
    size: ButtonSize = ButtonSize.Medium
) {
    Button(
        onClick = { if (!loading) onClick() },
        modifier = modifier.height(size.height),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(Spacing.radiusSm),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        ),
        contentPadding = PaddingValues(
            horizontal = size.horizontalPadding,
            vertical = 0.dp
        )
    ) {
        ButtonContent(
            text = text,
            loading = loading,
            leadingIcon = leadingIcon,
            iconSize = size.iconSize
        )
    }
}

/**
 * Icon button with consistent styling
 */
@Composable
fun SyncFlowIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    size: Dp = Spacing.minTouchTarget
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(size),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = tint,
            disabledContentColor = tint.copy(alpha = 0.38f)
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(Spacing.iconMd)
        )
    }
}

/**
 * Floating action button variant
 */
@Composable
fun SyncFlowFab(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    extended: Boolean = false,
    text: String = "",
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(Spacing.fabSize),
        shape = RoundedCornerShape(Spacing.radiusLg),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 6.dp,
            pressedElevation = 12.dp
        ),
        contentPadding = if (extended) {
            PaddingValues(horizontal = 20.dp)
        } else {
            PaddingValues(16.dp)
        }
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(Spacing.iconMd)
        )
        if (extended && text.isNotEmpty()) {
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = text,
                style = SyncFlowTypography.buttonSmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Internal button content layout
 */
@Composable
private fun ButtonContent(
    text: String,
    loading: Boolean,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    iconSize: Dp = 20.dp,
    contentColor: Color = Color.Unspecified
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(iconSize),
                strokeWidth = 2.dp,
                color = if (contentColor != Color.Unspecified) contentColor else MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.width(Spacing.xs))
        } else if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = if (contentColor != Color.Unspecified) contentColor else Color.Unspecified
            )
            Spacer(Modifier.width(Spacing.xs))
        }

        Text(
            text = text,
            style = SyncFlowTypography.buttonSmall,
            fontWeight = FontWeight.Medium
        )

        if (trailingIcon != null && !loading) {
            Spacer(Modifier.width(Spacing.xs))
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = if (contentColor != Color.Unspecified) contentColor else Color.Unspecified
            )
        }
    }
}
