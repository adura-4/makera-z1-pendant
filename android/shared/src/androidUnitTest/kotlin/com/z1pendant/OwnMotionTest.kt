package com.z1pendant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The controls drawer opens by itself for motion the pendant didn't start, not for its own. */
class OwnMotionTest {
    private val own = OwnMotion()
    private fun at(t: Long, state: String, hotkey: Boolean = false, jogAt: Long = 0L, connected: Boolean = true) =
        own.isOwn(state, connected, t, hotkey, jogAt)

    @Test fun typedMoveIsOwnUntilItEnds() {
        own.sent(1000)
        assertTrue("still Idle just after sending", at(1100, "Idle"))
        assertTrue(at(1300, "Run"))
        assertTrue("a long move, well past 1.5 s", at(5000, "Run"))
        assertFalse("done", at(5100, "Idle"))
        assertFalse("then a job from Studio is not the pendant's", at(9000, "Run"))
    }

    @Test fun jogStaysOwnWhileTheMachineFinishes() {
        assertTrue(at(1000, "Idle", jogAt = 990))
        assertTrue(at(1300, "Run", jogAt = 990))
        assertTrue("10 mm step still running 3 s after the click", at(4000, "Run", jogAt = 990))
        assertFalse(at(4100, "Idle", jogAt = 990))
        assertFalse(at(6000, "Run", jogAt = 990))
    }

    @Test fun hotkeyMotionIsOwnAfterTheHotkeyIsAcked() {
        assertTrue(at(1000, "Home", hotkey = true))
        assertTrue("acked, still homing", at(2000, "Home"))
        assertFalse(at(9000, "Idle"))
    }

    @Test fun aMoveThatNeverStartsExpires() {
        own.sent(1000)
        assertTrue(at(1500, "Idle"))
        assertFalse(at(1000 + OwnMotion.START_GRACE_MS + 1, "Idle"))
        assertFalse(at(4000, "Run"))
    }

    @Test fun nothingSentIsNeverOwn() {
        assertFalse(at(1000, "Run"))
    }

    @Test fun disconnectForgetsIt() {
        own.sent(1000)
        assertTrue(at(1300, "Run"))
        assertFalse(at(1400, "Run", connected = false))
        assertFalse("after reconnecting, motion is not assumed to be ours", at(2000, "Run"))
    }
}
