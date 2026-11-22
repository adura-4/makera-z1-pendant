package com.z1pendant

import com.z1pendant.link.StatusParser
import com.z1pendant.run.Assist
import com.z1pendant.run.CamOffset
import com.z1pendant.run.Extents
import com.z1pendant.run.Mesh
import com.z1pendant.run.Origin
import com.z1pendant.run.RunCommands
import com.z1pendant.run.RunSetup
import com.z1pendant.run.Toolpath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Run flow's pure parts, checked against what Makera Studio sent on 2026-11-17. */
class RunModelTest {
    private val small = """
        (T1  D=3.175 - flat end mill)
        G90 G94
        T1 M6
        G0 X48.965 Y98.647
        G0 Z5
        G1 Z-1 F300
        G1 X10.201 Y50
        G2 X99.799 Y7.188 I1 J1
        G1 X50 Y102.813
        G53 G0 X-200 Y-200 (machine coordinates: not part of the toolpath)
        G0 X300 Y300
        M30
    """.trimIndent()

    @Test fun extentsAreTheFeedMovesEndPoints() {
        assertEquals(Extents(10.201, 7.188, 99.799, 102.813), Toolpath.extents(small))
    }

    @Test fun extentsHandleIncrementalAndModalMoves() {
        val e = Toolpath.extents("G90\nG0 X10 Y10\nG1 Z-1\nG91\nX5\nY-3\nG90 G0 X0\n")!!
        // Only moves with X/Y words count (the plunge at X10 Y10 doesn't), like Studio's numbers.
        assertEquals(Extents(15.0, 7.0, 15.0, 10.0), e)
        assertNull(Toolpath.extents("G0 X1 Y1\nG0 X5 Y5\n"))
    }

    /** The real file Studio started: it sent `M495 X10.201Y7.188P1`. Skipped if the file isn't on this PC. */
    @Test fun matchesStudioOnTheRealFile() {
        val f = File("E:/Makera Z1 CNC Stuff/GCODE/can_lid_op_1/can_lid_op1.cnc")
        if (!f.exists()) return
        val e = Toolpath.extents(f.readText())!!
        assertEquals("M495 X10.201Y7.188P1", RunCommands.m495(RunSetup(), e))
        assertFalse(Toolpath.cutsBeforeToolChange(f.readText()))
    }

    @Test fun m495CarriesOnlyWhatIsOn() {
        val e = Extents(10.201, 7.188, 99.799, 102.813)
        val all = RunSetup(origin = Origin.ANCHOR1, marginAtStart = true, leveling = true, gridX = 3, gridY = 4, clearance = 5.0)
        assertEquals("M495 X10.201Y7.188C99.799D102.813O44.799F47.813A89.598B95.625I3J4H5.000P1", RunCommands.m495(all, e))
        assertEquals("M495 X10.201Y7.188C99.799D102.813", RunCommands.scanMargin(e))
        val z = RunSetup(probeZ = true, zAtX = 5.0, zAtY = 6.0)
        assertEquals("M495 X10.201Y7.188O5.000F6.000P1", RunCommands.m495(z, e))
    }

    @Test fun startLinesWithoutLevelingAreStudios() {
        val e = Extents(10.201, 7.188, 99.799, 102.813)
        assertEquals(listOf("M495 X10.201Y7.188C99.799D102.813P1"), RunCommands.startLines(RunSetup(marginAtStart = true), e, 100.0))
    }

    @Test fun levelingIsInsetAndDepthLimited() {
        // Tonight's job: the cutting area runs past the 19 x 38 block.
        val e = Extents(-13.747, -23.434, 13.99, 22.002)
        val s = RunSetup(marginAtStart = true, probeZ = true, leveling = true, gridX = 3, gridY = 3, clearance = 5.0, levelInset = 3.0)
        assertEquals(listOf(
            "M670 Z3.500",                                                   // (5 + 2) / 2: touches reach 2 mm below the top
            "M495 X-13.747Y-23.434C13.990D22.002O13.869F22.718",             // scan + Z probe, no P
            "M495 X-10.747Y-20.434A21.737B39.436I3J3H5.000P1",               // the grid, 3 mm in
            "M670 Z100.000"), RunCommands.startLines(s, e, 100.0))
        // Leveling alone: no scan / Z probe line.
        assertEquals(3, RunCommands.startLines(RunSetup(leveling = true), e, 100.0).size)
    }

    @Test fun haltReasonIsParsedAndNamed() {
        val st = com.z1pendant.link.StatusParser.parse("<Alarm|MPos:-1.0,-1.0,-1.0,0.0,0.0|WPos:0,0,0,0,0|H:13>", 0)!!
        assertEquals(13, st.halt)
        assertEquals("E-stop pressed", com.z1pendant.ui.haltText(st.halt))
        assertNull(com.z1pendant.link.StatusParser.parse("<Idle|MPos:0,0,0|WPos:0,0,0>", 0)!!.halt)
    }

    @Test fun insetNeverTurnsTheGridInsideOut() {
        val a = RunSetup(levelInset = 50.0).levelArea(Extents(0.0, 0.0, 10.0, 40.0))
        assertEquals(1.0, a.width, 1e-9); assertTrue(a.height > 0)
    }

    @Test fun anchorOneLocksProbeZAndSetsTheOffset() {
        val s = RunSetup().withOrigin(Origin.ANCHOR1).copy(offX = 0.0, offY = -3.0, probeZ = false)
        assertTrue(s.zOn); assertTrue(s.needsLaser)
        assertEquals(listOf("G10L2P0X-190.690Y-197.580"), RunCommands.originLines(s, -190.69 to -194.58))
        assertEquals(listOf("G10L20P0X0Y0"), RunCommands.originLines(RunSetup(origin = Origin.SPINDLE), null))
        assertEquals(emptyList<String>(), RunCommands.originLines(RunSetup(), null))
        // Probe Z defaults: on when the origin sets X/Y only.
        assertTrue(RunSetup().withOrigin(Origin.SPINDLE).probeZ)
        assertFalse(RunSetup().withOrigin(Origin.CURRENT).probeZ)
        assertFalse(RunSetup().withOrigin(Origin.PROBE3D).needsLaser)
    }

    @Test fun playUsesTheBoardsSpaceCharacter() {
        assertEquals("play /sd/gcodes/can_lid_op1.cnc", RunCommands.play("/sd/gcodes/can_lid_op1.cnc"))
        assertEquals("play /sd/gcodes/a\u0001b.nc", RunCommands.play("/sd/gcodes/a b.nc"))
    }

    @Test fun camOffsetFromTheCommunityPostHeader() {
        val h = "(  X Offset   : 0 )\n(  Y Offset   : -3 )\n"
        assertEquals(CamOffset(0.0, -3.0), CamOffset.parse(h))
        assertNull(CamOffset.parse("(T1 D=3)"))
    }

    @Test fun cutBeforeFirstToolChange() {
        assertTrue(Toolpath.cutsBeforeToolChange("G90\nG0 X0 Y0\nG1 Z-1 F100\nM6 T1\n"))
        assertFalse(Toolpath.cutsBeforeToolChange("(G1 in a comment)\nG90\nT1M6\nG1 X1\n"))
        assertFalse(Toolpath.cutsBeforeToolChange("G90\nG0 X0 Y0\nM6 T1\nG1 X1\n"))
    }

    @Test fun meshBothFormats() {
        val plain = "0.0100 0.0200 -0.0100 \n0.0000 0.0500 0.0300 \nok\n"
        assertEquals(listOf(listOf(0.01, 0.02, -0.01), listOf(0.0, 0.05, 0.03)), Mesh.parse(plain))
        val human = """
              50.0000|    0.0000     0.0500     0.0300
               0.0000|    0.0100     0.0200    -0.0100
                       -----+----------+----------+-----
                       0.0000 40.0000 80.0000
        """.trimIndent()
        assertEquals(listOf(listOf(0.01, 0.02, -0.01), listOf(0.0, 0.05, 0.03)), Mesh.parse(human))
        assertEquals(0.06, Mesh.spread(Mesh.parse(plain)!!), 1e-9)
        assertNull(Mesh.parse("ok\n"))
        assertEquals(0.05, Mesh.largest(Mesh.parse(plain)!!)!!, 1e-9)
    }

    /** The Z1 right after power-on (2026-11-22): `|O:` reported, but `M375.1` prints every point as nan. */
    @Test fun meshEmptyNanGrid() {
        val ys = (14 downTo 0).map { 50.0 * it / 14 }
        val text = ys.joinToString("\n") { y -> "%.4f|".format(y) + "        nan".repeat(15) } +
            "\n-----+" + "----------+".repeat(14) + "-----\n" + (0..14).joinToString(" ") { "%.4f".format(50.0 * it / 14) } + "\nok\n"
        val g = Mesh.parse(text)!!
        assertEquals(15, g.size); assertEquals(15, g[0].size)
        assertTrue(g.flatten().all { it.isNaN() })
        assertNull(Mesh.largest(g))
        assertEquals(0.0, Mesh.spread(g), 0.0)
        // A grid with some empty points still has a largest value (with its sign).
        assertEquals(-0.08, Mesh.largest(listOf(listOf(Double.NaN, 0.02), listOf(-0.08, Double.NaN)))!!, 1e-9)
    }

    @Test fun assistAndMeshFromStatus() {
        // Captured 2026-11-17 after M331, M331.1, M331.2 (M331.4 had been cleared by the abort).
        val st = StatusParser.parse("<Idle|MPos:-9.6906,-13.5797,-8.0215,0.0,0.0|WPos:161.6694,142.2923,35.1043,-90.0,0.0|" +
            "F:0.0,3000.0,100.0|S:0.0,10000.0,100.0,1,28.9,23.0,1,1,0,0|T:-1,-2.419,1|O:0.081>", 0)!!
        assertEquals(true, Assist.VACUUM.state(st.sFields))
        assertEquals(true, Assist.BLOW.state(st.sFields))
        assertEquals(true, Assist.BED_CLEAN.state(st.sFields))
        assertEquals(false, Assist.ANTI_STATIC.state(st.sFields))
        assertEquals(0.081, st.mesh!!, 1e-9)
        assertNull(StatusParser.parse("<Idle|MPos:0,0,0|WPos:0,0,0>", 0)!!.mesh)
    }
}
