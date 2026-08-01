package org.connectbot.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #479: text is reported as clipped after pinch-zooming, with no way to scroll
 * across to the missing part. Pinch-zoom changes the font size, which changes
 * how many columns fit, which resizes the terminal — so the question is what a
 * shrink does to text that is already on screen, and whether growing back
 * restores it.
 *
 * If a shrink discards the overflow, zooming in and back out is lossy and the
 * blank space on the right of the reporter's video is content that no longer
 * exists.
 */
@RunWith(AndroidJUnit4::class)
class ResizeReflowTest {

    private fun visibleText(e: TerminalEmulatorImpl): String {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        e.processPendingUpdates()
        return e.snapshot.value.lines.joinToString("\n") { it.text.trimEnd() }.trimEnd()
    }

    @Test
    fun `shrinking then growing the terminal preserves a long line`() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(initialRows = 10, initialCols = 80)
            as TerminalEmulatorImpl
        // 70 printable characters, comfortably inside 80 columns.
        val line = (1..7).joinToString("") { "ABCDEFGHI$it" }
        emulator.writeInput("$line\r\n".toByteArray())
        delay(120)

        assertEquals(
            "precondition: the line should be intact at 80 columns",
            line,
            visibleText(emulator).lines().first(),
        )

        // Zoom in: bigger glyphs, so fewer columns fit.
        emulator.resize(10, 40)
        delay(120)
        // Zoom back out.
        emulator.resize(10, 80)
        delay(120)

        val after = visibleText(emulator)
        assertEquals(
            "after shrinking to 40 columns and growing back to 80, the line should " +
                "still be readable in full — if the tail is gone, pinch-zoom is " +
                "destroying terminal content rather than just re-laying it out (#479). " +
                "Got:\n$after",
            line,
            after.lines().joinToString("").trimEnd(),
        )
    }
}
