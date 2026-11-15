package com.z1pendant.ui

import androidx.compose.ui.layout.onSizeChanged

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.draw.drawBehind
import kotlinx.coroutines.launch
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
import com.z1pendant.platform.nowMs
import com.z1pendant.util.Fmt
import kotlin.math.roundToInt

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
    s >= 1 -> Fmt.fixed(s, 0)
    else -> Fmt.plain(s)
}

/** True when the window is at least 600 dp wide (iPad, Android tablet): bigger readout, smaller dial, 5×3 hotkeys. */
val LocalWide = androidx.compose.runtime.staticCompositionLocalOf { false }
/** True on a landscape tablet (at least 840 dp wide and wider than tall): side-by-side layout. */
val LocalLandscape = androidx.compose.runtime.staticCompositionLocalOf { false }

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
    var moveFor by remember { mutableStateOf<Char?>(null) }
    val wide = LocalWide.current
    val padMode by vm.jogPad.collectAsState()
    val rowScope = rememberCoroutineScope()
    val zeroUndo by vm.zeroUndo.collectAsState()
    moveFor?.let { a -> MoveDialog(vm, a) { moveFor = null } }

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

        // Long-running hotkeys (homing, multi-step sequences with waits) get a banner with the
        // current step and Cancel. Quick ones finish before it would appear, so nothing flashes.
        var showRunning by remember { mutableStateOf(false) }
        LaunchedEffect(running?.label) {
            showRunning = false
            if (running != null) { kotlinx.coroutines.delay(1500); showRunning = true }
        }
        running?.takeIf { showRunning }?.let { r ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Running: ${r.label}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    Text(r.stepText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { vm.runner.cancel() }) { Text("Cancel") }
            }
        }

        // ---- the pieces (arranged below for portrait, or side by side on a landscape tablet) ----
        val landscape = LocalLandscape.current
        // Dial and jog pad have their own step lists; indices are clamped for the frame where the
        // mode has switched but the step index hasn't caught up yet.
        val stepList = vm.steps
        val si = stepIdx.coerceIn(0, stepList.lastIndex)

        // Readout: tap a row = select the axis; hold 1.5 s or "move" = Move box; zero / undo.
        val dro: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 4.dp)) {
            vm.axes.forEachIndexed { i, a ->
                val sel = a == axis && !padMode           // the pad's buttons name their own axis
                val hold = remember { androidx.compose.animation.core.Animatable(0f) }
                val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                Row(
                    // Tap = select the axis. Hold 1.5 s (the row fills while you hold) = Move box.
                    Modifier.fillMaxWidth()
                        .tapOrHold(a, HOLD_MS, hold, rowScope,
                            onTap = { vm.selectAxis(a); vm.haptics.press() },
                            onHold = { vm.selectAxis(a); vm.haptics.majorTick(); moveFor = a })
                        .background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                        .drawBehind {
                            // shown after a short delay so ordinary taps don't flash it
                            val p = ((hold.value - 0.1f) / 0.9f).coerceIn(0f, 1f)
                            if (p > 0f) drawRect(fill, size = androidx.compose.ui.geometry.Size(size.width * p, size.height))
                        }
                        .padding(horizontal = 10.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(a.toString(), fontSize = if (landscape) 36.sp else if (wide) 40.sp else 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(if (wide) 50.dp else 26.dp),
                        color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(fmtPos(st?.wpos?.getOrNull(i), connected), fontSize = if (landscape) 44.sp else if (wide) 52.sp else 26.sp, lineHeight = if (landscape) 46.sp else if (wide) 54.sp else 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        Text("M " + fmtPos(st?.mpos?.getOrNull(i), connected), fontSize = if (landscape) 16.sp else if (wide) 18.sp else 12.sp, lineHeight = if (landscape) 17.sp else if (wide) 20.sp else 13.sp, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    PendantOutlinedButton(onClick = { vm.selectAxis(a); vm.haptics.press(); moveFor = a }, enabled = connected,
                        contentPadding = PaddingValues(horizontal = if (wide) 16.dp else 10.dp, vertical = 0.dp), modifier = Modifier.height(if (wide) 46.dp else 34.dp)) { Text("move", fontSize = if (wide) 17.sp else 13.sp) }
                    Spacer(Modifier.width(6.dp))
                    // "zero" sets this axis to 0; pressed again while it still reads 0 (nothing moved)
                    // it puts the previous value back, like a DRO's zero/recall. Label shows which.
                    val undo = zeroUndo.containsKey(a) && vm.canRestoreZero(a, st)
                    PendantOutlinedButton(onClick = { vm.haptics.press(); vm.zero(a.toString()) }, enabled = connected && st?.isIdle == true,
                        contentPadding = PaddingValues(horizontal = if (wide) 16.dp else 10.dp, vertical = 0.dp), modifier = Modifier.height(if (wide) 46.dp else 34.dp).widthIn(min = if (wide) 88.dp else 64.dp)) {
                        Text(if (undo) "undo" else "zero", fontSize = if (wide) 17.sp else 13.sp)
                    }
                }
            }
        }
        }
        val stepSelector: @Composable () -> Unit = {
            Segmented(stepList.map { stepLabel(it) }, si) { vm.selectStep(it); vm.haptics.press() }
        }
        // Dial or jog pad in a box of the given size; top-left switches between them.
        val stage: @Composable androidx.compose.foundation.layout.BoxScope.(androidx.compose.ui.unit.Dp) -> Unit = { size ->
            if (!padMode) Handwheel(
                degPerClick = degPerClick, axis = axis,
                stepText = "${stepLabel(stepList[si])}${if (axis == 'A') "°" else "mm"}/click",
                enabled = connected && running == null,
                onClick = { vm.wheelClicks(it) },
                modifier = Modifier.size(size).align(Alignment.Center),
            ) else JogPad(vm, step = stepList[si], enabled = connected && running == null,
                modifier = Modifier.align(Alignment.Center))
            ModeButton(padMode, Modifier.align(Alignment.TopStart)) { vm.setJogPad(!padMode) }
        }
        // Hotkeys: Edit (drag to rearrange) turns into Save; + adds one. Hold a tile 2 s to edit it.
        val hotkeyActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
            IconButton(onClick = { onEdit(null) }) {
                Icon(Icons.Filled.Add, "New hotkey", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            }
            if (editMode) Button(onClick = { vm.saveArrangement(); vm.haptics.press() }) { Text("Save") }
            else TextButton(onClick = { vm.startArranging(); vm.haptics.press() }) { Text("Edit") }
        }
        val visible = hotkeys.filter { it.showOnMain }
        val arranged = if (!editMode) emptyList() else {
            val byId = hotkeys.associateBy { it.id }
            val order = arrangeOrder ?: hotkeys.map { it.id }
            (order.mapNotNull { byId[it] } + hotkeys.filter { it.id !in order })   // new ones go last
                .let { l -> l.filter { it.showOnMain } + l.filterNot { it.showOnMain } }   // shown first
        }
        val hotkeyGrid: @Composable (List<Hotkey>, Int) -> Unit = { shown, cols ->
            if (editMode) Text("Drag tiles to rearrange · tap one to edit · Save when done", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HotkeyGrid(shown, cols = cols, arranging = editMode, toggleOf = { toggleState(it, outputs, connected) },
                onTap = { h -> if (editMode) onEdit(h) else vm.requestRun(h) },
                onLong = { h -> vm.haptics.majorTick(); onEdit(h) },
                onMove = { id, to -> vm.moveInArrangement(id, to) },
                onLift = { vm.haptics.majorTick() })
            if (shown.isEmpty()) Text("No hotkeys yet. Tap + to add one.", fontSize = 13.sp)
        }

        if (landscape) {
            // ---- landscape tablet: readout, hotkeys and the stop controls on the left; steps
            // and a big dial / jog pad on the right. Everything always visible: no drawer, no
            // expand chevron.
            Row(Modifier.weight(1f).fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(0.48f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        dro()
                        Row(Modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Hotkeys", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f)); hotkeyActions()
                        }
                        hotkeyGrid(if (editMode) arranged else visible, 4)
                    }
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FeedOverride(st?.feedOverride, connected) { vm.setFeedOverride(it) }
                        StopButtons(st?.state, connected, onHold = vm::hold, onResume = vm::resume, onReset = vm::reset, height = 56.dp)
                    }
                }
                Column(Modifier.weight(0.52f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    stepSelector()
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) { stage(minOf(maxWidth, maxHeight)) }
                }
            }
        } else {
        // ---- portrait (phones, and tablets held upright) ----
        // Header + running banner pinned on top, the drawer pinned at the bottom; between scrolls.
        // The dial takes whatever height the rest leaves (on wide screens it grows when there are
        // only 1-2 hotkey rows, shrinks for 3). viewH = visible height, otherH = content minus dial.
        var viewH by remember { mutableIntStateOf(0) }
        var otherH by remember { mutableIntStateOf(-1) }
        val placedDial = remember { IntArray(1) }               // dial height used in the last layout, px
        if (expanded) placedDial[0] = 0
        Column(Modifier.weight(1f).onSizeChanged { viewH = it.height }.verticalScroll(scroll)) {
        Column(Modifier.onSizeChanged { otherH = it.height - placedDial[0] }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        dro()
        stepSelector()

        // Dial / jog pad, hidden while the hotkey grid is expanded. Bottom-left: up-chevron opens
        // the hotkey grid (it slides up); expanded, a down-chevron brings the dial back.
        if (!expanded) BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Same height in both modes, so nothing below jumps. Both fill the leftover height.
            // Phones: up to 75% of the width, down to 62% (the jog pad's full height), so the screen
            // doesn't scroll on shorter phones or with a larger display size (e.g. a Galaxy S21 at
            // 360 dp wide was 32 dp too tall). Wide: between 260 dp and 80% of the width.
            val density = androidx.compose.ui.platform.LocalDensity.current
            val left = if (viewH > 0 && otherH >= 0) with(density) { (viewH - otherH).toDp() } else null
            val box = if (!wide) (left ?: maxWidth).coerceIn(maxWidth * 0.62f, maxWidth * 0.75f)
                else (left ?: maxWidth * 0.5f).coerceIn(260.dp, maxWidth * 0.8f)
            placedDial[0] = with(density) { box.roundToPx() }
            Box(Modifier.fillMaxWidth().height(box)) {
                stage(box)
                IconButton(onClick = { vm.hotkeysExpanded.value = true; vm.haptics.press() },
                    modifier = Modifier.align(Alignment.BottomStart)) {
                    Icon(Icons.Filled.ExpandLess, "Show all hotkeys", Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // Collapsed: the first 8 hotkeys (15 on a wide screen). Expanded: dial hidden, all hotkeys,
        // + and Edit.
        if (expanded) Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.hotkeysExpanded.value = false; vm.haptics.press() }, enabled = !editMode) {
                Icon(Icons.Filled.ExpandMore, "Back to the dial", Modifier.size(32.dp),
                    tint = if (editMode) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.weight(1f))
            hotkeyActions()
        }
        val shown = when {
            editMode -> arranged
            expanded -> visible
            else -> visible.take(if (wide) 15 else 8)
        }
        hotkeyGrid(shown, if (wide) 5 else 4)

        Spacer(Modifier.height(4.dp))
        }
        }

        // ---- bottom drawer: feed override + hold/resume/reset ----
        // Closed it's a slim handle. Swipe up (or tap) to open, swipe down to close. It opens by
        // itself only when something other than the pendant is driving the machine or it stops:
        // Hold, Alarm, or motion the pendant didn't start (e.g. a job started from Studio). Dial
        // jogs and hotkeys (Home, Z up, ...) don't open it. If it opened itself, it closes again
        // once the machine is back to Idle.
        val open by vm.controlsOpen.collectAsState()
        val s = st?.state
        // A hotkey's motion keeps the machine busy after the hotkey "finishes" (the firmware acks
        // a line when it's queued, not when the move ends), so remember it until Idle again.
        var pendantMotion by remember { mutableStateOf(false) }
        LaunchedEffect(running != null, s) {
            if (running != null) pendantMotion = true
            else if (s.equals("Idle", true) || !connected) pendantMotion = false
        }
        val machineMoving = (s.equals("Run", true) || s.equals("Home", true)) && !pendantMotion && running == null &&
            nowMs() - vm.jog.lastJogAt > 1500
        val needsControls = connected && (machineMoving ||
            s.equals("Hold", true) || s.equals("Pause", true) || s.equals("Alarm", true))
        var autoOpened by remember { mutableStateOf(false) }
        LaunchedEffect(needsControls) {
            if (needsControls && !vm.controlsOpen.value) { vm.controlsOpen.value = true; autoOpened = true }
            if (!needsControls && autoOpened) { vm.controlsOpen.value = false; autoOpened = false }
        }
        ControlsDrawer(open, feed = st?.feedOverride, connected = connected,
            onOpen = { o -> if (o != open) { vm.controlsOpen.value = o; autoOpened = false; vm.haptics.press() } },
            onFeed = { vm.setFeedOverride(it) }, onHold = vm::hold, onResume = vm::resume, onReset = vm::reset, state = st?.state)
        }
    }
}

@Composable
private fun ControlsDrawer(
    open: Boolean, feed: Double?, connected: Boolean, onOpen: (Boolean) -> Unit,
    onFeed: (Int) -> Unit, onHold: () -> Unit, onResume: () -> Unit, onReset: () -> Unit,
    state: String? = null,
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
                // Muted while idle; the relevant ones light up (see StopButtons).
                StopButtons(state, connected, onHold, onResume, onReset)
            }
        }
    }
}

/** How long to hold a readout row to open its Move box. */
private const val HOLD_MS = 1500L

/**
 * Tap (released within 500 ms) = [onTap]; held still for [ms] = [onHold]. [progress] runs 0→1
 * while holding (for a fill / ring). A hold let go early, or a finger that slides off (a scroll),
 * does nothing. Child buttons keep their own taps (they consume them first).
 */
private fun Modifier.tapOrHold(
    key: Any, ms: Long, progress: androidx.compose.animation.core.Animatable<Float, *>,
    scope: kotlinx.coroutines.CoroutineScope, onTap: () -> Unit, onHold: () -> Unit,
): Modifier = pointerInput(key) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown()
        val ring = scope.launch {
            progress.snapTo(0f)
            progress.animateTo(1f, androidx.compose.animation.core.tween(ms.toInt(), easing = androidx.compose.animation.core.LinearEasing))
        }
        // "up" = released, "moved" = slid off / scrolling, null = held the full time
        var upAt = 0L
        val outcome: String? = withTimeoutOrNull(ms) {
            var r = ""
            while (r.isEmpty()) {
                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                r = when {
                    c == null -> "moved"
                    !c.pressed -> { upAt = c.uptimeMillis; "up" }
                    (c.position - down.position).getDistance() > slop -> "moved"
                    else -> ""
                }
            }
            r
        }
        ring.cancel()
        scope.launch { progress.snapTo(0f) }
        when (outcome) {
            // Event times (same clock as down.uptimeMillis on every platform).
            "up" -> if (upAt - down.uptimeMillis < 500) onTap()
            null -> {
                onHold()
                // swallow the rest of this touch so lifting the finger isn't also a tap
                while (true) {
                    val ev = awaitPointerEvent()
                    ev.changes.forEach { it.consume() }
                    if (ev.changes.none { it.pressed }) break
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

fun fmtPos(v: Double?, connected: Boolean) = if (v == null || !connected) "---.---" else Fmt.fixed(v, 3, width = 9)

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
        Slider(value = shown.coerceIn(10f, 200f), onValueChange = { drag = ((it / 5f).roundToInt() * 5).toFloat() },
            onValueChangeFinished = { drag?.let { onSet(it.toInt()) }; drag = null },
            valueRange = 10f..200f, enabled = enabled, modifier = Modifier.weight(1f))
        Text("${shown.toInt()}%", fontFamily = FontFamily.Monospace, modifier = Modifier.width(54.dp))
        TextButton(onClick = { onSet(100) }, enabled = enabled, contentPadding = PaddingValues(4.dp)) { Text("100") }
    }
}
