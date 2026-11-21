package com.z1pendant

import com.z1pendant.probe.ProbeLength
import com.z1pendant.probe.ProbeTool
import com.z1pendant.run.RunController
import com.z1pendant.run.RunController.LaserPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Run flow: the job may only start once the setter reading says the laser probe is in. */
class LaserCheckTest {
    private val learned = mapOf(ProbeTool.PROBE_3D to -69.574, ProbeTool.ROD to -72.02, ProbeTool.LASER to -78.53)

    @Test fun matchingLengthGoesOn() {
        // 2026-11-18 on the Z1: the swap's slow setter touch read -78.546.
        val v = RunController.laserVerdict(ProbeLength.check(ProbeTool.LASER, -78.546, learned))
        assertEquals(LaserPhase.OK, v.phase)
        assertEquals(null, v.note)                         // a match just says checked, not how far off
    }

    @Test fun theThreeDProbeLeftInIsCaught() {
        val v = RunController.laserVerdict(ProbeLength.check(ProbeTool.LASER, -69.56, learned))
        assertEquals(LaserPhase.WRONG, v.phase)
        assertEquals(ProbeTool.PROBE_3D, v.other)
    }

    @Test fun anUnknownLengthStops() {
        assertEquals(LaserPhase.WRONG, RunController.laserVerdict(ProbeLength.check(ProbeTool.LASER, -60.0, learned)).phase)
    }

    @Test fun noReadingIsNotAPass() {
        // Before: "measured (length not read)" and the job started anyway.
        assertEquals(LaserPhase.FAILED, RunController.laserVerdict(null).phase)
    }

    @Test fun firstEverReadingIsLearned() {
        val c = ProbeLength.check(ProbeTool.LASER, -78.5, emptyMap()).copy(result = ProbeLength.Result.LEARNED)
        assertEquals(LaserPhase.OK, RunController.laserVerdict(c).phase)
    }
}
