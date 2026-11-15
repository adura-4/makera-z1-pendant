package com.z1pendant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cyclone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.z1pendant.AppViewModel
import com.z1pendant.SPINDLE_PRESETS
import com.z1pendant.util.Fmt
import kotlinx.coroutines.delay

/**
 * Spindle start box, opened by the Spindle tile (or a bound button) while the spindle is off. It
 * always opens; there's no "don't ask". Same frame as the Move box: blue icon + "Spindle", ✕
 * top-right, Start at the bottom. The RPM box opens holding the last RPM started with; a preset
 * flashes when tapped and puts its value in the box. Shows the exact line, or why it can't start
 * (checked before anything is sent: not Idle, no tool, out of range).
 */
@Composable
fun SpindleDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val st by vm.link.status.collectAsState()
    val last by vm.spindleRpm.collectAsState()
    var field by remember { mutableStateOf(last.toString().let { TextFieldValue(it, TextRange(it.length)) }) }
    fun set(t: String) { field = TextFieldValue(t, TextRange(t.length)) }
    val rpm = field.text.filter { it.isDigit() }.take(6).toIntOrNull()
    val block = remember(st, rpm) { vm.spindleBlock(rpm) }  // re-checked on every status
    val lastTool by vm.lastSetTool.collectAsState()
    var pick by remember { mutableStateOf(lastTool) }       // the tool on the stepper
    var toolSet by remember { mutableStateOf<Int?>(null) }   // set from this box: "Tool Tn set." once the machine reports it
    var flash by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(flash) { if (flash != null) { delay(220); flash = null } }
    val start = { if (rpm != null && block == null) vm.startSpindle(rpm) }
    val boxShape = RoundedCornerShape(10.dp)
    val cs = MaterialTheme.colorScheme

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = cs.surfaceVariant) {
            Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Cyclone, null, Modifier.size(30.dp), tint = cs.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("Spindle", fontSize = 24.sp)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Cancel") }
                }
                Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Presets, 3 per row; the last is the spindle's max.
                    SPINDLE_PRESETS.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { p ->
                                val lit = flash == p
                                Box(Modifier.weight(1f).height(44.dp).clip(boxShape)
                                    .background(if (lit) cs.primary else cs.surfaceVariant)
                                    .border(1.dp, if (lit) cs.primary else cs.outline, boxShape)
                                    .clickable { set(p.toString()); flash = p; vm.haptics.press() },
                                    contentAlignment = Alignment.Center) {
                                    Text(Fmt.thousands(p), fontSize = 16.sp,
                                        color = if (lit) cs.onPrimary else cs.onSurface,
                                        fontWeight = if (lit) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                    OutlinedTextField(field, { v -> field = v.copy(text = v.text.filter { it.isDigit() }.take(6)) },
                        singleLine = true, shape = boxShape,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        suffix = { Text("RPM") },
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 20.sp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { start() }))
                    if (block != null) Text(block, fontSize = 13.sp, color = cs.error)
                    else if (toolSet != null && st?.tool == toolSet) Text("Tool T$toolSet set.", fontSize = 13.sp, color = Color(0xFF86EFAC))
                    // No usable tool: pick one (− / +, hold to run) and tell the firmware it's in (M493.2, no motion).
                    if (vm.spindleToolProblem(st)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Stepper(pick, 1..99, onChange = { pick = it }, label = { "T$it" }, onStep = { vm.haptics.tick() })
                            PendantOutlinedButton(onClick = { vm.setTool(pick); toolSet = pick }, shape = boxShape,
                                modifier = Modifier.height(48.dp)) { Text("Set tool", fontSize = 16.sp) }
                        }
                        vm.link.dialect.setTool(pick).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 15.sp) }
                    } else if (block == null) {
                        vm.link.dialect.spindleOn(rpm!!).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 15.sp) }
                    }
                    Button(onClick = start, enabled = block == null,
                        modifier = Modifier.align(Alignment.CenterHorizontally).fillMaxWidth(0.6f).height(50.dp)) {
                        Text("Start", fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

/**
 * Spindle stop box, opened by the Spindle tile (or a bound button) while the spindle runs. Always
 * asks: a job may be using the spindle, and the app can't tell who started it. Same frame as the
 * start box: blue icon + title, ✕ top-right, the line it sends, Stop at the bottom.
 */
@Composable
fun SpindleStopDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = cs.surfaceVariant) {
            Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Cyclone, null, Modifier.size(30.dp), tint = cs.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("Stop spindle?", fontSize = 24.sp)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Cancel") }
                }
                Column(Modifier.padding(end = 12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.link.dialect.spindleOff().forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 15.sp) }
                    Button(onClick = { vm.stopSpindle() },
                        modifier = Modifier.align(Alignment.CenterHorizontally).fillMaxWidth(0.6f).height(50.dp)) {
                        Text("Stop", fontSize = 18.sp)
                    }
                }
            }
        }
    }
}
