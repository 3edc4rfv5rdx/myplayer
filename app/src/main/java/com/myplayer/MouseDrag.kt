package com.myplayer

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.launch
import kotlin.math.abs

// Compose scrolls a list for a mouse by its wheel only: a drag with the button
// held is left to the touch screen. A TV box's air mouse has no wheel, so
// without this nothing in a list moves under it. A finger never gets here —
// this only takes a drag that starts with a mouse button.

/**
 * [state]'s vertical list pulled along with a mouse's button held, and flung on
 * when let go. Taken ahead of what lies under it, once past the touch slop, so
 * a row dragged across is not clicked or long-pressed too; a click that does
 * not move, or a drag that sets off sideways, is left alone.
 */
@Composable
fun Modifier.mouseDragScroll(state: ScrollableState): Modifier {
    val fling = ScrollableDefaults.flingBehavior()
    val scope = rememberCoroutineScope()
    return pointerInput(state, fling) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type != PointerType.Mouse) return@awaitEachGesture
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var pulled = Offset.Zero
            var dragging = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    // Let go after a drag: not a click on whatever is under it now.
                    if (dragging) change.consume()
                    break
                }
                tracker.addPosition(change.uptimeMillis, change.position)
                val moved = change.position - change.previousPosition
                if (dragging) {
                    // What is under the pointer goes with it, so the scroll runs the other way.
                    state.dispatchRawDelta(-moved.y)
                } else {
                    pulled += moved
                    if (pulled.getDistance() < viewConfiguration.touchSlop) continue
                    // Pulled sideways: a seek bar's drag, not a scroll — left to it.
                    if (abs(pulled.x) > abs(pulled.y)) break
                    dragging = true
                    state.dispatchRawDelta(-pulled.y)
                }
                change.consume()
            }
            if (dragging) {
                val velocity = -tracker.calculateVelocity().y
                scope.launch { state.scroll { with(fling) { performFling(velocity) } } }
            }
        }
    }
}

/** A column that scrolls, by a finger or a mouse alike. */
@Composable
fun Modifier.verticalScrollByAny(state: ScrollState = rememberScrollState()): Modifier =
    mouseDragScroll(state).verticalScroll(state)
