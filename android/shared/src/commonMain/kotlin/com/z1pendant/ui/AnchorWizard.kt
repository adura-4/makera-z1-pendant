package com.z1pendant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.key
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.z1pendant.probe.ProbeSetup
import com.z1pendant.probe.awaitingSwap
import com.z1pendant.probe.resetSummary
import com.z1pendant.probe.SETTINGS_DECIMALS
import com.z1pendant.probe.TIP_DECIMALS
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.z1pendant.AppViewModel
import com.z1pendant.link.ConnState
import com.z1pendant.platform.PlatformBackHandler
import com.z1pendant.probe.AnchorResult
import com.z1pendant.probe.AnchorSetup
import com.z1pendant.probe.AnchorState
import com.z1pendant.probe.AnchorStep
import com.z1pendant.probe.Bracket
import com.z1pendant.probe.ToolAction
import com.z1pendant.probe.ProbeTool
import com.z1pendant.probe.BED_CLEAR_LEAD
import com.z1pendant.probe.BED_CLEAR_REST
import com.z1pendant.probe.WRONG_PROBE_NOTE
import com.z1pendant.probe.ZCorrection
import com.z1pendant.probe.canConfirmRetry
import com.z1pendant.probe.probeRejected
import com.z1pendant.probe.canStart
import com.z1pendant.probe.savable
import com.z1pendant.probe.changedText
import com.z1pendant.probe.label
import com.z1pendant.probe.message
import com.z1pendant.probe.question
import com.z1pendant.probe.rows
import com.z1pendant.probe.SetupField
import com.z1pendant.probe.SetupIssue
import com.z1pendant.probe.setupProblems
import com.z1pendant.probe.setupWarnings
import com.z1pendant.util.Fmt
import kotlinx.coroutines.launch

private val WarnBg = Color(0xFF3A2A0B)
private val WarnFg = Color(0xFFFDE68A)

// One vertical rhythm for the run and settings pages.
private val GroupGap = 12.dp
private val LabelGap = 6.dp
// The page column already adds 8 dp above the pinned block; this tops it up to a GroupGap.
private val BottomGap = GroupGap - 8.dp

/** A view onto `AnchorController`; [startAtSetup] opens on the settings, and Done then closes. */
@Composable
fun AnchorWizard(vm: AppViewModel, startAtSetup: Boolean = false, onClose: () -> Unit) {
    val a = vm.anchor
    val state by a.state.collectAsState()
    val setup by a.setup.collectAsState()
    val probeSetup by vm.probe.setup.collectAsState()
    val st by vm.link.status.collectAsState()
    val conn by vm.link.conn.collectAsState()
    val scope = rememberCoroutineScope()
    var inSetup by remember { mutableStateOf(startAtSetup) }
    // Edits are kept here and only saved while valid, so an unsafe offset is never stored.
    var draft by remember { mutableStateOf(setup) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val running = state is AnchorState.Running
    val tip = probeSetup.tip
    val s = state
    val connected = conn == ConnState.Connected
    // Every start, the retry included, needs the same gate: both re-home and re-run the whole thing.
    val ready = canStart(connected, st?.isIdle == true, setup, tip)

    // A pending Pre-position Z question is answered before the wizard closes.
    var closeAfterFix by remember { mutableStateOf(false) }
    fun leave() { a.forgetRun(); if (a.zCorrection.value != null) closeAfterFix = true else onClose() }
    fun close() { if (!running) { a.reset(); leave() } }
    fun backToRun() { a.reset(); a.ask() }
    LaunchedEffect(state, inSetup) { if (state is AnchorState.Idle && !inSetup) a.ask() }
    PlatformBackHandler { when { running -> {}; inSetup && !startAtSetup -> { inSetup = false; backToRun() }; else -> close() } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(CalAnchorIcon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(when {
                running -> "Calibrating Anchor 1"
                s is AnchorState.Failed -> "Calibration Stopped"
                s is AnchorState.Done -> "Anchor 1 Measured"
                inSetup -> "Anchor 1 Calibration Settings"
                else -> "Calibrate Anchor 1"
            }, fontSize = 18.sp, maxLines = 1, modifier = Modifier.weight(1f))
            IconButton(onClick = ::close, enabled = !running) { Icon(Icons.Filled.Close, "Close") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                s is AnchorState.Running -> RunningPage(vm, s)
                s is AnchorState.Failed -> FailedPage(vm, s, connected, ready, onClose = ::close, onAgain = ::backToRun,
                    onConfirmRetry = {
                        val b = s.bracket; val w = s.wasteboard
                        if (b != null && w != null) { vm.probe.rememberLength(); a.start(b, w) }
                    })
                s is AnchorState.Done -> Text("The corner was found. Save it to the machine's config in the dialog.", fontSize = 14.sp, color = Muted)
                inSetup -> SetupPage(draft, tip, startAtSetup,
                    onChange = { v ->
                        draft = v
                        if (v.savable(tip)) a.update(v)
                    },
                    onTip = { if (it > 0.0) vm.probe.update(probeSetup.copy(tip = it)) },
                    onReset = {
                        vm.probe.update(probeSetup.copy(tip = ProbeSetup().tip))
                        draft = AnchorSetup(); a.update(draft)
                    })
                s is AnchorState.Asking -> AskPage(setup, tip, s, onChoose = { b, w -> a.choose(b, w) },
                    onSettings = { draft = setup; inSetup = true })
                else -> {}
            }
        }
        when {
            // Stop, pinned at the bottom where your thumb is (as in the probe wizard).
            s is AnchorState.Running -> Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { vm.haptics.press(); a.stop() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626), contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(58.dp)) {
                    Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("Stop halts the machine immediately, ending calibration.", fontSize = 11.5.sp, color = Muted)
            }
            inSetup && s !is AnchorState.Failed && s !is AnchorState.Done -> {
                val ok = draft.savable(tip)
                Button(onClick = { a.update(draft); if (startAtSetup) close() else { inSetup = false; backToRun() } }, enabled = ok,
                    modifier = Modifier.fillMaxWidth().padding(top = BottomGap, bottom = 12.dp).height(48.dp)) { Text("Done", fontSize = 16.sp) }
            }
            s is AnchorState.Asking -> {
                Column(Modifier.fillMaxWidth().padding(top = BottomGap, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BedClearWarning()
                    NotReadyNote(connected, st?.state, st?.isIdle == true)
                    Button(onClick = { vm.haptics.press(); a.start(s.bracket, s.wasteboard) },
                        enabled = ready, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Start", fontSize = 16.sp)
                    }
                }
            }
        }
    }

    val zFix by a.zCorrection.collectAsState()
    // Housekeeping, so it waits until the result and restart dialogs are gone.
    if (s !is AnchorState.Done && s !is AnchorState.Running) zFix?.let { z ->
        fun finish() { if (closeAfterFix) { closeAfterFix = false; onClose() } }
        ZCorrectionDialog(z,
            onNotNow = { a.declineZCorrection(); finish() },
            onUpdate = { a.acceptZCorrection(); finish() })
    }

    if (s is AnchorState.Done) {
        if (!saved) ResultDialog(s.result, saving,
            onSave = { saving = true; scope.launch { val ok = a.save(s.result); saving = false; if (ok) saved = true } },
            onCancel = ::backToRun)
        else RestartDialog(
            onRestart = { a.restart(); saved = false; a.reset(); leave() },
            onClose = { saved = false; a.reset(); leave() })
    }
}

@Composable
private fun NotReadyNote(connected: Boolean, state: String?, idle: Boolean) {
    if (!connected) Text("Not connected.", fontSize = 12.sp, color = Amber)
    else if (!idle) Text("The machine is ${state ?: "busy"}, not Idle.", fontSize = 12.sp, color = Amber)
}

/** Verbatim wording (see `BED_CLEAR_LEAD`): do not shorten or re-split. */
@Composable
private fun BedClearWarning() {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(WarnBg).padding(10.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Warning, null, tint = WarnFg); Spacer(Modifier.width(8.dp))
        Text(buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(BED_CLEAR_LEAD)
            }
            append(BED_CLEAR_REST)
        }, fontSize = 14.sp, lineHeight = 19.sp, color = WarnFg, modifier = Modifier.weight(1f))
    }
}

// ---- settings ---------------------------------------------------------------------------------
@Composable
internal fun SetupPage(s: AnchorSetup, tip: Double, fromArranging: Boolean, onChange: (AnchorSetup) -> Unit,
                      onTip: (Double) -> Unit, onReset: () -> Unit) {
    val d = SETTINGS_DECIMALS
    // Bumping this rebuilds every field so each shows the restored value.
    var resetKey by remember { mutableStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        Text("Offsets are measured from the anchor bracket's inside corner.", fontSize = 12.5.sp, color = Muted)
        val problems = s.setupProblems(tip)
        val warnings = s.setupWarnings(tip)
        // Only a message tied to no field sits up here; the rest follow their own field.
        problems.filter { it.field == null }.forEach { ProblemNote(it.message) }
        if (problems.isNotEmpty()) Text("These settings aren't saved until they're fixed.", fontSize = 12.sp, color = Muted)
        key(resetKey) {
            SettingField("X start offset", s.xOff, d, notes = { FieldNotes(problems, warnings, SetupField.X_OFF) }) { onChange(s.copy(xOff = it)) }
            SettingField("Y start offset", s.yOff, d, notes = { FieldNotes(problems, warnings, SetupField.Y_OFF) }) { onChange(s.copy(yOff = it)) }
            SettingField("Side probing depth", s.depth, d, notes = { FieldNotes(problems, warnings, SetupField.DEPTH) }) { onChange(s.copy(depth = it)) }
            SettingField("Tip diameter", tip, TIP_DECIMALS, notes = { FieldNotes(problems, warnings, SetupField.TIP) }, onValue = onTip)
            SettingField("Pre-position X", s.preX, d, keepSign = true, notes = { FieldNotes(problems, warnings, SetupField.PRE_X) }) { onChange(s.copy(preX = it)) }
            SettingField("Pre-position Y", s.preY, d, keepSign = true, notes = { FieldNotes(problems, warnings, SetupField.PRE_Y) }) { onChange(s.copy(preY = it)) }
            SettingField("Pre-position Z", s.referenceDz, d) { onChange(s.copy(referenceDz = it)) }
            SettingField("Probing overshoot allowance", s.overshoot, d,
                sub = "Initial Z probe overtravel allowance before halting.", notes = { FieldNotes(problems, warnings, SetupField.OVERSHOOT) }) { onChange(s.copy(overshoot = it)) }
            SettingField("Thin bracket thickness", s.thin, d, notes = { FieldNotes(problems, warnings, SetupField.THIN) }) { onChange(s.copy(thin = it)) }
            SettingField("Thick bracket thickness", s.thick, d, notes = { FieldNotes(problems, warnings, SetupField.THICK) }) { onChange(s.copy(thick = it)) }
            SettingField("MDF Wasteboard Thickness", s.wasteboard, d, notes = { FieldNotes(problems, warnings, SetupField.WASTEBOARD) }) { onChange(s.copy(wasteboard = it)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(LabelGap)) {
            Text("Default bracket", fontSize = 12.sp, color = Muted)
            Segmented(listOf("Thin", "Thick"), s.defaultBracket.ordinal) { onChange(s.copy(defaultBracket = Bracket.entries[it])) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(LabelGap)) {
            Text("Default wasteboard", fontSize = 12.sp, color = Muted)
            Segmented(listOf("Present", "Removed"), if (s.defaultWasteboard) 0 else 1) { onChange(s.copy(defaultWasteboard = it == 0)) }
        }
        if (fromArranging) Text("Changes are saved as you go.", fontSize = 12.sp, color = Muted)
        Spacer(Modifier.height(4.dp))
        PendantOutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth().height(44.dp)) {
            Text("Reset to defaults", fontSize = 15.sp)
        }
    }
    if (confirmReset) PendantDialog("Reset to defaults?", dismissible = true, onDismiss = { confirmReset = false },
        buttons = {
            PendantOutlinedButton(onClick = { confirmReset = false }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Cancel", fontSize = 16.sp) }
            Button(onClick = { confirmReset = false; onReset(); resetKey++ }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Reset", fontSize = 16.sp) }
        }) {
        Text("This restores ${resetSummary(ProbeSetup().tip)} to their original values. Your current settings will be lost.",
            fontSize = 14.sp, lineHeight = 19.sp, color = Muted)
    }
}

/** A setting box like the probe wizard's; a tap selects the whole value, or all but a leading minus when [keepSign]. */
@Composable
private fun SettingField(label: String, value: Double, decimals: Int, keepSign: Boolean = false, sub: String? = null,
                         notes: @Composable () -> Unit = {}, onValue: (Double) -> Unit) {
    fun show(v: Double) = Fmt.fixed(v, decimals)
    var field by remember { mutableStateOf(TextFieldValue(show(value))) }
    var focused by remember { mutableStateOf(false) }
    // Applied after the tap's own caret placement has settled.
    LaunchedEffect(focused) {
        if (focused) {
            delay(60)
            val t = field.text
            field = field.copy(selection = TextRange(if (keepSign && t.startsWith("-")) 1 else 0, t.length))
        }
    }
    val bad = field.text.toDoubleOrNull() == null
    val primary = MaterialTheme.colorScheme.primary
    // Sized in sp so it grows with the system font scale: room for 7 monospace characters (-100.00), the suffix and padding.
    val fieldWidth = with(LocalDensity.current) { 92.sp.toDp() } + 24.dp
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp)
            if (sub != null) Text(sub, fontSize = 12.sp, color = Muted)
        }
        Spacer(Modifier.width(8.dp))
        BasicTextField(field, { v ->
            val t = v.text.replace(',', '.')
            field = v.copy(text = t)
            t.toDoubleOrNull()?.let(onValue)
        }, singleLine = true,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End),
            cursorBrush = SolidColor(primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(fieldWidth).onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) field.text.toDoubleOrNull()?.let { v -> field = TextFieldValue(show(v)) }
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
    notes()
    }
}

/** A blocking problem: the filled error treatment. */
@Composable
private fun ProblemNote(message: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ErrBg).padding(10.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Error, null, tint = ErrFg); Spacer(Modifier.width(8.dp))
        Text(message, fontSize = 13.5.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
    }
}

/** An advisory: amber text with no fill, so it never reads as a blocker. */
@Composable
private fun WarningNote(message: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Warning, null, tint = WarnFg, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
        Text(message, fontSize = 12.5.sp, lineHeight = 17.sp, color = WarnFg, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun FieldNotes(problems: List<SetupIssue>, warnings: List<SetupIssue>, field: SetupField) {
    problems.filter { it.field == field }.forEach { ProblemNote(it.message) }
    warnings.filter { it.field == field }.forEach { WarningNote(it.message) }
}

// ---- per-run questions ------------------------------------------------------------------------
@Composable
internal fun AskPage(setup: AnchorSetup, tip: Double, s: AnchorState.Asking, onChoose: (Bracket, Boolean) -> Unit, onSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        Column(verticalArrangement = Arrangement.spacedBy(LabelGap)) {
            Text("Anchor Bracket", fontSize = 12.sp, color = Muted)
            Segmented(listOf("Thin (${Fmt.fixed(setup.thin, 2)}mm)", "Thick (${Fmt.fixed(setup.thick, 2)}mm)"), s.bracket.ordinal) {
                onChoose(Bracket.entries[it], s.wasteboard)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(LabelGap)) {
            Text("MDF Wasteboard", fontSize = 12.sp, color = Muted)
            Segmented(listOf("Present", "Removed"), if (s.wasteboard) 0 else 1) { onChoose(s.bracket, it == 0) }
        }
        // No fields here, so every message is listed.
        setup.setupProblems(tip).forEach { ProblemNote(it.message) }
        setup.setupWarnings(tip).forEach { WarningNote(it.message) }
        SmallButton("Settings", { Icon(Icons.Filled.Settings, null) }, outlined = true, onClick = onSettings)
    }
}

// ---- running ----------------------------------------------------------------------------------
@Composable
internal fun RunningPage(vm: AppViewModel, s: AnchorState.Running) {
    val st by vm.link.status.collectAsState()
    val setup by vm.anchor.setup.collectAsState()
    val conn by vm.link.conn.collectAsState()
    val action by vm.probe.toolAction.collectAsState()
    val connected = conn == ConnState.Connected
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GroupGap)) {
    // One list: the tool change's own stages nest under "Fit and calibrate", and the run owns the motion.
    StepChecklist(AnchorStep.entries.map { it.label() }, s.step.ordinal, detailKey = action?.stage,
        detail = if (s.step == AnchorStep.PROBE) ({
            action?.takeIf { it.stage != ToolAction.Stage.DONE && it.error == null }?.let { a ->
                Progress(a.stage, a.load, ProbeTool.PROBE_3D.noun)
                if (awaitingSwap(a, st?.state)) Box(Modifier.padding(start = 32.dp, top = 8.dp)) {
                    SmallButton("I've swapped it \u00b7 continue", null, outlined = true) { vm.probe.continueToolChange() }
                }
            }
            Unit
        }) else null)
    MachinePosition(st?.mpos, connected)
    RunAnswers(setup, s.bracket, s.wasteboard)
    }
}

/** Live machine position, in its own card so the checklist stays a checklist. */
@Composable
private fun MachinePosition(mpos: List<Double>?, connected: Boolean) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TileBg).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf('X', 'Y', 'Z').forEachIndexed { i, ax ->
            Row {
                Text("$ax ", fontSize = 13.sp, color = Muted)
                Text(fmtPos(mpos?.getOrNull(i), connected).trim(), fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace, color = Color(0xFFCBD5E1))
            }
        }
    }
}

/** The run's own answers, repeated while it works: homing is dead time, and a wrong one shows here. */
@Composable
private fun RunAnswers(setup: AnchorSetup, bracket: Bracket, wasteboard: Boolean) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TileBg).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The same words the questions used, so a wrong answer reads as wrong.
        AnswerRow("Anchor Bracket", if (bracket == Bracket.THIN)
            "Thin (${Fmt.fixed(setup.thin, 2)}mm)" else "Thick (${Fmt.fixed(setup.thick, 2)}mm)")
        AnswerRow("MDF Wasteboard", if (wasteboard) "Present (${Fmt.fixed(setup.wasteboard, 2)}mm)" else "Removed")
    }
}

@Composable
private fun AnswerRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = Muted)
        Text(value, fontSize = 14.sp)
    }
}

// ---- failure ----------------------------------------------------------------------------------
@Composable
internal fun FailedPage(vm: AppViewModel, f: AnchorState.Failed, connected: Boolean, ready: Boolean,
                       onClose: () -> Unit, onAgain: () -> Unit, onConfirmRetry: () -> Unit) {
    val st by vm.link.status.collectAsState()
    val check by vm.probe.lengthCheck.collectAsState()
    val rejected = probeRejected(check, f)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ErrBg).padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Error, null, tint = ErrFg, modifier = Modifier.size(26.dp)); Spacer(Modifier.width(8.dp))
            Text(f.reason, fontSize = 15.sp, lineHeight = 20.sp)
        }
        if (rejected) CheckRowIcon(Icons.Filled.Info, "Use the 3D probe", WRONG_PROBE_NOTE)
        CheckRowIcon(Icons.Filled.Info, "What changed", changedText(f.changed, f.halted))
        // The only place the probe-length override is reachable: this routine writes a machine-wide value.
        val retryOffered = canConfirmRetry(check, f)
        val alarm = st?.isAlarm == true
        if (retryOffered) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NotReadyNote(connected, st?.state, st?.isIdle == true)
            Button(onClick = onConfirmRetry, enabled = ready, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Confirm 3D probe and retry", fontSize = 16.sp, maxLines = 1)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PendantOutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).height(48.dp)) { Text("Close", fontSize = 16.sp) }
            // Unlock only: a retry homes and moves the machine, so it stays a separate, deliberate press.
            if (alarm) Button(onClick = { vm.link.dialect.unlock().forEach { vm.link.sendLine(it) } },
                modifier = Modifier.weight(1f).height(48.dp)) {
                Icon(Icons.Filled.LockOpen, null); Spacer(Modifier.width(4.dp)); Text("Unlock", fontSize = 16.sp, maxLines = 1, softWrap = false)
            } else Button(onClick = onAgain, modifier = Modifier.weight(1f).height(48.dp)) { Text("Try again", fontSize = 16.sp, maxLines = 1) }
        }
    }
}

// ---- dialogs ----------------------------------------------------------------------------------
/** A plain Dialog with our own panel, like `MoveDialog`: AlertDialog's spacing and look are foreign here. */
@Composable
private fun PendantDialog(title: String, dismissible: Boolean, onDismiss: () -> Unit = {}, wide: Boolean = false,
                          buttons: @Composable RowScope.() -> Unit, body: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible,
            usePlatformDefaultWidth = !wide)) {
        Surface(if (wide) Modifier.fillMaxWidth().padding(horizontal = 12.dp) else Modifier,
            shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(if (wide) Modifier.padding(horizontal = 24.dp, vertical = 24.dp) else Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 12.dp)) {
                Text(title, fontSize = 22.sp)
                body()
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = buttons)
            }
        }
    }
}

@Composable
internal fun ZCorrectionDialog(z: ZCorrection, onNotNow: () -> Unit, onUpdate: () -> Unit) {
    PendantDialog("Pre-position Z looks off", dismissible = false,
        buttons = {
            PendantOutlinedButton(onClick = onNotNow, modifier = Modifier.weight(1f).height(48.dp)) { Text("Not now", fontSize = 16.sp) }
            Button(onClick = onUpdate, modifier = Modifier.weight(1f).height(48.dp)) { Text("Update", fontSize = 16.sp) }
        }) {
        Text(z.message(), fontSize = 14.sp, lineHeight = 19.sp, color = Muted)
        Text(z.question(), fontSize = 14.sp, lineHeight = 19.sp)
    }
}

@Composable
internal fun ResultDialog(r: AnchorResult, saving: Boolean, onSave: () -> Unit, onCancel: () -> Unit) {
    PendantDialog("Anchor 1 Measured", dismissible = false, wide = true,
        buttons = {
            PendantOutlinedButton(onClick = onCancel, enabled = !saving, modifier = Modifier.weight(1f).height(52.dp)) { Text("Cancel", fontSize = 16.sp) }
            Button(onClick = onSave, enabled = !saving, modifier = Modifier.weight(1.6f).height(52.dp)) {
                Text(if (saving) "Saving…" else "Save to machine", fontSize = 15.sp, maxLines = 1)
            }
        }) {
        val mono = FontFamily.Monospace
        val primary = MaterialTheme.colorScheme.primary
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            r.rows().zip(listOf("X", "Y")).forEach { (row, axis) ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Wide enough for the glyph at 32sp; weight-free so both cards align
                    Text(axis, Modifier.width(24.dp), fontSize = 32.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1, softWrap = false)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ResultLine("Current", row.current, 14.sp, Muted, mono)
                        ResultLine("Measured", row.measured, 14.sp, MaterialTheme.colorScheme.onSurface, mono)
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF5B6474)))
                        ResultLine("Difference", row.change, 16.sp, primary, mono)
                    }
                }
            }
        }
    }
}

/** Label left, value right; the value never wraps and the label yields space to it. */
@Composable
private fun ResultLine(label: String, value: String, size: androidx.compose.ui.unit.TextUnit, colour: Color, mono: FontFamily) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = size, color = colour)
        Text(value, fontSize = size, fontFamily = mono, color = colour, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
    }
}

@Composable
internal fun RestartDialog(onRestart: () -> Unit, onClose: () -> Unit) {
    PendantDialog("Restart the machine?", dismissible = false,
        buttons = {
            PendantOutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).height(48.dp)) { Text("Close", fontSize = 16.sp) }
            Button(onClick = onRestart, modifier = Modifier.weight(1f).height(48.dp)) { Text("Restart now", fontSize = 16.sp) }
        }) {
        Text("The new anchor coordinates have been saved, but the machine only reads it at start-up. " +
            "Restart now and the machine will reboot and re-home. " +
            "Or close this dialog and manually power cycle the machine.", fontSize = 14.sp, lineHeight = 19.sp, color = Muted)
    }
}
