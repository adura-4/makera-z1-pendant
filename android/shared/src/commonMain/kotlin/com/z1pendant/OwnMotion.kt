package com.z1pendant

/**
 * Tells the pendant's own motion (jogs, typed moves, hotkeys) apart from motion something else
 * started (a job from Makera Studio), so the controls drawer opens by itself only for the latter.
 *
 * Motion counts as the pendant's own from the moment it sends a move until the machine has been
 * busy and is Idle again. The firmware acks a line when it is queued, not when the move ends, and
 * the machine may still report Idle for a few hundred ms after a move is sent, so an Idle reading
 * only ends it once the machine was seen busy, or [START_GRACE_MS] after the last send (the move
 * never started, e.g. it was already there).
 */
class OwnMotion {
    private var sentAt = -1L
    private var sawBusy = false
    private var seenJogAt = 0L

    /** The pendant just sent something that may move the machine. */
    fun sent(now: Long) { sentAt = now; sawBusy = false }

    /**
     * Whether the machine's current motion is the pendant's own, given its latest [state]. Call it
     * on every status. A running hotkey and a jog sent since the last call count as sends.
     */
    fun isOwn(state: String?, connected: Boolean, now: Long, hotkeyRunning: Boolean, lastJogAt: Long): Boolean {
        if (hotkeyRunning) sent(now)
        if (lastJogAt > seenJogAt) { seenJogAt = lastJogAt; sent(lastJogAt) }
        if (sentAt < 0) return false
        if (!connected) { sentAt = -1; return false }
        if (!state.equals("Idle", true)) sawBusy = true
        else if (sawBusy || now - sentAt > START_GRACE_MS) { sentAt = -1; return false }
        return true
    }

    companion object {
        const val START_GRACE_MS = 1500L
    }
}
