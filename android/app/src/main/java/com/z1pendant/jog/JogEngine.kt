package com.z1pendant.jog

import android.os.SystemClock
import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sign

data class AxisLimits(val maxRateMmMin: Double, val softMin: Double?, val softMax: Double?)

data class JogConfig(
    val tickMs: Long = 40,
    /** Hand speed = clicks in this window × step ÷ window. */
    val handWindowMs: Long = 200,
    /**
     * Motion kept queued while turning, in seconds of travel at hand speed. Smoothie waits
     * queue_delay_time_ms (100 ms) before starting a queue that was empty, so letting it run dry
     * mid-turn = a pause + 100 ms restart. A small steady buffer avoids that.
     */
    val bufferS: Double = 0.12,
    /** How fast the feed corrects toward that buffer (s). */
    val correctS: Double = 0.3,
    /** Smoothie's queue_delay_time_ms: the machine sits still this long when a jog starts. */
    val startDelayS: Double = 0.1,
    val accelMmS2: Double = 150.0,            // live M503: XYZ 150 mm/s²
    val minFeedMmMin: Double = 5.0,             // low enough to follow a slow 0.01 mm turn without stop-go
    val limits: Map<Char, AxisLimits> = mapOf(
        // Mins from the live config.txt. Max = -1: on the real Z1 (fw 1.1.3.0.1.13) X+0.1 from the
        // homed MPos -1.000 raised "Soft Endstop X was exceeded" (2026-11-13). Y/Z max assumed the same.
        'X' to AxisLimits(3000.0, -207.0, -1.0),
        'Y' to AxisLimits(3000.0, -206.0, -1.0),
        'Z' to AxisLimits(1000.0, -102.0, -1.0),
        'A' to AxisLimits(1800.0, null, null),    // 30 °/s
    ),
    /** Clamp to soft limits unless the status explicitly says "not homed" (stock fw has no H field). */
    val softLimitClamp: Boolean = true,
)

/**
 * Turns handwheel/button clicks into short relative moves.
 *
 * Every [JogConfig.tickMs] the clicks collected since the last tick become ONE move. Its feed is
 * hand speed plus a correction that holds about [JogConfig.bufferS] of motion queued, so the
 * firmware's queue never runs dry mid-turn (each dry spell costs a stop + 100 ms restart).
 * The "lead" (commanded but not yet travelled, dead-reckoned from the latest status) plus waiting
 * clicks is capped at braking distance (v²/2a) + one tick + the buffer + one click; anything beyond
 * is dropped ([onDrop]; the UI gives a thud). So when input stops or the link drops, the axis stops
 * within about that distance.
 */
class JogEngine(
    private val link: MachineLink,
    private val scope: CoroutineScope,
    var config: JogConfig = JogConfig(),
    private val onDrop: () -> Unit = {},
    private val onLimit: () -> Unit = {},
    /** A jog ended (the wheel stopped): total distance moved per axis, for the console. */
    private val onJogDone: (Map<Char, Double>) -> Unit = {},
) {
    private val burst = LinkedHashMap<Char, Double>()
    @Volatile var axis: Char = 'X'
        set(v) { if (v != field) { pending.set(0); target = null }; field = v }
    @Volatile var step: Double = 0.1
    @Volatile var enabled = true

    private val pending = AtomicInteger(0)
    @Volatile private var target: Double? = null
    @Volatile private var targetAxis: Char = 'X'
    @Volatile private var needAbsolute = false
    @Volatile private var lastSendAt = 0L
    /** elapsedRealtime of the last jog move sent (0 = never). */
    val lastJogAt: Long get() = lastSendAt
    @Volatile private var lastCmdV = 0.0                 // mm/s of the last move sent
    @Volatile private var lastDropAt = 0L
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                try { tick() } catch (_: Exception) {}
                delay(config.tickMs)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    private val clickTimes = ArrayDeque<Long>()

    /** Signed number of detents. */
    fun click(n: Int) {
        if (!enabled) return
        pending.addAndGet(n)
        val t = SystemClock.elapsedRealtime()
        synchronized(clickTimes) {
            repeat(abs(n).coerceAtMost(100)) { clickTimes.addLast(t) }
            while (clickTimes.size > 400) clickTimes.removeFirst()
        }
    }

    /** Detents per second over the last [JogConfig.handWindowMs]. */
    private fun handRate(now: Long): Double = synchronized(clickTimes) {
        while (clickTimes.isNotEmpty() && now - clickTimes.first() > config.handWindowMs) clickTimes.removeFirst()
        clickTimes.size / (config.handWindowMs / 1000.0)
    }

    /** Drop whatever is pending (e.g. app backgrounded, finger lifted with "stop on release"). */
    fun cancelPending() { pending.set(0) }

    private fun axisIndex(a: Char) = "XYZA".indexOf(a)

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val st = link.status.value
        val connected = link.conn.value == ConnState.Connected
        val n = pending.get()

        if (!connected || st == null || st.isAlarm || st.state in setOf("Hold", "Home", "Pause", "Tool", "Sleep")) {
            if (n != 0) {
                pending.set(0); onDrop()
                android.util.Log.i("JogEngine", "dropped $n clicks: conn=$connected state=${st?.state}")
            }
            target = null
            return
        }
        // Fresh positions while jogging keep the lead estimate tight.
        val active = n != 0 || now - lastSendAt < 1000
        link.setPollIntervalMs(if (active) 50 else 200)

        if (n == 0) {
            if (needAbsolute && now - lastSendAt > 250 && link.pendingLines == 0) {
                link.sendLine(link.dialect.absoluteMode(), quiet = true); needAbsolute = false
                if (burst.isNotEmpty()) { onJogDone(LinkedHashMap(burst)); burst.clear() }
            }
            if (st.isIdle && link.pendingLines == 0 && now - lastSendAt > 300) target = null
            return
        }

        val a = axis
        val ai = axisIndex(a)
        val lim = config.limits[a] ?: return
        val pos = st.mpos.getOrNull(ai) ?: return
        if (target == null || targetAxis != a) {
            if (!st.isIdle || link.pendingLines > 0) return     // let the previous axis/macro finish
            target = pos; targetAxis = a
        }
        if (link.pendingLines >= 2) return

        val stp = step
        val vmax = lim.maxRateMmMin / 60.0                        // mm/s
        val vHand = min(handRate(now) * stp, vmax)
        // Lead = commanded minus reported, minus what the machine has travelled since that status
        // (dead reckoning), so stale status doesn't inflate it.
        val statusAgeS = (now - st.atMs + (link.latencyMs.value ?: 30) / 2) / 1000.0
        val rawLead = abs(target!! - pos)
        val lead = if (st.state.equals("Run", true)) maxOf(0.0, rawLead - lastCmdV * statusAgeS) else rawLead
        // Feed: hand speed, corrected toward a lead of vHand × buffer.
        val vCmd = (vHand + (lead - vHand * config.bufferS) / config.correctS)
            .coerceIn(maxOf(config.minFeedMmMin / 60.0, vHand * 0.3), vmax)
        // Floor of 5 clicks: at the start of a slow turn the speed estimate is still tiny, and a
        // precise 0.01 mm turn must not lose clicks. The feed correction clears them quickly.
        // The floor stops at 5 mm (one click at the 5 mm step), so a big step can't leave a long
        // move running after you stop turning. The start-delay term covers clicks that pile up
        // while the firmware holds its queue.
        val floorMm = maxOf(stp, min(5 * stp, 5.0))
        val cap = maxOf(floorMm, vCmd * vCmd / (2 * config.accelMmS2) + vCmd * config.tickMs / 1000.0 +
            vHand * (config.bufferS + config.startDelayS) + stp)
        val allowed = cap - lead

        // Lead + waiting clicks may never exceed the cap: anything more is thrown away (thud), not
        // stored up, so when input stops the axis stops within [cap] of where it was commanded.
        val maxClicks = floor(maxOf(0.0, allowed) / stp + 1e-9).toInt()
        if (abs(n) > maxClicks) {
            val drop = abs(n) - maxClicks
            pending.addAndGet(-sign(n.toDouble()).toInt() * drop)
            if (now - lastDropAt > 150) { onDrop(); lastDropAt = now }     // one thud per 150 ms, not a buzz
            android.util.Log.i("JogEngine", "dropped $drop clicks (cap ${"%.2f".format(cap)} lead ${"%.2f".format(lead)})")
        }
        if (allowed < stp * 0.999) return

        var clicks = min(abs(pending.get()), floor(allowed / stp + 1e-9).toInt())
        if (clicks <= 0) return
        val dir = sign(pending.get().toDouble())
        var dist = dir * clicks * stp

        // Soft limits: clamp, and buzz when we hit one (the stock firmware alarms on a soft-limit hit).
        if (config.softLimitClamp && st.homed != false) {
            val t = target!! + dist
            val lo = lim.softMin; val hi = lim.softMax
            val clamped = t.coerceIn(lo ?: Double.NEGATIVE_INFINITY, hi ?: Double.POSITIVE_INFINITY)
            if (clamped != t) {
                dist = clamped - target!!
                pending.set(0); onLimit()
                if (abs(dist) < 1e-4) return
                clicks = abs(pending.get())
            }
        }

        val feed = (vCmd * 60.0).coerceIn(config.minFeedMmMin, lim.maxRateMmMin)
        lastCmdV = feed / 60.0
        pending.addAndGet(-(dir * clicks).toInt().let { if (it == 0) 0 else it })
        link.sendLine(link.dialect.jogMove(a, dist, feed), quiet = true)
        burst[a] = (burst[a] ?: 0.0) + dist
        target = target!! + dist
        lastSendAt = now
        needAbsolute = true
    }
}
