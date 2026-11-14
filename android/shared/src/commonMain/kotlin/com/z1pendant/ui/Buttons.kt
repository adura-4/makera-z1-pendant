package com.z1pendant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Material 3's OutlinedButton as it looked in material3 1.3 (the app's original look): primary
 * (blue) label and an `outline` border. material3 1.4, which Compose Multiplatform 1.9 brings,
 * switched to a grey label and a darker border.
 */
@Composable
fun PendantOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.material3.OutlinedButton(
        onClick = onClick, modifier = modifier, enabled = enabled, shape = shape,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = cs.primary,
            disabledContentColor = cs.onSurface.copy(alpha = 0.38f),
        ),
        border = BorderStroke(1.dp, if (enabled) cs.outline else cs.onSurface.copy(alpha = 0.12f)),
        contentPadding = contentPadding, content = content,
    )
}
