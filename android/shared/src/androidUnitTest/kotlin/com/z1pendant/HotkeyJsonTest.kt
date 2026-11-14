package com.z1pendant

import com.z1pendant.hotkeys.BuiltIn
import com.z1pendant.hotkeys.Hotkey
import com.z1pendant.hotkeys.HotkeyJson
import com.z1pendant.hotkeys.Kind
import com.z1pendant.hotkeys.Step
import com.z1pendant.hotkeys.Trigger
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * hotkeys.json compatibility: files written by the org.json version must load unchanged, and the
 * new writer must produce the same JSON (same keys, values and nesting).
 */
class HotkeyJsonTest {
    private val sample: List<Hotkey> = Hotkey.defaults() + listOf(
        Hotkey(id = "seq-1", label = "Probe “Z” °", icon = "tx:🔧", kind = Kind.SEQUENCE,
            steps = listOf(Step.Builtin(BuiltIn.HOME), Step.WaitIdle, Step.Delay(750), Step.GCode("G91\nG0 Z5 ; up\nG90"),
                Step.RunHotkey("abc")),
            confirm = true, onlyIdle = false, showOnMain = false,
            triggers = listOf(Trigger(24, false, null, "Volume up"), Trigger(96, true, "8BitDo Micro", "Button a (hold) · 8BitDo Micro"))),
    )

    // ---- the pre-multiplatform org.json writer, verbatim ----
    private fun oldStep(s: Step): JSONObject = when (s) {
        is Step.Builtin -> JSONObject().put("t", "builtin").put("action", s.action.name)
        is Step.GCode -> JSONObject().put("t", "gcode").put("text", s.text)
        Step.WaitIdle -> JSONObject().put("t", "waitIdle")
        is Step.Delay -> JSONObject().put("t", "delay").put("ms", s.ms)
        is Step.RunHotkey -> JSONObject().put("t", "run").put("id", s.id)
    }
    private fun oldTrigger(t: Trigger) = JSONObject().put("keyCode", t.keyCode).put("longPress", t.longPress)
        .put("device", t.device ?: JSONObject.NULL).put("label", t.label)
    private fun oldHotkey(h: Hotkey): JSONObject = JSONObject()
        .put("id", h.id).put("label", h.label).put("icon", h.icon).put("kind", h.kind.name)
        .put("steps", JSONArray(h.steps.map { oldStep(it) }))
        .put("confirm", h.confirm).put("onlyIdle", h.onlyIdle).put("showOnMain", h.showOnMain)
        .put("triggers", JSONArray(h.triggers.map { oldTrigger(it) }))
    private fun oldExport(list: List<Hotkey>): String = JSONObject()
        .put("format", "z1pendant-hotkeys").put("version", 1)
        .put("hotkeys", JSONArray(list.map { oldHotkey(it) })).toString(2)

    @Test fun oldFilesLoad() {
        assertEquals(sample, HotkeyJson.import(oldExport(sample)))
    }

    @Test fun newFilesMatchOldStructure() {
        // JsonObject equality = same keys and values, in any order.
        val new = Json.parseToJsonElement(HotkeyJson.export(sample))
        val old = Json.parseToJsonElement(oldExport(sample))
        assertEquals(old, new)
    }

    @Test fun roundTrip() {
        assertEquals(sample, HotkeyJson.import(HotkeyJson.export(sample)))
    }

    @Test fun layoutIsTwoSpaceIndented() {
        val text = HotkeyJson.export(sample.take(1))
        assertTrue(text, text.startsWith("{\n  \"format\": \"z1pendant-hotkeys\",\n  \"version\": 1,\n  \"hotkeys\": [\n    {\n      \"id\": "))
        assertTrue(text, text.contains("\"triggers\": []"))
    }

    @Test fun lenientValuesReadLikeOrgJson() {
        // Hand-edited file: numbers as strings, booleans as strings, missing fields, unknown step type.
        val text = """{"format":"z1pendant-hotkeys","hotkeys":[{"label":"X","kind":"NOPE","confirm":"true",
            "steps":[{"t":"delay","ms":"250"},{"t":"what"},{"t":"builtin","action":"NOPE"},{"t":"gcode","text":"M5"}],
            "triggers":[{"keyCode":"24","longPress":"TRUE","device":null}]}]}"""
        val h = HotkeyJson.import(text).single()
        assertEquals("X", h.label); assertEquals("tx:?", h.icon); assertEquals(Kind.SEQUENCE, h.kind)
        assertEquals(true, h.confirm); assertEquals(true, h.onlyIdle); assertEquals(true, h.showOnMain)
        assertEquals(listOf(Step.Delay(250), Step.GCode("M5")), h.steps)
        assertEquals(listOf(Trigger(24, true, null, "")), h.triggers)
        assertEquals(36, h.id.length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongFormatRejected() {
        HotkeyJson.import("""{"format":"something-else","hotkeys":[]}""")
    }
}
