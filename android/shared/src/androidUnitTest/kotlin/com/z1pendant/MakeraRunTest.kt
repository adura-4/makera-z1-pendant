package com.z1pendant

import android.os.SystemClock
import com.z1pendant.link.ConnState
import com.z1pendant.link.Dialect
import com.z1pendant.link.GrblishDialect
import com.z1pendant.link.MachineLink
import com.z1pendant.link.MachineStatus
import com.z1pendant.link.MachineTarget
import com.z1pendant.probe.ProbeStop
import com.z1pendant.probe.Touch
import com.z1pendant.probe.runMakeraCommand
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The extracted M480 echo loop against a fake link and a line channel fed by the test. */
class MakeraRunTest {
    private val link = RunFakeLink()
    private val lines = Channel<String>(Channel.UNLIMITED)
    private val touched = mutableListOf<Touch>()
    private var sends = 0
    /** Added to the clock, so a test can push the run past the 4 s unhomed window without waiting. */
    private var clockOffset = 0L

    @Before fun setUp() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000 + clockOffset }
    }

    @After fun tearDown() = unmockkStatic(SystemClock::class)

    private suspend fun run(onTouch: (Touch) -> Unit = { touched += it }) =
        runMakeraCommand("M480.1 X1", lines, link, { sends++ }, onTouch)

    private fun stopOf(block: suspend () -> Unit): ProbeStop = try {
        runBlocking { block() }; fail("expected ProbeStop"); error("unreachable")
    } catch (e: ProbeStop) { e }

    @Test fun collectsTouchesUntilDoneAtc() = runBlocking {
        lines.trySend("[PRB:1.000,2.000,3.000:1]"); lines.trySend("[PRB:4.000,5.000,6.000:1]"); lines.trySend("Done ATC")
        val got = run()
        assertEquals(listOf(Touch(1.0, 2.0, 3.0), Touch(4.0, 5.0, 6.0)), got)
        assertEquals(got, touched)
        assertEquals(listOf("M480.1 X1"), link.lines)
        assertEquals(1, sends)
    }

    @Test fun aMissedTouchStops() {
        lines.trySend("[PRB:1.000,2.000,3.000:0]")
        assertEquals("The probe didn't touch anything.", stopOf { run() }.message)
    }

    @Test fun abortStops() {
        lines.trySend("Abort")
        assertEquals("Probing was aborted.", stopOf { run() }.message)
    }

    @Test fun anErrorLineStopsFriendly() {
        lines.trySend("ALARM:Probe dead")
        assertTrue(stopOf { run() }.message!!.contains("setter"))
    }

    @Test fun nothingHappeningStopsAsUnhomed() {
        val e = stopOf { run() }
        assertTrue(e.message!!, e.message!!.contains("homed"))
    }

    // The guard is "nothing happened", not "it took a while": motion or a touch has to clear it.
    @Test fun aSlowRunThatDidMoveIsNotCalledUnhomed() = runBlocking {
        val pos = listOf(0.0, 0.0, 0.0, 0.0, 0.0)
        link.status.value = MachineStatus("Run", pos, pos, 100.0, 0.0, null, "<Run>", 0)
        launch { delay(300); clockOffset = 5000; delay(300); lines.send("Done ATC") }
        assertEquals(emptyList<Touch>(), run())
    }

    @Test fun aSlowRunThatTouchedIsNotCalledUnhomed() = runBlocking {
        lines.trySend("[PRB:1.000,2.000,3.000:1]")        // a touch arrived, the machine never reported Run
        launch { delay(300); clockOffset = 5000; delay(300); lines.send("Done ATC") }
        assertEquals(listOf(Touch(1.0, 2.0, 3.0)), run())
    }

    @Test fun onTouchCanAbortTheRun() {
        lines.trySend("[PRB:1.000,2.000,3.000:1]"); lines.trySend("Done ATC")
        val e = stopOf { run { throw ProbeStop("Out of range.") } }
        assertEquals("Out of range.", e.message)
    }

    @Test fun echoedLinesReachOnEcho() = runBlocking {
        lines.trySend("G91 G0 X5"); lines.trySend("Done ATC")
        val echoed = mutableListOf<String>()
        runMakeraCommand("M480.1", lines, link, {}, {}, { echoed += it })
        assertEquals(listOf("G91 G0 X5", "Done ATC"), echoed)
    }

    @Test fun aDroppedLinkStops() {
        link.conn.value = ConnState.Lost
        assertTrue(stopOf { run() }.message!!.contains("dropped"))
    }

    @Test fun anAlarmStatusStops() {
        val pos = listOf(0.0, 0.0, 0.0, 0.0, 0.0)
        link.status.value = MachineStatus("Alarm", pos, pos, 100.0, 0.0, null, "<Alarm>", 0)
        assertTrue(stopOf { run() }.message!!.contains("Alarm"))
    }
}

private class RunFakeLink : MachineLink {
    override val conn = MutableStateFlow(ConnState.Connected)
    override val status = MutableStateFlow<MachineStatus?>(null)
    override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val sent = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val latencyMs = MutableStateFlow<Int?>(30)
    override val outputs = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    override val dialect: Dialect = GrblishDialect
    override val pendingLines = 0
    val lines = mutableListOf<String>()

    override fun connect(target: MachineTarget) {}
    override fun disconnect() {}
    override fun sendLine(line: String, quiet: Boolean, onAck: ((ok: Boolean, reply: String) -> Unit)?) {
        lines += line; onAck?.invoke(true, "ok")
    }
    override fun feedHold() {}
    override fun resume() {}
    override fun reset() {}
    override fun setPollIntervalMs(ms: Long) {}
    override fun refreshOutputs() {}
    override fun assumeOutput(name: String, on: Boolean) {}
}
