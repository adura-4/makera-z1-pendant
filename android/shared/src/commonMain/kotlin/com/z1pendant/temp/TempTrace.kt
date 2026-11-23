package com.z1pendant.temp

import com.z1pendant.link.MachineStatus

/** One reading: seconds since the job started, spindle and box temperature (°C). */
data class TempSample(val tS: Double, val spindle: Double?, val box: Double?)

/** Where a tool started cutting: seconds since the job started, tool number. */
data class ToolMark(val tS: Double, val tool: Int)

/** What the graph draws: the samples and tool marks so far (a copy, safe to hand to the UI). */
data class TempView(val samples: List<TempSample>, val marks: List<ToolMark>) {
    val last get() = samples.lastOrNull()
    val durationS get() = samples.lastOrNull()?.tS ?: 0.0
}

/** The Z1's temperature limits (°C): it halts the job above these. */
data class TempLimits(val spindle: Double, val box: Double) {
    companion object {
        /** From the Z1 Pro's built-in config (LPC dump 2026-11-08), used until the SD config is read. */
        val DEFAULT = TempLimits(spindle = 70.0, box = 60.0)
    }
}

/** Which reading is within [WARN_MARGIN] of its limit (a banner, a buzz and a pulsing line). */
enum class TempWarn { SPINDLE, BOX }

/** The spindle and box temperatures, `|S:` fields 5 and 6 (`S:0.0,10000.0,100.0,0,36.3,25.0,…`). */
val MachineStatus.spindleTemp: Double? get() = sFields?.getOrNull(4)?.toDoubleOrNull()
val MachineStatus.boxTemp: Double? get() = sFields?.getOrNull(5)?.toDoubleOrNull()

/**
 * The temperatures over one job, for the job panel's graph. A sample every [STEP_MS] at most (the
 * status comes 3–4 times a second); past [MAX_SAMPLES] every other one is dropped, so a long job keeps
 * its whole shape. Tool marks: a "Done ATC" arms one, and the next status with a real tool number
 * (the one crossing "Done ATC" can still carry −1 or the old tool) places it at the time of the
 * "Done ATC". A job that starts without a tool change gets its first mark when the spindle starts.
 */
class TempTrace {
    private var startMs = 0L
    private var lastAddMs: Long? = null
    private val samples = ArrayList<TempSample>()
    private val marks = ArrayList<ToolMark>()
    private var pendingMarkMs: Long? = null
    var active = false; private set

    fun reset(nowMs: Long) {
        startMs = nowMs; lastAddMs = null; samples.clear(); marks.clear(); pendingMarkMs = null; active = true
    }

    /** Back into the same job after a dropped link: keep adding to this trace (a gap in time, no reset). */
    fun resume() { active = true }

    /** Stop adding (the job ended); the trace stays for the alarm sheet until the next job starts. */
    fun stop() { active = false; pendingMarkMs = null }

    /** A status during the job. Returns true if the trace changed (a sample or a mark was added). */
    fun onStatus(nowMs: Long, st: MachineStatus): Boolean {
        if (!active) return false
        var changed = false
        val tool = st.tool
        val p = pendingMarkMs
        if (tool != null && tool > 0 && !st.state.equals("Tool", true)) {
            if (p != null) { changed = addMark(p, tool) || changed; pendingMarkMs = null }
            else if (marks.isEmpty() && (st.spindleRpm ?: 0.0) > 0.0) changed = addMark(nowMs, tool) || changed
        }
        if (lastAddMs.let { it == null || nowMs - it >= STEP_MS }) {
            val s = st.spindleTemp; val b = st.boxTemp
            if (s != null || b != null) {
                samples += TempSample((nowMs - startMs) / 1000.0, s, b); lastAddMs = nowMs; changed = true
                if (samples.size > MAX_SAMPLES) {
                    val kept = samples.filterIndexed { i, _ -> i % 2 == 0 || i == samples.lastIndex }
                    samples.clear(); samples.addAll(kept)
                }
            }
        }
        return changed
    }

    /** The machine said "Done ATC": the next real tool number gets a mark here. */
    fun onToolChangeDone(nowMs: Long) { if (active) pendingMarkMs = nowMs }

    private fun addMark(atMs: Long, tool: Int): Boolean {
        if (marks.lastOrNull()?.tool == tool) return false
        marks += ToolMark((atMs - startMs) / 1000.0, tool)
        return true
    }

    fun view() = TempView(samples.toList(), marks.toList())

    /** [nowMs] as seconds into the trace (where the alarm sheet draws the halt). */
    fun secondsAt(nowMs: Long) = (nowMs - startMs) / 1000.0

    companion object {
        const val STEP_MS = 2_000L
        const val MAX_SAMPLES = 1_200
        /** Warn this many °C under a limit. */
        const val WARN_MARGIN = 5.0
        /** Amber this many °C under the spindle limit. */
        const val WARM_MARGIN = 15.0

        /** Spindle °C per minute over about the last minute (null with under 20 s of data). */
        fun ratePerMin(v: TempView): Double? {
            val pts = v.samples.filter { it.spindle != null }
            val last = pts.lastOrNull() ?: return null
            val first = pts.firstOrNull { it.tS >= last.tS - 60 } ?: return null
            val dt = last.tS - first.tS
            if (dt < 20) return null
            return (last.spindle!! - first.spindle!!) / dt * 60
        }

        /** The reading that's close to its limit, or null. With hysteresis: [was] stays until 1 °C further down. */
        fun warn(spindle: Double?, box: Double?, lim: TempLimits, was: TempWarn?): TempWarn? {
            fun near(v: Double?, l: Double, w: Boolean) = v != null && v >= l - WARN_MARGIN - (if (w) 1.0 else 0.0)
            return when {
                near(spindle, lim.spindle, was == TempWarn.SPINDLE) -> TempWarn.SPINDLE
                near(box, lim.box, was == TempWarn.BOX) -> TempWarn.BOX
                else -> null
            }
        }
    }
}
