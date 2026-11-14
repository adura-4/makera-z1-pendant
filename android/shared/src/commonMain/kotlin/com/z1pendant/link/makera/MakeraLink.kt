package com.z1pendant.link.makera

import com.z1pendant.link.ConnState
import com.z1pendant.link.Dialect
import com.z1pendant.link.GrblishDialect
import com.z1pendant.link.MachineLink
import com.z1pendant.link.MachineStatus
import com.z1pendant.link.MachineTarget
import com.z1pendant.link.StatusParser
import com.z1pendant.platform.PLog
import com.z1pendant.platform.nowMs
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.isClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Stock Makera Z1 over Wi-Fi: the ESP32 bridges TCP 2222 straight to the LPC's UART.
 *
 * Flow control: lines go out one at a time (window 2) and each waits for its "ok"/"error" reply.
 * If the machine never acks (unverified on stock firmware), the link drops to timed spacing so
 * nothing jams. A status query goes out every [pollMs]; no reply for [lossMs] = link lost, and
 * the link reconnects by itself.
 *
 * Sockets are ktor-network (the same code on Android and iOS).
 */
class MakeraLink(private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) : MachineLink {
    override val conn = MutableStateFlow(ConnState.Disconnected)
    override val status = MutableStateFlow<MachineStatus?>(null)
    override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val sent = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val latencyMs = MutableStateFlow<Int?>(null)
    override val outputs = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    @Volatile private var lastDiagAt = 0L
    override val dialect: Dialect = GrblishDialect
    val firmware = MutableStateFlow<String?>(null)

    private class Pending(val line: String, val quiet: Boolean, val onAck: ((Boolean, String) -> Unit)?, var sentAt: Long = 0)

    private val lock = SynchronizedObject()
    /** One frame at a time on the socket. */
    private val writeLock = Mutex()
    private val selector by lazy { SelectorManager(Dispatchers.IO) }
    private val queue = ArrayDeque<Pending>()
    private val inFlight = ArrayDeque<Pending>()
    @Volatile private var out: ByteWriteChannel? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var target: MachineTarget? = null
    @Volatile private var pollMs = 200L
    @Volatile private var queryAt = 0L
    @Volatile private var lastStatusAt = 0L
    private var job: Job? = null
    /** null = not known yet, true = machine replies "ok" per line, false = it doesn't. */
    @Volatile var acksSeen: Boolean? = null; private set

    private val window = 2
    private val ackTimeoutMs = 4000L
    private val lossMs = 1500L

    override val pendingLines: Int get() = synchronized(lock) { queue.size + inFlight.size }

    override fun setPollIntervalMs(ms: Long) { pollMs = ms.coerceIn(40, 2000) }

    /**
     * The "diagnose" line is answered with a 0x82 frame and no "ok" (the firmware intercepts it
     * before the command queue), so it's sent outside the line queue. Confirmed on the Z1:
     * `{S:…|L:…|V:…|F:…|G:light,…|T:…|C:…|E:…|P:…|I:…|RSSI:…}`; G[0] follows M821/M822.
     */
    override fun refreshOutputs() {
        if (conn.value != ConnState.Connected) return
        lastDiagAt = nowMs()
        scope.launch { sendRaw(MakeraFrame.line("diagnose")) }
    }

    override fun assumeOutput(name: String, on: Boolean) { outputs.value = outputs.value + (name to on) }

    private fun parseDiag(text: String) {
        val body = text.substringAfter('{').substringBefore('}')
        val f = body.split('|').associate { it.substringBefore(':') to it.substringAfter(':').split(',') }
        val m = outputs.value.toMutableMap()
        // G = light, beep, extendin, extendout state, extendout pwm (community SimpleShell::diagnose_command)
        f["G"]?.getOrNull(0)?.trim()?.let { m["light"] = it == "1" }
        f["G"]?.getOrNull(3)?.trim()?.let { m["vacuum"] = it == "1" }
        outputs.value = m
    }

    override fun connect(target: MachineTarget) {
        disconnect()
        firmware.value = null              // belongs to the previous machine
        this.target = target
        job = scope.launch { runLoop(target) }
    }

    override fun disconnect() {
        job?.cancel(); job = null
        closeSocket()
        target = null
        failAll("disconnected")
        conn.value = ConnState.Disconnected
        status.value = null
        latencyMs.value = null
    }

    private suspend fun runLoop(t: MachineTarget) {
        var first = true
        while (scope.isActive && target == t) {
            conn.value = if (first) ConnState.Connecting else ConnState.Lost
            first = false
            try {
                val s = withTimeout(3000) { aSocket(selector).tcp().connect(t.host, t.port) { noDelay = true } }
                socket = s; out = s.openWriteChannel(autoFlush = false)
                lastStatusAt = nowMs()
                queryAt = 0
                val reader = scope.launch { readLoop(s) }
                sendRaw(MakeraFrame.rt('?'.code)); queryAt = nowMs()
                // Wait for the first status before calling it connected.
                val t0 = nowMs()
                while (status.value == null || status.value!!.atMs < t0) {
                    if (nowMs() - t0 > 2500) throw LinkException("no status reply")
                    delay(20)
                }
                outputs.value = emptyMap()           // light state etc. belong to the machine we just reached
                conn.value = ConnState.Connected
                pumpLoop()
                reader.cancel()
            } catch (e: Exception) {
                PLog.w(TAG, "link: ${e.message}")
            }
            // Disconnected (this job cancelled): disconnect() cleans up and sets the state.
            currentCoroutineContext().ensureActive()
            closeSocket()
            failAll("link lost")
            if (target != t) break
            conn.value = ConnState.Lost
            latencyMs.value = null
            delay(1500)
        }
    }

    /** Runs while connected: status polls, line sending, ack timeouts, loss detection. */
    private suspend fun pumpLoop() {
        while (scope.isActive && socket?.isClosed == false) {
            val now = nowMs()
            // Output states every 3 s, but not while jogging (fast status polls mean a jog is on).
            if (now - lastDiagAt > 3000 && pollMs >= 200) refreshOutputs()
            if (now - lastStatusAt > lossMs) throw LinkException("no status for ${now - lastStatusAt} ms")
            if (queryAt == 0L && now - lastStatusAt >= pollMs || queryAt != 0L && now - queryAt > 600) {
                queryAt = now
                sendRaw(MakeraFrame.rt('?'.code))
            }
            var toSend: Pending? = null
            synchronized(lock) {
                // Ack timeouts: release the slot so a missing "ok" never jams the pendant.
                // Confirmed on the Z1 (fw 1.1.3.0.1.13): G-code / $ lines get "ok"; a timeout here
                // means something is wrong, so it's logged but never changes the flow-control mode.
                while (inFlight.isNotEmpty() && now - inFlight.first().sentAt > ackTimeoutMs) {
                    val p = inFlight.removeFirst()
                    PLog.w(TAG, "no ok for '${p.line}' in $ackTimeoutMs ms")
                    p.onAck?.invoke(true, "")
                }
                if (queue.isNotEmpty() && inFlight.size < window) {
                    val p = queue.removeFirst()
                    p.sentAt = now
                    // Shell commands (version, ls, …) reply with text and no "ok": don't wait for one,
                    // or the next line's "ok" would be matched to the wrong command.
                    if (expectsOk(p.line)) inFlight.addLast(p) else p.onAck?.invoke(true, "")
                    toSend = p
                }
            }
            toSend?.let {
                PLog.d(TAG, "-> ${it.line}")
                sendRaw(MakeraFrame.line(it.line))
                if (!it.quiet) sent.tryEmit(it.line)
            }
            delay(if (toSend != null) 1 else 5)
        }
    }

    private suspend fun readLoop(s: Socket) {
        val dec = MakeraFrame.Decoder()
        val buf = ByteArray(4096)
        try {
            val ins = s.openReadChannel()         // throws if the socket was already replaced/closed
            while (true) {
                val n = ins.readAvailable(buf, 0, buf.size)
                if (n < 0) break
                dec.feed(buf, n) { type, payload -> onPacket(type, payload.decodeToString()) }
            }
        } catch (_: Exception) {
        }
        if (socket === s) closeSocket()
    }

    private fun onPacket(type: Int, text: String) {
        val now = nowMs()
        when (type) {
            MakeraFrame.STATUS_RES -> {
                lastStatusAt = now
                if (queryAt != 0L) { latencyMs.value = (now - queryAt).toInt(); queryAt = 0 }
                StatusParser.parse(text, now)?.let { status.value = it }
            }
            MakeraFrame.FIRM_VER -> firmware.value = text.trim()
            MakeraFrame.DIAG_RES -> parseDiag(text)
            else -> {
                for (raw in text.split('\n')) {
                    val line = raw.trim()
                    if (line.isEmpty()) continue
                    val isOk = line.startsWith("ok", true)
                    val isErr = line.startsWith("error", true)
                    if (isOk || isErr) {
                        acksSeen = true
                        val p = synchronized(lock) { inFlight.removeFirstOrNull() }
                        p?.onAck?.invoke(isOk, line)
                        if (isOk && p?.quiet == true) continue      // a jog move's "ok"
                    }
                    messages.tryEmit(line)
                }
            }
        }
    }

    override fun sendLine(line: String, quiet: Boolean, onAck: ((ok: Boolean, reply: String) -> Unit)?) {
        if (conn.value != ConnState.Connected) {
            if (!quiet) messages.tryEmit("not sent (not connected): $line")
            onAck?.invoke(false, "not connected"); return
        }
        synchronized(lock) { queue.addLast(Pending(line, quiet, onAck)) }
    }

    override fun feedHold() { rt('!'.code); sent.tryEmit("! (feed hold)") }
    override fun resume() { rt('~'.code); sent.tryEmit("~ (resume)") }
    override fun reset() {
        synchronized(lock) { queue.clear() }
        rt(0x18); sent.tryEmit("Ctrl-X (reset)")
    }

    private fun rt(c: Int) { scope.launch { sendRaw(MakeraFrame.rt(c)) } }

    private suspend fun sendRaw(frame: ByteArray) {
        try {
            writeLock.withLock { out?.let { o -> o.writeFully(frame); o.flush() } }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            PLog.w(TAG, "send: ${e.message}")
            closeSocket()
        }
    }

    private fun failAll(why: String) {
        val dropped = synchronized(lock) { (inFlight + queue).also { inFlight.clear(); queue.clear() } }
        dropped.forEach { it.onAck?.invoke(false, why) }
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; out = null
    }

    private class LinkException(msg: String) : Exception(msg)

    companion object {
        const val TAG = "MakeraLink"
        private val GCODE = Regex("""^\s*(\$|[GMTFSXYZABN]\s*[-+.\d])""", RegexOption.IGNORE_CASE)
        fun expectsOk(line: String) = GCODE.containsMatchIn(line)
    }
}
