package com.z1pendant.link

/**
 * The machine's name when it was reached by address rather than found by a scan. The Z1 keeps the
 * name its Wi-Fi broadcast uses in its config, `wifi.machine_name Makera_Z1P_024816`, and
 * `config-get sd wifi.machine_name` answers "sd: wifi.machine_name is set to Makera_Z1P_024816" (the
 * same reply form the Run screen reads anchor 1 from). A target typed in as an address carries the
 * address as its name until this fills it in.
 */
object MachineName {
    const val COMMAND = "config-get sd wifi.machine_name"
    private val reply = Regex("""wifi\.machine_name is set to\s+(\S.*?)\s*$""")

    /** The name from a reply line, or null for any other line. */
    fun parse(line: String): String? = reply.find(line.trim())?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    /** Typed in as an address (no scan name yet): ask the machine. */
    fun needsLookup(t: MachineTarget) = t.name.isBlank() || t.name == t.host
}
