package com.z1pendant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Asks before Reset (the drawer's red button). Reset sends Ctrl-X (0x18), which Makera's own app
 * calls "emergency stop": everything stops at once, a running job is cancelled, and the controller
 * halts until it's unlocked. Hold stays one tap: that's the quick way to pause.
 */
@Composable
fun ResetDialog(onReset: () -> Unit, onDismiss: () -> Unit, jobActive: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val bold = SpanStyle(fontWeight = FontWeight.SemiBold)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = cs.surfaceVariant) {
            Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, Modifier.size(30.dp), tint = Color(0xFFF87171))
                    Spacer(Modifier.width(10.dp))
                    Text(if (jobActive) "Cancel the job?" else "Stop the machine?", fontSize = 24.sp)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Cancel") }
                }
                Column(Modifier.padding(end = 12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(buildAnnotatedString {
                        append("Stops everything "); withStyle(bold) { append("at once") }; append(", like an emergency stop:")
                    }, fontSize = 15.sp)
                    Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("•  motion and the spindle stop", fontSize = 14.sp)
                        if (jobActive) Text("•  the job is cancelled (it can't be resumed)", fontSize = 14.sp)
                        Text("•  then the alarm is cleared, so you can move again", fontSize = 14.sp)
                        if (jobActive) Text("•  and you're back on the main screen", fontSize = 14.sp)
                    }
                    Text(buildAnnotatedString {
                        append("To pause and carry on, use "); withStyle(bold) { append("Hold") }; append(" instead.")
                    }, fontSize = 15.sp)
                    Text("Ctrl-X (0x18)", fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = cs.onSurfaceVariant)
                    Button(onClick = onReset,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626), contentColor = Color.White),
                        modifier = Modifier.align(Alignment.CenterHorizontally).fillMaxWidth(0.6f).height(50.dp)) {
                        Text(if (jobActive) "Cancel job" else "Stop", fontSize = 18.sp)
                    }
                }
            }
        }
    }
}
