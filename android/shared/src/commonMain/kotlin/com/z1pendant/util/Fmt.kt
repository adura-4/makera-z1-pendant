package com.z1pendant.util

import kotlin.math.abs

/**
 * Number formatting without java.util.Formatter / BigDecimal, giving the same text the Android app
 * produced with them (US decimal point):
 * - [fixed] = `String.format(Locale.US, "%[+][width].Nf", v)`. Java rounds the shortest decimal
 *   representation of the double (what `Double.toString` prints) half-up, so 0.125 → "0.13" and
 *   -0.0 → "-0.000". This does the same.
 * - [scaledPlain] = `BigDecimal(v).setScale(n, HALF_UP).stripTrailingZeros().toPlainString()`, which
 *   rounds the EXACT binary value (1.00005 is 1.0000499… so it gives "1").
 * - [plain] = `v.toBigDecimal().stripTrailingZeros().toPlainString()` (from `Double.toString`).
 */
object Fmt {
    /** Two decimals, trailing zeros dropped: 121.25 → "121.25", 162.5 → "162.5", 125.0 → "125". */
    fun trimmed2(v: Double): String {
        val t = kotlin.math.round(v * 100).toLong()
        val a = kotlin.math.abs(t)
        val frac = (a % 100).toString().padStart(2, '0').trimEnd('0')
        val s = if (frac.isEmpty()) "${a / 100}" else "${a / 100}.$frac"
        return if (t < 0) "-$s" else s
    }

    /** 12000 → "12,000" (RPM on the spindle tile and in the start box). */
    fun thousands(n: Int): String {
        val d = kotlin.math.abs(n).toString()
        val g = d.reversed().chunked(3).joinToString(",").reversed()
        return if (n < 0) "-$g" else g
    }

    fun fixed(v: Double, decimals: Int, plus: Boolean = false, width: Int = 0): String {
        val body = when {
            v.isNaN() -> "NaN"
            v.isInfinite() -> if (v > 0) (if (plus) "+Infinity" else "Infinity") else "-Infinity"
            else -> {
                val neg = v < 0.0 || (v == 0.0 && 1.0 / v < 0.0)        // Java keeps the sign of -0.0
                val (digits, point) = shortestDigits(abs(v))
                (if (neg) "-" else if (plus) "+" else "") + roundedText(digits, point, decimals)
            }
        }
        return body.padStart(width)
    }

    /** Like `"%.4f".format(v).trimEnd('0')`, with a trailing "." turned into ".0". */
    fun trimmed4(v: Double): String = fixed(v, 4).trimEnd('0').let { if (it.endsWith(".")) it + "0" else it }

    fun plain(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return v.toString()
        val (digits, point) = shortestDigits(abs(v))
        if (digits.isEmpty()) return "0"
        return (if (v < 0) "-" else "") + plainText(digits, point)
    }

    fun scaledPlain(v: Double, scale: Int = 4): String {
        require(scale in 0..4) { "scale $scale not supported" }
        if (v.isNaN() || v.isInfinite()) return v.toString()
        val a = abs(v)
        if (a == 0.0) return "0"
        val bits = a.toRawBits()
        val be = ((bits ushr 52) and 0x7FF).toInt()
        val frac = bits and 0xFFFFFFFFFFFFFL
        val m = if (be == 0) frac else frac or (1L shl 52)
        val e = if (be == 0) -1074 else be - 1075                 // a = m × 2^e exactly
        val pow5 = longArrayOf(1, 5, 25, 125, 625)[scale]
        val units: String = if (a < TWO_49) {
            // a × 10^scale = m × 5^scale × 2^(e+scale); m × 625 < 2^63. Here e + scale ≤ 0.
            val big = m * pow5
            val k = -(e + scale)
            val r = when {
                k == 0 -> big
                k >= 64 -> 0L
                else -> (big ushr k) + ((big ushr (k - 1)) and 1L)     // HALF_UP: the bit just below decides
            }
            r.toString()
        } else {
            // ≥ 2^49 the value has at most 3 binary fraction digits, so its exact decimal fits in
            // the scale and no rounding happens: write it out exactly.
            val (digits, point) = exactDigits(m, e)
            roundedText(digits, point, scale).replace(".", "")
        }
        val str = units.trimStart('0').padStart(scale + 1, '0')
        val intPart = str.substring(0, str.length - scale)
        val fracPart = str.substring(str.length - scale).trimEnd('0')
        val text = if (fracPart.isEmpty()) intPart else "$intPart.$fracPart"
        return if (v < 0 && units.any { it != '0' }) "-$text" else text
    }

    private const val TWO_49 = 562949953421312.0

    /** |v| = 0.DIGITS × 10^point, DIGITS without leading/trailing zeros ("" for zero). */
    internal fun shortestDigits(a: Double): Pair<String, Int> {
        if (a == 0.0) return "" to 0
        val s = a.toString()                               // "123.45", "1.0E-4", "1.2345E10"
        val eIdx = s.indexOfFirst { it == 'E' || it == 'e' }
        val mant = if (eIdx >= 0) s.substring(0, eIdx) else s
        val exp10 = if (eIdx >= 0) s.substring(eIdx + 1).removePrefix("+").toInt() else 0
        val dot = mant.indexOf('.')
        val intPart = if (dot >= 0) mant.substring(0, dot) else mant
        val fracPart = if (dot >= 0) mant.substring(dot + 1) else ""
        return normalize(intPart + fracPart, intPart.length + exp10)
    }

    /** Exact decimal digits of m × 2^e (for the rare huge values in [scaledPlain]). */
    private fun exactDigits(m: Long, e: Int): Pair<String, Int> {
        var d = m.toString()
        return if (e >= 0) {
            repeat(e) { d = mulSmall(d, 2) }
            normalize(d, d.length)
        } else {
            repeat(-e) { d = mulSmall(d, 5) }              // m × 5^k / 10^k
            normalize(d, d.length + e)
        }
    }

    private fun normalize(raw: String, point0: Int): Pair<String, Int> {
        val lead = raw.indexOfFirst { it != '0' }
        if (lead < 0) return "" to 0
        return raw.substring(lead).trimEnd('0') to point0 - lead
    }

    /** 0.DIGITS × 10^point rounded half-up to [n] decimals, as "I.FFF" (no sign). */
    private fun roundedText(digits: String, point: Int, n: Int): String {
        val keep = point + n
        var units = "0"
        if (digits.isNotEmpty() && keep >= 0) {
            val kept = if (keep <= digits.length) digits.substring(0, keep) else digits.padEnd(keep, '0')
            units = kept.ifEmpty { "0" }
            if (keep < digits.length && digits[keep] >= '5') units = incr(units)
        }
        val p = units.padStart(n + 1, '0')
        return if (n == 0) p else p.substring(0, p.length - n) + "." + p.substring(p.length - n)
    }

    private fun plainText(digits: String, point: Int): String = when {
        point <= 0 -> "0." + "0".repeat(-point) + digits
        point >= digits.length -> digits + "0".repeat(point - digits.length)
        else -> digits.substring(0, point) + "." + digits.substring(point)
    }

    private fun incr(s: String): String {
        val c = s.toCharArray()
        var i = c.lastIndex
        while (i >= 0) {
            if (c[i] == '9') { c[i] = '0'; i-- } else { c[i] = c[i] + 1; return c.concatToString() }
        }
        return "1" + c.concatToString()
    }

    private fun mulSmall(s: String, k: Int): String {
        val sb = StringBuilder()
        var carry = 0
        for (i in s.lastIndex downTo 0) {
            val x = (s[i] - '0') * k + carry
            sb.append('0' + x % 10); carry = x / 10
        }
        while (carry > 0) { sb.append('0' + carry % 10); carry /= 10 }
        return sb.reverse().toString()
    }
}
