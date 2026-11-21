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
    else -> if (code > 40) "Power problem" else null
}

/** Why the machine is in Alarm, as one line: the halt code's words, else the Z1's own ALARM line. */
fun alarmReason(st: MachineStatus?, lastAlarm: String?): String =
    haltText(st?.halt) ?: lastAlarm ?: "The Z1 is in Alarm"

/** In the drawer while the machine is in Alarm: the reason and Clear alarm. Tap the text for the details. */
@Composable
fun AlarmStrip(vm: AppViewModel, st: MachineStatus?) {
    val last by vm.lastAlarm.collectAsState()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AlarmBg).clickable { vm.alarmSheet.value = true }
        .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Warning, null, tint = AlarmRed, modifier = Modifier.size(20.dp))
        Text(alarmReason(st, last), color = AlarmRed, fontSize = 14.sp, maxLines = 2, modifier = Modifier.weight(1f).padding(start = 8.dp))
        Button(onClick = vm::clearAlarm, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626), contentColor = Color.White)) {
            Text("Clear alarm")
        }
    }
}

/** Tapping the Alarm status (or it appearing on the main screen): what happened and Clear alarm. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmSheet(vm: AppViewModel) {
    val st by vm.link.status.collectAsState()
    val last by vm.lastAlarm.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val code = st?.halt
    ModalBottomSheet(onDismissRequest = { vm.alarmSheet.value = false }, containerColor = Color(0xFF111A2B)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = AlarmRed, modifier = Modifier.size(28.dp))
                Text("Alarm", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(start = 8.dp))
            }
            Text(alarmReason(st, last), fontSize = 16.sp, color = Color.White)
            // The Z1's own words, when the code already said it differently.
            if (haltText(code) != null && last != null) Text("The Z1 says: $last", fontSize = 13.sp, color = muted)
            if (code == 13) Text("Release the e-stop first, then clear the alarm.", fontSize = 14.sp, color = muted)
            if (st?.job != null) Text("The job that was running is still open on the Z1. Clearing the alarm ends it.", fontSize = 14.sp, color = muted)
            if (code != null && (code in 21..26 || code > 40)) Text("If it won't clear, power-cycle the Z1.", fontSize = 14.sp, color = muted)
            Button(onClick = vm::clearAlarm, modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626), contentColor = Color.White)) {
                Text("Clear alarm", fontSize = 16.sp)
            }
            TextButton(onClick = { vm.alarmSheet.value = false }) { Text("Close") }
        }
    }
}
