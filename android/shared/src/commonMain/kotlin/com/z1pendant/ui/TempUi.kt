package com.z1pendant.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.z1pendant.AppViewModel
import com.z1pendant.temp.TempLimits
import com.z1pendant.temp.TempTrace
import com.z1pendant.temp.TempView
import com.z1pendant.temp.TempWarn
import com.z1pendant.temp.boxTemp
import com.z1pendant.temp.spindleTemp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

val SpindleBlue = Color(0xFF38BDF8)
val BoxViolet = Color(0xFFA78BFA)
private val Warm = Color(0xFFF59E0B)
private val Hot = Color(0xFFF97316)
private val LimitRed = Color(0xFFF87171)

/** 36.3 → "36". */
fun deg(v: Double?) = v?.roundToInt()?.toString() ?: "—"

/** Blue, amber within 15 °C of the limit, orange within 5 °C. */
fun spindleColor(t: Double?, lim: Double) = when {
    t == null -> SpindleBlue
    t >= lim - TempTrace.WARN_MARGIN -> Hot
    t >= lim - TempTrace.WARM_MARGIN -> Warm
    else -> SpindleBlue
}

fun boxColor(t: Double?, lim: Double) = if (t != null && t >= lim - TempTrace.WARN_MARGIN) Hot else BoxViolet

/** In the job panel when a reading is within 5 °C of its limit. */
@Composable
fun TempBanner(vm: AppViewModel) {
    val w by vm.tempWarn.collectAsState()
    val st by vm.link.status.collectAsState()
    val lim by vm.tempLimits.collectAsState()
    val warn = w ?: return
    val (what, v, l) = if (warn == TempWarn.SPINDLE) Triple("Spindle", st?.spindleTemp, lim.spindle) else Triple("Box", st?.boxTemp, lim.box)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF7C2D12)).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Thermostat, null, tint = Color(0xFFFED7AA), modifier = Modifier.size(20.dp))
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) { append("$what ${deg(v)} °C. ") }
            append("The Z1 stops the job at ${deg(l)} °C. Hold to let it cool, or slow it down.")
        }, color = Color(0xFFFED7AA), fontSize = 13.sp)
    }
}

/** The job panel's Temperature card: both readings now, and the graph since the job started. */
@Composable
fun TempCard(vm: AppViewModel) {
    val view by vm.temps.collectAsState()
    val st by vm.link.status.collectAsState()
    val lim by vm.tempLimits.collectAsState()
    val warn by vm.tempWarn.collectAsState()
    val s = st?.spindleTemp; val b = st?.boxTemp
    if (s == null && b == null) return
    val v = view ?: TempView(emptyList(), emptyList())
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
        .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 4.dp)) {
        TempHeader("Spindle", s, b, lim, TempTrace.ratePerMin(v))
        TempGraph(v, lim, warn, Modifier.fillMaxWidth().height(104.dp).padding(top = 4.dp))
        TimeFooter("job start", "now")
    }
}

@Composable
fun TempHeader(spindleLabel: String, s: Double?, b: Double?, lim: TempLimits, rate: Double?, rateText: String? = null) {
    val sc = spindleColor(s, lim.spindle)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(spindleLabel, color = sc, fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
        Text(" ${deg(s)} °C", color = sc, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        val r = rateText ?: rate?.let { (if (it >= 0) "↑ " else "↓ ") + "${kotlin.math.abs(it).roundToInt()} °C/min" }
        if (r != null) Text(r, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp, bottom = 2.dp))
        Spacer(Modifier.weight(1f))
        val bc = boxColor(b, lim.box)
        Text("Box", color = bc, fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
        Text(" ${deg(b)} °C", color = bc, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun TimeFooter(left: String, right: String, middle: String? = null) {
    val c = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        Text(left, fontSize = 10.sp, color = c)
        Spacer(Modifier.weight(1f))
        if (middle != null) { Text(middle, fontSize = 10.sp, color = c); Spacer(Modifier.weight(1f)) }
        Text(right, fontSize = 10.sp, color = c)
    }
}

/**
 * Spindle (blue) and box (violet) over time, each on its own scale (tick numbers in its colour), with
 * its limit as a dashed (spindle) or dotted (box) line. A line within 5 °C of its limit pulses. Tool
 * marks along the bottom: a dot and the tool number where each tool started, a faint line up from it.
 * [haltS]: where the machine halted (alarm sheet).
 */
@Composable
fun TempGraph(v: TempView, lim: TempLimits, warn: TempWarn?, modifier: Modifier, haltS: Double? = null) {
    val tm = rememberTextMeasurer()
    val pulse = if (warn != null) {
        val t by rememberInfiniteTransition(label = "temp").animateFloat(0f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse")
        t
    } else 0f
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val left = 22.dp.toPx(); val right = size.width - 22.dp.toPx()
        val top = 6.dp.toPx(); val axisY = size.height - 16.dp.toPx()
        val dur = max(v.durationS, 60.0)
        fun x(t: Double) = (left + (t / dur) * (right - left)).toFloat()
        val sv = v.samples.mapNotNull { it.spindle }; val bv = v.samples.mapNotNull { it.box }
        val sLo = min(20.0, floor((sv.minOrNull() ?: 20.0) - 2)); val sHi = max(80.0, lim.spindle + 5)
        val bLo = min(20.0, floor((bv.minOrNull() ?: 20.0) - 2)); val bHi = max(lim.box + 5, (bv.maxOrNull() ?: 0.0) + 2)
        fun ys(t: Double) = (axisY - (t - sLo) / (sHi - sLo) * (axisY - top)).toFloat()
        fun yb(t: Double) = (axisY - (t - bLo) / (bHi - bLo) * (axisY - top)).toFloat()

        drawLine(Color(0xFF334155), Offset(left, axisY), Offset(right, axisY), 1.dp.toPx())
        drawLine(LimitRed, Offset(left, ys(lim.spindle)), Offset(right, ys(lim.spindle)), 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
        drawLine(BoxViolet.copy(alpha = 0.8f), Offset(left, yb(lim.box)), Offset(right, yb(lim.box)), 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 3.dp.toPx())))

        // Tick numbers: the limit, and round numbers that aren't crowding it.
        fun ticks(lo: Double, hi: Double, limit: Double, step: Int): List<Double> {
            val out = mutableListOf(lo)
            var t = (floor(lo / step) + 1) * step
            while (t < hi) { if (kotlin.math.abs(t - limit) >= 6 && t - lo >= 6) out += t; t += step }
            return out + limit
        }
        val label = TextStyle(fontSize = 9.sp)
        for (t in ticks(sLo, sHi, lim.spindle, 20)) {
            val r = tm.measure(deg(t), label.copy(color = SpindleBlue))
            drawText(r, topLeft = Offset(left - r.size.width - 3.dp.toPx(), ys(t) - r.size.height / 2))
        }
        for (t in ticks(bLo, bHi, lim.box, 15)) {
            val r = tm.measure(deg(t), label.copy(color = BoxViolet))
            drawText(r, topLeft = Offset(right + 3.dp.toPx(), yb(t) - r.size.height / 2))
        }

        // Tool marks.
        for (m in v.marks) {
            val mx = x(m.tS)
            if (m.tS > 1) drawLine(muted.copy(alpha = 0.6f), Offset(mx, top), Offset(mx, axisY), 0.8.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx())))
            drawCircle(Color(0xFFE2E8F0), 2.5.dp.toPx(), Offset(mx, axisY))
            val r = tm.measure("T${m.tool}", TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE2E8F0)))
            drawText(r, topLeft = Offset((mx - r.size.width / 2).coerceIn(0f, size.width - r.size.width), axisY + 3.dp.toPx()))
        }
        haltS?.let { h ->
            val hx = x(h)
            drawLine(LimitRed.copy(alpha = 0.7f), Offset(hx, top), Offset(hx, axisY), 1.dp.toPx())
            // "halt" under the axis at the line (left of it when there's no room on the right).
            val r = tm.measure("halt", TextStyle(fontSize = 10.sp, color = LimitRed))
            val lx = if (hx + 3.dp.toPx() + r.size.width <= size.width) hx + 3.dp.toPx() else hx - 3.dp.toPx() - r.size.width
            drawText(r, topLeft = Offset(lx, axisY + 3.dp.toPx()))
        }

        fun line(pick: (com.z1pendant.temp.TempSample) -> Double?, y: (Double) -> Float): Path {
            val p = Path(); var pen = false
            for (s in v.samples) {
                val t = pick(s)
                if (t == null) { pen = false; continue }
                if (pen) p.lineTo(x(s.tS), y(t)) else { p.moveTo(x(s.tS), y(t)); pen = true }
            }
            return p
        }
        val lastS = v.samples.lastOrNull { it.spindle != null }; val lastB = v.samples.lastOrNull { it.box != null }
        val sCol = spindleColor(lastS?.spindle, lim.spindle); val bCol = boxColor(lastB?.box, lim.box)
        val bPath = line({ it.box }, ::yb); val sPath = line({ it.spindle }, ::ys)
        fun glow(p: Path, c: Color) = drawPath(p, c.copy(alpha = 0.15f + 0.6f * pulse),
            style = Stroke((3 + 6 * pulse).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (warn == TempWarn.BOX) glow(bPath, bCol)
        if (warn == TempWarn.SPINDLE) glow(sPath, sCol)
        drawPath(bPath, bCol, style = Stroke(1.6.dp.toPx(), join = StrokeJoin.Round))
        drawPath(sPath, sCol, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
        lastB?.let { drawCircle(bCol, 2.5.dp.toPx(), Offset(x(it.tS), yb(it.box!!))) }
        lastS?.let { drawCircle(sCol, (3 + if (warn == TempWarn.SPINDLE) 2.5f * pulse else 0f).dp.toPx(), Offset(x(it.tS), ys(it.spindle!!))) }
    }
}

