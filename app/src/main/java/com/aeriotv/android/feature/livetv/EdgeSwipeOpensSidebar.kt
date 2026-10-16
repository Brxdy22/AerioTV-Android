package com.aeriotv.android.feature.livetv

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max

/**
 * Apple issue #102 parity (phone / tablet, Sidebar Menu only): a horizontal
 * drag that starts in a band just inside the start edge and travels at least
 * 40 dp toward the content opens the group sidebar.
 *
 * The band never overlaps the system back-gesture inset (Logan 2026-10-16:
 * do not fight the system edge, no exclusion rects). It starts at
 * max(24 dp, WindowInsets.systemGestures start inset) and is 48 dp wide, so
 * 24 to 72 dp on a device whose gesture inset is 24 dp or less.
 *
 * Observes on the Initial pass without consuming, so vertical scrolling and
 * children see every event until the gesture is recognized; a drag that
 * goes vertical first is abandoned. Only once it triggers are the remaining
 * events consumed.
 */
fun Modifier.edgeSwipeOpensSidebar(enabled: Boolean, onOpen: () -> Unit): Modifier = composed {
    if (!enabled) return@composed this
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val insetPx = if (rtl) WindowInsets.systemGestures.getRight(density, LayoutDirection.Rtl)
    else WindowInsets.systemGestures.getLeft(density, LayoutDirection.Ltr)
    val open by rememberUpdatedState(onOpen)
    pointerInput(rtl, insetPx) {
        val zoneStart = max(24.dp.toPx(), insetPx.toFloat())
        val zoneEnd = zoneStart + 48.dp.toPx()
        val travel = 40.dp.toPx()
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val fromEdge = if (rtl) size.width - down.position.x else down.position.x
            if (fromEdge < zoneStart || fromEdge > zoneEnd) return@awaitEachGesture
            var dx = 0f
            var dy = 0f
            var triggered = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (triggered) { change.consume(); continue }
                val d = change.positionChange()
                dx += if (rtl) -d.x else d.x
                dy += d.y
                // Vertical-dominant or moving away from the content: not ours.
                if (abs(dy) > slop && abs(dy) >= abs(dx)) break
                if (dx < -slop) break
                if (dx >= travel && dx > abs(dy)) {
                    triggered = true
                    change.consume()
                    android.util.Log.i("AerioFocus", "[GROUPS] edge swipe opened sidebar")
                    open()
                }
            }
        }
    }
}
