package com.z1pendant

import com.z1pendant.probe.AnchorSetup
import com.z1pendant.probe.Bracket
import com.z1pendant.probe.Corner
import com.z1pendant.probe.ProbeKind
import com.z1pendant.probe.approachZ
import com.z1pendant.probe.notchProblem
import com.z1pendant.probe.ToolAction
import com.z1pendant.probe.awaitingSwap
import com.z1pendant.probe.resetSummary
import com.z1pendant.probe.probeSetup
import com.z1pendant.probe.referenceTopHeight
import com.z1pendant.probe.SetupField
import com.z1pendant.probe.SetupIssue
import com.z1pendant.probe.prePositionRange
import com.z1pendant.probe.setupProblems
import com.z1pendant.probe.setupWarnings
import com.z1pendant.probe.savable
import com.z1pendant.probe.canStart
import com.z1pendant.probe.topTouchOk
import com.z1pendant.probe.zCorrection
import com.z1pendant.probe.message
import com.z1pendant.probe.question
import com.z1pendant.probe.NOMINAL_DROP
import com.z1pendant.probe.touchPointX
import com.z1pendant.probe.touchPointY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorModelTest {
    private val s = AnchorSetup()

    @Test fun referenceTopHeightIsThinPlusWasteboard() {
        assertEquals(11.0, s.referenceTopHeight(), 1e-9)
    }

    @Test fun approachZForEachCombination() {
        assertEquals(-66.0, s.approachZ(-1.0, Bracket.THIN, true), 1e-9)
        assertEquals(-72.0, s.approachZ(-1.0, Bracket.THIN, false), 1e-9)
        assertEquals(-56.0, s.approachZ(-1.0, Bracket.THICK, true), 1e-9)
        assertEquals(-62.0, s.approachZ(-1.0, Bracket.THICK, false), 1e-9)
    }

    @Test fun approachZFollowsTheLiveHomedZ() {
        assertEquals(-67.5, s.approachZ(-2.5, Bracket.THIN, true), 1e-9)
    }

    @Test fun editingAThicknessMovesTheReference() {
        val t = s.copy(wasteboard = 6.2)
        assertEquals(11.2, t.referenceTopHeight(), 1e-9)
        assertEquals(-66.0, t.approachZ(-1.0, Bracket.THIN, true), 1e-9)
        assertEquals(-72.2, t.approachZ(-1.0, Bracket.THIN, false), 1e-9)
    }

    @Test fun probeSetupIsFrontLeftInsideCorner() {
        val p = s.probeSetup(tip = 2.0)
        assertEquals(ProbeKind.INSIDE, p.kind)
        assertEquals(Corner.FRONT_LEFT, p.corner)
        assertEquals("M480.8 X14.000 Y14.000 Z1.800 D2.000", p.makeraCommand())
    }

    @Test fun topTouchWithinAllowanceIsOk() {
        assertTrue(topTouchOk(touchZ = -71.0, approachZ = -66.0, overshoot = 2.0))
        assertTrue(topTouchOk(touchZ = -73.0, approachZ = -66.0, overshoot = 2.0))   // drop 7 = 5 + 2
    }

    @Test fun topTouchBeyondAllowanceIsNotOk() {
        assertFalse(topTouchOk(touchZ = -74.0, approachZ = -66.0, overshoot = 2.0))
    }

    @Test fun theAllowanceFeedsTheSanityCheck() {
        // The same touch (drop 6) is refused by a tight allowance and accepted by the default.
        assertFalse(topTouchOk(touchZ = -72.0, approachZ = -66.0, overshoot = 0.5))
        assertTrue(topTouchOk(touchZ = -72.0, approachZ = -66.0, overshoot = 2.0))
        // And a generous one widens it past the default's limit.
        assertFalse(topTouchOk(touchZ = -74.0, approachZ = -66.0, overshoot = 2.0))
        assertTrue(topTouchOk(touchZ = -74.0, approachZ = -66.0, overshoot = 3.0))
    }

    @Test fun aTouchAboveTheApproachIsStillImpossible() {
        assertFalse(topTouchOk(touchZ = -65.0, approachZ = -66.0, overshoot = 2.0))
    }

    @Test fun defaultAllowanceIsTwoMillimetres() {
        assertEquals(2.0, AnchorSetup().overshoot, 0.0)
        assertEquals(5.0, NOMINAL_DROP, 0.0)
    }

    // ---- Pre-position Z correction --------------------------------------------------------------
    @Test fun noPromptWithinTheFirstRunThreshold() {
        assertNull(zCorrection(5.4, 5.0, firstRun = true, currentZ = -65.0))
        assertNull(zCorrection(4.6, 5.0, firstRun = true, currentZ = -65.0))
        assertNull(zCorrection(5.5, 5.0, firstRun = true, currentZ = -65.0))     // exactly 0.5 is not "more than"
    }

    @Test fun promptsAtTheTighterFirstRunThreshold() {
        val c = zCorrection(5.7, 5.0, firstRun = true, currentZ = -65.0)
        assertNotNull(c)
        assertEquals(-65.7, c!!.suggestedZ, 1e-9)
    }

    @Test fun theSameDifferenceDoesNotPromptOnALaterRun() {
        assertNull(zCorrection(5.7, 5.0, firstRun = false, currentZ = -65.0))
        assertNull(zCorrection(6.0, 5.0, firstRun = false, currentZ = -65.0))    // exactly 1.0 is not "more than"
        assertNotNull(zCorrection(6.2, 5.0, firstRun = false, currentZ = -65.0))
    }

    @Test fun aLargerDropLowersPrePositionZ() {
        val c = zCorrection(6.0, 5.0, firstRun = true, currentZ = -65.0)!!
        assertEquals(-66.0, c.suggestedZ, 1e-9)
        assertTrue(c.tooHigh)
        assertEquals(1.0, c.offBy, 1e-9)
        assertEquals("Probe descended 6.00 mm, expected 5.00 mm. Pre-position Z looks 1.00 mm high.", c.message())
        assertEquals("Update it to -66.00? (5.00 mm offset)", c.question())
    }

    @Test fun aSmallerDropRaisesPrePositionZ() {
        val c = zCorrection(3.5, 5.0, firstRun = false, currentZ = -65.0)!!
        assertEquals(-63.5, c.suggestedZ, 1e-9)
        assertFalse(c.tooHigh)
        assertEquals("Probe descended 3.50 mm, expected 5.00 mm. Pre-position Z looks 1.50 mm low.", c.message())
        assertEquals("Update it to -63.50? (5.00 mm offset)", c.question())
    }

    @Test fun theSuggestionBuildsOnTheCurrentSetting() {
        assertEquals(-69.25, zCorrection(7.25, 5.0, firstRun = false, currentZ = -67.0)!!.suggestedZ, 1e-9)
    }

    private val r = 1.0
    private fun refused(p: Double, b: Bracket?, tip: Double = r) = notchProblem('X', p, tip, b) != null

    @Test fun theTouchPointIsThePrePositionPlusTheStartOffset() {
        assertEquals(10.0, s.touchPointX(), 1e-9)
        assertEquals(10.0, s.touchPointY(), 1e-9)
        val t = s.copy(preX = -2.0, xOff = 30.0, preY = 1.0, yOff = 8.0)
        assertEquals(28.0, t.touchPointX(), 1e-9)
        assertEquals(9.0, t.touchPointY(), 1e-9)
    }

    @Test fun theDefaultOffsetsClearEveryZone() {
        for (b in listOf(Bracket.THIN, Bracket.THICK, null)) assertNull(notchProblem('X', 7.0, r, b))
    }

    @Test fun theCornerReliefIsRefusedOnBothBrackets() {
        assertTrue(refused(2.0, Bracket.THIN)); assertTrue(refused(2.0, Bracket.THICK))
    }

    @Test fun theTipRadiusWidensTheCornerZone() {
        assertTrue(refused(3.9, Bracket.THICK))
        assertFalse(refused(4.1, Bracket.THICK))
    }

    @Test fun theArmCutoutIsRefusedOnTheThinBracketOnly() {
        assertTrue(refused(30.0, Bracket.THIN)); assertFalse(refused(30.0, Bracket.THICK))
    }

    @Test fun aTouchPointPastTheArmsEndIsRefused() {
        assertTrue(refused(90.0, Bracket.THIN)); assertTrue(refused(90.0, Bracket.THICK))
    }

    @Test fun theTipRadiusWidensTheArmEnd() {
        assertTrue(refused(84.5, Bracket.THICK))
        assertFalse(refused(83.5, Bracket.THICK))
    }

    @Test fun theTipRadiusWidensTheArmCutout() {
        assertTrue(refused(23.4, Bracket.THIN)); assertTrue(refused(36.6, Bracket.THIN))
        assertFalse(refused(22.6, Bracket.THIN)); assertFalse(refused(37.4, Bracket.THIN))
    }

    // The edges are inclusive: exactly on one is refused, a hair outside is not.
    @Test fun theZoneEdgesAreInclusiveOnBothSides() {
        assertTrue("4.0 is the corner edge", refused(4.0, Bracket.THICK))
        assertFalse(refused(4.001, Bracket.THICK))
        assertTrue("84.0 is the inner-face edge", refused(84.0, Bracket.THICK))
        assertFalse(refused(83.999, Bracket.THICK))
        assertTrue("23.0 is the cut-out's near edge", refused(23.0, Bracket.THIN))
        assertFalse(refused(22.999, Bracket.THIN))
        assertTrue("37.0 is the cut-out's far edge", refused(37.0, Bracket.THIN))
        assertFalse(refused(37.001, Bracket.THIN))
    }

    @Test fun aBiggerTipWidensEveryZoneFurther() {
        assertTrue(refused(4.5, Bracket.THICK, tip = 3.0))
        assertFalse(refused(4.5, Bracket.THICK, tip = 1.0))
    }

    @Test fun eitherBracketRefusesTheUnionOfBothZones() {
        assertTrue(refused(30.0, null)); assertTrue(refused(2.0, null))
    }

    @Test fun theMessageNamesTheAxisTheTouchDownAndTheZone() {
        assertEquals("X touches down 2.00 mm from the corner — inside the corner relief.",
            notchProblem('X', 2.0, r, Bracket.THICK))
        assertEquals("Y touches down 30.00 mm from the corner — inside the 24–36 mm cut-out.",
            notchProblem('Y', 30.0, r, Bracket.THIN))
        assertEquals("X touches down 86.00 mm from the corner — past the 85 mm face.",
            notchProblem('X', 86.0, r, Bracket.THICK))
    }

    @Test fun theMessageQuotesTheTouchDownNotTheStartOffset() {
        val m = AnchorSetup(xOff = 6.0).setupProblems(2.0).single()   // pre-position -4 puts it at 2
        assertEquals(SetupIssue(SetupField.X_OFF, "X touches down 2.00 mm from the corner — inside the corner relief."), m)
    }

    @Test fun onlyAWaitingMachineInItsToolStateShowsTheSwapButton() {
        fun a(stage: ToolAction.Stage, load: Boolean = true, err: String? = null) = ToolAction(load, stage, err)
        assertTrue(awaitingSwap(a(ToolAction.Stage.WAITING), "Tool"))
        assertFalse("user already pressed continue", awaitingSwap(a(ToolAction.Stage.WAITING), "Run"))
        assertFalse(awaitingSwap(a(ToolAction.Stage.CALIBRATING), "Tool"))
        assertFalse(awaitingSwap(a(ToolAction.Stage.DONE), "Tool"))
        assertFalse(awaitingSwap(a(ToolAction.Stage.WAITING, err = "x"), "Tool"))
        assertFalse(awaitingSwap(a(ToolAction.Stage.WAITING, load = false), "Tool"))
        assertFalse(awaitingSwap(null, "Tool"))
    }

    @Test fun aNonPositiveTipIsAProblem() {
        assertTrue(AnchorSetup().setupProblems(0.0).any { "tip" in it.message })
    }

    @Test fun theResetSummaryNamesTheTip() {
        assertTrue("2.000" in resetSummary(2.0))
    }

    // ---- pre-position range warning -----------------------------------------------------------
    private fun warned(pre: Double, tip: Double = 2.0) = AnchorSetup(preX = pre).setupWarnings(tip).any { it.field == SetupField.PRE_X }

    @Test fun thePrePositionRangeForATwoMillimetreTipIsMinus13ToMinus3() {
        val range = prePositionRange(2.0)
        assertEquals(-13.0, range.start, 1e-9)
        assertEquals(-3.0, range.endInclusive, 1e-9)
    }

    @Test fun theDefaultPrePositionIsInRangeForTheDefaultTip() {
        assertTrue(AnchorSetup().setupWarnings(2.0).isEmpty())
        assertTrue(AnchorSetup().setupWarnings(4.0).isEmpty())
    }

    @Test fun aPrePositionInTheMiddleOfTheRangeDoesNotWarn() {
        assertFalse(warned(-8.0))
    }

    @Test fun tooCloseToTheCornerWarns() {
        assertTrue(warned(-2.0)); assertTrue(warned(0.0)); assertTrue(warned(3.0))
    }

    @Test fun tooFarOutWarns() {
        assertTrue(warned(-14.0)); assertTrue(warned(-30.0))
    }

    @Test fun theRangeBoundariesThemselvesAreInRange() {
        val range = prePositionRange(2.0)
        assertFalse(warned(range.start)); assertFalse(warned(range.endInclusive))
        assertTrue(warned(range.start - 0.001)); assertTrue(warned(range.endInclusive + 0.001))
    }

    @Test fun theRangeMovesWithTheTipDiameter() {
        assertEquals(-12.0, prePositionRange(4.0).start, 1e-9)
        assertEquals(-4.0, prePositionRange(4.0).endInclusive, 1e-9)
        assertEquals(-13.5, prePositionRange(1.0).start, 1e-9)
        assertEquals(-2.5, prePositionRange(1.0).endInclusive, 1e-9)
        assertFalse(warned(-2.9, tip = 1.0)); assertTrue(warned(-2.9, tip = 4.0))
        assertFalse(warned(-12.9, tip = 1.0)); assertTrue(warned(-12.9, tip = 4.0))
    }

    @Test fun eachAxisWarnsAgainstItsOwnField() {
        val w = AnchorSetup(preX = -8.0, preY = -20.0).setupWarnings(2.0)
        assertEquals(listOf(SetupField.PRE_Y), w.map { it.field })
        assertEquals("Y pre-position -20.00 mm may miss the bracket's top face; use -3 to -13 mm.", w.single().message)
        assertEquals(listOf(SetupField.PRE_X, SetupField.PRE_Y), AnchorSetup(preX = 1.0, preY = -20.0).setupWarnings(2.0).map { it.field })
    }

    // The owner chose advice, not a block: the top-touch check catches a miss before anything plunges.
    @Test fun anOutOfRangePrePositionWarnsButNeverBlocks() {
        val bad = AnchorSetup(preX = -20.0, preY = 1.0, xOff = 30.0, yOff = 12.0)   // touch points 10 and 13
        assertEquals(2, bad.setupWarnings(2.0).size)
        assertTrue(bad.setupProblems(2.0).toString(), bad.setupProblems(2.0).isEmpty())
        assertTrue(bad.savable(2.0))
        assertTrue(canStart(connected = true, idle = true, setup = bad, tip = 2.0))
    }

    @Test fun noWarningsWhileTheTipOrPrePositionIsNotANumber() {
        assertTrue(AnchorSetup().setupWarnings(0.0).isEmpty())
        assertTrue(AnchorSetup(preX = Double.NaN).setupWarnings(2.0).isEmpty())
    }

    @Test fun problemsCarryTheFieldTheyConcern() {
        assertEquals(SetupField.X_OFF, AnchorSetup(xOff = 6.0).setupProblems(2.0).single().field)
        assertEquals(SetupField.Y_OFF, AnchorSetup(yOff = 0.0).setupProblems(2.0).single().field)
        assertEquals(SetupField.TIP, AnchorSetup().setupProblems(0.0).single().field)
        assertNull(AnchorSetup(xOff = Double.NaN).setupProblems(2.0).single().field)
    }

    @Test fun theAxesAreCheckedIndependently() {
        val bad = s.copy(yOff = 34.0)                   // y touch point 30
        assertNull(notchProblem('X', bad.touchPointX(), r, Bracket.THIN))
        assertNotNull(notchProblem('Y', bad.touchPointY(), r, Bracket.THIN))
    }
}
