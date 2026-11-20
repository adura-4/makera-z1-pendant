package com.z1pendant.jog

import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineLink
import com.z1pendant.link.MachineStatus
import com.z1pendant.platform.PLog
import com.z1pendant.platform.nowMs
import com.z1pendant.util.Fmt
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    /**
     * While a jog-pad button is held ([JogEngine.holdRate]) the feed is this fraction of the
     * button's speed, constant, with no lead correction. A little slower than the clicks arrive, the
     * queue slowly fills (up to the cap, where a dropped click costs nothing: moves are still queued)
     * and never runs dry. Any feed change from move to move is audible on the Z1, and a feed even a
     * few % above the supply empties the queue and stops the machine for a moment.
     */
    val holdFeedFactor: Double = 0.97,
    /**
     * The Z1 refreshes the position in its status only every 250–370 ms and repeats it in between.
     * A repeated position is taken to be as old as the first status that carried it, up to this age;
     * older than that the machine is standing still at it, so the latest status's age is used.
     */
    val positionMaxAgeMs: Long = 400,
    /** Smoothie's queue_delay_time_ms: the machine sits still this long when a jog starts. */
    val startDelayS: Double = 0.1,
    /**
     * Clicks that can't start a jog within this long (machine busy: a job, someone else's move, a
     * macro still running) are thrown away with a thud, never stored up to fire later.
     */
    val startWaitMs: Long = 1500,
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
 * Turns handwheel/button clicks into short moves.
 *
 * Moves are sent as absolute machine positions (`G53 G1 X…`), not relative ones, so a jog never
 * leaves the machine in G91: if the link drops or the app dies mid-jog, nothing is left for the
 * next sender (Makera Studio, a hotkey) to trip over. G53 applies to its own line only.
 *
 * A jog session starts from a machine position the machine reported after the first click (never
 * an older reading), and adds the clicks to it. While it runs, the reported position must stay on
 * the path the session commanded and the other axes must stay put; if not, something else moved
 * the machine, and the session ends (no more moves; [onDrop]) until a fresh start.
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
        set(v) { if (v != field) { pending.store(0); endSession() }; field = v }
    @Volatile var step: Double = 0.1
    @Volatile var enabled = true
    /**
     * While a jog-pad button is held and repeating: its exact click rate (clicks/s). It is used as
     * the hand speed, and the feed is a constant [JogConfig.holdFeedFactor] of it. null = measure the
     * hand (dial, taps, keys).
     */
    @Volatile var holdRate: Double? = null

    private val pending = AtomicInt(0)
    @Volatile private var target: Double? = null
    @Volatile private var targetAxis: Char = 'X'
    /** nowMs() of the first click waiting for a session to start (0 = none). */
    @Volatile private var firstClickAt = 0L
    /** The session's commanded path on its axis (start and every target), and the other axes at the start. */
    private var pathLo = 0.0
    private var pathHi = 0.0
    private var startPos: List<Double> = emptyList()
    @Volatile private var lastSendAt = 0L
    /** nowMs() of the last jog move sent (0 = never). */
    val lastJogAt: Long get() = lastSendAt
    @Volatile private var lastCmdV = 0.0                 // mm/s of the last move sent
    @Volatile private var lastDropAt = 0L
    /** The last reported machine position and the arrival time of the first status that carried it. */
    private var lastMpos: List<Double> = emptyList()
    private var mposSince = 0L
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                try { tick() } catch (e: Exception) { PLog.w("JogEngine", "tick: $e") }
                delay(config.tickMs)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    private val clickTimes = ArrayDeque<Long>()
    private val clickLock = SynchronizedObject()

    // ---- jog trace (logcat tag JogTrace): only while jogging, for smoothness / direction analysis ----
    @Volatile private var traceUntil = 0L
    private var traceLastStatusAt = 0L
    private fun tr(msg: String) = PLog.d("JogTrace", "${nowMs()} $msg")
    private fun f3(v: Double) = Fmt.fixed(v, 3)

    /** Signed number of detents. */
    fun click(n: Int) {
        if (!enabled) return
        val t = nowMs()
        if (target == null && firstClickAt == 0L) firstClickAt = t
        val p = pending.addAndFetch(n)
        traceUntil = t + 2000
        tr("C n=$n pend=$p ax=$axis step=$step hold=${holdRate?.let { Fmt.fixed(it, 2) } ?: "-"} sess=${target != null}")
        synchronized(clickLock) {
            repeat(abs(n).coerceAtMost(100)) { clickTimes.addLast(t) }
            while (clickTimes.size > 400) clickTimes.removeFirst()
        }
    }

    /** Detents per second: the held button's repeat rate, or the clicks over the last [JogConfig.handWindowMs]. */
    private fun handRate(now: Long): Double = holdRate ?: synchronized(clickLock) {
        while (clickTimes.isNotEmpty() && now - clickTimes.first() > config.handWindowMs) clickTimes.removeFirst()
        clickTimes.size / (config.handWindowMs / 1000.0)
    }

    /** Drop whatever is pending (e.g. app backgrounded, finger lifted with "stop on release"). */
    fun cancelPending() { val p = pending.load(); pending.store(0); if (nowMs() < traceUntil) tr("CANCEL pend=$p") }

    private fun endSession() {
        if (target != null && nowMs() < traceUntil) tr("END target=${f3(target!!)}")
        target = null; firstClickAt = 0L; waitSince = 0L
    }

    /** When clicks first had to wait for a jog to start (0 = not waiting). */
    private var waitSince = 0L

    private fun axisIndex(a: Char) = "XYZA".indexOf(a)

    /**
     * Arrival time of the first status in the current run of "Run, no job, feed 0" (0 = not in one).
     * Every move starts with ~0.1 s of exactly that (the firmware's queue delay) while the position
     * it reports is still the old one, so it only counts as standing still once it has lasted
     * [SETTLE_MS] (the spindle is on and the axes really are still).
     */
    private var runStillSince = 0L

    /** Standing still, with a position that can be trusted: Idle, or "Run, feed 0" for [SETTLE_MS]. */
    private fun settled(st: MachineStatus): Boolean =
        st.isIdle || (stationary(st) && runStillSince != 0L && st.atMs - runStillSince >= SETTLE_MS)

    /**
     * How much higher the Z1 reports Z while moving than when Idle (null = not seen yet). Idle shows
     * the planned position; moving, it shows the motors' position, which includes any Z correction
     * (on 2026-11-20 a steady +0.349 mm, at every X/Y). Learned during X/Y/A jogs from the still Z,
     * and taken off Z readings that aren't Idle, so a Z jog's lead and path use the planned Z.
     */
    private var zRunOffset: Double? = null
    private var sessionFromIdle = false

    /** Machine position with the moving-Z correction taken off (Idle readings are already planned). */
    private fun planned(st: MachineStatus): List<Double> {
        val off = zRunOffset
        if (st.isIdle || off == null || st.mpos.size < 3) return st.mpos
        return st.mpos.toMutableList().also { it[2] = it[2] - off }
    }

    internal fun tick() {
        val now = nowMs()
        val st = link.status.value
        val connected = link.conn.value == ConnState.Connected
        val n = pending.load()
        val tracing = now < traceUntil
        if (n != 0 || now - lastSendAt < 1500) traceUntil = maxOf(traceUntil, now + 1500)
        if (tracing && st != null && st.atMs != traceLastStatusAt) {
            traceLastStatusAt = st.atMs
            tr("S age=${now - st.atMs} lat=${link.latencyMs.value} plines=${link.pendingLines} ${st.raw}")
        }

        if (st != null) {
            if (stationary(st) && !st.isIdle) { if (runStillSince == 0L) runStillSince = st.atMs } else runStillSince = 0L
        }
        if (!connected) zRunOffset = null              // the next machine (or a power cycle) may differ

        if (!connected || st == null || st.isAlarm || st.state in setOf("Hold", "Home", "Pause", "Tool", "Sleep")) {
            if (n != 0) {
                pending.store(0); onDrop()
                PLog.i("JogEngine", "dropped $n clicks: conn=$connected state=${st?.state}")
            }
            endSession()
            return
        }
        // Fresh positions while jogging keep the lead estimate tight.
        val active = n != 0 || now - lastSendAt < 1000
        link.setPollIntervalMs(if (active) 50 else 200)

        // During an X/Y/A jog that started Idle, Z doesn't move: any difference while moving is the
        // Z1's moving-vs-Idle reporting offset (see [zRunOffset]).
        if (target != null && targetAxis != 'Z' && sessionFromIdle && !st.isIdle && st.mpos.size > 2 && startPos.size > 2) {
            val off = st.mpos[2] - startPos[2]
            if (zRunOffset?.let { abs(it - off) > 0.0005 } != false) tr("ZOFF ${f3(off)} (was ${zRunOffset?.let { f3(it) }})")
            zRunOffset = off
        }
        val plan = planned(st)

        if (n == 0) {
            if (burst.isNotEmpty() && now - lastSendAt > 250 && link.pendingLines == 0) {
                onJogDone(LinkedHashMap(burst)); burst.clear()
            }
            // The jog is over once the machine stands still AT its target (a "still" reading taken
            // just before our last move began must not end it), or, if it stopped elsewhere (halted,
            // a limit), once it has been still for a while.
            val t = target
            if (t != null && settled(st) && link.pendingLines == 0 && now - lastSendAt > 300) {
                val p = plan.getOrNull(axisIndex(targetAxis))
                if ((p != null && abs(p - t) <= tolerance(targetAxis, st)) || now - lastSendAt > 2000) endSession()
            }
            if (target == null) firstClickAt = 0L          // clicks were cancelled before a session started
            waitSince = 0L
            return
        }

        val a = axis
        val ai = axisIndex(a)
        val lim = config.limits[a] ?: return
        val pos = plan.getOrNull(ai) ?: return
        if (target == null || targetAxis != a) {
            // Start only with the machine standing still and nothing of ours in flight (the previous
            // axis or a macro finishes first), from where the machine says it is now: a status that
            // arrived after the first click, not one from before (stale after a Wi-Fi gap, or from
            // before another sender moved it). Clicks that wait too long are dropped, not saved up:
            // a move that fires seconds later (say, when a spindle stops) would be a surprise.
            if (!settled(st) || link.pendingLines > 0 || st.atMs <= firstClickAt) {
                if (waitSince == 0L) { waitSince = now; tr("WAIT state=${st.state} plines=${link.pendingLines} fresh=${st.atMs > firstClickAt}") }
                else if (now - waitSince > config.startWaitMs) {
                    pending.store(0); onDrop(); waitSince = 0L
                    if (target == null) firstClickAt = 0L
                    PLog.i("JogEngine", "dropped $n clicks: no jog start for ${config.startWaitMs} ms (state ${st.state})")
                }
                return
            }
            waitSince = 0L
            target = pos; targetAxis = a
            pathLo = pos; pathHi = pos; startPos = plan.toList(); sessionFromIdle = st.isIdle
            tr("START ax=$a pos=${f3(pos)} statusAge=${now - st.atMs} mposAge=${if (st.mpos == lastMpos) now - mposSince else 0} " +
                "sinceFirstClick=${now - firstClickAt} sinceLastSend=${now - lastSendAt}")
        } else if (offPath(plan, ai, pos, tolerance(a, st))) {
            pending.store(0); endSession(); onDrop()
            PLog.w("JogEngine", "machine left the jog path (axis $a at ${Fmt.fixed(pos, 3)}, path " +
                "${Fmt.fixed(pathLo, 3)}..${Fmt.fixed(pathHi, 3)}): something else moved it; jog stopped")
            return
        }
        if (link.pendingLines >= 2) { tr("BUSY plines=${link.pendingLines} pend=$n"); return }

        val stp = step
        val vmax = lim.maxRateMmMin / 60.0                        // mm/s
        val vHand = min(handRate(now) * stp, vmax)
        // Lead = commanded minus reported, minus what the machine has travelled since that position
        // was read (dead reckoning), so stale status doesn't inflate it. The Z1 refreshes the position
        // it reports only every 250–370 ms and repeats it in between, so the reading is as old as the
        // first status that carried it, not the latest one. Counting from the latest made the lead look
        // up to ~0.3 s of travel too long: the feed swung ±10 % and clicks hit the cap and were dropped.
        if (st.mpos != lastMpos) { lastMpos = st.mpos; mposSince = st.atMs }
        val readAt = if (now - mposSince <= config.positionMaxAgeMs) mposSince else st.atMs
        val statusAgeS = (now - readAt + (link.latencyMs.value ?: 30) / 2) / 1000.0
        val rawLead = abs(target!! - pos)
        val lead = if (!stationary(st)) maxOf(0.0, rawLead - lastCmdV * statusAgeS) else rawLead
        // Feed: hand speed, corrected toward a lead of vHand × buffer.
        // Floor: a waiting click always moves at least fast enough to finish its step in ~0.3 s.
        // The hand-speed window is only 200 ms, so a click sent later than that (the first move
        // waits for Idle and a fresh status; on iPhone that took ~0.45 s) used to fall back to
        // the 5 mm/min minimum: a 10 mm pad tap became a two-minute crawl stuck in "Run".
        val vFloor = maxOf(config.minFeedMmMin / 60.0, vHand * 0.3, min(stp / 0.3, vmax))
        val vCmd = if (holdRate != null) (vHand * config.holdFeedFactor).coerceIn(config.minFeedMmMin / 60.0, vmax)
            else (vHand + (lead - vHand * config.bufferS) / config.correctS).coerceIn(vFloor, vmax)
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
            pending.addAndFetch(-sign(n.toDouble()).toInt() * drop)
            if (now - lastDropAt > 150) { onDrop(); lastDropAt = now }     // one thud per 150 ms, not a buzz
            PLog.i("JogEngine", "dropped $drop clicks (cap ${Fmt.fixed(cap, 2)} lead ${Fmt.fixed(lead, 2)})")
        }
        if (allowed < stp * 0.999) return

        var clicks = min(abs(pending.load()), floor(allowed / stp + 1e-9).toInt())
        if (clicks <= 0) return
        val dir = sign(pending.load().toDouble())
        var dist = dir * clicks * stp

        // Soft limits: clamp, and buzz when we hit one (the stock firmware alarms on a soft-limit hit).
        if (config.softLimitClamp && st.homed != false) {
            val t = target!! + dist
            val lo = lim.softMin; val hi = lim.softMax
            val clamped = t.coerceIn(lo ?: Double.NEGATIVE_INFINITY, hi ?: Double.POSITIVE_INFINITY)
            if (clamped != t) {
                dist = clamped - target!!
                pending.store(0); onLimit()
                if (abs(dist) < 1e-4) return
                clicks = abs(pending.load())
            }
        }

        val feed = (vCmd * 60.0).coerceIn(config.minFeedMmMin, lim.maxRateMmMin)
        lastCmdV = feed / 60.0
        pending.addAndFetch(-(dir * clicks).toInt().let { if (it == 0) 0 else it })
        val to = target!! + dist
        tr("SEND to=${f3(to)} d=${f3(dist)} k=$clicks F=${Fmt.fixed(feed, 0)} pos=${f3(pos)} rawLead=${f3(rawLead)} lead=${f3(lead)} " +
            "vHand=${f3(vHand * 60)} cap=${f3(cap)} readAge=${now - readAt} pendAfter=${pending.load()} plines=${link.pendingLines}")
        link.sendLine(link.dialect.jogTo(a, to, feed), quiet = true)
        burst[a] = (burst[a] ?: 0.0) + dist
        target = to
        pathLo = min(pathLo, to); pathHi = maxOf(pathHi, to)
        lastSendAt = now
    }

    /**
     * True when the reported machine position can't come from this session's moves: the jogged
     * axis outside the commanded path, or another axis moved. [PATH_TOLERANCE] covers status rounding.
     */
    private fun offPath(mpos: List<Double>, ai: Int, pos: Double, tol: Double): Boolean {
        if (pos < pathLo - tol || pos > pathHi + tol) return true
        // Z is never checked as an "other" axis: while moving it carries the Z1's reporting offset
        // (and a leveling grid's correction, which changes as X/Y move). X, Y and A have none.
        return startPos.indices.any { i -> i != ai && i != 2 && i < mpos.size && abs(mpos[i] - startPos[i]) > PATH_TOLERANCE }
    }

    /** Path / at-target tolerance for [axis]: Z while moving, before its offset is known, gets [Z_SLACK]. */
    private fun tolerance(axis: Char, st: MachineStatus): Double =
        if (axis == 'Z' && !st.isIdle && zRunOffset == null) Z_SLACK else PATH_TOLERANCE

    companion object {
        /** mm (° for A): status positions come with 3–4 decimals. */
        const val PATH_TOLERANCE = 0.02
        /** Z reading while moving, before its offset has been learned (mm). */
        const val Z_SLACK = 1.0
        /** "Run, feed 0" counts as standing still once it has lasted this long (ms). */
        const val SETTLE_MS = 600L
    }
}

/**
 * The machine is standing still: Idle, or Run with nothing moving. The Z1 reports Run the whole
 * time the spindle turns, so "Run, no job playing, actual feed 0" is a spindle on and the axes
 * still, and jogging is fine. A playing job (|P:) always counts as busy, even between moves.
 * Without a feed reading (other firmware) only Idle counts.
 */
internal fun stationary(st: MachineStatus): Boolean = st.isIdle ||
    (st.state.equals("Run", ignoreCase = true) && st.job == null && st.feedActual == 0.0)
