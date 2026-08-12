package org.connectbot.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #478, reopened: the reporter confirmed the v5.86.20 stale-clamp fix, then
 * hours later — after more use — "the issue still exists". So the clamp was
 * one mechanism, and something else with the same symptom survives it. These
 * tests interrogate the layer below the gesture code: does the *content*
 * survive the resizes a pinch-zoom generates, or is "can't scroll back to it"
 * sometimes literal because the lines no longer exist?
 *
 * A pinch-zoom resizes rows and cols together (bigger glyphs, fewer of both).
 * Fewer rows pushes the top screen lines into scrollback; more rows pops them
 * back. The pop path rebuilds only the first `cols` cells of the stored line
 * and drops the line from the store either way — so any pop at a width
 * narrower than the line was stored at would destroy the tail permanently.
 */
@RunWith(AndroidJUnit4::class)
class ZoomScrollbackIntegrityTest {

    private fun settle(e: TerminalEmulatorImpl) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        e.processPendingUpdates()
    }

    /** Scrollback + screen, oldest first, blank-stripped: everything reachable. */
    private fun allText(e: TerminalEmulatorImpl): List<String> {
        settle(e)
        val snap = e.snapshot.value
        return (snap.scrollback.map { it.text.trimEnd() } + snap.lines.map { it.text.trimEnd() })
            .filter { it.isNotEmpty() }
    }

    @Test
    fun `pinch-zoom row cycles preserve every scrollback line`() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
            as TerminalEmulatorImpl
        val n = 100
        for (i in 1..n) emulator.writeInput("line-%03d\r\n".format(i).toByteArray())
        delay(200)

        val before = allText(emulator)
        assertTrue(
            "precondition: expected all $n lines on screen+scrollback, got ${before.size}",
            before.containsAll((1..n).map { "line-%03d".format(it) }),
        )

        // Three zoom-in/zoom-out cycles: glyphs bigger (fewer rows AND cols),
        // then back. The reporter's repro is "zoom in and out a few times".
        repeat(3) {
            emulator.resize(12, 40)
            delay(150)
            emulator.resize(24, 80)
            delay(150)
        }

        val after = allText(emulator)
        val missing = (1..n).map { "line-%03d".format(it) }.filterNot { after.contains(it) }
        assertEquals(
            "zoom cycles destroyed scrollback lines — 'not fully scrollable' is " +
                "literal: the content no longer exists. Missing: $missing",
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun `growing rows at a narrower width must not destroy stored line tails`() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
            as TerminalEmulatorImpl
        // The pop takes the MOST RECENT scrollback line first, so the marker
        // must sit near the end of the store to be inside the popped window:
        // fillers first, the 70-char marker, then just enough lines to scroll
        // it off screen (24 rows) but keep it within the ~16 lines a
        // 24-row -> 40-row growth pops back.
        val marker = (1..7).joinToString("") { "ABCDEFGHI$it" }
        for (i in 1..30) emulator.writeInput("filler-%02d\r\n".format(i).toByteArray())
        emulator.writeInput("$marker\r\n".toByteArray())
        for (i in 1..30) emulator.writeInput("extra-%02d\r\n".format(i).toByteArray())
        delay(200)
        settle(emulator)
        val sb = emulator.snapshot.value.scrollback.map { it.text.trimEnd() }
        val depthFromEnd = sb.size - 1 - sb.indexOf(marker)
        assertTrue(
            "precondition: the marker must be in scrollback within the popped " +
                "window (found ${sb.indexOf(marker)} of ${sb.size}, " +
                "$depthFromEnd from the end; need < 16)",
            sb.contains(marker) && depthFromEnd < 16,
        )

        // Rows grow while cols shrink — the rotation shape (landscape wide/short
        // to portrait narrow/tall). The row growth pops stored 80-wide lines
        // back onto a 40-wide screen.
        emulator.resize(40, 40)
        delay(150)
        // And back: the line should still exist in full somewhere reachable.
        emulator.resize(24, 80)
        delay(150)

        val after = allText(emulator)
        assertTrue(
            "the 80-col line's tail was destroyed by a pop at 40 cols — after a " +
                "rotation round trip the stored content should still be readable " +
                "in full (soft-wrapped is fine, gone is not). Got:\n" +
                after.joinToString("\n"),
            after.contains(marker) || after.joinToString("").contains(marker),
        )
    }
}
