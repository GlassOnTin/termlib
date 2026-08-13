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

    /**
     * Anchored to a real measured device rather than an assumed density: the
     * OnePlus 13 this was tested on runs a 420dpi override (2.625x), where the
     * old raw 24px worked out to 9.1dp. Landing within a pixel of that keeps
     * the change imperceptible on the hardware it was measured against.
     */
    @Test
    fun `stays within a pixel of the old behaviour on the measured device`() {
        assertEquals(24f, scrollThresholdPx(2.625f), 1.0f)
    }

    /** On the dense displays the complaint came from, it must not get twitchier. */
    @Test
    fun `is no more sensitive than before on 3x and 4x displays`() {
        assertTrue("3x got more twitchy", scrollThresholdPx(3f) >= 24f)
        assertTrue("4x got more twitchy", scrollThresholdPx(4f) >= 24f)
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

    // #524: the host coarsens the per-event quantum for swipe-arrows mode so
    // one deliberate swipe steps history entry by entry.
    @Test
    fun `callback quantum multiplies the content-scroll threshold`() {
        assertEquals(
            scrollThresholdPx(2.625f) * 4f,
            callbackScrollThresholdPx(2.625f, 4f),
            0.001f,
        )
    }

    /** A multiplier below 1 must not make scrolling twitchier than the default. */
    @Test
    fun `callback quantum clamps sub-1 multipliers to the content default`() {
        assertEquals(scrollThresholdPx(3f), callbackScrollThresholdPx(3f, 0.25f), 0.001f)
        assertEquals(scrollThresholdPx(3f), callbackScrollThresholdPx(3f, 1f), 0.001f)
    }
}
