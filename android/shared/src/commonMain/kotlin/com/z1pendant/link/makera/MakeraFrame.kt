package com.z1pendant.link.makera

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
    /** File download (Wi-Fi board): 0xB0 start "download <path>\n", 0xB1 md5, 0xB2 size, 0xB3 data, 0xB4 end, 0xB5 cancel/error. */
    const val DL_START = 0xB0; const val DL_MD5 = 0xB1; const val DL_SIZE = 0xB2
    const val DL_DATA = 0xB3; const val DL_END = 0xB4; const val DL_CANCEL = 0xB5
    /** "Other client is currently uploading/downloading" and similar refusals. */
    const val REFUSED = 0x91
    /** Longest frame: a download packet is 8192 data bytes + seq + type + CRC. */
    const val MAX_LEN = 8199
    /** Empty request → "path|md5" of the playing job file (the Wi-Fi board answers it; confirmed 2026-11-15). */
    const val JOB_FILE = 0xB7

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
        val out = ByteArray(body.size + 6)
        out[0] = 0x86.toByte(); out[1] = 0x68
        body.copyInto(out, 2)
        val t = 2 + body.size
        out[t] = (crc ushr 8).toByte(); out[t + 1] = crc.toByte(); out[t + 2] = 0x55; out[t + 3] = 0xAA.toByte()
        return out
    }

    fun line(text: String) = build(CTRL_MULTI, ascii(text))

    /** US-ASCII bytes, '?' for anything else (one per character / surrogate pair), like Java's encoder. */
    fun ascii(text: String): ByteArray {
        val out = ArrayList<Byte>(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) i++
            out.add(if (c.code < 0x80) c.code.toByte() else '?'.code.toByte())
            i++
        }
        return out.toByteArray()
    }
    fun rt(ch: Int) = build(CTRL_SINGLE, byteArrayOf(ch.toByte()))

    class Decoder {
        private var buf = ByteArray(4096)
        private var n = 0
        var badFrames = 0; private set

        fun feed(data: ByteArray, len: Int, out: (type: Int, payload: ByteArray) -> Unit) {
            if (n + len > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, n + len))
            data.copyInto(buf, n, 0, len); n += len
            var i = 0
            while (true) {
                while (i + 1 < n && !(buf[i] == 0x86.toByte() && buf[i + 1] == 0x68.toByte())) i++
                if (i + 4 > n) break
                val ln = ((buf[i + 2].toInt() and 0xFF) shl 8) or (buf[i + 3].toInt() and 0xFF)
                if (ln < 3 || ln > MAX_LEN) { i += 2; badFrames++; continue }
                val total = 6 + ln
                if (i + total > n) break
                val crcAt = i + 4 + ln - 2
                val crc = ((buf[crcAt].toInt() and 0xFF) shl 8) or (buf[crcAt + 1].toInt() and 0xFF)
                val foot = ((buf[i + 4 + ln].toInt() and 0xFF) shl 8) or (buf[i + 5 + ln].toInt() and 0xFF)
                if (foot != 0x55AA || crc != crc16(buf, i + 2, crcAt)) { i += 2; badFrames++; continue }
                out(buf[i + 4].toInt() and 0xFF, buf.copyOfRange(i + 5, crcAt))
                i += total
            }
            if (i > 0) { buf.copyInto(buf, 0, i, n); n -= i }
        }
    }
}
