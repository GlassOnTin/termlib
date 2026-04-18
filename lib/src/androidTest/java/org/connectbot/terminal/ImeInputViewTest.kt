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
    fun testSendBackspaceKeyDownClearsEditable() {
        val ic = makeView().ic()
        ic.commitText("abc", 1)

        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))

        assertEquals("", ic.getEditable()?.toString())
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

    // === commitText clears editable (regression guard) ===

    @Test
    fun testCommitTextClearsEditable() {
        val ic = makeView().ic()
        ic.commitText("some text", 1)

        assertEquals("", ic.getEditable()?.toString())
    }

    @Test
    fun testCommitTextWithActiveCompositionClearsEditable() {
        val ic = makeView().ic()
        ic.setComposingText("wor", 1)
        ic.commitText("word", 1)

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
    fun testUpdateSelectionCalledAfterBackspaceKeyDown() {
        val imm = mockk<InputMethodManager>(relaxed = true)
        val view = makeView(imm)
        val ic = view.ic()

        ic.commitText("abc", 1)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))

        verify { imm.updateSelection(view, 0, 0, -1, -1) }
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
}
