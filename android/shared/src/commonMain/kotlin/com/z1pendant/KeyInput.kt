package com.z1pendant

/**
 * A hardware button event, platform-neutral. Key codes are Android `KeyEvent` codes (they are
 * stored in hotkeys.json triggers); another platform maps its keys onto them.
 */
data class KeyInput(
    val keyCode: Int,
    val action: KeyAction,
    val repeatCount: Int,
    /** Event-clock times (ms) of the first down and of this event. */
    val downTime: Long,
    val eventTime: Long,
    val isLongPress: Boolean,
    /** Input device name (e.g. a Bluetooth remote), if known. */
    val deviceName: String?,
    /** Readable key name, e.g. "Volume up", "Button a". */
    val label: String,
    val isGamepadButton: Boolean,
)

enum class KeyAction { Down, Up, Other }

/** The Android key codes the app treats specially. */
object KeyCodes {
    const val HOME = 3
    const val BACK = 4
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
}
