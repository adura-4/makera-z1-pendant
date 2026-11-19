package com.z1pendant

import android.os.SystemClock
import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineTarget
import com.z1pendant.link.makera.MakeraFrame
import com.z1pendant.link.makera.MakeraLink
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket

/**
 * Read-only check on a real machine: the link's file download, plain and right after a download left
 * running by a dropped connection. Runs only with Z1_LIVE_HOST=<ip> and Z1_LIVE_FILE=/sd/gcodes/<name>
 * set; nothing here moves the machine or writes to it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveDownloadTest {
    private val host: String? = System.getenv("Z1_LIVE_HOST")
    private val file: String = System.getenv("Z1_LIVE_FILE") ?: "/sd/gcodes/_upload_test_200k.cnc"

    @Before fun setUp() {
        assumeTrue("Z1_LIVE_HOST not set", host != null)
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000 }
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After fun tearDown() {
        if (host == null) return
        Dispatchers.resetMain()
        unmockkStatic(SystemClock::class)
    }

    private suspend fun timedDownload(link: MakeraLink, label: String): String {
        val t0 = System.nanoTime()
        val text = link.downloadFile(file)
        val secs = (System.nanoTime() - t0) / 1e9
        println("$label: ${text?.length} chars in ${"%.1f".format(secs)} s")
        assertNotNull(label, text)
        assertTrue("$label took $secs s", secs < 6.0)
        return text!!
    }

    @Test fun downloadPlainAndAfterLeftover() = runBlocking {
        val link = MakeraLink()
        link.connect(MachineTarget("z1", host!!, 2222))
        withTimeout(8000) { while (link.conn.value != ConnState.Connected) delay(20) }
        val a = timedDownload(link, "plain")
        val b = timedDownload(link, "again")
        assertTrue(a == b)
        link.disconnect()
        delay(500)
        // A download started by a connection that then drops: the board keeps it for the next
        // connection in that slot.
        Socket(host, 2222).use { s ->
            s.getOutputStream().write(MakeraFrame.build(0xB0, "download $file\n".encodeToByteArray()))
            s.getOutputStream().flush()
            Thread.sleep(400)
        }
        Thread.sleep(500)
        val link2 = MakeraLink()
        link2.connect(MachineTarget("z1", host, 2222))
        withTimeout(8000) { while (link2.conn.value != ConnState.Connected) delay(20) }
        assertTrue(timedDownload(link2, "after a dropped download") == a)
        assertTrue(timedDownload(link2, "and again") == a)
        link2.disconnect()
    }
}
