package com.z1pendant

import android.os.SystemClock
import com.z1pendant.link.ANCHOR_X_KEY
import com.z1pendant.link.ANCHOR_Y_KEY
import com.z1pendant.link.ConnState
import com.z1pendant.link.Dialect
import com.z1pendant.link.GrblishDialect
import com.z1pendant.link.MachineConfig
import com.z1pendant.link.MachineLink
import com.z1pendant.link.MachineStatus
import com.z1pendant.link.MachineTarget
import com.z1pendant.platform.Prefs
import com.z1pendant.platform.PrefsEditor
import com.z1pendant.probe.AnchorController
import com.z1pendant.probe.AnchorResult
import com.z1pendant.probe.AnchorSetup
import com.z1pendant.probe.AnchorState
import com.z1pendant.probe.AnchorStep
import com.z1pendant.probe.Bracket
import com.z1pendant.probe.changedText
import com.z1pendant.probe.ProbeController
import com.z1pendant.probe.ProbeTool
import com.z1pendant.probe.ToolAction
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The anchor 1 calibration routine against a fake machine: the pre-flight refusals, the pinned
 * probe check, the lift-traverse-descend rule, the top-touch sanity check, the work-offset restore,
 * and the save / read-back / restart path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnchorControllerTest {
    private val link = AnchorFakeLink()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val prefs = MapPrefs()
    private lateinit var probe: ProbeController
    private lateinit var config: MachineConfig
    private lateinit var anchor: AnchorController

    @Before fun setUp() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000 }
        link.begin()
        probe = ProbeController(link, scope, prefs)
        config = MachineConfig(link, scope)
        anchor = AnchorController(link, probe, config, scope, prefs)
        // Stands in for the user swapping the probe in and pressing the machine's button.
        scope.launch {
            probe.toolAction.collect { if (it?.stage == ToolAction.Stage.WAITING) probe.continueToolChange() }
        }
    }

    @After fun tearDown() {
        scope.cancel()
        unmockkStatic(SystemClock::class)
    }

    // ---- helpers ----------------------------------------------------------------------------
    private suspend fun await(timeoutMs: Long = 30_000, cond: () -> Boolean) =
        withTimeout(timeoutMs) { while (!cond()) delay(20) }

    private suspend fun runToEnd(bracket: Bracket = Bracket.THIN, waste: Boolean = true): AnchorState {
        anchor.start(bracket, waste)
        await { anchor.state.value is AnchorState.Done || anchor.state.value is AnchorState.Failed }
        return anchor.state.value
    }

    private fun failed() = anchor.state.value as AnchorState.Failed

    /** Lines that move the machine, in the order they went out (homing included). */
    private fun motion() = link.lines.filter { it == "\$H" || it.contains("G0") || it.contains("G1 ") }

    // ---- Review Focus 2 ---------------------------------------------------------------------
    @Test fun refusesWhenTheAnchorCannotBeRead() = runBlocking {
        link.cfg.clear()                                  // config-get is never answered
        val s = runToEnd()
        assertTrue(s.toString(), s is AnchorState.Failed)
        assertTrue(failed().reason, ANCHOR_X_KEY in failed().reason)
        assertEquals("no motion was sent: ${link.lines}", emptyList<String>(), motion())
        assertFalse("homing is motion", link.lines.contains("\$H"))
    }

    // ---- Review Focus 3 ---------------------------------------------------------------------
    @Test fun refusesAnImplausibleAnchor() = runBlocking {
        link.cfg[ANCHOR_X_KEY] = -359.0                   // the firmware's own defaults
        link.cfg[ANCHOR_Y_KEY] = -234.0
        val s = runToEnd()
        assertTrue(s.toString(), s is AnchorState.Failed)
        assertTrue(failed().reason, failed().reason.contains("Makera Studio"))
        assertEquals("no motion was sent: ${link.lines}", emptyList<String>(), motion())
        assertFalse("homing is motion", link.lines.contains("\$H"))
    }

    // ---- notch zones --------------------------------------------------------------------------
    private suspend fun assertRefusedBeforeMoving(bracket: Bracket, s: AnchorSetup, vararg words: String) {
        anchor.update(s)
        val st = runToEnd(bracket)
        assertTrue(st.toString(), st is AnchorState.Failed)
        for (w in words) assertTrue(failed().reason, w in failed().reason)
        assertEquals("no motion was sent: ${link.lines}", emptyList<String>(), motion())
        assertFalse("homing is motion", link.lines.contains("\$H"))
    }

    @Test fun theRunRefusesABracketWhoseZoneTheOffsetsHit() = runBlocking {
        assertRefusedBeforeMoving(Bracket.THIN, AnchorSetup(xOff = 34.0), "30.00", "cut-out", "X touches")
    }

    @Test fun theSameOffsetsAreFineOnTheThickBracket() = runBlocking {
        anchor.update(AnchorSetup(xOff = 34.0))
        val st = runToEnd(Bracket.THICK)
        assertFalse(st.toString() + link.lines, st is AnchorState.Failed && "cut-out" in st.reason)
    }

    @Test fun theRunWidensTheZoneByTheTipRadius() = runBlocking {
        assertRefusedBeforeMoving(Bracket.THIN, AnchorSetup(xOff = 28.5), "24.50", "cut-out")
        anchor.reset(); anchor.update(AnchorSetup(xOff = 26.9))   // 22.9 clears a 1 mm radius, 23.0 does not
        assertNull(runToEnd(Bracket.THIN).let { (it as? AnchorState.Failed)?.reason?.takeIf { r -> "cut-out" in r } })
    }

    @Test fun theRunChecksTheYAxisToo() = runBlocking {
        assertRefusedBeforeMoving(Bracket.THICK, AnchorSetup(yOff = 6.0), "2.00", "relief", "Y touches")
    }

    @Test fun pinsCheckAsToTheThreeDProbe() = runBlocking {
        link.holdAtToolChange = true                      // park the run in the probe step
        anchor.start(Bracket.THIN, true)
        await { (anchor.state.value as? AnchorState.Running)?.step == AnchorStep.PROBE }
        assertEquals(ProbeTool.PROBE_3D, probe.checkAs)
        anchor.stop()
        await { anchor.state.value is AnchorState.Failed }
        assertNull("checkAs must be cleared on the way out", probe.checkAs)
    }

    @Test fun readsTheAnchorFromMposAfterDoneAtc() = runBlocking {
        val s = runToEnd()
        assertTrue(s.toString(), s is AnchorState.Done)
        val r = (s as AnchorState.Done).result
        assertEquals(AnchorResult(-190.620, -193.990, -190.634, -193.971), r)
        assertEquals(-0.014, r.dx, 1e-9)
        assertEquals(0.019, r.dy, 1e-9)
        // The approach went out as an absolute machine Z derived from the live homed Z of -1.
        assertTrue(link.lines.toString(), link.lines.any { it.contains("G53") && it.contains("Z-66.500") })
        assertFalse("never a relative approach", link.lines.any { it.contains("G91") })
        assertEquals("M480.8 X14.000 Y14.000 Z1.800 D2.000", link.lines.first { it.startsWith("M480") })
    }

    // ---- Pre-position Z correction ----------------------------------------------------------
    @Test fun noPromptWhenTheDropIsAsExpected() = runBlocking {
        assertTrue(runToEnd() is AnchorState.Done)
        assertNull(anchor.zCorrection.value)
    }

    @Test fun offersACorrectionOnTheFirstRunAtHalfAMillimetre() = runBlocking {
        link.topTouchZ = -66.5 - 5.7                      // drop 5.7 from the approach of -66.5
        assertTrue(runToEnd() is AnchorState.Done)
        assertEquals(-65.7, anchor.zCorrection.value!!.suggestedZ, 1e-6)
    }

    @Test fun theSameDropIsQuietOnALaterRun() = runBlocking {
        assertTrue(runToEnd() is AnchorState.Done)        // first run, drop 5
        anchor.reset()
        link.topTouchZ = -66.5 - 5.7
        assertTrue(runToEnd() is AnchorState.Done)
        assertNull(anchor.zCorrection.value)
    }

    @Test fun aLaterRunStillPromptsPastAMillimetre() = runBlocking {
        assertTrue(runToEnd() is AnchorState.Done)
        anchor.reset()
        link.topTouchZ = -66.5 - 6.4
        assertTrue(runToEnd() is AnchorState.Done)
        assertEquals(-66.4, anchor.zCorrection.value!!.suggestedZ, 1e-6)
    }

    @Test fun aFailedRunNeverOffersACorrection() = runBlocking {
        link.topTouchZ = -78.0
        assertTrue(runToEnd() is AnchorState.Failed)
        assertNull(anchor.zCorrection.value)
    }

    @Test fun acceptingWritesPrePositionZAndKeepsTheRest() = runBlocking {
        link.topTouchZ = -66.5 - 6.0
        runToEnd()
        anchor.acceptZCorrection()
        assertEquals(-66.0, anchor.setup.value.referenceDz, 1e-6)
        assertEquals(AnchorSetup().overshoot, anchor.setup.value.overshoot, 0.0)
        assertNull(anchor.zCorrection.value)
        // Persisted, not just held in memory.
        val reloaded = AnchorController(link, probe, config, scope, prefs)
        assertEquals(-66.0, reloaded.setup.value.referenceDz, 1e-6)
    }

    @Test fun notNowChangesNothingAndAsksAgainNextRun() = runBlocking {
        link.topTouchZ = -66.5 - 6.0
        runToEnd()
        anchor.declineZCorrection()
        assertEquals(-65.0, anchor.setup.value.referenceDz, 0.0)
        anchor.reset()
        // Declined on the first run, so the later-run threshold now applies: 6.4 still asks again.
        link.topTouchZ = -66.5 - 6.4
        runToEnd()
        assertEquals(-66.4, anchor.zCorrection.value!!.suggestedZ, 1e-6)
    }

    @Test fun theAllowanceSetsTheStopPoint() = runBlocking {
        anchor.update(AnchorSetup(overshoot = 0.5))       // limit: drop 5.5
        link.topTouchZ = -66.5 - 6.0
        assertTrue(runToEnd() is AnchorState.Failed)
        assertTrue("the run must be aborted", link.resets > 0)
        assertNull(anchor.zCorrection.value)
    }

    @Test fun aDropWithinTheAllowanceIsNotStopped() = runBlocking {
        anchor.update(AnchorSetup(overshoot = 3.0))       // limit: drop 8
        link.topTouchZ = -66.5 - 7.9
        assertTrue(runToEnd() is AnchorState.Done)
    }

    // ---- Review Focus 5 ---------------------------------------------------------------------
    @Test fun aRetryKeepsTheRunsAnswersAndLeavingTheWizardForgetsThem() = runBlocking {
        link.cfg.clear()                                  // fails early, before any motion
        runToEnd(Bracket.THICK, waste = false)
        assertTrue(anchor.state.value.toString(), anchor.state.value is AnchorState.Failed)

        // "Try again" re-asks. Reverting to the defaults here would silently change the approach Z.
        anchor.ask()
        val retry = anchor.state.value as AnchorState.Asking
        assertEquals(Bracket.THICK, retry.bracket)
        assertFalse("the wasteboard answer must survive the retry", retry.wasteboard)

        // Leaving drops them, so the next visit opens on the settings defaults again.
        anchor.forgetRun()
        anchor.ask()
        val fresh = anchor.state.value as AnchorState.Asking
        assertEquals(AnchorSetup().defaultBracket, fresh.bracket)
        assertEquals(AnchorSetup().defaultWasteboard, fresh.wasteboard)
    }

    @Test fun twoReadersOfOneKeyBothGetTheAnswer() = runBlocking {
        // One MachineConfig is shared by the Run flow and this routine, so the same key can be in
        // flight twice; a single waiter slot dropped the earlier caller and left it to time out.
        // cfg is cleared so nothing answers automatically and both callers are waiting at once.
        link.cfg.clear()
        val a = async { config.get(ANCHOR_X_KEY) }
        val b = async { config.get(ANCHOR_X_KEY) }
        await { link.gets >= 2 }
        link.messages.emit("sd: $ANCHOR_X_KEY is set to -190.620")
        assertEquals("the first reader must not be dropped", -190.620, a.await() ?: Double.NaN, 1e-9)
        assertEquals(-190.620, b.await() ?: Double.NaN, 1e-9)
    }

    @Test fun theConfigWriteOutlivesTheScreenThatStartedIt() = runBlocking {
        // save() writes X then Y. On the screen's own scope a recreation (a rotation) could cancel
        // it between the two and leave a mismatched pair, so it runs on the controller's scope.
        val screen = CoroutineScope(Dispatchers.Default)
        // join() waits only for saveMeasured to hand the write to the controller's scope, so the
        // cancel below is deterministic; awaiting the transient saving flag would be a race.
        screen.launch { anchor.saveMeasured(AnchorResult(-190.620, -193.990, -190.634, -193.971)) }.join()
        screen.cancel()                                   // the screen goes away mid-write
        await { anchor.saved.value }
        assertEquals(-190.634, link.cfg[ANCHOR_X_KEY]!!, 1e-9)
        assertEquals(-193.971, link.cfg[ANCHOR_Y_KEY]!!, 1e-9)
    }

    @Test fun theMachineDwellsAfterTheDescentAndBeforeTheProbe() = runBlocking {
        // A G4 the machine runs in its own queue, not a pause on the phone: M480.8 reads the
        // machine Z when it is parsed, and a rapid stop is still settling when Idle first appears.
        runToEnd()
        val dwell = link.lines.indexOfFirst { it.startsWith("G4 S") }
        val probe = link.lines.indexOfFirst { it.startsWith("M480.") }
        assertTrue("a dwell must be sent: ${link.lines}", dwell >= 0)
        assertTrue("the dwell must come before the probe: ${link.lines}", dwell < probe)
    }

    @Test fun stopsWhenTheTopTouchIsTooLow() = runBlocking {
        // The check accepts down to -73.500 (approach -66.5, nominal drop 5, allowance 2); this is well past it.
        link.topTouchZ = -78.0
        val s = runToEnd()
        assertTrue(s.toString(), s is AnchorState.Failed)
        assertTrue(failed().reason, failed().reason.contains("thin", true))
        assertTrue(failed().reason, failed().reason.contains("wasteboard", true))
        assertTrue("the run must be aborted, not left to plunge", link.resets > 0)
        // The firmware's own routine was cut off: its wall touches never reached the result.
        assertTrue(anchor.state.value is AnchorState.Failed)
    }

    // ---- Review Focus 4 ---------------------------------------------------------------------
    @Test fun restoresWorkOffsetsAfterAStop() = runBlocking {
        link.holdAtProbing = true                         // park the run inside M480.8
        anchor.start(Bracket.THIN, true)
        await { link.lines.any { it.startsWith("M480") } }
        anchor.stop()
        await { anchor.state.value is AnchorState.Failed }
        val restore = link.lines.filter { it.startsWith("G10") }
        assertEquals("one restore line: ${link.lines}", 1, restore.size)
        assertTrue(restore[0], restore[0].contains("X"))
        assertTrue(restore[0], restore[0].contains("Y"))
        assertFalse("Z0 is tied to the tool that set it", restore[0].contains("Z"))
        // The feed override M480.8 forces to 100% is put back where it was.
        assertTrue(link.lines.toString(), link.lines.any { it.startsWith("M220S") })
        // No recovery move at all, so nothing traverses at depth.
        assertNoTraverseAtDepth()
    }

    @Test fun aFailureKeepsTheBracketAndWasteboardTheRunUsed() = runBlocking {
        link.topTouchZ = -78.0
        runToEnd(Bracket.THICK, waste = false)
        assertEquals(Bracket.THICK, failed().bracket)
        assertEquals(false, failed().wasteboard)
    }

    @Test fun aSaveFailureAlsoKeepsThem() = runBlocking {
        anchor.start(Bracket.THICK, false)
        await { anchor.state.value is AnchorState.Done }
        link.cfg.clear()
        anchor.save(AnchorResult(-190.0, -194.0, -150.0, -194.0))   // a relocation, refused
        assertEquals(Bracket.THICK, failed().bracket)
        assertEquals(false, failed().wasteboard)
        assertFalse(failed().halted)
    }

    @Test fun aStopIsRecordedOnTheFailure() = runBlocking {
        link.holdAtProbing = true
        anchor.start(Bracket.THIN, true)
        await { link.lines.any { it.startsWith("M480") } }
        anchor.stop()
        await { anchor.state.value is AnchorState.Failed }
        assertTrue(failed().halted)
    }

    @Test fun aFailureThatWasNotAStopIsNotMarkedStopped() = runBlocking {
        link.refuseLine = "G90 G53 G0 Z-2"                // refused outright: nothing resets the machine
        runToEnd()
        assertFalse(failed().halted)
        assertEquals("nothing reset the machine", 0, link.resets)
    }

    /** The top-touch guard resets the machine, so its failure must carry the post-stop caution. */
    @Test fun aTopTouchFailureIsReportedAsAStop() = runBlocking {
        link.topTouchZ = -78.0
        val s = runToEnd()
        assertTrue(s.toString(), s is AnchorState.Failed)
        assertTrue("the guard really does reset the machine", link.resets > 0)
        assertTrue("a reset mid-probe is a stop", failed().halted)
        val text = changedText(failed().changed, failed().halted)
        assertTrue(text, text.contains("may have refused it"))
        assertTrue(text, text.contains("Check X0 and Y0"))
    }

    /** An Alarm sets neither Stop nor the guard, yet the restore is refused by a halted machine. */
    @Test fun aFailureWhileAlarmedIsMarkedHaltedAndCarriesTheCaution() = runBlocking {
        link.alarmAfterProbe = true                       // the zero has moved, then the machine halts
        val s = runToEnd()
        assertTrue(s.toString(), s is AnchorState.Failed)
        assertEquals("no Stop and no reset", 0, link.resets)
        assertTrue("an alarmed machine is halted", failed().halted)
        val text = changedText(failed().changed, failed().halted)
        assertTrue(text, text.contains("Check X0 and Y0"))
    }

    // ---- the probe-length check aborts before anything moves to the bracket -------------------
    /** Only homing and the tool change may have gone out: no lift, no traverse, no descent, no M480. */
    private fun assertNeverReachedTheBracket() {
        assertEquals("only homing moved: ${link.lines}", listOf("\$H"), motion())
        assertFalse("M480.8 must not be sent", link.lines.any { it.startsWith("M480") })
    }

    @Test fun aWrongProbeLengthStopsBeforeMovingToTheBracket() = runBlocking {
        // The setter reads -69.570, so a learned -50 makes this a different probe.
        prefs.edit { putString("probe.len.PROBE_3D", "-50.000") }
        val s = runToEnd()
        assertTrue(s.toString(), s is AnchorState.Failed)
        assertTrue(failed().reason, failed().reason.contains("only works with the 3D probe"))
        assertTrue("the wrong-probe flag must be set", failed().wrongProbe)
        assertNeverReachedTheBracket()
    }

    @Test fun aMissingProbeLengthStopsBeforeMovingToTheBracket() = runBlocking {
        link.calSetterMiss = true                         // calibrates, but reports no setter touch
        val s = runToEnd()
        assertTrue(s.toString(), s is AnchorState.Failed)
        assertTrue(failed().reason, failed().reason.contains("didn't report a probe length"))
        assertFalse("only a rejected probe is a wrong-probe failure", failed().wrongProbe)
        assertNeverReachedTheBracket()
    }

    @Test fun theFeedOverrideIsPutBackOnTheSuccessPath() = runBlocking {
        assertTrue(runToEnd() is AnchorState.Done)
        assertEquals("the feed override must go back exactly once", 1,
            link.lines.count { it.startsWith("M220S") })
        assertTrue("85 was the override before the run", link.lines.contains("M220S85"))
    }

    @Test fun liftsBeforeEveryTraverse() = runBlocking {
        assertTrue(runToEnd() is AnchorState.Done)
        assertNoTraverseAtDepth()
        // Lift, traverse, descend, in that order.
        val m = motion()
        val lift = m.indexOfFirst { it.contains("Z-2") }
        val xy = m.indexOfFirst { it.contains("X-194.620") }
        val down = m.indexOfFirst { it.contains("Z-66.500") }
        assertTrue(m.toString(), lift >= 0 && xy > lift && down > xy)
    }

    /** Every X/Y move must be sent while the last commanded Z is the safe Z. */
    private fun assertNoTraverseAtDepth() {
        val axisRe = Regex("""([XYZ])\s*(-?\d+(?:\.\d+)?)""")
        var z = 0.0
        var sawTraverse = false
        for (line in link.lines) {
            if (!line.contains("G0")) continue
            val words = axisRe.findAll(line).associate { it.groupValues[1] to it.groupValues[2].toDouble() }
            if ("X" in words || "Y" in words) {
                sawTraverse = true
                assertEquals("traversed at depth: \"$line\" (Z was $z) in ${link.lines}", -2.0, z, 1e-9)
            }
            words["Z"]?.let { z = it }
        }
        assertTrue("the check never saw a traverse: ${link.lines}", sawTraverse || link.lines.none { it == "\$H" })
    }

    // ---- Review Focus 1 ---------------------------------------------------------------------
    @Test fun savesBothKeysAndReportsPartialFailure() = runBlocking {
        link.setFails += ANCHOR_Y_KEY                     // X acknowledges, Y never answers
        val r = AnchorResult(-190.620, -193.990, -190.634, -193.971)
        assertFalse("a half-written anchor is not a success", anchor.save(r))
        val f = failed()
        assertTrue(f.reason, ANCHOR_X_KEY in f.reason)
        assertTrue("the reason must say X was written", f.reason.contains("-190.634"))
        assertTrue(f.changed, ANCHOR_X_KEY in f.changed && ANCHOR_Y_KEY in f.changed)
        assertEquals("X really was written", -190.634, link.cfg[ANCHOR_X_KEY]!!, 1e-9)
    }

    @Test fun fallsBackToTwoDecimalsWhenTheLineHasNoRoom() = runBlocking {
        link.noSpaceAt3 += ANCHOR_X_KEY
        assertTrue(anchor.save(AnchorResult(-190.620, -193.990, -190.634, -193.971)))
        assertEquals(-190.63, link.cfg[ANCHOR_X_KEY]!!, 1e-9)
        assertEquals(-193.971, link.cfg[ANCHOR_Y_KEY]!!, 1e-9)
        val x = link.lines.filter { it.startsWith("config-set sd $ANCHOR_X_KEY") }
        assertEquals("3 decimals first, then 2: $x", 2, x.size)
        assertTrue(x[0], x[0].endsWith("-190.634"))
        assertTrue(x[1], x[1].endsWith("-190.63"))
    }

    @Test fun invalidatesTheAnchorCacheOnSave() = runBlocking {
        assertEquals(-190.620 to -193.990, config.anchor())
        val gets = link.gets
        assertEquals("cached", -190.620 to -193.990, config.anchor())
        assertEquals("the cache answered, nothing was asked", gets, link.gets)
        assertTrue(anchor.save(AnchorResult(-190.620, -193.990, -190.634, -193.971)))
        // The save read both keys back, and a later read sees the new value.
        assertEquals(-190.634 to -193.971, config.anchor())
        assertTrue("the machine was asked again", link.gets > gets)
    }

    @Test fun restartSendsTheResetConsoleCommand() = runBlocking {
        anchor.restart()
        assertEquals(listOf("reset"), link.lines)
        assertEquals("link.reset() is the Ctrl-X halt, not a reboot", 0, link.resets)
    }

    // ---- ruling 5: the approach Z must come from the live homed Z, not a constant -------------
    @Test fun approachZComesFromTheLiveHomedZ() = runBlocking {
        link.homeZ = -3.0                                 // -3 + -65 + 0 = -68, not the nominal -66
        assertTrue(runToEnd() is AnchorState.Done)
        assertTrue(link.lines.toString(), link.lines.any { it.contains("G53") && it.contains("Z-68.000") })
        assertFalse("a hardcoded datum would send -66.000",
            link.lines.any { it.contains("Z-66.000") || it.contains("Z-66.500") })
    }

    // ---- ruling 6: the sanity check is the FIRST touch only ----------------------------------
    @Test fun onlyTheFirstTouchIsSanityChecked() = runBlocking {
        // The wall touches are at plunge depth, far below the approach: checking them would be wrong.
        link.wallTouchZ = -90.0
        val s = runToEnd()
        assertTrue("the wall touches must not trip the top check: $s", s is AnchorState.Done)
    }

    // ---- ruling 10: an echo is not proof the file changed ------------------------------------
    @Test fun failsWhenTheReadBackDoesNotMatch() = runBlocking {
        link.storeInstead = -1.234                        // acks the value sent, stores something else
        assertFalse(anchor.save(AnchorResult(-190.620, -193.990, -190.634, -193.971)))
        val f = failed()
        assertTrue(f.reason, f.reason.contains("-1.234"))
        assertTrue(f.reason, f.reason.contains("Makera Studio"))
    }

    // ---- ruling 7: the restore must put the offsets back exactly -----------------------------
    @Test fun restoresTheWorkOffsetsExactly() = runBlocking {
        val start = link.offsets()
        assertTrue(runToEnd() is AnchorState.Done)
        val end = link.offsets()
        assertEquals("X0 back where it started", start[0], end[0], 1e-6)
        assertEquals("Y0 back where it started", start[1], end[1], 1e-6)
        // M480.8 re-zeroes Z on the bracket's top face and that is deliberately left in place.
        assertEquals("Z0 is left as the probing routine set it", -71.5, end[2], 1e-6)
        assertTrue("Z0 really did change", kotlin.math.abs(end[2] - start[2]) > 1.0)
    }

    // ---- ruling 9: the 2-decimal fallback, on either key, and when it also fails -------------
    @Test fun fallsBackToTwoDecimalsOnTheYKey() = runBlocking {
        link.noSpaceAt3 += ANCHOR_Y_KEY
        assertTrue(anchor.save(AnchorResult(-190.620, -193.990, -190.634, -193.971)))
        assertEquals(-190.634, link.cfg[ANCHOR_X_KEY]!!, 1e-9)
        assertEquals(-193.97, link.cfg[ANCHOR_Y_KEY]!!, 1e-9)
        assertEquals(1, link.lines.count { it.startsWith("config-set sd $ANCHOR_X_KEY") })
        assertEquals(2, link.lines.count { it.startsWith("config-set sd $ANCHOR_Y_KEY") })
    }

    @Test fun reportsFailureWhenTwoDecimalsAlsoHasNoRoom() = runBlocking {
        link.noSpaceAlways += ANCHOR_X_KEY
        assertFalse(anchor.save(AnchorResult(-190.620, -193.990, -190.634, -193.971)))
        assertTrue(failed().reason, failed().reason.contains("2 decimals"))
        assertEquals("X was never written", -190.620, link.cfg[ANCHOR_X_KEY]!!, 1e-9)
        assertFalse("Y must not be sent after X failed",
            link.lines.any { it.startsWith("config-set sd $ANCHOR_Y_KEY") })
    }

    // ---- ruling 3: a timeout is not a failed write -------------------------------------------
    @Test fun anUnacknowledgedWriteReportsWhatTheMachineHolds() = runBlocking {
        link.setFails += ANCHOR_X_KEY
        assertFalse(anchor.save(AnchorResult(-190.620, -193.990, -190.634, -193.971)))
        val f = failed()
        // The machine was asked, and still reads the old value: that is what gets reported.
        assertTrue(f.reason, f.reason.contains("-190.620"))
        assertFalse("an unverified claim about an irreversible write",
            f.reason.contains("Nothing was written"))
        assertTrue(link.lines.toString(), link.lines.any { it == "config-get sd $ANCHOR_X_KEY" })
    }

    @Test fun anUnacknowledgedWriteThatDidLandCountsAsWritten() = runBlocking {
        link.setFails += ANCHOR_X_KEY
        link.cfg[ANCHOR_X_KEY] = -190.634                 // it landed; only the reply went missing
        assertTrue("the write did happen, so it must not be called a failure",
            anchor.save(AnchorResult(-190.620, -193.990, -190.634, -193.971)))
    }

    // ---- ruling 12: the firmware defaults, fired independently of the bed bounds -------------
    @Test fun refusesTheFirmwareDefaultsEvenInsideTheBed() = runBlocking {
        link.cfg[ANCHOR_X_KEY] = -359.0
        link.cfg[ANCHOR_Y_KEY] = -234.0
        // A bed wide enough that the bounds check passes, so only the defaults check can refuse.
        val wide = AnchorController(link, probe, config, scope, prefs,
            travel = { listOf(-500.0, -1.0, -500.0, -1.0) })
        wide.start(Bracket.THIN, true)
        await { wide.state.value is AnchorState.Failed }
        val f = wide.state.value as AnchorState.Failed
        assertTrue(f.reason, f.reason.contains("Makera Studio"))
        assertEquals("no motion was sent: ${link.lines}", emptyList<String>(), motion())
    }

    // ---- ruling 4 + 12: the measurement itself has to be plausible ---------------------------
    @Test fun refusesAnImplausibleMeasurement() = runBlocking {
        link.parkX = -300.0                               // outside the bed: not a corner
        val s = runToEnd()
        assertTrue(s.toString(), s is AnchorState.Failed)
        assertTrue(failed().reason, failed().reason.contains("plausible"))
    }

    @Test fun saveRefusesAnImplausibleAnchorOutright() = runBlocking {
        assertFalse(anchor.save(AnchorResult(-190.620, -193.990, -300.0, -193.971)))
        assertFalse("nothing may be sent", link.lines.any { it.startsWith("config-set") })
    }

    // ---- ruling 4: an unconfirmed lift must not be followed by a traverse --------------------
    @Test fun aRefusedLiftStopsBeforeTheTraverse() = runBlocking {
        link.refuseLine = "G90 G53 G0 Z-2"                // the exact lift line, so nothing else can match
        val s = runToEnd()
        assertTrue(s.toString(), s is AnchorState.Failed)
        assertTrue("it must stop because the lift was refused, not for some other reason",
            failed().reason.contains("Soft limit"))
        assertTrue("the lift really was the refused line", link.lines.contains("G90 G53 G0 Z-2"))
        assertFalse("the traverse must never go out after a refused lift",
            link.lines.any { it.contains("X-194.620") })
        assertFalse(link.lines.any { it.startsWith("M480") })
    }

    // ---- finding 6: an Alarm must stop the routine before M480.8 goes out --------------------
    @Test fun anAlarmOnTheDescentStopsBeforeProbing() = runBlocking {
        link.alarmOnLine = "Z-66.500"
        val s = runToEnd()
        assertTrue(s.toString(), s is AnchorState.Failed)
        assertTrue(failed().reason, failed().reason.contains("Alarm"))
        assertFalse("M480.8 must not be sent into an alarmed machine",
            link.lines.any { it.startsWith("M480") })
    }

    // ---- finding 1: mpos is stale until the machine has actually stopped ---------------------
    @Test fun waitsForTheMachineToSettleBeforeReadingAndRestoring() = runBlocking {
        link.settleScope = scope                          // still moving when Done ATC arrives
        val s = runToEnd()
        assertTrue(s.toString(), s is AnchorState.Done)
        // The anchor is the settled corner, not where the head was mid-move.
        assertEquals(-190.634, (s as AnchorState.Done).result.measuredX, 1e-9)
        assertEquals(-193.971, s.result.measuredY, 1e-9)
        // And the restore wrote its offset from the settled position, so X0/Y0 come back exactly.
        assertEquals(-100.0, link.offsets()[0], 1e-6)
        assertEquals(-100.0, link.offsets()[1], 1e-6)
    }

    @Test fun restoreWaitsForTheMachineToStopOnAFailureThatIsNotAStop() = runBlocking {
        // The no-touch throw: the head is on the corner but the last polled status lags behind it.
        link.settleScope = scope
        link.missTouchAfterTop = true
        val s = runToEnd()
        assertTrue(s.toString(), s is AnchorState.Failed)
        assertTrue(failed().reason, failed().reason.contains("touch"))
        // Restoring from the stale poll would write the offset 7 mm out on X and 3 mm out on Y.
        assertEquals("X0 restored from a stale position", -100.0, link.offsets()[0], 1e-6)
        assertEquals("Y0 restored from a stale position", -100.0, link.offsets()[1], 1e-6)
    }

    // ---- finding 1: the hole plausible() alone could not catch -------------------------------
    @Test fun refusesAMeasurementStillAtThePrePosition() = runBlocking {
        // Inside the bed and near the anchor, so only comparing against it can catch this.
        link.parkX = -194.620; link.parkY = -197.990
        val s = runToEnd()
        assertTrue(s.toString(), s is AnchorState.Failed)
        assertTrue(failed().reason, failed().reason.contains("pre-position"))
    }

    @Test fun refusesAMeasurementThatRelocatesTheAnchor() = runBlocking {
        link.parkX = -150.0; link.parkY = -150.0          // inside the bed, 40 mm from the anchor
        val s = runToEnd()
        assertTrue(s.toString(), s is AnchorState.Failed)
        val why = failed().reason
        assertTrue(why, why.contains("relocation"))
        // A measurement really was taken, so the message must not claim nothing was measured...
        assertTrue(why, why.contains("A corner was found"))
        assertFalse(why, why.contains("Nothing was measured"))
        // ...and it must say what to do about it.
        assertTrue(why, why.contains("Makera Studio"))
    }

    @Test fun saveRefusesAnAnchorThatRelocates() = runBlocking {
        // Inside the bed and not a firmware default, so the bounds check alone would let it through.
        assertFalse(anchor.save(AnchorResult(-190.620, -193.990, -150.0, -193.971)))
        assertTrue(failed().reason, failed().reason.contains("relocation"))
        assertFalse("nothing may be sent", link.lines.any { it.startsWith("config-set") })
    }

    @Test fun acceptsARealCalibrationShift() = runBlocking {
        // A few hundredths is the normal case and must not be mistaken for a relocation.
        assertTrue(anchor.save(AnchorResult(-190.620, -193.990, -190.634, -193.971)))
    }

    @Test fun doesNotRestoreFromAStalePositionWhenTheMachineNeverStops() = runBlocking {
        // Takes the 10 s restore timeout: the machine reports Run forever, so the position never settles.
        link.settleScope = scope; link.neverSettles = true; link.missTouchAfterTop = true
        val s = runToEnd()
        assertTrue(s.toString(), s is AnchorState.Failed)
        assertFalse("no G10 may be written from a position known to be stale",
            link.lines.any { it.startsWith("G10") })
        assertTrue(failed().changed, failed().changed.contains("NOT restored"))
        assertEquals("the work offsets are left untouched",
            listOf(-100.0, -100.0, -70.0), link.offsets())
        // The feed override does not depend on position, so it is still put back.
        assertTrue(link.lines.toString(), link.lines.any { it.startsWith("M220S") })
    }

    // ---- finding 1 (round 3): a Stop must not skip the feed override -------------------------
    @Test fun aStopDuringTheRestoreStillPutsTheFeedOverrideBack() = runBlocking {
        // Poll 1 is the post-probe settle, so from poll 2 the restore's own idle wait is blocked and
        // the run is certainly suspended inside it. Stop on poll 3: by then start() has returned and
        // assigned the job, and the cancellation lands in the restore rather than before it.
        // (Watching for the RESTORE state does not work — StateFlow conflates it away.)
        link.blockFromPollAfterProbing = 2
        link.onPoll = { n ->
            if (n == 3) {
                anchor.stop()
                link.blockFromPollAfterProbing = Int.MAX_VALUE   // let the retry settle, not time out
            }
        }
        val s = runToEnd()
        assertTrue(s.toString(), s is AnchorState.Failed)
        assertEquals("Stopped.", failed().reason)
        // The override does not depend on position, so a Stop must not skip it — exactly once.
        assertEquals("the feed override must go back exactly once", 1,
            link.lines.count { it.startsWith("M220S") })
        assertTrue("85 was the override before the run", link.lines.contains("M220S85"))
        // And the work zero is still put back, by the retry under NonCancellable.
        assertEquals(1, link.lines.count { it.startsWith("G10") })
        assertEquals(-100.0, link.offsets()[0], 1e-6)
        assertEquals(-100.0, link.offsets()[1], 1e-6)
    }

    // ---- finding 4 (round 3): zero pre-position offsets ---------------------------------------
    @Test fun acceptsAMeasurementAtTheAnchorWhenTheOffsetsAreZero() = runBlocking {
        // With no offset the pre-position IS the anchor, so the park check has to be skipped or a
        // perfectly accurate measurement would be refused as "never left the pre-position".
        anchor.update(anchor.setup.value.copy(preX = 0.0, preY = 0.0))
        link.parkX = -190.620; link.parkY = -193.990
        val s = runToEnd()
        assertTrue(s.toString(), s is AnchorState.Done)
        assertEquals(0.0, (s as AnchorState.Done).result.dx, 1e-9)
    }

    // ---- finding 2 (round 3): the unrestored-zero note is said once ---------------------------
    @Test fun theUnrestoredZeroNoteIsNotRepeated() = runBlocking {
        // Block the restore's wait, then drop the link so it gives up at once rather than after 10 s.
        link.blockFromPollAfterProbing = 2
        link.onPoll = { if (it == 2) link.conn.value = ConnState.Lost }
        val s = runToEnd()
        assertTrue(s.toString(), s is AnchorState.Failed)
        val f = failed()
        val note = "Your work zero was NOT restored"
        assertTrue(f.reason, note in f.reason)
        assertTrue("the measurement must not be lost", f.reason.contains("-190.634"))
        assertEquals("the note belongs in one place, not two", 1,
            (f.reason + " " + f.changed).split(note).size - 1)
        assertFalse(link.lines.any { it.startsWith("G10") })
    }
}

/** Settings in a map. */
private class MapPrefs : Prefs {
    private val map = HashMap<String, Any?>()
    override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
    override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
    override fun getLong(key: String, default: Long) = map[key] as? Long ?: default
    override fun getFloat(key: String, default: Float) = map[key] as? Float ?: default
    override fun getString(key: String, default: String?) = map[key] as? String ?: default
    override fun edit(block: PrefsEditor.() -> Unit) {
        object : PrefsEditor {
            override fun putBoolean(key: String, value: Boolean) { map[key] = value }
            override fun putInt(key: String, value: Int) { map[key] = value }
            override fun putLong(key: String, value: Long) { map[key] = value }
            override fun putFloat(key: String, value: Float) { map[key] = value }
            override fun putString(key: String, value: String) { map[key] = value }
        }.block()
    }
}

/**
 * A machine that homes, changes to the probe, answers `config-get` / `config-set` and plays
 * Makera's `M480.8` echoes. Every status it publishes is stamped slightly ahead of now, so a
 * waiter always counts it as having arrived after the wait began.
 */
private class AnchorFakeLink : MachineLink {
    override val conn = MutableStateFlow(ConnState.Connected)
    override val status = MutableStateFlow<MachineStatus?>(null)
    override val messages = MutableSharedFlow<String>(extraBufferCapacity = 512)
    override val sent = MutableSharedFlow<String>(extraBufferCapacity = 512)
    override val latencyMs = MutableStateFlow<Int?>(20)
    override val outputs = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    override val dialect: Dialect = GrblishDialect

    /**
     * Report work still outstanding from this idle-wait poll on, so one chosen wait blocks. Polls
     * are only counted once M480.8 has run, so poll 1 is the post-probe settle and poll 2 the restore.
     */
    var blockFromPollAfterProbing = Int.MAX_VALUE
    /** Called on each counted poll with its number, so a test can act at an exact moment. */
    var onPoll: ((Int) -> Unit)? = null
    private var polls = 0
    private var probed = false
    override val pendingLines: Int get() {
        if (!probed) return 0
        val n = ++polls
        onPoll?.invoke(n)
        return if (n >= blockFromPollAfterProbing) 1 else 0
    }

    val lines = mutableListOf<String>()
    var resets = 0

    val cfg = linkedMapOf(ANCHOR_X_KEY to -190.620, ANCHOR_Y_KEY to -193.990)
    var gets = 0
    /** Keys whose `config-set` is never acknowledged. */
    val setFails = mutableSetOf<String>()
    /** Keys the firmware rejects for space at 3 decimals but takes at 2. */
    val noSpaceAt3 = mutableSetOf<String>()
    /** Keys the firmware rejects for space however few decimals are sent. */
    val noSpaceAlways = mutableSetOf<String>()

    /** Z of the top touch; null = a good touch, the nominal drop below the commanded approach. */
    var topTouchZ: Double? = null
    /** Z of the two wall touches, which are legitimately far below the approach Z. */
    var wallTouchZ = -67.800
    var holdAtToolChange = false
    var holdAtProbing = false
    /** Where M480.8 parks: the corner it found. */
    var parkX = -190.634
    var parkY = -193.971
    /** Where homing leaves Z. Deliberately NOT -1.0 (= NOMINAL_HOMED_Z), so a hardcoded datum is caught. */
    var homeZ = -1.500
    /** Acknowledge a config-set but store this instead, to exercise the read-back check. */
    var storeInstead: Double? = null
    /** Refuse exactly this line, as the firmware refuses one that would break a soft limit. */
    var refuseLine: String? = null
    /** Go into Alarm on any line containing this, instead of reporting Idle. */
    var alarmOnLine: String? = null
    /** M480.8 writes the work zero, then the machine alarms instead of parking. */
    var alarmAfterProbe = false
    /** Keep reporting Run for a moment after Done ATC: a real machine is still moving then. */
    var settleScope: CoroutineScope? = null
    /** Report a missed touch after a good top touch, to exercise a failure that is not a Stop. */
    var missTouchAfterTop = false
    /** Never stop moving, so a waiter times out rather than ever seeing Idle. */
    var neverSettles = false
    /** The setter calibration reports a missed touch, so no probe length is ever learned. */
    var calSetterMiss = false


    private var mx = -50.0; private var my = -50.0; private var mz = -20.0
    private var trueX = -50.0; private var trueY = -50.0   // the published mpos lags this while settling
    private val off = mutableListOf(-100.0, -100.0, -70.0)
    /** The live work offsets, so a test can check the restore put them back exactly. */
    fun offsets() = off.toList()
    private var tool: Int? = 4
    private var feed = 85.0
    private var zCommanded = 0.0

    fun begin() = publish()

    private fun publish(state: String = "Idle") {
        val mpos = listOf(mx, my, mz, 0.0)
        status.value = MachineStatus(state, mpos, mpos.mapIndexed { i, v -> v - (off.getOrNull(i) ?: 0.0) },
            feedOverride = feed, spindleRpm = 0.0, homed = null, raw = "<$state>",
            atMs = com.z1pendant.platform.nowMs() + 1000, tool = tool)
    }

    private val axisRe = Regex("""([XYZ])\s*(-?\d+(?:\.\d+)?)""")

    override fun sendLine(line: String, quiet: Boolean, onAck: ((ok: Boolean, reply: String) -> Unit)?) {
        lines += line
        refuseLine?.let { if (it == line) { onAck?.invoke(false, "Error:Soft limit exceeded"); return } }
        onAck?.invoke(true, "ok")
        alarmOnLine?.let { if (it in line) { publish("Alarm"); return } }
        if (alarmAfterProbe && probed) return              // a halted machine takes nothing more
        when {
            line == "\$H" -> {
                mx = -1.0; my = -1.0; trueX = -1.0; trueY = -1.0; mz = homeZ; zCommanded = homeZ; publish()
            }

            line.startsWith("config-get sd ") -> {
                val k = line.removePrefix("config-get sd ").trim(); gets++
                cfg[k]?.let { messages.tryEmit("sd: $k is set to $it") }
            }

            line.startsWith("config-set sd ") -> {
                val rest = line.removePrefix("config-set sd ").trim()
                val k = rest.substringBefore(' '); val v = rest.substringAfter(' ')
                val decimals = v.substringAfter('.', "").length
                when {
                    k in setFails -> {}
                    k in noSpaceAlways ->
                        messages.tryEmit("sd: $k not enough space to overwrite existing key/value")
                    k in noSpaceAt3 && decimals > 2 ->
                        messages.tryEmit("sd: $k not enough space to overwrite existing key/value")
                    else -> {
                        cfg[k] = storeInstead ?: v.toDouble()
                        messages.tryEmit("sd: $k has been set to $v")
                    }
                }
            }

            line.startsWith("M6 T") -> { publish("Tool"); messages.tryEmit("Please change the tool to: T9999") }
            line == "M490.2" -> if (!holdAtToolChange) calibrate()
            line.startsWith("M480.8") -> if (!holdAtProbing) probeCorner()
            line.startsWith("M220S") -> { feed = line.removePrefix("M220S").toDouble(); publish() }

            line.startsWith("G10") -> {
                // The firmware applies G10 L20 where the head REALLY is, not where the last poll said.
                axisRe.findAll(line.removePrefix("G10L20P0")).forEach { m ->
                    val i = "XYZ".indexOf(m.groupValues[1][0])
                    off[i] = listOf(trueX, trueY, mz)[i] - m.groupValues[2].toDouble()
                }
                publish()
            }

            else -> {
                axisRe.findAll(line).forEach { m ->
                    val v = m.groupValues[2].toDouble()
                    when (m.groupValues[1]) {
                        "X" -> { mx = v; trueX = v }; "Y" -> { my = v; trueY = v }
                        "Z" -> { mz = v; zCommanded = v }
                    }
                }
                publish()
            }
        }
    }

    /** The firmware's setter calibration, as `ProbeController` follows it. */
    private fun calibrate() {
        tool = 9999
        publish("Run")
        messages.tryEmit("G38.6 Z-100 P1")
        messages.tryEmit(if (calSetterMiss) "[PRB:0.000,0.000,0.000:0]" else "[PRB:-1.000,-1.000,-69.570:1]")
        messages.tryEmit("M492.3")
        mz = -1.0
        messages.tryEmit("Done ATC")
        publish()
    }

    /**
     * `M480.8`: the top face, then the two walls, then it writes the work zero on the corner and
     * parks there. The feed override ends at 100% whatever it was.
     */
    private fun probeCorner() {
        probed = true; polls = 0
        val top = topTouchZ ?: (zCommanded - 5.0)
        messages.tryEmit("[PRB:${mx},${my},$top:1]")
        if (missTouchAfterTop) { stallThenSettle(); messages.tryEmit("[PRB:0.000,0.000,0.000:0]"); return }
        messages.tryEmit("[PRB:-205.000,-193.000,$wallTouchZ:1]")
        messages.tryEmit("[PRB:-190.000,-208.000,$wallTouchZ:1]")
        off[0] = parkX; off[1] = parkY; off[2] = top    // G10 L20 on the corner and the top face
        feed = 100.0
        if (alarmAfterProbe) { publish("Alarm"); return }
        messages.tryEmit("Done ATC")
        if (settleScope == null) { mx = parkX; my = parkY; trueX = parkX; trueY = parkY; mz = zCommanded; publish() }
        else stallThenSettle()
    }

    /** The head is already on the corner; the last published status still says otherwise. */
    private fun stallThenSettle() {
        trueX = parkX; trueY = parkY
        mx = parkX + 7.0; my = parkY - 3.0; mz = zCommanded
        publish("Run")
        if (!neverSettles) settleScope?.launch { delay(60); mx = parkX; my = parkY; publish() }
    }

    override fun connect(target: MachineTarget) {}
    override fun disconnect() {}
    override fun feedHold() {}
    override fun resume() {}
    override fun reset() { resets++ }
    override fun setPollIntervalMs(ms: Long) {}
    override fun refreshOutputs() {}
    override fun assumeOutput(name: String, on: Boolean) {}
}
