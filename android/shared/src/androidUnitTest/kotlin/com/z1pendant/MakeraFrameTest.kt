package com.z1pendant

import com.z1pendant.link.makera.MakeraFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MakeraFrameTest {
    private fun hex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    @Test fun testVector() {
        assertArrayEquals(hex("86 68 00 03 71 3b e5 55 aa"), MakeraFrame.build(0x71))
    }

    @Test fun lineIsAsciiLikeJava() {
        for (s in listOf("G91G1X0.01F300", "G0 X1 ; °", "naïve 🔧 end", "")) {
            assertArrayEquals(s, s.toByteArray(Charsets.US_ASCII), MakeraFrame.ascii(s))
        }
    }

    @Test fun decoderSplitsAndResyncs() {
        val a = MakeraFrame.build(MakeraFrame.NORMAL_INFO, "ok\r\n".toByteArray())
        val b = MakeraFrame.build(MakeraFrame.STATUS_RES, "<Idle|MPos:-1.000,-2.000,-3.000,0,0>".toByteArray())
        val junk = byteArrayOf(1, 2, 0x86.toByte(), 3)
        val all = junk + a + b
        val got = mutableListOf<Pair<Int, String>>()
        val dec = MakeraFrame.Decoder()
        // Feed in awkward pieces, as TCP may deliver them.
        var i = 0
        for (n in listOf(3, 5, 1, 7, 100)) {
            val end = minOf(all.size, i + n)
            if (i >= end) break
            val chunk = all.copyOfRange(i, end)
            dec.feed(chunk, chunk.size) { t, p -> got += t to p.decodeToString() }
            i = end
        }
        assertEquals(listOf(0x90 to "ok\r\n", 0x81 to "<Idle|MPos:-1.000,-2.000,-3.000,0,0>"), got)
    }
}
