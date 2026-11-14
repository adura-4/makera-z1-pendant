package com.z1pendant.hotkeys

import com.z1pendant.hotkeys.JsonCompat.getInt
import com.z1pendant.hotkeys.JsonCompat.getObject
import com.z1pendant.hotkeys.JsonCompat.getString
import com.z1pendant.hotkeys.JsonCompat.isNull
import com.z1pendant.hotkeys.JsonCompat.optArray
import com.z1pendant.hotkeys.JsonCompat.optBoolean
import com.z1pendant.hotkeys.JsonCompat.optLong
import com.z1pendant.hotkeys.JsonCompat.optString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/** App-side actions that aren't plain G-code (or whose G-code depends on the backend). */
enum class BuiltIn(val label: String) {
    HOME("Home all"),
    UNLOCK("Unlock (clear alarm)"),
    ZERO_SELECTED("Zero selected axis"),
    ZERO_X("Zero X"), ZERO_Y("Zero Y"), ZERO_Z("Zero Z"), ZERO_A("Zero A"),
    ZERO_XY("Zero X and Y"), ZERO_XYZ("Zero X, Y and Z"),
    HOLD("Feed hold"), RESUME("Resume"), RESET("Reset / abort"),
    JOG_PLUS("Jog + one click"), JOG_MINUS("Jog − one click"),
    NEXT_AXIS("Next axis"), AXIS_X("Select X"), AXIS_Y("Select Y"), AXIS_Z("Select Z"), AXIS_A("Select A"),
    STEP_UP("Bigger step"), STEP_DOWN("Smaller step"),
    FEED_UP("Feed override +10%"), FEED_DOWN("Feed override −10%"), FEED_100("Feed override 100%"),
    LIGHT_TOGGLE("Light on/off"), VACUUM_TOGGLE("Vacuum on/off"),
}

sealed class Step {
    data class Builtin(val action: BuiltIn) : Step()
    data class GCode(val text: String) : Step()
    data object WaitIdle : Step()
    data class Delay(val ms: Long) : Step()
    data class RunHotkey(val id: String) : Step()

    fun describe(lookup: (String) -> String?): String = when (this) {
        is Builtin -> action.label
        is GCode -> text.lines().filter { it.isNotBlank() }.let { if (it.size <= 1) it.firstOrNull() ?: "(empty)" else "${it.first()} … (${it.size} lines)" }
        WaitIdle -> "Wait until idle"
        is Delay -> "Wait ${ms} ms"
        is RunHotkey -> "Run “${lookup(id) ?: "missing"}”"
    }

    fun toJson(): JsonObject = when (this) {
        is Builtin -> buildJsonObject { put("t", "builtin"); put("action", action.name) }
        is GCode -> buildJsonObject { put("t", "gcode"); put("text", text) }
        WaitIdle -> buildJsonObject { put("t", "waitIdle") }
        is Delay -> buildJsonObject { put("t", "delay"); put("ms", ms) }
        is RunHotkey -> buildJsonObject { put("t", "run"); put("id", id) }
    }

    companion object {
        fun fromJson(o: JsonObject): Step? = when (o.optString("t")) {
            "builtin" -> runCatching { Builtin(BuiltIn.valueOf(o.getString("action"))) }.getOrNull()
            "gcode" -> GCode(o.optString("text"))
            "waitIdle" -> WaitIdle
            "delay" -> Delay(o.optLong("ms", 500))
            "run" -> RunHotkey(o.optString("id"))
            else -> null
        }
    }
}

enum class Kind { BUILTIN, GCODE, SEQUENCE }

/**
 * A physical button bound to a hotkey. [keyCode] is Android's KeyEvent code (volume keys, gamepad
 * buttons, Bluetooth remotes and USB keyboards / a Pico-as-HID pendant all arrive as key events).
 * [device] is the input device name, or null to match the key from any device.
 */
data class Trigger(val keyCode: Int, val longPress: Boolean, val device: String?, val label: String) {
    fun toJson() = buildJsonObject {
        put("keyCode", keyCode); put("longPress", longPress)
        put("device", device?.let { JsonPrimitive(it) } ?: JsonNull); put("label", label)
    }

    companion object {
        fun fromJson(o: JsonObject) = Trigger(o.getInt("keyCode"), o.optBoolean("longPress"),
            if (o.isNull("device")) null else o.optString("device"), o.optString("label"))
    }
}

data class Hotkey(
    val id: String = Uuid.random().toString(),
    val label: String,
    /** "mi:<IconName>" for a built-in icon, or "tx:<text>" for an emoji / short text. */
    val icon: String,
    val kind: Kind,
    val steps: List<Step>,
    val confirm: Boolean = false,
    val onlyIdle: Boolean = true,
    val showOnMain: Boolean = true,
    val triggers: List<Trigger> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id); put("label", label); put("icon", icon); put("kind", kind.name)
        put("steps", JsonArray(steps.map { it.toJson() }))
        put("confirm", confirm); put("onlyIdle", onlyIdle); put("showOnMain", showOnMain)
        put("triggers", JsonArray(triggers.map { it.toJson() }))
    }

    companion object {
        /**
         * Machine Z for "Z up" moves. The Z1's soft limit top is homed MPos -1 (confirmed: a move
         * past -1 alarms "Soft Endstop ... was exceeded"), so G53 Z0 halts the machine. -2 leaves 1 mm.
         */
        const val SAFE_Z = "-2"
        fun fromJson(o: JsonObject): Hotkey {
            val steps = o.optArray("steps")?.let { a -> a.indices.mapNotNull { Step.fromJson(a.getObject(it)) } } ?: emptyList()
            val trig = o.optArray("triggers")?.let { a -> a.indices.map { Trigger.fromJson(a.getObject(it)) } } ?: emptyList()
            return Hotkey(
                id = o.optString("id").ifEmpty { Uuid.random().toString() },
                label = o.optString("label", "?"), icon = o.optString("icon", "tx:?"),
                kind = runCatching { Kind.valueOf(o.getString("kind")) }.getOrDefault(Kind.SEQUENCE),
                steps = steps, confirm = o.optBoolean("confirm"), onlyIdle = o.optBoolean("onlyIdle", true),
                showOnMain = o.optBoolean("showOnMain", true), triggers = trig,
            )
        }

        fun defaults(): List<Hotkey> = listOf(
            Hotkey(label = "Home", icon = "mi:Home", kind = Kind.BUILTIN, steps = listOf(Step.Builtin(BuiltIn.HOME)), confirm = true),
            Hotkey(label = "Unlock", icon = "mi:LockOpen", kind = Kind.BUILTIN, steps = listOf(Step.Builtin(BuiltIn.UNLOCK)), onlyIdle = false),
            Hotkey(label = "Zero XY", icon = "mi:GpsFixed", kind = Kind.BUILTIN, steps = listOf(Step.Builtin(BuiltIn.ZERO_XY))),
            Hotkey(label = "Zero Z", icon = "mi:VerticalAlignBottom", kind = Kind.BUILTIN, steps = listOf(Step.Builtin(BuiltIn.ZERO_Z))),
            Hotkey(label = "Go to XY0", icon = "mi:CenterFocusStrong", kind = Kind.GCODE,
                steps = listOf(Step.GCode("G90\nG53 G0 Z$SAFE_Z\nG0 X0 Y0")), confirm = true),
            Hotkey(label = "Z up", icon = "mi:VerticalAlignTop", kind = Kind.GCODE, steps = listOf(Step.GCode("G90\nG53 G0 Z$SAFE_Z"))),
            Hotkey(label = "Light", icon = "mi:Lightbulb", kind = Kind.BUILTIN, steps = listOf(Step.Builtin(BuiltIn.LIGHT_TOGGLE)), onlyIdle = false),
            Hotkey(label = "Vacuum", icon = "mi:Air", kind = Kind.BUILTIN, steps = listOf(Step.Builtin(BuiltIn.VACUUM_TOGGLE)), onlyIdle = false),
            Hotkey(label = "Spindle stop", icon = "mi:Stop", kind = Kind.GCODE, steps = listOf(Step.GCode("M5")), onlyIdle = false),
            Hotkey(label = "Next axis", icon = "mi:SwapHoriz", kind = Kind.BUILTIN, steps = listOf(Step.Builtin(BuiltIn.NEXT_AXIS)), onlyIdle = false, showOnMain = false),
        )
    }
}

/**
 * hotkeys.json / exported hotkey files: `{"format": "z1pendant-hotkeys", "version": 1, "hotkeys": [...]}`,
 * pretty-printed with a 2-space indent (as org.json's `toString(2)` wrote it before).
 */
object HotkeyJson {
    const val FORMAT = "z1pendant-hotkeys"

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }
    /** org.json's parser was forgiving about hand-edited files; stay close to that. */
    @OptIn(ExperimentalSerializationApi::class)
    private val reader = Json { isLenient = true; allowTrailingComma = true; allowComments = true }

    fun export(list: List<Hotkey>): String = json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("format", FORMAT); put("version", 1)
        put("hotkeys", JsonArray(list.map { it.toJson() }))
    })

    fun import(text: String): List<Hotkey> {
        val o = reader.parseToJsonElement(text).jsonObject
        require(o.optString("format") == FORMAT) { "Not a Z1 Pendant hotkey file" }
        val a = o["hotkeys"] as? JsonArray ?: throw IllegalArgumentException("No hotkeys array")
        return a.indices.map { Hotkey.fromJson(a.getObject(it)) }
    }
}
