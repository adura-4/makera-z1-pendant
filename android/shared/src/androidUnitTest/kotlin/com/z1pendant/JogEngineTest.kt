package com.z1pendant

import android.os.SystemClock
import com.z1pendant.jog.JogEngine
import com.z1pendant.link.ConnState
import com.z1pendant.link.Dialect
import com.z1pendant.link.GrblishDialect
import com.z1pendant.link.MachineLink
import com.z1pendant.link.MachineStatus
import com.z1pendant.link.MachineTarget
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The jog engine against a fake link: it sends absolute machine positions (G53) and never G91/G90,
 * starts a session only from a status that arrived after the first click, and stops when the
 * machine leaves the commanded path (something else moved it).
 */
class JogEngineTest {
    private var clock = 0L
    private val link = FakeLink()
    private var drops = 0
    private val jog = JogEngine(link, CoroutineScope(Dispatchers.Unconfined), onDrop = { drops++ })

    @Before fun setUp() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { clock }
        jog.axis = 'X'
        jog.step = 0.1
    }

    @After fun tearDown() = unmockkStatic(SystemClock::class)

    private fun status(state: String, x: Double, y: Double = -70.0, at: Long = clock): MachineStatus {
        val pos = listOf(x, y, -1.0, 0.0, 0.0)
        return MachineStatus(state, pos, pos, 100.0, 0.0, null, "<$state|MPos:$x,$y,-1>", at)
    }

    private fun at(t: Long) { clock = t }

    /** Starts a session at X -112.5 and sends its first move (one 0.1 click) to -112.4. */
    private fun startSession() {
        at(1000); link.status.value = status("Idle", -112.5)
        at(1100); jog.click(1)
        at(1120); link.status.value = status("Idle", -112.5)
        at(1140); jog.tick()
    }

    @Test fun sendsMachinePositionsAndNeverRelativeMode() {
        startSession()
        assertEquals(1, link.lines.size)
        assertTrue(link.lines[0], link.lines[0].startsWith("G53G1X-112.4F"))

        at(1180); jog.click(2)
        link.status.value = status("Run", -112.45, at = 1170)
        jog.tick()
        assertEquals(2, link.lines.size)
        assertTrue(link.lines[1], link.lines[1].startsWith("G53G1X-112.2F"))

        // Input stops and the machine settles: nothing more is sent (no G90 to restore).
        for (t in 1300L..2000L step 40) { at(t); link.status.value = status("Idle", -112.2); jog.tick() }
        assertEquals(2, link.lines.size)
        assertTrue(link.lines.toString(), link.lines.none { "G91" in it || "G90" in it })
    }

    @Test fun waitsForAStatusNewerThanTheFirstClick() {
        at(1000); link.status.value = status("Idle", -112.5)
        at(1100); jog.click(1)
        jog.tick()
        at(1500); jog.tick()                        // still only the status from before the click
        assertEquals("no move from a status older than the click", 0, link.lines.size)

        at(1520); link.status.value = status("Idle", -100.0)      // the machine is somewhere else now
        at(1540); jog.tick()
        assertEquals(listOf("G53G1X-99.9"), link.lines.map { it.substringBefore('F') })
    }

    @Test fun stopsWhenTheMachineLeavesTheJogPath() {
        startSession()
        at(1200); link.status.value = status("Run", -110.0)       // not between -112.5 and -112.4
        jog.click(1); jog.tick()
        assertEquals("no move after something else moved the machine", 1, link.lines.size)
        assertEquals(1, drops)

        // A new click starts over from a fresh reading of where the machine is.
        at(1300); link.status.value = status("Idle", -110.0)
        at(1400); jog.click(1)
        at(1420); link.status.value = status("Idle", -110.0)
        at(1440); jog.tick()
        assertEquals(listOf("G53G1X-112.4", "G53G1X-109.9"), link.lines.map { it.substringBefore('F') })
    }

    @Test fun stopsWhenAnotherAxisMoves() {
        startSession()
        at(1200); link.status.value = status("Run", -112.45, y = -60.0)
        jog.click(1); jog.tick()
        assertEquals(1, link.lines.size)
        assertEquals(1, drops)
    }

    @Test fun statusRoundingStaysOnThePath() {
        startSession()
        at(1200); link.status.value = status("Run", -112.39)     // 0.01 past the target: rounding, not a stranger
        jog.click(1); jog.tick()
        assertEquals(2, link.lines.size)
        assertEquals(0, drops)
    }

    @Test fun typedMovesNeverUseRelativeMode() {
        assertEquals(listOf("G53 G0 X-102.5"), AppViewModel.moveLines('X', 10.0, relative = true, endMachine = -102.5))
        assertEquals(listOf("G90 G0 Z5"), AppViewModel.moveLines('Z', 5.0, relative = false, endMachine = -40.0))
    }
}

/** A link that records the lines sent and acks them at once. */
private class FakeLink : MachineLink {
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
