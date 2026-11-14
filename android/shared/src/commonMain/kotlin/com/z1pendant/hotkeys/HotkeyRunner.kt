package com.z1pendant.hotkeys

import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** What the runner needs from the rest of the app for built-in steps. */
interface BuiltinHost {
    fun runBuiltin(b: BuiltIn): List<String>?    // G-code to send, or null if handled locally
    fun beforeGcode()                              // e.g. jog engine restores G90 first
    fun setJogEnabled(on: Boolean)
}

class HotkeyRunner(
    private val link: MachineLink,
    private val scope: CoroutineScope,
    private val host: BuiltinHost,
    private val lookup: (String) -> Hotkey?,
) {
    data class Running(val label: String, val stepText: String)

    val running = MutableStateFlow<Running?>(null)
    val lastError = MutableStateFlow<String?>(null)
    private var job: Job? = null

    /** Why [hk] can't run right now, or null if it can. Guards are checked here; "ask first" is the UI's job. */
    fun blockReason(hk: Hotkey): String? {
        val needsMachine = hk.steps.any { it !is Step.Builtin || it.action !in localOnly }
        if (needsMachine && link.conn.value != ConnState.Connected) return "Not connected"
        val instantOnly = hk.steps.size == 1 && hk.steps[0].let { it is Step.Builtin && it.action in instant }
        if (!instantOnly && running.value != null) return "“${running.value!!.label}” is still running"
        if (hk.onlyIdle) {
            val st = link.status.value ?: return "No machine status"
            if (!st.isIdle) return "Machine is ${st.state}, not Idle"
        }
        return null
    }

    fun run(hk: Hotkey) {
        blockReason(hk)?.let { lastError.value = "${hk.label}: $it"; return }
        // A single local step (axis/step/feed/hold) runs instantly, no "running" banner.
        if (hk.steps.size == 1 && hk.steps[0].let { it is Step.Builtin && it.action in instant }) {
            val b = (hk.steps[0] as Step.Builtin).action
            host.runBuiltin(b)?.forEach { link.sendLine(it) }
            return
        }
        job = scope.launch {
            running.value = Running(hk.label, "")
            host.setJogEnabled(false)
            try {
                runSteps(hk, depth = 0)
            } catch (e: kotlinx.coroutines.CancellationException) {
                lastError.value = "${hk.label}: cancelled"
            } catch (e: Exception) {
                lastError.value = "${hk.label}: ${e.message}"
            } finally {
                host.setJogEnabled(true)
                running.value = null
            }
        }
    }

    /** Stop sending the rest of the sequence and pause motion (feed hold). */
    fun cancel() {
        if (job?.isActive == true) { job?.cancel(); link.feedHold() }
    }

    private suspend fun runSteps(hk: Hotkey, depth: Int) {
        if (depth > 6) error("sequence nests too deep")
        for (s in hk.steps) {
            running.value = Running(hk.label, s.describe { lookup(it)?.label })
            when (s) {
                is Step.Builtin -> host.runBuiltin(s.action)?.let { sendAll(it) }
                is Step.GCode -> {
                    val lines = s.text.lines().map { it.substringBefore(';').trim() }.filter { it.isNotEmpty() && !it.startsWith("(") }
                    host.beforeGcode()
                    sendAll(lines)
                }
                Step.WaitIdle -> waitIdle()
                is Step.Delay -> delay(s.ms)
                is Step.RunHotkey -> runSteps(lookup(s.id) ?: error("hotkey missing"), depth + 1)
            }
        }
    }

    private suspend fun sendAll(lines: List<String>) {
        for (l in lines) {
            val done = CompletableDeferred<Pair<Boolean, String>>()
            link.sendLine(l, onAck = { ok, reply -> done.complete(ok to reply) })
            val (ok, reply) = withTimeout(120_000) { done.await() }
            if (!ok) error("“$l” → ${reply.ifEmpty { "failed" }}")
        }
    }

    private suspend fun waitIdle() {
        while (link.pendingLines > 0) delay(20)
        delay(300)                                    // let a fresh status show the move started
        withTimeout(30 * 60_000L) {
            while (true) {
                val st = link.status.value
                if (link.conn.value != ConnState.Connected) error("link lost")
                if (st != null && st.isAlarm) error("machine in Alarm")
                if (st != null && st.isIdle) break
                delay(50)
            }
        }
    }

    companion object {
        val instant = setOf(BuiltIn.HOLD, BuiltIn.RESUME, BuiltIn.RESET, BuiltIn.JOG_PLUS, BuiltIn.JOG_MINUS,
            BuiltIn.NEXT_AXIS, BuiltIn.AXIS_X, BuiltIn.AXIS_Y, BuiltIn.AXIS_Z, BuiltIn.AXIS_A,
            BuiltIn.STEP_UP, BuiltIn.STEP_DOWN, BuiltIn.FEED_UP, BuiltIn.FEED_DOWN, BuiltIn.FEED_100,
            BuiltIn.LIGHT_TOGGLE, BuiltIn.VACUUM_TOGGLE)
        val localOnly = setOf(BuiltIn.NEXT_AXIS, BuiltIn.AXIS_X, BuiltIn.AXIS_Y, BuiltIn.AXIS_Z, BuiltIn.AXIS_A,
            BuiltIn.STEP_UP, BuiltIn.STEP_DOWN)
    }
}
