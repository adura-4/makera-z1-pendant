package com.z1pendant.link.makera

import android.content.Context
import android.net.wifi.WifiManager
import com.z1pendant.link.MachineTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** The machine's ESP32 broadcasts "name,ip,port,busy,..." to UDP 3333 about once a second. */
object Discovery {
    const val PORT = 3333

    suspend fun scan(ctx: Context, millis: Long = 3000, onFound: (MachineTarget, Boolean) -> Unit) =
        withContext(Dispatchers.IO) {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("z1pendant-discovery").apply { setReferenceCounted(false); acquire() }
            val sock = DatagramSocket(null).apply {
                reuseAddress = true; broadcast = true; soTimeout = 250
                bind(InetSocketAddress(PORT))
            }
            try {
                val buf = ByteArray(512)
                val end = System.currentTimeMillis() + millis
                val seen = HashSet<String>()
                while (System.currentTimeMillis() < end) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { sock.receive(pkt) } catch (_: SocketTimeoutException) { continue }
                    val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8).trim().trim('<', '>', '\u0000')
                    val p = text.split(',').map { it.trim() }
                    if (p.size < 2) continue
                    val host = p[1].ifEmpty { pkt.address.hostAddress ?: continue }
                    val port = p.getOrNull(2)?.toIntOrNull() ?: 2222
                    if (seen.add("$host:$port")) onFound(MachineTarget(p[0], host, port), p.getOrNull(3) == "1")
                }
            } finally {
                sock.close(); lock.release()
            }
        }
}
