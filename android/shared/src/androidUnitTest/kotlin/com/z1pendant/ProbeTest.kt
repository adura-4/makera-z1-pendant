package com.z1pendant

import com.z1pendant.probe.Corner
import com.z1pendant.probe.ProbeIo
import com.z1pendant.probe.ProbeKind
import com.z1pendant.probe.ProbeSequences
import com.z1pendant.probe.ProbeSetup
import com.z1pendant.probe.ProbeStop
import com.z1pendant.probe.Touch
import com.z1pendant.probe.parsePrb
import com.z1pendant.probe.stepAfterTouches
import com.z1pendant.probe.stepLabels
import com.z1pendant.probe.touchesInStep
import com.z1pendant.probe.widthsFromTouches
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.z1pendant.probe.ProbeLength.Result as R
import com.z1pendant.probe.ProbeTool as T
import kotlin.math.abs

/**
 * The probing sequences against a simulated part: a block standing on the table, or a pocket cut
 * into a slab. The fake machine follows G53 moves and answers G38.2 with where a ball of the given
 * diameter would first touch, like the Z1's [PRB:] report.
 */
class ProbeTest {
    private val eps = 1e-6

    /** A block [x0,x1]×[y0,y1] with its top at [top]; or, if [pocket], a slab with that pocket cut [depth] deep. */
    private class World(val x0: Double, val x1: Double, val y0: Double, val y1: Double, val top: Double,
                        val pocket: Boolean = false, val depth: Double = 10.0, val tip: Double = 2.0) : ProbeIo {
        var x = 0.0; var y = 0.0; var z = 0.0
        val lines = mutableListOf<String>()
        private val r = tip / 2
        private fun inside(px: Double, py: Double) = px in x0..x1 && py in y0..y1
        private fun surfaceAt(px: Double, py: Double) =
            if (pocket) (if (inside(px, py)) top - depth else top) else (if (inside(px, py)) top else top - 100)

        override suspend fun send(line: String) {
            lines += line
            Regex("""G53 G[01] ((?:[XYZ]-?[\d.]+ ?)+)""").find(line)?.groupValues?.get(1)?.split(' ')?.filter { it.isNotBlank() }?.forEach {
                val v = it.drop(1).toDouble()
                when (it[0]) { 'X' -> x = v; 'Y' -> y = v; 'Z' -> z = v }
            }
            if (z < surfaceAt(x, y) - 1e-9) throw ProbeStop("crashed into the part at $x,$y,$z")
        }

        override suspend fun probe(line: String): Touch {
            lines += line
            val m = Regex("""G38\.2 ([XYZ])(-?[\d.]+)""").find(line)!!
            val axis = m.groupValues[1][0]; val d = m.groupValues[2].toDouble()
            val dir = if (d > 0) 1 else -1
            val stop: Double? = when (axis) {
                'Z' -> surfaceAt(x, y).takeIf { it in (z - abs(d))..z }
                'X' -> wall(x, dir, y, true)?.takeIf { (it - x) * dir in 0.0..abs(d) }
                else -> wall(y, dir, x, false)?.takeIf { (it - y) * dir in 0.0..abs(d) }
            }
            stop ?: throw ProbeStop("no touch on $line from $x,$y,$z")
            when (axis) { 'X' -> x = stop; 'Y' -> y = stop; else -> z = stop }
            return Touch(x, y, z)
        }

        /** Ball center where it meets a side moving along one axis (only below the top). */
        private fun wall(p: Double, dir: Int, other: Double, isX: Boolean): Double? {
            val lo = if (isX) x0 else y0; val hi = if (isX) x1 else y1
            val oLo = if (isX) y0 else x0; val oHi = if (isX) y1 else x1
            if (z >= top) return null
            return if (pocket) {
                if (other !in oLo..oHi) null else if (dir < 0) lo + r else hi - r
            } else {
                if (other !in oLo..oHi) null else if (dir > 0 && p < lo) lo - r else if (dir < 0 && p > hi) hi + r else null
            }
        }
    }

    private fun run(w: World, s: ProbeSetup, start: Touch, set: Boolean = false, zeroZ: Double? = null) = runBlocking {
        w.x = start.x; w.y = start.y; w.z = start.z
        ProbeSequences(w, s, start, set, zeroZ).run()
    }

    @Test fun makeraCommands() {
        assertEquals("M480.4 X20.000 Y20.000 Z2.000 D2.000", ProbeSetup(ProbeKind.OUTSIDE, Corner.FRONT_LEFT).makeraCommand())
        assertEquals("M480.1 X20.000 Y30.000 Z1.500 D2.000", ProbeSetup(ProbeKind.OUTSIDE, Corner.BACK_LEFT, y = 30.0, depth = 1.5).makeraCommand())
        assertEquals("M480.6 X20.000 Y20.000 Z2.000 D3.175", ProbeSetup(ProbeKind.INSIDE, Corner.BACK_RIGHT, tip = 3.175).makeraCommand())
        assertEquals("M480.9 X20.000 Y0.000 Z2.000 D2.000", ProbeSetup(ProbeKind.HOLE, axisY = false).makeraCommand())
        assertEquals("M480.10 X0.000 Y20.000 Z2.000 D2.000", ProbeSetup(ProbeKind.BLOCK, axisX = false).makeraCommand())
        assertNull(ProbeSetup(ProbeKind.TOP).makeraCommand())
        // Makera has no routine for these: the pendant runs them itself.
        assertNull(ProbeSetup(ProbeKind.OUTSIDE, axisY = false).makeraCommand())
        assertNull(ProbeSetup(ProbeKind.INSIDE, axisZ = false).makeraCommand())
        assertNull(ProbeSetup(ProbeKind.BLOCK, axisZ = false).makeraCommand())
        // A hole has no Z, so its Z toggle doesn't matter.
        assertEquals("M480.9 X20.000 Y20.000 Z2.000 D2.000", ProbeSetup(ProbeKind.HOLE, axisZ = false).makeraCommand())
    }

    @Test fun axisToggles() {
        val s = ProbeSetup(ProbeKind.OUTSIDE)
        assertEquals("X Y Z", s.axesText())
        val xOnly = s.toggle('Y').toggle('Z')
        assertEquals("X", xOnly.axesText())
        assertEquals("the last axis can't be turned off", xOnly, xOnly.toggle('X'))
        assertEquals(listOf("Touch the X side", "Move over the edge"), xOnly.stepLabels())
        assertEquals(listOf("Touch the top"), s.toggle('X').toggle('Y').stepLabels())
        // Z only, then Hole center (no Z there): everything back on rather than nothing to do.
        val hole = s.toggle('X').toggle('Y').copy(kind = ProbeKind.HOLE).normalized()
        assertTrue(hole.doX && hole.doY && !hole.doZ)
        assertEquals("Outside corner · front-left · X", xOnly.title())
    }

    @Test fun outsideCornerXOnlyWithoutZ() {
        // Z off: no top touch. Z0 is on the top (machine −60) and the probe is parked way up at −20:
        // it goes down to 2 mm below Z0, not 2 mm below where it was parked.
        val w = World(0.0, 50.0, 0.0, 40.0, top = -60.0)
        val m = run(w, ProbeSetup(ProbeKind.OUTSIDE, Corner.FRONT_LEFT, axisY = false, axisZ = false), Touch(5.0, 5.0, -20.0), zeroZ = -60.0)
        assertEquals(0.0, m.edgeX!!, eps); assertNull(m.edgeY); assertNull(m.top)
        assertTrue("never touches Z", w.lines.none { it.startsWith("G38.2 Z") })
        assertTrue("never touches Y", w.lines.none { it.startsWith("G38.2 Y") })
        assertTrue(w.lines.contains("G53 G1 Z-62.000 F300"))
        assertEquals("over the X edge, Y and Z where it started", listOf(0.0, 5.0, -20.0), listOf(w.x, w.y, w.z))
        // No Z0 to go by: it refuses rather than guessing.
        val e = runCatching { run(World(0.0, 50.0, 0.0, 40.0, top = -60.0), ProbeSetup(ProbeKind.OUTSIDE, axisZ = false), Touch(5.0, 5.0, -20.0)) }.exceptionOrNull()
        assertTrue(e is ProbeStop)
        // Parked at or below Z0 with Z off: it won't start (it would sweep into the part).
        assertTrue(ProbeSetup(ProbeKind.OUTSIDE, axisZ = false).startProblem(0.3) != null)
        assertNull(ProbeSetup(ProbeKind.OUTSIDE, axisZ = false).startProblem(40.0))
        assertNull("Z on touches the top itself", ProbeSetup(ProbeKind.OUTSIDE).startProblem(-5.0))
    }

    @Test fun cornerXAndZWithoutY() {
        val w = World(0.0, 50.0, 0.0, 40.0, top = -60.0)
        val m = run(w, ProbeSetup(ProbeKind.OUTSIDE, Corner.BACK_RIGHT, axisY = false), Touch(45.0, 35.0, -55.0))
        assertEquals(50.0, m.edgeX!!, eps); assertNull(m.edgeY); assertEquals(-60.0, m.top!!, eps)
        assertEquals(listOf(50.0, 35.0, -55.0), listOf(w.x, w.y, w.z))
    }

    @Test fun insideCornerYOnly() {
        val w = World(0.0, 40.0, 0.0, 30.0, top = -60.0, pocket = true)
        val m = run(w, ProbeSetup(ProbeKind.INSIDE, Corner.BACK_LEFT, x = 10.0, y = 10.0, axisX = false), Touch(-5.0, 35.0, -55.0))
        assertNull(m.edgeX); assertEquals(30.0, m.edgeY!!, eps); assertEquals(-60.0, m.top!!, eps)
    }

    @Test fun blockCenterWithoutZ() {
        val w = World(0.0, 50.0, 0.0, 40.0, top = -60.0)
        val m = run(w, ProbeSetup(ProbeKind.BLOCK, x = 35.0, y = 30.0, axisZ = false), Touch(24.0, 21.0, -40.0), zeroZ = -60.0)
        assertEquals(25.0, m.centerX!!, eps); assertEquals(20.0, m.centerY!!, eps); assertNull(m.top)
        assertTrue(w.lines.none { it.startsWith("G38.2 Z") })
    }

    @Test fun outsideCornerFrontLeftAndBackRight() {
        val w = World(0.0, 50.0, 0.0, 40.0, top = -60.0)
        val m = run(w, ProbeSetup(ProbeKind.OUTSIDE, Corner.FRONT_LEFT), Touch(5.0, 5.0, -55.0))
        assertEquals(0.0, m.edgeX!!, eps); assertEquals(0.0, m.edgeY!!, eps); assertEquals(-60.0, m.top!!, eps)
        assertEquals("parks over the corner at the start height", listOf(0.0, 0.0, -55.0), listOf(w.x, w.y, w.z))
        assertTrue("never sets the zero in Measure only", w.lines.none { it.startsWith("G10") })
        assertTrue("no relative moves", w.lines.none { "G91" in it })

        val w2 = World(0.0, 50.0, 0.0, 40.0, top = -60.0)
        val m2 = run(w2, ProbeSetup(ProbeKind.OUTSIDE, Corner.BACK_RIGHT, depth = 1.5), Touch(45.0, 35.0, -55.0))
        assertEquals(50.0, m2.edgeX!!, eps); assertEquals(40.0, m2.edgeY!!, eps)
    }

    @Test fun insideCornerBackLeft() {
        val w = World(0.0, 40.0, 0.0, 30.0, top = -60.0, pocket = true)
        // Start over the slab just outside the pocket's back-left corner; X/Y 10 lead into the pocket.
        val m = run(w, ProbeSetup(ProbeKind.INSIDE, Corner.BACK_LEFT, x = 10.0, y = 10.0), Touch(-5.0, 35.0, -55.0))
        assertEquals(0.0, m.edgeX!!, eps); assertEquals(30.0, m.edgeY!!, eps); assertEquals(-60.0, m.top!!, eps)
    }

    @Test fun holeCenterBothAxesAndXOnly() {
        val w = World(0.0, 40.0, 0.0, 30.0, top = -60.0, pocket = true)
        val m = run(w, ProbeSetup(ProbeKind.HOLE), Touch(18.0, 14.0, -62.0))
        assertEquals(20.0, m.centerX!!, eps); assertEquals(15.0, m.centerY!!, eps)
        assertEquals(40.0, m.widthX!!, eps); assertEquals(30.0, m.widthY!!, eps)
        assertNull("a hole has no Z", m.top)

        val w2 = World(0.0, 40.0, 0.0, 30.0, top = -60.0, pocket = true)
        val m2 = run(w2, ProbeSetup(ProbeKind.HOLE, axisY = false), Touch(18.0, 14.0, -62.0))
        assertEquals(20.0, m2.centerX!!, eps); assertNull(m2.centerY)
        assertTrue(w2.lines.none { it.contains(" Y") && it.startsWith("G38") })
    }

    @Test fun blockCenter() {
        val w = World(0.0, 50.0, 0.0, 40.0, top = -60.0)
        val m = run(w, ProbeSetup(ProbeKind.BLOCK, x = 35.0, y = 30.0), Touch(24.0, 21.0, -55.0))
        assertEquals(25.0, m.centerX!!, eps); assertEquals(20.0, m.centerY!!, eps)
        assertEquals(50.0, m.widthX!!, eps); assertEquals(40.0, m.widthY!!, eps)
        assertEquals(-60.0, m.top!!, eps)
    }

    @Test fun topOnlySetsZeroOnlyWhenAsked() {
        val w = World(0.0, 50.0, 0.0, 40.0, top = -60.0)
        val m = run(w, ProbeSetup(ProbeKind.TOP), Touch(10.0, 10.0, -55.0), set = true)
        assertEquals(-60.0, m.top!!, eps)
        val i = w.lines.indexOf("G10 L20 P0 Z0")
        assertTrue("Z0 written right after the slow touch", i > 0 && w.lines[i - 1].startsWith("G38.2 Z-3"))
        assertEquals(-55.0, w.z, eps)

        val w2 = World(0.0, 50.0, 0.0, 40.0, top = -60.0)
        run(w2, ProbeSetup(ProbeKind.TOP), Touch(10.0, 10.0, -55.0), set = false)
        assertTrue(w2.lines.none { it.startsWith("G10") })
    }

    @Test fun startOffsetTooShortIsCaughtNotCrashed() {
        // Outside corner with X 3: stepping out 3 mm from 5 mm in is still over the part.
        val w = World(0.0, 50.0, 0.0, 40.0, top = -60.0)
        val e = runCatching { run(w, ProbeSetup(ProbeKind.OUTSIDE, Corner.FRONT_LEFT, x = 3.0), Touch(5.0, 5.0, -55.0)) }.exceptionOrNull()
        assertTrue(e is ProbeStop)
    }

    @Test fun prbAndMakeraWidths() {
        assertEquals(Touch(-9.691, -13.58, -69.637) to true, parsePrb("[PRB:-9.691,-13.580,-69.637:1]"))
        assertEquals(false, parsePrb("[PRB:1.000,2.000,3.000:0]")!!.second)
        assertNull(parsePrb("ok"))
        // A run on the real machine, 2026-11-15, hole center X only: fast/slow on X−, fast/slow on X+.
        val t = listOf(-177.686, -177.683, -144.903, -144.908).map { Touch(it, -164.497, -56.0) }
        val (wx, wy) = widthsFromTouches(ProbeKind.HOLE, ProbeSetup(ProbeKind.HOLE, axisY = false), t)
        assertEquals(34.775, wx!!, 1e-9); assertNull(wy)
    }

    /** Probes told apart by where they trip the setter (values from the 2026-11-15 test). */
    @Test fun probeLengthCheck() {
        val L = com.z1pendant.probe.ProbeLength
        val learned = mapOf(T.PROBE_3D to -69.570, T.ROD to -72.015, T.LASER to -78.537)
        // What each probe can do: the laser probe only Top only (no "use it instead" for a corner).
        for (k in com.z1pendant.probe.ProbeKind.entries) {
            assertTrue(T.PROBE_3D.canDo(k)); assertTrue(T.ROD.canDo(k))
            assertEquals(k == com.z1pendant.probe.ProbeKind.TOP, T.LASER.canDo(k))
        }
        assertEquals(R.MATCH, L.check(T.PROBE_3D, -69.573, learned).result)
        assertEquals(R.MATCH, L.check(T.LASER, -78.526, learned).result)
        // Picked the 3D probe, the laser probe is in.
        L.check(T.PROBE_3D, -78.530, learned).let { assertEquals(R.LOOKS_LIKE, it.result); assertEquals(T.LASER, it.other); assertFalse(it.ok) }
        // Picked the rod, it reads like nothing learned.
        assertEquals(R.UNKNOWN, L.check(T.ROD, -70.6, learned).result)
        // First time with the laser probe, but it reads like the 3D probe: don't learn it.
        L.check(T.LASER, -69.6, mapOf(T.PROBE_3D to -69.570)).let { assertEquals(R.LOOKS_LIKE, it.result); assertEquals(T.PROBE_3D, it.other) }
        // First time, nothing close: NEW (the controller learns it).
        assertEquals(R.NEW, L.check(T.ROD, -72.0, mapOf(T.PROBE_3D to -69.570)).result)
        // The laser probe forces Top only.
        val s = ProbeSetup(kind = ProbeKind.OUTSIDE).withTool(T.LASER)
        assertEquals(ProbeKind.TOP, s.kind)
    }

    @Test fun touchesWithinAStepPickTheSide() {
        // Block center, X and Y: top (2 touches), X sides (4: left fast/slow, right fast/slow), Y sides (4), center.
        val s = ProbeSetup(kind = ProbeKind.BLOCK, axisX = true, axisY = true)
        fun at(n: Int) = s.stepAfterTouches(n).let { it to s.touchesInStep(it, n) }
        assertEquals(0 to 0, at(0))
        assertEquals(1 to 0, at(2))      // X sides started: the left side
        assertEquals(1 to 1, at(3))
        assertEquals(1 to 2, at(4))      // left done, now the right side
        assertEquals(1 to 3, at(5))
        assertEquals(2 to 0, at(6))      // Y sides: the front
        assertEquals(2 to 2, at(8))      // then the back
        // An echo can move the run on before its touches: never negative.
        assertEquals(0, s.touchesInStep(2, 3))
    }
}
