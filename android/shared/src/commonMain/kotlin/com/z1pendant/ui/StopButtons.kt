package com.z1pendant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AMBER = Color(0xFFD97706); private val AMBER_TEXT = Color(0xFFF59E0B)
private val GREEN = Color(0xFF16A34A); private val GREEN_TEXT = Color(0xFF22C55E)
private val RED = Color(0xFFDC2626); private val RED_TEXT = Color(0xFFF87171)

/**
 * Hold / Resume / Reset, lit by machine state. Always in the same place; each one fills with its
 * colour only when it's the relevant action, otherwise it's a quiet app-themed tile with tinted
 * text: moving (Run / Home / Jog) lights Hold and Reset, Hold / Pause / Door lights Resume and
 * Reset, Alarm lights Reset. Enabled whenever connected, lit or not.
 */
@Composable
fun StopButtons(state: String?, connected: Boolean, onHold: () -> Unit, onResume: () -> Unit, onReset: () -> Unit,
                height: Dp = 52.dp, modifier: Modifier = Modifier, jobActive: Boolean = false) {
    val s = state?.lowercase()
    val moving = connected && (s == "run" || s == "home" || s == "jog")
    val held = connected && (s == "hold" || s == "pause" || s == "door")
    val alarm = connected && s == "alarm"
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StopButton("Hold", lit = moving, AMBER, AMBER_TEXT, connected, onHold, height)
        StopButton("Resume", lit = held, GREEN, GREEN_TEXT, connected, onResume, height)
        // The same halt either way (Studio's Stop): during a job it reads as what it does to the job.
        StopButton(if (jobActive) "Cancel job" else "Stop", lit = moving || held || alarm, RED, RED_TEXT, connected, onReset, height)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.StopButton(
    label: String, lit: Boolean, fill: Color, text: Color, enabled: Boolean, onClick: () -> Unit, height: Dp,
) {
    Button(
        onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f).height(height),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (lit) fill else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (lit) Color.White else text,
        ),
        // The default 24 dp side padding left "Resume" too little room on a 360 dp phone: it wrapped.
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) { Text(label, fontSize = 16.sp, maxLines = 1, softWrap = false) }
}
