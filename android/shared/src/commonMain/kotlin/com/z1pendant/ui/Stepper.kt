package com.z1pendant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * − value + stepper. A tap steps once; holding − or + repeats after 0.4 s and speeds up the longer
 * it's held (150 ms per step down to 40 ms). Stops at the ends of [range]. [onStep] fires on every
 * step that changed the value (for a haptic tick).
 */
@Composable
fun Stepper(
    value: Int, range: IntRange, onChange: (Int) -> Unit, label: (Int) -> String,
    onStep: () -> Unit = {}, modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(modifier.height(48.dp).border(1.dp, MaterialTheme.colorScheme.outline, shape).clip(shape),
        verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", "Down", value, range, -1, onChange, onStep)
        Text(label(value), fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        StepButton("+", "Up", value, range, +1, onChange, onStep)
    }
}

@Composable
private fun StepButton(
    text: String, desc: String, value: Int, range: IntRange, dir: Int,
    onChange: (Int) -> Unit, onStep: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val v by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val tick by rememberUpdatedState(onStep)
    var pressed by remember { mutableStateOf(false) }
    fun step(): Boolean {
        val n = (v + dir).coerceIn(range.first, range.last)
        if (n == v) return false
        change(n); tick(); return true
    }
    Box(
        Modifier.width(44.dp).height(48.dp)
            .background(if (pressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else androidx.compose.ui.graphics.Color.Transparent)
            .semantics { contentDescription = desc }
            .pointerInput(dir) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    pressed = true
                    step()
                    val repeat = scope.launch {
                        delay(400)
                        var gap = 150L
                        while (step()) { delay(gap); gap = maxOf(40L, gap * 85 / 100) }
                    }
                    waitForUpOrCancellation()
                    repeat.cancel()
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
    }
}
