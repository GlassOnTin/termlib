@file:OptIn(androidx.compose.ui.ExperimentalIndirectPointerApi::class)

package org.connectbot.terminal

import android.view.MotionEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.indirect.IndirectPointerEvent
import androidx.compose.ui.input.indirect.IndirectPointerInputModifierNode
import androidx.compose.ui.input.indirect.nativeEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.node.ModifierNodeElement

/**
 * Handle a physical touchpad's two-finger scroll over the terminal.
 *
 * Compose 1.11 routes a touchpad `ACTION_SCROLL` through the *indirect* pointer
 * channel, delivered only to an [IndirectPointerInputModifierNode]. The
 * terminal's mouse-wheel handler is a plain `pointerInput` filtering
 * `PointerEventType.Scroll`, which never receives indirect events — so a
 * Bluetooth-keyboard/touchpad two-finger scroll was dead in the terminal even
 * though it scrolls the app's own lists (foundation scrollables implement this
 * same node). A hardware mouse wheel arrives as a *direct* scroll event and
 * kept working; only the touchpad gesture was missing. (#419)
 *
 * [onScrollNotches] receives whole wheel notches (positive = up/into history)
 * and the event position, so the caller can reuse the exact mouse-wheel body
 * (forward to a mouse-tracking app, else move local scrollback).
 *
 * NB: this depends on Compose UI's indirect-pointer API. Verified present in
 * 1.11.4 (termlib pins 1.11.x); if a Compose bump changes it, this is the file
 * to revisit.
 */
internal fun Modifier.indirectVerticalScroll(
    onScrollNotches: (steps: Int, x: Float, y: Float) -> Unit,
): Modifier = this then IndirectScrollElement(onScrollNotches)

private data class IndirectScrollElement(
    val onScrollNotches: (Int, Float, Float) -> Unit,
) : ModifierNodeElement<IndirectScrollNode>() {
    override fun create() = IndirectScrollNode(onScrollNotches)
    override fun update(node: IndirectScrollNode) {
        node.onScrollNotches = onScrollNotches
    }
}

private class IndirectScrollNode(
    var onScrollNotches: (Int, Float, Float) -> Unit,
) : Modifier.Node(), IndirectPointerInputModifierNode {

    private val notches = WheelNotchAccumulator()

    override fun onIndirectPointerEvent(event: IndirectPointerEvent, pass: PointerEventPass) {
        if (pass != PointerEventPass.Main) return
        val native = event.nativeEvent
        if (native.actionMasked != MotionEvent.ACTION_SCROLL) return

        var axisV = native.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (axisV == 0f && android.os.Build.VERSION.SDK_INT >= 34) {
            // Some touchpad drivers report the generic AXIS_SCROLL (API 34+)
            // rather than the legacy AXIS_VSCROLL.
            axisV = native.getAxisValue(MotionEvent.AXIS_SCROLL)
        }
        if (axisV == 0f) return

        // Compose flips AXIS_VSCROLL on the way in for the mouse-wheel path, and
        // WheelNotchAccumulator follows that flipped sign (up/into-history is
        // negative). We read the raw axis here, so negate to match. (#419)
        val steps = notches.feed(-axisV)
        event.changes.forEach { it.consume() }
        if (steps != 0) onScrollNotches(steps, native.x, native.y)
    }

    override fun onCancelIndirectPointerInput() {
        notches.reset()
    }
}
