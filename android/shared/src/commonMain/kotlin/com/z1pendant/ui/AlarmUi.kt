package com.z1pendant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.z1pendant.AppViewModel
import com.z1pendant.link.MachineStatus
import com.z1pendant.temp.TempLimits
import com.z1pendant.temp.boxTemp
import com.z1pendant.temp.spindleTemp

private val AlarmRed = Color(0xFFF87171)
private val AlarmBg = Color(0xFF3B1212)

/**
 * The Z1's halt reason (`|H:` in the status, only while halted), in words. Codes from the community
 * firmware's Kernel.h, the same order Makera's apps show them (reference/probing_app_notes.md).
 */
fun haltText(code: Int?): String? = when (code) {
    null -> null
    1 -> "Stopped by hand"
    2 -> "Homing failed"
    3 -> "Probe failed"
    4 -> "Tool setter calibration failed"
    in 5..8 -> "Tool change problem"
    9 -> "Spindle overheated"
    10 -> "Soft limit: a move would go past the machine's travel"
    11 -> "Cover open"
    12 -> "Wireless probe dead or not set"
    13 -> "E-stop pressed"
    14 -> "Power supply overheated"
    15 -> "Not homed"
    21 -> "Hard limit hit"
    22 -> "X motor error"
    23 -> "Y motor error"
    24 -> "Z motor error"
    25 -> "Spindle stalled"
    26 -> "SD card problem"
    41 -> "Spindle driver fault"
    else -> if (code > 40) "Power problem" else null
}

/** Why the machine is in Alarm, as one line: the halt code's words, else the Z1's own ALARM line. */
fun alarmReason(st: MachineStatus?, lastAlarm: String?): String =
    haltText(st?.halt) ?: lastAlarm ?: "The Z1 is in Alarm"

/**
 * After an overheat halt (9 spindle, 14 box), what's still too hot: the Z1 checks once a second and would
 * halt again if the alarm were cleared above the limit. Null when clearing is fine.
 */
fun stillTooHot(code: Int?, st: MachineStatus?, lim: TempLimits): Pair<String, Double>? = when (code) {
    9 -> st?.spindleTemp?.takeIf { it >= lim.spindle }?.let { "spindle" to it }
    14 -> st?.boxTemp?.takeIf { it >= lim.box }?.let { "box" to it }
    else -> null
}

/** In the drawer while the machine is in Alarm: the reason and Clear alarm. Tap the text for the details. */
@Composable
fun AlarmStrip(vm: AppViewModel, st: MachineStatus?) {
    val last by vm.lastAlarm.collectAsState()
    val fromMsg by vm.haltFromMessage.collectAsState()
    val lim by vm.tempLimits.collectAsState()
    val code = st?.halt ?: fromMsg
    val hot = stillTooHot(code, st, lim)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AlarmBg).clickable { vm.alarmSheet.value = true }
        .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Warning, null, tint = AlarmRed, modifier = Modifier.size(20.dp))
        Text(haltText(code) ?: alarmReason(st, last), color = AlarmRed, fontSize = 14.sp, maxLines = 2, modifier = Modifier.weight(1f).padding(start = 8.dp))
        Button(onClick = vm::clearAlarm, enabled = hot == null,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626), contentColor = Color.White)) {
            Text(if (hot != null) "Cooling ${deg(hot.second)} °C" else "Clear alarm")
        }
    }
}

/**
 * Tapping the Alarm status (or it appearing on the main screen): what happened and Clear alarm. An
 * overheat (9 spindle, 14 box) shows the job's temperatures with the halt and the cooling since, and
 * Clear alarm waits until it's back under the limit. A spindle driver fault (41) says it needs a power
 * cycle and shows the temperatures and spindle speed just before it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmSheet(vm: AppViewModel) {
    val st by vm.link.status.collectAsState()
    val last by vm.lastAlarm.collectAsState()
    val fromMsg by vm.haltFromMessage.collectAsState()
    val lim by vm.tempLimits.collectAsState()
    val temps by vm.temps.collectAsState()
    val atS by vm.alarmAtS.collectAsState()
    val before by vm.beforeAlarm.collectAsState()
    val haltT by vm.haltTemp.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val code = st?.halt ?: fromMsg
    val overheat = code == 9 || code == 14
    val hot = stillTooHot(code, st, lim)
    fun one(v: Double?) = v?.let { com.z1pendant.util.Fmt.fixed(it, 1) }
    ModalBottomSheet(onDismissRequest = { vm.alarmSheet.value = false }, containerColor = Color(0xFF111A2B)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = AlarmRed, modifier = Modifier.size(28.dp))
                Text(when (code) { 9 -> "Spindle overheated"; 14 -> "Electronics box overheated"; 41 -> "Spindle driver fault"; else -> "Alarm" },
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(start = 8.dp))
            }
            when (code) {
                9 -> Text("The spindle reached ${one(haltT) ?: deg(before?.spindleTemp)} °C; the Z1 stops at ${deg(lim.spindle)} °C. " +
                    "This is the spindle's own temperature sensor, not the driver board.", fontSize = 15.sp, color = Color.White)
                14 -> Text("The electronics box reached ${one(haltT) ?: deg(before?.boxTemp)} °C; the Z1 stops at ${deg(lim.box)} °C.",
                    fontSize = 15.sp, color = Color.White)
                41 -> {
                    Text("The spindle's driver board signalled a fault and the Z1 stopped. It only sends \"fault\", not why: " +
                        "usually an overload or a stall, otherwise its supply voltage or its own heat.", fontSize = 15.sp, color = Color.White)
                    before?.let { b ->
                        Text("At the time: spindle ${deg(b.spindleTemp)} °C, box ${deg(b.boxTemp)} °C" +
                            (b.spindleTarget?.let { ", ${com.z1pendant.util.Fmt.thousands(it.toInt())} rpm set" } ?: "") +
                            (b.spindleRpm?.let { ", ${com.z1pendant.util.Fmt.thousands(it.toInt())} measured" } ?: "") + ".",
                            fontSize = 13.sp, color = muted)
                    }
                    Text("The Z1 needs a power off/on to clear this one; Clear alarm won't.", fontSize = 14.sp, color = Color.White,
                        fontWeight = FontWeight.SemiBold)
                }
                else -> {
                    Text(alarmReason(st, last), fontSize = 16.sp, color = Color.White)
                    // The Z1's own words, when the code already said it differently.
                    if (haltText(code) != null && last != null) Text("The Z1 says: $last", fontSize = 13.sp, color = muted)
                }
            }
            // The run-up and the cooling since, while there's a trace from the job.
            val v = temps
            if (overheat && v != null && v.samples.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 4.dp)) {
                    val now = if (code == 9) st?.spindleTemp else st?.boxTemp
                    val peak = v.samples.mapNotNull { if (code == 9) it.spindle else it.box }.maxOrNull()
                    val cooling = now != null && peak != null && now < peak
                    TempHeader("Spindle now", st?.spindleTemp, st?.boxTemp, lim, null, rateText = if (cooling) "↓ cooling" else null)
                    TempGraph(v, lim, null, Modifier.fillMaxWidth().height(104.dp).padding(top = 4.dp), haltS = atS)
                    TimeFooter("job start", "now")
                }
            }
            if (code == 13) Text("Release the e-stop first, then clear the alarm.", fontSize = 14.sp, color = muted)
            if (st?.job != null) Text("The job that was running is still open on the Z1. Clearing the alarm ends it.", fontSize = 14.sp, color = muted)
            if (code != null && (code in 21..26 || (code > 40 && code != 41))) Text("If it won't clear, power-cycle the Z1.", fontSize = 14.sp, color = muted)
            if (code == 41) {
                OutlinedButton(onClick = vm::clearAlarm, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Clear alarm anyway", fontSize = 16.sp) }
            } else {
                Button(onClick = vm::clearAlarm, enabled = hot == null, modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626), contentColor = Color.White,
                        disabledContainerColor = Color(0xFF3F1D1D), disabledContentColor = Color(0xFFFCA5A5))) {
                    Text(if (hot != null) "Clear alarm: under ${deg(if (code == 9) lim.spindle else lim.box)} °C first (${deg(hot.second)} °C)"
                         else "Clear alarm", fontSize = if (hot != null) 14.sp else 16.sp)
                }
            }
            TextButton(onClick = { vm.alarmSheet.value = false }) { Text("Close") }
        }
    }
}
