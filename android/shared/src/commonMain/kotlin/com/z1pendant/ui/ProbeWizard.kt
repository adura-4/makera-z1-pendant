package com.z1pendant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.z1pendant.AppViewModel
import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineStatus
import com.z1pendant.platform.PlatformBackHandler
import com.z1pendant.probe.CenterAxes
import com.z1pendant.probe.Corner
import com.z1pendant.probe.PROBE_TOOL
import com.z1pendant.probe.ProbeKind
import com.z1pendant.probe.ProbeMode
import com.z1pendant.probe.ProbeResult
import com.z1pendant.probe.ProbeSetup
import com.z1pendant.probe.ProbeLength
import com.z1pendant.probe.ProbeTool
import com.z1pendant.probe.RunState
import com.z1pendant.probe.ToolAction
import com.z1pendant.util.Fmt

private val TileBg = Color(0xFF1E293B)
private val TileOn = Color(0xFF132A3E)
private val Muted = Color(0xFF94A3B8)
private val Green = Color(0xFF22C55E)
private val Amber = Color(0xFFF59E0B)
private val ErrBg = Color(0xFF3B1D22)
private val ErrFg = Color(0xFFF87171)
private val WarnBg = Color(0xFF3A2A0B)
private val WarnFg = Color(0xFFFDE68A)

/**
 * Full-screen 3D probe wizard (mockups/probe_mock.html): 1 what, 2 settings, 3 probe in and
 * calibrated, 4 position + mode + Run, 5 result.
 */
@Composable
/**
 * [requireZero]: opened from Run's 3D probe origin. The finish button ([doneLabel], "Next") only
 * goes on once an X or Y work zero has been set. [onClose] gets whether one was.
 */
fun ProbeWizard(vm: AppViewModel, doneLabel: String = "Done", requireZero: Boolean = false, onClose: (zeroSet: Boolean) -> Unit) {
    val p = vm.probe
    var step by remember { mutableStateOf(1) }
    val setup by p.setup.collectAsState()
    val run by p.run.collectAsState()
    var info by remember { mutableStateOf(false) }
    var askRun by remember { mutableStateOf(false) }
    val running = run is RunState.Running
    fun finish(zeroSet: Boolean) { if (!running) { p.closeWizard(); onClose(zeroSet) } }
    fun close() = finish(false)
    // Each step's Next only once that step is satisfied; nothing moves on while the probe is being loaded or calibrated.
    val action by p.toolAction.collectAsState()
    val acting = action?.let { it.stage != ToolAction.Stage.DONE && it.error == null } == true
    val confirmed by p.confirmed.collectAsState(); val calibrated by p.calibrated.collectAsState()
    val len by p.lengthCheck.collectAsState()
    val status by vm.link.status.collectAsState(); val linkConn by vm.link.conn.collectAsState()
    val probeReady = linkConn == ConnState.Connected && status?.tool == PROBE_TOOL && confirmed && calibrated && !acting && len?.ok != false
    LaunchedEffect(run) { if (run !is RunState.Idle) step = 5 }
    LaunchedEffect(Unit) { p.openWizard() }
    PlatformBackHandler { when { running -> {}; step in 2..4 -> step--; else -> close() } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.MyLocation, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(when (step) {
                1 -> "Probe"; 2 -> "Check the probe"; 3 -> "Move the probe into place"; 4 -> setup.title()
                else -> when (run) { is RunState.Running -> "Probing"; is RunState.Failed -> "Probing stopped"; else -> "Probe finished" }
            }, fontSize = 18.sp, maxLines = 1, modifier = Modifier.weight(1f))
            IconButton(onClick = ::close, enabled = !running) { Icon(Icons.Filled.Close, "Close") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 1..5) Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(if (i <= step) MaterialTheme.colorScheme.primary else Color(0xFF2B3648)))
        }
        if (step == 2 || step == 4) Row(Modifier.clickable { info = true }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(6.dp)); Text("How these work", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (step) {
                // The start offsets are measured from where the probe is parked, so you set them
                // after moving it into place.
                1 -> StepWhat(setup) { p.update(it) }
                2 -> StepCheck(vm)
                3 -> StepPosition(vm, setup, probeReady, onEdit = { step = 1 })
                4 -> { StepSettings(setup) { p.update(it) }; ModeAndStatus(vm) }
                // Probe again keeps the calibration (the probe stayed in), unless the run lost it (no touch).
                else -> StepResult(vm, run, doneLabel, requireZero, onAgain = { p.reset(); step = if (p.calibrated.value) 3 else 2 }, onDone = ::finish)
            }
        }
        // While probing: Stop, pinned at the bottom where your thumb is.
        if (running) Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { vm.haptics.press(); p.stop() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626), contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(58.dp)) {
                Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("Halts the machine at once (reset). Unlock afterwards.", fontSize = 11.5.sp, color = Muted)
        }
        // Bottom buttons (step 5 has its own, inside the result).
        if (step in 1..4) Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step > 1) PendantOutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Back", fontSize = 16.sp) }
            when (step) {
                1 -> Button(onClick = { step++ }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Next", fontSize = 16.sp) }
                2, 3 -> Button(onClick = { step++ }, enabled = probeReady, modifier = Modifier.weight(1f).height(48.dp)) { Text("Next", fontSize = 16.sp) }
                4 -> {
                    val mode by p.mode.collectAsState()
                    val ready = probeReady && status?.isIdle == true && settingsValid(setup)
                    Button(onClick = { askRun = true }, enabled = ready, modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(4.dp))
                        Text(if (mode == ProbeMode.MEASURE) "Measure" else "Run probe", fontSize = 16.sp)
                    }
                }
            }
        }
    }

    if (askRun) {
        val mode = p.mode.value
        AlertDialog(onDismissRequest = { askRun = false },
            title = { Text("Start probing?") },
            text = { Text(if (mode == ProbeMode.MEASURE) "The machine starts moving right away. Your work zero won't change."
                else "The machine starts moving right away, and this sets your work zero (${zeroAxesText(setup)}).") },
            confirmButton = { TextButton(onClick = { askRun = false; vm.haptics.press(); p.start() }) { Text("Start") } },
            dismissButton = { TextButton(onClick = { askRun = false }) { Text("Cancel") } })
    }
    if (info) InfoSheet(step, setup.tool) { info = false }
}

private fun zeroAxesText(s: ProbeSetup) = when (s.kind) {
    ProbeKind.TOP -> "Z"
    ProbeKind.HOLE -> listOfNotNull("X".takeIf { s.doX }, "Y".takeIf { s.doY }).joinToString(" ")
    ProbeKind.BLOCK -> listOfNotNull("X".takeIf { s.doX }, "Y".takeIf { s.doY }, "Z").joinToString(" ")
    else -> "X Y Z"
}

private fun settingsValid(s: ProbeSetup) = s.x in 0.5..200.0 && s.y in 0.5..200.0 && s.depth in 0.0..30.0 && s.tip in 0.2..12.0

// ---- step 1: what ------------------------------------------------------------------------------
@Composable
private fun Tile(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.alpha(if (enabled) 1f else 0.35f).clip(RoundedCornerShape(10.dp)).background(if (selected) TileOn else TileBg)
        .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(10.dp))
        .clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
private fun Label(t: String) = Text(t, fontSize = 12.sp, color = Muted)

@Composable
private fun StepWhat(s: ProbeSetup, onChange: (ProbeSetup) -> Unit) {
    Label("Probe")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProbeTool.entries.forEach { t ->
            Tile(s.tool == t, { onChange(s.withTool(t)) }, Modifier.weight(1f)) {
                ProbeToolArt(t, 40.dp)
                Text(t.label, fontSize = 14.sp, color = if (s.tool == t) MaterialTheme.colorScheme.primary else Color.Unspecified)
            }
        }
    }
    when (s.tool) {
        ProbeTool.ROD -> Text("The probe rod only triggers on conductive (metal) parts.", fontSize = 12.sp, color = Muted)
        ProbeTool.LASER -> Text("The laser probe only senses straight down, so it can only do Top only.", fontSize = 12.sp, color = Muted)
        else -> {}
    }
    Label("Probing operation")
    ProbeKind.entries.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { k ->
                val sel = s.kind == k
                Tile(sel, { onChange(s.copy(kind = k)) }, Modifier.weight(1f), enabled = !s.tool.topOnly || k == ProbeKind.TOP) {
                    if (k == ProbeKind.HOLE || k == ProbeKind.BLOCK) Row { ProbeKindArt(k, 60.dp); ProbeKindArt(k, 60.dp, square = true) }
                    else ProbeKindArt(k, 64.dp)
                    Text(k.label, fontSize = 14.sp, color = if (sel) MaterialTheme.colorScheme.primary else Color.Unspecified)
                    if (k == ProbeKind.HOLE || k == ProbeKind.BLOCK) Text("round or square", fontSize = 11.sp, color = Muted)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    if (s.kind.hasCorner) {
        Label("Corner")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Corner.BACK_LEFT, Corner.BACK_RIGHT, Corner.FRONT_LEFT, Corner.FRONT_RIGHT).forEach { c ->
                val sel = s.corner == c
                Tile(sel, { onChange(s.copy(corner = c)) }, Modifier.weight(1f)) {
                    CornerArt(c, s.kind == ProbeKind.INSIDE, 44.dp)
                    Text(c.label, fontSize = 11.sp, color = if (sel) MaterialTheme.colorScheme.primary else Color.Unspecified)
                }
            }
        }
    }
    if (s.kind.hasCenterAxes) {
        Label("Axis")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CenterAxes.entries.forEach { a ->
                val sel = s.axes == a
                Tile(sel, { onChange(s.copy(axes = a)) }, Modifier.weight(1f)) {
                    Text(a.label, fontSize = 14.sp, color = if (sel) MaterialTheme.colorScheme.primary else Color.Unspecified)
                }
            }
        }
    }
}

// ---- step 2: settings ------------------------------------------------------------------------
@Composable
private fun StepSettings(s: ProbeSetup, onChange: (ProbeSetup) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ProbeKindArt(s.kind, 96.dp, square = false)
    }
    if (s.kind == ProbeKind.TOP) {
        Text("Top only touches straight down, so there's nothing to set.", fontSize = 14.sp, color = Muted)
        return
    }
    Text("Distances are from where the probe is now.", fontSize = 12.5.sp, color = Muted)
    if (s.doX) NumField(FieldArt.X, s.tool, "X start offset", s.x, 0) { onChange(s.copy(x = it)) }
    if (s.doY) NumField(FieldArt.Y, s.tool, "Y start offset", s.y, 0) { onChange(s.copy(y = it)) }
    if (s.kind != ProbeKind.HOLE) NumField(FieldArt.DEPTH, s.tool, "Side depth", s.depth, 3) { onChange(s.copy(depth = it)) }
    NumField(FieldArt.TIP, s.tool, "Tip diameter", s.tip, 3) { onChange(s.copy(tip = it)) }
}

/** A setting with its drawing in a tile; [decimals] = how it's shown once you leave the box (0 = as typed). */
@Composable
private fun NumField(art: FieldArt, tool: ProbeTool, label: String, value: Double, decimals: Int, onValue: (Double) -> Unit) {
    fun show(v: Double) = if (decimals > 0) Fmt.fixed(v, decimals) else mmText(v)
    var text by remember { mutableStateOf(show(value)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(width = 98.dp, height = 78.dp).clip(RoundedCornerShape(10.dp)).background(TileBg)
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp)).padding(5.dp)) {
            FieldArtImage(art, Modifier.fillMaxSize(), tool)
        }
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        // A compact rounded box like the mock-up (Material's text field is tall and square-cornered):
        // the number right-aligned, "mm" small on its baseline, blue border while editing.
        var focused by remember { mutableStateOf(false) }
        val bad = text.toDoubleOrNull() == null
        val primary = MaterialTheme.colorScheme.primary
        androidx.compose.foundation.text.BasicTextField(text, { t ->
            text = t.replace(',', '.')
            text.toDoubleOrNull()?.let(onValue)
        }, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.End),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(108.dp).onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) text.toDoubleOrNull()?.let { v -> text = show(v) }
            },
            decorationBox = { inner ->
                Row(Modifier.height(42.dp).clip(RoundedCornerShape(10.dp))
                    .border(if (focused || bad) 2.dp else 1.dp, if (bad) ErrFg else if (focused) primary else Color(0xFF5B6474), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Box(Modifier.weight(1f).alignByBaseline(), contentAlignment = Alignment.CenterEnd) { inner() }
                        Spacer(Modifier.width(4.dp))
                        Text("mm", fontSize = 12.sp, color = Muted, modifier = Modifier.alignByBaseline())
                    }
                }
            })
    }
}

// ---- step 3: probe in, calibrated ------------------------------------------------------------
@Composable
private fun CheckRow(state: Int, title: String, sub: String?, dim: Boolean = false, body: @Composable ColumnScope.() -> Unit = {}) {
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
private fun SmallButton(text: String, icon: (@Composable () -> Unit)?, outlined: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        icon?.let { it(); Spacer(Modifier.width(6.dp)) }; Text(text, fontSize = 15.sp)
    }
    if (outlined) PendantOutlinedButton(onClick, Modifier.fillMaxWidth().height(42.dp), enabled = enabled, content = content)
    else Button(onClick, Modifier.fillMaxWidth().height(42.dp), enabled = enabled, content = content)
}

@Composable
private fun StepCheck(vm: AppViewModel) {
    val p = vm.probe
    val st by vm.link.status.collectAsState()
    val conn by vm.link.conn.collectAsState()
    val confirmed by p.confirmed.collectAsState()
    val calibrated by p.calibrated.collectAsState()
    val action by p.toolAction.collectAsState()
    val len by p.lengthCheck.collectAsState()
    val setup by p.setup.collectAsState()
    val name = setup.tool.label
    val tool = st?.tool
    val connected = conn == ConnState.Connected
    val idle = st?.isIdle == true
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
            Progress(action!!.stage, load = true, setup.tool.noun)
            if (action!!.stage == ToolAction.Stage.WAITING) SmallButton("I've swapped it · continue", null, outlined = true) { p.continueToolChange() }
        }
        tool == PROBE_TOOL && confirmed -> CheckRow(2, "$name in the spindle", "T$PROBE_TOOL")
        tool == PROBE_TOOL -> CheckRow(1, "$name in the spindle", "The machine says a probe (T$PROBE_TOOL) is in. The number can be left over from last time: is the ${setup.tool.noun} in?") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { p.confirmed.value = true }, modifier = Modifier.weight(1f).height(42.dp)) { Text("Yes, it's in") }
                PendantOutlinedButton(onClick = {
                    // M6 T9999 does nothing while the machine already says T9999: mark the spindle empty first.
                    vm.link.sendLine("M493.2 T-1"); p.loadProbe()
                }, enabled = connected && idle, modifier = Modifier.weight(1f).height(42.dp)) { Text("No, load it") }
            }
        }
        else -> CheckRow(3, "$name in the spindle", if (tool == null) "The machine hasn't reported a tool." else if (tool < 0) "The spindle is empty." else "The machine has T$tool in.") {
            SmallButton("Load the ${setup.tool.noun}", { Icon(Icons.Filled.SwapHoriz, null) }, enabled = connected && idle) { p.loadProbe() }
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
            SmallButton("Calibrate again", { CalibrateIcon(primary) }, outlined = true, enabled = connected && idle) { p.calibrate() }
        }
        else -> CheckRow(1, "Calibrated on the tool setter", "Every time you probe: it checks the probe triggers and which probe is in.") {
            SmallButton("Calibrate now", { CalibrateIcon(onPrimary) }, enabled = connected && idle) { p.calibrate() }
            Text("The machine moves over the tool setter and lowers the probe onto it.", fontSize = 11.5.sp, color = Muted)
        }
    }
    // Row 3: it's the probe you picked, going by where it trips the setter.
    if (calibrated && !acting) len?.let { LengthRow(it, p, setup.kind, connected && idle) }
}

private fun lenName(t: ProbeTool) = t.noun
/** "3D Probe", "Probe Rod", "Laser Probe": for status lines like "3D Probe Confirmed". */
private fun titleName(t: ProbeTool) = t.label.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

/** Which probe the setter says is in: each probe trips it at its own Z (learned on its first calibration). */
@Composable
private fun LengthRow(c: ProbeLength.Check, p: com.z1pendant.probe.ProbeController, kind: ProbeKind, canMove: Boolean) {
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
            if (c.other.canDo(kind)) SmallButton("Use the ${lenName(c.other)} instead", null, outlined = true) { p.switchToMatch() }
            else Text("The ${lenName(c.other)} can't do ${kind.label.lowercase()}: swap in the ${lenName(c.tool)}.", fontSize = 11.5.sp, color = Muted)
        }
        ProbeLength.Result.UNKNOWN -> CheckRow(3, "Doesn't match the ${lenName(c.tool)}",
            "Setter Z $z, learned $exp (${Fmt.fixed(kotlin.math.abs(c.z - (c.expected ?: c.z)), 2)} mm off). " +
                "A different probe, or it's not seated the same way in the collet.") {
            load()
            SmallButton("Confirm ${titleName(c.tool)}", null, outlined = true) { p.rememberLength() }
        }
        ProbeLength.Result.NEW -> CheckRow(3, "${c.tool.label} not learned yet", "It tripped the setter at Z $z.") {
            load()
            SmallButton("Remember this length for the ${lenName(c.tool)}", null, outlined = true) { p.rememberLength() }
        }
    }
}

@Composable
private fun Progress(stage: ToolAction.Stage, load: Boolean, name: String = "probe") {
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

// ---- step 4: position ------------------------------------------------------------------------
@Composable
private fun MiniReadout(st: MachineStatus?, connected: Boolean, selected: Char?, modifier: Modifier = Modifier.fillMaxWidth(), onSelect: (Char) -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 2.dp)) {
        listOf('X', 'Y', 'Z').forEachIndexed { i, a ->
            val sel = a == selected
            Row(Modifier.fillMaxWidth().clickable { onSelect(a) }
                .background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                .padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(a.toString(), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (sel) MaterialTheme.colorScheme.primary else Muted, modifier = Modifier.width(26.dp))
                Text(fmtPos(st?.wpos?.getOrNull(i), connected), fontSize = 24.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}

private fun hint(s: ProbeSetup): String = when (s.kind) {
    ProbeKind.OUTSIDE -> "Jog the ball about 5 mm above the part, about 5 mm in from the ${s.corner.label} corner."
    ProbeKind.INSIDE -> "Jog the ball about 5 mm above the top, over the part just outside the pocket's ${s.corner.label} corner."
    ProbeKind.HOLE -> "Jog the ball into the hole, roughly centered, at least 2 mm below its edge."
    ProbeKind.BLOCK -> "Jog the ball about 5 mm above the block, roughly over its center."
    ProbeKind.TOP -> "Jog the ball about 5 mm above where you want Z0."
}

@Composable
private fun StepPosition(vm: AppViewModel, s: ProbeSetup, probeReady: Boolean, onEdit: () -> Unit) {
    val p = vm.probe
    val st by vm.link.status.collectAsState()
    val conn by vm.link.conn.collectAsState()
    val axis by vm.axis.collectAsState()
    val padMode by vm.jogPad.collectAsState()
    val stepIdx by vm.stepIndex.collectAsState()
    val degPerClick by vm.degPerClick.collectAsState()
    val mode by p.mode.collectAsState()
    val connected = conn == ConnState.Connected
    val stepList = vm.steps
    val si = stepIdx.coerceIn(0, stepList.lastIndex)

    MiniReadout(st, connected, if (padMode) null else axis) { vm.selectAxis(it); vm.haptics.press() }
    Segmented(stepList.map { stepLabel(it) }, si) { vm.selectStep(it); vm.haptics.press() }
    Box(Modifier.fillMaxWidth().height(210.dp)) {
        if (!padMode) Handwheel(degPerClick = degPerClick, axis = axis,
            stepText = "${stepLabel(stepList[si])}${if (axis == 'A') "°" else "mm"}/click",
            enabled = connected, onClick = { vm.wheelClicks(it) }, modifier = Modifier.size(210.dp).align(Alignment.Center))
        else JogPad(vm, step = stepList[si], enabled = connected, modifier = Modifier.align(Alignment.Center))
        ModeButton(padMode, Modifier.align(Alignment.TopStart)) { vm.setJogPad(!padMode) }
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(TileBg)) {
        Box(Modifier.width(4.dp).height(56.dp).background(MaterialTheme.colorScheme.primary))
        Text(hint(s), fontSize = 13.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(10.dp))
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(TileBg).clickable(onClick = onEdit).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ProbeKindArt(s.kind, 44.dp); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("${s.tool.label} · ${s.title()}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(summary(s), fontSize = 12.sp, color = Muted)
        }
        Text("edit", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
    }
    if (!connected) Text("Not connected.", fontSize = 12.sp, color = Amber)
    else if (!probeReady) Text("The probe isn't loaded and calibrated: go back to Check the probe.", fontSize = 12.sp, color = Amber)
}

/** Under the settings: Makera's own routine (sets the zero) or the pendant's touches (measure only), and why Run is off. */
@Composable
private fun ModeAndStatus(vm: AppViewModel) {
    val p = vm.probe
    val st by vm.link.status.collectAsState()
    val conn by vm.link.conn.collectAsState()
    val mode by p.mode.collectAsState()
    val connected = conn == ConnState.Connected
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf(ProbeMode.SET_ZERO to ("Set work zero" to Icons.Filled.MyLocation), ProbeMode.MEASURE to ("Measure only" to Icons.Filled.SquareFoot)).forEach { (m, li) ->
            val sel = mode == m
            Row(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(9.dp)).background(if (sel) Color(0xFF173A55) else Color.Transparent)
                .clickable { p.mode.value = m; vm.haptics.press() }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(li.second, null, tint = if (sel) MaterialTheme.colorScheme.primary else Muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(li.first, fontSize = 13.5.sp, color = if (sel) MaterialTheme.colorScheme.primary else Muted, fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
    val ok by p.confirmed.collectAsState(); val cal by p.calibrated.collectAsState()
    when {
        !connected -> Text("Not connected.", fontSize = 12.sp, color = Amber)
        p.toolAction.value?.let { it.stage != ToolAction.Stage.DONE && it.error == null } == true ->
            Text("The probe is being loaded or calibrated: wait for it to finish.", fontSize = 12.sp, color = Muted)
        st?.tool != PROBE_TOOL || !ok || !cal -> Text("The probe isn't loaded and calibrated: go back to Check the probe.", fontSize = 12.sp, color = Amber)
        p.lengthCheck.value?.ok == false -> Text("The probe in the spindle doesn't look like the ${p.setup.value.tool.noun}: go back to Check the probe.", fontSize = 12.sp, color = Amber)
        st?.isIdle != true -> Text("Waiting for the machine to be Idle (${st?.state}).", fontSize = 12.sp, color = Muted)
    }
}

/** A distance as typed: 20 rather than 20.0, 12.5 stays 12.5. */
private fun mmText(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else Fmt.trimmed4(v)

private fun summary(s: ProbeSetup): String = when (s.kind) {
    ProbeKind.TOP -> "straight down"
    ProbeKind.HOLE -> listOfNotNull("X ${mmText(s.x)}".takeIf { s.doX }, "Y ${mmText(s.y)}".takeIf { s.doY }, "tip ${Fmt.fixed(s.tip, 3)} mm").joinToString(" · ")
    else -> listOfNotNull("X ${mmText(s.x)}".takeIf { s.doX }, "Y ${mmText(s.y)}".takeIf { s.doY },
        "depth ${Fmt.fixed(s.depth, 3)}", "tip ${Fmt.fixed(s.tip, 3)} mm").joinToString(" · ")
}

// ---- step 5: result --------------------------------------------------------------------------
@Composable
private fun StepResult(vm: AppViewModel, run: RunState, doneLabel: String, requireZero: Boolean, onAgain: () -> Unit, onDone: (zeroSet: Boolean) -> Unit) {
    val st by vm.link.status.collectAsState()
    val conn by vm.link.conn.collectAsState()
    val connected = conn == ConnState.Connected
    when (run) {
        is RunState.Running -> {
            // Option E: what you're probing, the run from above, the step list, the position as
            // chips. Stop is pinned at the bottom of the screen (see ProbeWizard).
            val setup by vm.probe.setup.collectAsState()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(setup.title(), fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(if (run.setMode) "Set work zero" else "Measure only", fontSize = 13.sp, color = Muted)
            }
            ProbeRunArt(setup, run.labels, run.index, Modifier.fillMaxWidth().height(240.dp), stepTouch = run.touch)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(TileBg).padding(horizontal = 12.dp, vertical = 6.dp)) {
                run.labels.forEachIndexed { i, label ->
                    val done = i < run.index; val now = i == run.index
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
                        if (done) doneNote(label, run.setMode)?.let { Text(it, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Muted) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                listOf('X', 'Y', 'Z').forEachIndexed { i, a ->
                    Row(Modifier.clip(RoundedCornerShape(50)).background(TileBg).padding(horizontal = 11.dp, vertical = 5.dp)) {
                        Text("$a ", fontSize = 13.sp, color = Muted)
                        Text(fmtPos(st?.mpos?.getOrNull(i), connected).trim(), fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFCBD5E1))
                    }
                }
            }
        }
        is RunState.Done -> ResultView(vm, run.result, st, connected, doneLabel, requireZero, onAgain, onDone)
        is RunState.Failed -> {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ErrBg).padding(12.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Error, null, tint = ErrFg, modifier = Modifier.size(26.dp)); Spacer(Modifier.width(8.dp))
                Text(run.reason, fontSize = 15.sp, lineHeight = 20.sp)
            }
            CheckRowIcon(Icons.Filled.Info, "What changed", run.changed)
            run.hint?.let { CheckRowIcon(Icons.Filled.Help, "Likely cause", it) }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PendantOutlinedButton(onClick = { onDone(false) }, modifier = Modifier.weight(1f).height(48.dp)) { Text(if (requireZero) "Back" else doneLabel, fontSize = 16.sp) }
                if (st?.isAlarm == true) Button(onClick = { vm.link.dialect.unlock().forEach { vm.link.sendLine(it) }; onAgain() }, modifier = Modifier.weight(1f).height(48.dp)) {
                    Icon(Icons.Filled.LockOpen, null); Spacer(Modifier.width(4.dp)); Text("Unlock, retry", fontSize = 16.sp)
                } else Button(onClick = onAgain, modifier = Modifier.weight(1f).height(48.dp)) { Text("Try again", fontSize = 16.sp) }
            }
        }
        RunState.Idle -> Text("Nothing running.", color = Muted)
    }
}

@Composable
private fun CheckRowIcon(icon: ImageVector, title: String, text: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TileBg).padding(12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = Muted, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
        Column { Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Medium); Text(text, fontSize = 13.sp, color = Muted, lineHeight = 17.sp) }
    }
}

@Composable
private fun Cells(cells: List<Pair<String, Double?>>) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(TileBg).padding(10.dp)) {
        cells.forEach { (l, v) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(l, fontSize = 11.sp, color = Muted)
                Text(v?.let { Fmt.fixed(it, 3) } ?: "—", fontSize = 17.sp, fontFamily = FontFamily.Monospace, color = if (v == null) Color(0xFF475569) else Color.Unspecified)
            }
        }
    }
}

@Composable
private fun ResultView(vm: AppViewModel, r: ProbeResult, st: MachineStatus?, connected: Boolean, doneLabel: String, requireZero: Boolean,
                       onAgain: () -> Unit, onDone: (zeroSet: Boolean) -> Unit) {
    val s = r.setup
    var undone by remember { mutableStateOf(false) }
    // Picked to start with: every axis the run measured, the same set Makera's own routine writes in
    // Set work zero (corners and block center set X, Y and Z; a hole has no top, so X and Y). Untick
    // Z when Z0 belongs on another surface (Jimmy, 2026-11-18).
    var setAxes by remember { mutableStateOf(listOfNotNull('X'.takeIf { r.zeroX != null }, 'Y'.takeIf { r.zeroY != null },
        'Z'.takeIf { r.top != null }).toSet()) }
    var didSet by remember { mutableStateOf(false) }
    val center = s.kind == ProbeKind.HOLE || s.kind == ProbeKind.BLOCK
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.CheckCircle, null, tint = Green, modifier = Modifier.size(28.dp)); Spacer(Modifier.width(8.dp))
        Text(if (r.mode == ProbeMode.MEASURE) "Measured. Your work zero hasn't changed." else "Work zero is set. ${whereText(r)}",
            fontSize = 15.sp, lineHeight = 20.sp)
    }
    if (r.mode == ProbeMode.SET_ZERO) {
        Label("Where the probe is now (work position)")
        // Narrower here (nothing to tap), so the letters sit close to their values.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { MiniReadout(st, connected, null, Modifier.fillMaxWidth(0.62f)) {} }
    } else {
        Label("From your current zero")
        Cells(listOfNotNull(
            ((if (center) "Center X" else "Corner X") to r.zeroX).takeIf { s.kind != ProbeKind.TOP && s.doX },
            ((if (center) "Center Y" else "Corner Y") to r.zeroY).takeIf { s.kind != ProbeKind.TOP && s.doY },
            ("Top Z" to r.top).takeIf { s.kind.hasTop }))
    }
    if (center) {
        Label("Measured from the touches")
        Cells(listOf("Width in X" to r.widthX, "Width in Y" to r.widthY))
    }
    if (r.mode == ProbeMode.SET_ZERO && ('X' in r.zeroSet || 'Y' in r.zeroSet) && !undone) {
        Row(Modifier.clickable { vm.probe.undoXY(r); undone = true; vm.haptics.press() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Undo, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(6.dp))
            Text("Put the old ${r.zeroSet.filter { it != 'Z' }.joinToString(" ")} zero back", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
        }
    }
    if (undone) Text("The old X Y zero is back.", fontSize = 13.sp, color = Muted)
    if (r.mode == ProbeMode.MEASURE && !didSet) {
        val avail = listOfNotNull('X'.takeIf { r.zeroX != null }, 'Y'.takeIf { r.zeroY != null }, 'Z'.takeIf { r.top != null })
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TileBg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.MyLocation, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                Column { Text("Set as work zero?", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("Pick the axes, then Set. Leave it if you only wanted to measure.", fontSize = 12.5.sp, color = Muted) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 30.dp)) {
                avail.forEach { a ->
                    val on = a in setAxes
                    Box(Modifier.clip(RoundedCornerShape(50)).background(if (on) TileOn else Color.Transparent)
                        .border(1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
                        .clickable { setAxes = if (on) setAxes - a else setAxes + a }.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        Text((if (on) "✓ " else "") + a, color = if (on) MaterialTheme.colorScheme.primary else Muted, fontSize = 15.sp)
                    }
                }
            }
            Button(onClick = { vm.probe.setZero(r, setAxes); didSet = true; vm.haptics.press() }, enabled = setAxes.isNotEmpty() && connected && st?.isIdle == true,
                modifier = Modifier.fillMaxWidth().height(42.dp)) {
                Icon(Icons.Filled.MyLocation, null); Spacer(Modifier.width(6.dp))
                Text("Set ${listOf('X', 'Y', 'Z').filter { it in setAxes }.joinToString(" ")} zero here")
            }
        }
    }
    if (didSet) Text("Work zero set on ${listOf('X', 'Y', 'Z').filter { it in setAxes }.joinToString(" ")}.", fontSize = 13.sp, color = Green)
    // An X or Y work zero came out of this run (and wasn't put back).
    val xySet = (r.mode == ProbeMode.SET_ZERO && ('X' in r.zeroSet || 'Y' in r.zeroSet) && !undone) ||
        (didSet && ('X' in setAxes || 'Y' in setAxes))
    if (requireZero && !xySet) Text("Set the X/Y work zero to go on.", fontSize = 13.sp, color = Muted)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PendantOutlinedButton(onClick = onAgain, modifier = Modifier.weight(1f).height(48.dp)) { Text("Probe again", fontSize = 16.sp) }
        Button(onClick = { onDone(xySet) }, enabled = !requireZero || xySet, modifier = Modifier.weight(1f).height(48.dp)) {
            Text(if (!requireZero && r.mode == ProbeMode.MEASURE && !didSet) "$doneLabel, don't set" else doneLabel, fontSize = 16.sp)
        }
    }
}

/** Beside a finished step: what it set (Makera / Top only) or that it was found (Measure only). */
private fun doneNote(label: String, setMode: Boolean): String? {
    val s = com.z1pendant.probe.Steps
    if (label == s.CORNER || label == s.CENTER) return null
    if (!setMode) return "found"
    return when (label) {
        s.TOP -> "Z0 set"
        s.X_SIDE, s.X_WALL, s.X_WALLS, s.X_SIDES -> "X0 set"
        else -> "Y0 set"
    }
}

private fun whereText(r: ProbeResult): String {
    val s = r.setup; val z = r.zeroSet
    val xy = listOf('X', 'Y').filter { it in z }.joinToString(" ") { "${it}0" }
    val place = when (s.kind) {
        ProbeKind.OUTSIDE, ProbeKind.INSIDE -> "at the ${s.corner.label} corner"
        ProbeKind.HOLE -> "at the center of the hole"
        ProbeKind.BLOCK -> "at the center of the block"
        ProbeKind.TOP -> ""
    }
    return listOfNotNull(xy.takeIf { it.isNotEmpty() }?.let { "$it $place" }, "Z0 on the top".takeIf { 'Z' in z })
        .joinToString(", ").ifEmpty { "Nothing changed." } + "."
}

// ---- info sheet ------------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoSheet(step: Int, tool: ProbeTool, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Color(0xFF172033)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("How these work", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            when (step) {
                4 -> {
                    InfoItem(FieldArt.X, tool, "X and Y start offset", "You park the probe over the part. It moves out this far, goes down, then comes back in until it touches the edge. Make it more than the distance from where you parked to the edge, plus a few mm. For a hole it's how far it searches each way; for an inside corner, how far it moves into the pocket.")
                    InfoItem(FieldArt.DEPTH, tool, "Side depth", "How far below the top surface the ball goes before it touches the sides. Decimals are fine (1.5). Go deeper if the edge is rounded or chamfered.")
                    InfoItem(FieldArt.TIP, tool, "Tip diameter", "The ball's size. It touches with the side of the ball, so half of this is added to find the real edge. Measure it with calipers if you can.")
                    InfoText("Set work zero", "Makera's own probing routine (M480). It touches and writes the new work zero itself, exactly as Makera Studio does.")
                    InfoText("Measure only", "The pendant makes the same touches itself and doesn't change anything. The next screen shows what it found, and you can set any of the axes as work zero from there.")
                }
                else -> {
                    InfoText("${tool.label} in the spindle", "The machine keeps a tool number, and it can be left over from last time. Load the ${tool.noun} runs Makera's tool change (M6 T9999): it waits while you swap, then calibrates the probe on the tool setter and checks it triggers. T9999 also keeps the probe powered and turns on the machine's stop for an unexpected probe touch.")
                    InfoText("Calibrated on the tool setter", "Every time you open probing, the probe is calibrated on the tool setter first. That does three things: the probe has to trigger on the setter (a dead probe or unplugged cable stops here), it measures the probe's length so the Z0 it sets carries over to your cutters, and where it trips the setter says which probe is in.")
                    InfoText("Which probe is in", "The 3D probe, the probe rod and the laser probe each trip the setter at their own height. The first calibration of each one learns it. If the reading belongs to a different probe than the one you picked, swap in the right one; or use the one that's in, if it can do what you picked (all three can do Top only).")
                }
            }
        }
    }
}

@Composable
private fun InfoItem(art: FieldArt, tool: ProbeTool, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(width = 66.dp, height = 56.dp).clip(RoundedCornerShape(10.dp)).background(TileBg).border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp)).padding(4.dp)) {
            FieldArtImage(art, Modifier.fillMaxSize(), tool)
        }
        Spacer(Modifier.width(12.dp))
        Column { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium); Text(text, fontSize = 13.sp, color = Color(0xFFCBD5E1), lineHeight = 18.sp) }
    }
}

@Composable
private fun InfoText(title: String, text: String) {
    Column { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium); Text(text, fontSize = 13.sp, color = Color(0xFFCBD5E1), lineHeight = 18.sp) }
}

/** The probe button on the main screen (top-right of the dial, mirroring the dial / pad switch). */
@Composable
fun ProbeButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.MyLocation, "3D probe", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
    }
}
