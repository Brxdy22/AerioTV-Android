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
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max

/**
 * Apple issue #102 parity (phone / tablet List view, Sidebar Menu only): a
 * horizontal drag that starts in a band just inside the start edge slides
 * the group drawer out under the finger ([EdgeDrawerDragState.dragPx]). On
 * release the drawer opens when the travel passes 40% of its width or the
 * fling toward the content exceeds 300 dp/s; otherwise it closes again.
 *
 * The band never overlaps the system back-gesture inset (Logan 2026-10-16:
 * do not fight the system edge, no exclusion rects). It starts at
 * max(24 dp, WindowInsets.systemGestures start inset) and is 48 dp wide, so
 * 24 to 72 dp on a device whose gesture inset is 24 dp or less.
 *
 * Observes on the Initial pass without consuming until the drag is
 * recognized (touch slop, horizontal dominant, toward the content), so
 * vertical scrolling and children are untouched; after that it consumes.
 *
 * Guide variant: [railBandPx] replaces the edge band with the fixed channel
 * column (0 to railBandPx from the grid's left, where GuideGrid draws it in
 * every layout direction), skipping the top [topExcludePx] (time header).
 * The program grid keeps its own horizontal pan.
 */
internal fun Modifier.edgeSwipeOpensSidebar(
    enabled: Boolean,
    drag: EdgeDrawerDragState,
    railBandPx: Float? = null,
    topExcludePx: Float = 0f,
    onOpen: () -> Unit,
): Modifier = composed {
    if (!enabled) return@composed this
    val density = LocalDensity.current
    val rtl = railBandPx == null && LocalLayoutDirection.current == LayoutDirection.Rtl
    val insetPx = if (rtl) WindowInsets.systemGestures.getRight(density, LayoutDirection.Rtl)
    else WindowInsets.systemGestures.getLeft(density, LayoutDirection.Ltr)
    val open by rememberUpdatedState(onOpen)
    pointerInput(rtl, insetPx, railBandPx, topExcludePx) {
        val zoneStart = if (railBandPx != null) 0f else max(24.dp.toPx(), insetPx.toFloat())
        val zoneEnd = railBandPx ?: (zoneStart + 48.dp.toPx())
        val flingPx = 300.dp.toPx()
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val fromEdge = if (rtl) size.width - down.position.x else down.position.x
            if (fromEdge < zoneStart || fromEdge > zoneEnd) return@awaitEachGesture
            if (down.position.y < topExcludePx) return@awaitEachGesture
            var dx = 0f
            var dy = 0f
            var tracking = false
            val velocity = VelocityTracker()
            velocity.addPosition(down.uptimeMillis, down.position)
            var released = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    velocity.addPosition(change.uptimeMillis, change.position)
                    if (!change.pressed) {
                        // Consume the up once claimed so the row under the finger
                        // never completes a press (no click on release).
                        if (tracking) change.consume()
                        released = true
                        break
                    }
                    val d = change.positionChange()
                    dx += if (rtl) -d.x else d.x
                    dy += d.y
                    if (!tracking) {
                        // Vertical-dominant or moving away from the content: not ours.
                        if (abs(dy) > slop && abs(dy) >= abs(dx)) break
                        if (dx < -slop) break
                        if (dx > slop && dx > abs(dy)) tracking = true else continue
                    }
                    change.consume()
                    drag.dragPx = (dx - slop).coerceIn(0f, drag.drawerWidthPx)
                }
            } finally {
                if (tracking) {
                    val vx = velocity.calculateVelocity().x.let { if (rtl) -it else it }
                    val travel = drag.dragPx ?: 0f
                    val commit = released &&
                        (travel > drag.drawerWidthPx * 0.4f || vx > flingPx)
                    if (commit) {
                        android.util.Log.i("AerioFocus", "[GROUPS] edge swipe opened sidebar")
                        open()
                    }
                    // Same frame as open(): the host animates from here to 1 or 0.
                    drag.dragPx = null
                }
            }
        }
    }
}
