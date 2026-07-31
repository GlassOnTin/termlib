package org.connectbot.terminal

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog

/**
 * #435: the long-press timer is launched into the coroutine scope that
 * encloses the whole pointer input, not into the gesture. When
 * `awaitEachGesture` cancels the gesture block — which is what an
 * accessibility service re-dispatching touches causes — the cleanup below the
 * loop is skipped and that timer OUTLIVES the gesture, firing its full
 * threshold after the finger has gone and starting a selection out of nowhere.
 *
 * That is why three successive threshold changes did nothing for the reporter:
 * raising the threshold only delays the phantom selection.
 *
 * The positive control matters as much as the assertion: if a genuine hold
 * cannot produce a selection in this harness, then "no selection after cancel"
 * proves nothing at all.
 */
@RunWith(AndroidJUnit4::class)
class CancelledGestureLongPressTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun selectionLines() = ShadowLog.getLogsForTag("HavenGesture").map { it.msg }.filter { "selection-started" in it }

    private fun renderTerminal() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        runBlocking { emulator.writeInput("ready\r\n".toByteArray()) }
        composeTestRule.setContent {
            TerminalWithAccessibility(
                terminalEmulator = emulator,
                keyboardEnabled = true,
                modifier = Modifier.size(400.dp, 600.dp),
            )
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `a held press does start a selection`() {
        ShadowLog.clear()
        renderTerminal()

        composeTestRule.onRoot().performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(2_000)
        composeTestRule.waitForIdle()

        assertTrue(
            "positive control failed: a 2s hold produced no selection, so this " +
                "harness cannot detect the bug and the test below is vacuous. " +
                "Lines seen: ${ShadowLog.getLogsForTag("HavenGesture").map { it.msg }}",
            selectionLines().isNotEmpty(),
        )
    }

    @Test
    fun `a cancelled gesture leaves no long-press armed`() {
        ShadowLog.clear()
        renderTerminal()

        // Finger down, then the pointer stream is cancelled out from under the
        // gesture — the accessibility case, in miniature.
        composeTestRule.onRoot().performTouchInput { down(center) }
        composeTestRule.onRoot().performTouchInput { cancel() }
        composeTestRule.mainClock.advanceTimeBy(2_000)
        composeTestRule.waitForIdle()

        assertTrue(
            "a cancelled gesture still started a selection: " + selectionLines() +
                " — the long-press timer outlived the gesture (#435). All lines: " +
                ShadowLog.getLogsForTag("HavenGesture").map { it.msg },
            selectionLines().isEmpty(),
        )
    }
}
