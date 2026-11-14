package com.z1pendant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.z1pendant.AppViewModel
import java.util.Locale

/**
 * Typed move for one axis, opened by long-pressing its readout row. "Go to" = work position,
 * "Move by" = relative. Shows the exact line(s) it will send; Go sends them (rapid, G0).
 * ± flips the sign (handy one-thumbed; the keyboard has a minus key too).
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

    AlertDialog(
        onDismissRequest = onDismiss,
        // Title: blue axis letter + "Move"; ✕ top-right closes. Go is the one big button at the bottom.
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(axis.toString(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 28.sp)
                Spacer(Modifier.width(10.dp))
                Text("Move", fontSize = 24.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.offset(x = 12.dp)) {
                    Icon(androidx.compose.material.icons.Icons.Filled.Close, "Cancel")
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Now: $axis ${st?.wpos?.getOrNull(i)?.let { "%.3f".format(Locale.US, it) } ?: "—"} $unit (work)",
                    fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Segmented(listOf("Go to", "Move by"), if (relative) 1 else 0) { relative = it == 1 }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        set(when {
                            text.startsWith("-") -> text.drop(1)
                            text.startsWith("+") -> "-" + text.drop(1)
                            else -> "-$text"
                        })
                    }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("±", fontSize = 20.sp) }
                    OutlinedTextField(field, { v -> field = v.copy(text = v.text.filter { it.isDigit() || it in ".,-+" }) },
                        singleLine = true, modifier = Modifier.weight(1f).focusRequester(focus),
                        label = { Text(if (relative) "Distance" else "Position") },
                        suffix = { Text(unit) },
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 20.sp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { go() }))
                }
                when {
                    plan == null -> Text(" ", fontSize = 13.sp)
                    plan.error != null -> Text(plan.error, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    else -> Column {
                        plan.lines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 15.sp) }
                        if (relative) plan.endWork?.let {
                            Text("ends at $axis ${"%.3f".format(Locale.US, it)} $unit", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Button(onClick = go, enabled = plan != null && plan.error == null && st?.isIdle == true,
                    modifier = Modifier.align(Alignment.CenterHorizontally).fillMaxWidth(0.6f).height(52.dp)) {
                    Text("Go", fontSize = 18.sp)
                }
            }
        },
        confirmButton = {},
    )
}
