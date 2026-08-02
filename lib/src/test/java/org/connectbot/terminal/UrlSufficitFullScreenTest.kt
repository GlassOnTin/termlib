package org.connectbot.terminal

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reporter's entire 63x50 screen for #491, read back off the device over
 * MCP rather than reconstructed from a paste.
 *
 * Worth keeping as real data: the two-space hanging indent, the trailing
 * space, and the 49 other rows the blob detector scans for structural columns
 * are all things a hand-written fixture had wrong.
 */
class UrlSufficitFullScreenTest {

    private val cols = 63

    private val screen = listOf(
        "❯ Did you spot anything worth submitting upstream?             ",
        "  page is live.                                                ",
        "                                                               ",
        "✻ Crunched for 39s · 2 shells still running                    ",
        "                                                               ",
        "● Background command \"Watch current CI run, verify recorded    ",
        "page live\" completed (exit code 0)                             ",
        "                                                               ",
        "  Read 1 file                                                  ",
        "                                                               ",
        "● Confirmed — CI green and the tokamak equilibrium page is live",
        "  at https://glassontin.github.io/sufficit/cases-recorded/gs-e ",
        "  quilibrium.html, flux surfaces and all. The recorded-run     ",
        "  mechanics worked as designed: CI regenerated the twelve      ",
        "  regular case pages, skipped the dolfinx generator gracefully,",
        "  and served the committed page as-is. Thirteen cases on the   ",
        "  site, and the FEniCSx bridge — with the project's first      ",
        "  guaranteed-not-estimated PDE certificate — is fully landed.  ",
        "                                                               ",
        "✻ Churned for 22s · 2 shells still running                     ",
        "                                                               ",
        "❯ could we add th Jump to bottom (ctrl+End) ↓                  ",
        "                                                               ",
        "───────────────────────────── Review Sufficit project vision ──",
        "❯\u00a0                                                             ",
        "───────────────────────────────────────────────────────────────",
        "  [PONYTAIL]                                                   ",
        "  ⏵⏵ bypass permissions on · ← 1 agent · 2 shells              ",
        "  ⧉  sufficit-explainer                                        ",
        "[sufficit]0:claude*     \"✳ Review Sufficit pro\" 17:41 02-Aug-26",
        "  takes weeks and permanent maintenance, and upstream has      ",
        "  active opinions about it. I'd want your explicit go-ahead    ",
        "  before we walked into that.                                  ",
        "                                                               ",
        "  But the thing we actually want — CI able to run the GS tests ",
        "  and regenerate the tokamak page like every other case —      ",
        "  doesn't need wheels at all. Your own box runs the Debian     ",
        "  python3-dolfinx package; GitHub's Ubuntu runners can install ",
        "  the same stack from the FEniCS PPA. That converts the        ",
        "  recorded-run page into a first-class CI-regenerated one      ",
        "  today. Implementing that now, in two steps so each is        ",
        "  verified — firs Jump to bottom (ctrl+End) ↓                  ",
        "                                                               ",
        "───────────────────────────── Review Sufficit project vision ──",
        "❯\u00a0                                                             ",
        "───────────────────────────────────────────────────────────────",
        "  [PONYTAIL]                                                   ",
        "  ⏵⏵ bypass permissions on · ← 1 agent · 2 shells              ",
        "  ⧉  sufficit-explainer                                        ",
        "[sufficit]0:claude*     \"✳ Review Sufficit pro\" 17:40 02-Aug-26",
    )

    @Test
    fun `the wrapped url resolves in full from either row`() {
        val state = screenState(cols, screen)
        val urlRow = screen.indexOfFirst { it.contains("https://glassontin") }
        val row = screen[urlRow]
        val start = row.indexOf("https://")
        val lastNonBlank = row.indexOfLast { !it.isWhitespace() }
        // The URL runs to the last printed column bar one, so the tail could
        // not have fitted — this is a wrap.
        assertEquals(1, cols - (lastNonBlank + 1))
        listOf(start + 2, start + 20, lastNonBlank - 2, lastNonBlank).forEach { c ->
            assertEquals(
                "row=$urlRow col=$c",
                EXPECTED,
                state.getHyperlinkUrlAt(urlRow, c, autoDetectUrls = true),
            )
        }
        assertEquals(EXPECTED, state.getHyperlinkUrlAt(urlRow + 1, 4, autoDetectUrls = true))
    }

    private companion object {
        const val EXPECTED =
            "https://glassontin.github.io/sufficit/cases-recorded/gs-equilibrium.html"
    }

    private fun screenState(cols: Int, lineTexts: List<String>): TerminalScreenState {
        val lines = lineTexts.mapIndexed { index, text -> lineOf(index, text, cols) }
        val snapshot = TerminalSnapshot(
            lines = lines, scrollback = emptyList(), cursorRow = 0, cursorCol = 0,
            cursorVisible = true, cursorBlink = true, cursorShape = CursorShape.BLOCK,
            terminalTitle = "", rows = lines.size, cols = cols, timestamp = 0L, sequenceNumber = 1L,
        )
        return TerminalScreenState(snapshot)
    }

    private fun lineOf(row: Int, text: String, cols: Int): TerminalLine = TerminalLine(
        row = row, cells = cells(text, cols), semanticSegments = emptyList(), softWrapped = false,
    )

    private fun cells(text: String, cols: Int): List<TerminalLine.Cell> =
        text.padEnd(cols).take(cols).map { char ->
            TerminalLine.Cell(char = char, fgColor = Color.White, bgColor = Color.Black)
        }
}
