package com.z1pendant.probe

import com.z1pendant.util.Fmt
import kotlin.math.abs

/**
 * 3D probing on the stock Z1: what you probe, its settings, Makera's own command for it, and the
 * touch sequences the pendant runs itself ("Measure only", and "Top only" in both modes).
 *
 * Makera's routines (`M480.1`–`.10`, firmware `ATCHandler::fill_*_scripts`) run inside the machine
 * and write the work zero with `G10 L20` between touches. The sequences here make the same moves
 * from the pendant, in machine coordinates (G53) so nothing depends on a zero being set, and read
 * each touch back from the machine's `[PRB:x,y,z:1]` report.
 */
enum class ProbeKind(val label: String) {
    OUTSIDE("Outside corner"), INSIDE("Inside corner"), HOLE("Hole center"), BLOCK("Block center"), TOP("Top only");
    val hasCorner get() = this == OUTSIDE || this == INSIDE
    val hasCenterAxes get() = this == HOLE || this == BLOCK
    /** Sets / measures Z (the top surface). A hole starts inside the hole, so it has no Z. */
    val hasTop get() = this != HOLE
}

/** Corner seen from above, front = toward you (machine −Y). Makera's "top-left" = back-left. */
enum class Corner(val label: String) {
    BACK_LEFT("back-left"), BACK_RIGHT("back-right"), FRONT_LEFT("front-left"), FRONT_RIGHT("front-right");
    val left get() = this == BACK_LEFT || this == FRONT_LEFT
    val back get() = this == BACK_LEFT || this == BACK_RIGHT
}

enum class CenterAxes(val label: String) { XY("X and Y"), X("X only"), Y("Y only") }

/** [noun] = the name inside a sentence ("Load the 3D probe", "the laser probe"). */
enum class ProbeTool(val label: String, val noun: String) {
    PROBE_3D("3D probe", "3D probe"), ROD("Probe rod", "probe rod"), LASER("Laser probe", "laser probe");
    /** The laser probe (the stock head probe) only senses straight down, so it can only do Top only. */
    val topOnly get() = this == LASER
    /** Whether this probe can do [k] (the laser probe only senses straight down). */
    fun canDo(k: ProbeKind) = !topOnly || k == ProbeKind.TOP
}

/**
 * Which probe is in, from where it trips the tool setter: the machine Z of the calibration's slow
 * touch (`G38.6 … P1`). Each probe sits at its own length. On 2026-11-15 the 3D probe, the probe rod
 * and the laser probe read −69.57, −72.02 and −78.53, repeating within 0.011 mm after reinserting
 * (reference/probe_length_test_20261115.md). Values are learned per machine, not built in.
 */
object ProbeLength {
    /** How far a reading may be from a probe's learned value and still be that probe. */
    const val WINDOW = 0.5

    enum class Result {
        /** Matches this probe's learned value. */
        MATCH,
        /** First calibration of this probe: its value was just learned. */
        LEARNED,
        /** This probe has no learned value (and the reading matches no other probe). */
        NEW,
        /** Matches another probe's learned value, not this one's. */
        LOOKS_LIKE,
        /** Matches nothing learned: another probe, or seated differently. */
        UNKNOWN,
    }

    data class Check(val tool: ProbeTool, val z: Double, val result: Result, val expected: Double? = null, val other: ProbeTool? = null) {
        /** The wizard may go on (the probe is the one you picked, or this was its first time). */
        val ok get() = result == Result.MATCH || result == Result.LEARNED
    }

    fun check(tool: ProbeTool, z: Double, learned: Map<ProbeTool, Double>): Check {
        val exp = learned[tool]
        if (exp != null && abs(z - exp) <= WINDOW) return Check(tool, z, Result.MATCH, exp)
        val other = learned.filterKeys { it != tool }.entries
            .filter { abs(z - it.value) <= WINDOW }.minByOrNull { abs(z - it.value) }
        return when {
            other != null -> Check(tool, z, Result.LOOKS_LIKE, exp, other.key)
            exp == null -> Check(tool, z, Result.NEW)
            else -> Check(tool, z, Result.UNKNOWN, exp)
        }
    }
}

enum class ProbeMode { SET_ZERO, MEASURE }

/** Everything the run needs. Distances in mm, always positive; the direction comes from the corner. */
data class ProbeSetup(
    val kind: ProbeKind = ProbeKind.OUTSIDE,
    val corner: Corner = Corner.FRONT_LEFT,
    val axes: CenterAxes = CenterAxes.XY,
    val tool: ProbeTool = ProbeTool.PROBE_3D,
    /** "X / Y start offset": how far it moves out (corner, block) or searches (hole, inside corner). */
    val x: Double = 20.0,
    val y: Double = 20.0,
    /** "Side depth": how far below the top the ball goes before touching the sides. */
    val depth: Double = 2.0,
    /** Ball (tip) diameter. */
    val tip: Double = 2.0,
) {
    /** Pick a probe; the laser probe can only do Top only. */
    fun withTool(t: ProbeTool) = copy(tool = t, kind = if (t.topOnly) ProbeKind.TOP else kind)

    val doX get() = !kind.hasCenterAxes || axes != CenterAxes.Y
    val doY get() = !kind.hasCenterAxes || axes != CenterAxes.X

    fun title(): String = when {
        kind.hasCorner -> "${kind.label} · ${corner.label}"
        kind.hasCenterAxes && axes != CenterAxes.XY -> "${kind.label} · ${axes.label}"
        else -> kind.label
    }

    /**
     * Makera's command for this setup, as Makera Studio sends it (`M480.%n X Y Z D`, all positive,
     * 3 decimals; a center on one axis sends 0 for the other). Null for Top only, which has none.
     */
    fun makeraCommand(): String? {
        val n = when (kind) {
            ProbeKind.OUTSIDE -> cornerIndex()
            ProbeKind.INSIDE -> cornerIndex() + 4
            ProbeKind.HOLE -> 9
            ProbeKind.BLOCK -> 10
            ProbeKind.TOP -> return null
        }
        val xv = if (kind.hasCenterAxes && axes == CenterAxes.Y) 0.0 else abs(x)
        val yv = if (kind.hasCenterAxes && axes == CenterAxes.X) 0.0 else abs(y)
        return "M480.$n X${f3(xv)} Y${f3(yv)} Z${f3(abs(depth))} D${f3(abs(tip))}"
    }

    /** Makera's corner numbering: 1 top(back)-left, 2 back-right, 3 front-right, 4 front-left. */
    private fun cornerIndex() = when (corner) {
        Corner.BACK_LEFT -> 1; Corner.BACK_RIGHT -> 2; Corner.FRONT_RIGHT -> 3; Corner.FRONT_LEFT -> 4
    }
}

internal fun f3(v: Double) = Fmt.fixed(v, 3)

/** The steps of a run, as the probing screen lists them. */
object Steps {
    const val TOP = "Touch the top"
    const val X_SIDE = "Touch the X side"; const val Y_SIDE = "Touch the Y side"
    const val X_WALL = "Touch the X wall"; const val Y_WALL = "Touch the Y wall"
    const val X_WALLS = "Touch the X walls"; const val Y_WALLS = "Touch the Y walls"
    const val X_SIDES = "Touch the X sides"; const val Y_SIDES = "Touch the Y sides"
    const val CORNER = "Move over the corner"; const val CENTER = "Move to the center"

    /** Touches each step makes (fast + slow per side), to follow Makera's routine by its [PRB:] reports. */
    fun touches(label: String) = when (label) {
        TOP, X_SIDE, Y_SIDE, X_WALL, Y_WALL -> 2
        X_WALLS, Y_WALLS, X_SIDES, Y_SIDES -> 4
        else -> 0
    }
}

/** The steps this setup's run goes through, in order. */
fun ProbeSetup.stepLabels(): List<String> = when (kind) {
    ProbeKind.OUTSIDE -> listOf(Steps.TOP, Steps.X_SIDE, Steps.Y_SIDE, Steps.CORNER)
    ProbeKind.INSIDE -> listOf(Steps.TOP, Steps.X_WALL, Steps.Y_WALL, Steps.CORNER)
    ProbeKind.HOLE -> listOfNotNull(Steps.X_WALLS.takeIf { doX }, Steps.Y_WALLS.takeIf { doY }, Steps.CENTER)
    ProbeKind.BLOCK -> listOfNotNull(Steps.TOP, Steps.X_SIDES.takeIf { doX }, Steps.Y_SIDES.takeIf { doY }, Steps.CENTER)
    ProbeKind.TOP -> listOf(Steps.TOP)
}

/** The step a run is on after [n] touches (Makera's routine reports each touch). */
fun ProbeSetup.stepAfterTouches(n: Int): Int {
    val labels = stepLabels()
    var sum = 0
    labels.forEachIndexed { i, l -> sum += Steps.touches(l); if (n < sum) return i }
    return labels.lastIndex
}

/** Of [total] touches so far, how many fall in step [index] (0 when an echo moved the run on early). */
fun ProbeSetup.touchesInStep(index: Int, total: Int): Int {
    val before = stepLabels().take(index).sumOf { Steps.touches(it) }
    return (total - before).coerceIn(0, Steps.touches(stepLabels().getOrElse(index) { "" }).coerceAtLeast(0))
}

/** One touch, from `[PRB:x,y,z:1]`: machine coordinates of the spindle when the probe triggered. */
data class Touch(val x: Double, val y: Double, val z: Double)

/** `[PRB:-9.691,-13.580,-69.637:1]` → the touch, or null for a miss (`:0`) / anything else. */
fun parsePrb(line: String): Pair<Touch, Boolean>? {
    val m = Regex("""\[PRB:\s*(-?[\d.]+),\s*(-?[\d.]+),\s*(-?[\d.]+)(?:,[^:\]]*)*:(\d)\]""").find(line) ?: return null
    val (x, y, z, ok) = m.destructured
    return Touch(x.toDouble(), y.toDouble(), z.toDouble()) to (ok == "1")
}

/**
 * What a run found, in machine coordinates. Edges are the part's sides (the ball's radius already
 * taken off); centers are midway between two sides; widths are side to side.
 */
data class Measured(
    val edgeX: Double? = null, val edgeY: Double? = null,
    val top: Double? = null,
    val centerX: Double? = null, val centerY: Double? = null,
    val widthX: Double? = null, val widthY: Double? = null,
) {
    /** The point that becomes (or would become) X0 / Y0. */
    val zeroX get() = centerX ?: edgeX
    val zeroY get() = centerY ?: edgeY
}

/** Machine-side moves the sequences need; implemented over the link (and faked in tests). */
interface ProbeIo {
    /** Send a line and wait for its ok. Throws [ProbeStop] if the machine refuses it or stops. */
    suspend fun send(line: String)
    /** Send a probing move (G38.2 …) and wait for the touch. Throws [ProbeStop] on a miss or a stop. */
    suspend fun probe(line: String): Touch
    /** Progress for the screen ("Touching the X side"). */
    fun step(text: String) {}
}

class ProbeStop(message: String) : Exception(message)

/** Probe feeds and distances, as Makera's routines use them (Z1 config: atc.probe.*). */
object ProbeFeeds {
    const val FAST = 100.0          // atc.probe.slow_rate_mm_m
    const val SLOW = 50.0           // half of it, for the second touch
    const val RETRACT = 1.0         // atc.probe.retract_mm
    const val SECOND = 3.0          // search for the second touch: retract + 2 mm
    const val Z_SEARCH = 108.0      // coordinate.toolrack_z: how far the first Z touch may go down
    /** Makera lowers to the side depth with G0 at 10 % feed override (M220 S10); this is the same speed. */
    const val PLUNGE = 300.0
}

/**
 * The touch sequences, run from the pendant. Positions are G53 (machine) moves; probing moves are
 * relative, as on the Z1 (`G38.2 X20` = search up to 20 mm in +X). [setZero] only matters for Top
 * only, the one kind without a Makera routine: it writes Z0 at the touch like Makera's routines do.
 */
class ProbeSequences(private val io: ProbeIo, private val s: ProbeSetup, private val start: Touch, private val setZero: Boolean) {
    private val r = s.tip / 2

    suspend fun run(): Measured = when (s.kind) {
        ProbeKind.OUTSIDE -> outsideCorner()
        ProbeKind.INSIDE -> insideCorner()
        ProbeKind.HOLE -> hole()
        ProbeKind.BLOCK -> block()
        ProbeKind.TOP -> Measured(top = top())
    }

    /** Down onto the top twice (fast, then slow from 1 mm up); returns the top's machine Z. */
    private suspend fun top(): Double {
        io.step(Steps.TOP)
        val t1 = io.probe("G38.2 Z-${f3(ProbeFeeds.Z_SEARCH)} F${f0(ProbeFeeds.FAST)}")
        io.send("G53 G0 Z${f3(t1.z + ProbeFeeds.RETRACT)}")
        val t2 = io.probe("G38.2 Z-${f3(ProbeFeeds.SECOND)} F${f0(ProbeFeeds.SLOW)}")
        if (setZero) io.send("G10 L20 P0 Z0")
        io.send("G53 G0 Z${f3(start.z)}")
        return t2.z
    }

    /**
     * Toward a side along [axis] in direction [dir] (+1 / −1): a fast touch searching [search] mm,
     * back off 1 mm, a slow touch. Returns the slow touch's coordinate on that axis.
     */
    private suspend fun side(axis: Char, dir: Int, search: Double): Double {
        val t1 = io.probe("G38.2 $axis${f3(dir * search)} F${f0(ProbeFeeds.FAST)}")
        io.send("G53 G0 $axis${f3(t1.on(axis) - dir * ProbeFeeds.RETRACT)}")
        val t2 = io.probe("G38.2 $axis${f3(dir * ProbeFeeds.SECOND)} F${f0(ProbeFeeds.SLOW)}")
        io.send("G53 G0 $axis${f3(t2.on(axis) - dir * ProbeFeeds.RETRACT)}")
        return t2.on(axis)
    }

    private suspend fun plunge(topZ: Double) = io.send("G53 G1 Z${f3(topZ - s.depth)} F${f0(ProbeFeeds.PLUNGE)}")

    /** Starts over the part near the corner: top, then out past each side, down, and back in. */
    private suspend fun outsideCorner(): Measured {
        val dx = if (s.corner.left) 1 else -1          // direction the probe moves to reach the X side
        val dy = if (s.corner.back) -1 else 1
        val topZ = top()
        io.step(Steps.X_SIDE)
        io.send("G53 G0 X${f3(start.x - dx * s.x)}")
        plunge(topZ)
        val ex = side('X', dx, s.x) + dx * r
        io.send("G53 G0 Z${f3(start.z)}")
        io.send("G53 G0 X${f3(start.x)}")
        io.step(Steps.Y_SIDE)
        io.send("G53 G0 Y${f3(start.y - dy * s.y)}")
        plunge(topZ)
        val ey = side('Y', dy, s.y) + dy * r
        io.send("G53 G0 Z${f3(start.z)}")
        io.step(Steps.CORNER)
        io.send("G53 G0 X${f3(ex)} Y${f3(ey)}")
        return Measured(edgeX = ex, edgeY = ey, top = topZ)
    }

    /** Starts over the top next to the corner: top, then into the pocket, down, and out to each wall. */
    private suspend fun insideCorner(): Measured {
        val px = if (s.corner.left) -1 else 1          // direction to the X wall
        val py = if (s.corner.back) 1 else -1
        val topZ = top()
        io.step(Steps.X_WALL)
        val inX = start.x - px * s.x
        val inY = start.y - py * s.y
        io.send("G53 G0 X${f3(inX)} Y${f3(inY)}")
        plunge(topZ)
        val ex = side('X', px, s.x) + px * r
        io.send("G53 G0 X${f3(inX)}")
        io.step(Steps.Y_WALL)
        val ey = side('Y', py, s.y) + py * r
        io.send("G53 G0 Y${f3(inY)}")
        io.send("G53 G0 Z${f3(start.z)}")
        io.step(Steps.CORNER)
        io.send("G53 G0 X${f3(ex)} Y${f3(ey)}")
        return Measured(edgeX = ex, edgeY = ey, top = topZ)
    }

    /** Starts inside the hole at touching depth: out to both walls on each axis, then to the middle. */
    private suspend fun hole(): Measured {
        var cx: Double? = null; var cy: Double? = null; var wx: Double? = null; var wy: Double? = null
        if (s.doX) {
            io.step(Steps.X_WALLS)
            val a = side('X', -1, s.x)
            val b = side('X', 1, 2 * s.x)
            cx = (a + b) / 2; wx = b - a + s.tip
            io.send("G53 G0 X${f3(cx)}")
        }
        if (s.doY) {
            io.step(Steps.Y_WALLS)
            val a = side('Y', -1, s.y)
            val b = side('Y', 1, 2 * s.y)
            cy = (a + b) / 2; wy = b - a + s.tip
            io.send("G53 G0 Y${f3(cy)}")
        }
        io.step(Steps.CENTER)
        return Measured(centerX = cx, centerY = cy, widthX = wx, widthY = wy)
    }

    /** Starts over the block, roughly centered: top, then down outside each side and in. */
    private suspend fun block(): Measured {
        val topZ = top()
        var cx: Double? = null; var cy: Double? = null; var wx: Double? = null; var wy: Double? = null
        if (s.doX) {
            io.step(Steps.X_SIDES)
            io.send("G53 G0 X${f3(start.x - s.x)}"); plunge(topZ)
            val a = side('X', 1, s.x)
            io.send("G53 G0 Z${f3(start.z)}"); io.send("G53 G0 X${f3(start.x + s.x)}"); plunge(topZ)
            val b = side('X', -1, 2 * s.x)
            io.send("G53 G0 Z${f3(start.z)}")
            cx = (a + b) / 2; wx = b - a - s.tip
            io.send("G53 G0 X${f3(cx)}")
        }
        if (s.doY) {
            io.step(Steps.Y_SIDES)
            io.send("G53 G0 Y${f3(start.y - s.y)}"); plunge(topZ)
            val a = side('Y', 1, s.y)
            io.send("G53 G0 Z${f3(start.z)}"); io.send("G53 G0 Y${f3(start.y + s.y)}"); plunge(topZ)
            val b = side('Y', -1, 2 * s.y)
            io.send("G53 G0 Z${f3(start.z)}")
            cy = (a + b) / 2; wy = b - a - s.tip
            io.send("G53 G0 Y${f3(cy)}")
        }
        io.step(Steps.CENTER)
        return Measured(top = topZ, centerX = cx, centerY = cy, widthX = wx, widthY = wy)
    }

    private fun Touch.on(axis: Char) = when (axis) { 'X' -> x; 'Y' -> y; else -> z }
    private fun f0(v: Double) = Fmt.fixed(v, 0)
}

/**
 * Widths from the touches Makera's routine reports (each side is touched twice: fast, then slow;
 * the slow one counts). Hole: [X−, X+] per axis; block: two Z touches first, then [X+side, X−side].
 */
fun widthsFromTouches(kind: ProbeKind, setup: ProbeSetup, touches: List<Touch>): Pair<Double?, Double?> {
    val t = if (kind == ProbeKind.BLOCK) touches.drop(2) else touches
    fun pair(i: Int, axis: Char): Double? {
        val a = t.getOrNull(i * 4 + 1) ?: return null
        val b = t.getOrNull(i * 4 + 3) ?: return null
        val d = abs(if (axis == 'X') b.x - a.x else b.y - a.y)
        return if (kind == ProbeKind.HOLE) d + setup.tip else d - setup.tip
    }
    if (kind != ProbeKind.HOLE && kind != ProbeKind.BLOCK) return null to null
    return when (setup.axes) {
        CenterAxes.XY -> pair(0, 'X') to pair(1, 'Y')
        CenterAxes.X -> pair(0, 'X') to null
        CenterAxes.Y -> null to pair(0, 'Y')
    }
}
