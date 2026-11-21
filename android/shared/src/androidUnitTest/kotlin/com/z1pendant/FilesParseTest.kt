package com.z1pendant

import com.z1pendant.files.JobInfo
import com.z1pendant.files.RemoteFiles
import com.z1pendant.link.RemoteEntry
import com.z1pendant.util.Md5
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import kotlin.random.Random

/** The Files screen's parsing and labels, against text captured from the Z1 (2026-11-16). */
class FilesParseTest {
    // Two 0x83 chunks from the Z1's `ls -s /sd/gcodes`, joined (spaces in names arrive as 0x01).
    private val listing = ".md5/ 0 20260810114950\r\nExamples/ 0 20260810114950\r\n.lz/ 0 20260821141650\r\n" +
        "goto-pack-pos-z1.nc  54 20260827150236\r\ncan_lid_op1.cnc  4081049 20261109130302\r\n" +
        "_upload\u0001test\u0001space.cnc  57 20261116164754\r\n_upload_test_next.cnc  57 19800101015630\r\n" +
        "can_lid_op_1_Setup4.cnc  1476995 20261116220426\r\n"

    @Test fun parsesTheZ1Listing() {
        val e = RemoteFiles.parseListing(listing)
        assertEquals(listOf("Examples", "goto-pack-pos-z1.nc", "can_lid_op1.cnc", "_upload test space.cnc",
            "_upload_test_next.cnc", "can_lid_op_1_Setup4.cnc"), e.map { it.name })      // .md5 and .lz hidden
        assertTrue(e[0].isDir)
        assertEquals(RemoteEntry("can_lid_op1.cnc", false, 4081049, "20261109130302"), e[2])
    }

    @Test fun sortsFoldersFirstThenNewestNameOrSize() {
        val e = RemoteFiles.parseListing(listing)
        assertEquals("Examples", RemoteFiles.sorted(e, RemoteFiles.Sort.Newest)[0].name)
        assertEquals("can_lid_op_1_Setup4.cnc", RemoteFiles.sorted(e, RemoteFiles.Sort.Newest)[1].name)
        assertEquals("_upload test space.cnc", RemoteFiles.sorted(e, RemoteFiles.Sort.Name)[1].name)
        assertEquals("can_lid_op1.cnc", RemoteFiles.sorted(e, RemoteFiles.Sort.Size)[1].name)
    }

    @Test fun pathsAndCrumbs() {
        assertEquals("/sd/gcodes/Examples", RemoteFiles.child("/sd/gcodes", "Examples"))
        assertEquals("/sd/gcodes", RemoteFiles.parent("/sd/gcodes/Examples"))
        assertEquals(listOf("gcodes" to "/sd/gcodes", "Examples" to "/sd/gcodes/Examples", "Tests" to "/sd/gcodes/Examples/Tests"),
            RemoteFiles.crumbs("/sd/gcodes/Examples/Tests"))
    }

    @Test fun cleansNamesAndKeepsBoth() {
        assertEquals("my_bracket_v2.cnc", RemoteFiles.cleanName(" my bracket v2.cnc "))
        assertEquals("a_b_c_d.nc", RemoteFiles.cleanName("a?b*c/d.nc"))
        assertEquals("x_2.cnc", RemoteFiles.uniqueName("x.cnc", setOf("x.cnc")))
        assertEquals("x_3.cnc", RemoteFiles.uniqueName("x.cnc", setOf("x.cnc", "x_2.cnc")))
        assertEquals("folder_2", RemoteFiles.uniqueName("folder", setOf("folder")))
    }

    @Test fun sizeLabels() {
        assertEquals("54 bytes", RemoteFiles.sizeLabel(54))
        assertEquals("21 KB", RemoteFiles.sizeLabel(21737))
        assertEquals("1.4 MB", RemoteFiles.sizeLabel(1476995))
        assertEquals("3.9 MB", RemoteFiles.sizeLabel(4081049))
    }

    @Test fun timeLabelsInLocalTime() {
        val est = { _: Long -> -5 * 3600 }                                   // the phone is 5 h behind UTC
        val now = RemoteFiles.epochOf("20261117010000")!!                     // = Nov 16, 20:00 local
        assertEquals("today 17:04", RemoteFiles.timeLabel("20261116220426", now, est))
        assertEquals("yesterday 22:24", RemoteFiles.timeLabel("20261116032424", now, est))   // Nov 15 22:24 local
        assertEquals("Nov 9", RemoteFiles.timeLabel("20261109130302", now, est))
        assertEquals("Aug 27", RemoteFiles.timeLabel("20260827150236", now, est))
        assertEquals("Dec 31, 2025", RemoteFiles.timeLabel("20260101040000", now, est))
        assertEquals("date unknown", RemoteFiles.timeLabel("19800101015630", now, est))
        assertNull(RemoteFiles.epochOf("2026"))
        assertEquals(0L, RemoteFiles.epochOf("19700101000000"))
    }

    // The header of can_lid_op_1/Setup4.cnc as posted by Z1 Post 0.1.0 (Makera Community post v1.4.6).
    private val setup4 = """
        (Setup4)
        (Generated with Z1 Post version 0.1.0)
        (Machine)
        (  vendor: Makera)
        (  model: Z1 3 axis)
        (T4  Flat Single Flute Engraving 30 deg*.3mm      D=0.3 SD=3.175 TD=0. FL=0.5 SL=9. BL=19. - ZMIN=-0.2 - flat end mill)
        (T1  Spiral O Metal 3.175*12mm  Makera  D=3.175 SD=3.175 - ZMIN=-5.45 - flat end mill)
        ( =================================================== )
        (   Z Origin Set To   : Stock Top )
        (   Stock Height                    : 5.5 mm )
        (   Toolpath Z Maximum from Stock Top: 12. mm )
        ( =================================================== )

        G90 G94
        G17
        G21

        T4M6
        (T9  should not count: after the header D=6)
        M400
    """.trimIndent() + "\n"

    @Test fun jobInfoFromTheMakeraPostHeader() {
        val ji = JobInfo.parse(setup4)
        assertEquals("5.5", ji.stockHeightMm)
        assertEquals("stock top", ji.zero)
        assertEquals(listOf(4, 1), ji.tools.map { it.number })
        assertEquals("Flat Single Flute Engraving 30 deg*.3mm · flat end mill", ji.tools[0].description)
        assertEquals("0.3", ji.tools[0].diameterMm)
        assertEquals("3.175", ji.tools[1].diameterMm)
    }

    @Test fun jobInfoFromAPostWithoutTheStockBlock() {
        // Fusion's plain posts list the tools but not the stock: the sheet then just leaves those rows out.
        val ji = JobInfo.parse("%\n(1001)\n(T2  D=6 CR=0 - ZMIN=-3 - flat end mill)\nG90 G94\n")
        assertNull(ji.stockHeightMm)
        assertNull(ji.zero)
        assertEquals(listOf(2), ji.tools.map { it.number })
        assertEquals("6", ji.tools[0].diameterMm)
    }

    @Test fun headerCompleteOnlyOnceAWholeGcodeLineArrived() {
        assertFalse(JobInfo.headerComplete("(Setup4)\n(T4  x D=1 - flat end mill)\n"))
        assertFalse(JobInfo.headerComplete("(Setup4)\n(T4  x D=1 - flat end mill)\nG9"))     // cut off mid-line
        assertTrue(JobInfo.headerComplete(setup4.substring(0, setup4.indexOf("G17"))))
    }

    @Test fun md5MatchesJava() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Md5.hex(ByteArray(0)))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Md5.hex("abc".toByteArray()))
        val r = Random(7)
        for (n in listOf(55, 56, 63, 64, 65, 119, 120, 1000, 100_000)) {
            val b = ByteArray(n).also { r.nextBytes(it) }
            val java = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
            assertEquals("size $n", java, Md5.hex(b))
        }
    }
}
