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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Icons offered in the hotkey editor. Anything else can be an emoji / short text ("tx:"). */
val HotkeyIcons: Map<String, ImageVector> = linkedMapOf(
    "Home" to Icons.Filled.Home,
    "LockOpen" to Icons.Filled.LockOpen,
    "GpsFixed" to Icons.Filled.GpsFixed,
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
