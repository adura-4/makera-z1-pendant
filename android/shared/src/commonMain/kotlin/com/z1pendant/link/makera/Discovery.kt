package com.z1pendant.link.makera

import com.z1pendant.link.MachineTarget
import com.z1pendant.platform.nowMs
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray

/** The machine's ESP32 broadcasts "name,ip,port,busy,..." to UDP 3333 about once a second. */
object Discovery {
    const val PORT = 3333

    /**
     * Listen for [millis] ms. [holdLock] is called first and its result (the release) at the end;
     * Android passes a Wi-Fi multicast lock there, without which some phones drop broadcasts.
     */
    suspend fun scan(millis: Long = 3000, holdLock: () -> (() -> Unit) = { {} }, onFound: (MachineTarget, Boolean) -> Unit) =
        withContext(Dispatchers.IO) {
            val release = holdLock()
            val selector = SelectorManager(Dispatchers.IO)
            try {
                val sock = aSocket(selector).udp().bind(InetSocketAddress("0.0.0.0", PORT)) {
                    reuseAddress = true; broadcast = true
                }
                try {
                    val end = nowMs() + millis
                    val seen = HashSet<String>()
                    while (nowMs() < end) {
                        val dg = withTimeoutOrNull(250) { sock.receive() } ?: continue
                        val bytes = dg.packet.readByteArray().let { if (it.size > 512) it.copyOf(512) else it }   // 512-byte buffer, as before
                        val text = bytes.decodeToString().trim().trim('<', '>', '\u0000')
                        val p = text.split(',').map { it.trim() }
                        if (p.size < 2) continue
                        val host = p[1].ifEmpty { (dg.address as? InetSocketAddress)?.hostname ?: continue }
                        val port = p.getOrNull(2)?.toIntOrNull() ?: 2222
                        if (seen.add("$host:$port")) onFound(MachineTarget(p[0], host, port), p.getOrNull(3) == "1")
                    }
                } finally {
                    sock.close()
                }
            } finally {
                selector.close(); release()
            }
        }
}
