package org.connectbot.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #435: a reporter's sub-200ms finger tap starts a text selection on their
 * device, while a synthetic click of the same duration does not on mine. The
 * disagreement is about what the touch stream contains, so the log line has to
 * carry the measurements someone would otherwise have to estimate.
 *
 * The first version of this line actively misled: the duration field was called
 * `pressMs` but was sampled when the long-press timer fired, so on a
 * `selection-started` line it always read ~threshold. That number was quoted
 * back at the reporter as evidence they had pressed for 901ms when they had
 * said, twice, that their taps were short. These tests pin the fields that
 * replaced it.
 */
class GestureLogLineTest {

    private fun line(
        outcome: String = "selection-started",
        gestureId: Long = 7,
        sinceDownMs: Long = 180,
        thresholdMs: Long = 900,
        mouseMode: Boolean = true,
        movedPx: Float = 3.4f,
        touchSlopPx: Float = 24f,
        pointerType: String = "Touch",
    ) = gestureLogLine(
        outcome, gestureId, sinceDownMs, thresholdMs, mouseMode, movedPx, touchSlopPx, pointerType,
    )

    /** Every field the diagnosis turns on must be present, or the report is another round trip. */
    @Test
    fun `carries every measurement the diagnosis needs`() {
        val s = line()
        for (field in listOf(
            "gesture=selection-started", "id=7", "sinceDownMs=180", "thresholdMs=900",
            "mouseMode=true", "movedPx=3.4", "slopPx=24.0", "pointer=Touch",
        )) {
            assertTrue("missing $field in: $s", s.contains(field))
        }
    }

    /**
     * The field must NOT be called pressMs. It is time since finger-down at the
     * moment of logging, which on a selection-started line is the threshold
     * restated — naming it after the user's press is what caused the
     * misdiagnosis this line exists to prevent.
     */
    @Test
    fun `does not claim to report how long the user pressed`() {
        val s = line()
        assertTrue("the honest field name is missing: $s", s.contains("sinceDownMs="))
        assertTrue("pressMs is the name that misled; it must not return: $s", !s.contains("pressMs"))
    }

    /**
     * Merged gestures are the actual defect: several physical taps arriving as
     * one gesture. Sharing an id is how that becomes visible in a capture.
     */
    @Test
    fun `taps in the same gesture share an id`() {
        val start = line(outcome = "selection-started", gestureId = 12, sinceDownMs = 901)
        val end = line(outcome = "ended-undetermined", gestureId = 12, sinceDownMs = 6794)
        assertTrue(start.contains("id=12"))
        assertTrue(end.contains("id=12"))
        // The pair is the evidence: a selection that fired at the threshold, and
        // the same gesture still running nearly six seconds later.
        assertTrue(end.contains("sinceDownMs=6794"))
    }

    /** A new gesture must be distinguishable from a continuing one. */
    @Test
    fun `separate gestures get separate ids`() {
        assertTrue(line(gestureId = 1).contains("id=1"))
        assertTrue(line(gestureId = 2).contains("id=2"))
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

    /** Filterable with `logcat -s HavenGesture`, so the shape must stay stable. */
    @Test
    fun `is one greppable line`() {
        val s = line()
        assertEquals("must not wrap onto multiple lines", 1, s.lines().size)
        assertTrue(s.startsWith("gesture="))
    }
}
