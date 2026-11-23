package com.z1pendant

import com.z1pendant.probe.AnchorResult
import com.z1pendant.probe.AnchorSetup
import com.z1pendant.probe.AnchorState
import com.z1pendant.probe.AnchorStep
import com.z1pendant.probe.BED_CLEAR_LEAD
import com.z1pendant.probe.BED_CLEAR_REST
import com.z1pendant.probe.Bracket
import com.z1pendant.probe.WORK_ZERO_UNCHANGED
import com.z1pendant.probe.canConfirmRetry
import com.z1pendant.probe.canStart
import com.z1pendant.probe.savable
import com.z1pendant.probe.NOTHING_CHANGED
import com.z1pendant.probe.ProbeLength
import com.z1pendant.probe.ProbeLength.Result
import com.z1pendant.probe.ProbeTool
import com.z1pendant.probe.WRONG_PROBE_NOTE
import com.z1pendant.probe.canConfirmProbe
import com.z1pendant.probe.changedText
import com.z1pendant.probe.label
import com.z1pendant.probe.probeRejected
import com.z1pendant.probe.rows
import com.z1pendant.probe.setupProblems
import com.z1pendant.probe.touchPointX
import com.z1pendant.probe.touchPointY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorScreenModelTest {
    private val tip = 4.0

    @Test fun defaultsHaveNoProblems() {
        assertTrue(AnchorSetup().setupProblems(tip).isEmpty())
    }

    @Test fun cornerReliefOffsetIsRefusedOnX() {
        val p = AnchorSetup(xOff = 1.0).setupProblems(tip)        // touch point -3
        assertTrue(p.any { it.message.startsWith("X touches") })
    }

    @Test fun cutOutOffsetIsRefusedEvenThoughAThickBracketWouldAllowIt() {
        val p = AnchorSetup(yOff = 34.0).setupProblems(tip)       // touch point 30, thin cut-out only
        assertTrue(p.any { it.message.startsWith("Y touches") })
    }

    @Test fun rangeChecksReportEachProblem() {
        val p = AnchorSetup(depth = 0.0, overshoot = -1.0, thin = 0.0, wasteboard = -2.0).setupProblems(tip)
        assertEquals(4, p.size)
    }

    private fun check(tool: ProbeTool, r: Result) = ProbeLength.Check(tool, -70.0, r, expected = -69.0)

    @Test fun onlyAnUnfamiliar3dProbeReadingCanBeConfirmed() {
        assertTrue(canConfirmProbe(check(ProbeTool.PROBE_3D, Result.UNKNOWN)))
        assertTrue(canConfirmProbe(check(ProbeTool.PROBE_3D, Result.NEW)))
        assertFalse(canConfirmProbe(check(ProbeTool.PROBE_3D, Result.LOOKS_LIKE)))
        assertFalse(canConfirmProbe(check(ProbeTool.PROBE_3D, Result.MATCH)))
        assertFalse(canConfirmProbe(check(ProbeTool.ROD, Result.UNKNOWN)))
        assertFalse(canConfirmProbe(null))
    }

    @Test fun afterAStopTheRestoreIsAttemptedNotDone() {
        val t = changedText("Your X0 was changed by the probing routine.", halted = true)
        assertTrue(t.contains("attempted"))
        assertTrue(t.contains("may have refused"))
    }

    @Test fun withoutAStopTheChangeIsReportedAsIs() {
        assertEquals("Your work zero wasn't changed.", changedText("Your work zero wasn't changed.", halted = false))
    }

    @Test fun aStopBeforeAnythingChangedAddsNoRestoreNote() {
        assertEquals(NOTHING_CHANGED, changedText(NOTHING_CHANGED, halted = true))
    }

    @Test fun resultRowsAreToThreeDecimalsWithSignedChange() {
        val rows = AnchorResult(-190.62, -193.99, -190.634, -193.971).rows()
        assertEquals(listOf("-190.620", "-190.634", "-0.014"), listOf(rows[0].current, rows[0].measured, rows[0].change))
        assertEquals(listOf("-193.990", "-193.971", "+0.019"), listOf(rows[1].current, rows[1].measured, rows[1].change))
    }

    @Test fun everyStepHasADistinctLabel() {
        assertEquals(6, AnchorStep.entries.map { it.label() }.toSet().size)
    }

    @Test fun cornerReliefOffsetIsRefusedOnY() {
        val p = AnchorSetup(yOff = 1.0).setupProblems(tip)        // touch point -3
        assertTrue(p.any { it.message.startsWith("Y touches") })
    }

    @Test fun nonFiniteSettingsAreRefused() {
        assertEquals(1, AnchorSetup(xOff = Double.NaN).setupProblems(tip).size)
        assertFalse(AnchorSetup(depth = Double.POSITIVE_INFINITY).savable(tip))
        assertFalse(AnchorSetup().savable(Double.NaN))
    }

    @Test fun aNonPositiveOffsetGetsOneMessageNotTwo() {
        val p = AnchorSetup(xOff = 0.0, preX = 0.0).setupProblems(tip)
        assertEquals(p.toString(), 1, p.size)
        assertTrue(p[0].message.contains("more than 0"))
    }

    @Test fun onlyValidSettingsAreSavable() {
        assertTrue(AnchorSetup().savable(tip))
        assertFalse(AnchorSetup(xOff = 34.0).savable(tip))
    }

    @Test fun startNeedsConnectionIdleAndUsableSettings() {
        val ok = AnchorSetup()
        assertTrue(canStart(true, true, ok, tip))
        assertFalse(canStart(false, true, ok, tip))
        assertFalse(canStart(true, false, ok, tip))
        assertFalse(canStart(true, true, AnchorSetup(xOff = 34.0), tip))
    }

    @Test fun theBedClearWarningIsVerbatim() {
        assertEquals("Clear the bed. Check the settings match the machine's current configuration. " +
            "Once the probe is fitted, calibration runs without stopping — obstacles on the bed, " +
            "or an incorrect setting, may damage the probe.", BED_CLEAR_LEAD + BED_CLEAR_REST)
    }

    @Test fun noRestoreCautionWhenTheWorkZeroWasNotChanged() {
        assertEquals(WORK_ZERO_UNCHANGED, changedText(WORK_ZERO_UNCHANGED, halted = true))
    }

    @Test fun retryNeedsTheRunsOwnChoicesAndAConfirmableCheck() {
        val c = ProbeLength.Check(ProbeTool.PROBE_3D, -70.0, Result.UNKNOWN, expected = -69.0)
        assertTrue(canConfirmRetry(c, AnchorState.Failed("x", "y", Bracket.THICK, false)))
        assertFalse(canConfirmRetry(c, AnchorState.Failed("x", "y")))
        assertFalse(canConfirmRetry(null, AnchorState.Failed("x", "y", Bracket.THIN, true)))
    }

    @Test fun aRejectedProbeIsToldApartFromOtherFailures() {
        val why = "The probe on the setter reads -78.5, which isn't the 3D probe. This routine only works with the 3D probe."
        val laser = check(ProbeTool.PROBE_3D, Result.LOOKS_LIKE)
        assertTrue(probeRejected(laser, AnchorState.Failed(why, "y", wrongProbe = true)))
        assertFalse(probeRejected(laser, AnchorState.Failed("The link dropped.", "y")))
        assertFalse(probeRejected(check(ProbeTool.PROBE_3D, Result.MATCH), AnchorState.Failed(why, "y", wrongProbe = true)))
        assertFalse(probeRejected(null, AnchorState.Failed(why, "y", wrongProbe = true)))
    }

    @Test fun wrongProbeDetectionIgnoresTheMessageWording() {
        val laser = check(ProbeTool.PROBE_3D, Result.LOOKS_LIKE)
        assertTrue(probeRejected(laser, AnchorState.Failed("Entirely different words.", "y", wrongProbe = true)))
        // The old wording without the flag is no longer enough.
        assertFalse(probeRejected(laser, AnchorState.Failed("which isn't the 3D probe", "y")))
    }

    @Test fun theWrongProbeNoteNamesTheProbeAndStatesTheDamageAsAConsequence() {
        // "Try again" only returns to the question page, so the failure page carries no bed-clear caption.
        assertTrue(WRONG_PROBE_NOTE, WRONG_PROBE_NOTE.contains("Wired Probe 2.0"))
        assertTrue(WRONG_PROBE_NOTE, WRONG_PROBE_NOTE.contains("touch off"))
        assertTrue(WRONG_PROBE_NOTE, WRONG_PROBE_NOTE.contains("will likely damage it"))
        assertFalse(WRONG_PROBE_NOTE, WRONG_PROBE_NOTE.contains("bed clear"))
    }

    @Test fun defaultOffsetsTouchDownTenMillimetresFromTheCorner() {
        val s = AnchorSetup()
        assertEquals(14.0, s.xOff, 1e-9); assertEquals(14.0, s.yOff, 1e-9)
        assertEquals(10.0, s.touchPointX(), 1e-9); assertEquals(10.0, s.touchPointY(), 1e-9)
        assertTrue(s.setupProblems(2.0).isEmpty())
    }

    @Test fun probingStepIsNamedForTheBracket() {
        assertEquals("Probe the anchor bracket", AnchorStep.PROBING.label())
    }
}
