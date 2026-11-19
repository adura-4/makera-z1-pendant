package com.z1pendant.util

/**
 * "Hide serial number" (Settings): the Z1 announces itself as e.g. `Makera_Z1P_123456`, and that
 * number shouldn't end up in screen recordings or screenshots. Masked only where it's shown.
 */
object Serial {
    private val named = Regex("""(?i)(makera[_ ]?z1p?)[_ -]?\d{4,}""")

    /** `Makera_Z1P_123456` → `Makera_Z1P`; anything else unchanged. */
    fun hide(s: String): String = named.replace(s) { it.groupValues[1] }

    fun shown(s: String, hide: Boolean): String = if (hide) hide(s) else s
}
