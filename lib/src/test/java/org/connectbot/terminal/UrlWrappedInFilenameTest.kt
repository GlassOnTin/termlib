/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.connectbot.terminal

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A URL hard-wrapped inside a filename, with prose following it.
 *
 * Reported from a Claude Code session in Haven: the tool wraps its own output
 * at a width of its choosing, which need not be the terminal's, so the row
 * carrying the URL is neither full nor soft-wrapped and the continuation
 * carries no `/#?&=` to mark it. Both continuation paths refused it and the
 * URL was truncated at the break.
 */
class UrlWrappedInFilenameTest {

    private val rows = arrayOf(
        "Confirmed — CI green and the tokamak equilibrium page is live",
        "at https://glassontin.github.io/sufficit/cases-recorded/gs-e",
        "quilibrium.html, flux surfaces and all. The recorded-run",
        "mechanics worked as designed: CI regenerated the twelve",
    )

    private val expected = "https://glassontin.github.io/sufficit/cases-recorded/gs-equilibrium.html"

    @Test
    fun `the break is joined however much wider the terminal is than the wrap`() {
        // The row carrying the URL is 60 columns. At 60 it fills the terminal
        // and the old margin rule already covered it; every wider terminal is
        // the reported bug, and the width of the mismatch is not knowable from
        // the screen, so none of them may depend on it.
        for (cols in listOf(60, 61, 62, 70, 100)) {
            val state = screenState(cols, *rows)
            assertEquals(
                "cols=$cols",
                expected,
                state.getHyperlinkUrlAt(1, 10, autoDetectUrls = true),
            )
        }
    }

    @Test
    fun `the tail row resolves to the same url as the row above`() {
        val state = screenState(70, *rows)
        assertEquals(expected, state.getHyperlinkUrlAt(2, 3, autoDetectUrls = true))
    }

    @Test
    fun `prose after the tail is not swallowed`() {
        val state = screenState(70, *rows)
        // "flux" begins at column 17 of the tail row, past the filename.
        assertNull(state.getHyperlinkUrlAt(2, 20, autoDetectUrls = true))
        assertNull(state.getHyperlinkUrlAt(3, 5, autoDetectUrls = true))
    }

    @Test
    fun `a sentence following a url is still not a continuation`() {
        // The guard this relaxes: without it, any row after a URL joins on.
        val state = screenState(
            80,
            "see https://example.com/path",
            "  i think this is prose",
        )
        assertEquals("https://example.com/path", state.getHyperlinkUrlAt(0, 10, autoDetectUrls = true))
        assertNull(state.getHyperlinkUrlAt(1, 5, autoDetectUrls = true))
    }

    @Test
    fun `a url with prose after it on its own row does not reach across`() {
        // The previous row must have been cut off — a URL that ends mid-row
        // ended because it ended, and whatever is below is a separate line.
        val state = screenState(
            80,
            "see https://example.com/path and then some words",
            "notes.txt is where it went",
        )
        assertEquals("https://example.com/path", state.getHyperlinkUrlAt(0, 10, autoDetectUrls = true))
    }

    @Test
    fun `extension shapes accepted and rejected`() {
        listOf("quilibrium.html,", "index.php", "notes.md", "a.html").forEach {
            assertTrue(it, looksLikeWrappedFileTail(it))
        }
        listOf(
            "i",                 // a word
            "bar",               // a word
            "1.2.3",             // version number — one digit after the dot
            "prose.",            // sentence end, nothing after the dot
            ".html",             // no name before the dot
            "release.20260802",  // digits, and too long
            "file.a",            // single-letter extension
        ).forEach {
            assertFalse(it, looksLikeWrappedFileTail(it))
        }
    }

    private fun screenState(cols: Int, vararg lineTexts: String): TerminalScreenState {
        val lines = lineTexts.mapIndexed { index, text -> lineOf(index, text, cols) }
        val snapshot = TerminalSnapshot(
            lines = lines,
            scrollback = emptyList(),
            cursorRow = 0,
            cursorCol = 0,
            cursorVisible = true,
            cursorBlink = true,
            cursorShape = CursorShape.BLOCK,
            terminalTitle = "",
            rows = lines.size,
            cols = cols,
            timestamp = 0L,
            sequenceNumber = 1L,
        )
        return TerminalScreenState(snapshot)
    }

    private fun lineOf(row: Int, text: String, cols: Int): TerminalLine = TerminalLine(
        row = row,
        cells = cells(text, cols),
        semanticSegments = emptyList(),
        softWrapped = text.length >= cols,
    )

    private fun cells(text: String, cols: Int): List<TerminalLine.Cell> =
        text.padEnd(cols).take(cols).map { char ->
            TerminalLine.Cell(char = char, fgColor = Color.White, bgColor = Color.Black)
        }
}
