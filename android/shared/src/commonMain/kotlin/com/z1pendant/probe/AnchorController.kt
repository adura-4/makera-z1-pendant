package com.z1pendant.probe

import com.z1pendant.hotkeys.Hotkey
import com.z1pendant.isProbeTool
import com.z1pendant.link.ANCHOR_X_KEY
import com.z1pendant.link.ANCHOR_Y_KEY
import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineConfig
import com.z1pendant.link.MachineLink
import com.z1pendant.link.MachineStatus
import com.z1pendant.link.SetResult
import com.z1pendant.platform.PLog
import com.z1pendant.platform.Prefs
import com.z1pendant.platform.nowMs
import com.z1pendant.run.BED_X0
import com.z1pendant.run.BED_X1
import com.z1pendant.run.BED_Y0
import com.z1pendant.run.BED_Y1
import com.z1pendant.util.Fmt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** `coordinate.anchor1_*` as `on_config_reload` leaves them: a machine whose anchor was never set. */
private const val FIRMWARE_DEFAULT_X = -359.0
private const val FIRMWARE_DEFAULT_Y = -234.0

/** Shown on the per-run page before anything has homed; the run itself reads the live homed Z. */
const val NOMINAL_HOMED_Z = -1.0

/** A read-back may be a 2-decimal fallback, so it is compared against the value actually written. */
private const val READ_BACK_TOLERANCE = 0.0005

/** A calibration refines an existing anchor, so a larger shift than this is not a calibration. */
private const val MAX_ANCHOR_SHIFT = 10.0

/** How close to the pre-position counts as "the head never left it". */
private const val PARK_TOLERANCE = 0.5

/** Set once any calibration has completed; picks the tighter first-run Pre-position Z threshold. */
private const val CALIBRATED_KEY = "anchor.calibrated"

enum class AnchorStep { HOME, PROBE, READ, MOVE, PROBING, RESTORE }

sealed class AnchorState {
    data object Idle : AnchorState()
    /** The per-run questions, with what the answers imply. */
    data class Asking(val bracket: Bracket, val wasteboard: Boolean,
                      val topHeight: Double, val approachZ: Double) : AnchorState()
    /** [bracket] / [wasteboard] are the run's own answers, shown back while it works. */
    data class Running(val step: AnchorStep, val done: Set<AnchorStep>,
                       val bracket: Bracket, val wasteboard: Boolean) : AnchorState()
    data class Done(val result: AnchorResult) : AnchorState()
    /**
     * [changed] is what the machine is left holding, so a half-finished run is never silent.
     * [bracket] / [wasteboard] are the run's own choices, and a null in either means no retry is
     * offered (it would re-home and re-run blind); [halted] is set when the machine was reset or
     * alarmed (Stop, top-touch guard, firmware halt), which makes the restore an attempt, not a fact.
     */
    data class Failed(val reason: String, val changed: String, val bracket: Bracket? = null,
                      val wasteboard: Boolean? = null, val halted: Boolean = false,
                      val wrongProbe: Boolean = false) : AnchorState()
}

/** What the machine's config says against what the probe found. */
data class AnchorResult(val currentX: Double, val currentY: Double, val measuredX: Double, val measuredY: Double) {
    val dx get() = measuredX - currentX
    val dy get() = measuredY - currentY
}

/**
 * Calibrating anchor 1 (the L bracket's inside corner) in one press: home, swap to the 3D probe and
 * let the firmware calibrate it, move to the current anchor, run Makera's own `M480.8` inside-corner
 * probe, then offer to write what it found into `coordinate.anchor1_x/y`.
 *
 * The anti-goal is writing a wrong anchor silently: every value is read back from the machine, and
 * a write that only half succeeded is reported as a failure naming the key that did change.
 */
class AnchorController(
    private val link: MachineLink,
    private val probe: ProbeController,
    private val config: MachineConfig,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    /** Something that may move the machine was sent (own-motion tracking). */
    private val onSend: () -> Unit = {},
    /** Jogging off while the routine moves the machine. */
    private val setJogEnabled: (Boolean) -> Unit = {},
    /** The machine's X/Y travel as x0, x1, y0, y1. The one seam for reading it from the machine later. */
    private val travel: () -> List<Double> = { listOf(BED_X0, BED_X1, BED_Y0, BED_Y1) },
) {
    val setup = MutableStateFlow(loadSetup())
    val state = MutableStateFlow<AnchorState>(AnchorState.Idle)
    /** Set by a successful run whose top-face drop was off; the UI offers it once the result dialog is gone. */
    val zCorrection = MutableStateFlow<ZCorrection?>(null)

    private var job: Job? = null
    // Held here, not in the UI, so a retry after the screen is recreated still uses what was chosen.
    private var lastRun: Pair<Bracket, Boolean>? = null
    @kotlin.concurrent.Volatile private var stopRequested = false
    // ProbeController's runLines is private and single-consumer, so this run needs its own.
    private val lines = Channel<String>(Channel.UNLIMITED)
    @kotlin.concurrent.Volatile private var listening = false

    init {
        scope.launch { link.messages.collect { if (listening) lines.trySend(it) } }
    }

    // ---- setup ------------------------------------------------------------------------------
    fun update(s: AnchorSetup) {
        setup.value = s
        prefs.edit {
            putFloat("anchor.xOff", s.xOff.toFloat()); putFloat("anchor.yOff", s.yOff.toFloat())
            putFloat("anchor.depth", s.depth.toFloat()); putFloat("anchor.overshoot", s.overshoot.toFloat())
            putFloat("anchor.preX", s.preX.toFloat()); putFloat("anchor.preY", s.preY.toFloat())
            putFloat("anchor.referenceDz", s.referenceDz.toFloat())
            putFloat("anchor.thin", s.thin.toFloat()); putFloat("anchor.thick", s.thick.toFloat())
            putFloat("anchor.wasteboard", s.wasteboard.toFloat())
            putString("anchor.defaultBracket", s.defaultBracket.name)
            putBoolean("anchor.defaultWasteboard", s.defaultWasteboard)
        }
    }

    /** True once the settings have been saved at least once; until then a tap opens them, never a run. */
    fun isConfigured() = prefs.getString("anchor.defaultBracket", null) != null

    private fun loadSetup(): AnchorSetup {
        val d = AnchorSetup()
        fun f(key: String, def: Double) = prefs.getFloat(key, def.toFloat()).toDouble()
        return AnchorSetup(
            xOff = f("anchor.xOff", d.xOff), yOff = f("anchor.yOff", d.yOff),
            depth = f("anchor.depth", d.depth), overshoot = f("anchor.overshoot", d.overshoot),
            preX = f("anchor.preX", d.preX), preY = f("anchor.preY", d.preY),
            referenceDz = f("anchor.referenceDz", d.referenceDz),
            thin = f("anchor.thin", d.thin), thick = f("anchor.thick", d.thick),
            wasteboard = f("anchor.wasteboard", d.wasteboard),
            defaultBracket = Bracket.entries.firstOrNull { it.name == prefs.getString("anchor.defaultBracket", null) }
                ?: d.defaultBracket,
            defaultWasteboard = prefs.getBoolean("anchor.defaultWasteboard", d.defaultWasteboard),
        )
    }

    // ---- the per-run questions ----------------------------------------------------------------
    /**
     * The questions, answered as this session last answered them: a retry after a failure must not
     * silently revert to the defaults, because a wrong bracket or wasteboard moves the approach Z.
     */
    fun ask() {
        val (b, w) = lastRun ?: (setup.value.defaultBracket to setup.value.defaultWasteboard)
        choose(b, w)
    }

    /** Leaving the wizard drops the run's answers, so the next visit opens on the settings defaults. */
    fun forgetRun() { if (job?.isActive != true) lastRun = null }

    fun choose(bracket: Bracket, wasteboard: Boolean) {
        val s = setup.value
        state.value = AnchorState.Asking(bracket, wasteboard,
            s.topHeight(bracket, wasteboard), s.approachZ(NOMINAL_HOMED_Z, bracket, wasteboard))
    }

    /** Writes the suggested Pre-position Z. */
    fun acceptZCorrection() {
        val z = zCorrection.value ?: return
        update(setup.value.copy(referenceDz = z.suggestedZ))
        zCorrection.value = null
    }

    /** Changes nothing persistent, so the question comes back next run. */
    fun declineZCorrection() { zCorrection.value = null }

    fun reset() { if (job?.isActive != true) state.value = AnchorState.Idle }

    // ---- the run ------------------------------------------------------------------------------
    fun start(bracket: Bracket, wasteboard: Boolean) {
        if (job?.isActive == true) return
        lastRun = bracket to wasteboard
        zCorrection.value = null
        stopRequested = false
        job = scope.launch { runRoutine(bracket, wasteboard) }
    }

    /** Stop: abort now (Ctrl-X, via the probe controller). The machine needs an Unlock afterwards. */
    fun stop() {
        stopRequested = true
        probe.stop()
        job?.cancel()
    }

    private suspend fun runRoutine(b: Bracket, waste: Boolean) {
        val s = setup.value
        val done = mutableSetOf<AnchorStep>()
        fun show(step: AnchorStep) { state.value = AnchorState.Running(step, done.toSet(), b, waste) }

        var before: List<Double>? = null
        var feed: Double? = null
        var topDrop: Double? = null
        var zeroRestored = false
        var feedRestored = false
        var restoreNote: String? = null
        // Position-independent, so it goes back even when the work zero can't, and after a Stop.
        fun restoreFeed() {
            val f = feed
            if (feedRestored || f == null) return
            feedRestored = true
            link.dialect.feedOverride(f.toInt()).forEach { send(it) }   // M480.8 forces 100%
        }
        suspend fun restore() {
            try {
                val b = before
                if (zeroRestored || b == null || b.size < 2) return
                // G10 L20 works from where the machine IS; catching only ProbeStop lets a cancellation through.
                val st = try { idleStatus(RESTORE_TIMEOUT) } catch (e: ProbeStop) { null }
                if (st == null || st.mpos.size < 2) {
                    zeroRestored = true
                    restoreNote = "Your work zero was NOT restored: the machine never stopped moving, " +
                        "so putting it back would have set it from the wrong position. Set X0 and Y0 yourself."
                } else {
                    // X and Y only: offsets() carries the tool length offset on Z (undoXY's precedent).
                    link.dialect.setWorkPosition(mapOf(
                        'X' to st.mpos[0] - b[0], 'Y' to st.mpos[1] - b[1])).forEach { send(it) }
                    // Only once the send has gone out: a throw inside it must not claim the zero is back.
                    zeroRestored = true
                }
            } finally {
                restoreFeed()
            }
        }

        var failure: String? = null
        var wrongProbe = false
        setJogEnabled(false)
        listening = true
        drain()
        probe.openWizard()                           // forces a fresh calibration; also clears checkAs
        probe.checkAs = ProbeTool.PROBE_3D           // so the setter reading is checked against the 3D probe
        try {
            if (link.conn.value != ConnState.Connected) throw ProbeStop("The machine isn't connected.")
            val st0 = link.status.value ?: throw ProbeStop("No status from the machine yet.")
            if (!st0.isIdle) throw ProbeStop("The machine isn't idle (${st0.state}).")

            // Pre-flight, before anything moves: an anchor that isn't set must not be moved to.
            show(AnchorStep.HOME)
            val anchor = config.anchor()
                ?: throw ProbeStop("Couldn't read the machine's anchor 1 ($ANCHOR_X_KEY / $ANCHOR_Y_KEY).")
            if (!plausible(anchor.first, anchor.second)) throw ProbeStop(
                "This machine's anchor 1 reads ${f3(anchor.first)}, ${f3(anchor.second)}, which does not look " +
                    "set. Set it in Makera Studio first; this routine refines an existing anchor.")
            val target = (anchor.first + s.preX) to (anchor.second + s.preY)
            if (!plausible(target.first, target.second)) throw ProbeStop(
                "The pre-position ${f3(target.first)}, ${f3(target.second)} is outside the machine's travel.")

            // A touch-down inside a bracket recess never triggers, and the machine would drive on.
            val tipRadius = probe.setup.value.tip / 2
            notchProblem('X', s.touchPointX(), tipRadius, b)?.let { throw ProbeStop(it) }
            notchProblem('Y', s.touchPointY(), tipRadius, b)?.let { throw ProbeStop(it) }

            // 1 — home. M480 does nothing unhomed, and the anchor is only as good as the zero it is measured from.
            link.dialect.home().forEach { send(it) }
            val homedZ = requireIdle(HOME_TIMEOUT, "homing").mpos.getOrNull(2)
                ?: throw ProbeStop("The machine didn't report a Z position after homing.")
            done += AnchorStep.HOME

            // 2 — always calibrate: the probe can be swapped by hand, and this is the dead-probe test.
            show(AnchorStep.PROBE)
            loadAndCalibrate()
            done += AnchorStep.PROBE

            // 3 — the current anchor (read in the pre-flight; config caches it).
            show(AnchorStep.READ)
            done += AnchorStep.READ

            // 4 — move to the bracket: lift, traverse, descend. Each is confirmed before the next.
            show(AnchorStep.MOVE)
            val approach = s.approachZ(homedZ, b, waste)
            traverseTo(target.first, target.second)
            // Absolute, derived from the live homed Z.
            val st = sendConfirmed("G90 G53 G0 Z${f3(approach)}", "lowering to the bracket")
            done += AnchorStep.MOVE

            // 5 — Makera's own inside-corner probe.
            show(AnchorStep.PROBING)
            before = probe.offsets(st)
            feed = st.feedOverride
            var firstTouch = true
            drain()                                  // the calibration's echoes are not this run's
            runMakeraCommand(s.probeSetup(probe.setup.value.tip).makeraCommand()!!, lines, link, onSend,
                onTouch = { t ->
                    // Only the first touch is the top face; the wall touches are legitimately at depth.
                    if (firstTouch) {
                        firstTouch = false
                        topDrop = approach - t.z
                        if (!topTouchOk(t.z, approach, s.overshoot)) {
                            // probe.stop() is a Ctrl-X, so the restore goes into a just-reset machine: say so.
                            stopRequested = true
                            probe.stop()
                            throw ProbeStop(
                                "The top is at ${f3(t.z)}, not where a ${bracketWord(b)} bracket " +
                                    "${if (waste) "on the wasteboard" else "with the wasteboard removed"} would put " +
                                    "it (about ${f3(approach - NOMINAL_DROP)}). Check the bracket and the wasteboard.")
                        }
                    }
                })

            // 6 — it parks on the corner with the tip radius already compensated, so mpos IS the anchor.
            val parked = requireIdle(MOVE_TIMEOUT, "probing")
            val px = parked.mpos.getOrNull(0); val py = parked.mpos.getOrNull(1)
            if (px == null || py == null) throw ProbeStop(
                "The machine didn't report a position after probing, so the corner wasn't read.")
            val result = AnchorResult(anchor.first, anchor.second, px, py)
            // The head must have left the pre-position and found a corner near the stored anchor.
            measurementProblem(result, target, s.preX != 0.0 || s.preY != 0.0)?.let { throw ProbeStop(it) }
            done += AnchorStep.PROBING
            PLog.i("anchor", "measured ${f3(result.measuredX)}, ${f3(result.measuredY)} " +
                "(was ${f3(result.currentX)}, ${f3(result.currentY)})")

            // 7 — put the work zero and the feed override back.
            show(AnchorStep.RESTORE)
            restore()
            // A good measurement is worth nothing if the user isn't told their work zero is wrong.
            restoreNote?.let { throw ProbeStop("Measured ${f3(result.measuredX)}, ${f3(result.measuredY)}. $it") }
            done += AnchorStep.RESTORE
            val firstRun = !prefs.getBoolean(CALIBRATED_KEY, false)
            zCorrection.value = topDrop?.let { zCorrection(it, NOMINAL_DROP, firstRun, s.referenceDz) }
            prefs.edit { putBoolean(CALIBRATED_KEY, true) }
            state.value = AnchorState.Done(result)
        } catch (e: ProbeStop) {
            failure = e.message ?: "The routine stopped."
            wrongProbe = e.wrongProbe
        } catch (e: kotlinx.coroutines.CancellationException) {
            failure = "Stopped."
        } catch (e: Exception) {
            failure = e.message ?: "The routine failed."
        } finally {
            probe.checkAs = null
            listening = false
            // A Stop cancels this job, so the restore has to run uncancellably to go out at all.
            withContext(NonCancellable) { restore() }
            setJogEnabled(true)
            failure?.let { why ->
                // Only add the note if the failure message isn't already carrying it.
                val note = restoreNote?.takeIf { it !in why }?.let { " $it" } ?: ""
                state.value = failed(why, changedSince(before) + note,
                    // Read live: an Alarm can arrive by routes nobody threaded a flag through.
                    stopRequested || link.status.value?.isAlarm == true, wrongProbe)
            }
        }
    }

    /** The lift is confirmed first: a refused one must never let the X/Y move go out at depth. */
    private suspend fun traverseTo(x: Double, y: Double) {
        sendConfirmed("G90 G53 G0 Z${Hotkey.SAFE_Z}", "lifting to the safe Z")
        sendConfirmed("G90 G53 G0 X${f3(x)} Y${f3(y)}", "moving over the anchor")
    }

    private fun send(line: String) { onSend(); link.sendLine(line) }

    /** Send a move, fail on a refusal, and wait until the machine has actually finished it. */
    private suspend fun sendConfirmed(line: String, what: String): MachineStatus {
        val ack = CompletableDeferred<Pair<Boolean, String>>()
        onSend(); link.sendLine(line) { ok, reply -> ack.complete(ok to reply) }
        val (ok, reply) = withTimeoutOrNull(ACK_TIMEOUT) { ack.await() }
            ?: throw ProbeStop("The machine didn't answer \"$line\".")
        if (!ok) throw ProbeStop(
            if (reply.isBlank()) "The machine refused \"$line\"." else ProbeController.friendly(reply))
        return requireIdle(MOVE_TIMEOUT, what)
    }

    /** The machine, stopped and healthy. An Alarm here must stop the routine before the next line. */
    private suspend fun requireIdle(timeoutMs: Long, what: String): MachineStatus {
        val st = idleStatus(timeoutMs) ?: throw ProbeStop("The machine didn't finish $what.")
        if (st.isAlarm) throw ProbeStop("The machine stopped (Alarm) while $what.")
        return st
    }

    private fun drain() { while (lines.tryReceive().isSuccess) { /* old lines */ } }

    private suspend fun loadAndCalibrate() {
        val tool = link.status.value?.tool
        // M6 T9999 does nothing while the machine already says T9999.
        if (tool != null && isProbeTool(tool)) probe.reloadProbe() else probe.loadProbe()
        val action = withTimeoutOrNull(CAL_TIMEOUT) {
            while (true) {
                if (link.conn.value != ConnState.Connected) throw ProbeStop("The link to the machine dropped.")
                val a = probe.toolAction.value
                if (a?.error != null) return@withTimeoutOrNull a
                if (a?.stage == ToolAction.Stage.DONE && probe.calibrated.value) return@withTimeoutOrNull a
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: throw ProbeStop("The probe didn't finish calibrating.")
        action.error?.let { throw ProbeStop(it) }

        // A wrong-length probe probes at the wrong depth, and nothing downstream would catch it.
        val c = probe.lengthCheck.value ?: throw ProbeStop("The calibration didn't report a probe length.")
        if (!c.ok) throw ProbeStop(
            "The probe on the setter reads ${f3(c.z)}, which isn't the 3D probe" +
                (c.other?.let { " (it reads like the ${it.noun})" } ?: "") +
                ". This routine only works with the 3D probe.", wrongProbe = true)
    }

    // ---- saving ---------------------------------------------------------------------------------
    /**
     * Write both keys, then read them back. True only when the machine's config really holds the
     * measured anchor: an echo proves the line arrived, not that the file changed.
     */
    suspend fun save(r: AnchorResult): Boolean {
        anchorProblem(r)?.let {
            fail(it, "The machine's config is unchanged.")
            return false
        }
        val x = write(ANCHOR_X_KEY, r.measuredX)
        if (x.wrote == null) {
            fail("Couldn't write $ANCHOR_X_KEY: ${x.error}. $ANCHOR_Y_KEY wasn't sent.",
                "$ANCHOR_X_KEY: ${x.error}. $ANCHOR_Y_KEY wasn't sent.")
            return false
        }
        val y = write(ANCHOR_Y_KEY, r.measuredY)
        if (y.wrote == null) {
            fail("$ANCHOR_X_KEY was written (${f3(x.wrote)}) but $ANCHOR_Y_KEY wasn't: ${y.error}. " +
                "The machine's anchor is half changed — run this again, or set $ANCHOR_Y_KEY in Makera Studio.",
                "$ANCHOR_X_KEY was written (${f3(x.wrote)}); $ANCHOR_Y_KEY: ${y.error}.")
            return false
        }
        config.invalidateAnchor()
        val back = config.anchor()
        if (back == null || abs(back.first - x.wrote) > READ_BACK_TOLERANCE ||
            abs(back.second - y.wrote) > READ_BACK_TOLERANCE) {
            fail("The machine acknowledged both values but reads back " +
                (back?.let { "${f3(it.first)}, ${f3(it.second)}" } ?: "nothing") +
                " instead of ${f3(x.wrote)}, ${f3(y.wrote)}. Check them in Makera Studio before using the machine.",
                "Both values were sent, but the machine doesn't read them back.")
            return false
        }
        PLog.i("anchor", "saved ${f3(x.wrote)}, ${f3(y.wrote)}")
        return true
    }

    private fun fail(reason: String, changed: String) { state.value = failed(reason, changed, false) }

    private fun failed(reason: String, changed: String, halted: Boolean, wrongProbe: Boolean = false) =
        AnchorState.Failed(reason, changed, lastRun?.first, lastRun?.second, halted, wrongProbe)

    /** [wrote] is the value the machine took, which the 2-decimal fallback rounds. */
    private class Write(val wrote: Double?, val error: String? = null)

    private suspend fun write(key: String, v: Double): Write = when (config.set(key, v, 3)) {
        SetResult.Acknowledged -> Write(rounded(v, 3))
        // The firmware writes in place and refuses a value too long for the line; 2 dp is Studio's, so it fits.
        SetResult.NoSpace -> when (config.set(key, v, 2)) {
            SetResult.Acknowledged -> Write(rounded(v, 2))
            SetResult.NoSpace -> Write(null, "the config line has no room for it, even at 2 decimals")
            SetResult.TimedOut -> readBack(key, v)
        }
        SetResult.TimedOut -> readBack(key, v)
    }

    /** No acknowledgement is not proof the write failed, so ask the machine what it now holds. */
    private suspend fun readBack(key: String, v: Double): Write {
        val now = config.get(key)
        return when {
            now == null -> Write(null, "the machine neither acknowledged it nor can say what $key now holds")
            abs(now - rounded(v, 3)) <= READ_BACK_TOLERANCE -> Write(rounded(v, 3))
            abs(now - rounded(v, 2)) <= READ_BACK_TOLERANCE -> Write(rounded(v, 2))
            else -> Write(null, "the machine didn't acknowledge it and still reads ${f3(now)}")
        }
    }

    private fun rounded(v: Double, decimals: Int) = Fmt.fixed(v, decimals).toDouble()

    /**
     * The firmware only reads `anchor1_*` at config load, so the new anchor needs a reboot. This is
     * the console `reset` command (SimpleShell), not link.reset(), which is the Ctrl-X emergency
     * halt and reloads nothing.
     */
    fun restart() = link.sendLine("reset")

    // ---- is this really a measured anchor? ------------------------------------------------------
    /** Checks any anchor must pass: inside the travel, not a firmware default, and near the old one. */
    private fun anchorProblem(r: AnchorResult): String? {
        val at = "${f3(r.measuredX)}, ${f3(r.measuredY)}"
        if (!plausible(r.measuredX, r.measuredY))
            return "$at isn't a plausible anchor, so it wasn't saved. Check the bracket, then run this again."
        val off = maxOf(abs(r.dx), abs(r.dy))
        if (off > MAX_ANCHOR_SHIFT)
            return "A corner was found at $at, but it is ${f3(off)} mm from the stored anchor " +
                "(${f3(r.currentX)}, ${f3(r.currentY)}) — a relocation, not a calibration — so it " +
                "wasn't saved. Check the bracket is the one the anchor was set for, or set the anchor in " +
                "Makera Studio first."
        return null
    }

    /** Parking back on the pre-position means no corner was found; indistinguishable if it IS the anchor. */
    private fun measurementProblem(r: AnchorResult, target: Pair<Double, Double>, offsetUsed: Boolean): String? {
        if (offsetUsed && abs(r.measuredX - target.first) <= PARK_TOLERANCE &&
            abs(r.measuredY - target.second) <= PARK_TOLERANCE)
            return "The routine finished back at the pre-position ${f3(target.first)}, " +
                "${f3(target.second)}, so no corner was found. Nothing was measured."
        return anchorProblem(r)
    }

    // ---- the machine's travel -------------------------------------------------------------------
    private fun plausible(x: Double, y: Double): Boolean {
        val (x0, x1, y0, y1) = travel()
        if (x < x0 || x > x1 || y < y0 || y > y1) return false
        return !(abs(x - FIRMWARE_DEFAULT_X) < 1e-3 && abs(y - FIRMWARE_DEFAULT_Y) < 1e-3)
    }

    // ---- waiting ---------------------------------------------------------------------------------
    /** The first Idle status taken after now, once nothing of ours is still waiting for an answer. */
    private suspend fun idleStatus(timeoutMs: Long): MachineStatus? {
        val since = nowMs()
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                if (link.conn.value != ConnState.Connected) throw ProbeStop("The link to the machine dropped.")
                val s = link.status.value
                if (s != null && s.atMs > since + 150 && s.isIdle && link.pendingLines == 0) return@withTimeoutOrNull s
                if (s?.isAlarm == true) return@withTimeoutOrNull s
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    }

    /** What the machine is left holding, from the work offsets now against those before the probe. */
    private fun changedSince(before: List<Double>?): String {
        if (before == null) return NOTHING_CHANGED
        val st = link.status.value ?: return "Unknown: no status from the machine."
        val now = probe.offsets(st)
        val ch = "XYZ".filterIndexed { i, _ ->
            i < now.size && i < before.size && abs(now[i] - before[i]) > 0.0005
        }
        return if (ch.isEmpty()) WORK_ZERO_UNCHANGED
        else "Your " + ch.toList().joinToString(" and ") + "0 " +
            (if (ch.length == 1) "was" else "were") + " changed by the probing routine."
    }

    private fun bracketWord(b: Bracket) = if (b == Bracket.THIN) "thin" else "thick"

    private companion object {
        const val HOME_TIMEOUT = 120_000L
        const val CAL_TIMEOUT = 240_000L
        const val MOVE_TIMEOUT = 120_000L
        const val RESTORE_TIMEOUT = 10_000L
        const val ACK_TIMEOUT = 15_000L
    }
}
