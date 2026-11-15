package com.z1pendant

import com.z1pendant.link.GrblishDialect
import com.z1pendant.link.StatusParser
import com.z1pendant.util.Fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpindleTest {
    /** A status line captured from the real Z1 (2026-11-08): current RPM 0, target 10000, tool 4. */
    private val real = "<Idle|MPos:-1.0000,-1.0000,-1.0000,0.0000,0.0000|WPos:145.1480,156.9800,55.0637,-90.0000,0.0000" +
        "|F:0.0,3000.0,100.0|S:0.0,10000.0,100.0,0,20.5,20.8,0,0,0,0|T:4,-7.106,-1|L:0, 0, 0, 0.0,100.0|C:4,1,0,1>"

    @Test fun statusCarriesRpmAndTool() {
        val st = StatusParser.parse(real, 0)!!
        assertEquals(0.0, st.spindleRpm!!, 0.0)
        assertEquals(4, st.tool)
        assertEquals(12000.0, StatusParser.parse(real.replace("S:0.0,", "S:12000.0,"), 0)!!.spindleRpm!!, 0.0)
        assertNull(StatusParser.parse("<Idle|MPos:0,0,0>", 0)!!.tool)
    }

    @Test fun commands() {
        assertEquals(listOf("M3 S12000"), GrblishDialect.spindleOn(12000))
        assertEquals(listOf("M5"), GrblishDialect.spindleOff())
    }

    @Test fun thousands() {
        assertEquals("0", Fmt.thousands(0))
        assertEquals("999", Fmt.thousands(999))
        assertEquals("13,000", Fmt.thousands(13000))
        assertEquals("1,234,567", Fmt.thousands(1234567))
        assertEquals("-12,000", Fmt.thousands(-12000))
    }

    @Test fun probeToolNumbers() {
        for (t in listOf(0, 9999, 999990, 999995)) assert(isProbeTool(t)) { "T$t is a probe" }
        for (t in listOf(-1, 1, 4, 6, 9998, 99999)) assert(!isProbeTool(t)) { "T$t is not a probe" }
    }

    @Test fun topPresetIsTheMax() = assertEquals(SPINDLE_MAX_RPM, SPINDLE_PRESETS.last())

    @Test fun overrideStatusFields() {
        val st = StatusParser.parse(real.replace("S:0.0,10000.0,100.0,", "S:10400.0,8000.0,130.0,"), 0)!!
        assertEquals(8000.0, st.spindleTarget!!, 0.0)
        assertEquals(130.0, st.spindleOverride!!, 0.0)
    }

    /** The examples from the design talk: RPM on the bar → % of the programmed RPM. */
    @Test fun overrideSliderMath() {
        val s8 = spindleSlideFor(8000.0, 100.0)
        assertEquals(8000, s8.now)
        assertEquals(4000, s8.lo); assertEquals(13000, s8.hi)
        assertEquals(162.5, s8.percentFor(13000), 1e-9)
        assertEquals(130.0, s8.percentFor(10400), 1e-9)
        assertEquals(50.0, s8.percentFor(1000), 1e-9)           // clamped to the floor
        val s12 = spindleSlideFor(12000.0, 100.0)
        assertEquals(6000, s12.lo); assertEquals(13000, s12.hi)
        assertEquals(108.33, s12.percentFor(13000), 1e-9)
        assertEquals(9700, spindleSlideFor(8000.0, s8.percentFor(9700)).now)   // lands on the drag
        val s4 = spindleSlideFor(4000.0, 100.0)
        assertEquals(8000, s4.hi)                                 // 200 % (stock clamp) comes first
        assertEquals(7800, spindleSlideFor(6000.0, 130.0).now)    // program changes speed: the % stays
        assertEquals(listOf("M223 S162.5"), GrblishDialect.spindleOverride(162.5))
        assertEquals(listOf("M223 S100"), GrblishDialect.spindleOverride(100.0))
        assertEquals(listOf("M223 S121.25"), GrblishDialect.spindleOverride(121.25))
        assertEquals(listOf("M223 S108.3"), GrblishDialect.spindleOverride(108.30))
    }
}
