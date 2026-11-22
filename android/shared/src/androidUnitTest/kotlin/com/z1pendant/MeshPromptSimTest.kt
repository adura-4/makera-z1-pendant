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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * The "Leveling mesh still active" popup on connect, against the simulator (python/z1sim.py --boot-mesh).
 * The Z1 right after power-on reports `|O:` with an empty grid (all nan, 2026-11-22): no popup. A grid
 * with values: popup, with its largest point. Skipped when Python isn't available.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshPromptSimTest {
    private val port = 22241
    private var sim: Process? = null

    private fun startSim(bootMesh: String) {
        val script = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "python/z1sim.py") }.firstOrNull { it.exists() }
        assumeTrue("z1sim.py not found", script != null)
        sim = try {
            ProcessBuilder("python", script!!.path, "--port", "$port", "--no-broadcast", "--boot-mesh", bootMesh)
                .redirectErrorStream(true).redirectOutput(File(System.getProperty("java.io.tmpdir"), "z1sim_mesh_test.log")).start()
        } catch (_: Exception) { null }
        assumeTrue("python not runnable", sim != null)
        val up = (1..50).any { runCatching { Socket("127.0.0.1", port).close() }.isSuccess || run { Thread.sleep(100); false } }
        assumeTrue("simulator didn't start", up)
    }

    @Before fun setUp() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000 }
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After fun tearDown() {
        sim?.destroy(); sim?.waitFor(5, TimeUnit.SECONDS)
        Dispatchers.resetMain()
        unmockkStatic(SystemClock::class)
    }

    private suspend fun connect(): AppViewModel {
        val vm = AppViewModel(MeshFakePlatform())
        vm.connect(MachineTarget("sim", "127.0.0.1", port))
        withTimeout(5000) { while (vm.link.conn.value != ConnState.Connected) delay(20) }
        withTimeout(5000) { while (vm.link.status.value?.mesh == null) delay(20) }   // the sim reports |O:
        return vm
    }

    @Test fun emptyGridAfterPowerOnDoesNotAsk() = runBlocking {
        startSim("empty")
        val vm = connect()
        withTimeout(10_000) { while (vm.console.value.none { "empty" in it }) delay(50) }
        assertNull(vm.meshPrompt.value)
        assertTrue(vm.console.value.any { "M375.1" in it })
        vm.link.disconnect()
    }

    @Test fun realGridAsks() = runBlocking {
        startSim("real")
        val vm = connect()
        withTimeout(10_000) { while (vm.meshPrompt.value == null) delay(50) }
        assertEquals(0.05, vm.meshPrompt.value!!, 1e-9)            // the grid's largest point, from M375.1
        vm.clearMesh()
        withTimeout(5000) { while (vm.link.status.value?.mesh != null) delay(50) }
        vm.link.disconnect()
    }
}

private class MeshFakePlatform : Platform {
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
