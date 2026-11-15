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

    @Test fun topPresetIsTheMax() = assertEquals(SPINDLE_MAX_RPM, SPINDLE_PRESETS.last())
}
