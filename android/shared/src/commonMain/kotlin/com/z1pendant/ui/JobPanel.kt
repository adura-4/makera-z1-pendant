package com.z1pendant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.z1pendant.JobTools
import com.z1pendant.JobView
import com.z1pendant.link.MachineStatus
import com.z1pendant.platform.wallClockHms
import com.z1pendant.util.Fmt

private val TrackColor = Color(0xFF0F1A2E)

/** 754 → "12m 34s"; 3_725 → "1h 02m"; 42 → "0m 42s". */
fun fmtDuration(s: Long): String {
    // An hour or more: hours and minutes ("1h 05m"); under an hour: minutes and seconds ("13m 22s",
    // "0m 42s"). Reads as a length of time, not a clock time (Jimmy, 2026-11-18).
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    fun two(v: Long) = v.toString().padStart(2, '0')
    return when {
        h > 0 -> "${h}h ${two(m)}m"
        else -> "${m}m ${two(sec)}s"
    }
}

/** The clock time [inS] seconds from now, "1:47 am" style, from the device's wall clock. */
fun clockIn(inS: Long): Pair<String, String> {
    val p = wallClockHms().split(':').mapNotNull { it.toIntOrNull() }
    if (p.size < 3) return "—" to ""
    val t = ((p[0] * 3600 + p[1] * 60 + p[2]) + inS) % 86_400
    val h24 = (t / 3600).toInt(); val m = ((t % 3600) / 60).toInt()
    val h12 = if (h24 % 12 == 0) 12 else h24 % 12
    return "$h12:${m.toString().padStart(2, '0')}" to (if (h24 < 12) "am" else "pm")
}

@Composable
private fun ProgressBar(percent: Int, height: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(modifier.height(height).clip(RoundedCornerShape(height / 2)).background(TrackColor)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(percent.coerceIn(0, 100) / 100f)
            .clip(RoundedCornerShape(height / 2)).background(MaterialTheme.colorScheme.primary))
    }
}

/** The slim job line at the top of the open drawer: percent, a thin bar, time left. Tap (or swipe up) for details. */
@Composable
fun JobLine(job: JobView, onOpen: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpen).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Description, null, Modifier.size(16.dp), tint = muted)
        Text("${job.percent}%", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        ProgressBar(job.percent, 6.dp, Modifier.weight(1f))
        Text(job.leftS?.let { "${fmtDuration(it)} left" } ?: fmtDuration(job.progress.elapsedS), fontSize = 12.5.sp, color = muted)
        Icon(Icons.Filled.ExpandLess, "Job details", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Cell(title: String, value: String, small: String? = null, valueColor: Color = Color.Unspecified, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
        .padding(horizontal = 9.dp, vertical = 6.dp)) {
        Text(title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, color = valueColor)
            if (small != null) Text(small, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, modifier = Modifier.padding(start = 3.dp, bottom = 1.dp))
        }
    }
}

/**
 * Job details: file, percent and line, time, feeds and speeds, the tool change being waited on,
 * and the tool list (from the job file's header, when the file could be read).
 */
@Composable
fun JobDetails(job: JobView, st: MachineStatus?, tools: JobTools?, onMesh: () -> Unit = {}, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val p = job.progress
    val toolChange = st?.state.equals("Tool", true)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClose).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Description, null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
            Text(job.file?.name ?: "Job", fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ExpandMore, "Back", Modifier.size(22.dp), tint = cs.primary)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${job.percent}%", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 34.sp)
            Text("line ${Fmt.thousands(p.line.toInt())}" + (tools?.lines?.let { " of ${Fmt.thousands(it)}" } ?: ""),
                fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
        ProgressBar(job.percent, 12.dp, Modifier.fillMaxWidth())
        // A leveling grid is active (|O:): this job's mesh, tap to see it.
        st?.mesh?.let { m ->
            Text("▦ Mesh ${Fmt.fixed(m, 2)} mm", fontSize = 13.sp, color = Color(0xFFFACC15), fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF3A2F0B)).clickable(onClick = onMesh)
                    .padding(horizontal = 10.dp, vertical = 4.dp))
        }
        if (toolChange) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(cs.secondaryContainer)) {
                Box(Modifier.width(4.dp).height(52.dp).background(cs.primary))
                Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Text("Tool change: put in T${st?.toolTarget ?: "?"}", color = cs.primary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Confirm on the machine, in Studio, or in the popup here.", fontSize = 13.sp, color = cs.onSurfaceVariant)
                }
            }
        }
        val left = job.leftS
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Cell("Elapsed", fmtDuration(p.elapsedS), modifier = Modifier.weight(1f))
            Cell("Left (est.)", left?.let { fmtDuration(it) } ?: "—", modifier = Modifier.weight(1f))
            val (clk, ampm) = if (left != null) clockIn(left) else ("—" to "")
            Cell("Done at", clk, ampm.ifEmpty { null }, modifier = Modifier.weight(1f))
        }
        if (!toolChange) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Cell("Feed", st?.feedActual?.let { Fmt.thousands(it.toInt()) } ?: "—",
                st?.feedSet?.let { "/${Fmt.thousands(it.toInt())}" }, modifier = Modifier.weight(1f))
            Cell("Spindle", st?.spindleRpm?.let { Fmt.thousands(it.toInt()) } ?: "—",
                st?.spindleTarget?.let { t -> "/" + if (t >= 1000 && t.toInt() % 1000 == 0) "${t.toInt() / 1000}k" else Fmt.thousands(t.toInt()) },
                modifier = Modifier.weight(1f))
            Cell("State", st?.state ?: "—", valueColor = Color(0xFF60A5FA), modifier = Modifier.weight(1f))
        }
        ToolList(tools, st, p.line, p.elapsedS, p.percent, job.pace)
    }
}

/**
 * Tool changes in the job, in order. Collapsed: the order as chips, then Now / Next with their
 * descriptions; tap for every change with done / now / roughly when. Without the job file: just
 * the tool in the collet.
 */
@Composable
private fun ToolList(tools: JobTools?, st: MachineStatus?, line: Long, elapsedS: Long, percent: Int, pace: Double = 1.0) {
    val cs = MaterialTheme.colorScheme
    var all by remember { mutableStateOf(false) }
    val box = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(cs.secondaryContainer)
    if (tools == null || tools.changes.isEmpty()) {
        val t = st?.tool
        if (t != null && t > 0) Row(box.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Now", fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.width(40.dp))
            Text("T$t", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = cs.primary)
        }
        return
    }
    // Which change we're in: the last one at or before the current line (or the one being waited on).
    val waiting = st?.state.equals("Tool", true)
    var cur = tools.changes.indexOfLast { it.line <= line }
    if (waiting) cur = tools.changes.indexOfFirst { it.line >= line }.let { if (it < 0) tools.changes.lastIndex else it }
    // "in ~N min": the estimated time from here to that change (JobTime) × the pace so far; without
    // the time table, seconds per file line so far.
    fun whenText(i: Int): String = when {
        i < cur -> "done"
        i == cur -> "now"
        tools.time != null -> {
            val s = (tools.time.between(line.coerceAtLeast(0), tools.changes[i].line, st?.feedOverride) * pace).toLong()
            if (s < 60) "~1 min" else "~${s / 60} min"
        }
        line <= 0 || elapsedS <= 0 -> ""
        else -> {
            val s = (tools.changes[i].line - line) * elapsedS / line
            if (s < 60) "~1 min" else "~${s / 60} min"
        }
    }
    Column(box.clickable { all = !all }.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (!all) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                tools.changes.forEachIndexed { i, c ->
                    if (i > 0) Text("›", color = cs.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 14.sp)
                    val done = i < cur; val now = i == cur
                    Text("T${c.tool}", fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                        fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal,
                        color = when { now -> cs.primary; done -> cs.onSurfaceVariant.copy(alpha = 0.55f); else -> cs.onSurface },
                        textDecoration = if (done) TextDecoration.LineThrough else null,
                        modifier = Modifier.then(if (now) Modifier.border(1.dp, cs.primary, RoundedCornerShape(6.dp)) else Modifier)
                            .padding(horizontal = 6.dp, vertical = 1.dp))
                }
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.ExpandMore, "All tools", Modifier.size(18.dp), tint = cs.primary)
            }
            if (cur >= 0) ToolLine("Now", tools.changes[cur].tool, tools.describe(tools.changes[cur].tool), null)
            if (cur + 1 <= tools.changes.lastIndex) {
                val n = tools.changes[cur + 1]
                ToolLine("Next", n.tool, tools.describe(n.tool), whenText(cur + 1).ifEmpty { null })
            }
        } else {
            tools.changes.forEachIndexed { i, c ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(when { i < cur -> "✓"; i == cur -> "▶"; else -> " " }, color = if (i == cur) cs.primary else cs.onSurfaceVariant,
                        fontSize = 13.sp, modifier = Modifier.width(14.dp))
                    val dim = if (i < cur) 0.45f else 1f
                    Text("T${c.tool}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                        color = cs.primary.copy(alpha = dim), modifier = Modifier.width(30.dp))
                    Text(tools.describe(c.tool) ?: "—", fontSize = 12.5.sp, color = cs.onSurface.copy(alpha = dim), modifier = Modifier.weight(1f))
                    Text(whenText(i), fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
                }
            }
            Row { Spacer(Modifier.weight(1f)); Icon(Icons.Filled.ExpandLess, "Fewer", Modifier.size(18.dp), tint = cs.primary) }
        }
    }
}

@Composable
private fun ToolLine(tag: String, tool: Int, desc: String?, whenText: String?) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tag, fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.width(34.dp).padding(top = 2.dp))
        Text("T$tool", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = cs.primary, modifier = Modifier.width(30.dp))
        Text((desc ?: "—") + (whenText?.let { " · in $it" }?.replace("in now", "now") ?: ""), fontSize = 12.5.sp, modifier = Modifier.weight(1f))
    }
}
