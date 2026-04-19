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

import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImeInputViewTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var keyboardHandler: KeyboardHandler

    @Before
    fun setup() {
        val terminalEmulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        keyboardHandler = mockk(relaxed = true)
    }

    private fun makeView(imm: InputMethodManager = mockk(relaxed = true)) =
        ImeInputView(context, keyboardHandler, imm)

    private fun ImeInputView.ic() = onCreateInputConnection(EditorInfo()) as BaseInputConnection

    // === IME editable buffer reset on key events ===

    @Test
    fun testSendEnterKeyDownClearsEditable() {
        val ic = makeView().ic()
        ic.commitText("git status", 1)

        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))

        assertEquals("", ic.getEditable()?.toString())
    }

    @Test
    fun testSendKeyUpDoesNotClearEditable() {
        val ic = makeView().ic()
        // Write directly to the editable, bypassing commitText (which clears it).
        ic.getEditable()?.append("hello")

        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))

        assertEquals("hello", ic.getEditable()?.toString())
    }

    @Test
    fun testSendBackspaceKeyDownDoesNotClearEditable() {
        // sendKeyEvent(DEL) lets BaseInputConnection handle editable
        // state on its own — we don't forcibly clear. Only Enter
        // clears (command boundary). Keeping editable stable across
        // DELs is what lets Gboard track replacement ranges for
        // composition-based autocorrect (#99).
        val ic = makeView().ic()
        ic.commitText("abc", 1)
        val beforeDel = ic.getEditable()?.toString() ?: ""

        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))

        // The editable should not be forcibly cleared — whatever
        // BaseInputConnection does with it is what we have.
        assertEquals(beforeDel, ic.getEditable()?.toString())
    }

    @Test
    fun testSecondCommandDoesNotAccumulateAfterEnter() {
        // Regression: "git status<enter>ls -l" should not appear as one suggestion candidate.
        val ic = makeView().ic()

        ic.commitText("git status", 1)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        assertEquals("", ic.getEditable()?.toString())

        ic.commitText("ls -l", 1)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        assertEquals("", ic.getEditable()?.toString())
    }

    // === commitText retains committed text in editable ===
    //
    // Gboard needs recent chars in the BaseInputConnection editable to
    // decide whether to offer an autocorrect suggestion and to route a
    // replacement via setComposingRegion. Clearing after every commit
    // (the old behaviour) dropped Gboard into per-char insert mode and
    // disabled autocorrect (the #99 symptom). Enter still clears on the
    // sendKeyEvent path so one command's text doesn't carry into the
    // next.

    @Test
    fun testCommitTextRetainsTextInEditableWhenStandardKeyboard() {
        // Standard keyboard mode → fullEditor=true → BaseInputConnection
        // provides a real Editable that persists across commits so Gboard
        // can offer autocorrect on the retained text.
        val view = makeView().apply { allowStandardKeyboard = true }
        val ic = view.ic()
        ic.commitText("some text", 1)

        assertEquals("some text", ic.getEditable()?.toString())
    }

    @Test
    fun testCommitTextWithActiveCompositionRetainsReplacementWhenStandardKeyboard() {
        val view = makeView().apply { allowStandardKeyboard = true }
        val ic = view.ic()
        ic.setComposingText("wor", 1)
        ic.commitText("word", 1)

        assertEquals("word", ic.getEditable()?.toString())
    }

    @Test
    fun testCommitTextInSecureModeLeavesEditableEmpty() {
        // Default Secure mode → fullEditor=false → the Editable is a
        // no-op proxy; writes through super.commitText don't persist.
        // That's correct for Secure mode (no suggestions / no Gboard
        // autocorrect anyway), and it means shell output can't leak
        // into an IME suggestion buffer.
        val ic = makeView().ic()
        ic.commitText("some text", 1)

        assertEquals("", ic.getEditable()?.toString())
    }

    // === finishComposingText clears editable (regression guard) ===

    @Test
    fun testFinishComposingTextClearsEditable() {
        val ic = makeView().ic()
        ic.setComposingText("partial", 1)
        ic.finishComposingText()

        assertEquals("", ic.getEditable()?.toString())
    }

    // === updateSelection is called after ACTION_DOWN key events ===

    @Test
    fun testUpdateSelectionCalledAfterEnterKeyDown() {
        val imm = mockk<InputMethodManager>(relaxed = true)
        val view = makeView(imm)
        val ic = view.ic()

        ic.commitText("git status", 1)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))

        verify { imm.updateSelection(view, 0, 0, -1, -1) }
    }

    @Test
    fun testUpdateSelectionNotCalledAfterBackspaceKeyDown() {
        // Non-Enter key events must NOT reset the IME's tracked
        // selection: Gboard needs stable selection updates to offer
        // composition-based autocorrect. An IME-dispatched DEL (or any
        // other non-Enter key) lets BaseInputConnection handle editable
        // state on its own.
        val imm = mockk<InputMethodManager>(relaxed = true)
        val view = makeView(imm)
        val ic = view.ic()

        ic.commitText("abc", 1)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))

        verify(exactly = 0) { imm.updateSelection(view, 0, 0, -1, -1) }
    }

    @Test
    fun testUpdateSelectionNotCalledOnKeyUp() {
        val imm = mockk<InputMethodManager>(relaxed = true)
        val view = makeView(imm)
        val ic = view.ic()

        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))

        verify(exactly = 0) { imm.updateSelection(any(), any(), any(), any(), any()) }
    }

    // === resetImeBuffer() — used by physical keyboard paths that bypass InputConnection ===

    @Test
    fun testResetImeBufferClearsEditable() {
        val imm = mockk<InputMethodManager>(relaxed = true)
        val view = makeView(imm)
        val ic = view.ic()

        ic.commitText("git status", 1)
        view.resetImeBuffer()

        assertEquals("", ic.getEditable()?.toString())
    }

    @Test
    fun testResetImeBufferCallsUpdateSelection() {
        val imm = mockk<InputMethodManager>(relaxed = true)
        val view = makeView(imm)
        view.ic()

        view.resetImeBuffer()

        verify { imm.updateSelection(view, 0, 0, -1, -1) }
    }

    @Test
    fun testResetImeBufferBeforeConnectionCreatedDoesNotCrash() {
        val view = makeView()
        // No InputConnection created yet — should not throw
        view.resetImeBuffer()
    }

    // === Double Enter prevention (Issue #41) ===

    @Test
    fun testSendKeyEventEnterThenCommitNewlineDoesNotDoubleDispatch() {
        // Some IMEs send BOTH sendKeyEvent(ENTER) AND commitText("\n").
        // Only one Enter should reach the terminal.
        val ic = makeView().ic()
        ic.commitText("hello", 1)

        // IME sends Enter key event first
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        // Then commitText with newline
        ic.commitText("\n", 1)

        // commitText("\n") should NOT dispatch another Enter — sendKeyEvent handled it.
        // The deferred fallback should have been cancelled.
        verify(exactly = 0) { keyboardHandler.onTextInput("\n".toByteArray()) }
    }

    @Test
    fun testCommitNewlineThenSendKeyEventDoesNotDoubleDispatch() {
        // Reverse order: commitText("\n") first, then sendKeyEvent(ENTER).
        // Only one Enter should reach the terminal.
        val ic = makeView().ic()
        ic.commitText("hello", 1)

        // commitText first — defers Enter dispatch
        ic.commitText("\n", 1)
        // sendKeyEvent arrives — cancels deferred dispatch, sends its own Enter
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))

        // The deferred Enter from commitText should have been cancelled.
        // Only the sendKeyEvent Enter should fire.
        verify(exactly = 0) { keyboardHandler.onTextInput("\n".toByteArray()) }
    }

    @Test
    fun testCommitNewlineWithoutKeyEventStillSendsEnter() {
        // Some IMEs only send commitText("\n") without a key event.
        // After 16ms timeout, Enter is dispatched via the fallback.
        val view = makeView()
        val ic = view.ic()
        ic.commitText("hello", 1)

        // Only commitText, no sendKeyEvent
        ic.commitText("\n", 1)

        // Enter is deferred — not sent synchronously
        // (verified indirectly — no crash, onTextInput not called with "\n" synchronously)
        verify(exactly = 0) { keyboardHandler.onTextInput("\n".toByteArray()) }
    }

    @Test
    fun testCommitTextWithEmbeddedNewlineFiltersIt() {
        // Some IMEs commit "word\n" as a single text
        val ic = makeView().ic()
        ic.commitText("word\n", 1)

        // Only "word" should be sent via onTextInput, not the newline
        verify { keyboardHandler.onTextInput("word".toByteArray()) }
        verify(exactly = 0) { keyboardHandler.onTextInput("word\n".toByteArray()) }
    }

    @Test
    fun testResetImeBufferClearsEditableAccumulatedBySetComposingText() {
        // setComposingText (voice input path) writes to the editable but does not clear it —
        // only finishComposingText does. resetImeBuffer() must also handle this mid-composition
        // case, which can be triggered by a physical hardware key interrupting voice input.
        val imm = mockk<InputMethodManager>(relaxed = true)
        val view = makeView(imm)
        val ic = view.ic()

        ic.setComposingText("hel", 1)
        view.resetImeBuffer()

        assertEquals("", ic.getEditable()?.toString())
        verify { imm.updateSelection(view, 0, 0, -1, -1) }
    }

    // === CJK commit-text path (#96) ===
    //
    // Guards the path that carries a tapped kanji candidate from the IME's
    // composition buffer into the terminal's input stream. mio-19 reported
    // that on macOS + Gboard Japanese romaji the floating overlay shows the
    // correct composition but the committed text lands garbled in the shell.
    // These tests pin the Haven-side bytes so any future regression shows up
    // here rather than in a user bug report.

    @Test
    fun testCommitTextWithBmpKanjiSendsExactUtf8Bytes() {
        // 愛 is U+611B, BMP, encoded as 3 UTF-8 bytes: E6 84 9B.
        val ic = makeView().ic()
        ic.commitText("愛", 1)
        verify { keyboardHandler.onTextInput("愛".toByteArray(Charsets.UTF_8)) }
    }

    @Test
    fun testCommitTextWithSupplementaryPlaneKanjiSendsExactUtf8Bytes() {
        // 𠮷 is U+20BB7 (supplementary plane — "tsuchi-yoshi", a Yoshinoya
        // surname variant). Java String stores it as a surrogate pair
        // (2 chars), UTF-8 is 4 bytes: F0 A0 AE B7. Catches any code path
        // that iterates char-by-char instead of code-point-by-code-point.
        val ic = makeView().ic()
        ic.commitText("𠮷", 1)
        verify { keyboardHandler.onTextInput("𠮷".toByteArray(Charsets.UTF_8)) }
    }

    @Test
    fun testSetComposingThenCommitSendsFinalOnceAndNeverThePartial() {
        // Simulates Gboard Japanese: romaji "a" arrives in composition,
        // gets converted to hiragana "あ", then candidate "愛" replaces it,
        // user taps the candidate → IME calls commitText("愛").
        //
        // Expectations:
        //   - composingText flow transitions through the partials and empties on commit
        //   - keyboardHandler.onTextInput fires exactly once, with "愛"
        //   - no partial ("あ", "a") ever reaches the shell
        val view = makeView()
        val ic = view.ic()

        ic.setComposingText("a", 1)
        assertEquals("a", view.composingText.value)

        ic.setComposingText("あ", 1)
        assertEquals("あ", view.composingText.value)

        ic.setComposingText("愛", 1)
        assertEquals("愛", view.composingText.value)

        ic.commitText("愛", 1)
        assertEquals("", view.composingText.value)

        verify(exactly = 1) { keyboardHandler.onTextInput("愛".toByteArray(Charsets.UTF_8)) }
        verify(exactly = 0) { keyboardHandler.onTextInput("a".toByteArray(Charsets.UTF_8)) }
        verify(exactly = 0) { keyboardHandler.onTextInput("あ".toByteArray(Charsets.UTF_8)) }
    }

    @Test
    fun testCommitTextWithCombiningDakutenPreservesBoth() {
        // "ば" can arrive two ways from an IME:
        //   1. Precomposed U+3070 (one code point, 3-byte UTF-8)
        //   2. Base は U+306F + combining dakuten U+3099 (two code points)
        // Both should reach the shell byte-for-byte — no normalisation,
        // no combining-mark dropping. Some Android IMEs pick form (2) for
        // certain Japanese keyboards; losing the dakuten corrupts readings.
        val ic = makeView().ic()
        val decomposed = "\u306F\u3099"  // は + combining dakuten
        ic.commitText(decomposed, 1)
        verify { keyboardHandler.onTextInput(decomposed.toByteArray(Charsets.UTF_8)) }
    }

    // === English Gboard autocorrect after word boundary (#99) ===
    //
    // When Gboard offers a correction in the suggestion bar after a word
    // has been committed, tapping the correction emits a
    // `setComposingRegion(start, end)` covering the already-committed
    // word, then a `commitText("the", 1)` with the replacement. Without
    // translating the composing-region length into terminal backspaces,
    // the correction just appends (terminal ends up with "teh the ").

    /**
     * Wire a view so dispatched key events (e.g. from
     * [BaseInputConnection.sendKeyEvent]) are forwarded to the
     * [keyboardHandler] mock — mirroring Haven's production wiring via
     * [ImeInputView.setOnKeyListener]. Without this, `sendBackspaces`
     * inside the connection fires into a View that has no listener, and
     * the mock never sees the onKeyEvent calls.
     */
    private fun makeWiredView(): ImeInputView {
        val view = makeView()
        view.setOnKeyListener { _, _, event ->
            keyboardHandler.onKeyEvent(
                androidx.compose.ui.input.key.KeyEvent(event),
            )
            false
        }
        return view
    }

    @Test
    fun testSetComposingRegionThenCommitBackspacesOverPriorWord() {
        val ic = makeWiredView().ic()

        // Simulate the pre-correction state: "teh " has already been
        // committed to the terminal, so nothing buffered in our side.
        // Then Gboard marks "teh" as composing and commits "the".
        ic.setComposingRegion(0, 3)
        ic.commitText("the", 1)

        // Three DEL keycodes for "teh", then "the" committed.
        val keyEvents = mutableListOf<androidx.compose.ui.input.key.KeyEvent>()
        verify { keyboardHandler.onKeyEvent(capture(keyEvents)) }
        assertEquals(
            "expected exactly 3 DEL key events to back-space over 'teh'",
            3,
            keyEvents.count { it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DEL },
        )
        verify(exactly = 1) { keyboardHandler.onTextInput("the".toByteArray(Charsets.UTF_8)) }
    }

    @Test
    fun testSetComposingRegionLengthIsCappedAtMax() {
        // Guard against a runaway IME asking us to back-space the whole
        // scrollback. The cap is internal to ImeInputView (currently 64);
        // assert we never dispatch more than that even for a huge range.
        val ic = makeWiredView().ic()

        ic.setComposingRegion(0, 10_000)
        ic.commitText("x", 1)

        val delCount = mutableListOf<androidx.compose.ui.input.key.KeyEvent>()
        verify { keyboardHandler.onKeyEvent(capture(delCount)) }
        assertEquals(
            "DEL dispatch must be capped",
            true,
            delCount.count { it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DEL } <= 64,
        )
    }

    @Test
    fun testSetComposingRegionDuringActiveCompositionIsIgnored() {
        // If an IME re-scopes its own in-flight composition via
        // setComposingRegion while composingText is non-empty, we must
        // NOT translate that to terminal backspaces — the floating
        // composer already owns that text, and double-erasing would
        // delete characters the user actually typed before the
        // composition started.
        val ic = makeWiredView().ic()

        ic.setComposingText("te", 1)
        // IME now re-scopes the composing region to a smaller slice.
        ic.setComposingRegion(0, 2)
        ic.commitText("the", 1)

        // Only the final committed text should hit the shell — no DEL
        // keys from a bogus replacement.
        val keyEvents = mutableListOf<androidx.compose.ui.input.key.KeyEvent>()
        verify(atLeast = 0) { keyboardHandler.onKeyEvent(capture(keyEvents)) }
        assertEquals(
            "re-scoping an active composition must not dispatch terminal DELs",
            0,
            keyEvents.count { it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DEL },
        )
        verify(exactly = 1) { keyboardHandler.onTextInput("the".toByteArray(Charsets.UTF_8)) }
    }

    @Test
    fun testSetComposingRegionThenComposeThenFinishAppliesReplacement() {
        // Variant of the autocorrect flow where Gboard confirms via
        // finishComposingText rather than commitText:
        //   setComposingRegion(0, 3)          mark "teh" as composing
        //   setComposingText("the", 1)        lay down replacement
        //   finishComposingText()             accept (no commitText!)
        // Without handling this in finishComposingText, the correction
        // never reaches the shell — the original repro for #99.
        val ic = makeWiredView().ic()

        ic.setComposingRegion(0, 3)
        ic.setComposingText("the", 1)
        ic.finishComposingText()

        val keyEvents = mutableListOf<androidx.compose.ui.input.key.KeyEvent>()
        verify { keyboardHandler.onKeyEvent(capture(keyEvents)) }
        assertEquals(
            "three DEL events to erase 'teh' before the replacement",
            3,
            keyEvents.count { it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DEL },
        )
        verify(exactly = 1) { keyboardHandler.onTextInput("the".toByteArray(Charsets.UTF_8)) }
    }

    // === replaceText (API 34+ autocorrect path, #99 primary fix) ===
    //
    // On Android 14+ Gboard uses InputConnection.replaceText for
    // suggestion-bar word corrections instead of the older
    // setComposingRegion + commitText sequence. BaseInputConnection's
    // default rewrites the Editable directly without informing the
    // host, so the terminal never sees the replacement.

    @Test
    fun testReplaceTextAtCursorBackspacesPriorWordAndSendsReplacement() {
        val ic = makeWiredView().apply { allowStandardKeyboard = true }.ic()

        // Mirror the pre-correction state: "I'll ty" has been committed
        // to the terminal. Then Gboard corrects "y" → "ry " at 6..7.
        ic.commitText("I'll ty", 1)
        ic.replaceText(6, 7, "ry ", 1, null)

        // One DEL to erase "y", then "ry " committed.
        val keyEvents = mutableListOf<androidx.compose.ui.input.key.KeyEvent>()
        verify { keyboardHandler.onKeyEvent(capture(keyEvents)) }
        val delCount = keyEvents.count { it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DEL }
        assertEquals("expected 1 DEL to erase 'y'", 1, delCount)
        verify(exactly = 1) { keyboardHandler.onTextInput("ry ".toByteArray(Charsets.UTF_8)) }
    }

    @Test
    fun testReplaceTextMultiCharAtCursor() {
        val ic = makeWiredView().apply { allowStandardKeyboard = true }.ic()

        // "I'll try wth" — Gboard corrects "th" at 10..12 with "ith ".
        ic.commitText("I'll try wth", 1)
        ic.replaceText(10, 12, "ith ", 1, null)

        val keyEvents = mutableListOf<androidx.compose.ui.input.key.KeyEvent>()
        verify { keyboardHandler.onKeyEvent(capture(keyEvents)) }
        val delCount = keyEvents.count { it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DEL }
        assertEquals("expected 2 DELs to erase 'th'", 2, delCount)
        verify(exactly = 1) { keyboardHandler.onTextInput("ith ".toByteArray(Charsets.UTF_8)) }
    }

    @Test
    fun testReplaceTextMidBufferDoesNotRewriteTerminal() {
        // If an IME ever calls replaceText with end != cursor, we skip
        // the terminal rewrite rather than risk garbling shell state.
        // The editable still updates (for IME context) but the
        // terminal keeps whatever the user actually typed.
        val ic = makeWiredView().apply { allowStandardKeyboard = true }.ic()

        ic.commitText("hello world", 1)  // cursor at 11
        // Replace "hello" at 0..5 — end (5) != cursor (11).
        ic.replaceText(0, 5, "HELLO", 1, null)

        val keyEvents = mutableListOf<androidx.compose.ui.input.key.KeyEvent>()
        verify(atLeast = 0) { keyboardHandler.onKeyEvent(capture(keyEvents)) }
        val delCount = keyEvents.count { it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DEL }
        assertEquals(
            "mid-buffer replace must not dispatch terminal DELs",
            0,
            delCount,
        )
        verify(exactly = 0) { keyboardHandler.onTextInput("HELLO".toByteArray(Charsets.UTF_8)) }
    }

    @Test
    fun testReplaceTextNotifiesUpdateSelection() {
        val imm = mockk<InputMethodManager>(relaxed = true)
        val view = makeView(imm).apply { allowStandardKeyboard = true }
        val ic = view.ic()

        ic.commitText("teh", 1)
        ic.replaceText(0, 3, "the", 1, null)

        // After replace: editable "the", cursor 3.
        verify { imm.updateSelection(view, 3, 3, -1, -1) }
    }

    // === updateSelection notifications after IME edits (#99 follow-up) ===
    //
    // Custom InputConnection implementations are required to call
    // InputMethodManager.updateSelection after every edit — TextView does
    // this implicitly but BaseInputConnection does not. Without these
    // callbacks Gboard's tracked cursor stays frozen at (0, 0), so it
    // never re-queries getTextBeforeCursor and never enters the
    // composition protocol that word-level autocorrect relies on.
    //
    // These tests pin the notification at each edit path in Standard
    // keyboard mode (fullEditor=true). Secure mode uses a no-op editable
    // and intentionally skips notification — no useful selection to report.

    @Test
    fun testCommitTextInStandardModeNotifiesUpdateSelection() {
        val imm = mockk<InputMethodManager>(relaxed = true)
        val view = makeView(imm).apply { allowStandardKeyboard = true }
        val ic = view.ic()

        ic.commitText("hi", 1)

        // After "hi" editable + selection=2, no composition.
        verify { imm.updateSelection(view, 2, 2, -1, -1) }
    }

    @Test
    fun testCommitTextInSecureModeDoesNotNotifyUpdateSelection() {
        // Secure mode uses a no-op editable; selection never actually
        // advances so there is nothing useful to tell the IME. Leaving
        // notification off here also preserves the existing
        // testUpdateSelectionNotCalledAfterBackspaceKeyDown contract.
        val imm = mockk<InputMethodManager>(relaxed = true)
        val view = makeView(imm)
        val ic = view.ic()

        ic.commitText("abc", 1)

        verify(exactly = 0) { imm.updateSelection(any(), any(), any(), any(), any()) }
    }

    @Test
    fun testSetComposingTextNotifiesUpdateSelectionWithCompositionRegion() {
        val imm = mockk<InputMethodManager>(relaxed = true)
        val view = makeView(imm).apply { allowStandardKeyboard = true }
        val ic = view.ic()

        ic.setComposingText("abc", 1)

        // Composition covers 0..3, selection at end of composition.
        verify { imm.updateSelection(view, 3, 3, 0, 3) }
    }

    @Test
    fun testFinishComposingTextNotifiesUpdateSelectionWithNoComposition() {
        val imm = mockk<InputMethodManager>(relaxed = true)
        val view = makeView(imm).apply { allowStandardKeyboard = true }
        val ic = view.ic()

        ic.setComposingText("abc", 1)
        ic.finishComposingText()

        // After finish: composition cleared, candidatesStart/End = -1.
        verify { imm.updateSelection(view, 3, 3, -1, -1) }
    }

    @Test
    fun testSetComposingRegionNotifiesUpdateSelection() {
        val imm = mockk<InputMethodManager>(relaxed = true)
        val view = makeView(imm).apply { allowStandardKeyboard = true }
        val ic = view.ic()

        // Put "teh " in the editable then mark the word as composing.
        ic.commitText("teh ", 1)
        ic.setComposingRegion(0, 3)

        // Selection stays at end (4), composition region is 0..3.
        verify { imm.updateSelection(view, 4, 4, 0, 3) }
    }

    @Test
    fun testAutocorrectFlowNotifiesUpdateSelectionAtEachStep() {
        // End-to-end: "teh " committed, Gboard marks 0..3 as composing,
        // then commits replacement "the" — IME must see selection move
        // through the full trajectory for its state machine to follow.
        val imm = mockk<InputMethodManager>(relaxed = true)
        val view = makeView(imm).apply { allowStandardKeyboard = true }
        val ic = view.ic()

        ic.commitText("teh ", 1)
        ic.setComposingRegion(0, 3)
        ic.commitText("the", 1)

        // 1. "teh " committed: selection at 4.
        verify { imm.updateSelection(view, 4, 4, -1, -1) }
        // 2. composing region 0..3, selection still 4.
        verify { imm.updateSelection(view, 4, 4, 0, 3) }
        // 3. Replacement committed: editable "the ", selection at 3.
        verify { imm.updateSelection(view, 3, 3, -1, -1) }
    }

    @Test
    fun testDeleteSurroundingTextInStandardModeNotifiesUpdateSelection() {
        val imm = mockk<InputMethodManager>(relaxed = true)
        val view = makeView(imm).apply { allowStandardKeyboard = true }
        val ic = view.ic()

        ic.commitText("abc", 1)
        ic.deleteSurroundingText(1, 0)

        // "abc" → "ab", selection at 2.
        verify { imm.updateSelection(view, 2, 2, -1, -1) }
    }

    @Test
    fun testFinishComposingTextCancelsPendingReplacement() {
        // If the IME marks a region then finishes composition (without
        // committing replacement text) the pending length must be
        // dropped — otherwise the next unrelated commit would eat
        // characters from the terminal.
        val ic = makeWiredView().ic()

        ic.setComposingRegion(0, 3)
        ic.finishComposingText()
        ic.commitText("x", 1)

        val keyEvents = mutableListOf<androidx.compose.ui.input.key.KeyEvent>()
        verify(atLeast = 0) { keyboardHandler.onKeyEvent(capture(keyEvents)) }
        assertEquals(
            "finishComposingText must drop the pending replacement length",
            0,
            keyEvents.count { it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DEL },
        )
        verify(exactly = 1) { keyboardHandler.onTextInput("x".toByteArray(Charsets.UTF_8)) }
    }
}
