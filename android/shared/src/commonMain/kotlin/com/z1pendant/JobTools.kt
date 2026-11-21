package com.z1pendant

/** One real tool change in a job file: the tool it asks for, at this (1-based) file line. */
data class ToolChange(val tool: Int, val line: Long)

/** What the pendant knows about a job file's tools: the changes in order, descriptions, total lines. */
data class JobTools(val changes: List<ToolChange>, val descriptions: Map<Int, String>, val lines: Int,
                    /** Estimated time per line (JobTime.kt), for the time left. */
                    val time: JobTime? = null) {
    fun describe(tool: Int): String? = descriptions[tool]

    /**
     * Each tool change with its estimated machine time: from its change to the next one (the first
     * also gets anything before it). Null times when there's no estimate.
     */
    fun toolTimes(): List<Pair<ToolChange, Double?>> = changes.mapIndexed { i, c ->
        val t = time
        val to = changes.getOrNull(i + 1)?.line
        c to t?.let { (if (to == null) it.total else it.at(to)) - it.at(if (i == 0) 0L else c.line) }
    }
}

/**
 * Reads tool information from a G-code file's text.
 *
 * Descriptions come from the CAM's header comments:
 * - Fusion 360 (Makera community post): `(T1  Spiral O Metal 3.175*12mm  Makera  D=3.175 SD=… - ZMIN=-5.45 - flat end mill)`
 *   → "Spiral O Metal 3.175*12mm Makera · flat end mill"
 * - Makera Studio / MakeraCAM: `;@MKR|TOOL|number=1|…|name=3.175*12mm Flat End(Metal)|type=Flat End|…`
 *   → "3.175*12mm Flat End(Metal)"
 *
 * Tool changes are the `M6` lines with a T word (`T1M6`, `M6 T1`, `T1 M06`), in file order. A change
 * to the tool that's already in is skipped, as the firmware skips it.
 */
object JobFileParser {
    private val fusionTool = Regex("""^\(\s*T(\d+)\s+(.*)\)\s*$""")
    private val mkrTool = Regex("""^;\s*@MKR\|TOOL\|(.*)$""", RegexOption.IGNORE_CASE)
    private val m6 = Regex("""(?i)(?<![A-Z])M0*6(?![0-9.])""")
    private val tWord = Regex("""(?i)(?<![A-Z])T\s*(\d+)""")

    fun parse(text: String): JobTools {
        val desc = LinkedHashMap<Int, String>()
        val changes = ArrayList<ToolChange>()
        var current: Int? = null
        var n = 0L
        for (raw in text.lineSequence()) {
            n++
            val line = raw.trim()
            if (line.isEmpty()) continue
            fusionTool.matchEntire(line)?.let { m ->
                val t = m.groupValues[1].toInt()
                if (t !in desc) fusionDescription(m.groupValues[2])?.let { desc[t] = it }
                return@let
            }
            mkrTool.matchEntire(line)?.let { m ->
                val f = m.groupValues[1].split('|').associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=', "").trim() }
                val t = f["number"]?.toIntOrNull()
                val name = f["name"].orEmpty()
                if (t != null && t !in desc && name.isNotEmpty()) desc[t] = name
            }
            // Code part only: drop ( … ) comments and anything after ';'.
            val code = line.substringBefore(';').replace(Regex("""\([^)]*\)"""), " ")
            if (!m6.containsMatchIn(code)) continue
            val t = tWord.find(code)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (t == current) continue
            changes += ToolChange(t, n)
            current = t
        }
        return JobTools(changes, desc, n.toInt(), runCatching { JobTimeEstimator.estimate(text) }.getOrNull())
    }

    /** "Spiral O Metal 3.175*12mm  Makera    D=3.175 SD=… - ZMIN=-5.45 - flat end mill" → "Spiral O Metal 3.175*12mm Makera · flat end mill". */
    private fun fusionDescription(body: String): String? {
        val parts = body.split(" - ")
        // The name runs up to the geometry (" D=…"); names can contain " - " themselves.
        val name = (if (" D=" in body) body.substringBefore(" D=") else parts[0]).replace(Regex("""\s+"""), " ").trim()
        val type = parts.last().trim().takeIf { parts.size > 1 && !it.startsWith("ZMIN", true) && "D=" !in it }
        return listOfNotNull(name.ifEmpty { null }, type).joinToString(" · ").ifEmpty { null }
    }
}
