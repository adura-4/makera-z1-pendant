package com.z1pendant

import com.z1pendant.hotkeys.BuiltIn
import com.z1pendant.hotkeys.Hotkey
import com.z1pendant.hotkeys.HotkeyRunner
import com.z1pendant.hotkeys.Kind
import com.z1pendant.hotkeys.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Cal Anchor tile: hidden by default, and added once to hotkey sets saved before it existed. */
class AnchorTileTest {
    private fun isCal(h: Hotkey) = h.steps.any { (it as? Step.Builtin)?.action == BuiltIn.CAL_ANCHOR }
    private val oldSet = Hotkey.defaults().filterNot(::isCal)

    @Test fun theDefaultSetEndsWithAHiddenCalAnchorTile() {
        val last = Hotkey.defaults().last()
        assertTrue(isCal(last))
        assertFalse(last.showOnMain)
    }

    @Test fun calAnchorOpensAScreenWithoutTakingTheRunnerSlot() {
        assertTrue(BuiltIn.CAL_ANCHOR in HotkeyRunner.instant)
    }

    @Test fun injectionAddsItToAnExistingSet() {
        val out = Hotkey.withCalAnchor(oldSet)
        assertEquals(oldSet.size + 1, out.size)
        assertEquals(1, out.count(::isCal))
        assertTrue(isCal(out.last()))
        assertEquals(oldSet, out.dropLast(1))
    }

    @Test fun injectionIsIdempotent() {
        val once = Hotkey.withCalAnchor(oldSet)
        assertEquals(once, Hotkey.withCalAnchor(once))
        assertEquals(1, Hotkey.withCalAnchor(once).count(::isCal))
    }

    @Test fun injectionFindsTheTileByItsStepNotByLast() {
        val later = Hotkey(label = "Later", icon = "mi:Home", kind = Kind.BUILTIN, steps = listOf(Step.Builtin(BuiltIn.HOME)))
        val out = Hotkey.withCalAnchor(oldSet, Hotkey.defaults() + later)
        assertEquals(1, out.count(::isCal))
        assertFalse(out.any { it.label == "Later" })
    }

    @Test fun anExistingTileIsLeftAloneEvenWhenTheUserShowsIt() {
        val shown = Hotkey.defaults().map { if (isCal(it)) it.copy(showOnMain = true) else it }
        assertEquals(shown, Hotkey.withCalAnchor(shown))
    }
}
