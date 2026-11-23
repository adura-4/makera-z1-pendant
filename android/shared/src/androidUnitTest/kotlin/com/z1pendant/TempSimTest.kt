package com.z1pendant

import android.os.SystemClock
import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineTarget
import com.z1pendant.platform.Platform
import com.z1pendant.platform.Prefs
import com.z1pendant.platform.PrefsEditor
import com.z1pendant.platform.TextFile
import com.z1pendant.temp.TempWarn
import com.z1pendant.ui.stillTooHot
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Spindle temperature through a job on the simulator (python/z1sim.py --heat): the limit is read from
 * the SD config, the job's temperatures and tool marks are recorded, the warning comes 5 °C under the
 * limit, and the overheat halt is recognised (code 9) with Clear alarm held while it's still too hot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TempSimTest {
    private val port = 22251
    private var sim: Process? = null
    private lateinit var simDir: File
    private var buzzes = 0

    @Before fun startSim() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000 }
        Dispatchers.setMain(Dispatchers.Unconfined)
        val script = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "python/z1sim.py") }.firstOrNull { it.exists() }
        assumeTrue("z1sim.py not found", script != null)
        simDir = File(System.getProperty("java.io.tmpdir"), "z1sim_sd_${System.nanoTime()}").apply { mkdirs() }
        File(simDir, "hot.cnc").writeText("G90 G94 G21\nT2M6\nS8000 M3\nG1 X40 F120\nM30\n")
        // Starts at 62 °C and gains 240 °C/min with the spindle on: warns at 70 within ~2 s, halts past 75.
        // No M5: the sim runs M3/M5 as soon as it reads them, not in step with the moves.
        sim = try {
            ProcessBuilder("python", script!!.path, "--port", "$port", "--no-broadcast", "--sd", simDir.path,
                "--heat", "240", "--spindle-temp", "62", "--spindle-max", "75")
                .redirectErrorStream(true).redirectOutput(File(System.getProperty("java.io.tmpdir"), "z1sim_temp_test.log")).start()
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

    @Test fun overheatDuringAJob() = runBlocking {
        val vm = AppViewModel(TempFakePlatform { buzzes++ })
        val link = vm.link
        vm.connect(MachineTarget("sim", "127.0.0.1", port))
        withTimeout(5000) { while (link.conn.value != ConnState.Connected) delay(20) }
        withTimeout(15_000) { while (vm.tempLimits.value.spindle != 75.0) delay(50) }       // from config-get sd
        for (l in listOf("\$H", "G53 G0 X-100 Y-100 Z-60", "G10L20P0X0Y0Z0")) link.sendLine(l)
        withTimeout(5_000) { while (link.status.value?.isIdle == true) delay(20) }          // homing has started
        withTimeout(30_000) { while (link.status.value?.let { it.isIdle && it.mpos[0] == -100.0 } != true) delay(50) }
        link.sendLine("play hot.cnc")

        withTimeout(10_000) { while (vm.job.value == null) delay(20) }
        try { withTimeout(15_000) { while (vm.tempWarn.value == null) delay(20) } }
        catch (e: Exception) { System.err.println(vm.console.value.takeLast(60).joinToString("\n")); System.err.println(link.status.value?.raw); throw e }
        assertEquals(TempWarn.SPINDLE, vm.tempWarn.value)
        assertEquals(1, buzzes)
        withTimeout(15_000) { while (link.status.value?.isAlarm != true) delay(20) }
        withTimeout(3000) { while (vm.haltFromMessage.value == null) delay(20) }

        assertEquals(9, link.status.value!!.halt)
        assertEquals(9, vm.haltFromMessage.value)
        assertTrue(vm.haltTemp.value!! > 75.0)
        assertNotNull(stillTooHot(9, link.status.value, vm.tempLimits.value))           // Clear alarm waits
        val v = vm.temps.value!!
        assertTrue("samples ${v.samples.size}", v.samples.size >= 2)
        assertEquals(listOf(2), v.marks.map { it.tool })                                  // T2 after Done ATC
        assertNotNull(vm.alarmAtS.value)
        assertTrue(vm.beforeAlarm.value!!.spindleRpm!! > 0)
        link.disconnect()
    }
}

private class TempFakePlatform(val onThud: () -> Unit) : Platform {
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
        override fun thud() { onThud() }
        override fun limit() {}
        override fun press() {}
    }
}
