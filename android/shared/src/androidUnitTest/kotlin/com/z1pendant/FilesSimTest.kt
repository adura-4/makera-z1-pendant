package com.z1pendant

import android.os.SystemClock
import com.z1pendant.files.FilesController
import com.z1pendant.files.RemoteFiles
import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineTarget
import com.z1pendant.link.makera.MakeraLink
import com.z1pendant.platform.Prefs
import com.z1pendant.platform.PrefsEditor
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The Files screen's operations against the machine simulator (python/z1sim.py, which answers ls -s /
 * mkdir / mv / rm / upload / download like the Z1's Wi-Fi board): list, new folder, upload (also into a
 * folder, with progress and cancel), rename, header read for Job info, delete. Skipped without Python.
 */
class FilesSimTest {
    private val port = 22241
    private var sim: Process? = null
    private lateinit var sd: File
    private lateinit var link: MakeraLink
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun start() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000 }
        val script = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "python/z1sim.py") }.firstOrNull { it.exists() }
        assumeTrue("z1sim.py not found", script != null)
        sd = File(System.getProperty("java.io.tmpdir"), "z1sim_files_${System.nanoTime()}").apply { mkdirs() }
        File(sd, "Examples").mkdirs()
        File(sd, "Examples/TopClamp.nc").writeText("G21\n")
        File(sd, "old job.cnc").writeText(HEADER + "G1 X1\n".repeat(4000))
        sim = try {
            ProcessBuilder("python", script!!.path, "--port", "$port", "--no-broadcast", "--sd", sd.path)
                .redirectErrorStream(true).redirectOutput(File(System.getProperty("java.io.tmpdir"), "z1sim_files_test.log")).start()
        } catch (_: Exception) { null }
        assumeTrue("python not runnable", sim != null)
        val up = (1..50).any { runCatching { Socket("127.0.0.1", port).close() }.isSuccess || run { Thread.sleep(100); false } }
        assumeTrue("simulator didn't start", up)
        link = MakeraLink()
        link.connect(MachineTarget("sim", "127.0.0.1", port))
        runBlocking { withTimeout(10_000) { while (link.conn.value != ConnState.Connected) delay(50) } }
    }

    @After fun stop() {
        if (::link.isInitialized) link.disconnect()
        scope.cancel()
        sim?.destroy(); sim?.waitFor(5, TimeUnit.SECONDS)
        unmockkStatic(SystemClock::class)
        if (::sd.isInitialized) sd.deleteRecursively()
    }

    @Test fun listMkdirUploadRenameHeadDelete() = runBlocking {
        val root = link.listDir(RemoteFiles.ROOT)
        assertNotNull(root)
        assertEquals(setOf("Examples", "old job.cnc"), root!!.map { it.name }.toSet())
        assertTrue(root.first { it.name == "Examples" }.isDir)

        assertNull(link.makeDir("/sd/gcodes/new stuff"))
        assertTrue(File(sd, "new stuff").isDirectory)
        assertNotNull(link.makeDir("/sd/gcodes/new stuff"))                    // already there: an error, not a hang

        val data = ByteArray(10_000) { (it % 251).toByte() }
        var last = 0L
        assertNull(link.uploadFile("/sd/gcodes/new stuff/part one.cnc", data) { s, t -> last = s; assertEquals(10_000L, t); true })
        assertEquals(10_000L, last)
        assertArrayEquals(data, File(sd, "new stuff/part one.cnc").readBytes())
        assertEquals("part one.cnc", link.listDir("/sd/gcodes/new stuff")!!.single().name)

        assertNull(link.rename("/sd/gcodes/new stuff/part one.cnc", "/sd/gcodes/new stuff/part_two.cnc"))
        assertTrue(File(sd, "new stuff/part_two.cnc").exists())
        assertNotNull(link.rename("/sd/gcodes/nothere.cnc", "/sd/gcodes/x.cnc"))

        val head = link.readFileHead("/sd/gcodes/old job.cnc", 65536) { com.z1pendant.files.JobInfo.headerComplete(it) }
        assertNotNull(head)
        assertTrue("stops early: ${head!!.length}", head.length < File(sd, "old job.cnc").length())
        val info = com.z1pendant.files.JobInfo.parse(head)
        assertEquals(listOf(3, 1), info.tools.map { it.number })
        assertEquals("stock top", info.zero)
        // The link is free again after stopping a download early.
        assertEquals("G21\n", link.downloadFile("/sd/gcodes/Examples/TopClamp.nc"))

        assertNull(link.delete("/sd/gcodes/new stuff"))
        assertFalse(File(sd, "new stuff").exists())
        assertNotNull(link.delete("/sd/gcodes/new stuff"))
    }

    @Test fun uploadCancelLeavesNothingAndTheNextUploadWorks() = runBlocking {
        val big = ByteArray(200_000).also { Random(3).nextBytes(it) }
        val r = link.uploadFile("/sd/gcodes/big.cnc", big) { s, _ -> s < 40_000 }
        assertEquals("cancelled", r)
        delay(500)
        assertFalse("a cancelled upload is removed", File(sd, "big.cnc").exists())
        assertNull(link.uploadFile("/sd/gcodes/big.cnc", big))
        assertArrayEquals(big, File(sd, "big.cnc").readBytes())
        val md5 = MessageDigest.getInstance("MD5").digest(big).joinToString("") { "%02x".format(it) }
        assertEquals(md5, com.z1pendant.util.Md5.hex(big))
    }

    @Test fun controllerUploadConflictAndReadOnlyRules() = runBlocking {
        val notes = mutableListOf<String>()
        File(sd, "old_job.cnc").writeText("G0\n")
        val files = FilesController(link, scope, MapPrefs()) { notes += it }
        files.open()
        waitFor { files.entries.value != null && files.readOnly.value == null }

        files.upload("old job.cnc", "G21\n".toByteArray())                     // cleaned to old_job.cnc, which is taken: asks first
        assertNotNull(files.conflict.value)
        files.resolveConflict(FilesController.Clash.KeepBoth)
        waitFor { File(sd, "old_job_2.cnc").exists() && files.upload.value == null }
        assertEquals("G21\n", File(sd, "old_job_2.cnc").readText())

        files.upload("my part.cnc", "G0\n".toByteArray())                       // spaces become _
        waitFor { File(sd, "my_part.cnc").exists() && files.upload.value == null }

        files.makeFolder("jobs")
        waitFor { File(sd, "jobs").isDirectory && files.working.value == null }
        files.enter("jobs")
        waitFor { files.path.value == "/sd/gcodes/jobs" && files.entries.value != null }
        files.upload("inside.cnc", "G1\n".toByteArray())
        waitFor { File(sd, "jobs/inside.cnc").exists() && files.upload.value == null }
        assertTrue(files.back())
        assertEquals(RemoteFiles.ROOT, files.path.value)

        waitFor { files.entries.value?.any { it.name == "my_part.cnc" } == true }
        val e = files.entries.value!!.first { it.name == "my_part.cnc" }
        files.rename(e, "renamed part.cnc")
        waitFor { File(sd, "renamed_part.cnc").exists() && files.working.value == null }
        files.delete(listOf("renamed_part.cnc", "old_job_2.cnc"))
        waitFor { !File(sd, "renamed_part.cnc").exists() && !File(sd, "old_job_2.cnc").exists() && files.working.value == null }
        assertTrue(notes.toString(), notes.any { it.startsWith("Deleted 2") })
    }

    @Test fun controllerCancelMidTransferLeavesNothing() = runBlocking {
        val notes = mutableListOf<String>()
        val files = FilesController(link, scope, MapPrefs()) { notes += it }
        files.open()
        waitFor { files.entries.value != null && files.readOnly.value == null }
        val big = ByteArray(1_500_000).also { Random(5).nextBytes(it) }
        files.upload("big.cnc", big)
        assertNotNull("the strip shows at once", files.upload.value)
        waitFor { (files.upload.value?.sent ?: 0) > 0 }
        files.cancelUpload()
        assertNull("the strip goes at once", files.upload.value)
        assertTrue(notes.toString(), notes.contains("Upload cancelled"))
        delay(1500)
        assertFalse("the board removed the half-written file", File(sd, "big.cnc").exists())
        files.upload("small.cnc", "G0\n".toByteArray())                       // the transfer slot is free again
        waitFor { File(sd, "small.cnc").exists() && files.upload.value == null }
    }

    @Test fun controllerCancelWhileWaitingForTheLink() = runBlocking {
        val notes = mutableListOf<String>()
        val files = FilesController(link, scope, MapPrefs()) { notes += it }
        files.open()
        waitFor { files.entries.value != null && files.readOnly.value == null }
        link.disconnect()                                                       // as after the phone's picker
        files.upload("late.cnc", "G0\n".toByteArray())
        assertEquals("Waiting for the Z1…", files.upload.value?.note)
        files.cancelUpload()
        assertNull(files.upload.value)
        link.connect(MachineTarget("sim", "127.0.0.1", port))
        waitFor { link.conn.value == ConnState.Connected }
        delay(2000)
        assertFalse("nothing is sent once the link is back", File(sd, "late.cnc").exists())
        assertTrue(notes.toString(), notes.contains("Upload cancelled"))
    }

    private suspend fun waitFor(ms: Long = 15_000, cond: () -> Boolean) = withTimeout(ms) { while (!cond()) delay(30) }

    private class MapPrefs : Prefs {
        val m = HashMap<String, Any>()
        override fun getBoolean(key: String, default: Boolean) = m[key] as? Boolean ?: default
        override fun getInt(key: String, default: Int) = m[key] as? Int ?: default
        override fun getLong(key: String, default: Long) = m[key] as? Long ?: default
        override fun getFloat(key: String, default: Float) = m[key] as? Float ?: default
        override fun getString(key: String, default: String?) = m[key] as? String ?: default
        override fun edit(block: PrefsEditor.() -> Unit) = object : PrefsEditor {
            override fun putBoolean(key: String, value: Boolean) { m[key] = value }
            override fun putInt(key: String, value: Int) { m[key] = value }
            override fun putLong(key: String, value: Long) { m[key] = value }
            override fun putFloat(key: String, value: Float) { m[key] = value }
            override fun putString(key: String, value: String) { m[key] = value }
        }.block()
    }

    companion object {
        val HEADER = """
            (old job)
            (T3  Ball 1/8  D=3.175 CR=1.5 - ball end mill)
            (T1  Flat 3.175  D=3.175 - flat end mill)
            (   Z Origin Set To   : Stock Top )
            (   Stock Height                    : 6. mm )
            G90 G94 G21
            T3M6
        """.trimIndent() + "\n"
    }
}
