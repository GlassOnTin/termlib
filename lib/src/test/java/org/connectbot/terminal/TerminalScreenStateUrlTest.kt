/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.connectbot.terminal

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [TerminalScreenState.getHyperlinkUrlAt], the multi-line URL
 * resolver used for tap-to-open hyperlinks on the terminal canvas.
 *
 * Regression coverage for the "whitespace gobbling" bug reported by
 * the Haven maintainer: the pre-fix implementation stripped every
 * whitespace run between URL-safe characters in the joined raw text,
 * without any notion of row boundaries. That meant a URL followed by
 * real English prose on the **same row** would have the intervening
 * spaces collapsed, and `URL_REGEX` would then greedily match all the
 * prose characters onto the URL. Example:
 *
 *     "see https://github.com/.../issues/78.  i think the is about..."
 *
 * became:
 *
 *     "https://github.com/.../issues/78.ithinktheisabout..."
 *
 * The fix keeps within-row whitespace intact and only joins rows that
 * are genuine URL continuations (previous row ends URL-safe, next row
 * starts URL-safe *at column 0* — no leading indentation allowed).
 */
class TerminalScreenStateUrlTest {

    private fun cells(text: String, cols: Int): List<TerminalLine.Cell> {
        // Pad the text to the full column width, as a real terminal does.
        val padded = text.padEnd(cols)
        return padded.map { ch ->
            TerminalLine.Cell(char = ch, fgColor = Color.White, bgColor = Color.Black)
        }
    }

    private fun lineOf(row: Int, text: String, cols: Int, softWrapped: Boolean = false): TerminalLine =
        TerminalLine(row = row, cells = cells(text, cols), softWrapped = softWrapped)

    private fun screenState(cols: Int, vararg lineTexts: String): TerminalScreenState {
        val lines = lineTexts.mapIndexed { i, t -> lineOf(i, t, cols) }
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
            sequenceNumber = 0L,
        )
        return TerminalScreenState(snapshot)
    }

    // --- Single-line / no-wrap cases (baseline) ---

    @Test
    fun `single url mid-line returns exactly the url`() {
        val state = screenState(80, "go to https://example.com/path for info")
        val url = state.getHyperlinkUrlAt(row = 0, col = 10) // inside "https://..."
        assertEquals("https://example.com/path", url)
    }

    @Test
    fun `tap outside any url returns null`() {
        val state = screenState(80, "go to https://example.com/path for info")
        assertNull(state.getHyperlinkUrlAt(row = 0, col = 0))
        assertNull(state.getHyperlinkUrlAt(row = 0, col = 35))
    }

    // --- REGRESSION: bug from #78 review ---

    /**
     * URL followed by real prose on the SAME row. Previously the
     * whitespace between the period and the next word would be
     * collapsed (because both sides are URL-safe), and the regex
     * would match the prose onto the URL.
     */
    @Test
    fun `url followed by prose on same row does not gobble prose`() {
        val state = screenState(
            120,
            "https://github.com/GlassOnTin/Haven/issues/78.  i think the is about the Connections screen tab button",
        )
        val url = state.getHyperlinkUrlAt(row = 0, col = 20)
        assertEquals(
            "https://github.com/GlassOnTin/Haven/issues/78.",
            url,
        )
    }

    /**
     * The same bug, but with no trailing period — a bare URL followed
     * by a space and an alphabetic word. Pre-fix the regex match would
     * have been "https://example.com/pathsomemoretext".
     */
    @Test
    fun `bare url followed by single space and word does not gobble word`() {
        val state = screenState(80, "see https://example.com/path somemoretext here")
        val url = state.getHyperlinkUrlAt(row = 0, col = 10)
        assertEquals("https://example.com/path", url)
    }

    // --- Multi-line URL continuation (the feature that must still work) ---

    /**
     * URL wraps exactly at the terminal column width. The continuation
     * line starts at column 0 with URL-safe characters (no leading
     * whitespace). This is the case the multi-line resolver exists for.
     */
    @Test
    fun `url wrapped at column boundary is joined across rows`() {
        // 40-col terminal. URL starts at col 0 of row 0, wraps at col 40.
        // Row 0 exactly 40 chars, row 1 starts with the tail of the URL.
        val row0 = "https://example.com/very/long/path/xxxx" // 40 chars, ends at col 40
        val row1 = "yyyy/zzzz"
        val state = screenState(40, row0, row1)

        val url = state.getHyperlinkUrlAt(row = 0, col = 10)
        assertEquals("https://example.com/very/long/path/xxxxyyyy/zzzz", url)

        // Tapping the continuation line should also resolve to the same URL.
        val urlFromCont = state.getHyperlinkUrlAt(row = 1, col = 2)
        assertEquals("https://example.com/very/long/path/xxxxyyyy/zzzz", urlFromCont)
    }

    /**
     * A URL that ends on row 0 followed by a new line of prose starting
     * with leading indentation (like a `  i think...` bullet) must NOT
     * be treated as a continuation, even though the row 0 ends URL-safe
     * and the first non-space on row 1 is URL-safe. The disambiguator is
     * that a real wrapped URL continuation would have its next character
     * at column 0, not after leading whitespace.
     */
    @Test
    fun `indented prose on next row is not a url continuation`() {
        val state = screenState(
            60,
            "see https://example.com/path",
            "  i think the is about stuff",
        )
        val url = state.getHyperlinkUrlAt(row = 0, col = 10)
        assertEquals("https://example.com/path", url)
        // Tapping the second row's prose must not claim it's a URL.
        assertNull(state.getHyperlinkUrlAt(row = 1, col = 6))
    }

    /**
     * URL wraps across more than one row. All continuation rows must
     * start at column 0 with URL-safe characters.
     */
    @Test
    fun `url wrapped across three rows is joined`() {
        val cols = 20
        val row0 = "https://example.com/" // 20 chars exactly
        val row1 = "a/b/c/d/e/f/g/h/i/j/" // 20 chars exactly
        val row2 = "k/l/m/"                // continuation, not padded yet
        val state = screenState(cols, row0, row1, row2)

        val url = state.getHyperlinkUrlAt(row = 1, col = 5)
        assertEquals("https://example.com/a/b/c/d/e/f/g/h/i/j/k/l/m/", url)
    }

    /**
     * Continuation chain broken by a blank line: the URL on row 0 must
     * not be joined to content after the blank line.
     */
    @Test
    fun `blank row breaks continuation chain`() {
        val state = screenState(
            40,
            "https://example.com/something/that/is/lo",
            "",
            "ng/should/not/be/joined",
        )
        val url = state.getHyperlinkUrlAt(row = 0, col = 10)
        // Only row 0's content — regex gives whatever it can match there.
        assertEquals("https://example.com/something/that/is/lo", url)
    }

    // --- REGRESSION: bug reported when Claude Code output wrapped a URL
    //     at the Haven terminal width.  The URL started mid-row after a
    //     prose prefix and wrapped to the next row with NO leading
    //     whitespace on the continuation row — a plain forced wrap.  The
    //     old single-line fast path returned the row 0 partial because
    //     the URL match did not touch either "edge" of the trimmed line
    //     (there were a few unused trailing cells where the wrap
    //     happened), which was interpreted as "URL is truly interior,
    //     no need to walk neighbours".  In reality, "followed only by
    //     whitespace padding" is not the same as "followed by prose" —
    //     the row *might* be part of a wrap.  Skip the fast path in that
    //     ambiguous case so the continuation walker gets a chance to
    //     look at the next row.

    @Test
    fun `url wrapped after trailing unused cells joins to next row`() {
        // 80-col terminal, matches a realistic Haven-on-phone width.
        // Prefix "…Issue #92 reply posted — " pushes the URL to col 27.
        // The URL is 72 chars.  27+72 = 99, so the URL cannot fit on
        // row 0 — it has to wrap.  The CLI tool that produced the
        // output hard-wrapped at the 80-col boundary leaving a few
        // trailing blanks on row 0 before the continuation.
        val prefix = "- Issue #92 reply posted — "
        val url = "https://github.com/GlassOnTin/Haven/issues/92#issuecomment-4253770624"
        // cols=80 → row 0 fits prefix + first 53 chars of URL
        //         → row 1 starts at col 0 with the remaining 19 chars
        val row0 = prefix + url.substring(0, 80 - prefix.length) // 80 chars exactly
        val row1 = url.substring(80 - prefix.length)             // tail of URL
        val state = screenState(80, row0, row1)

        // Tap anywhere inside the URL on row 0.
        val clickCol = prefix.length + 10 // inside "github.com/Glass…"
        assertEquals(url, state.getHyperlinkUrlAt(row = 0, col = clickCol))
        // Tapping the continuation row resolves to the full URL too.
        assertEquals(url, state.getHyperlinkUrlAt(row = 1, col = 4))
    }

    @Test
    fun `url interrupted by trailing blanks only, not by prose, is not truncated`() {
        // Degenerate: 80-col terminal, URL ends before row edge, nothing
        // after it on the row, and no continuation on the next row.
        // The walker should return the complete URL on row 0, not a
        // truncated prefix.  This used to go through the fast path and
        // return the URL correctly — the risk of the fix is we break
        // this case, so cover it explicitly.
        val state = screenState(
            80,
            "prefix https://example.com/path", // ends at col 31, blanks after
            "next line of unrelated prose",
        )
        assertEquals(
            "https://example.com/path",
            state.getHyperlinkUrlAt(row = 0, col = 12),
        )
    }
}
