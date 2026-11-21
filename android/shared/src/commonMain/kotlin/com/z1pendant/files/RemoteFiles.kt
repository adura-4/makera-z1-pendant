package com.z1pendant.files

import com.z1pendant.link.RemoteEntry

/**
 * The Z1's `ls -s` listing and the names and labels the Files screen shows
 * (formats measured on the Z1: reference/file_commands_notes.md in the Makera Z1 Upgrades project).
 */
object RemoteFiles {
    const val ROOT = "/sd/gcodes"

    /** Folders: `name/ 0 YYYYMMDDhhmmss`; files: `name  size YYYYMMDDhhmmss`. Spaces in names arrive as 0x01. */
    private val dirLine = Regex("""^(.+)/ (\d+) (\d{14})$""")
    private val fileLine = Regex("""^(.+?)  (\d+) (\d{14})$""")

    /** Parse the text of all 0x83 chunks. The Z1's own `.md5` and `.lz` caches (anything starting with '.') are left out. */
    fun parseListing(text: String): List<RemoteEntry> = text.split('\n').mapNotNull { raw ->
        val line = raw.trimEnd('\r')
        if (line.isBlank()) return@mapNotNull null
        val d = dirLine.matchEntire(line)
        val e = when {
            d != null -> RemoteEntry(d.groupValues[1].replace('\u0001', ' '), true, 0, d.groupValues[3])
            else -> fileLine.matchEntire(line)?.let {
                RemoteEntry(it.groupValues[1].replace('\u0001', ' '), false, it.groupValues[2].toLong(), it.groupValues[3])
            }
        } ?: return@mapNotNull null
        e.takeUnless { it.name.startsWith(".") }
    }

    enum class Sort { Newest, Name, Size }

    /** Folders first, then files in the chosen order. */
    fun sorted(entries: List<RemoteEntry>, sort: Sort): List<RemoteEntry> {
        val byName = compareBy<RemoteEntry> { it.name.lowercase() }
        val files = entries.filter { !it.isDir }.sortedWith(when (sort) {
            Sort.Newest -> compareByDescending<RemoteEntry> { it.modified }.then(byName)
            Sort.Name -> byName
            Sort.Size -> compareByDescending<RemoteEntry> { it.size }.then(byName)
        })
        return entries.filter { it.isDir }.sortedWith(byName) + files
    }

    fun child(dir: String, name: String) = dir.trimEnd('/') + "/" + name
    fun parent(path: String) = path.trimEnd('/').substringBeforeLast('/').ifEmpty { ROOT }

    /** The path below /sd/gcodes as crumbs: "/sd/gcodes/a/b" -> [("gcodes", root), ("a", ...), ("b", ...)]. */
    fun crumbs(path: String): List<Pair<String, String>> {
        val rel = path.removePrefix(ROOT).trim('/')
        val out = mutableListOf("gcodes" to ROOT)
        var p = ROOT
        if (rel.isNotEmpty()) for (part in rel.split('/')) { p = "$p/$part"; out += part to p }
        return out
    }

    /** A name the Z1 takes and Makera Studio shows properly: spaces become _ (Studio draws nothing for them),
     *  and characters the Z1 can't take (?*!~\ / and anything outside printable ASCII) become _ too. */
    fun cleanName(name: String): String = buildString {
        for (c in name.trim()) append(if (c == ' ' || c in "?*!~\\/" || c.code < 0x20 || c.code > 0x7E) '_' else c)
    }

    /** "name.cnc" taken -> "name_2.cnc", then _3, ... */
    fun uniqueName(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot); val ext = name.substring(dot)
        var n = 2
        while ("${base}_$n$ext" in taken) n++
        return "${base}_$n$ext"
    }

    fun sizeLabel(bytes: Long): String = when {
        bytes < 1024 -> "$bytes bytes"
        bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
        else -> {
            val tenths = (bytes * 10 + 512 * 1024) / (1024 * 1024)
            "${tenths / 10}.${tenths % 10} MB"
        }
    }

    /** Unix seconds for a UTC "YYYYMMDDhhmmss", or null if it isn't one. */
    fun epochOf(stamp: String): Long? {
        if (stamp.length != 14 || !stamp.all { it.isDigit() }) return null
        val y = stamp.substring(0, 4).toInt(); val mo = stamp.substring(4, 6).toInt(); val d = stamp.substring(6, 8).toInt()
        val h = stamp.substring(8, 10).toInt(); val mi = stamp.substring(10, 12).toInt(); val s = stamp.substring(12, 14).toInt()
        if (mo !in 1..12 || d !in 1..31) return null
        return daysFromCivil(y, mo, d) * 86400 + h * 3600L + mi * 60L + s
    }

    /** Days since 1970-01-01 (Howard Hinnant's algorithm). */
    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468
    }

    private fun civilFromDays(z0: Long): Triple<Int, Int, Int> {
        val z = z0 + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
        return Triple(y, m, d)
    }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /**
     * "today 17:04", "yesterday 09:12", "Nov 9", "Aug 27, 2025", or "date unknown" (files the Z1 wrote
     * before its clock was set say 1980). [offsetOf] gives the phone's UTC offset at a moment, seconds.
     */
    fun timeLabel(stamp: String, nowEpoch: Long, offsetOf: (Long) -> Int): String {
        val utc = epochOf(stamp) ?: return ""
        if (stamp.startsWith("1980")) return "date unknown"
        val local = utc + offsetOf(utc)
        val nowLocal = nowEpoch + offsetOf(nowEpoch)
        val day = local.floorDiv(86400L); val today = nowLocal.floorDiv(86400L)
        val secs = local - day * 86400
        val hm = "${(secs / 3600).toString().padStart(2, '0')}:${(secs % 3600 / 60).toString().padStart(2, '0')}"
        val (y, m, d) = civilFromDays(day)
        val (ny, _, _) = civilFromDays(today)
        return when {
            day == today -> "today $hm"
            day == today - 1 -> "yesterday $hm"
            y == ny -> "${MONTHS[m - 1]} $d"
            else -> "${MONTHS[m - 1]} $d, $y"
        }
    }
}
