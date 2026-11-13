package com.z1pendant.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
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

        // ---- step selector (the axis is picked by tapping its row in the readout above) ----
        Segmented(vm.steps.map { stepLabel(it) }, stepIdx) { vm.selectStep(it); vm.haptics.press() }

        // ---- handwheel (hidden while the hotkey grid is expanded) ----
        // Collapsed, the hotkeys toggle is a bare down-chevron in the empty corner left of the
        // dial, so the tiles start right under the dial. Expanded, an up-chevron brings the dial back.
        if (!expanded) Box(Modifier.fillMaxWidth()) {
            Handwheel(
                degPerClick = degPerClick, axis = axis,
                stepText = "${stepLabel(vm.steps[stepIdx])} ${if (axis == 'A') "°" else "mm"}/click",
                enabled = connected && running == null,
                onClick = { vm.wheelClicks(it) },
                modifier = Modifier.fillMaxWidth(0.75f).align(Alignment.Center),
            )
            IconButton(onClick = { vm.hotkeysExpanded.value = true; vm.haptics.press() },
                modifier = Modifier.align(Alignment.BottomStart)) {
                Icon(Icons.Filled.ExpandMore, "Show all hotkeys", Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }

        // ---- hotkeys ----
        // Collapsed: dial + the first 8 hotkeys. Expanded: dial hidden, all hotkeys, and Edit to
        // rearrange (drag) / add; Edit turns into Save. Hold a tile 2 s to edit it, in either view.
        if (expanded) Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.hotkeysExpanded.value = false; vm.haptics.press() }, enabled = !editMode) {
                Icon(Icons.Filled.ExpandLess, "Back to the dial", Modifier.size(32.dp),
                    tint = if (editMode) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.primary)
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
                (order.mapNotNull { byId[it] } + hotkeys.filter { it.id !in order })   // new ones go last
                    .let { l -> l.filter { it.showOnMain } + l.filterNot { it.showOnMain } }   // shown first
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
        if (shown.isEmpty()) Text("No hotkeys. Tap the arrow, then +.", fontSize = 13.sp)

        Spacer(Modifier.height(4.dp))
        }

        // ---- bottom drawer: feed override + hold/resume/reset ----
        // Closed it's a slim handle. Swipe up (or tap) to open, swipe down to close. It opens by
        // itself when the machine needs those buttons: Hold, Alarm, a hotkey running, or motion
        // that isn't a dial jog (e.g. a job started from Studio). If it opened itself, it closes
        // again once the machine is back to Idle.
        val open by vm.controlsOpen.collectAsState()
        val s = st?.state
        val machineMoving = s.equals("Run", true) &&
            android.os.SystemClock.elapsedRealtime() - vm.jog.lastJogAt > 1500
        val needsControls = connected && (running != null || machineMoving ||
            s.equals("Hold", true) || s.equals("Pause", true) || s.equals("Alarm", true))
        var autoOpened by remember { mutableStateOf(false) }
        LaunchedEffect(needsControls) {
            if (needsControls && !vm.controlsOpen.value) { vm.controlsOpen.value = true; autoOpened = true }
            if (!needsControls && autoOpened) { vm.controlsOpen.value = false; autoOpened = false }
        }
        ControlsDrawer(open, feed = st?.feedOverride, connected = connected,
            onOpen = { o -> if (o != open) { vm.controlsOpen.value = o; autoOpened = false; vm.haptics.press() } },
            onFeed = { vm.setFeedOverride(it) }, onHold = vm::hold, onResume = vm::resume, onReset = vm::reset)
    }
}

@Composable
private fun ControlsDrawer(
    open: Boolean, feed: Double?, connected: Boolean, onOpen: (Boolean) -> Unit,
    onFeed: (Int) -> Unit, onHold: () -> Unit, onResume: () -> Unit, onReset: () -> Unit,
) {
    val swipeDp = 24.dp
    val swipePx = with(androidx.compose.ui.platform.LocalDensity.current) { swipeDp.toPx() }
    // Vertical swipe anywhere on the drawer; horizontal drags still reach the feed slider.
    val swipe = Modifier.pointerInput(open) {
        var dy = 0f
        detectVerticalDragGestures(
            onDragStart = { dy = 0f },
            onVerticalDrag = { c, d -> dy += d; c.consume() },
            onDragEnd = { if (dy < -swipePx) onOpen(true) else if (dy > swipePx) onOpen(false) },
        )
    }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 4.dp)
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 10.dp, bottomEnd = 10.dp))
            .background(if (open) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .then(swipe),
    ) {
        // Handle: grab bar, and the current feed so you can see it without opening.
        Box(Modifier.fillMaxWidth().height(30.dp).clickable { onOpen(!open) }, contentAlignment = Alignment.Center) {
            Box(Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)))
            if (!open) {
                Text("Feed ${feed?.toInt() ?: 100}%", fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp))
                Text("Hold · Resume · Reset", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp))
            }
        }
        androidx.compose.animation.AnimatedVisibility(open,
            enter = androidx.compose.animation.expandVertically(expandFrom = Alignment.Top),
            exit = androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Top)) {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FeedOverride(feed, connected, onFeed)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onHold, enabled = connected, modifier = Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706), contentColor = Color.White)) { Text("Hold", fontSize = 16.sp) }
                    Button(onClick = onResume, enabled = connected, modifier = Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A), contentColor = Color.White)) { Text("Resume", fontSize = 16.sp) }
                    Button(onClick = onReset, enabled = connected, modifier = Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626), contentColor = Color.White)) { Text("Reset", fontSize = 16.sp) }
                }
            }
        }
    }
}

/** On/off for toggle tiles (light, vacuum); null = not a toggle, or state unknown. */
fun toggleState(h: Hotkey, outputs: Map<String, Boolean>, connected: Boolean): Boolean? {
    val isLight = h.steps.size == 1 &&
        (h.steps[0] as? com.z1pendant.hotkeys.Step.Builtin)?.action == com.z1pendant.hotkeys.BuiltIn.LIGHT_TOGGLE
    val isVacuum = h.steps.size == 1 &&
        (h.steps[0] as? com.z1pendant.hotkeys.Step.Builtin)?.action == com.z1pendant.hotkeys.BuiltIn.VACUUM_TOGGLE
    return if (!connected) null else if (isLight) outputs["light"] else if (isVacuum) outputs["vacuum"] else null
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
