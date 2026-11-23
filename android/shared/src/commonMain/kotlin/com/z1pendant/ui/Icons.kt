package com.z1pendant.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Bracket in plan view with the 3D probe in side elevation; see the anchor-calibration design spec. */
val CalAnchorIcon: ImageVector by lazy {
    ImageVector.Builder("CalAnchor", 24.dp, 24.dp, 24f, 24f).apply {
        // Even-odd fill is what makes the two pin holes knock out.
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(2f, 2f); lineTo(6.5f, 2f); lineTo(6.5f, 10.6f)
            arcTo(1.3f, 1.3f, 0f, false, false, 6.5f, 13.2f)
            lineTo(6.5f, 16.7f)
            arcTo(0.8f, 0.8f, 0f, true, false, 7.3f, 17.5f)
            lineTo(10.8f, 17.5f)
            arcTo(1.3f, 1.3f, 0f, false, false, 13.4f, 17.5f)
            lineTo(22f, 17.5f); lineTo(22f, 22f); lineTo(2f, 22f); close()
            moveTo(3.25f, 4.5f)
            arcToRelative(1f, 1f, 0f, true, false, 2f, 0f)
            arcToRelative(1f, 1f, 0f, true, false, -2f, 0f); close()
            moveTo(18.75f, 19.75f)
            arcToRelative(1f, 1f, 0f, true, false, 2f, 0f)
            arcToRelative(1f, 1f, 0f, true, false, -2f, 0f); close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(13.55f, 2f); horizontalLineToRelative(1.4f); verticalLineToRelative(2f)
            horizontalLineToRelative(-1.4f); close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(11.85f, 4f); horizontalLineTo(16.65f)
            arcTo(0.7f, 0.7f, 0f, false, true, 17.35f, 4.7f)
            verticalLineTo(9.9f)
            arcTo(0.7f, 0.7f, 0f, false, true, 16.65f, 10.6f)
            horizontalLineTo(11.85f)
            arcTo(0.7f, 0.7f, 0f, false, true, 11.15f, 9.9f)
            verticalLineTo(4.7f)
            arcTo(0.7f, 0.7f, 0f, false, true, 11.85f, 4f); close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(13.65f, 10.6f); lineTo(14.85f, 10.6f); lineTo(14.5f, 13.8f)
            lineTo(14f, 13.8f); close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(14.25f, 13.8f)
            arcToRelative(0.9f, 0.9f, 0f, true, false, 0f, 1.8f)
            arcToRelative(0.9f, 0.9f, 0f, true, false, 0f, -1.8f); close()
        }
    }.build()
}

/** Icons offered in the hotkey editor. Anything else can be an emoji / short text ("tx:"). */
val HotkeyIcons: Map<String, ImageVector> = linkedMapOf(
    "Home" to Icons.Filled.Home,
    "LockOpen" to Icons.Filled.LockOpen,
    "GpsFixed" to Icons.Filled.GpsFixed,
    "CalAnchor" to CalAnchorIcon,
    "CenterFocusStrong" to Icons.Filled.CenterFocusStrong,
    "MyLocation" to Icons.Filled.MyLocation,
    "VerticalAlignTop" to Icons.Filled.VerticalAlignTop,
    "VerticalAlignBottom" to Icons.Filled.VerticalAlignBottom,
    "ArrowUpward" to Icons.Filled.ArrowUpward,
    "ArrowDownward" to Icons.Filled.ArrowDownward,
    "SwapHoriz" to Icons.Filled.SwapHoriz,
    "Lightbulb" to Icons.Filled.Lightbulb,
    "Cyclone" to Icons.Filled.Cyclone,
    "Highlight" to Icons.Filled.Highlight,
    "Air" to Icons.Filled.Air,
    "CleaningServices" to Icons.Filled.CleaningServices,
    "Cyclone" to Icons.Filled.Cyclone,
    "Toys" to Icons.Filled.Toys,
    "Block" to Icons.Filled.Block,
    "Stop" to Icons.Filled.Stop,
    "PlayArrow" to Icons.Filled.PlayArrow,
    "Pause" to Icons.Filled.Pause,
    "RestartAlt" to Icons.Filled.RestartAlt,
    "Refresh" to Icons.Filled.Refresh,
    "Speed" to Icons.Filled.Speed,
    "Straighten" to Icons.Filled.Straighten,
    "Adjust" to Icons.Filled.Adjust,
    "CropFree" to Icons.Filled.CropFree,
    "Layers" to Icons.Filled.Layers,
    "Build" to Icons.Filled.Build,
    "Handyman" to Icons.Filled.Handyman,
    "Hardware" to Icons.Filled.Hardware,
    "Bolt" to Icons.Filled.Bolt,
    "Flag" to Icons.Filled.Flag,
    "Star" to Icons.Filled.Star,
    "Timer" to Icons.Filled.Timer,
    "Warning" to Icons.Filled.Warning,
    "PowerSettingsNew" to Icons.Filled.PowerSettingsNew,
    "Videocam" to Icons.Filled.Videocam,
    "Visibility" to Icons.Filled.Visibility,
)

@Composable
fun HotkeyIcon(icon: String, size: Dp = 26.dp, tint: Color = LocalContentColor.current, modifier: Modifier = Modifier) {
    when {
        icon.startsWith("mi:") -> {
            val v = HotkeyIcons[icon.removePrefix("mi:")] ?: Icons.Filled.Star
            Icon(v, contentDescription = null, tint = tint, modifier = modifier.then(Modifier.size(size)))
        }
        else -> Text(icon.removePrefix("tx:").take(3), fontSize = (size.value * 0.8f).sp, color = tint,
            textAlign = TextAlign.Center, modifier = modifier)
    }
}
