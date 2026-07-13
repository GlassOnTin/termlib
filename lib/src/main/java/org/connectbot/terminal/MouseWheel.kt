package org.connectbot.terminal

import kotlin.math.abs

/**
 * Rows the local scrollback moves for one wheel notch when no remote app is
 * tracking the mouse. Three is the near-universal terminal default (xterm,
 * gnome-terminal, iTerm), and it is what a user's muscle memory expects.
 */
const val WHEEL_SCROLLBACK_ROWS = 3

/**
 * Turns a stream of Compose `scrollDelta.y` values into whole wheel notches.
 *
 * A classic wheel reports one notch at a time, but high-resolution wheels and
 * trackpad-style pointing devices report fractions, so a naive "one event =
 * one notch" mapping either scrolls far too fast or drops small movements
 * entirely. Accumulating and draining whole notches handles both: fractions
 * add up until they earn a notch, and the remainder carries over.
 *
 * Sign follows Compose, where a wheel turned away from the user (scroll up,
 * back into history) is NEGATIVE — the inverse of Android's raw
 * `MotionEvent.AXIS_VSCROLL`, which Compose flips on the way in.
 */
class WheelNotchAccumulator {

    private var accumulated = 0f

    /**
     * Feed one `scrollDelta.y`. Returns the whole notches it completed:
     * positive = scroll up (into history), negative = scroll down, 0 = the
     * movement is still too small to act on and has been banked.
     */
    fun feed(scrollDeltaY: Float): Int {
        if (scrollDeltaY.isNaN() || scrollDeltaY == 0f) return 0
        accumulated += scrollDeltaY
        var notches = 0
        while (abs(accumulated) >= 1f) {
            if (accumulated <= -1f) {
                notches += 1 // up
                accumulated += 1f
            } else {
                notches -= 1 // down
                accumulated -= 1f
            }
        }
        return notches
    }

    /**
     * Drop banked fractions. Called when the pointer leaves or the emulator is
     * swapped, so a half-notch from one context can't leak into the next.
     */
    fun reset() {
        accumulated = 0f
    }
}
