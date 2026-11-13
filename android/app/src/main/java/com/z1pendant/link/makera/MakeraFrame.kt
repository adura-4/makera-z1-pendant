package com.z1pendant.link.makera

import java.io.ByteArrayOutputStream

/**
 * Stock Makera wire format (confirmed from the Makera app decompile and the community firmware):
 *   86 68 | len u16 BE | type | payload | CRC u16 BE | 55 AA
 *   len = 1 + payload + 2; CRC-16/XMODEM (poly 0x1021, init 0) over len + type + payload.
 */
object MakeraFrame {
    const val CTRL_SINGLE = 0xA1
    const val CTRL_MULTI = 0xA2
    const val FIRM_VER = 0x71
    const val STATUS_RES = 0x81
    const val DIAG_RES = 0x82
    const val NORMAL_INFO = 0x90

    private val TABLE = IntArray(256) { i ->
        var c = i shl 8
        repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x1021 else c shl 1 }
        c and 0xFFFF
    }

    fun crc16(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var c = 0
        for (i in from until to) c = ((c shl 8) and 0xFFFF) xor TABLE[((c ushr 8) xor (data[i].toInt() and 0xFF)) and 0xFF]
        return c
    }

    fun build(type: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val len = payload.size + 3
        val body = ByteArray(3 + payload.size)
        body[0] = (len ushr 8).toByte(); body[1] = len.toByte(); body[2] = type.toByte()
        payload.copyInto(body, 3)
        val crc = crc16(body)
        val out = ByteArrayOutputStream(body.size + 6)
        out.write(0x86); out.write(0x68); out.write(body)
        out.write(crc ushr 8); out.write(crc and 0xFF); out.write(0x55); out.write(0xAA)
        return out.toByteArray()
    }

    fun line(text: String) = build(CTRL_MULTI, text.toByteArray(Charsets.US_ASCII))
    fun rt(ch: Int) = build(CTRL_SINGLE, byteArrayOf(ch.toByte()))

    class Decoder {
        private var buf = ByteArray(4096)
        private var n = 0
        var badFrames = 0; private set

        fun feed(data: ByteArray, len: Int, out: (type: Int, payload: ByteArray) -> Unit) {
            if (n + len > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, n + len))
            System.arraycopy(data, 0, buf, n, len); n += len
            var i = 0
            while (true) {
                while (i + 1 < n && !(buf[i] == 0x86.toByte() && buf[i + 1] == 0x68.toByte())) i++
                if (i + 4 > n) break
                val ln = ((buf[i + 2].toInt() and 0xFF) shl 8) or (buf[i + 3].toInt() and 0xFF)
                if (ln < 3 || ln > 2048) { i += 2; badFrames++; continue }
                val total = 6 + ln
                if (i + total > n) break
                val crcAt = i + 4 + ln - 2
                val crc = ((buf[crcAt].toInt() and 0xFF) shl 8) or (buf[crcAt + 1].toInt() and 0xFF)
                val foot = ((buf[i + 4 + ln].toInt() and 0xFF) shl 8) or (buf[i + 5 + ln].toInt() and 0xFF)
                if (foot != 0x55AA || crc != crc16(buf, i + 2, crcAt)) { i += 2; badFrames++; continue }
                out(buf[i + 4].toInt() and 0xFF, buf.copyOfRange(i + 5, crcAt))
                i += total
            }
            if (i > 0) { System.arraycopy(buf, i, buf, 0, n - i); n -= i }
        }
    }
}
