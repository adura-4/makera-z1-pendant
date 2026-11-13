package com.z1pendant.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.z1pendant.AppViewModel
import com.z1pendant.hotkeys.Hotkey
import com.z1pendant.link.ConnState
import java.util.Locale

fun stateColor(state: String?, conn: ConnState): Color = when {
    conn != ConnState.Connected -> Color(0xFF6B7280)
    state == null -> Color(0xFF6B7280)
    state.equals("Idle", true) -> Color(0xFF16A34A)
    state.equals("Run", true) -> Color(0xFF2563EB)
    state.equals("Hold", true) || state.equals("Pause", true) -> Color(0xFFD97706)
    state.equals("Alarm", true) -> Color(0xFFDC2626)
    state.equals("Home", true) -> Color(0xFF7C3AED)
    else -> Color(0xFF0891B2)
}

fun stepLabel(s: Double) = when {
    s >= 1 -> "%.0f".format(Locale.US, s)
    else -> "%s".format(Locale.US, s.toBigDecimal().stripTrailingZeros().toPlainString())
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(vm: AppViewModel, onSettings: () -> Unit, onConsole: () -> Unit, onEdit: (Hotkey?) -> Unit,
               scroll: androidx.compose.foundation.ScrollState = rememberScrollState()) {
    val conn by vm.link.conn.collectAsState()
    val st by vm.link.status.collectAsState()
    val lat by vm.link.latencyMs.collectAsState()
    val target by vm.target.collectAsState()
    val axis by vm.axis.collectAsState()
    val stepIdx by vm.stepIndex.collectAsState()
    val hotkeys by vm.hotkeys.collectAsState()
    val running by vm.runner.running.collectAsState()
    val degPerClick by vm.degPerClick.collectAsState()
    val outputs by vm.link.outputs.collectAsState()
    val editMode by vm.editMode.collectAsState()
    val expanded by vm.hotkeysExpanded.collectAsState()
    val arrangeOrder by vm.arrangeOrder.collectAsState()
    val connected = conn == ConnState.Connected

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- header ----
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onSettings)) {
                Text(target?.name ?: "No machine", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (conn) {
                        ConnState.Connected -> "${target?.host} · ${lat?.let { "$it ms" } ?: "—"}"
                        ConnState.Connecting -> "connecting to ${target?.host}…"
                        ConnState.Lost -> "link lost · reconnecting…"
                        ConnState.Disconnected -> "tap to connect"
                    }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val pill = if (connected) st?.state ?: "…" else if (conn == ConnState.Lost) "Lost" else "Offline"
            Text(pill, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(stateColor(st?.state, conn)).padding(horizontal = 14.dp, vertical = 6.dp))
            IconButton(onClick = onConsole) { Icon(Icons.Filled.Terminal, "Console") }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
        }

        running?.let { r ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Running: ${r.label}", fontWeight = FontWeight.SemiBold)
                    Text(r.stepText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { vm.runner.cancel() }) { Text("Cancel") }
            }
        }

        // Header + running banner pinned on top, feed + Hold/Resume/Reset pinned at the bottom;
        // everything between scrolls.
        Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ---- DRO ----
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 4.dp)) {
            vm.axes.forEachIndexed { i, a ->
                val sel = a == axis
                Row(
                    Modifier.fillMaxWidth().clickable { vm.selectAxis(a); vm.haptics.press() }
                        .background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                        .padding(horizontal = 10.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(a.toString(), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp),
                        color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(fmtPos(st?.wpos?.getOrNull(i), connected), fontSize = 26.sp, lineHeight = 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        Text("M " + fmtPos(st?.mpos?.getOrNull(i), connected), fontSize = 12.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { vm.haptics.press(); vm.zero(a.toString()) }, enabled = connected && st?.isIdle == true,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp), modifier = Modifier.height(34.dp)) { Text("zero", fontSize = 13.sp) }
                }
            }
        }

        // ---- axis + step selectors ----
        Segmented(vm.axes.map { it.toString() }, vm.axes.indexOf(axis)) { vm.selectAxis(vm.axes[it]); vm.haptics.press() }
        Segmented(vm.steps.map { stepLabel(it) }, stepIdx) { vm.selectStep(it); vm.haptics.press() }

        // ---- handwheel (hidden while the hotkey grid is expanded) ----
        if (!expanded) Handwheel(
            degPerClick = degPerClick, axis = axis,
            stepText = "${stepLabel(vm.steps[stepIdx])} ${if (axis == 'A') "°" else "mm"}/click",
            enabled = connected && running == null,
            onClick = { vm.wheelClicks(it) },
            modifier = Modifier.fillMaxWidth(0.62f).align(Alignment.CenterHorizontally),
        )

        // ---- hotkeys ----
        // Collapsed: dial + the first 8 hotkeys. Expanded: dial hidden, all hotkeys, and Edit to
        // rearrange (drag) / add; Edit turns into Save. Hold a tile 2 s to edit it, in either view.
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.hotkeysExpanded.value = !expanded; vm.haptics.press() },
                enabled = !editMode, contentPadding = PaddingValues(start = 0.dp, end = 8.dp)) {
                Text("Hotkeys", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (expanded) "Collapse" else "Expand")
            }
            Spacer(Modifier.weight(1f))
            if (expanded) {
                // New hotkey: a plain + next to Edit/Save, there whether or not you're arranging.
                IconButton(onClick = { onEdit(null) }) {
                    Icon(Icons.Filled.Add, "New hotkey", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                }
                if (editMode) Button(onClick = { vm.saveArrangement(); vm.haptics.press() }) { Text("Save") }
                else TextButton(onClick = { vm.startArranging(); vm.haptics.press() }) { Text("Edit") }
            }
        }
        val visible = hotkeys.filter { it.showOnMain }
        val shown = when {
            editMode -> {
                val byId = hotkeys.associateBy { it.id }
                val order = arrangeOrder ?: hotkeys.map { it.id }
                order.mapNotNull { byId[it] } + hotkeys.filter { it.id !in order }   // new ones go last
            }
            expanded -> visible
            else -> visible.take(8)
        }
        if (editMode) Text("Drag tiles to rearrange · tap one to edit · Save when done", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HotkeyGrid(shown, arranging = editMode, toggleOf = { toggleState(it, outputs, connected) },
            onTap = { h -> if (editMode) onEdit(h) else vm.requestRun(h) },
            onLong = { h -> vm.haptics.majorTick(); onEdit(h) },
            onMove = { id, to -> vm.moveInArrangement(id, to) },
            onLift = { vm.haptics.majorTick() })
        if (!expanded && visible.size > 8) Text("+${visible.size - 8} more: tap Hotkeys", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (shown.isEmpty()) Text("No hotkeys. Tap Hotkeys, then +.", fontSize = 13.sp)

        Spacer(Modifier.height(4.dp))
        }

        // ---- pinned bottom: feed override + hold/resume/reset (always in reach) ----
        FeedOverride(st?.feedOverride, connected) { vm.setFeedOverride(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            Button(onClick = vm::hold, enabled = connected, modifier = Modifier.weight(1f).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706), contentColor = Color.White)) { Text("Hold", fontSize = 16.sp) }
            Button(onClick = vm::resume, enabled = connected, modifier = Modifier.weight(1f).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A), contentColor = Color.White)) { Text("Resume", fontSize = 16.sp) }
            Button(onClick = vm::reset, enabled = connected, modifier = Modifier.weight(1f).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626), contentColor = Color.White)) { Text("Reset", fontSize = 16.sp) }
        }
    }
}

/** On/off for toggle tiles (only the light so far); null = not a toggle, or state unknown. */
fun toggleState(h: Hotkey, outputs: Map<String, Boolean>, connected: Boolean): Boolean? {
    val isLight = h.steps.size == 1 &&
        (h.steps[0] as? com.z1pendant.hotkeys.Step.Builtin)?.action == com.z1pendant.hotkeys.BuiltIn.LIGHT_TOGGLE
    return if (isLight && connected) outputs["light"] else null
}

fun fmtPos(v: Double?, connected: Boolean) = if (v == null || !connected) "---.---" else "%9.3f".format(Locale.US, v)

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp))
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))) {
        options.forEachIndexed { i, o ->
            val sel = i == selected
            Box(Modifier.weight(1f).fillMaxHeight()
                .background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent)
                .clickable { onSelect(i) }, contentAlignment = Alignment.Center) {
                Text(o, fontSize = 16.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    fontFamily = FontFamily.Monospace,
                    color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
fun FeedOverride(current: Double?, enabled: Boolean, onSet: (Int) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val shown = drag ?: (current?.toFloat() ?: 100f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Feed", modifier = Modifier.width(44.dp))
        Slider(value = shown.coerceIn(10f, 200f), onValueChange = { drag = (Math.round(it / 5f) * 5).toFloat() },
            onValueChangeFinished = { drag?.let { onSet(it.toInt()) }; drag = null },
            valueRange = 10f..200f, enabled = enabled, modifier = Modifier.weight(1f))
        Text("${shown.toInt()}%", fontFamily = FontFamily.Monospace, modifier = Modifier.width(54.dp))
        TextButton(onClick = { onSet(100) }, enabled = enabled, contentPadding = PaddingValues(4.dp)) { Text("100") }
    }
}
