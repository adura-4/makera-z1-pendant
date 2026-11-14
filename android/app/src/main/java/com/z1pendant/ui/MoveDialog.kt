package com.z1pendant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import java.util.Locale

/**
 * Typed move for one axis, opened from its readout row (move button, or a 1.5 s hold).
 * "Go to" = work position, "Move by" = relative. Shows the exact line(s) it will send; Go sends
 * them (rapid, G0). ± flips the sign. A plain panel rather than AlertDialog, whose fixed title /
 * body / button spacing was too loose.
 */
@Composable
fun MoveDialog(vm: AppViewModel, axis: Char, onDismiss: () -> Unit) {
    val st by vm.link.status.collectAsState()
    var field by remember { mutableStateOf(TextFieldValue("")) }
    val text = field.text
    fun set(t: String) { field = TextFieldValue(t, TextRange(t.length)) }   // cursor to the end
    var relative by remember { mutableStateOf(false) }
    val unit = if (axis == 'A') "°" else "mm"
    val i = vm.axes.indexOf(axis)
    val value = text.replace(',', '.').trim().let { if (it == "-" || it == "+") null else it.toDoubleOrNull() }
    val plan = value?.let { vm.planMove(axis, it, relative) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val go = { if (value != null && plan?.error == null) { vm.runMove(axis, value, relative); onDismiss() } }
    val boxShape = RoundedCornerShape(10.dp)

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 18.dp)) {
                // Title: blue axis letter + "Move"; ✕ top-right closes.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(axis.toString(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 28.sp)
                    Spacer(Modifier.width(10.dp))
                    Text("Move", fontSize = 24.sp)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Cancel") }
                }
                Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Now: $axis ${st?.wpos?.getOrNull(i)?.let { "%.3f".format(Locale.US, it) } ?: "—"} $unit",
                        fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Segmented(listOf("Go to", "Move by"), if (relative) 1 else 0) { relative = it == 1 }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            set(when {
                                text.startsWith("-") -> text.drop(1)
                                text.startsWith("+") -> "-" + text.drop(1)
                                else -> "-$text"
                            })
                        }, shape = boxShape, contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(56.dp)) { Text("±", fontSize = 22.sp) }
                        OutlinedTextField(field, { v -> field = v.copy(text = v.text.filter { it.isDigit() || it in ".,-+" }) },
                            singleLine = true, shape = boxShape,
                            modifier = Modifier.weight(1f).height(56.dp).focusRequester(focus),
                            suffix = { Text(unit) },
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 20.sp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { go() }))
                    }
                    // What Go will send (or why it can't). Only there once a number is typed; the box grows.
                    if (plan != null) Column {
                        when {
                            plan.error != null -> Text(plan.error, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                            else -> {
                                plan.lines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 15.sp) }
                                if (relative) plan.endWork?.let {
                                    Text("ends at $axis ${"%.3f".format(Locale.US, it)} $unit", fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Button(onClick = go, enabled = plan != null && plan.error == null && st?.isIdle == true,
                        modifier = Modifier.align(Alignment.CenterHorizontally).fillMaxWidth(0.6f).height(50.dp)) {
                        Text("Go", fontSize = 18.sp)
                    }
                }
            }
        }
    }
}
