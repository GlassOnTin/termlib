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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Compose-specific state adapter for terminal screen rendering.
 *
 * This class bridges the gap between the Service-layer TerminalEmulator (which
 * emits immutable snapshots via StateFlow) and the Compose UI layer. It manages
 * UI-only state such as scroll position while observing terminal state changes.
 *
 * Separation of concerns:
 * - Terminal state (lines, cursor, etc.): Owned by TerminalEmulator
 * - UI state (scroll position, zoom, etc.): Owned by TerminalScreenState
 * - Selection state: Owned by SelectionManager
 *
 * @property snapshot The current immutable terminal snapshot
 */
@Stable
internal class TerminalScreenState(
    initialSnapshot: TerminalSnapshot
) {
    /**
     * The current immutable terminal snapshot.
     * Updated via updateSnapshot() to preserve scroll position across snapshot changes.
     */
    var snapshot by mutableStateOf(initialSnapshot)
        private set

    /**
     * Current scroll position in the scrollback buffer.
     * 0 = bottom (current screen), >0 = scrolled back in history
     */
    var scrollbackPosition by mutableStateOf(0)
        private set

    /**
     * Total number of lines (scrollback + visible screen).
     */
    val totalLines: Int get() = snapshot.scrollback.size + snapshot.rows

    /**
     * Get a line at the specified index, accounting for scrollback.
     *
     * @param index Line index (0 = oldest scrollback, totalLines-1 = last visible line)
     * @return The terminal line at the specified index
     */
    fun getLine(index: Int): TerminalLine {
        return if (index < snapshot.scrollback.size) {
            snapshot.scrollback[index]
        } else {
            val screenIndex = index - snapshot.scrollback.size
            if (screenIndex in snapshot.lines.indices) {
                snapshot.lines[screenIndex]
            } else {
                // Return empty line if index out of bounds
                TerminalLine.empty(
                    row = screenIndex,
                    cols = snapshot.cols,
                    defaultFg = androidx.compose.ui.graphics.Color.White,
                    defaultBg = androidx.compose.ui.graphics.Color.Black
                )
            }
        }
    }

    /**
     * Get the visible line at the specified row, accounting for scroll position.
     *
     * @param row Row in the visible viewport (0-based)
     * @return The terminal line to display at this row
     */
    fun getVisibleLine(row: Int): TerminalLine {
        if (scrollbackPosition > 0) {
            // Calculate actual row in scrollback/screen
            val actualIndex = snapshot.scrollback.size - scrollbackPosition + row
            return getLine(actualIndex.coerceIn(0, totalLines - 1))
        }
        // Not scrolled - show current screen
        return if (row in snapshot.lines.indices) {
            snapshot.lines[row]
        } else {
            TerminalLine.empty(
                row = row,
                cols = snapshot.cols,
                defaultFg = androidx.compose.ui.graphics.Color.White,
                defaultBg = androidx.compose.ui.graphics.Color.Black
            )
        }
    }

    /**
     * Get the hyperlink URL at a visible row/col, handling URLs that wrap
     * across terminal-width boundaries.
     *
     * A multi-line URL is reconstructed by walking outward from [row] only
     * through rows that are *genuine* URL continuations of their neighbour:
     * the previous row ends with a URL-safe character (after stripping the
     * terminal-width padding) AND the next row begins with a URL-safe
     * character **at column 0** — no leading whitespace allowed. That last
     * constraint is what distinguishes a wrapped URL ("…/issu" + "es/78")
     * from a URL followed by a new line of indented prose ("…/issues/78"
     * + "  i think…"): a real wrap has no room for leading indentation.
     *
     * Within a single row, whitespace is always preserved, so prose after
     * a URL on the same row is not merged into the URL by the regex.
     *
     * Fixes the "ithinktheis..." whitespace-gobbling regression reported
     * during #78 review.
     */
    fun getHyperlinkUrlAt(row: Int, col: Int): String? {
        val line = getVisibleLine(row)

        // OSC 8 segments always take priority
        val osc8 = line.semanticSegments.firstOrNull {
            it.semanticType == SemanticType.HYPERLINK && it.contains(col)
        }?.metadata
        if (osc8 != null) return osc8

        // Single-line fast path: if a regex match doesn't touch either edge
        // of the trimmed line text, no continuation logic is needed.
        val singleHit = line.autoDetectedUrls.firstOrNull { col >= it.first && col < it.second }
        if (singleHit != null) {
            val trimmedLen = line.text.trimEnd().length
            if (singleHit.second < trimmedLen && singleHit.first > 0) return singleHit.third
        }

        // Walk outward from `row` to find the tight bounds of the URL
        // continuation group. A row `r+1` is a continuation of row `r` iff:
        //   - row r's trimEnd last char is URL-safe AND
        //   - row r+1's FIRST char (at col 0, no trimStart) is URL-safe.
        // The "no trimStart" rule is what blocks indented prose from being
        // glued onto a URL ending on the previous row.
        fun isContinuation(prevRow: Int, curRow: Int): Boolean {
            if (prevRow < 0 || curRow >= snapshot.rows) return false
            val prevText = getVisibleLine(prevRow).text
            val prevTrimmed = prevText.trimEnd()
            if (prevTrimmed.isEmpty() || !prevTrimmed.last().isUrlSafe()) return false
            val curText = getVisibleLine(curRow).text
            if (curText.isEmpty()) return false
            val firstCh = curText[0]
            // firstCh == ' ' or '\t' would fail isUrlSafe() too, but be
            // explicit — leading whitespace on the continuation row is the
            // key disambiguator and any indentation kills the continuation.
            if (firstCh == ' ' || firstCh == '\t') return false
            return firstCh.isUrlSafe()
        }

        var startRow = row
        while (startRow > 0 && isContinuation(startRow - 1, startRow)) startRow--
        var endRow = row
        while (endRow < snapshot.rows - 1 && isContinuation(endRow, endRow + 1)) endRow++

        // Build the joined text. Within each row, we trim only the trailing
        // terminal-width padding — the internal whitespace of the row is
        // preserved so prose word separators remain intact. Rows in the
        // continuation group are concatenated with no separator (that IS
        // the wrap behaviour we want to reverse).
        val joined = StringBuilder()
        var tapOffsetInJoined = 0
        for (r in startRow..endRow) {
            if (r == row) tapOffsetInJoined = joined.length + col
            val rowText = getVisibleLine(r).text.trimEnd()
            joined.append(rowText)
        }

        // If the URL contains the tap point in the joined text, return it.
        // Otherwise fall back to the single-line hit (if any).
        val match = TerminalLine.URL_REGEX.findAll(joined).firstOrNull { m ->
            tapOffsetInJoined in m.range.first..m.range.last
        }
        return match?.value ?: singleHit?.third
    }

    /**
     * Scroll to the bottom (current screen).
     */
    fun scrollToBottom() {
        scrollbackPosition = 0
    }

    /**
     * Scroll to the top (oldest scrollback).
     */
    fun scrollToTop() {
        scrollbackPosition = snapshot.scrollback.size
    }

    /**
     * Scroll by a relative amount.
     *
     * @param delta Lines to scroll (positive = up/back, negative = down/forward)
     */
    fun scrollBy(delta: Int) {
        scrollbackPosition = (scrollbackPosition + delta).coerceIn(0, snapshot.scrollback.size)
    }

    /**
     * Check if currently scrolled to the bottom.
     */
    fun isAtBottom(): Boolean = scrollbackPosition == 0

    /**
     * Update the snapshot while preserving UI state (scroll position).
     * This allows the terminal content to update without resetting the scroll position.
     *
     * @param newSnapshot The new snapshot to use
     */
    internal fun updateSnapshot(newSnapshot: TerminalSnapshot) {
        val oldScrollbackSize = snapshot.scrollback.size
        val newScrollbackSize = newSnapshot.scrollback.size
        snapshot = newSnapshot

        // Adjust scroll position when scrollback size changes (e.g. terminal resize).
        // If user was at the bottom, keep them there.
        // If scrolled up, adjust by the delta so the same content stays visible.
        if (scrollbackPosition == 0) {
            // At bottom — stay at bottom (no adjustment needed)
        } else {
            val delta = newScrollbackSize - oldScrollbackSize
            scrollbackPosition = (scrollbackPosition + delta).coerceIn(0, newScrollbackSize)
        }
    }
}

/**
 * Remember a TerminalScreenState that observes the given TerminalEmulator.
 *
 * This composable function creates a TerminalScreenState that automatically
 * updates when the TerminalEmulator emits new snapshots via StateFlow.
 *
 * @param terminalEmulator The terminal emulator to observe
 * @return A TerminalScreenState that tracks the current terminal snapshot
 */
@Composable
internal fun rememberTerminalScreenState(
    terminalEmulator: TerminalEmulatorImpl
): TerminalScreenState {
    val snapshot by remember(terminalEmulator) {
        terminalEmulator.snapshot
    }.collectAsState()

    // Create state instance once per emulator, but update its snapshot when it changes
    val state = remember(terminalEmulator) {
        TerminalScreenState(snapshot)
    }

    // Update the snapshot without recreating the state object (preserves scrollbackPosition)
    state.updateSnapshot(snapshot)

    return state
}

/** True if the character commonly appears in URLs (query params, percent encoding, path). */
internal fun Char.isUrlSafe(): Boolean = isLetterOrDigit() || this in "/:@!$&'()*+,;=-._~%?#[]"
