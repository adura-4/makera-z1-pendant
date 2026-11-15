package com.z1pendant

import com.z1pendant.link.StatusParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JobTest {
    /** Status lines captured from the real Z1 during the test job (2026-11-15). */
    private val running = "<Run|MPos:-143.1906,-115.5797,-41.2434,0.0000,0.0000|WPos:9.9994,10.0003,15.0000,-90.0000,0.0000|" +
        "F:0.0,1000.0,100.0|S:5113.7,6000.0,85.0,0,28.9,22.1,0,0,0,0|T:1,-7.109,1|L:0, 0, 0, 0.0,100.0|P:86,16,58|C:4,1,0,1>"
    private val toolWait = "<Tool|MPos:-9.89,-13.58,-3,0,0|WPos:0,0,0,0,0|F:0.0,1000.0,100.0|S:0.0,6000.0,85.0|T:1,-7.109,2|P:196,30,122>"

    @Test fun progressField() {
        val st = StatusParser.parse(running, 0)!!
        assertEquals(86L, st.job!!.line); assertEquals(16, st.job!!.percent); assertEquals(58L, st.job!!.elapsedS)
        assertEquals(1000.0, st.feedSet!!, 0.0); assertEquals(0.0, st.feedActual!!, 0.0)
        assertEquals(1, st.tool); assertEquals(1, st.toolTarget); assertEquals(-7.109, st.tlo!!, 1e-9)
        val w = StatusParser.parse(toolWait, 0)!!
        assertEquals("Tool", w.state); assertEquals(2, w.toolTarget)
        assertNull(StatusParser.parse(running.replace("|P:86,16,58", ""), 0)!!.job)
        // The Wi-Fi board's canned fallback status isn't a job.
        assertNull(StatusParser.parse(running.replace("P:86,16,58", "P:1234,50,1200"), 0)!!.job)
    }

    @Test fun leftEstimate() {
        assertEquals(304L, JobView(com.z1pendant.link.JobProgress(86, 16, 58), null).leftS)
        assertNull(JobView(com.z1pendant.link.JobProgress(0, 0, 0), null).leftS)
    }

    @Test fun fusionToolsAndChanges() {
        val g = """
            (Setup6)
            (T1  Spiral O Metal 3.175*12mm  Makera    D=3.175 SD=3.175 TD=3.175 FL=12. SL=12. BL=19. - ZMIN=-5.45 - flat end mill)
            (T1  Spiral O Metal 3.175*12mm  Makera    D=3.175 SD=3.175 TD=3.175 FL=12. SL=12. BL=19. - ZMIN=-3.95 - flat end mill)
            (T3  Chamfering Bit - 1/8? Shank      D=3.175 SD=3.175 TD=0.1 FL=3. SL=21.5 BL=21.5 TAPER=45deg - ZMIN=-1.25 - chamfer mill)
            (T2  OSG 404-1250-BN 1/8 4FL Ball  OSG  404-1250-BN  D=3.175 CR=1.587 - ZMIN=-2.287 - ball end mill)
            G90 G94
            T1M6
            G1 X1
            T1M6
            T3M6
            T2 M06
            M6 T2
            (T9 in a comment M6)
            T1M6
        """.trimIndent()
        val t = JobFileParser.parse(g)
        assertEquals(listOf(ToolChange(1, 7), ToolChange(3, 10), ToolChange(2, 11), ToolChange(1, 14)), t.changes)
        assertEquals("Spiral O Metal 3.175*12mm Makera · flat end mill", t.describe(1))
        assertEquals("OSG 404-1250-BN 1/8 4FL Ball OSG 404-1250-BN · ball end mill", t.describe(2))
        assertEquals("Chamfering Bit - 1/8? Shank · chamfer mill", t.describe(3))
        assertEquals(14, t.lines)
    }

    @Test fun makeraStudioTools() {
        val g = ";@MKR|TOOL|number=1|id=7|name=3.175*12mm Flat End(Metal)|type=Flat End|diameter=3.175\n;@MKR|TIME|seconds=1750\nM6 T1\nG0 X0"
        val t = JobFileParser.parse(g)
        assertEquals("3.175*12mm Flat End(Metal)", t.describe(1))
        assertEquals(listOf(ToolChange(1, 3)), t.changes)
    }
}
