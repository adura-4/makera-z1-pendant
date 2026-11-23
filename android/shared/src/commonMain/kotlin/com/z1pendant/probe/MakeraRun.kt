package com.z1pendant.probe

import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineLink
import com.z1pendant.platform.nowMs
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sends one of Makera's `M480.x` routines and follows the lines the machine echoes until `Done ATC`.
 * [onTouch] fires per `[PRB:]` touch and [onEcho] per echoed line; a throw from either ends the run.
 */
suspend fun runMakeraCommand(
    command: String,
    lines: ReceiveChannel<String>,
    link: MachineLink,
    onSend: () -> Unit,
    onTouch: (Touch) -> Unit,
    onEcho: (String) -> Unit = {},
): List<Touch> {
    val touches = mutableListOf<Touch>()
    onSend(); link.sendLine(command)
    val t0 = nowMs()
    var moved = false
    while (true) {
        val line = withTimeoutOrNull(250) { lines.receive() }
        val st = link.status.value
        if (link.conn.value != ConnState.Connected) throw ProbeStop("The link to the machine dropped.")
        if (st != null && !st.isIdle) moved = true
        if (line != null) {
            parsePrb(line)?.let { (t, ok) ->
                if (!ok) throw ProbeStop("The probe didn't touch anything.")
                touches += t; onTouch(t)
            }
            onEcho(line)
            if (line.startsWith("Done ATC")) break
            if (line.startsWith("Abort")) throw ProbeStop("Probing was aborted.")
            if (ProbeController.isStop(line)) throw ProbeStop(ProbeController.friendly(line))
        }
        if (st?.isAlarm == true) throw ProbeStop("The machine stopped (Alarm).")
        // Makera's routine silently does nothing when the machine isn't homed.
        if (!moved && touches.isEmpty() && nowMs() - t0 > 4000)
            throw ProbeStop("Nothing happened. Makera's routine needs the machine homed.")
    }
    return touches
}
