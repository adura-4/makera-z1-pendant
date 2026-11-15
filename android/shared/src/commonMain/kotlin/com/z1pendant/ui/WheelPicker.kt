package com.z1pendant.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A scroll-wheel picker: three rows visible, flick up/down, it snaps so one row sits in the middle
 * (between the two lines) and that one is picked. [onPick] fires each time a new row reaches the
 * middle (the caller ticks the haptics and remembers it).
 */
@Composable
fun WheelPicker(
    items: List<String>, initial: Int, onPick: (Int) -> Unit,
    modifier: Modifier = Modifier, rowH: Dp = 32.dp, width: Dp = 92.dp,
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initial.coerceIn(0, items.lastIndex))
    val rowPx = with(LocalDensity.current) { rowH.toPx() }
    // The row in the middle: the first visible one, or the next once it's scrolled more than half out.
    val centre by remember {
        derivedStateOf {
            val i = state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > rowPx / 2) 1 else 0
            i.coerceIn(0, items.lastIndex)
        }
    }
    LaunchedEffect(Unit) { snapshotFlow { centre }.collect { onPick(it) } }
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.width(width).height(rowH * 3).border(1.dp, MaterialTheme.colorScheme.outline, shape)) {
        LazyColumn(
            state = state, flingBehavior = rememberSnapFlingBehavior(state),
            contentPadding = PaddingValues(vertical = rowH),       // so the first and last rows can sit in the middle
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().height(rowH * 3),
        ) {
            items(items.size) { i ->
                val mid = i == centre
                Box(Modifier.height(rowH).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(items[i], fontFamily = FontFamily.Monospace,
                        fontSize = if (mid) 20.sp else 15.sp,
                        fontWeight = if (mid) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (mid) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                }
            }
        }
        // The two lines that frame the picked row.
        HorizontalDivider(Modifier.align(Alignment.TopCenter).padding(top = rowH, start = 6.dp, end = 6.dp),
            color = MaterialTheme.colorScheme.outline)
        HorizontalDivider(Modifier.align(Alignment.TopCenter).padding(top = rowH * 2, start = 6.dp, end = 6.dp),
            color = MaterialTheme.colorScheme.outline)
    }
}
