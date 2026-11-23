package com.z1pendant.probe

import com.z1pendant.util.Fmt
import kotlin.math.abs

enum class Bracket { THIN, THICK }

data class AnchorSetup(
    val xOff: Double = 14.0,
    val yOff: Double = 14.0,
    val depth: Double = 1.8,
    val overshoot: Double = 2.0,
    val preX: Double = -4.0,
    val preY: Double = -4.0,
    val referenceDz: Double = -65.0,
    val thin: Double = 5.0,
    val thick: Double = 15.0,
    val wasteboard: Double = 6.0,
    val defaultBracket: Bracket = Bracket.THIN,
    val defaultWasteboard: Boolean = true,
)

/** The expected drop from the park height to the bracket's top face; not user-editable. */
const val NOMINAL_DROP = 5.0
/** How far the measured drop may differ before Pre-position Z is queried; tighter on the first run. */
const val FIRST_RUN_PROMPT_THRESHOLD = 0.5
const val LATER_RUN_PROMPT_THRESHOLD = 1.0

fun AnchorSetup.thickness(b: Bracket): Double = if (b == Bracket.THIN) thin else thick

fun AnchorSetup.topHeight(b: Bracket, waste: Boolean): Double =
    thickness(b) + if (waste) wasteboard else 0.0

fun AnchorSetup.referenceTopHeight(): Double = thin + wasteboard

/** Approach Z shifts with the live homed Z and any difference from the reference top height. */
fun AnchorSetup.approachZ(homedZ: Double, b: Bracket, waste: Boolean): Double =
    homedZ + referenceDz + (topHeight(b, waste) - referenceTopHeight())

fun AnchorSetup.probeSetup(tip: Double): ProbeSetup = ProbeSetup(
    kind = ProbeKind.INSIDE,
    corner = Corner.FRONT_LEFT,
    tool = ProbeTool.PROBE_3D,
    x = xOff,
    y = yOff,
    depth = depth,
    tip = tip,
)

/** A touch above the approach Z is impossible, so it fails. */
fun topTouchOk(touchZ: Double, approachZ: Double, overshoot: Double): Boolean =
    touchZ <= approachZ && approachZ - touchZ <= NOMINAL_DROP + overshoot

/** A proposed Pre-position Z; [drop] is what the probe fell, [expected] what it should have. */
data class ZCorrection(val drop: Double, val expected: Double, val currentZ: Double, val suggestedZ: Double) {
    /** A larger drop than expected means the park is too high. */
    val tooHigh get() = drop > expected
    val offBy get() = abs(drop - expected)
}

/** Null when the measured drop is close enough to [nominal]; the drop is the same for every bracket/wasteboard. */
fun zCorrection(drop: Double, nominal: Double, firstRun: Boolean, currentZ: Double): ZCorrection? {
    val limit = if (firstRun) FIRST_RUN_PROMPT_THRESHOLD else LATER_RUN_PROMPT_THRESHOLD
    if (abs(drop - nominal) <= limit) return null
    // The drop moves one-for-one with Pre-position Z, so lowering Z by the excess restores the nominal drop.
    return ZCorrection(drop, nominal, currentZ, currentZ - (drop - nominal))
}

fun ZCorrection.message(): String =
    "Probe descended ${Fmt.fixed(drop, 2)} mm, expected ${Fmt.fixed(expected, 2)} mm. " +
        "Pre-position Z looks ${Fmt.fixed(offBy, 2)} mm ${if (tooHigh) "high" else "low"}."

fun ZCorrection.question(): String =
    "Update it to ${Fmt.fixed(suggestedZ, 2)}? (${Fmt.fixed(expected, 2)} mm offset)"

/** Where the probe touches down along each arm: pre-position, then the start offset. */
fun AnchorSetup.touchPointX(): Double = preX + xOff
fun AnchorSetup.touchPointY(): Double = preY + yOff

// Measured from the inside corner; source: docs/reference/Makera L Brackets.dxf
// DXF: notch 24.500-35.500, relief ends 2.475, face 85.000. In use (rounded): 24, 36, 3, 85.
// The rounding is deliberately conservative (refused zone only grows): do not "correct" it to the DXF values.
// The notch is a 180-degree arc of radius 5.500 biting 8.5 mm into the 15 mm arm, so a touch there contacts nothing.
const val INNER_FACE = 85.0
const val NOTCH_START = 24.0
const val NOTCH_END = 36.0
const val CORNER_RELIEF_END = 3.0

/** A fixed limit: whole numbers print plainly, fractions keep their decimals. */
private fun limit(v: Double) = Fmt.trimmed2(v)

/** Null when [touchPoint] (mm from the inside corner) is safe; a recess or the arm's end means no trigger. */
fun notchProblem(axis: Char, touchPoint: Double, tipRadius: Double, bracket: Bracket?): String? {
    val r = tipRadius
    val thin = bracket != Bracket.THICK
    val zone = when {
        touchPoint <= CORNER_RELIEF_END + r -> "inside the corner relief"
        touchPoint >= INNER_FACE - r -> "past the ${limit(INNER_FACE)} mm face"
        thin && touchPoint >= NOTCH_START - r && touchPoint <= NOTCH_END + r ->
            "inside the ${limit(NOTCH_START)}–${limit(NOTCH_END)} mm cut-out"
        else -> return null
    }
    return "$axis touches down ${Fmt.fixed(touchPoint, 2)} mm from the corner — $zone."
}

/** The setting a message belongs to, so the page can show it beside that field; null is not tied to one. */
enum class SetupField { X_OFF, Y_OFF, DEPTH, OVERSHOOT, TIP, PRE_X, PRE_Y, THIN, THICK, WASTEBOARD }

data class SetupIssue(val field: SetupField?, val message: String)

/** Valid pre-position (negative, into the corner block) for a tip: nearest the corner to furthest out. */
fun prePositionRange(tip: Double): ClosedFloatingPointRange<Double> {
    val r = tip / 2
    return -(14.0 - r)..-(2.0 + r)
}

/** Blocking: everything wrong with these settings, empty when usable; the notch guard uses both brackets' zones. */
fun AnchorSetup.setupProblems(tip: Double): List<SetupIssue> {
    val all = listOf(xOff, yOff, depth, overshoot, preX, preY, referenceDz, thin, thick, wasteboard, tip)
    if (all.any { !it.isFinite() }) return listOf(SetupIssue(null, "Every setting must be a number."))
    val r = tip / 2
    fun at(f: SetupField, m: String?) = m?.let { SetupIssue(f, it) }
    // A non-positive offset gets the range message only; its touch point means nothing.
    return listOfNotNull(
        at(SetupField.X_OFF, notchProblem('X', touchPointX(), r, bracket = null)?.takeIf { xOff > 0.0 }),
        at(SetupField.Y_OFF, notchProblem('Y', touchPointY(), r, bracket = null)?.takeIf { yOff > 0.0 }),
        at(SetupField.X_OFF, "The X start offset must be more than 0.".takeIf { xOff <= 0.0 }),
        at(SetupField.Y_OFF, "The Y start offset must be more than 0.".takeIf { yOff <= 0.0 }),
        at(SetupField.DEPTH, "The side probing depth must be more than 0.".takeIf { depth <= 0.0 }),
        at(SetupField.OVERSHOOT, "The probing overshoot allowance can't be negative.".takeIf { overshoot < 0.0 }),
        at(SetupField.THIN, "The thin bracket thickness must be more than 0.".takeIf { thin <= 0.0 }),
        at(SetupField.THICK, "The thick bracket thickness must be more than 0.".takeIf { thick <= 0.0 }),
        at(SetupField.WASTEBOARD, "The wasteboard thickness can't be negative.".takeIf { wasteboard < 0.0 }),
        at(SetupField.TIP, "The tip diameter must be more than 0.".takeIf { tip <= 0.0 }),
    )
}

/** Advisory only: never blocks saving or starting, since the top-touch check catches a miss before any plunge. */
fun AnchorSetup.setupWarnings(tip: Double): List<SetupIssue> {
    if (!tip.isFinite() || tip <= 0.0 || !preX.isFinite() || !preY.isFinite()) return emptyList()
    val range = prePositionRange(tip)
    fun check(axis: Char, f: SetupField, v: Double) = SetupIssue(f,
        "$axis pre-position ${Fmt.fixed(v, 2)} mm may miss the bracket's top face; " +
            "use ${limit(range.endInclusive)} to ${limit(range.start)} mm.").takeIf { v !in range }
    return listOfNotNull(check('X', SetupField.PRE_X, preX), check('Y', SetupField.PRE_Y, preY))
}

/** Only settings that pass the guard are stored. */
fun AnchorSetup.savable(tip: Double): Boolean = setupProblems(tip).isEmpty()

/** Start needs a connected, idle machine and usable settings. */
fun canStart(connected: Boolean, idle: Boolean, setup: AnchorSetup, tip: Double): Boolean =
    connected && idle && setup.savable(tip)

/** The swap button belongs only to a machine sitting in its tool-change state, not a stale stage. */
fun awaitingSwap(a: ToolAction?, machineState: String?): Boolean =
    a != null && a.load && a.error == null && a.stage == ToolAction.Stage.WAITING &&
        machineState.equals("Tool", ignoreCase = true)

/** Two decimals everywhere on the settings page except the tip diameter. */
const val SETTINGS_DECIMALS = 2
const val TIP_DECIMALS = 3

/** What "Reset to defaults" restores, named for the confirmation. */
fun resetSummary(tipDefault: Double): String =
    "the offsets, probing depth, overshoot allowance, pre-position, Z, bracket and wasteboard thicknesses, " +
        "default choices, and the tip diameter (${Fmt.fixed(tipDefault, TIP_DECIMALS)} mm)"

/** The retry needs the probe check to be confirmable and the run's own choices to still be known. */
fun canConfirmRetry(c: ProbeLength.Check?, f: AnchorState.Failed): Boolean =
    canConfirmProbe(c) && f.bracket != null && f.wasteboard != null

const val BED_CLEAR_LEAD = "Clear the bed. Check the settings match the machine's current configuration."
const val BED_CLEAR_REST = " Once the probe is fitted, calibration runs without stopping — " +
    "obstacles on the bed, or an incorrect setting, may damage the probe."

/** Only a 3D probe whose setter reading is unfamiliar can be confirmed; a rod or laser reading is a stop. */
fun canConfirmProbe(c: ProbeLength.Check?): Boolean =
    c != null && c.tool == ProbeTool.PROBE_3D &&
        (c.result == ProbeLength.Result.UNKNOWN || c.result == ProbeLength.Result.NEW)

/** After a halt (reset or Alarm) the restore goes into a refusing machine, so it is attempted, not done. */
fun changedText(changed: String, halted: Boolean): String =
    if (halted && changed != NOTHING_CHANGED && changed != WORK_ZERO_UNCHANGED)
        "$changed The restore of your work zero was attempted after the halt, but the machine had " +
            "just been stopped and may have refused it. Check X0 and Y0 before you cut anything."
    else changed

const val NOTHING_CHANGED = "Nothing on the machine was changed."
const val WORK_ZERO_UNCHANGED = "Your work zero wasn't changed."

fun AnchorStep.label(): String = when (this) {
    AnchorStep.HOME -> "Home the machine"
    AnchorStep.PROBE -> "Fit and calibrate the 3D probe"
    AnchorStep.READ -> "Fetch config coordinates"
    AnchorStep.MOVE -> "Move to the bracket"
    AnchorStep.PROBING -> "Probe the anchor bracket"
    AnchorStep.RESTORE -> "Restore your work zero"
}

/** One result row: the key, then current, measured and change to 3 decimals. */
data class AnchorRow(val key: String, val current: String, val measured: String, val change: String)

fun AnchorResult.rows(): List<AnchorRow> = listOf(
    AnchorRow("anchor1_x", Fmt.fixed(currentX, 3), Fmt.fixed(measuredX, 3), Fmt.fixed(dx, 3, plus = true)),
    AnchorRow("anchor1_y", Fmt.fixed(currentY, 3), Fmt.fixed(measuredY, 3), Fmt.fixed(dy, 3, plus = true)),
)

/** The probe-length check rejected the fitted probe (a laser or rod, say); nothing is about to move. */
fun probeRejected(c: ProbeLength.Check?, f: AnchorState.Failed): Boolean =
    c != null && !c.ok && f.wrongProbe

/**
 * Named to the laser on purpose: it is the one that physically cannot side-probe. The probe rod can,
 * but only against an electrically conductive workpiece, which this routine does not set up or test
 * for, so it is refused along with the laser rather than supported half-way.
 */
const val WRONG_PROBE_NOTE = "Wired Probe 2.0 can touch off on top but can't side-probe, so the run " +
    "fails partway, and using the wrong probe will likely damage it."
