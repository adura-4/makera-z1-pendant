package com.z1pendant.link

/** Parses Smoothie/Makera status: <Idle|MPos:x,y,z,a,b|WPos:...|F:feed,set,override|S:rpm,...> */
object StatusParser {
    fun parse(text: String, atMs: Long): MachineStatus? {
        val s = text.indexOf('<'); val e = text.indexOf('>', s + 1)
        if (s < 0 || e < 0) return null
        val parts = text.substring(s + 1, e).split('|')
        var mpos = emptyList<Double>(); var wpos = emptyList<Double>()
        var fro: Double? = null; var rpm: Double? = null; var homed: Boolean? = null; var tool: Int? = null
        var sTarget: Double? = null; var sOverride: Double? = null
        for (p in parts.drop(1)) {
            val c = p.indexOf(':'); if (c < 0) continue
            val k = p.substring(0, c); val v = p.substring(c + 1).split(',').map { it.trim() }
            when (k) {
                "MPos" -> mpos = v.mapNotNull { it.toDoubleOrNull() }
                "WPos" -> wpos = v.mapNotNull { it.toDoubleOrNull() }
                "F" -> fro = v.getOrNull(2)?.toDoubleOrNull()
                "S" -> {                                                // current, target, override %, …
                    rpm = v.getOrNull(0)?.toDoubleOrNull()
                    sTarget = v.getOrNull(1)?.toDoubleOrNull(); sOverride = v.getOrNull(2)?.toDoubleOrNull()
                }
                "T" -> tool = v.getOrNull(0)?.toIntOrNull()             // active tool, tool offset, target tool, …
                // "H" is the halt reason (only present while halted), NOT a homed flag. The stock
                // firmware has no homed field, so homed stays null (= unknown).
            }
        }
        if (wpos.isEmpty()) wpos = mpos
        return MachineStatus(parts[0], mpos, wpos, fro, rpm, homed, text, atMs, tool, sTarget, sOverride)
    }
}
