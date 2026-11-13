package com.z1pendant.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.z1pendant.hotkeys.Hotkey
import kotlinx.coroutines.launch

private const val COLS = 4
private val TILE_H = 78.dp
private val GAP = 8.dp
/** Hold this long on a tile to open its editor (a ring fills while you hold). */
private const val LONG_PRESS_MS = 2000L

/**
 * Hotkey tiles in a 4-wide grid. One gesture handler on the grid (not per tile), so tiles can
 * move under the finger without disturbing the gesture:
 * - normal: tap = run, hold 2 s = edit (ring shows progress). Vertical drags still scroll the page.
 * - arranging: drag a tile to a new slot (others slide live), tap = edit.
 */
@Composable
fun HotkeyGrid(
    items: List<Hotkey>,
    arranging: Boolean,
    toggleOf: (Hotkey) -> Boolean?,
    onTap: (Hotkey) -> Unit,
    onLong: (Hotkey) -> Unit,
    onMove: (id: String, toIndex: Int) -> Unit,
    onLift: () -> Unit,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { GAP.toPx() }
    val tileHPx = with(density) { TILE_H.toPx() }
    var widthPx by remember { mutableFloatStateOf(0f) }
    val cellW = ((widthPx - gapPx * (COLS - 1)) / COLS).coerceAtLeast(1f)
    fun topLeft(i: Int) = Offset((i % COLS) * (cellW + gapPx), (i / COLS) * (tileHPx + gapPx))
    fun indexAt(p: Offset): Int? {
        if (p.x < 0 || p.y < 0) return null
        val c = (p.x / (cellW + gapPx)).toInt(); val r = (p.y / (tileHPx + gapPx)).toInt()
        if (c >= COLS) return null
        val i = r * COLS + c
        return i.takeIf { it in items.indices }
    }

    val latestItems by rememberUpdatedState(items)
    var pressId by remember { mutableStateOf<String?>(null) }
    val progress = remember { Animatable(0f) }
    var dragId by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val slop = LocalViewConfiguration.current.touchSlop

    val gestures = if (arranging) {
        Modifier
            .pointerInput("arrange") {
                detectDragGestures(
                    onDragStart = { p ->
                        val i = indexAt(p) ?: return@detectDragGestures
                        dragId = latestItems[i].id; grab = p - topLeft(i); pointer = p; onLift()
                    },
                    onDrag = { change, amount ->
                        val id = dragId ?: return@detectDragGestures
                        change.consume()
                        pointer += amount
                        val to = indexAt(pointer)
                            ?: if (pointer.y > topLeft(latestItems.lastIndex).y) latestItems.lastIndex else null
                        val from = latestItems.indexOfFirst { it.id == id }
                        if (to != null && from >= 0 && to != from) onMove(id, to)
                    },
                    onDragEnd = { dragId = null },
                    onDragCancel = { dragId = null },
                )
            }
            .pointerInput("arrangeTap") {
                detectTapGestures { p -> indexAt(p)?.let { onTap(latestItems[it]) } }
            }
    } else {
        Modifier.pointerInput("normal") {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val i = indexAt(down.position) ?: return@awaitEachGesture
                val h = latestItems[i]
                pressId = h.id
                val ring = scope.launch {
                    progress.snapTo(0f)
                    progress.animateTo(1f, tween(LONG_PRESS_MS.toInt(), easing = LinearEasing))
                }
                // "up" = tap, "moved" = a scroll or a slip off the tile, null = held the full time.
                // (AwaitPointerEventScope's own withTimeoutOrNull: allowed inside a pointer gesture.)
                val outcome: String? = withTimeoutOrNull(LONG_PRESS_MS) {
                    var r = ""
                    while (r.isEmpty()) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id }
                        r = when {
                            c == null -> "moved"
                            !c.pressed -> "up"
                            (c.position - down.position).getDistance() > slop -> "moved"
                            else -> ""
                        }
                    }
                    r
                }
                ring.cancel()
                scope.launch { progress.snapTo(0f) }
                pressId = null
                when (outcome) {
                    // A hold abandoned before 2 s does nothing: only a quick tap runs the hotkey.
                    "up" -> if (android.os.SystemClock.uptimeMillis() - down.uptimeMillis < 500) { down.consume(); onTap(h) }
                    null -> {
                        onLong(h)
                        // swallow the rest of this touch so lifting the finger isn't a tap
                        while (true) {
                            val ev = awaitPointerEvent()
                            ev.changes.forEach { it.consume() }
                            if (ev.changes.none { it.pressed }) break
                        }
                    }
                }
            }
        }
    }

    Layout(
        content = {
            items.forEachIndexed { i, h ->
                key(h.id) {
                    val dragging = h.id == dragId
                    val lift = if (dragging) (pointer - grab) - topLeft(i) else Offset.Zero
                    HotkeyTileView(
                        h, arranging, toggleOf(h),
                        ring = if (h.id == pressId) progress.value else 0f,
                        modifier = Modifier
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer {
                                translationX = lift.x; translationY = lift.y
                                val s = if (dragging) 1.08f else 1f; scaleX = s; scaleY = s
                                shadowElevation = if (dragging) 16f else 0f
                            },
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth().then(gestures),
    ) { measurables, constraints ->
        widthPx = constraints.maxWidth.toFloat()
        val w = ((constraints.maxWidth - gapPx * (COLS - 1)) / COLS).toInt().coerceAtLeast(1)
        val h = tileHPx.toInt()
        val placeables = measurables.map { it.measure(androidx.compose.ui.unit.Constraints.fixed(w, h)) }
        val rows = (placeables.size + COLS - 1) / COLS
        val height = if (rows == 0) 0 else (rows * h + (rows - 1) * gapPx).toInt()
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { i, p ->
                val tl = topLeft(i)
                p.place(tl.x.toInt(), tl.y.toInt())
            }
        }
    }
}

@Composable
private fun HotkeyTileView(h: Hotkey, arranging: Boolean, toggle: Boolean?, ring: Float, modifier: Modifier) {
    // Toggle tiles (light): bright white = on, dimmed = off. Not a toggle / unknown = normal.
    val fg = when (toggle) {
        true -> Color.White
        false -> MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.35f)
        null -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    val ringColor = MaterialTheme.colorScheme.primary
    Column(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .then(if (arranging) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
            .drawWithContent {
                drawContent()
                // Hold-to-edit progress: appears after a short delay so normal taps don't flash it.
                val shown = ((ring - 0.12f) / 0.88f).coerceIn(0f, 1f)
                if (shown > 0f) {
                    val inset = 4.dp.toPx(); val sw = 3.dp.toPx()
                    drawArc(ringColor, -90f, 360f * shown, false,
                        topLeft = Offset(inset, inset), size = Size(size.width - 2 * inset, size.height - 2 * inset),
                        style = Stroke(sw, cap = StrokeCap.Round))
                }
            }
            .alpha(if (arranging && !h.showOnMain) 0.5f else 1f).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box {
            HotkeyIcon(h.icon, 26.dp, fg)
            if (arranging && !h.showOnMain) Icon(Icons.Filled.VisibilityOff, null, Modifier.size(12.dp).align(Alignment.TopEnd))
        }
        Text(h.label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = fg,
            fontWeight = if (toggle == true) FontWeight.SemiBold else FontWeight.Normal)
        if (h.triggers.isNotEmpty() || h.confirm) {
            Text((if (h.confirm) "ask " else "") + (if (h.triggers.isNotEmpty()) "⌨${h.triggers.size}" else ""),
                fontSize = 9.sp, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
        }
    }
}
