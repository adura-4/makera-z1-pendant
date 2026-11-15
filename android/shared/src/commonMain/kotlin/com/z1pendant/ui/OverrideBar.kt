package com.z1pendant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.filled.Close
import kotlinx.coroutines.flow.first
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
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

/** The next multiple of 5 up ([dir] > 0) or down from [v]: 121.25 → 125 / 120, 120 → 125 / 115. */
fun stepFive(v: Double, dir: Int): Double {
    val k = v / 5.0
    return if (dir > 0) (kotlin.math.floor(k + 1e-9) + 1) * 5.0 else (kotlin.math.ceil(k - 1e-9) - 1) * 5.0
}

/**
 * An override value that − / + have changed but the machine hasn't confirmed yet. [step] shows the
 * new value at once and sends it once the taps stop for [quietMs] (so five quick taps send one
 * command); [sendNow] sends straight away (bar released). The value stays shown until the status
 * reports it ([same]) or 2.5 s pass, so the row doesn't flick back to the old value meanwhile.
 */
class PendingOverride internal constructor() {
    var value by mutableStateOf<Double?>(null); internal set
    internal var tapAt by mutableStateOf(0L)
    internal var send: (Double) -> Unit = {}
    internal var waiting by mutableStateOf(false)
    fun step(v: Double) { value = v; waiting = false; tapAt = com.z1pendant.platform.nowMs() }
    fun sendNow(v: Double) { value = v; send(v); waiting = true }
}

@Composable
fun rememberPendingOverride(current: Double?, onSend: (Double) -> Unit, same: (Double, Double) -> Boolean,
                            quietMs: Long = 500): PendingOverride {
    val p = remember { PendingOverride() }
    val cur by rememberUpdatedState(current)
    p.send = onSend
    // Debounce the taps.
    LaunchedEffect(p.tapAt) {
        if (p.tapAt == 0L) return@LaunchedEffect
        kotlinx.coroutines.delay(quietMs)
        p.value?.let { p.send(it); p.waiting = true }
    }
    // Hold the sent value until the machine reports it.
    LaunchedEffect(p.waiting, p.value) {
        if (!p.waiting) return@LaunchedEffect
        val want = p.value ?: return@LaunchedEffect
        kotlinx.coroutines.withTimeoutOrNull(2500) {
            snapshotFlow { cur }.first { it != null && same(it, want) }
        }
        p.value = null; p.waiting = false
    }
    return p
}

/**
 * One override row: label · − · bar · + · %. The % is blue when it isn't 100; tapping it opens a
 * "back to 100 %?" prompt that covers this row's − … + span (Reset / ✕; a tap elsewhere closes it).
 * − and + step once per tap (no auto-repeat, so a held button can't run to the limit).
 */
@Composable
fun OverrideRow(
    label: String, dragLabel: String?, value: Float, base: Float, range: ClosedFloatingPointRange<Float>,
    enabled: Boolean, percent: Int?, onDrag: (Float) -> Unit, onRelease: () -> Unit,
    onStep: (Int) -> Unit, resetText: String, onReset: () -> Unit,
) {
    var asking by remember { mutableStateOf(false) }
    if (!enabled) asking = false
    val cs = MaterialTheme.colorScheme
    val labelW = 64.dp; val pctW = 60.dp; val gap = 6.dp
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gap)) {
            if (dragLabel != null) Text(dragLabel, modifier = Modifier.width(labelW), maxLines = 1,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = cs.primary)
            else Text(label, modifier = Modifier.width(labelW), maxLines = 1)
            StepButton("−", "$label down", enabled) { onStep(-1) }
            OverrideBar(value, base, range, enabled, onDrag, onRelease, Modifier.weight(1f))
            StepButton("+", "$label up", enabled) { onStep(+1) }
            OverridePercent(percent, enabled) { asking = true }
        }
        if (asking) {
            // Cover this row from the left edge of − to the right edge of +.
            val spanX = labelW + gap
            val spanW = maxWidth - labelW - gap - gap - pctW
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.ui.window.Popup(
                alignment = Alignment.CenterStart,
                offset = with(density) { androidx.compose.ui.unit.IntOffset(spanX.roundToPx(), 0) },
                onDismissRequest = { asking = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true),
            ) {
                androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = Color(0xFF2A3852), shadowElevation = 8.dp,
                    modifier = Modifier.width(spanW).height(44.dp)) {
                    androidx.compose.foundation.layout.Row(Modifier.padding(start = 12.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(resetText, fontSize = 13.5.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        androidx.compose.material3.Button(onClick = { asking = false; onReset() },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.height(32.dp)) { Text("Reset", fontSize = 14.sp) }
                        androidx.compose.material3.IconButton(onClick = { asking = false }, modifier = Modifier.size(36.dp)) {
                            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Close, "Cancel",
                                Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepButton(text: String, desc: String, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    Box(Modifier.size(34.dp).clip(shape)
        .border(1.dp, cs.outline.copy(alpha = if (enabled) 1f else 0.4f), shape)
        .clickable(enabled = enabled, onClick = onClick)
        .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center) {
        Text(text, fontSize = 20.sp, color = cs.primary.copy(alpha = if (enabled) 1f else 0.35f))
    }
}
