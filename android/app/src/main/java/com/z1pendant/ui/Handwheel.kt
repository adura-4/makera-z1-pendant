package com.z1pendant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Drag-to-turn handwheel. Every [degPerClick] of rotation around the centre is one detent:
 * clockwise = +, counter-clockwise = −. [onClick] gets ±1 per detent (the caller does haptics).
 * Touches near the centre are ignored (angle is unstable there).
 */
@Composable
fun Handwheel(
    degPerClick: Float,
    axis: Char,
    stepText: String,
    enabled: Boolean,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var angle by remember { mutableFloatStateOf(0f) }        // visual rotation, degrees
    var active by remember { mutableStateOf(false) }
    val click by rememberUpdatedState(onClick)
    val deg by rememberUpdatedState(degPerClick)
    val en by rememberUpdatedState(enabled)

    val ring = MaterialTheme.colorScheme.surfaceVariant
    val tick = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val dim = if (enabled) 1f else 0.4f

    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val r = size.width / 2f
                    if (hypot(down.position.x - c.x, down.position.y - c.y) < r * 0.22f) return@awaitEachGesture
                    down.consume()
                    active = true
                    var last = atan2(down.position.y - c.y, down.position.x - c.x)
                    var acc = 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        val p = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!p.pressed) break
                        val moved = p.positionChange() != Offset.Zero   // read before consume(): consumed changes report zero
                        p.consume()
                        if (!moved) continue
                        val d = hypot(p.position.x - c.x, p.position.y - c.y)
                        val a = atan2(p.position.y - c.y, p.position.x - c.x)
                        if (d < r * 0.15f) { last = a; continue }
                        var delta = Math.toDegrees((a - last).toDouble()).toFloat()
                        if (delta > 180) delta -= 360f
                        if (delta < -180) delta += 360f
                        last = a
                        angle += delta
                        acc += delta
                        while (acc >= deg) { acc -= deg; if (en) click(1) }
                        while (acc <= -deg) { acc += deg; if (en) click(-1) }
                    }
                    active = false
                }
            }
        ) {
            val r = size.minDimension / 2f
            val c = center
            drawCircle(ring.copy(alpha = dim), radius = r * 0.98f)
            drawCircle(if (active) accent.copy(alpha = 0.6f) else tick.copy(alpha = 0.25f), radius = r * 0.98f, style = Stroke(width = r * 0.025f))
            rotate(angle, c) {
                val n = (360f / degPerClick).toInt().coerceAtLeast(6)
                // The tick sitting at 12 o'clock is lit in the accent colour; turning the wheel one
                // detent brings the next tick up there and it lights instead.
                val top = Math.floorMod(Math.round(-angle * n / 360f), n)
                for (i in 0 until n) {
                    val t = (i * 360f / n - 90f) * PI.toFloat() / 180f
                    val major = i % 5 == 0
                    val lit = i == top
                    val r0 = r * (if (lit) 0.74f else if (major) 0.78f else 0.84f); val r1 = r * 0.93f
                    drawLine(if (lit) accent.copy(alpha = dim) else tick.copy(alpha = (if (major) 0.9f else 0.5f) * dim),
                        Offset(c.x + r0 * cos(t), c.y + r0 * sin(t)), Offset(c.x + r1 * cos(t), c.y + r1 * sin(t)),
                        strokeWidth = r * (if (lit) 0.03f else if (major) 0.022f else 0.012f), cap = StrokeCap.Round)
                }
                // finger dimple
                val t = -90f * PI.toFloat() / 180f
                drawCircle(accent.copy(alpha = dim), radius = r * 0.09f, center = Offset(c.x + r * 0.6f * cos(t), c.y + r * 0.6f * sin(t)))
            }
            drawCircle(Color.Black.copy(alpha = 0.25f), radius = r * 0.46f)
        }
        // Axis letter dead centre, the step underneath it, both inside the dark centre disc.
        Text(axis.toString(), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = if (enabled) accent else tick)
        Text(stepText, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = tick,
            modifier = Modifier.offset(y = 36.dp))
    }
}

