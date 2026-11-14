package com.z1pendant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.z1pendant.AppViewModel
import com.z1pendant.hotkeys.BuiltIn
import com.z1pendant.hotkeys.Hotkey
import com.z1pendant.hotkeys.Kind
import com.z1pendant.hotkeys.Step
import com.z1pendant.platform.PlatformBackHandler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotkeyEditor(vm: AppViewModel, initial: Hotkey?, onClose: () -> Unit) {
    val all by vm.hotkeys.collectAsState()
    var h by remember {
        mutableStateOf(initial ?: Hotkey(label = "New hotkey", icon = "mi:Star", kind = Kind.GCODE, steps = listOf(Step.GCode(""))))
    }
    val isNew = initial == null || all.none { it.id == h.id }
    var iconPicker by remember { mutableStateOf(false) }
    var binding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    PlatformBackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
                    Text(if (isNew) "New hotkey" else "Edit hotkey", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Button(onClick = { vm.upsertHotkey(h); onClose() }) { Text("Save") }
                }

                // label + icon
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable { iconPicker = true }, contentAlignment = Alignment.Center) {
                        HotkeyIcon(h.icon, 30.dp, MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    OutlinedTextField(h.label, { h = h.copy(label = it) }, label = { Text("Label") }, singleLine = true, modifier = Modifier.weight(1f))
                }

                // action type
                Text("Action", fontWeight = FontWeight.SemiBold)
                Segmented(listOf("Built-in", "G-code", "Sequence"), h.kind.ordinal) { i ->
                    val k = Kind.entries[i]
                    h = when (k) {
                        Kind.BUILTIN -> h.copy(kind = k, steps = listOf(h.steps.firstOrNull { it is Step.Builtin } ?: Step.Builtin(BuiltIn.HOME)))
                        Kind.GCODE -> h.copy(kind = k, steps = listOf(h.steps.firstOrNull { it is Step.GCode } ?: Step.GCode("")))
                        Kind.SEQUENCE -> h.copy(kind = k, steps = h.steps.filterNot { it is Step.GCode && it.text.isBlank() })
                    }
                }
                when (h.kind) {
                    Kind.BUILTIN -> BuiltinPicker((h.steps.firstOrNull() as? Step.Builtin)?.action ?: BuiltIn.HOME) {
                        h = h.copy(steps = listOf(Step.Builtin(it)))
                    }
                    Kind.GCODE -> OutlinedTextField(
                        (h.steps.firstOrNull() as? Step.GCode)?.text ?: "", { h = h.copy(steps = listOf(Step.GCode(it))) },
                        label = { Text("G-code (one command per line; ; comments ok)") },
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        minLines = 4, modifier = Modifier.fillMaxWidth())
                    Kind.SEQUENCE -> SequenceEditor(vm, h) { h = h.copy(steps = it) }
                }

                // guards
                Text("Guards", fontWeight = FontWeight.SemiBold)
                SwitchRow("Ask before running", h.confirm) { h = h.copy(confirm = it) }
                SwitchRow("Only when machine is Idle", h.onlyIdle) { h = h.copy(onlyIdle = it) }
                SwitchRow("Show in hotkey grid (off = button-only)", h.showOnMain) { h = h.copy(showOnMain = it) }

                // triggers
                Text("Buttons", fontWeight = FontWeight.SemiBold)
                Text("Volume keys, Bluetooth remotes / gamepads, USB keyboards or a Pico HID pendant. Hold the button while binding for a long-press binding.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                h.triggers.forEachIndexed { i, t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⌨ ${t.label}", modifier = Modifier.weight(1f))
                        IconButton(onClick = { h = h.copy(triggers = h.triggers.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Filled.Close, "Remove") }
                    }
                }
                if (binding) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer).padding(12.dp)) {
                    Text("Press the button to bind now…", fontWeight = FontWeight.SemiBold)
                    Text("Hold it for half a second or more to bind a long-press instead.", fontSize = 12.sp)
                    TextButton(onClick = { vm.capture.value = null; binding = false }) { Text("Cancel") }
                } else PendantOutlinedButton(onClick = {
                    binding = true
                    vm.capture.value = { t -> h = h.copy(triggers = h.triggers + t); binding = false }
                }) { Text("Bind a button…") }

                // actions
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PendantOutlinedButton(onClick = { vm.requestRun(h, test = true) }) { Text("Test run") }
                    if (!isNew) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { confirmDelete = true }, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFDC2626))) { Text("Delete") }
                    }
                }
                Text("Test run skips “ask before running” but still checks “only when Idle”. To move it, expand Hotkeys → Edit and drag.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(40.dp))
            }
        }
    }

    if (iconPicker) IconPicker(h.icon, { h = h.copy(icon = it); iconPicker = false }) { iconPicker = false }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete “${h.label}”?") },
        confirmButton = { TextButton(onClick = { vm.deleteHotkey(h.id); confirmDelete = false; onClose() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
    )
}

@Composable
fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(value, onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuiltinPicker(value: BuiltIn, onPick: (BuiltIn) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }) {
        OutlinedTextField(value.label, {}, readOnly = true, label = { Text("Built-in action") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth())
        ExposedDropdownMenu(open, { open = false }) {
            BuiltIn.entries.forEach { b -> DropdownMenuItem(text = { Text(b.label) }, onClick = { onPick(b); open = false }) }
        }
    }
}

@Composable
fun SequenceEditor(vm: AppViewModel, h: Hotkey, onChange: (List<Step>) -> Unit) {
    val all by vm.hotkeys.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    val steps = h.steps
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (steps.isEmpty()) Text("No steps yet.", fontSize = 13.sp)
        steps.forEachIndexed { i, s ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { editing = i }.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}. " + s.describe { id -> all.firstOrNull { it.id == id }?.label }, modifier = Modifier.weight(1f), fontSize = 14.sp)
                IconButton(onClick = { if (i > 0) onChange(steps.toMutableList().apply { add(i - 1, removeAt(i)) }) }) { Icon(Icons.Filled.ArrowUpward, "Up") }
                IconButton(onClick = { onChange(steps.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Filled.Close, "Remove") }
            }
        }
        PendantOutlinedButton(onClick = { adding = true }) { Text("Add step…") }
    }
    if (adding) AlertDialog(
        onDismissRequest = { adding = false }, title = { Text("Add step") },
        text = {
            Column {
                listOf<Pair<String, Step>>(
                    "Built-in action" to Step.Builtin(BuiltIn.HOME),
                    "G-code" to Step.GCode(""),
                    "Wait until idle" to Step.WaitIdle,
                    "Delay" to Step.Delay(500),
                    "Run another hotkey" to Step.RunHotkey(all.firstOrNull { it.id != h.id }?.id ?: ""),
                ).forEach { (name, s) ->
                    TextButton(onClick = {
                        onChange(steps + s); adding = false
                        if (s !is Step.WaitIdle) editing = steps.size
                    }) { Text(name) }
                }
            }
        }, confirmButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
    )
    editing?.let { i ->
        val s = steps.getOrNull(i) ?: return@let
        var draft by remember(i) { mutableStateOf(s) }
        AlertDialog(
            onDismissRequest = { editing = null }, title = { Text("Step ${i + 1}") },
            text = {
                when (val d = draft) {
                    is Step.Builtin -> BuiltinPicker(d.action) { draft = Step.Builtin(it) }
                    is Step.GCode -> OutlinedTextField(d.text, { draft = Step.GCode(it) }, minLines = 4,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace), label = { Text("G-code") })
                    is Step.Delay -> OutlinedTextField(d.ms.toString(), { t -> draft = Step.Delay(t.filter(Char::isDigit).toLongOrNull() ?: 0) },
                        label = { Text("Milliseconds") }, singleLine = true)
                    is Step.RunHotkey -> Column {
                        all.filter { it.id != h.id }.forEach { o ->
                            Row(Modifier.fillMaxWidth().clickable { draft = Step.RunHotkey(o.id) }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(d.id == o.id, { draft = Step.RunHotkey(o.id) }); Text(o.label)
                            }
                        }
                    }
                    Step.WaitIdle -> Text("Waits until all sent lines are answered and the machine reports Idle.")
                }
            },
            confirmButton = { TextButton(onClick = { onChange(steps.toMutableList().apply { set(i, draft) }); editing = null }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun IconPicker(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(if (current.startsWith("tx:")) current.removePrefix("tx:") else "") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Icon") },
        text = {
            Column {
                LazyVerticalGrid(GridCells.Adaptive(48.dp), Modifier.heightIn(max = 320.dp)) {
                    items(HotkeyIcons.keys.toList()) { k ->
                        Box(Modifier.size(48.dp).padding(3.dp).clip(RoundedCornerShape(8.dp))
                            .then(if (current == "mi:$k") Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
                            .clickable { onPick("mi:$k") }, contentAlignment = Alignment.Center) { HotkeyIcon("mi:$k", 26.dp) }
                    }
                }
                OutlinedTextField(text, { text = it.take(3) }, label = { Text("…or emoji / text (up to 3)") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onPick("tx:$text") else onDismiss() }) { Text("Use text") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
