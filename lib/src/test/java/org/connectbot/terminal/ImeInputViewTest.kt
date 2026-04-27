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

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.text.Normalizer

@RunWith(AndroidJUnit4::class)
class ImeInputViewTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var keyboardHandler: KeyboardHandler

    @Before
    fun setup() {
        val terminalEmulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        keyboardHandler = KeyboardHandler(terminalEmulator)
    }

    private val noOpImm get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun makeView(
        selectionUpdates: MutableList<SelectionUpdate>? = null,
    ): ImeInputView {
        val onUpdateSelection: (View, Int, Int, Int, Int) -> Unit =
            if (selectionUpdates != null) {
                { view, selStart, selEnd, cStart, cEnd ->
                    selectionUpdates.add(SelectionUpdate(view, selStart, selEnd, cStart, cEnd))
                }
            } else {
                { _, _, _, _, _ -> }
            }
        return ImeInputView(context, keyboardHandler, noOpImm, onUpdateSelection)
    }

    data class SelectionUpdate(
        val view: View,
        val selStart: Int,
        val selEnd: Int,
        val candidatesStart: Int,
        val candidatesEnd: Int,
    )

    private fun ImeInputView.ic(composeMode: Boolean = false): BaseInputConnection {
        isComposeModeActive = composeMode
        return onCreateInputConnection(EditorInfo()) as BaseInputConnection
    }

    // === IME editable buffer reset on key events (compose mode — has a real Editable) ===

    @Test
    fun testSendEnterKeyDownClearsEditable() {
        val ic = makeView().ic(composeMode = true)
        ic.commitText("git status", 1)

        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))

        assertEquals("", ic.getEditable()?.toString())
    }

    @Test
    fun testSendKeyUpDoesNotClearEditable() {
        val ic = makeView().ic(composeMode = true)
        ic.getEditable()?.append("hello")

        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))

        assertEquals("hello", ic.getEditable()?.toString())
    }

    @Test
    fun testSendBackspaceKeyDownDoesNotClearEditable() {
        // #99: Gboard needs accumulated chars in Editable to route autocorrect
        // via setComposingRegion. Clearing on every backspace drops the IME
        // into per-char insert mode. Editable is cleared only on Enter.
        val ic = makeView().ic(composeMode = true)
        ic.commitText("abc", 1)

        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))

        assertEquals("abc", ic.getEditable()?.toString())
    }

    @Test
    fun testSecondCommandDoesNotAccumulateAfterEnter() {
        // Regression: "git status<enter>ls -l" should not appear as one suggestion candidate.
        val ic = makeView().ic(composeMode = true)

        ic.commitText("git status", 1)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        assertEquals("", ic.getEditable()?.toString())

        ic.commitText("ls -l", 1)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        assertEquals("", ic.getEditable()?.toString())
    }

    // === commitText RETAINS editable (#99 — Gboard autocorrect context) ===

    @Test
    fun testCommitTextRetainsTextInEditable() {
        // #99: committed text stays in Editable so Gboard can offer autocorrect
        // suggestions. Clearing on commit leaves Gboard in per-char mode.
        val ic = makeView().ic(composeMode = true)
        ic.commitText("some text", 1)

        assertEquals("some text", ic.getEditable()?.toString())
    }

    @Test
    fun testCommitTextWithActiveCompositionRetainsReplacement() {
        // When a composition is active and commitText arrives, the committed
        // text replaces the composition region. Result lives in Editable.
        val ic = makeView().ic(composeMode = true)
        ic.setComposingText("wor", 1)
        ic.commitText("word", 1)

        assertEquals("word", ic.getEditable()?.toString())
    }

    // === updateSelection is called after ACTION_DOWN key events (compose mode) ===

    @Test
    fun testUpdateSelectionCalledAfterEnterKeyDown() {
        val updates = mutableListOf<SelectionUpdate>()
        val view = makeView(updates)
        val ic = view.ic(composeMode = true)

        ic.commitText("git status", 1)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))

        assertTrue(updates.any { it.view === view && it.selStart == 0 && it.selEnd == 0 && it.candidatesStart == -1 && it.candidatesEnd == -1 })
    }

    @Test
    fun testUpdateSelectionNotResetAfterBackspaceKeyDown() {
        // #99: backspace should NOT reset IME state to (0, 0, -1, -1) — that
        // would drop Gboard's autocorrect context. Only Enter is a command
        // boundary that warrants a full selection reset.
        val updates = mutableListOf<SelectionUpdate>()
        val view = makeView(updates)
        val ic = view.ic(composeMode = true)

        ic.commitText("abc", 1)
        val updatesBeforeDel = updates.size
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))

        // Any new update from the DEL must not be the reset signal.
        val newUpdates = updates.drop(updatesBeforeDel)
        assertTrue(
            "Backspace should not reset IME selection to (0,0,-1,-1)",
            newUpdates.none { it.selStart == 0 && it.selEnd == 0 && it.candidatesStart == -1 && it.candidatesEnd == -1 },
        )
    }

    @Test
    fun testUpdateSelectionNotCalledOnKeyUp() {
        val updates = mutableListOf<SelectionUpdate>()
        val view = makeView(updates)
        val ic = view.ic(composeMode = true)

        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))

        assertTrue(updates.isEmpty())
    }

    // === resetImeBuffer() — used by physical keyboard paths that bypass InputConnection ===

    @Test
    fun testResetImeBufferClearsEditable() {
        val view = makeView()
        val ic = view.ic(composeMode = true)

        ic.commitText("git status", 1)
        view.resetImeBuffer()

        assertEquals("", ic.getEditable()?.toString())
    }

    @Test
    fun testResetImeBufferCallsUpdateSelection() {
        val updates = mutableListOf<SelectionUpdate>()
        val view = makeView(updates)
        view.ic()

        view.resetImeBuffer()

        assertTrue(updates.any { it.view === view && it.selStart == 0 && it.selEnd == 0 && it.candidatesStart == -1 && it.candidatesEnd == -1 })
    }

    @Test
    fun testResetImeBufferBeforeConnectionCreatedDoesNotCrash() {
        val view = makeView()
        // No InputConnection created yet — should not throw
        view.resetImeBuffer()
    }

    @Test
    fun testResetImeBufferClearsEditableAccumulatedBySetComposingText() {
        // setComposingText (voice input path) writes to the editable but does not clear it —
        // only finishComposingText does. resetImeBuffer() must also handle this mid-composition
        // case, which can be triggered by a physical hardware key interrupting voice input.
        val updates = mutableListOf<SelectionUpdate>()
        val view = makeView(updates)
        val ic = view.ic(composeMode = true)

        ic.setComposingText("hel", 1)
        view.resetImeBuffer()

        assertEquals("", ic.getEditable()?.toString())
        assertTrue(updates.any { it.view === view && it.selStart == 0 && it.selEnd == 0 && it.candidatesStart == -1 && it.candidatesEnd == -1 })
    }

    // === Physical-keyboard reset policy (issue #99 follow-up) ===

    @Test
    fun testShouldResetImeBufferOnKey_commandBoundaries() {
        // Enter and Escape end or interrupt the current shell line, so the IME
        // can safely forget its tracked prefix and start the next command fresh.
        assertTrue(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_ENTER))
        assertTrue(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertTrue(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_ESCAPE))
    }

    @Test
    fun testShouldResetImeBufferOnKey_textAndModifiersDoNotReset() {
        // Heart of the #99 follow-up: pressing Shift or typing a capital on a
        // physical keyboard must not wipe Gboard's tracked context for
        // subsequent soft-keyboard autocorrect.
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_SHIFT_RIGHT))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_CTRL_LEFT))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_CTRL_RIGHT))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_ALT_LEFT))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_META_LEFT))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_CAPS_LOCK))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_A))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_Z))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_0))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_SPACE))
    }

    @Test
    fun testShouldResetImeBufferOnKey_navigationDoesNotReset() {
        // Cursor movement, backspace, Tab, and function keys either don't
        // produce text (nav) or aren't meaningful command boundaries (Tab,
        // Fn). Leaving them alone keeps the IME's context intact across
        // brief physical-keyboard interjections.
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_DPAD_LEFT))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_DPAD_UP))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_DPAD_DOWN))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_DEL))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_FORWARD_DEL))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_TAB))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_F1))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_F12))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_HOME))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_MOVE_END))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_PAGE_UP))
    }

    // === IME duplicate character tests (connectbot/connectbot#1955) ===

    private fun createKeyboardOutputCapture(): Pair<InputConnection, MutableList<ByteArray>> {
        val outputs = mutableListOf<ByteArray>()
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data -> outputs.add(data.copyOf()) },
        )
        val handler = KeyboardHandler(emulator)
        var ic: InputConnection? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = ImeInputView(context, handler)
            view.isComposeModeActive = true
            view.setOnKeyListener { _, _, event ->
                handler.onKeyEvent(
                    androidx.compose.ui.input.key.KeyEvent(event),
                )
            }
            ic = view.onCreateInputConnection(EditorInfo())
        }
        return ic!! to outputs
    }

    /**
     * Compute the effective text from captured keyboard output by applying
     * BS (0x08) and DEL (0x7F) as character erasure operations.
     */
    private fun effectiveText(outputs: List<ByteArray>): String {
        val buffer = StringBuilder()
        for (data in outputs) {
            for (byte in data) {
                val code = byte.toInt() and 0xFF
                when {
                    code == 0x08 || code == 0x7F -> {
                        if (buffer.isNotEmpty()) buffer.deleteCharAt(buffer.length - 1)
                    }

                    code >= 0x20 -> buffer.append(byte.toInt().toChar())
                }
            }
        }
        return buffer.toString()
    }

    private fun drainMainLooper() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test
    fun testCommitAfterComposingDoesNotDuplicate() {
        val (ic, outputs) = createKeyboardOutputCapture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("a", 1)
            ic.commitText("a", 1)
        }
        drainMainLooper()
        assertEquals("a", effectiveText(outputs))
    }

    @Test
    fun testMultiCharComposingCommitDoesNotDuplicate() {
        val (ic, outputs) = createKeyboardOutputCapture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("h", 1)
            ic.setComposingText("he", 1)
            ic.setComposingText("hel", 1)
            ic.commitText("hel", 1)
        }
        drainMainLooper()
        assertEquals("hel", effectiveText(outputs))
    }

    @Test
    fun testDirectCommitWithoutComposing() {
        val (ic, outputs) = createKeyboardOutputCapture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText("x", 1)
        }
        drainMainLooper()
        assertEquals("x", effectiveText(outputs))
    }

    // === Unicode precomposition (NFC normalization) ===

    /**
     * Some IMEs send decomposed Unicode (NFD): a base character followed by a combining
     * diacritic as separate code points. The terminal must send the precomposed NFC form
     * so the remote host receives a single character (e.g. ä U+00E4) rather than two
     * separate code points (a U+0061 + combining umlaut U+0308).
     */
    @Test
    fun testDecomposedUmlautIsPrecomposed() {
        val (ic, outputs) = createKeyboardOutputCapture()
        // NFD: 'a' (U+0061) + combining diaeresis (U+0308) → should arrive as NFC ä (U+00E4)
        val nfdUmlaut = "a\u0308"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText(nfdUmlaut, 1)
        }
        drainMainLooper()
        val received = outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
        val expected = Normalizer.normalize(nfdUmlaut, Normalizer.Form.NFC)
        assertEquals(expected, received)
    }

    @Test
    fun testDecomposedCircumflexIsPrecomposed() {
        val (ic, outputs) = createKeyboardOutputCapture()
        // NFD: 'e' (U+0065) + combining circumflex (U+0302) → should arrive as NFC ê (U+00EA)
        val nfdCircumflex = "e\u0302"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText(nfdCircumflex, 1)
        }
        drainMainLooper()
        val received = outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
        val expected = Normalizer.normalize(nfdCircumflex, Normalizer.Form.NFC)
        assertEquals(expected, received)
    }

    @Test
    fun testAlreadyNfcTextIsUnchanged() {
        val (ic, outputs) = createKeyboardOutputCapture()
        // NFC ä (U+00E4) should pass through unchanged
        val nfcUmlaut = "\u00E4"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText(nfcUmlaut, 1)
        }
        drainMainLooper()
        val received = outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
        assertEquals(nfcUmlaut, received)
    }

    @Test
    fun testSurrogatePairSentAsOneCodepoint() {
        val (ic, outputs) = createKeyboardOutputCapture()
        // U+1F600 GRINNING FACE — encoded as a surrogate pair in Java/Kotlin strings
        val emoji = "\uD83D\uDE00"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText(emoji, 1)
        }
        drainMainLooper()
        val received = outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
        assertEquals(emoji, received)
    }

    // === Soft-keyboard TYPE_NULL key event routing ===

    private fun createNonComposeModeCapture(): Triple<InputConnection, ImeInputView, MutableList<ByteArray>> {
        val outputs = mutableListOf<ByteArray>()
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data -> outputs.add(data.copyOf()) },
        )
        val handler = KeyboardHandler(emulator)
        var ic: InputConnection? = null
        var view: ImeInputView? = null

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = ImeInputView(context, handler).also { v ->
                v.setOnKeyListener { _, _, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        ImeInputView.shouldResetImeBufferOnKey(event.keyCode)
                    ) {
                        v.resetImeBuffer()
                    }
                    handler.onKeyEvent(androidx.compose.ui.input.key.KeyEvent(event))
                }
                ic = v.onCreateInputConnection(EditorInfo())
            }
        }
        return Triple(ic!!, view!!, outputs)
    }

    /**
     * With TYPE_NULL, InputConnection.sendKeyEvent delivers the key directly to the terminal.
     */
    @Test
    fun testTypeNullSendKeyEventDeliversCharacter() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        }
        drainMainLooper()

        assertEquals("a", effectiveText(outputs))
    }

    /**
     * With TYPE_NULL, a raw view event (e.g. physical keyboard) that arrives independently
     * of sendKeyEvent still reaches the terminal via setOnKeyListener.
     */
    @Test
    fun testTypeNullRawViewEventDeliversCharacter() {
        val (_, view, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        }
        drainMainLooper()

        assertEquals("a", effectiveText(outputs))
    }

    /**
     * With TYPE_NULL, ENTER via View.dispatchKeyEvent must reach the terminal.
     */
    @Test
    fun testTypeNullRawViewEventEnterReachesTerminal() {
        val outputs = mutableListOf<ByteArray>()
        var enterDispatched = false
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data ->
                outputs.add(data.copyOf())
                if (data.contains(0x0D.toByte())) enterDispatched = true
            },
        )
        val handler = KeyboardHandler(emulator)
        var view: ImeInputView? = null

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = ImeInputView(context, handler).also { v ->
                v.setOnKeyListener { _, _, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        ImeInputView.shouldResetImeBufferOnKey(event.keyCode)
                    ) {
                        v.resetImeBuffer()
                    }
                    handler.onKeyEvent(androidx.compose.ui.input.key.KeyEvent(event))
                }
                v.onCreateInputConnection(EditorInfo())
            }
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view!!.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        }
        drainMainLooper()

        assertTrue("ENTER via dispatchKeyEvent did not reach the terminal", enterDispatched)
    }

    /**
     * Gboard sends via commitText AND fires a concurrent raw view event. The commitText
     * path delivers the character; the raw view event is independent. Two 'a's total.
     */
    @Test
    fun testTypeNullRawViewEventAndCommitTextDeliverIndependently() {
        val (ic, view, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Simulate Gboard: sends via commitText AND fires a raw view event.
            // Each path delivers a character — two independent 'a's total.
            ic.commitText("a", 1)
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        }
        drainMainLooper()

        assertEquals("aa", effectiveText(outputs))
    }

    @Test
    fun testTypeNullCommitTextDeliversAccentedCharacterWithoutDuplication() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText("ü", 1)
            ic.commitText("a", 1)
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
        assertEquals("üa", received)
    }

    /**
     * With TYPE_NULL, KEYCODE_DEL delivered via View.dispatchKeyEvent (physical keyboard or
     * Gboard's raw key path) must reach the terminal.
     */
    @Test
    fun testTypeNullRawViewDelKeyReachesTerminal() {
        val (_, view, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("DEL via dispatchKeyEvent did not reach the terminal", received.contains(0x7F.toByte()))
    }

    /**
     * Samsung Keyboard composes typed text via setComposingText then accepts via
     * finishComposingText without firing commitText. In Secure mode our
     * setComposingText handler eagerly commits the delta so sticky toolbar
     * modifiers (Ctrl/Alt) take effect on the very next keypress. (#110)
     */
    @Test
    fun testTypeNullSetComposingTextEagerlyCommitsInSecureMode() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("h", 1)
            ic.setComposingText("he", 1)
            ic.setComposingText("hel", 1)
            ic.setComposingText("hell", 1)
            ic.setComposingText("hello", 1)
            ic.finishComposingText()
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
        assertEquals("hello", received)
    }

    /**
     * Samsung Keyboard re-fires each composed character as a sendKeyEvent on its
     * batch flush (space/enter). The chars we already committed eagerly via
     * setComposingText must be suppressed to prevent double input. (#110)
     */
    @Test
    fun testTypeNullSendKeyEventAfterEagerComposeIsSuppressed() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("d", 1)
            ic.finishComposingText()
            // Samsung's deferred sendKeyEvent for the same char.
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_D))
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
        assertEquals("d", received)
    }

    /**
     * Real Samsung Keyboard "pwd<ENTER>" trace from SeriousM's logcat
     * (#110 v5.24.46 confirmation). Each char arrives via setComposingText
     * during typing (eager-committed), then finishComposingText with no
     * extra commit, then ENTER as a real sendKeyEvent that should reach
     * the terminal as 0x0d (CR). Asserts the full sequence renders as
     * "pwd\n" or "pwd\r" — whatever the terminal layer emits for ENTER.
     */
    @Test
    fun testTypeNullSamsungComposeMultiCharThenEnter() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("p", 1)
            ic.setComposingText("pw", 1)
            ic.setComposingText("pwd", 1)
            ic.finishComposingText()
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
        // Terminal emits CR for ENTER by default (DECCKM off, no LFE).
        assertEquals("pwd\r", received)
    }

    /**
     * Same multi-char path but with each char's *real-keyboard* ACTION_DOWN
     * sendKeyEvent arriving after finishComposingText (some keyboards do this
     * instead of accepting the composition). Each ACTION_DOWN should be
     * suppressed — the terminal must receive each char exactly once.
     */
    @Test
    fun testTypeNullSamsungMultiCharSuppressedOnDeferredKeyEvents() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("h", 1)
            ic.setComposingText("he", 1)
            ic.setComposingText("hel", 1)
            ic.setComposingText("hell", 1)
            ic.setComposingText("hello", 1)
            ic.finishComposingText()
            // Deferred per-char sendKeyEvents that Samsung sometimes flushes.
            listOf(
                KeyEvent.KEYCODE_H,
                KeyEvent.KEYCODE_E,
                KeyEvent.KEYCODE_L,
                KeyEvent.KEYCODE_L,
                KeyEvent.KEYCODE_O,
            ).forEach { code ->
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
        assertEquals("hello", received)
    }

    /**
     * Composition shrinks (user backspaces during composition before the IME
     * has flushed): we should send backspaces for the lost chars. (#110)
     */
    @Test
    fun testTypeNullSetComposingTextShrinkSendsBackspaces() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("test", 1)
            ic.setComposingText("te", 1)
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        // First four bytes are "test"; then two DEL (0x7F) backspaces.
        assertEquals("test".toByteArray().toList() + listOf(0x7F.toByte(), 0x7F.toByte()), received.toList())
    }

    /**
     * With TYPE_NULL, soft-keyboard backspace arrives via deleteSurroundingText →
     * sendKeyEvent(KEYCODE_DEL). Verify it reaches the terminal.
     */
    @Test
    fun testTypeNullDeleteSurroundingTextDeliversBackspace() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.deleteSurroundingText(1, 0)
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("DEL via deleteSurroundingText did not reach the terminal", received.contains(0x7F.toByte()))
    }

    // === DelKeyMode IME tests ===

    private fun createNonComposeModeWithMode(delKeyMode: DelKeyMode): Pair<InputConnection, MutableList<ByteArray>> {
        val outputs = mutableListOf<ByteArray>()
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data -> outputs.add(data.copyOf()) },
        )
        val handler = KeyboardHandler(emulator)
        handler.delKeyMode = delKeyMode
        var ic: InputConnection? = null

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ImeInputView(context, handler).also { v ->
                v.setOnKeyListener { _, _, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        ImeInputView.shouldResetImeBufferOnKey(event.keyCode)
                    ) {
                        v.resetImeBuffer()
                    }
                    handler.onKeyEvent(androidx.compose.ui.input.key.KeyEvent(event))
                }
                ic = v.onCreateInputConnection(EditorInfo())
            }
        }
        return ic!! to outputs
    }

    @Test
    fun testImeSoftBackspaceDeleteModeDefaultDeliversDel() {
        val (ic, outputs) = createNonComposeModeWithMode(DelKeyMode.Delete)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.deleteSurroundingText(1, 0)
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Expected DEL (0x7f) in default Delete mode", received.contains(0x7F.toByte()))
    }

    @Test
    fun testImeSoftBackspaceBackspaceModeDeliversCtrlH() {
        val (ic, outputs) = createNonComposeModeWithMode(DelKeyMode.Backspace)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.deleteSurroundingText(1, 0)
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Expected ^H (0x08) in Backspace mode", received.contains(0x08.toByte()))
        assertFalse("Should NOT send DEL (0x7f) in Backspace mode", received.contains(0x7F.toByte()))
    }

    /**
     * With TYPE_NULL, soft-keyboard ENTER arrives via sendKeyEvent(KEYCODE_ENTER) — it is a
     * non-printable key so there is no competing raw view event. Verify it reaches the terminal.
     */
    @Test
    fun testTypeNullSendKeyEventEnterReachesTerminal() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("ENTER via sendKeyEvent did not reach the terminal", received.contains(0x0D.toByte()))
    }

    // === Ctrl/Alt modifier key routing from soft keyboards (issue-2050) ===
    // Keyboards like "Unexpected keyboard", SwiftKey, and Hacker's Keyboard send Ctrl/Alt
    // combos via sendKeyEvent (with or without metaState). All sendKeyEvent calls are
    // forwarded directly to keyboardHandler.

    /**
     * Ctrl+A via sendKeyEvent (metaState=META_CTRL_ON) must reach the terminal as 0x01.
     * This is the path used by keyboards like "Unexpected keyboard" and SwiftKey.
     */
    @Test
    fun testTypeNullSendKeyEventCtrlAProducesControlChar() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(
                KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON),
            )
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Ctrl+A via sendKeyEvent did not produce 0x01", received.contains(0x01.toByte()))
    }

    /**
     * Ctrl+C via sendKeyEvent must reach the terminal as 0x03.
     */
    @Test
    fun testTypeNullSendKeyEventCtrlCProducesControlChar() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(
                KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON),
            )
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Ctrl+C via sendKeyEvent did not produce 0x03", received.contains(0x03.toByte()))
    }

    /**
     * Alt+A via sendKeyEvent must reach the terminal as ESC + 'a'.
     */
    @Test
    fun testTypeNullSendKeyEventAltAProducesEscapePrefix() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(
                KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_ALT_ON),
            )
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Alt+A via sendKeyEvent did not produce ESC prefix (0x1B)", received.contains(0x1B.toByte()))
        assertTrue("Alt+A via sendKeyEvent did not produce 'a'", received.contains('a'.code.toByte()))
    }

    /**
     * Plain printable key via sendKeyEvent (no modifier) delivers the character.
     * Hacker's Keyboard uses this path for number keys.
     */
    @Test
    fun testTypeNullSendKeyEventPlainPrintableDeliversCharacter() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B))
        }
        drainMainLooper()

        assertEquals("b", effectiveText(outputs))
    }

    /**
     * Space via sendKeyEvent delivers a space. KEYCODE_SPACE has isPrintingKey()=false
     * (KeyCharacterMap classifies ' ' as SPACE_SEPARATOR), but it is still forwarded like
     * all other sendKeyEvent keys.
     */
    @Test
    fun testTypeNullSendKeyEventSpaceDeliversSpace() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE))
        }
        drainMainLooper()

        assertEquals(" ", effectiveText(outputs))
    }
}
