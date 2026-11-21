package com.z1pendant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI

class JobTimeTest {
    @Test fun aStraightMove() {
        // 100 mm at 600 mm/min: 10 s at full speed, 10.067 s from rest to rest at 150 mm/s²; midpoint.
        assertEquals(10.033, JobTimeEstimator.moveTime(100.0, 600.0), 0.001)
        val t = JobTimeEstimator.estimate("G21 G90\nG1 X100 F600\nG1 X0\n")
        assertEquals(20.07, t.total, 0.01)
        assertEquals(10.03, t.at(2), 0.01)               // after line 2
    }

    @Test fun arcsInEachPlane() {
        val q = PI / 2 * 10
        // G17 quarter circles, both directions, I/J and R forms
        assertEquals(q, JobTimeEstimator.arcLength(doubleArrayOf(10.0, 0.0, 0.0), doubleArrayOf(0.0, -10.0, 0.0), 17, true, -10.0, 0.0, null, null), 1e-6)
        assertEquals(q, JobTimeEstimator.arcLength(doubleArrayOf(10.0, 0.0, 0.0), doubleArrayOf(0.0, 10.0, 0.0), 17, false, -10.0, 0.0, null, null), 1e-6)
        assertEquals(q, JobTimeEstimator.arcLength(doubleArrayOf(10.0, 0.0, 0.0), doubleArrayOf(0.0, 10.0, 0.0), 17, false, null, null, null, 10.0), 1e-6)
        assertEquals(3 * q, JobTimeEstimator.arcLength(doubleArrayOf(10.0, 0.0, 0.0), doubleArrayOf(0.0, 10.0, 0.0), 17, true, -10.0, 0.0, null, null), 1e-6)
        // Jimmy's Fusion lead-in (G18 G3 X13.821 Z-0.2 I-0.318 K0 from X14.139 Z0.117): a quarter
        // circle, not three quarters (G18's CW is seen from +Y).
        val lead = JobTimeEstimator.arcLength(doubleArrayOf(14.139, -21.364, 0.117), doubleArrayOf(13.821, -21.364, -0.2), 18, false, -0.318, null, 0.0, null)
        assertEquals(PI / 2 * 0.318, lead, 1e-3)
    }

    @Test fun feedOverrideScalesOnlyFeedMoves() {
        val t = JobTimeEstimator.estimate("G0 Z10\nG1 X100 F600\n")
        val rapid = t.at(1); val feed = t.total - rapid
        assertEquals(rapid + feed * 2, t.leftAfter(0, 50.0), 1e-6)
        assertEquals(feed, t.leftAfter(1, 100.0), 1e-6)
        assertEquals(0.0, t.leftAfter(2, 100.0), 1e-6)
    }

    @Test fun toolChangesCountedLikeTheFirmware() {
        val t = JobTimeEstimator.estimate("T1M6\nG1 X1 F100\nM6 T1\nT2 M6\nM6T1\n")
        assertEquals(3, t.toolChanges)                    // T1, T2, T1 (the second T1 is skipped)
    }

    @Test fun paceFollowsTheMachine() {
        val text = (1..200).joinToString("\n") { "G1 X${if (it % 2 == 0) 0 else 50} F600" }   // 5 s per line
        val t = JobTimeEstimator.estimate("G21\n$text\n")
        val p = JobPace()
        // The machine runs 20 % slower than the estimate.
        var elapsed = 0.0
        for (line in 1L..60L) { elapsed += (t.at(line) - t.at(line - 1)) * 1.2; p.update(line, elapsed.toLong(), t, 100.0) }
        assertEquals(1.2, p.value, 0.02)
        // A feed override is already in the estimate it compares against: at 50 % and twice as long, the pace stays.
        for (line in 61L..120L) { elapsed += (t.at(line) - t.at(line - 1)) * 1.2 * 2; p.update(line, elapsed.toLong(), t, 50.0) }
        assertEquals(1.2, p.value, 0.03)
        p.reset(); assertEquals(1.0, p.value, 0.0)
    }

    @Test fun approximateDurations() {
        assertEquals("1 min", approxDuration(20.0))
        assertEquals("13 min", approxDuration(754.0))
        assertEquals("1 h 05 min", approxDuration(3900.0))
    }

    @Test fun drawerDurationsDontLookLikeClockTimes() {
        assertEquals("12m 34s", com.z1pendant.ui.fmtDuration(754))
        assertEquals("1h 02m", com.z1pendant.ui.fmtDuration(3_725))
        assertEquals("0m 42s", com.z1pendant.ui.fmtDuration(42))
        assertEquals("59m 59s", com.z1pendant.ui.fmtDuration(3_599))
        assertEquals("1h 00m", com.z1pendant.ui.fmtDuration(3_600))
        assertEquals("1m 00s", com.z1pendant.ui.fmtDuration(60))
    }

    /** Jimmy's air-cut job, 2026-11-18: pass 1 took about 1:20, pass 2 about 11:46 (relay log). Skipped if the file isn't here. */
    @Test fun tonightsJob() {
        val f = File("E:/Python Projects/Makera Z1 Upgrades/field_test/jobs/AIR_pendant_block_air_test_Setup1.cnc")
        if (!f.exists()) return
        val text = f.readText()
        val tools = JobFileParser.parse(text)
        val t = tools.time!!
        val (c1, c2) = tools.changes.map { it.line }
        val pass1 = t.at(c2) - t.at(c1)
        val pass2 = t.total - t.at(c2)
        assertTrue("pass 1 $pass1", pass1 in 72.0..88.0)
        assertTrue("pass 2 $pass2", pass2 in 670.0..745.0)
        assertEquals(2, t.toolChanges)
        // The Run page's per-tool times add up to the total and match the two passes.
        val per = tools.toolTimes()
        assertEquals(listOf(1, 2), per.map { it.first.tool })
        assertEquals(pass1, per[0].second!!, 1e-6)
        assertEquals(pass2, per[1].second!!, 1e-6)
        assertEquals(t.total, per.sumOf { it.second!! }, 1e-6)
    }
}
