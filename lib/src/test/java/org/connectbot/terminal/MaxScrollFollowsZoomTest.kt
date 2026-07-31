package org.connectbot.terminal

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #478: the scrollback could not be scrolled all the way after pinch-zooming,
 * intermittently.
 *
 * The scroll limit is `scrollback lines x character height`, so a zoom changes
 * it twice over — the glyphs get taller and the reflow moves lines into the
 * scrollback. But the gesture handler is a long-lived
 * `pointerInput(terminalEmulator, gestureCallback)` coroutine that does NOT
 * restart on a font-size change, so a plain capture of that limit stays frozen
 * at its pre-zoom value and every later scroll clamps to it. The intermittency
 * came from anything that changed `gestureCallback` — entering mouse mode, an
 * app taking the alt screen — restarting the handler and quietly fixing it
 * until the next zoom.
 *
 * The char metrics were already routed through [rememberUpdatedState] for
 * exactly this reason; the scroll limit was not. This pins that a value read
 * that way tracks changes while a plain capture does not, which is the whole
 * difference between the two.
 */
@RunWith(AndroidJUnit4::class)
class MaxScrollFollowsZoomTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `a captured scroll limit goes stale across a zoom while a State read does not`() {
        val charHeight = mutableFloatStateOf(20f)
        val scrollbackLines = mutableIntStateOf(100)

        // What a long-lived gesture coroutine sees, both ways.
        var capturedOnce = -1f
        var readThroughState: (() -> Float)? = null

        composeTestRule.setContent {
            val maxScroll = scrollbackLines.intValue * charHeight.floatValue
            val currentMaxScroll = rememberUpdatedState(maxScroll)
            if (capturedOnce < 0f) {
                // Captured when the handler started, exactly as the old code did.
                capturedOnce = maxScroll
                readThroughState = { currentMaxScroll.value }
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(2000f, capturedOnce, 0.01f)
        assertEquals(2000f, readThroughState!!(), 0.01f)

        // Pinch-zoom in: taller glyphs, and the reflow pushes more lines into
        // the scrollback. Both inputs to the limit move.
        charHeight.floatValue = 30f
        scrollbackLines.intValue = 140
        composeTestRule.waitForIdle()

        assertEquals(
            "the captured value is frozen at its pre-zoom limit — this is the bug",
            2000f,
            capturedOnce,
            0.01f,
        )
        assertEquals(
            "the State read must track the zoom, or the new scrollback is unreachable",
            4200f,
            readThroughState!!(),
            0.01f,
        )
    }
}
