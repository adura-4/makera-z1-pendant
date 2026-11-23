package com.z1pendant

import android.os.SystemClock
import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineTarget
import com.z1pendant.platform.Platform
import com.z1pendant.platform.Prefs
import com.z1pendant.platform.PrefsEditor
import com.z1pendant.platform.TextFile
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Job percent through a whole job on the simulator, with Jimmy's boss_to_12.5 file (2026-11-23):
 * 55 lines, half of its bytes a comment header, the tool change at the top. The Z1's own percent
 * (bytes read, read ahead) said 44 % before anything was cut, and lines ÷ total stopped at 96–98 %
 * when the job ended. The pendant's percent now starts at 0, only goes up, and reaches 100.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobProgressSimTest {
    private val port = 22252
    private var sim: Process? = null
    private lateinit var simDir: File

    @Before fun startSim() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000 }
        Dispatchers.setMain(Dispatchers.Unconfined)
        val script = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "python/z1sim.py") }.firstOrNull { it.exists() }
        assumeTrue("z1sim.py not found", script != null)
        simDir = File(System.getProperty("java.io.tmpdir"), "z1sim_sd_${System.nanoTime()}").apply { mkdirs() }
        File(simDir, "boss.cnc").writeText(BOSS)            // the sim's SD folder is /sd/gcodes
        sim = try {
            ProcessBuilder("python", script!!.path, "--port", "$port", "--no-broadcast", "--sd", simDir.path, "--speed", "4")
                .redirectErrorStream(true).redirectOutput(File(System.getProperty("java.io.tmpdir"), "z1sim_progress_test.log")).start()
        } catch (_: Exception) { null }
        assumeTrue("python not runnable", sim != null)
        val up = (1..50).any { runCatching { Socket("127.0.0.1", port).close() }.isSuccess || run { Thread.sleep(100); false } }
        assumeTrue("simulator didn't start", up)
    }

    @After fun stopSim() {
        sim?.destroy(); sim?.waitFor(5, TimeUnit.SECONDS)
        Dispatchers.resetMain()
        unmockkStatic(SystemClock::class)
        simDir.deleteRecursively()
    }

    @Test fun percentThroughTheBossJob() = runBlocking {
        val vm = AppViewModel(ProgressFakePlatform())
        val link = vm.link
        vm.connect(MachineTarget("sim", "127.0.0.1", port))
        withTimeout(5000) { while (link.conn.value != ConnState.Connected) delay(20) }
        for (l in listOf("\$H", "G53 G0 X-100 Y-100 Z-40", "G10L20P0X0Y0Z-20")) link.sendLine(l)
        withTimeout(5_000) { while (link.status.value?.isIdle == true) delay(20) }
        withTimeout(30_000) { while (link.status.value?.let { it.isIdle && it.mpos[0] == -100.0 } != true) delay(50) }
        link.sendLine("play /sd/gcodes/boss.cnc")
        withTimeout(10_000) { while (vm.job.value == null) delay(20) }

        // Every status while the job runs: (line, the Z1's %, the pendant's %, the old formula's %).
        val trace = mutableListOf<IntArray>()
        var lastRaw = ""
        withTimeout(120_000) {
            while (vm.job.value != null) {
                val j = vm.job.value
                val st = link.status.value
                if (j != null && st != null && st.raw != lastRaw) {
                    lastRaw = st.raw
                    val n = j.totalLines
                    val old = if (n != null && n > 0 && j.progress.line > 0) (j.progress.line * 100 / 56).toInt().coerceIn(0, 99) else j.progress.percent
                    trace += intArrayOf(j.progress.line.toInt(), j.progress.percent, j.percent, old, n ?: -1)
                }
                delay(25)
            }
        }
        val report = trace.joinToString("\n") { "line ${it[0]}  z1 ${it[1]}%  pendant ${it[2]}%  old ${it[3]}%  of ${it[4]}" }
        File(System.getProperty("java.io.tmpdir"), "job_progress_trace.txt").writeText(report)

        val known = trace.filter { it[4] > 0 }                           // once the file is in
        assertTrue("the file was read during the job", known.isNotEmpty())
        assertEquals("55 lines, not 56 (the trailing newline isn't a line)", 55, known.first()[4])
        assertTrue("the Z1's own % started high (bytes read)", trace.first()[1] >= 40)
        known.filter { it[0] == 0 }.forEach { assertEquals("line 0 = nothing cut yet = 0 %", 0, it[2]) }
        known.zipWithNext().forEach { (a, b) -> assertTrue("never goes back: ${a[2]} → ${b[2]}", b[2] >= a[2]) }
        assertEquals("reaches 100 % before the job ends", 100, known.last()[2])
        link.disconnect()
    }

    companion object {
        /** Jimmy's stock_nut_boss_to_12.5.cnc (Z1 Post 0.1.0), as it is on the Z1's SD card. */
        val BOSS = """
(boss_to_12.5)
(Generated with Z1 Post version 0.1.0)
(Restore rapid moves enabled: True, maximum steps inbetween start and stop: 3, minimum travel distance: 3mm)
(Machine)
(  vendor: Makera)
(  model: Z1 3 axis)
(T8  WEXWE 1/4 3FL DLC square 1/4x18x2  WEXWE  B09WYZMKQQ  D=6.35 SD=6.35 TD=6.35 FL=18. SL=18. BL=31.8 - ZMIN=-9.53 - flat end mill)
( =================================================== )
(   Z Origin Set To   : Stock Top )
(   Stock Height                    : 14.05 mm )
(   Toolpath Z Maximum from Stock Top: 15. mm )
(   Toolpath Z Min from Stock Top: -9.53 mm )
(  )
( =================================================== )

( The following values are based on the part position info used in fusion and can be used to roughly align the machine offset from anchor 1 if you use that feature. )
(   X Offset   : 0. mm )
(   Y Offset   : 0. mm )

( =================================================== )

G90 G94
G17
G21

T8M6
(5)
M400
M851 S100
S13000 M3
G54
G0 X-0.635 Y11.33
Z15.
G1 Z5. F1000
Z1. F500
Z-8.895
G19 G2 Y10.695 Z-9.53 J-0.635 K0. F1000
G1 Y10.06
G17 G3 X0. Y9.425 I0.635 J0.
G2 X0. Y-9.425 I0. J-9.425
X0. Y9.425 I0. J9.425
X0. Y-9.425 I0. J-9.425
X0. Y9.425 I0. J9.425
G3 X0.635 Y10.06 I0. J0.635
G1 Y10.695
G19 G3 Y11.33 Z-8.895 J0. K0.635
G1 Z15.
G17

(Boss OD to 12.5 (T8))
M5
M400
M852
G28
M30
""".trimStart('\n').replace("\n", "\r\n")
    }
}

private class ProgressFakePlatform : Platform {
    private val map = HashMap<String, Any?>()
    override val prefs = object : Prefs {
        override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
        override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
        override fun getLong(key: String, default: Long) = map[key] as? Long ?: default
        override fun getFloat(key: String, default: Float) = map[key] as? Float ?: default
        override fun getString(key: String, default: String?) = map[key] as? String ?: default
        override fun edit(block: PrefsEditor.() -> Unit) {
            object : PrefsEditor {
                override fun putBoolean(key: String, value: Boolean) { map[key] = value }
                override fun putInt(key: String, value: Int) { map[key] = value }
                override fun putLong(key: String, value: Long) { map[key] = value }
                override fun putFloat(key: String, value: Float) { map[key] = value }
                override fun putString(key: String, value: String) { map[key] = value }
            }.block()
        }
    }
    override val hotkeyFile = object : TextFile {
        var text: String? = null
        override fun exists() = text != null
        override fun readText() = text ?: ""
        override fun writeText(text: String) { this.text = text }
    }
    override val haptics = object : Haptics {
        override var strength = 0f
        override val description = "none"
        override fun tick() {}
        override fun majorTick() {}
        override fun thud() {}
        override fun limit() {}
        override fun press() {}
    }
}
