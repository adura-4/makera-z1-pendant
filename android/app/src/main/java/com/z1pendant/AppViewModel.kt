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
import com.z1pendant.link.MachineStatus
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
    val steps = listOf(0.005, 0.01, 0.1, 1.0, 5.0)
    val axes = listOf('X', 'Y', 'Z', 'A')
    val axis = MutableStateFlow(prefs.getString("axis", "X")!!.first())
    val stepIndex = MutableStateFlow(prefs.getInt("stepIndex", 2).coerceIn(0, steps.lastIndex))
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
    /** Bottom drawer with feed override + Hold/Resume/Reset pulled up. */
    val controlsOpen = MutableStateFlow(false)
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

    val jog = JogEngine(link, viewModelScope, onDrop = { haptics.thud() }, onLimit = { haptics.limit() },
        onJogDone = { moved ->
            moved.forEach { (a, d) ->
                log("↕ jog $a ${"%+.3f".format(java.util.Locale.US, d)} ${if (a == 'A') "°" else "mm"} (dial)")
            }
        })

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
            link.messages.collect { m -> log("← $m") }
        }
        viewModelScope.launch { link.sent.collect { m -> log("→ $m") } }
        viewModelScope.launch { runner.lastError.collect { it?.let { e -> snack.tryEmit(e); runner.lastError.value = null } } }
        viewModelScope.launch {
            link.conn.collectLatest { c ->
                log("· link $c")
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
        link.sendLine(line.trim())          // the link logs it when it actually goes out
    }

    /** Console line with a time stamp. Everything sent (except dial jogs) and every reply. */
    private fun log(s: String) {
        // Formatter made here, not as a field: log() already runs from init, before later fields exist.
        val line = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + s
        synchronized(console) { console.value = (console.value + line).takeLast(500) }
    }

    fun zero(axes: String) = zeroLines(axes).forEach { link.sendLine(it) }

    // ---- zero / undo (like a DRO's zero + recall) ---------------------------------------------
    /** Work value an axis had before it was zeroed, and the machine position it was zeroed at. */
    data class ZeroUndo(val prevWork: Double, val machineAt: Double)
    val zeroUndo = MutableStateFlow<Map<Char, ZeroUndo>>(emptyMap())

    /** Undo is offered while the axis still reads 0.000 and hasn't moved since it was zeroed. */
    fun canRestoreZero(a: Char, st: MachineStatus?): Boolean {
        val u = zeroUndo.value[a] ?: return false
        val i = axes.indexOf(a)
        val w = st?.wpos?.getOrNull(i) ?: return false
        val m = st.mpos.getOrNull(i) ?: return false
        return kotlin.math.abs(w) < 0.0005 && kotlin.math.abs(m - u.machineAt) < 0.0015
    }

    /**
     * Zero [which] (e.g. "XY"), or, if every one of them can be restored (pressed again with nothing moved),
     * put their previous work values back with G10 L20. Used by the zero buttons and the Zero
     * hotkeys alike.
     */
    private fun zeroLines(which: String): List<String> {
        val st = link.status.value
        val list = which.toList()
        if (list.isNotEmpty() && list.all { canRestoreZero(it, st) }) {
            val prev = list.associateWith { zeroUndo.value.getValue(it).prevWork }
            zeroUndo.value = zeroUndo.value - list.toSet()
            log("↺ restore " + prev.entries.joinToString(" ") { (a, v) -> "$a ${"%.3f".format(java.util.Locale.US, v)}" })
            return link.dialect.setWorkPosition(prev)
        }
        val m = zeroUndo.value.toMutableMap()
        for (a in list) {
            val i = axes.indexOf(a)          // the X/Y/Z/A list, not [which]
            val w = st?.wpos?.getOrNull(i); val mp = st?.mpos?.getOrNull(i)
            if (w != null && mp != null) m[a] = ZeroUndo(w, mp) else m.remove(a)
        }
        zeroUndo.value = m
        return link.dialect.zeroAxes(which)
    }

    // ---- typed move (long-press a readout row) ------------------------------------------------
    /** The lines a typed move would send, or why it can't run. */
    data class MovePlan(val lines: List<String>, val error: String?, val endWork: Double? = null)

    /**
     * [relative] false = go to work position [value]; true = move by [value]. Rapid (G0).
     * Refuses targets past the soft limits (the stock firmware halts on those) when soft-limit
     * clamping is on, the same switch the dial uses.
     */
    fun planMove(axis: Char, value: Double, relative: Boolean): MovePlan {
        val st = link.status.value ?: return MovePlan(emptyList(), "No status from the machine yet")
        val i = axes.indexOf(axis)
        val m = st.mpos.getOrNull(i); val w = st.wpos.getOrNull(i)
        if (m == null || w == null) return MovePlan(emptyList(), "No $axis position from the machine")
        val endM = if (relative) m + value else value + (m - w)
        val endW = endM - (m - w)
        val lim = jog.config.limits[axis]
        if (softClamp.value && st.homed != false && lim != null) {
            val lo = lim.softMin; val hi = lim.softMax
            if ((lo != null && endM < lo - 1e-6) || (hi != null && endM > hi + 1e-6)) {
                fun f(d: Double?) = d?.let { "%.3f".format(java.util.Locale.US, it) } ?: "…"
                return MovePlan(emptyList(), "Past the soft limit: would end at machine $axis ${f(endM)} " +
                    "(allowed ${f(lo)} to ${f(hi)})", endW)
            }
        }
        val v = java.math.BigDecimal(value).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        val lines = if (relative) listOf("G91 G0 $axis$v", "G90") else listOf("G90 G0 $axis$v")
        return MovePlan(lines, null, endW)
    }

    fun runMove(axis: Char, value: Double, relative: Boolean) {
        val st = link.status.value
        val why = when {
            link.conn.value != ConnState.Connected -> "not connected"
            st == null || !st.isIdle -> "machine is ${st?.state ?: "unknown"}, not Idle"
            runner.running.value != null -> "“${runner.running.value!!.label}” is still running"
            else -> planMove(axis, value, relative).error
        }
        if (why != null) { snack.tryEmit("Move $axis: $why"); haptics.thud(); return }
        haptics.press()
        log("▶ move $axis: ${if (relative) "by" else "to"} $value")
        planMove(axis, value, relative).lines.forEach { link.sendLine(it) }
    }
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
            // Zero hotkeys toggle like the zero buttons: pressed again with nothing moved = undo.
            BuiltIn.ZERO_SELECTED -> zeroLines(axis.value.toString())
            BuiltIn.ZERO_X -> zeroLines("X"); BuiltIn.ZERO_Y -> zeroLines("Y")
            BuiltIn.ZERO_Z -> zeroLines("Z"); BuiltIn.ZERO_A -> zeroLines("A")
            BuiltIn.ZERO_XY -> zeroLines("XY"); BuiltIn.ZERO_XYZ -> zeroLines("XYZ")
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
            BuiltIn.LIGHT_TOGGLE -> toggleOutput("light") { d.light(it) }
            BuiltIn.VACUUM_TOGGLE -> toggleOutput("vacuum") { d.vacuum(it) }
        }
    }

    /** Flip an on/off output. The tile flips now; the diagnose read-back confirms it. Unknown = turn on. */
    private fun toggleOutput(name: String, cmd: (Boolean) -> List<String>): List<String> {
        val on = link.outputs.value[name] == true
        link.assumeOutput(name, !on)
        viewModelScope.launch { kotlinx.coroutines.delay(1200); link.refreshOutputs() }
        return cmd(!on)
    }

    // ---- hotkeys ------------------------------------------------------------------------------
    private fun loadHotkeys(): List<Hotkey> = shownFirst(try {
        if (hotkeyFile.exists()) migrate(HotkeyJson.import(hotkeyFile.readText())) else Hotkey.defaults()
    } catch (_: Exception) { Hotkey.defaults() })

    /** Grid order rule: hotkeys shown in the grid always come first, hidden (button-only) ones after. */
    private fun shownFirst(l: List<Hotkey>) = l.filter { it.showOnMain } + l.filterNot { it.showOnMain }

    /**
     * Old defaults had separate on/off hotkeys: "Light on" (M821) + "Light off" (M822), and
     * "Vacuum on" (M851) + "Vacuum off" (M852). Fold each pair into one toggle, kept where the
     * "on" one was, with both sets of button bindings. Shown in the grid if either one was.
     */
    private fun migrate(list: List<Hotkey>): List<Hotkey> {
        fun isOnly(h: Hotkey, code: String) = h.steps.size == 1 && (h.steps[0] as? com.z1pendant.hotkeys.Step.GCode)?.text?.trim() == code
        fun fold(l: List<Hotkey>, onLabel: String, onCode: String, offLabel: String, offCode: String,
                 label: String, action: BuiltIn): List<Hotkey> {
            val on = l.indexOfFirst { it.label == onLabel && isOnly(it, onCode) }
            val off = l.indexOfFirst { it.label == offLabel && isOnly(it, offCode) }
            if (on < 0 || off < 0) return l
            val toggle = l[on].copy(label = label, kind = com.z1pendant.hotkeys.Kind.BUILTIN,
                steps = listOf(com.z1pendant.hotkeys.Step.Builtin(action)),
                showOnMain = l[on].showOnMain || l[off].showOnMain,
                triggers = l[on].triggers + l[off].triggers)
            return l.mapIndexedNotNull { i, h -> when (i) { on -> toggle; off -> null; else -> h } }
        }
        // Old "Z up" / "Go to XY0" defaults raised Z with G53 G0 Z0, which is past the soft limit
        // (top = -1) and halts the machine. Rewrite exactly that line to the safe height.
        val zFix = Regex("""^\s*G53\s*G0\s*Z0(\.0*)?\s*$""", RegexOption.IGNORE_CASE)
        fun fixZ(h: Hotkey) = h.copy(steps = h.steps.map { st ->
            if (st is com.z1pendant.hotkeys.Step.GCode)
                st.copy(text = st.text.lines().joinToString("\n") { if (zFix.matches(it)) "G53 G0 Z${Hotkey.SAFE_Z}" else it })
            else st
        })
        val out = fold(fold(list, "Light on", "M821", "Light off", "M822", "Light", BuiltIn.LIGHT_TOGGLE),
            "Vacuum on", "M851", "Vacuum off", "M852", "Vacuum", BuiltIn.VACUUM_TOGGLE).map(::fixZ)
        if (out != list) hotkeyFile.writeText(HotkeyJson.export(out))
        return out
    }

    private fun saveHotkeys(all: List<Hotkey>) {
        val list = shownFirst(all)
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
        // Same order the grid shows (shown first), so `to` means the slot under the finger.
        // A shown tile stays among the shown ones, a hidden tile among the hidden ones.
        val byId = hotkeys.value.associateBy { it.id }
        val l = (arrangeOrder.value ?: return).let { ids ->
            ids.filter { byId[it]?.showOnMain == true } + ids.filterNot { byId[it]?.showOnMain == true }
        }.toMutableList()
        val from = l.indexOf(id); if (from < 0) return
        val nShown = l.count { byId[it]?.showOnMain == true }
        val range = if (byId[id]?.showOnMain == true) 0 until nShown else nShown..l.lastIndex
        if (range.isEmpty()) return
        val dest = to.coerceIn(range.first, range.last)
        if (dest == from) return                       // dragged past the shown/hidden boundary: stays put
        l.add(dest, l.removeAt(from))
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
        runNow(h)
    }
    fun confirmRun() { confirmFor.value?.let { haptics.press(); runNow(it) }; confirmFor.value = null }
    /** Console marks which hotkey the commands that follow belong to. */
    private fun runNow(h: Hotkey) { log("▶ hotkey: ${h.label}"); runner.run(h) }

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
