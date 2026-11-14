package com.z1pendant

import com.z1pendant.link.GrblishDialect
import com.z1pendant.util.Fmt
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.random.Random

/** The common formatter must print exactly what java.util.Formatter / BigDecimal printed before. */
class FmtTest {
    private val specials = listOf(
        0.0, -0.0, 0.125, 0.375, 1.0005, 1.00005, 2.5, 3.5, -2.5, 1234.5, 0.0005, 0.00049, -0.0004, -0.0005,
        0.005, 0.01, 0.1, 1.0, 5.0, 10.0, 25.0, 25.4, -105.0, 100.0, -207.0, -1.0, -2.0, 0.15, 0.35, 1.15,
        123.4565, -98.7655, 1e-7, 1e-5, 1e7, 1e15, 1e20, 1e300, Double.MIN_VALUE, 4.9e-300,
        123456789.987654321, 562949953421312.5, 9007199254740993.0, 1.5e16, 3000.0, 2999.9999, 0.0049999,
        -0.00001, -0.00005, 0.99995, 9.99995, 99999.99995,
    )

    private fun randoms(seed: Int) = Random(seed).let { r ->
        List(20000) {
            when (it % 4) {
                0 -> r.nextDouble(-300.0, 300.0)
                1 -> (r.nextInt(-300000, 300000) / 1000.0)                    // typical 3-decimal positions
                2 -> (r.nextInt(-3000000, 3000000) / 10000.0) + 0.00005       // near ties
                else -> r.nextDouble(-1e6, 1e6)
            }
        }
    }

    @Test fun fixedMatchesJavaFormat() {
        for (v in specials + randoms(1)) {
            for (n in 0..4) assertEquals("%.${n}f of $v", String.format(Locale.US, "%.${n}f", v), Fmt.fixed(v, n))
            assertEquals("%9.3f of $v", String.format(Locale.US, "%9.3f", v), Fmt.fixed(v, 3, width = 9))
            assertEquals("%+.3f of $v", String.format(Locale.US, "%+.3f", v), Fmt.fixed(v, 3, plus = true))
        }
    }

    @Test fun scaledPlainMatchesBigDecimal() {
        for (v in specials + randoms(2)) {
            val want = BigDecimal(v).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            assertEquals("scaledPlain of $v", want, Fmt.scaledPlain(v, 4))
        }
        assertEquals("0.005", Fmt.scaledPlain(0.005)); assertEquals("1", Fmt.scaledPlain(1.0))
        assertEquals("5", Fmt.scaledPlain(5.0)); assertEquals("25.4", Fmt.scaledPlain(25.4))
        assertEquals("-105", Fmt.scaledPlain(-105.0))
    }

    @Test fun plainMatchesBigDecimalOfToString() {
        for (v in specials + randoms(3)) {
            assertEquals("plain of $v", v.toBigDecimal().stripTrailingZeros().toPlainString(), Fmt.plain(v))
        }
    }

    @Test fun dialectFormatsLikeBefore() {
        fun oldFmt(v: Double) = "%.4f".format(Locale.US, v).trimEnd('0').let { if (it.endsWith(".")) it + "0" else it }
        for (v in specials + randoms(4)) assertEquals("fmt of $v", oldFmt(v), GrblishDialect.fmt(v))
        assertEquals("G91G1X0.01F123", GrblishDialect.jogMove('X', 0.01, 123.4))
        assertEquals("G91G1Z-1.0F1000", GrblishDialect.jogMove('Z', -1.0, 999.5))
    }
}
