package org.connectbot.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #421: a user set tmux to scroll one line per wheel event and still got five
 * lines per swipe. The bindings were right — Haven was sending five wheel
 * events, because the travel needed for one event was a raw pixel count.
 *
 * 24px is ~1.3mm on a 3x phone and ~4mm on a 1x screen, so the same physical
 * gesture produced three times as many escape sequences on a dense display.
 */
class ScrollThresholdTest {

    /** The bug: identical physical travel must cost the same number of events. */
    @Test
    fun `the same physical distance yields the same number of wheel events`() {
        // 10mm of travel, in pixels, at three densities (1dp ~= 1/160 inch).
        val tenMmDp = 10f / 25.4f * 160f
        val counts = listOf(1f, 2f, 3.5f).map { density ->
            val travelPx = tenMmDp * density
            (travelPx / scrollThresholdPx(density)).toInt()
        }
        assertEquals(
            "a 10mm swipe must send the same wheel-event count on every display",
            listOf(counts.first(), counts.first(), counts.first()),
            counts,
        )
    }

    /** Chosen so behaviour is unchanged on the 3x displays most users hold. */
    @Test
    fun `matches the previous raw 24px on a 3x display`() {
        assertEquals(24f, scrollThresholdPx(3f), 0.001f)
    }

    /** Sanity: it scales, rather than being a constant wearing a function's clothes. */
    @Test
    fun `scales with density`() {
        assertTrue(scrollThresholdPx(1f) < scrollThresholdPx(2f))
        assertEquals(scrollThresholdPx(1f) * 2f, scrollThresholdPx(2f), 0.001f)
    }

    /**
     * Guard the magnitude, not just the scaling: one wheel event per ~1.3mm is
     * already twitchy, and a value low enough to fire on incidental movement
     * would flood a mouse-mode app with escape sequences.
     */
    @Test
    fun `threshold stays in a usable physical range`() {
        val mmPerEvent = scrollThresholdPx(3f) / 3f / 160f * 25.4f
        assertTrue("one wheel event per ${mmPerEvent}mm is too twitchy", mmPerEvent >= 1.0f)
        assertTrue("one wheel event per ${mmPerEvent}mm would feel sluggish", mmPerEvent <= 6.0f)
    }
}
