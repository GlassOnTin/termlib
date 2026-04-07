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
     * Get the hyperlink URL at a visible row/col, handling URLs that span
     * multiple lines. Joins a small window of lines around the tap point,
     * strips whitespace between URL-safe chars (wrap artifacts from CLI tools),
     * and runs one regex match.
     */
    fun getHyperlinkUrlAt(row: Int, col: Int): String? {
        val line = getVisibleLine(row)

        // OSC 8 segments always take priority
        val osc8 = line.semanticSegments.firstOrNull {
            it.semanticType == SemanticType.HYPERLINK && it.contains(col)
        }?.metadata
        if (osc8 != null) return osc8

        // Try single-line detection first (fast path, cached per line)
        val singleHit = line.autoDetectedUrls.firstOrNull { col >= it.first && col < it.second }
        if (singleHit != null) {
            // If the match doesn't touch a line edge, it's self-contained
            val trimmedLen = line.text.trimEnd().length
            if (singleHit.second < trimmedLen && singleHit.first > 0) return singleHit.third
        }

        // Join a window of ±6 lines, strip inter-URL whitespace, single regex pass
        val window = 6
        val startRow = (row - window).coerceAtLeast(0)
        val endRow = (row + window).coerceAtMost(snapshot.rows - 1)

        val raw = StringBuilder()
        var tapOffset = 0
        for (r in startRow..endRow) {
            if (r == row) tapOffset = raw.length
            raw.append(getVisibleLine(r).text)
        }

        // Collapse whitespace runs between URL-safe chars (one pass).
        // Lines are padded to terminal width, so between joined lines
        // there can be 30+ trailing spaces — strip the entire run.
        val cleaned = StringBuilder(raw.length)
        val rawToClean = IntArray(raw.length)
        var i = 0
        while (i < raw.length) {
            rawToClean[i] = cleaned.length
            val ch = raw[i]
            if ((ch == ' ' || ch == '\t') && i > 0 && raw[i - 1].isUrlSafe()) {
                val wsStart = i
                while (i < raw.length && (raw[i] == ' ' || raw[i] == '\t')) {
                    rawToClean[i] = cleaned.length
                    i++
                }
                if (i < raw.length && raw[i].isUrlSafe()) continue
                for (j in wsStart until i) cleaned.append(raw[j])
                continue
            }
            cleaned.append(ch)
            i++
        }
        val cleanedCol = if (tapOffset + col in rawToClean.indices)
            rawToClean[tapOffset + col] else tapOffset + col

        return TerminalLine.URL_REGEX.findAll(cleaned).firstOrNull { m ->
            cleanedCol >= m.range.first && cleanedCol <= m.range.last
        }?.value ?: singleHit?.third
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
        snapshot = newSnapshot
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
