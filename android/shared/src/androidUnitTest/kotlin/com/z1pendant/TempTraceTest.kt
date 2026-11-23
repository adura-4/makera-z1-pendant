package com.z1pendant

import com.z1pendant.link.StatusParser
import com.z1pendant.temp.TempLimits
import com.z1pendant.temp.TempTrace
import com.z1pendant.temp.TempWarn
import com.z1pendant.temp.boxTemp
import com.z1pendant.temp.spindleTemp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TempTraceTest {
    /** A status like the Z1's (2026-11-22): `|S:` rpm, target, override, vacuum, spindle °C, box °C, … */
    private fun st(spindle: Double, box: Double, tool: Int = 8, rpm: Double = 12000.0, state: String = "Run") =
        StatusParser.parse("<$state|MPos:-1.0000,-1.0000,-1.0000,0.0000,0.0000|WPos:0,0,0,0,0|F:0.0,3000.0,100.0|" +
            "S:$rpm,12000.0,100.0,0,$spindle,$box,0,0,0,0|T:$tool,-0.167,-1|P:10,5,30>", 0)!!

    @Test fun readsTemperaturesFromS() {
        val s = StatusParser.parse("<Idle|MPos:-1.0000,-1.0000,-1.0000,0.0000,0.0000|WPos:164.6240,153.3340,33.5996,-90.0000,0.0000|" +
            "F:0.0,3000.0,100.0|S:0.0,10000.0,100.0,0,36.3,25.0,0,0,0,0|T:10,-0.167,-1>", 0)!!
        assertEquals(36.3, s.spindleTemp!!, 1e-9)
        assertEquals(25.0, s.boxTemp!!, 1e-9)
    }

    @Test fun oneSampleEveryTwoSeconds() {
        val t = TempTrace(); t.reset(0)
        for (ms in 0L..10_000L step 250) t.onStatus(ms, st(40.0 + ms / 1000.0, 25.0))
        val v = t.view()
        assertEquals(6, v.samples.size)                          // 0, 2, 4, 6, 8, 10 s
        assertEquals(10.0, v.samples.last().tS, 1e-9)
        assertEquals(50.0, v.samples.last().spindle!!, 1e-9)
    }

    @Test fun longJobsKeepTheirWholeShape() {
        val t = TempTrace(); t.reset(0)
        for (i in 0..5_000) t.onStatus(i * 2_000L, st(40.0, 25.0))
        val v = t.view()
        assert(v.samples.size <= TempTrace.MAX_SAMPLES) { v.samples.size }
        assertEquals(0.0, v.samples.first().tS, 1e-9)
        assertEquals(10_000.0, v.samples.last().tS, 1e-9)
    }

    @Test fun toolMarks() {
        val t = TempTrace(); t.reset(0)
        // The job opens with a tool change: spindle off, no mark yet.
        t.onStatus(1_000, st(36.0, 25.0, tool = 9999, rpm = 0.0, state = "Tool"))
        t.onToolChangeDone(50_000)
        t.onStatus(50_100, st(36.0, 25.0, tool = -1, rpm = 0.0))      // the status crossing Done ATC: no tool yet
        t.onStatus(50_400, st(36.0, 25.0, tool = 8, rpm = 0.0))
        t.onStatus(60_000, st(40.0, 25.0, tool = 8))
        t.onToolChangeDone(120_000)
        t.onStatus(120_300, st(50.0, 26.0, tool = 1))
        t.onToolChangeDone(200_000)                                     // same tool again (re-probe): no new mark
        t.onStatus(200_300, st(52.0, 26.0, tool = 1))
        assertEquals(listOf(50.0 to 8, 120.0 to 1), t.view().marks.map { it.tS to it.tool })
    }

    @Test fun firstMarkWhenTheSpindleStartsWithoutAToolChange() {
        val t = TempTrace(); t.reset(0)
        t.onStatus(1_000, st(36.0, 25.0, tool = 3, rpm = 0.0))
        t.onStatus(5_000, st(36.0, 25.0, tool = 3, rpm = 9000.0))
        assertEquals(listOf(5.0 to 3), t.view().marks.map { it.tS to it.tool })
    }

    @Test fun stoppedTraceAddsNothing() {
        val t = TempTrace(); t.reset(0)
        t.onStatus(0, st(40.0, 25.0)); t.stop()
        t.onStatus(5_000, st(45.0, 25.0))
        assertEquals(1, t.view().samples.size)
    }

    @Test fun ratePerMinute() {
        val t = TempTrace(); t.reset(0)
        for (s in 0..60 step 2) t.onStatus(s * 1000L, st(36.0 + s * 0.25, 25.0))     // 15 °C/min
        assertEquals(15.0, TempTrace.ratePerMin(t.view())!!, 1e-6)
        val short = TempTrace(); short.reset(0); short.onStatus(0, st(40.0, 25.0)); short.onStatus(10_000, st(41.0, 25.0))
        assertNull(TempTrace.ratePerMin(short.view()))
    }

    @Test fun warnNearTheLimitWithHysteresis() {
        val lim = TempLimits(75.0, 60.0)
        assertNull(TempTrace.warn(69.9, 30.0, lim, null))
        assertEquals(TempWarn.SPINDLE, TempTrace.warn(70.0, 30.0, lim, null))
        assertEquals(TempWarn.SPINDLE, TempTrace.warn(69.2, 30.0, lim, TempWarn.SPINDLE))  // stays until 69
        assertNull(TempTrace.warn(68.9, 30.0, lim, TempWarn.SPINDLE))
        assertEquals(TempWarn.BOX, TempTrace.warn(50.0, 55.0, lim, null))
        assertEquals(TempWarn.SPINDLE, TempTrace.warn(72.0, 57.0, lim, null))            // the spindle first
    }
}
