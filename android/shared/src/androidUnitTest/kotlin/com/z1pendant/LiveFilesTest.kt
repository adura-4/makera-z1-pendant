package com.z1pendant

import android.os.SystemClock
import com.z1pendant.files.JobInfo
import com.z1pendant.files.RemoteFiles
import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineTarget
import com.z1pendant.link.makera.MakeraLink
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The Files operations on a real Z1 (opt-in: set Z1_LIVE_HOST, e.g. 192.168.1.48; the machine must be idle).
 * Works only inside a throwaway folder /sd/gcodes/_pendant_live_test, which it removes. Optional
 * Z1_LIVE_INFO_FILE = a file to read Job info from (e.g. /sd/gcodes/can_lid_op_1_Setup4.cnc).
 */
class LiveFilesTest {
    private val host = System.getenv("Z1_LIVE_HOST")
    private lateinit var link: MakeraLink
    private val dir = "/sd/gcodes/_pendant_live_test"

    @Before fun start() {
        assumeTrue("set Z1_LIVE_HOST to run", !host.isNullOrBlank())
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000 }
        link = MakeraLink()
        link.connect(MachineTarget("z1", host!!, 2222))
        runBlocking { withTimeout(10_000) { while (link.conn.value != ConnState.Connected) delay(50) } }
        assumeTrue("machine not idle", link.status.value?.isIdle == true)
    }

    @After fun stop() {
        if (::link.isInitialized) runBlocking { link.delete(dir) }.also { link.disconnect() }
        if (!host.isNullOrBlank()) unmockkStatic(SystemClock::class)
    }

    @Test fun roundTrip() = runBlocking {
        val root = link.listDir(RemoteFiles.ROOT)
        assertNotNull(root)
        println("root: ${root!!.size} entries, e.g. ${root.take(4)}")
        assertTrue(root.none { it.name.startsWith(".") })

        assertNull(link.makeDir(dir))
        val data = "(live test)\nG21\n".repeat(600).encodeToByteArray()      // ~10 KB, three packets
        val t0 = System.nanoTime()
        assertNull(link.uploadFile("$dir/a b.cnc", data))
        println("upload ${data.size} bytes: ${(System.nanoTime() - t0) / 1_000_000} ms")
        val listed = link.listDir(dir)!!
        assertEquals(listOf("a b.cnc"), listed.map { it.name })
        assertEquals(data.size.toLong(), listed.single().size)
        println("listed: $listed (UTC stamp ${listed.single().modified})")

        assertNull(link.rename("$dir/a b.cnc", "$dir/b.cnc"))
        assertEquals(listOf("b.cnc"), link.listDir(dir)!!.map { it.name })
        assertEquals(data.decodeToString(), link.downloadFile("$dir/b.cnc"))
        assertNotNull(link.delete("$dir/nothere.cnc"))

        System.getenv("Z1_LIVE_INFO_FILE")?.let { f ->
            val t1 = System.nanoTime()
            val head = link.readFileHead(f, 65536) { JobInfo.headerComplete(it) }
            assertNotNull(head)
            println("job info header: ${head!!.length} chars in ${(System.nanoTime() - t1) / 1_000_000} ms -> ${JobInfo.parse(head)}")
        }
        assertNull(link.delete(dir))
        assertTrue(link.listDir(RemoteFiles.ROOT)!!.none { it.name == "_pendant_live_test" })
    }
}
