package com.z1pendant.link

import com.z1pendant.util.Fmt
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the UI, jog engine and hotkeys need from a machine. The Makera (stock firmware,
 * TCP 2222) backend implements it today; a LinuxCNC backend (CM4 host) can implement it later
 * without touching the rest of the app.
 */
interface MachineLink {
    val conn: StateFlow<ConnState>
    val status: StateFlow<MachineStatus?>
    /** Text the machine sends that isn't a status reply ("ok", errors, alarms, version). */
    val messages: SharedFlow<String>
    /** What went out, as it was sent: command lines and real-time commands (hold, resume, reset). */
    val sent: SharedFlow<String>
    /** Round-trip time of the last status query, ms. */
    val latencyMs: StateFlow<Int?>

    fun connect(target: MachineTarget)
    fun disconnect()

    /**
     * Queue one command line. Lines go out in order; [onAck] runs when the machine answers it.
     * [quiet] lines (the dial's jog moves, ~25 a second) and their "ok"s stay out of [sent]/[messages].
     */
    fun sendLine(line: String, quiet: Boolean = false, onAck: ((ok: Boolean, reply: String) -> Unit)? = null)
    /** Lines sent but not yet answered. */
    val pendingLines: Int

    fun feedHold()
    fun resume()
    /** Abort: stop motion now, flush queues. Machine needs unlock afterwards. */
    fun reset()

    /** Status poll rate; raised while jogging. */
    fun setPollIntervalMs(ms: Long)

    /** Machine outputs the backend can read back, e.g. "light" -> true. Missing key = unknown. */
    val outputs: StateFlow<Map<String, Boolean>>
    /** Ask the machine for fresh output states now (also refreshed periodically). */
    fun refreshOutputs()
    /** Show an output as [on] right away (after sending its command), until the next read-back. */
    fun assumeOutput(name: String, on: Boolean)

    /** Backend-specific commands, so built-ins don't hard-code one dialect. */
    val dialect: Dialect
}

data class MachineTarget(val name: String, val host: String, val port: Int)

enum class ConnState { Disconnected, Connecting, Connected, Lost }

data class MachineStatus(
    val state: String,                 // Idle, Run, Hold, Alarm, Home, Pause, Wait, Tool, Sleep
    val mpos: List<Double>,            // X Y Z A (B)
    val wpos: List<Double>,
    val feedOverride: Double?,         // %
    val spindleRpm: Double?,
    /** Homed state if the backend reports one; null on stock Makera firmware (it doesn't). */
    val homed: Boolean?,
    val raw: String,
    val atMs: Long,                    // nowMs() when received
) {
    val isIdle get() = state.equals("Idle", ignoreCase = true)
    val isAlarm get() = state.equals("Alarm", ignoreCase = true)
}

interface Dialect {
    fun home(): List<String>
    fun unlock(): List<String>
    fun zeroAxes(axes: String): List<String>       // e.g. "X", "XYZ"
    /** Make the current position read [values] in work coordinates (undo of a zero). */
    fun setWorkPosition(values: Map<Char, Double>): List<String>
    fun feedOverride(percent: Int): List<String>
    fun light(on: Boolean): List<String>
    fun vacuum(on: Boolean): List<String>
    /**
     * One jog move to machine position [machinePos] on [axis]. Must not change the machine's modal
     * state (no G91), so nothing is left behind if the link drops mid-jog.
     */
    fun jogTo(axis: Char, machinePos: Double, feedMmMin: Double): String
}

object GrblishDialect : Dialect {
    override fun home() = listOf("\$H")
    override fun unlock() = listOf("\$X")
    override fun zeroAxes(axes: String) = listOf("G10L20P0" + axes.map { "${it}0" }.joinToString(""))
    override fun setWorkPosition(values: Map<Char, Double>) =
        listOf("G10L20P0" + values.entries.joinToString("") { (a, v) -> a + fmt(v) })
    override fun feedOverride(percent: Int) = listOf("M220S$percent")
    // Z1 config: switch.light.input_on_command M821 / off M822 (confirmed on the machine 2026-11-13).
    override fun light(on: Boolean) = listOf(if (on) "M821" else "M822")
    // Z1 config: switch.extendout (P2.2, the vacuum / chip-clear output) on M851 / off M852.
    override fun vacuum(on: Boolean) = listOf(if (on) "M851" else "M852")
    // G53 = machine coordinates for this line only; G90/G91 and the work offset stay as they are.
    override fun jogTo(axis: Char, machinePos: Double, feedMmMin: Double) =
        "G53G1$axis${fmt(machinePos)}F${Fmt.fixed(feedMmMin, 0)}"

    fun fmt(v: Double) = Fmt.trimmed4(v)
}
