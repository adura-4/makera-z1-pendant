package com.z1pendant

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.z1pendant.hotkeys.BuiltIn
import com.z1pendant.hotkeys.BuiltinHost
import com.z1pendant.hotkeys.Hotkey
import com.z1pendant.hotkeys.HotkeyJson
import com.z1pendant.hotkeys.HotkeyRunner
import com.z1pendant.hotkeys.Trigger
import com.z1pendant.jog.JogEngine
import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineTarget
import com.z1pendant.link.makera.Discovery
import com.z1pendant.link.makera.MakeraLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("z1pendant", Context.MODE_PRIVATE)
    val haptics = Haptics(app)
    val link = MakeraLink()

    // ---- UI state -------------------------------------------------------------------------
    val steps = listOf(0.001, 0.01, 0.1, 1.0)
    val axes = listOf('X', 'Y', 'Z', 'A')
    val axis = MutableStateFlow(prefs.getString("axis", "X")!!.first())
    val stepIndex = MutableStateFlow(prefs.getInt("stepIndex", 2).coerceIn(0, 3))
    val target = MutableStateFlow(loadTarget())
    val discovered = MutableStateFlow<List<Pair<MachineTarget, Boolean>>>(emptyList())
    val scanning = MutableStateFlow(false)
    val snack = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val console = MutableStateFlow<List<String>>(emptyList())
    val confirmFor = MutableStateFlow<Hotkey?>(null)
    /** Bound buttons and volume-jog only act while the main screen is showing. */
    @Volatile var onMainScreen = true
    val editMode = MutableStateFlow(false)
    /** Hotkey grid expanded (dial hidden, every hotkey shown). */
    val hotkeysExpanded = MutableStateFlow(false)
    /** While arranging: the working order (ids). Saved by [saveArrangement]. */
    val arrangeOrder = MutableStateFlow<List<String>?>(null)

    // settings
    val hapticStrength = MutableStateFlow(prefs.getFloat("haptic", 0.7f))
    val volumeJog = MutableStateFlow(prefs.getBoolean("volumeJog", true))
    val keepScreenOn = MutableStateFlow(prefs.getBoolean("keepScreenOn", true))
    val tickMs = MutableStateFlow(prefs.getLong("tickMs", 40))
    val degPerClick = MutableStateFlow(prefs.getFloat("degPerClick", 12f))
    val softClamp = MutableStateFlow(prefs.getBoolean("softClamp", true))

    // hotkeys
    private val hotkeyFile = File(app.filesDir, "hotkeys.json")
    val hotkeys = MutableStateFlow(loadHotkeys())
    /** Non-null while the binding dialog waits for a button press. */
    val capture = MutableStateFlow<((Trigger) -> Unit)?>(null)

    val jog = JogEngine(link, viewModelScope, onDrop = { haptics.thud() }, onLimit = { haptics.limit() })

    private val host = object : BuiltinHost {
        override fun runBuiltin(b: BuiltIn): List<String>? = builtin(b)
        override fun beforeGcode() { /* jog engine sends G90 once it's quiet; wait for that */ }
        override fun setJogEnabled(on: Boolean) { jog.enabled = on; if (!on) jog.cancelPending() }
    }
    val runner = HotkeyRunner(link, viewModelScope, host) { id -> hotkeys.value.firstOrNull { it.id == id } }

    init {
        haptics.strength = hapticStrength.value
        jog.axis = axis.value
        jog.step = steps[stepIndex.value]
        applyJogConfig()
        jog.start()
        viewModelScope.launch {
            link.messages.collect { m -> console.value = (console.value + "← $m").takeLast(200) }
        }
        viewModelScope.launch { runner.lastError.collect { it?.let { e -> snack.tryEmit(e); runner.lastError.value = null } } }
        viewModelScope.launch {
            link.conn.collectLatest { c ->
                console.value = (console.value + "· link $c").takeLast(200)
                if (c != ConnState.Connected) jog.cancelPending()
            }
        }
        target.value?.let { if (prefs.getBoolean("autoConnect", true)) link.connect(it) }
    }

    private fun applyJogConfig() {
        jog.config = jog.config.copy(tickMs = tickMs.value, softLimitClamp = softClamp.value)
    }

    // ---- connection -----------------------------------------------------------------------
    private fun loadTarget(): MachineTarget? {
        val h = prefs.getString("host", null) ?: return null
        return MachineTarget(prefs.getString("name", h)!!, h, prefs.getInt("port", 2222))
    }

    fun connect(t: MachineTarget) {
        prefs.edit().putString("host", t.host).putInt("port", t.port).putString("name", t.name)
            .putBoolean("autoConnect", true).apply()
        target.value = t
        link.connect(t)
    }

    fun disconnect() {
        prefs.edit().putBoolean("autoConnect", false).apply()
        link.disconnect()
    }

    private var scanJob: Job? = null
    fun scan() {
        scanJob?.cancel()
        discovered.value = emptyList()
        scanning.value = true
        scanJob = viewModelScope.launch {
            try {
                Discovery.scan(getApplication(), 3500) { t, busy -> discovered.value = discovered.value + (t to busy) }
            } catch (e: Exception) {
                snack.tryEmit("Scan failed: ${e.message}")
            } finally { scanning.value = false }
        }
    }

    // ---- jog --------------------------------------------------------------------------------
    fun selectAxis(a: Char) { axis.value = a; jog.axis = a; prefs.edit().putString("axis", a.toString()).apply() }
    fun selectStep(i: Int) {
        val j = i.coerceIn(0, steps.lastIndex)
        stepIndex.value = j; jog.step = steps[j]; prefs.edit().putInt("stepIndex", j).apply()
    }
    private var detents = 0
    /** One handwheel detent: tick, with a firmer click every 10th. */
    fun wheelClicks(n: Int) {
        jog.click(n)
        detents += 1
        if (detents % 10 == 0) haptics.majorTick() else haptics.tick()
    }

    /** Test hook: [n] clicks spread evenly over [ms] (sign = direction). */
    fun viewModelScopeLaunchBurst(n: Int, ms: Long) {
        viewModelScope.launch {
            val count = kotlin.math.abs(n); val dir = if (n < 0) -1 else 1
            val start = SystemClock.elapsedRealtime()
            android.util.Log.i("JogTest", "burst start n=$n ms=$ms step=${steps[stepIndex.value]}")
            for (k in 1..count) {
                val due = start + ms * k / count
                val wait = due - SystemClock.elapsedRealtime()
                if (wait > 0) kotlinx.coroutines.delay(wait)
                wheelClicks(dir)
            }
            android.util.Log.i("JogTest", "burst end")
        }
    }

    fun sendConsole(line: String) {
        if (line.isBlank()) return
        console.value = (console.value + "→ $line").takeLast(200)
        link.sendLine(line.trim())
    }

    fun zero(axes: String) = link.dialect.zeroAxes(axes).forEach { link.sendLine(it) }
    fun setFeedOverride(p: Int) = link.dialect.feedOverride(p.coerceIn(10, 300)).forEach { link.sendLine(it) }
    fun hold() { haptics.press(); link.feedHold() }
    fun resume() { haptics.press(); link.resume() }
    fun reset() { haptics.press(); runner.cancel(); jog.cancelPending(); link.reset() }

    private fun builtin(b: BuiltIn): List<String>? {
        val d = link.dialect
        val fro = link.status.value?.feedOverride?.toInt() ?: 100
        return when (b) {
            BuiltIn.HOME -> d.home()
            BuiltIn.UNLOCK -> d.unlock()
            BuiltIn.ZERO_SELECTED -> d.zeroAxes(axis.value.toString())
            BuiltIn.ZERO_X -> d.zeroAxes("X"); BuiltIn.ZERO_Y -> d.zeroAxes("Y")
            BuiltIn.ZERO_Z -> d.zeroAxes("Z"); BuiltIn.ZERO_A -> d.zeroAxes("A")
            BuiltIn.ZERO_XY -> d.zeroAxes("XY"); BuiltIn.ZERO_XYZ -> d.zeroAxes("XYZ")
            BuiltIn.HOLD -> { link.feedHold(); null }
            BuiltIn.RESUME -> { link.resume(); null }
            BuiltIn.RESET -> { reset(); null }
            BuiltIn.JOG_PLUS -> { jog.click(1); null }
            BuiltIn.JOG_MINUS -> { jog.click(-1); null }
            BuiltIn.NEXT_AXIS -> { selectAxis(axes[(axes.indexOf(axis.value) + 1) % axes.size]); null }
            BuiltIn.AXIS_X -> { selectAxis('X'); null }; BuiltIn.AXIS_Y -> { selectAxis('Y'); null }
            BuiltIn.AXIS_Z -> { selectAxis('Z'); null }; BuiltIn.AXIS_A -> { selectAxis('A'); null }
            BuiltIn.STEP_UP -> { selectStep(stepIndex.value + 1); null }
            BuiltIn.STEP_DOWN -> { selectStep(stepIndex.value - 1); null }
            BuiltIn.FEED_UP -> d.feedOverride((fro + 10).coerceAtMost(300))
            BuiltIn.FEED_DOWN -> d.feedOverride((fro - 10).coerceAtLeast(10))
            BuiltIn.FEED_100 -> d.feedOverride(100)
            BuiltIn.LIGHT_TOGGLE -> {
                val on = link.outputs.value["light"] == true
                link.assumeOutput("light", !on)     // tile flips now; the read-backs confirm it
                viewModelScope.launch {
                    kotlinx.coroutines.delay(1200); link.refreshOutputs()
                }
                d.light(!on)              // unknown state = turn it on
            }
        }
    }

    // ---- hotkeys ------------------------------------------------------------------------------
    private fun loadHotkeys(): List<Hotkey> = try {
        if (hotkeyFile.exists()) migrate(HotkeyJson.import(hotkeyFile.readText())) else Hotkey.defaults()
    } catch (_: Exception) { Hotkey.defaults() }

    /** v0.1 defaults had "Light on" (M821) + "Light off" (M822); fold them into one toggle. */
    private fun migrate(list: List<Hotkey>): List<Hotkey> {
        fun isOnly(h: Hotkey, code: String) = h.steps.size == 1 && (h.steps[0] as? com.z1pendant.hotkeys.Step.GCode)?.text?.trim() == code
        val on = list.indexOfFirst { it.label == "Light on" && isOnly(it, "M821") }
        val off = list.indexOfFirst { it.label == "Light off" && isOnly(it, "M822") }
        if (on < 0 || off < 0) return list
        val toggle = list[on].copy(label = "Light", kind = com.z1pendant.hotkeys.Kind.BUILTIN,
            steps = listOf(com.z1pendant.hotkeys.Step.Builtin(BuiltIn.LIGHT_TOGGLE)),
            triggers = list[on].triggers + list[off].triggers)
        return list.mapIndexedNotNull { i, h -> when (i) { on -> toggle; off -> null; else -> h } }
            .also { hotkeyFile.writeText(HotkeyJson.export(it)) }
    }

    private fun saveHotkeys(list: List<Hotkey>) {
        hotkeys.value = list
        viewModelScope.launch(Dispatchers.IO) { hotkeyFile.writeText(HotkeyJson.export(list)) }
    }

    fun upsertHotkey(h: Hotkey) {
        val l = hotkeys.value.toMutableList()
        val i = l.indexOfFirst { it.id == h.id }
        if (i >= 0) l[i] = h else l.add(h)
        saveHotkeys(l)
    }
    fun deleteHotkey(id: String) = saveHotkeys(hotkeys.value.filterNot { it.id == id })
    fun moveHotkey(id: String, by: Int) {
        val l = hotkeys.value.toMutableList()
        val i = l.indexOfFirst { it.id == id }; if (i < 0) return
        val j = (i + by).coerceIn(0, l.lastIndex)
        l.add(j, l.removeAt(i)); saveHotkeys(l)
    }
    fun resetHotkeys() = saveHotkeys(Hotkey.defaults())

    fun startArranging() { arrangeOrder.value = hotkeys.value.map { it.id }; editMode.value = true }
    fun moveInArrangement(id: String, to: Int) {
        val l = (arrangeOrder.value ?: return).toMutableList()
        val from = l.indexOf(id); if (from < 0) return
        l.add(to.coerceIn(0, l.lastIndex), l.removeAt(from))
        arrangeOrder.value = l
        haptics.tick()
    }
    fun saveArrangement() {
        val order = arrangeOrder.value
        if (order != null) {
            val byId = hotkeys.value.associateBy { it.id }
            val sorted = order.mapNotNull { byId[it] } + hotkeys.value.filter { it.id !in order }
            saveHotkeys(sorted)
        }
        arrangeOrder.value = null; editMode.value = false
    }
    fun exportHotkeys(): String = HotkeyJson.export(hotkeys.value)
    fun importHotkeys(text: String) {
        try {
            val l = HotkeyJson.import(text); saveHotkeys(l); snack.tryEmit("Imported ${l.size} hotkeys")
        } catch (e: Exception) { snack.tryEmit("Import failed: ${e.message}") }
    }

    /** Tap on a tile, a bound button, or the editor's Test button. */
    fun requestRun(h: Hotkey, test: Boolean = false) {
        runner.blockReason(h)?.let { snack.tryEmit("${h.label}: $it"); haptics.thud(); return }
        if (h.confirm && !test) { confirmFor.value = h; return }
        haptics.press()
        runner.run(h)
    }
    fun confirmRun() { confirmFor.value?.let { haptics.press(); runner.run(it) }; confirmFor.value = null }

    // ---- settings -----------------------------------------------------------------------------
    fun setHaptic(v: Float) { hapticStrength.value = v; haptics.strength = v; prefs.edit().putFloat("haptic", v).apply() }
    fun setVolumeJog(v: Boolean) { volumeJog.value = v; prefs.edit().putBoolean("volumeJog", v).apply() }
    fun setKeepScreenOn(v: Boolean) { keepScreenOn.value = v; prefs.edit().putBoolean("keepScreenOn", v).apply() }
    fun setTickMs(v: Long) { tickMs.value = v; prefs.edit().putLong("tickMs", v).apply(); applyJogConfig() }
    fun setDegPerClick(v: Float) { degPerClick.value = v; prefs.edit().putFloat("degPerClick", v).apply() }
    fun setSoftClamp(v: Boolean) { softClamp.value = v; prefs.edit().putBoolean("softClamp", v).apply(); applyJogConfig() }

    // ---- physical buttons -------------------------------------------------------------------------
    private class Held(val downAt: Long, var longFired: Boolean = false)
    private val held = HashMap<Int, Held>()
    private val longMs = 500L

    private fun deviceName(e: KeyEvent): String? = e.device?.name ?: InputDevice.getDevice(e.deviceId)?.name

    private fun keyLabel(e: KeyEvent): String {
        val k = KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_").lowercase().replace('_', ' ')
        return k.replaceFirstChar { it.uppercase() }
    }

    private fun matches(t: Trigger, e: KeyEvent) = t.keyCode == e.keyCode && (t.device == null || t.device == deviceName(e))

    private fun bindings(e: KeyEvent, long: Boolean) =
        hotkeys.value.filter { h -> h.triggers.any { it.longPress == long && matches(it, e) } }

    /** Called from Activity.dispatchKeyEvent. Returns true if consumed. */
    fun onKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_POWER) return false
        val cap = capture.value
        if (cap != null) {
            when (e.action) {
                KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) held[code] = Held(e.downTime) else if (e.isLongPress) held[code]?.longFired = true
                KeyEvent.ACTION_UP -> {
                    val h = held.remove(code)
                    // Event timestamps, not the wall clock: works for injected events and laggy BT remotes.
                    val long = h != null && (h.longFired || e.eventTime - e.downTime >= longMs)
                    val dev = deviceName(e)
                    cap(Trigger(code, long, if (isVolume(code)) null else dev,
                        keyLabel(e) + (if (long) " (hold)" else "") + (dev?.takeIf { !isVolume(code) }?.let { " · $it" } ?: "")))
                    capture.value = null
                    haptics.press()
                }
            }
            return true
        }

        if (!onMainScreen) return false          // editor / settings / console: keys behave normally
        val longs = bindings(e, true)
        val shorts = bindings(e, false)
        val volume = isVolume(code)
        val volumeDefault = volume && volumeJog.value && shorts.isEmpty()
        if (longs.isEmpty() && shorts.isEmpty() && !volumeDefault) {
            // Swallow unbound gamepad buttons on the main screen: B would otherwise act as Back.
            return KeyEvent.isGamepadButton(code)
        }

        fun fireShort() {
            if (shorts.isNotEmpty()) shorts.forEach { requestRun(it) }
            else if (volumeDefault) { jog.click(if (code == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1); haptics.tick() }
        }

        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount == 0) {
                    held[code] = Held(e.downTime)
                    if (longs.isEmpty()) fireShort()
                } else {
                    val h = held[code] ?: return true
                    if (longs.isNotEmpty()) {
                        if (!h.longFired && (e.isLongPress || e.eventTime - e.downTime >= longMs)) { h.longFired = true; longs.forEach { requestRun(it) } }
                    } else if (volumeDefault) fireShort()          // holding a volume key keeps jogging, one click per repeat
                }
            }
            KeyEvent.ACTION_UP -> {
                val h = held.remove(code)
                if (longs.isNotEmpty() && h != null && !h.longFired) fireShort()
            }
        }
        return true
    }

    private fun isVolume(c: Int) = c == KeyEvent.KEYCODE_VOLUME_UP || c == KeyEvent.KEYCODE_VOLUME_DOWN

    override fun onCleared() { jog.stop(); link.disconnect() }
}
