package com.z1pendant

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * A job's estimated run time, line by line, worked out from the file the way a sender's preview does
 * it (Makera Studio's Monitor too: it downloads the job and sums length ÷ feed per move; see
 * pendant/docs/run_time_estimate_options.md). The time left is then the time of the lines still to
 * run, not "lines left × the pace so far": short and long lines aren't the same.
 *
 * Machine time only: waits in a tool change aren't in it (the Z1's elapsed time stops then too), nor
 * the firmware's own routines (the setter, a margin scan, probing), which take seconds.
 */
class JobTime internal constructor(
    /** Seconds from the start to the end of file line n (1-based, index n; [0] is 0). */
    private val cum: FloatArray,
    /** The part of [cum] spent in feed moves (G1/G2/G3): what the feed override scales. */
    private val feedCum: FloatArray,
    /** Real tool changes (M6 to a different tool), as the firmware does them. */
    val toolChanges: Int,
) {
    val total: Double get() = cum.last().toDouble()
    private fun idx(line: Long) = line.coerceIn(0L, cum.size - 1L).toInt()
    fun at(line: Long): Double = cum[idx(line)].toDouble()
    fun feedAt(line: Long): Double = feedCum[idx(line)].toDouble()

    /** Estimated seconds after [line], with the feed moves at [feedOverridePct] %. */
    fun leftAfter(line: Long, feedOverridePct: Double? = null): Double {
        val all = total - at(line)
        val feed = feedCum.last() - feedAt(line)
        val f = (feedOverridePct ?: 100.0).coerceAtLeast(1.0) / 100.0
        return (all - feed) + feed / f
    }

    /** Estimated seconds between two lines, feed moves at [feedOverridePct] % (for the pace). */
    fun between(from: Long, to: Long, feedOverridePct: Double?): Double {
        val all = at(to) - at(from)
        val feed = feedAt(to) - feedAt(from)
        val f = (feedOverridePct ?: 100.0).coerceAtLeast(1.0) / 100.0
        return (all - feed) + feed / f
    }
}

/**
 * How the job is really going against the estimate: the Z1's elapsed seconds ÷ the estimated seconds
 * for the same lines (feed moves at the override in force then), over the last [WINDOW_S] seconds.
 * 1 until [MIN_EST_S] seconds of estimate are in the window, then kept within [MIN]..[MAX]: the
 * setter, a probe run or a margin scan inside the window slow it for a while, and a bad file or a
 * dropped status shouldn't throw the number around.
 */
class JobPace {
    companion object {
        const val WINDOW_S = 180.0
        const val MIN_EST_S = 30.0
        const val MIN = 0.6
        const val MAX = 1.8
    }
    var value = 1.0
        private set
    private var lastLine = -1L
    private var est = 0.0
    private val samples = ArrayDeque<DoubleArray>()      // [elapsed s, estimate so far s]

    fun reset() { value = 1.0; lastLine = -1L; est = 0.0; samples.clear() }

    fun update(line: Long, elapsedS: Long, t: JobTime, feedOverridePct: Double?) {
        if (line < lastLine) reset()                       // another job (or the same one again)
        if (lastLine < 0) { lastLine = line; samples.addLast(doubleArrayOf(elapsedS.toDouble(), est)); return }
        if (line == lastLine) return
        est += t.between(lastLine, line, feedOverridePct)
        lastLine = line
        samples.addLast(doubleArrayOf(elapsedS.toDouble(), est))
        while (samples.size > 2 && samples.last()[0] - samples.first()[0] > WINDOW_S) samples.removeFirst()
        val de = samples.last()[0] - samples.first()[0]
        val dt = samples.last()[1] - samples.first()[1]
        if (dt >= MIN_EST_S) value = (de / dt).coerceIn(MIN, MAX)
    }
}

object JobTimeEstimator {
    /** Z1 limits from the live machine's M503 (2026-11-08): acceleration, and the rapid rates. */
    const val ACCEL_MM_S2 = 150.0
    const val RAPID_XY_MM_MIN = 3000.0
    const val RAPID_Z_MM_MIN = 1000.0

    private val word = Regex("""([A-Za-z])\s*([-+]?(?:\d+\.?\d*|\.\d+))""")
    private val comment = Regex("""\([^)]*\)""")

    /**
     * Each move's time is the midpoint of two models: at full speed the whole way (too short), and
     * starting and stopping at every move with the Z1's acceleration (too long; the planner blends
     * moves). On Jimmy's two-tool test job (2026-11-18) the real times fell between the two, the
     * midpoint within about 3 %.
     */
    fun estimate(text: String): JobTime {
        val lines = text.lineSequence().toList()
        val cum = FloatArray(lines.size + 1)
        val feedCum = FloatArray(lines.size + 1)
        var t = 0.0; var tf = 0.0
        val pos = doubleArrayOf(0.0, 0.0, 0.0)
        var motion = 0; var plane = 17; var absolute = true; var inch = false
        var feed = 0.0                              // mm/min
        var tool: Int? = null; var changes = 0
        for ((i, raw) in lines.withIndex()) {
            val code = comment.replace(raw.substringBefore(';'), " ")
            val ws = word.findAll(code).map { it.groupValues[1].uppercase()[0] to it.groupValues[2].toDouble() }.toList()
            if (ws.isNotEmpty()) {
                val gs = ws.filter { it.first == 'G' }.map { it.second }
                val ms = ws.filter { it.first == 'M' }.map { it.second }
                fun v(c: Char) = ws.lastOrNull { it.first == c }?.second
                for (g in gs) when {
                    g == 0.0 || g == 1.0 || g == 2.0 || g == 3.0 -> motion = g.toInt()
                    g == 17.0 || g == 18.0 || g == 19.0 -> plane = g.toInt()
                    g == 90.0 -> absolute = true
                    g == 91.0 -> absolute = false
                    g == 20.0 -> inch = true
                    g == 21.0 -> inch = false
                }
                val scale = if (inch) 25.4 else 1.0
                v('F')?.let { feed = it * scale }
                if (ms.any { it == 6.0 }) v('T')?.toInt()?.let { if (it != tool) { changes++; tool = it } }
                if (gs.any { it == 4.0 }) t += v('S') ?: ((v('P') ?: 0.0) / 1000.0)   // Smoothie: P ms, S s
                val machine = gs.any { it == 53.0 || it == 28.0 || it == 30.0 } || gs.any { abs(it - 38.0) < 1 }
                val hasAxis = ws.any { it.first == 'X' || it.first == 'Y' || it.first == 'Z' }
                if (hasAxis && !machine) {
                    val tgt = DoubleArray(3) { k ->
                        val w = v("XYZ"[k])?.times(scale)
                        when { w == null -> pos[k]; absolute -> w; else -> pos[k] + w }
                    }
                    if (motion == 0) {
                        val dxy = hypot(tgt[0] - pos[0], tgt[1] - pos[1]); val dz = abs(tgt[2] - pos[2])
                        val d = hypot(dxy, dz)
                        val speed = when {
                            d < 1e-9 -> 0.0
                            dxy < 1e-9 -> RAPID_Z_MM_MIN
                            dz < 1e-9 -> RAPID_XY_MM_MIN
                            else -> minOf(RAPID_XY_MM_MIN, RAPID_Z_MM_MIN * d / dz)
                        }
                        t += moveTime(d, speed)
                    } else {
                        val d = if (motion == 1) dist(pos, tgt)
                            else arcLength(pos, tgt, plane, motion == 2, v('I')?.times(scale), v('J')?.times(scale), v('K')?.times(scale), v('R')?.times(scale))
                        val s = moveTime(d, feed)
                        t += s; tf += s
                    }
                    tgt.copyInto(pos)
                }
            }
            cum[i + 1] = t.toFloat(); feedCum[i + 1] = tf.toFloat()
        }
        return JobTime(cum, feedCum, changes)
    }

    /** Midpoint of full speed and rest-to-rest (trapezoid at [ACCEL_MM_S2]), seconds. */
    internal fun moveTime(d: Double, mmPerMin: Double): Double {
        if (d <= 0 || mmPerMin <= 0) return 0.0
        val v = mmPerMin / 60.0
        val a = ACCEL_MM_S2
        val full = d / v
        val rest = if (d < v * v / a) 2 * sqrt(d / a) else d / v + v / a
        return (full + rest) / 2
    }

    private fun dist(a: DoubleArray, b: DoubleArray) = sqrt((a[0] - b[0]).let { it * it } + (a[1] - b[1]).let { it * it } + (a[2] - b[2]).let { it * it })

    /** Arc (with any helical part along the plane's normal), centre from I/J/K or R. */
    internal fun arcLength(p0: DoubleArray, p1: DoubleArray, plane: Int, cw: Boolean,
                           i: Double?, j: Double?, k: Double?, r: Double?): Double {
        // Plane axes in right-handed order, so CW is as seen from the normal's + side: G17 XY, G18 ZX, G19 YZ.
        val (a, b) = when (plane) { 18 -> 2 to 0; 19 -> 1 to 2; else -> 0 to 1 }
        val n = 3 - a - b
        val off = doubleArrayOf(i ?: 0.0, j ?: 0.0, k ?: 0.0)
        val helix = p1[n] - p0[n]
        if (r != null && i == null && j == null && k == null) {
            val chord = hypot(p1[a] - p0[a], p1[b] - p0[b])
            val rad = abs(r)
            if (rad < 1e-9 || chord > 2 * rad + 1e-6) return hypot(chord, helix)
            var ang = 2 * asin((chord / (2 * rad)).coerceAtMost(1.0))
            if (r < 0) ang = 2 * PI - ang
            return hypot(rad * ang, helix)
        }
        val ca = p0[a] + off[a]; val cb = p0[b] + off[b]
        val rad = hypot(p0[a] - ca, p0[b] - cb)
        val a0 = atan2(p0[b] - cb, p0[a] - ca); val a1 = atan2(p1[b] - cb, p1[a] - ca)
        var sweep = if (cw) a0 - a1 else a1 - a0
        while (sweep <= 1e-9) sweep += 2 * PI
        return hypot(rad * sweep, helix)
    }
}

/** "about 13 min" style for an estimate: 45 → "1 min", 754 → "13 min", 3_900 → "1 h 05 min". */
fun approxDuration(s: Double): String {
    val m = kotlin.math.round(s / 60.0).toLong().coerceAtLeast(1)
    return if (m < 60) "$m min" else "${m / 60} h ${(m % 60).toString().padStart(2, '0')} min"
}
