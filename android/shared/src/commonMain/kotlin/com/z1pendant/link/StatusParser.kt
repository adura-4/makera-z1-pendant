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
        var job: JobProgress? = null; var toolTarget: Int? = null; var tlo: Double? = null
        var feedActual: Double? = null; var feedSet: Double? = null
        var sFields: List<String>? = null; var mesh: Double? = null; var halt: Int? = null
        for (p in parts.drop(1)) {
            val c = p.indexOf(':'); if (c < 0) continue
            val k = p.substring(0, c); val v = p.substring(c + 1).split(',').map { it.trim() }
            when (k) {
                "MPos" -> mpos = v.mapNotNull { it.toDoubleOrNull() }
                "WPos" -> wpos = v.mapNotNull { it.toDoubleOrNull() }
                "F" -> {                                                // actual, set, override %
                    feedActual = v.getOrNull(0)?.toDoubleOrNull(); feedSet = v.getOrNull(1)?.toDoubleOrNull()
                    fro = v.getOrNull(2)?.toDoubleOrNull()
                }
                "S" -> {                                                // current, target, override %, …
                    rpm = v.getOrNull(0)?.toDoubleOrNull()
                    sTarget = v.getOrNull(1)?.toDoubleOrNull(); sOverride = v.getOrNull(2)?.toDoubleOrNull()
                    sFields = v
                }
                "O" -> mesh = v.getOrNull(0)?.toDoubleOrNull()
                "T" -> {                                                // active tool, tool offset, target tool, …
                    tool = v.getOrNull(0)?.toIntOrNull(); tlo = v.getOrNull(1)?.toDoubleOrNull()
                    toolTarget = v.getOrNull(2)?.toIntOrNull()
                }
                // Job progress: line, percent, elapsed s. The Wi-Fi board has a canned fallback status
                // with P:1234,50,1200 that it can send when it has no real one: not a real job.
                "P" -> if (v.size >= 3 && v.take(3) != listOf("1234", "50", "1200")) {
                    val l = v[0].toLongOrNull(); val pc = v[1].toIntOrNull(); val e = v[2].toLongOrNull()
                    if (l != null && pc != null && e != null) job = JobProgress(l, pc, e)
                }
                // "H" is the halt reason (only present while halted), NOT a homed flag. The stock
                // firmware has no homed field, so homed stays null (= unknown).
                "H" -> halt = v.getOrNull(0)?.toIntOrNull()
            }
        }
        if (wpos.isEmpty()) wpos = mpos
        return MachineStatus(parts[0], mpos, wpos, fro, rpm, homed, text, atMs, tool, sTarget, sOverride, job, toolTarget, tlo, feedActual, feedSet, sFields, mesh, halt)
    }
}
