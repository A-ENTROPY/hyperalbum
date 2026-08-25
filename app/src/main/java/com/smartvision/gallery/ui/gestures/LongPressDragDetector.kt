package com.smartvision.gallery.ui.gestures

import android.util.Log
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import kotlinx.coroutines.withTimeoutOrNull

/**
 * iOS 26 "Liquid Lensing" gesture: long-press to summon the magnifier, then
 * drag to move it; release to dismiss. Short presses fire [onTap] instead.
 *
 * Implementation note: `awaitPointerEvent()` inside Compose's
 * `AwaitPointerEventScope` is NOT cooperatively cancellable by `withTimeout`
 * — wrapping it in a 400 ms timeout silently never fires. So we drive the
 * long-press timer ourselves: each loop iteration awaits the next event for
 * at most `remainingMs`, then checks wall-clock elapsed time. If the timeout
 * elapses without an UP, we fire `onLongPress`. If UP arrives first, we
 * fire [onTap].
 *
 * Why a single detector owns the gesture (no `Modifier.clickable` on the
 * child items): the bar's `pointerInput` and a child `clickable` compete
 * for the UP event. If the child consumes UP first, the bar's detector
 * never sees it and waits the full long-press timeout — causing the lens
 * to flash on a tap the user thought was a click. Routing both gestures
 * through one detector makes the contract unambiguous.
 */
suspend fun PointerInputScope.detectLongPressThenDrag(
    onTap: (pressPosition: Offset) -> Unit,
    onLongPress: (pressPosition: Offset) -> Unit,
    onDrag: (currentPosition: Offset, totalDrag: Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downId = down.id
        val pressPosition = down.position
        val longPressMs = viewConfiguration.longPressTimeoutMillis
        val startNanos = System.nanoTime()

        Log.d(
            "LiquidLens",
            "DBG gesture: down at $pressPosition, longPressMs=$longPressMs",
        )

        var firedLongPress = false
        while (true) {
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L
            val remaining = longPressMs - elapsedMs
            if (remaining <= 0L) {
                firedLongPress = true
                break
            }
            val event = withTimeoutOrNull(remaining) {
                awaitPointerEvent()
            }
            if (event == null) {
                firedLongPress = true
                break
            }
            val change = event.changes.firstOrNull { it.id == downId }
            if (change == null || change.changedToUp()) {
                val elapsedAtUp = (System.nanoTime() - startNanos) / 1_000_000L
                Log.d(
                    "LiquidLens",
                    "DBG onTap fired at $pressPosition (elapsedMs=$elapsedAtUp)",
                )
                onTap(pressPosition)
                return@awaitEachGesture
            }
        }

        if (!firedLongPress) return@awaitEachGesture
        val elapsedAtLong = (System.nanoTime() - startNanos) / 1_000_000L
        Log.d(
            "LiquidLens",
            "DBG onLongPress fired at $pressPosition (elapsedMs=$elapsedAtLong)",
        )
        onLongPress(pressPosition)

        var totalDrag = Offset.Zero
        try {
            drag(downId) { change: PointerInputChange ->
                totalDrag += change.position - pressPosition
                change.consume()
                onDrag(change.position, totalDrag)
            }
            onDragEnd()
        } catch (_: Throwable) {
            onDragCancel()
        }
    }
}