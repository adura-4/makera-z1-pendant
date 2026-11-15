package com.z1pendant

import android.os.SystemClock
import com.z1pendant.jog.JogEngine
import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineTarget
import com.z1pendant.link.makera.MakeraLink
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * The ktor-network link against the machine simulator (python/z1sim.py), on the host JVM:
 * connect, status polling, a line with its "ok", disconnect. Skipped when Python isn't available.
 */
class MakeraLinkSimTest {
    private val port = 22229
    private var sim: Process? = null

    @Before fun startSim() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000 }
        val script = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "python/z1sim.py") }.firstOrNull { it.exists() }
        assumeTrue("z1sim.py not found", script != null)
        sim = try {
            ProcessBuilder("python", script!!.path, "--port", "$port", "--no-broadcast")
                .redirectErrorStream(true).redirectOutput(File(System.getProperty("java.io.tmpdir"), "z1sim_test.log")).start()
        } catch (_: Exception) { null }
        assumeTrue("python not runnable", sim != null)
        val up = (1..50).any { runCatching { Socket("127.0.0.1", port).close() }.isSuccess || run { Thread.sleep(100); false } }
        assumeTrue("simulator didn't start", up)
    }

    @After fun stopSim() {
        sim?.destroy(); sim?.waitFor(5, TimeUnit.SECONDS)
        unmockkStatic(SystemClock::class)
    }

    /**
     * Jog engine + link + sim end to end: ten 0.1 mm clicks move X by 1 mm, and the machine is not
     * left in relative mode: a plain absolute G0 afterwards lands where it says.
     */
    @Test fun jogLeavesNoRelativeModeBehind() = runBlocking {
        val link = MakeraLink()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val jog = JogEngine(link, scope)
        jog.axis = 'X'; jog.step = 0.1
        link.connect(MachineTarget("sim", "127.0.0.1", port))
        withTimeout(5000) { while (link.conn.value != ConnState.Connected) delay(20) }
        val x0 = link.status.value!!.mpos[0]
        jog.start()
        repeat(10) { jog.click(1); delay(30) }
        withTimeout(10000) {
            while (link.status.value?.let { it.isIdle && kotlin.math.abs(it.mpos[0] - (x0 + 1.0)) < 1e-6 } != true) delay(20)
        }
        delay(600)                                   // the engine's quiet time after a jog
        jog.stop()

        val ack = CompletableDeferred<Pair<Boolean, String>>()
        link.sendLine("G0 X-90") { ok, reply -> ack.complete(ok to reply) }
        assertTrue("ok for G0 X-90", withTimeout(3000) { ack.await() }.first)
        withTimeout(10000) { while (link.status.value?.let { it.isIdle && it.mpos[0] == -90.0 } != true) delay(20) }
        link.disconnect(); scope.cancel()
    }

    @Test fun connectPollSendDisconnect() = runBlocking {
        val link = MakeraLink()
        link.connect(MachineTarget("sim", "127.0.0.1", port))
        withTimeout(5000) { while (link.conn.value != ConnState.Connected) delay(20) }
        val st = link.status.value!!
        assertTrue(st.raw, st.mpos.size >= 4)
        val first = st.atMs
        withTimeout(3000) { while (link.status.value!!.atMs == first) delay(20) }        // polled again
        assertTrue("latency measured", link.latencyMs.value != null)

        val ack = CompletableDeferred<Pair<Boolean, String>>()
        link.sendLine("G91 G0 X-1") { ok, reply -> ack.complete(ok to reply) }
        val (ok, reply) = withTimeout(3000) { ack.await() }
        assertTrue("ok for the move: $reply", ok)
        link.sendLine("G90")
        withTimeout(3000) { while (link.pendingLines > 0) delay(10) }

        link.disconnect()
        assertEquals(ConnState.Disconnected, link.conn.value)
        delay(300)
        assertEquals("stays disconnected", ConnState.Disconnected, link.conn.value)
    }

    /** \$H ends at the soft max (-1), like the real Z1, so the first move after homing doesn't alarm. */
    @Test fun moveAfterHomingStaysInsideSoftLimits() = runBlocking {
        val link = MakeraLink()
        link.connect(MachineTarget("sim", "127.0.0.1", port))
        withTimeout(5000) { while (link.conn.value != ConnState.Connected) delay(20) }
        link.sendLine("\$H")
        withTimeout(15000) {
            while (link.status.value?.let { it.isIdle && it.mpos.take(3).all { m -> m == -1.0 } } != true) delay(50)
        }
        val ack = CompletableDeferred<Pair<Boolean, String>>()
        link.sendLine("G91 G0 X-1") { ok, reply -> ack.complete(ok to reply) }
        val (ok, reply) = withTimeout(3000) { ack.await() }
        assertTrue("ok for the first move after homing: $reply", ok)
        link.sendLine("G90")
        // The firmware (and the sim) waits 100 ms before running a queue that was empty: wait for the position.
        withTimeout(5000) { while (link.status.value?.let { it.isIdle && it.mpos[0] == -2.0 } != true) delay(20) }
        assertEquals(link.status.value!!.raw, "Idle", link.status.value!!.state)
        link.disconnect()
    }
}
