package com.z1pendant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Fill past 100 %: a darker shade of the theme blue. */
private val OverBlue = Color(0xFF0369A1)
private val TrackColor = Color(0xFF0F1A2E)

/**
 * Override bar for the drawer (feed, spindle): a rounded track and a fill, no handle, no end dot.
 * The part of the fill past [base] (100 %) is darker; the end of the fill is always rounded like
 * the track. Drag anywhere on it: the value moves with the finger from where it was (no jump to
 * the touch point, and a tap changes nothing). [onDrag] gets the raw value; the caller snaps,
 * clamps and shows it, and sends on [onRelease].
 */
@Composable
fun OverrideBar(
    value: Float, base: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean,
    onDrag: (Float) -> Unit, onRelease: () -> Unit, modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    val current by rememberUpdatedState(value)
    val drag by rememberUpdatedState(onDrag)
    val release by rememberUpdatedState(onRelease)
    val span = range.endInclusive - range.start
    val fill = MaterialTheme.colorScheme.primary
    val a = if (enabled) 1f else 0.35f
    Box(
        modifier.height(40.dp).onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var raw = 0f
                detectHorizontalDragGestures(
                    onDragStart = { raw = current },
                    onDragEnd = { release() },
                    onDragCancel = { release() },
                ) { change, dx ->
                    change.consume()
                    raw = (raw + dx / widthPx * span).coerceIn(range.start, range.endInclusive)
                    drag(raw)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(20.dp)) {
            val h = size.height
            val r = CornerRadius(h / 2)
            fun xOf(v: Float) = ((v - range.start) / span).coerceIn(0f, 1f) * size.width
            drawRoundRect(TrackColor, cornerRadius = r)
            val x = xOf(value)
            if (x <= 0f) return@Canvas
            val w = maxOf(x, h)                            // a sliver still draws as a round cap
            val xb = xOf(base)
            if (value > base && xb > 0f) {
                // Past 100 %: the whole fill in the darker blue (rounded end), the part up to 100 % on top
                // in the normal blue, round on the left and straight where the two meet.
                drawRoundRect(OverBlue.copy(alpha = a), size = Size(w, h), cornerRadius = r)
                val p = Path().apply {
                    addRoundRect(RoundRect(0f, 0f, xb, h, topLeftCornerRadius = r, topRightCornerRadius = CornerRadius.Zero,
                        bottomRightCornerRadius = CornerRadius.Zero, bottomLeftCornerRadius = r))
                }
                drawPath(p, fill.copy(alpha = a))
            } else {
                drawRoundRect(fill.copy(alpha = a), size = Size(w, h), cornerRadius = r)
            }
        }
    }
}

/**
 * The % at the right of an override row. Blue when it isn't 100 % (tap = back to 100 %), white
 * at 100 % (nothing to reset). null = off ("—").
 */
@Composable
fun OverridePercent(percent: Int?, enabled: Boolean, onReset: () -> Unit) {
    val off100 = enabled && percent != null && percent != 100
    Text(percent?.let { "$it%" } ?: "—", fontFamily = FontFamily.Monospace, fontSize = 15.sp, maxLines = 1,
        textAlign = TextAlign.End,
        color = if (off100) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f),
        fontWeight = if (off100) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.width(60.dp).then(if (off100) Modifier.clickable(onClick = onReset) else Modifier)
            .padding(vertical = 10.dp))
}
