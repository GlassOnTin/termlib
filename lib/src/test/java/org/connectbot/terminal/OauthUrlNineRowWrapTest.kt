/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.connectbot.terminal

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A Claude Code OAuth URL as it actually appeared in a 54-column Haven tab:
 * hard-wrapped by the emitting CLI across **nine** rows, none marked
 * softWrapped. Reported from the device — tapping it opened an incomplete URL.
 *
 * Nine rows matters. [UrlBlobDetector] finds its anchor (the row carrying the
 * scheme) by walking UP from the tap, and that walk is capped, so a tap far
 * enough below the `https://` row cannot see it. Every row of a wrapped URL is
 * equally tappable to a user, so a cap on the upward walk is a cap on where you
 * are allowed to tap — which is not a distinction anyone makes when aiming at a
 * link.
 */
class OauthUrlNineRowWrapTest {

    private val cols = 54

    private val rows = listOf(
        "https://claude.com/cai/oauth/authorize?code=true&clien",
        "t_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e&response_typ",
        "e=code&redirect_uri=https%3A%2F%2Fplatform.claude.com%",
        "2Foauth%2Fcode%2Fcallback&scope=org%3Acreate_api_key+u",
        "ser%3Aprofile+user%3Ainference+user%3Asessions%3Aclaud",
        "e_code+user%3Amcp_servers+user%3Afile_upload&code_chal",
        "lenge=TVz0xSGkvQA3ysgwkLVqmyAmrHKoWG7636kwn5HRlKw&code",
        "_challenge_method=S256&state=QE_KuxEbS0q-8vqCiat221D7i",
        "9Qt9KIPJNk34RB7NyQ",
    )

    private val fullUrl = rows.joinToString("").trim()

    private fun state(): TerminalScreenState {
        val lines = rows.mapIndexed { i, text ->
            TerminalLine(
                row = i,
                cells = text.padEnd(cols).map { ch ->
                    TerminalLine.Cell(char = ch, fgColor = Color.White, bgColor = Color.Black)
                },
                softWrapped = false,
            )
        }
        return TerminalScreenState(
            TerminalSnapshot(
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
            ),
        )
    }

    @Test
    fun `tapping the scheme row yields the whole url`() {
        val url = state().getHyperlinkUrlAt(row = 0, col = 10, autoDetectUrls = true)
        assertEquals(fullUrl, url)
    }

    /**
     * The report: a tap on any row of the wrapped URL must open the same URL.
     * Reported per-row so a failure names exactly which taps are broken rather
     * than just "not equal".
     */
    @Test
    fun `tapping any row of the wrapped url yields the whole url`() {
        val s = state()
        val broken = rows.indices.mapNotNull { row ->
            val got = s.getHyperlinkUrlAt(row = row, col = 5, autoDetectUrls = true)
            if (got == fullUrl) {
                null
            } else {
                "row $row -> " + (got?.let { "${it.length} of ${fullUrl.length} chars" } ?: "null")
            }
        }
        assertEquals("rows whose tap did not resolve the full URL: $broken", emptyList<String>(), broken)
    }
}
