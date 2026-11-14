package com.z1pendant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.z1pendant.AppViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Jog pad: −Y on top, +Y below, −X left, +X right, current step in the middle; to the right
 * +Z/−Z and +A/−A pairs centred on the middle row. Tap = one step; hold = repeat (after 0.4 s)
 * until let go, at a rate that stays within the axis's max speed.
 */
@Composable
fun JogPad(vm: AppViewModel, step: Double, enabled: Boolean, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        // 5 buttons across + gaps (6+6 in the cross, 14 to the rails, 10 between rails)
        val cell: Dp = minOf(if (LocalWide.current) 88.dp else 68.dp, (maxWidth - 36.dp) / 5)
        val gap = 6.dp
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Spacer(Modifier.size(cell)); PadButton(vm, 'Y', -1, cell, enabled); Spacer(Modifier.size(cell))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                    PadButton(vm, 'X', -1, cell, enabled)
                    Box(Modifier.size(cell).clip(CircleShape).background(Color.Black.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stepLabel(step), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("mm", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    PadButton(vm, 'X', 1, cell, enabled)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Spacer(Modifier.size(cell)); PadButton(vm, 'Y', 1, cell, enabled); Spacer(Modifier.size(cell))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (a in listOf('Z', 'A')) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        PadButton(vm, a, 1, cell, enabled)
                        PadButton(vm, a, -1, cell, enabled)
                    }
                }
            }
        }
    }
}

@Composable
private fun PadButton(vm: AppViewModel, axis: Char, dir: Int, size: Dp, enabled: Boolean) {
    var pressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val en by rememberUpdatedState(enabled)
    val label = (if (dir > 0) "+" else "−") + axis
    Box(
        Modifier.size(size).clip(RoundedCornerShape(14.dp))
            .background(if (pressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.secondaryContainer)
            .semantics { contentDescription = label }
            .pointerInput(axis, dir) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    if (!en) { waitForUpOrCancellation(); return@awaitEachGesture }
                    pressed = true
                    vm.padJog(axis, dir)
                    var repeated = false
                    val repeat = scope.launch {
                        delay(400)
                        while (true) { repeated = true; vm.padJog(axis, dir); delay(vm.padRepeatMs(axis)) }
                    }
                    waitForUpOrCancellation()
                    repeat.cancel()
                    pressed = false
                    if (repeated) vm.padReleased()          // stop now: drop clicks not yet sent
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = if (enabled) 1f else 0.4f))
    }
}

/** Top-left switch: joystick icon in dial mode (→ jog pad), dial icon in pad mode (→ dial). */
@Composable
fun ModeButton(padMode: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme.primary
    Box(
        modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick).semantics { contentDescription = if (padMode) "Dial" else "Jog pad" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(28.dp)) {
            val u = size.width / 24f
            if (!padMode) {
                // joystick: ball on a stick, on a base
                drawCircle(c, radius = 3.2f * u, center = Offset(12 * u, 6 * u))
                drawLine(c, Offset(12 * u, 9.2f * u), Offset(12 * u, 15.2f * u), strokeWidth = 1.8f * u, cap = StrokeCap.Round)
                drawRoundRect(c, topLeft = Offset(5 * u, 15 * u), size = Size(14 * u, 6.5f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(7 * u, 3.25f * u), style = Stroke(1.8f * u))
            } else {
                // dial: ring with a marker at 12 o'clock
                drawCircle(c, radius = 9 * u, center = Offset(12 * u, 12 * u), style = Stroke(1.8f * u))
                drawCircle(c, radius = 1.8f * u, center = Offset(12 * u, 7 * u))
                drawLine(c, Offset(12 * u, 2.8f * u), Offset(12 * u, 4.8f * u), strokeWidth = 1.8f * u, cap = StrokeCap.Round)
            }
        }
    }
}
