package org.connectbot.terminal

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #435: a reporter's sub-200ms finger tap starts a text selection on their
 * device, while a synthetic click of the same duration does not on mine. The
 * disagreement is about what the touch stream contains, so the log line has to
 * carry the measurements someone would otherwise have to estimate.
 */
class GestureLogLineTest {

    private fun line(
        outcome: String = "selection-started",
        pressMs: Long = 180,
        thresholdMs: Long = 900,
        mouseMode: Boolean = true,
        movedPx: Float = 3.4f,
        touchSlopPx: Float = 24f,
        pointerType: String = "Touch",
    ) = gestureLogLine(outcome, pressMs, thresholdMs, mouseMode, movedPx, touchSlopPx, pointerType)

    /** Every field the diagnosis turns on must be present, or the report is another round trip. */
    @Test
    fun `carries every measurement the diagnosis needs`() {
        val s = line()
        for (field in listOf(
            "gesture=selection-started", "pressMs=180", "thresholdMs=900",
            "mouseMode=true", "movedPx=3.4", "slopPx=24.0", "pointer=Touch",
        )) {
            assertTrue("missing $field in: $s", s.contains(field))
        }
    }

    /**
     * The decisive contradiction: a selection starting on a press SHORTER than
     * the threshold means the timer is not what produced it, which is the
     * opposite of what three shipped fixes assumed.
     */
    @Test
    fun `a selection under the threshold is visible in the line`() {
        val s = line(pressMs = 180, thresholdMs = 900)
        assertTrue(s.contains("pressMs=180"))
        assertTrue(s.contains("thresholdMs=900"))
    }

    /** Finger vs synthetic click is the reporter's own discriminator. */
    @Test
    fun `distinguishes pointer kinds`() {
        assertTrue(line(pointerType = "Touch").contains("pointer=Touch"))
        assertTrue(line(pointerType = "Mouse").contains("pointer=Mouse"))
    }

    /** A "tap" that drifts past touch slop is really a drag — the line must show it. */
    @Test
    fun `movement is reported against the slop it is judged by`() {
        val s = line(movedPx = 31.2f, touchSlopPx = 24f)
        assertTrue(s.contains("movedPx=31.2"))
        assertTrue(s.contains("slopPx=24.0"))
    }

    /** Outcomes must be greppable and distinct. */
    @Test
    fun `outcome names the result`() {
        assertTrue(line(outcome = "ended-undetermined").contains("gesture=ended-undetermined"))
        assertTrue(line(outcome = "selection-started").contains("gesture=selection-started"))
    }
}
