/*
 * ConnectBot Terminal
 * Copyright 2025 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.connectbot.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [keyboardCoveredShiftPx] — the upward render shift that keeps
 * the bottom-most content visible above the soft keyboard on the primary
 * buffer (issue #206 + the tmux/Claude-Code status-line crop follow-up).
 *
 * Geometry used throughout: charHeight = 20px. A 1060px keyboard-hidden
 * viewport holds 53 rows; with the keyboard up the viewport is 960px and
 * `covered` = 1060 - 960 = 100px (5 rows).
 */
class KeyboardCoveredShiftTest {

    private val ch = 20f

    private fun shift(
        covered: Float,
        altScreen: Boolean = false,
        scrollbackPosition: Int = 0,
        cursorRow: Int,
        lastContentRow: Int,
        availableHeight: Int = 960,
    ) = keyboardCoveredShiftPx(
        covered = covered,
        altScreen = altScreen,
        scrollbackPosition = scrollbackPosition,
        cursorRow = cursorRow,
        lastContentRow = lastContentRow,
        charHeight = ch,
        availableHeight = availableHeight,
    )

    @Test
    fun keyboardHidden_noShift() {
        assertEquals(0f, shift(covered = 0f, cursorRow = 48, lastContentRow = 52), 0f)
    }

    @Test
    fun alternateBuffer_noShift() {
        // Alt buffer reflows the PTY to the live height, so nothing to shift
        // even with the keyboard up.
        assertEquals(0f, shift(covered = 100f, altScreen = true, cursorRow = 48, lastContentRow = 52), 0f)
    }

    @Test
    fun scrolledIntoHistory_showsBottomOfWindow() {
        assertEquals(100f, shift(covered = 100f, scrollbackPosition = 5, cursorRow = 48, lastContentRow = 52), 0f)
    }

    @Test
    fun bottomShellPrompt_cursorAtBottom_shiftsFullCovered() {
        // Plain shell: cursor and last content are the same bottom row (49 of a
        // 50-row grid). Shift brings that row flush above the keyboard.
        assertEquals(
            100f,
            shift(covered = 100f, cursorRow = 49, lastContentRow = 49, availableHeight = 900),
            0f,
        )
    }

    @Test
    fun tmuxStatusLineBelowCursor_shiftsToShowStatusLine() {
        // The reported bug: Claude Code / a REPL inside tmux on the PRIMARY
        // buffer — cursor in the input box (row 48) but content (status line)
        // runs to row 52. Anchoring on the cursor alone shifted only 20px
        // (1 row), clipping rows 49-52. Anchoring on the last content row
        // shifts the full 100px so the status line sits above the keyboard.
        assertEquals(100f, shift(covered = 100f, cursorRow = 48, lastContentRow = 52), 0f)
    }

    @Test
    fun cursorAnchorAloneWouldUnderShift_regression() {
        // Documents the pre-fix value for the same inputs: the old cursor-only
        // anchor produced (48+1)*20 - 960 = 20px, leaving the bottom clipped.
        val cursorOnly = ((48 + 1) * ch) - 960
        assertEquals(20f, cursorOnly, 0f)
        // The fix produces a larger shift for the identical geometry.
        assertEquals(100f, shift(covered = 100f, cursorRow = 48, lastContentRow = 52), 0f)
    }

    @Test
    fun highCursorWithBottomContent_clampedSoCursorStaysOnScreen() {
        // Pathological: cursor near the top (row 2) but content to row 52.
        // Shifting the full 100px would scroll the cursor's row off the top
        // (2*20 - 100 = -60). The clamp limits the shift to cursorRow*charHeight
        // = 40px so the cursor's top edge lands exactly at the viewport top.
        assertEquals(40f, shift(covered = 100f, cursorRow = 2, lastContentRow = 52), 0f)
    }

    @Test
    fun cursorAtRowZero_neverShifts() {
        // Can't shift a row-0 cursor up without losing it entirely.
        assertEquals(0f, shift(covered = 100f, cursorRow = 0, lastContentRow = 52), 0f)
    }

    @Test
    fun contentAboveCursor_anchorsOnCursor() {
        // lastContentRow above the cursor (e.g. trailing blank rows) → anchor on
        // the cursor, same as the classic shell case.
        assertEquals(
            100f,
            shift(covered = 100f, cursorRow = 49, lastContentRow = 30, availableHeight = 900),
            0f,
        )
    }

    @Test
    fun cursorWellAboveFold_noShiftNeeded() {
        // Short content, cursor high in a tall viewport: desired shift is
        // negative, coerced to 0 — the cursor is already visible.
        assertEquals(0f, shift(covered = 100f, cursorRow = 10, lastContentRow = 10), 0f)
    }
}
