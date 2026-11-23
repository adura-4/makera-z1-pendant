package com.z1pendant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.z1pendant.AppViewModel
import com.z1pendant.link.ConnState
import com.z1pendant.probe.PROBE_TOOL
import com.z1pendant.probe.ProbeKind
import com.z1pendant.probe.ProbeLength
import com.z1pendant.probe.ProbeTool
import com.z1pendant.probe.ToolAction
import com.z1pendant.util.Fmt

private val WarnBg = Color(0xFF3A2A0B)
private val WarnFg = Color(0xFFFDE68A)

/**
 * The step list shown while a probe or anchor run is in progress. [detail] draws under the active row
 * (keyed by [detailKey] so the active row is re-scrolled into view when it changes); [footer] closes the panel.
 */
@Composable
fun StepChecklist(labels: List<String>, index: Int, note: (String) -> String? = { null }, modifier: Modifier = Modifier,
                  detailKey: Any? = null, detail: (@Composable () -> Unit)? = null, footer: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(TileBg).padding(horizontal = 12.dp, vertical = 6.dp)) {
        labels.forEachIndexed { i, label ->
            val done = i < index; val now = i == index
            val bring = remember { BringIntoViewRequester() }
            if (now && detail != null) LaunchedEffect(index, detailKey) { bring.bringIntoView() }
            Column(Modifier.fillMaxWidth().bringIntoViewRequester(bring)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        done -> Icon(Icons.Filled.CheckCircle, null, tint = Green, modifier = Modifier.size(22.dp))
                        now -> Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.primary))
                        }
                        else -> Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).border(2.dp, Color(0xFF334155), RoundedCornerShape(11.dp)))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(label, fontSize = 15.sp, fontWeight = if (now) FontWeight.Medium else FontWeight.Normal,
                        color = when { now -> Color.Unspecified; done -> Muted; else -> Color(0xFF64748B) }, modifier = Modifier.weight(1f))
                    if (done) note(label)?.let { Text(it, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Muted) }
                }
                if (now && detail != null) Column(Modifier.padding(bottom = 6.dp)) { detail() }
            }
        }
        footer?.invoke()
    }
}

@Composable
internal fun CheckRow(state: Int, title: String, sub: String?, dim: Boolean = false, body: @Composable ColumnScope.() -> Unit = {}) {
    // state: 0 waiting, 1 now, 2 done, 3 warning
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TileBg).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            val (ic, tint) = when (state) {
                2 -> Icons.Filled.CheckCircle to Green
                1 -> Icons.Filled.RadioButtonChecked to MaterialTheme.colorScheme.primary
                3 -> Icons.Filled.Warning to Amber
                else -> Icons.Filled.RadioButtonUnchecked to Color(0xFF475569)
            }
            Icon(ic, null, tint = tint, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp))
            Column {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (dim) Color(0xFF64748B) else Color.Unspecified)
                sub?.let { Text(it, fontSize = 12.5.sp, color = Muted, lineHeight = 17.sp) }
            }
        }
        body()
    }
}

@Composable
internal fun SmallButton(text: String, icon: (@Composable () -> Unit)?, outlined: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        icon?.let { it(); Spacer(Modifier.width(6.dp)) }; Text(text, fontSize = 15.sp)
    }
    if (outlined) PendantOutlinedButton(onClick, Modifier.fillMaxWidth().height(42.dp), enabled = enabled, content = content)
    else Button(onClick, Modifier.fillMaxWidth().height(42.dp), enabled = enabled, content = content)
}

@Composable
internal fun StepCheck(vm: AppViewModel, allowUseOther: Boolean = true, allowConfirm: Boolean = true,
                       showTool: ProbeTool? = null, showKind: ProbeKind? = null,
                       runOwnsMotion: Boolean = false) {
    val p = vm.probe
    val st by vm.link.status.collectAsState()
    val conn by vm.link.conn.collectAsState()
    val confirmed by p.confirmed.collectAsState()
    val calibrated by p.calibrated.collectAsState()
    val action by p.toolAction.collectAsState()
    val len by p.lengthCheck.collectAsState()
    val setup by p.setup.collectAsState()
    val probeTool = showTool ?: setup.tool
    val probeKind = showKind ?: setup.kind
    val name = probeTool.label
    val tool = st?.tool
    val connected = conn == ConnState.Connected
    val idle = st?.isIdle == true
    // A routine that owns the machine must be the only motion stream, so its own buttons stay off.
    val canMove = connected && idle && !runOwnsMotion
    val acting = action?.let { it.stage != ToolAction.Stage.DONE && it.error == null } == true
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary

    action?.error?.let { e ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ErrBg).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Error, null, tint = ErrFg); Spacer(Modifier.width(8.dp))
            Text(e, fontSize = 14.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { p.dismissToolError() }) { Text("OK") }
        }
        if (st?.isAlarm == true) SmallButton("Unlock", { Icon(Icons.Filled.LockOpen, null) }) { vm.link.dialect.unlock().forEach { vm.link.sendLine(it) } }
    }

    // Why the buttons below are off (2026-11-18: the Pixel had left the machine's Wi-Fi, and the panel
    // only said "The spindle is empty" next to a greyed Load button).
    if (!acting && action?.error == null && (!connected || !idle)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(WarnBg).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Error, null, tint = WarnFg); Spacer(Modifier.width(8.dp))
            Text(when {
                !connected -> "Not connected to the machine (${conn.name.lowercase()}). Loading and calibrating need the link; check the phone is on the machine's Wi-Fi."
                else -> "The machine is ${st?.state ?: "busy"}, not Idle. Loading and calibrating wait until it's Idle."
            }, fontSize = 14.sp, modifier = Modifier.weight(1f))
        }
    }

    // Row 1: the probe is in the spindle.
    when {
        action?.load == true && acting -> CheckRow(1, "$name in the spindle", "Loading…") {
            Progress(action!!.stage, load = true, probeTool.noun)
            if (action!!.stage == ToolAction.Stage.WAITING) SmallButton("I've swapped it · continue", null, outlined = true) { p.continueToolChange() }
        }
        tool == PROBE_TOOL && confirmed -> CheckRow(2, "$name in the spindle", "T$PROBE_TOOL")
        tool == PROBE_TOOL -> CheckRow(1, "$name in the spindle", "The machine says a probe (T$PROBE_TOOL) is in. The number can be left over from last time: is the ${probeTool.noun} in?") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { p.confirmed.value = true }, modifier = Modifier.weight(1f).height(42.dp)) { Text("Yes, it's in") }
                PendantOutlinedButton(onClick = {
                    // M6 T9999 does nothing while the machine already says T9999: mark the spindle empty first.
                    vm.link.sendLine("M493.2 T-1"); p.loadProbe()
                }, enabled = canMove, modifier = Modifier.weight(1f).height(42.dp)) { Text("No, load it") }
            }
        }
        else -> CheckRow(3, "$name in the spindle", if (tool == null) "The machine hasn't reported a tool." else if (tool < 0) "The spindle is empty." else "The machine has T$tool in.") {
            SmallButton("Load the ${probeTool.noun}", { Icon(Icons.Filled.SwapHoriz, null) }, enabled = canMove) { p.loadProbe() }
            Text("Moves to the tool change spot and waits while you swap. Then it calibrates the probe on the tool setter and checks it triggers.",
                fontSize = 11.5.sp, color = Muted, lineHeight = 15.sp)
        }
    }

    // Row 2: calibrated on the tool setter.
    val probeIn = tool == PROBE_TOOL && confirmed
    when {
        action?.load == false && acting -> CheckRow(1, "Calibrated on the tool setter", "Calibrating…") { Progress(action!!.stage, load = false) }
        !probeIn -> CheckRow(0, "Calibrated on the tool setter", null, dim = true)
        calibrated -> CheckRow(2, "Calibrated on the tool setter", "Probe triggered on the setter") {
            SmallButton("Calibrate again", { CalibrateIcon(primary) }, outlined = true, enabled = canMove) { p.calibrate() }
        }
        else -> CheckRow(1, "Calibrated on the tool setter", "Every time you probe: it checks the probe triggers and which probe is in.") {
            SmallButton("Calibrate now", { CalibrateIcon(onPrimary) }, enabled = canMove) { p.calibrate() }
            Text("The machine moves over the tool setter and lowers the probe onto it.", fontSize = 11.5.sp, color = Muted)
        }
    }
    // Row 3: it's the probe you picked, going by where it trips the setter.
    if (calibrated && !acting) len?.let { LengthRow(it, p, probeKind, canMove, allowUseOther, allowConfirm) }
}

private fun lenName(t: ProbeTool) = t.noun
/** "3D Probe", "Probe Rod", "Laser Probe": for status lines like "3D Probe Confirmed". */
private fun titleName(t: ProbeTool) = t.label.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

/** Which probe the setter says is in: each probe trips it at its own Z (learned on its first calibration). */
@Composable
internal fun LengthRow(c: ProbeLength.Check, p: com.z1pendant.probe.ProbeController, kind: ProbeKind, canMove: Boolean, allowUseOther: Boolean = true, allowConfirm: Boolean = true) {
    val z = Fmt.fixed(c.z, 3)
    val exp = c.expected?.let { Fmt.fixed(it, 3) }
    val load: @Composable () -> Unit = {
        SmallButton("Load the ${lenName(c.tool)}", { Icon(Icons.Filled.SwapHoriz, null) }, enabled = canMove) { p.reloadProbe() }
    }
    when (c.result) {
        ProbeLength.Result.MATCH -> CheckRow(2, "${titleName(c.tool)} Confirmed", "Setter Z $z, learned $exp")
        ProbeLength.Result.LEARNED -> CheckRow(2, "${c.tool.label} length learned",
            "It tripped the setter at Z $z. From now on the app checks the ${lenName(c.tool)} reads the same, so a wrong probe gets caught.")
        // The reading is another probe's: swap in the one you picked, or use the one that's in if it
        // can do this operation (all three can do Top only; the laser probe nothing else).
        ProbeLength.Result.LOOKS_LIKE -> CheckRow(3, "${titleName(c.other!!)} Detected",
            "It tripped the setter at Z $z, like the ${lenName(c.other)} (${Fmt.fixed(p.learned()[c.other] ?: c.z, 3)}). " +
                "You picked the ${lenName(c.tool)}.") {
            load()
            if (allowUseOther) {
                if (c.other.canDo(kind)) SmallButton("Use the ${lenName(c.other)} instead", null, outlined = true) { p.switchToMatch() }
                else Text("The ${lenName(c.other)} can't do ${kind.label.lowercase()}: swap in the ${lenName(c.tool)}.", fontSize = 11.5.sp, color = Muted)
            }
        }
        ProbeLength.Result.UNKNOWN -> CheckRow(3, "Doesn't match the ${lenName(c.tool)}",
            "Setter Z $z, learned $exp (${Fmt.fixed(kotlin.math.abs(c.z - (c.expected ?: c.z)), 2)} mm off). " +
                "A different probe, or it's not seated the same way in the collet.") {
            load()
            if (allowConfirm) SmallButton("Confirm ${titleName(c.tool)}", null, outlined = true) { p.rememberLength() }
        }
        ProbeLength.Result.NEW -> CheckRow(3, "${c.tool.label} not learned yet", "It tripped the setter at Z $z.") {
            load()
            if (allowConfirm) SmallButton("Remember this length for the ${lenName(c.tool)}", null, outlined = true) { p.rememberLength() }
        }
    }
}

@Composable
internal fun Progress(stage: ToolAction.Stage, load: Boolean, name: String = "probe") {
    val steps = buildList {
        if (load) { add(ToolAction.Stage.MOVING to "Moving to the tool change spot"); add(ToolAction.Stage.WAITING to "Swap in the $name and plug in its cable, then press the button on the machine") }
        else add(ToolAction.Stage.MOVING to "Moving over the tool setter")
        add(ToolAction.Stage.CALIBRATING to "Calibrate on the tool setter")
        add(ToolAction.Stage.CHECKING to "Check the probe triggers")
    }
    Column(Modifier.padding(start = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEach { (s, t) ->
            val done = s.ordinal < stage.ordinal; val now = s == stage
            Row(verticalAlignment = Alignment.Top) {
                when {
                    done -> Icon(Icons.Filled.CheckCircle, null, tint = Green, modifier = Modifier.size(16.dp))
                    now -> Box(Modifier.padding(3.dp).size(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.primary))
                    else -> Box(Modifier.size(16.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(t, fontSize = 12.5.sp, lineHeight = 16.sp, color = when { now -> Color.Unspecified; done -> Muted; else -> Color(0xFF64748B) },
                    fontWeight = if (now) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
}
