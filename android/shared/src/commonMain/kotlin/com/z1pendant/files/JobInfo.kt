package com.z1pendant.files

import com.z1pendant.JobFileParser

/** One tool listed in a job file's header. */
data class ToolInfo(val number: Int, val description: String, val diameterMm: String?)

/**
 * What the Files screen's "Job info" shows: stock height, where Z zero is, and the tools, read from the
 * file's header only (the first few KB; each tool once, in the order the header lists them).
 *
 * Only what Fusion's own post output carries: the Carvera community post writes "Stock Height" and
 * "Z Origin Set To", and Fusion's tool dump writes "(T1 D=3.175 …)". The stock's X x Y size isn't in it.
 */
data class JobInfo(
    /** Stock height in mm from the post's header ("Stock Height : 5.5 mm"), else null. */
    val stockHeightMm: String?,
    /** Where Z zero is, from the post's "Z Origin Set To : Stock Top" ("stock top"), else null. */
    val zero: String?,
    val tools: List<ToolInfo>,
) {
    companion object {
        private val stockHeight = Regex("""Stock Height\s*:\s*([0-9.]+)\s*mm""", RegexOption.IGNORE_CASE)
        private val zOrigin = Regex("""Z Origin Set To\s*:\s*([^)]+?)\s*\)""", RegexOption.IGNORE_CASE)
        private val fusionTool = Regex("""^\(\s*T(\d+)\s+(.*)\)\s*$""")
        private val diameter = Regex("""(?<![A-Z])D=([0-9.]+)""")

        /** True once [text] holds the whole header: a line of actual G-code has appeared after it. */
        fun headerComplete(text: String): Boolean =
            text.split('\n').dropLast(1)                 // the last piece may be a cut-off line
                .any { val l = it.trim(); l.isNotEmpty() && l[0] !in "(;%" }

        /** Parse the header part of a job file. */
        fun parse(header: String): JobInfo {
            val lines = header.lineSequence().map { it.trim() }.toList()
            // The header ends at the first line of G-code; tool lines after that aren't the header's.
            val headerLines = lines.takeWhile { it.isEmpty() || it[0] in "(;%" }
            val text = headerLines.joinToString("\n")
            val tools = JobFileParser.parse(text)
            val diameters = HashMap<Int, String>()
            for (l in headerLines) fusionTool.matchEntire(l)?.let { m ->
                val t = m.groupValues[1].toInt()
                diameter.find(m.groupValues[2])?.let { d -> diameters.getOrPut(t) { trimNumber(d.groupValues[1]) } }
            }
            return JobInfo(
                stockHeightMm = stockHeight.find(text)?.groupValues?.get(1)?.let(::trimNumber),
                zero = zOrigin.find(text)?.groupValues?.get(1)?.lowercase(),
                tools = tools.descriptions.map { (n, d) -> ToolInfo(n, d, diameters[n]) },
            )
        }

        /** "3.175" stays, "6." -> "6", "0.30" -> "0.3". */
        private fun trimNumber(s: String) = if ('.' in s) s.trimEnd('0').trimEnd('.').ifEmpty { "0" } else s
    }
}
