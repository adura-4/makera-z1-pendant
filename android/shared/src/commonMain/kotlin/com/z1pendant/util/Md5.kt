package com.z1pendant.util

/**
 * MD5 (RFC 1321), for the upload's 0xB1 frame: the Z1 stores the hash we send as the file's MD5 cache,
 * which Makera Studio compares against. Common code has no MD5 of its own, so it's here (small and fast
 * enough for G-code files of a few MB).
 */
object Md5 {
    private val S = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )
    private val K = IntArray(64) { i ->
        val v = kotlin.math.abs(kotlin.math.sin((i + 1).toDouble())) * 4294967296.0
        v.toLong().toInt()
    }

    fun digest(data: ByteArray): ByteArray {
        val bitLen = data.size.toLong() * 8
        val padLen = ((56 - (data.size + 1) % 64) + 64) % 64
        val msg = ByteArray(data.size + 1 + padLen + 8)
        data.copyInto(msg)
        msg[data.size] = 0x80.toByte()
        for (i in 0 until 8) msg[msg.size - 8 + i] = (bitLen ushr (8 * i)).toByte()
        var a0 = 0x67452301; var b0 = 0xefcdab89.toInt(); var c0 = 0x98badcfe.toInt(); var d0 = 0x10325476
        val m = IntArray(16)
        var off = 0
        while (off < msg.size) {
            for (j in 0 until 16) {
                val p = off + j * 4
                m[j] = (msg[p].toInt() and 0xFF) or ((msg[p + 1].toInt() and 0xFF) shl 8) or
                    ((msg[p + 2].toInt() and 0xFF) shl 16) or ((msg[p + 3].toInt() and 0xFF) shl 24)
            }
            var a = a0; var b = b0; var c = c0; var d = d0
            for (i in 0 until 64) {
                var f: Int; val g: Int
                when (i / 16) {
                    0 -> { f = (b and c) or (b.inv() and d); g = i }
                    1 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) % 16 }
                    2 -> { f = b xor c xor d; g = (3 * i + 5) % 16 }
                    else -> { f = c xor (b or d.inv()); g = (7 * i) % 16 }
                }
                f += a + K[i] + m[g]
                a = d; d = c; c = b
                b += f.rotateLeft(S[i])
            }
            a0 += a; b0 += b; c0 += c; d0 += d
            off += 64
        }
        val out = ByteArray(16)
        for ((n, v) in intArrayOf(a0, b0, c0, d0).withIndex()) for (i in 0 until 4) out[n * 4 + i] = (v ushr (8 * i)).toByte()
        return out
    }

    /** Lowercase hex, 32 characters (what the Z1 expects). */
    fun hex(data: ByteArray): String = digest(data).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
