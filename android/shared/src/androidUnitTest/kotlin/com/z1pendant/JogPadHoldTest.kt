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
import kotlin.math.abs
import kotlin.math.sign
import kotlin.random.Random

/**
 * Holding a jog-pad button (1 mm step, repeating every 120 ms) against a model machine that runs the
 * moves at their feed and refreshes the position it reports only every 250–370 ms, as the Z1 does,
 * with status replies arriving 10–120 ms late (seeded, so the run is deterministic).
 *
 * On a real Z1 any feed change from move to move is audible, and a feed above the rate the clicks
 * arrive at empties the queue: the machine stops for a moment and starts again. So while a button is
 * held, every move must have the same feed and the machine must never run out of moves.
 */
class JogPadHoldTest {
    private var clock = 0L
    private val machine = ModelMachine(x = -150.0)
    private val link = ModelLink(machine)
    private var drops = 0
    private val jog = JogEngine(link, CoroutineScope(Dispatchers.Unconfined), onDrop = { drops++ })

    @Before fun setUp() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { clock }
        jog.axis = 'X'
        jog.step = 1.0
    }

    @After fun tearDown() = unmockkStatic(SystemClock::class)

    /** Press (one click), hold 0.4 s, then repeat every [repeatMs] for [holdMs], as JogPad does. */
    private fun holdButton(repeatMs: Long, holdMs: Long, dir: Int = -1) {
        val rnd = Random(42)
        val replies = ArrayDeque<Pair<Long, MachineStatus>>()     // (arrives at, status)
        var lastArrival = 0L
        clock = 1000
        link.status.value = machine.status(clock)
        val pressAt = clock + 100
        val repeatFrom = pressAt + 400
        var nextClick = pressAt
        while (clock < repeatFrom + holdMs) {
            if (clock >= nextClick) {
                if (nextClick == repeatFrom) jog.holdRate = 1000.0 / repeatMs
                jog.click(dir)
                nextClick = if (nextClick == pressAt) repeatFrom else nextClick + repeatMs
            }
            if (clock % 50 == 0L) {                                   // status query, answered late
                lastArrival = maxOf(lastArrival, clock + rnd.nextLong(10, 121))
                replies.addLast(lastArrival to machine.status(clock))
            }
            while (replies.isNotEmpty() && replies.first().first <= clock) {
                val (arrives, st) = replies.removeFirst()
                link.status.value = st.copy(atMs = arrives)
            }
            if (clock % 40 == 0L) jog.tick()
            machine.advance(5, clock, counting = clock >= repeatFrom + 300)
            clock += 5
        }
    }

    @Test fun heldButtonSendsOneConstantFeed() {
        holdButton(repeatMs = 120, holdMs = 3000)
        val held = link.feeds.drop(1)                               // the first is the tap before the repeat
        assertTrue("moves sent: ${held.size}", held.size >= 20)
        assertEquals("one feed while holding, got ${link.feeds}", 1, held.toSet().size)
        assertEquals("1 mm per 120 ms = 500 mm/min, a little slower", 485.0, held.first(), 0.5)
    }

    @Test fun heldButtonNeverRunsDry() {
        holdButton(repeatMs = 120, holdMs = 3000)
        assertEquals("ms the machine stood still while the button was held", 0L, machine.starvedMs)
        assertEquals("clicks dropped while holding", 0, drops)
        val travelled = -150.0 - machine.x                         // 1 + 3000/120 clicks of 1 mm, minus what is still queued
        assertTrue("travelled $travelled mm", travelled > 21.0 && travelled <= 26.0)
    }

    /** A long hold (+X, 15 s ≈ 120 mm) fills the queue to the cap; clicks dropped there must not stop the machine. */
    @Test fun longHoldStaysWithinTheCapAndKeepsMoving() {
        holdButton(repeatMs = 120, holdMs = 15_000, dir = 1)
        assertEquals(0L, machine.starvedMs)
        assertTrue("queued ${machine.queued} mm", machine.queued <= 5.0 + 1e-9)
    }
}

/** One axis that runs the queued moves at their feed (no acceleration). */
private class ModelMachine(var x: Double) {
    private val queue = ArrayDeque<Pair<Double, Double>>()        // target, mm/s
    private var commanded = x

    fun move(line: String) {
        val v = line.substringAfterLast('F').toDouble() / 60.0
        val n = Regex("""X(-?[\d.]+)""").find(line)!!.groupValues[1].toDouble()
        commanded = if (line.startsWith("G53")) n else commanded + n   // G53: machine position; G91: distance
        queue.addLast(commanded to v)
    }

    /** The Z1 refreshes the position it reports only every 250–370 ms (measured), not on every query. */
    private var reported = x
    private var nextRefresh = 0L
    private val rnd = Random(7)

    /** ms spent with nothing queued while [advance] was told to count. */
    var starvedMs = 0L
    /** Distance still queued (mm). */
    val queued: Double get() = abs(commanded - x)

    fun advance(ms: Long, now: Long, counting: Boolean = false) {
        if (now >= nextRefresh) { reported = x; nextRefresh = now + rnd.nextLong(250, 371) }
        if (counting && queue.isEmpty()) starvedMs += ms
        var dt = ms / 1000.0
        while (dt > 0 && queue.isNotEmpty()) {
            val (to, v) = queue.first()
            val d = to - x
            if (abs(d) <= v * dt) { x = to; dt -= abs(d) / v; queue.removeFirst() } else { x += sign(d) * v * dt; dt = 0.0 }
        }
    }

    fun status(at: Long): MachineStatus {
        val pos = listOf(reported, -70.0, -1.0, 0.0, 0.0)
        val state = if (queue.isEmpty()) "Idle" else "Run"
        return MachineStatus(state, pos, pos, 100.0, 0.0, null, "<$state|MPos:$reported>", at)
    }
}

/** Hands jog moves to the model machine, records their feeds, acks at once. */
private class ModelLink(private val machine: ModelMachine) : MachineLink {
    override val conn = MutableStateFlow(ConnState.Connected)
    override val status = MutableStateFlow<MachineStatus?>(null)
    override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val sent = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val latencyMs = MutableStateFlow<Int?>(60)
    override val outputs = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    override val dialect: Dialect = GrblishDialect
    override val pendingLines = 0
    val feeds = mutableListOf<Double>()

    override fun connect(target: MachineTarget) {}
    override fun disconnect() {}
    override fun sendLine(line: String, quiet: Boolean, onAck: ((ok: Boolean, reply: String) -> Unit)?) {
        if ('X' in line && 'F' in line) { machine.move(line); feeds += line.substringAfterLast('F').toDouble() }
        onAck?.invoke(true, "ok")
    }
    override fun feedHold() {}
    override fun resume() {}
    override fun reset() {}
    override fun setPollIntervalMs(ms: Long) {}
    override fun refreshOutputs() {}
    override fun assumeOutput(name: String, on: Boolean) {}
}
