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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A minimal invisible View that provides proper IME input handling for terminal emulation.
 *
 * This view creates a custom InputConnection that:
 * - Handles backspace via deleteSurroundingText by sending KEYCODE_DEL
 * - Handles enter/return keys properly via sendKeyEvent
 * - Configures the keyboard to disable suggestions while allowing voice input
 * - Handles composing text from IME (for voice input partial results)
 * - Manages IME visibility using InputMethodManager for reliable show/hide
 *
 * Based on the ConnectBot v1.9.13 TerminalView implementation.
 */
internal class ImeInputView(
    context: Context,
    private val keyboardHandler: KeyboardHandler,
    internal val inputMethodManager: InputMethodManager =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
) : View(context) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    var isComposeModeActive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (windowToken != null) {
                flushCompositionBeforeRestart()
                inputMethodManager.restartInput(this)
            }
        }

    /** When true, use standard keyboard allowing voice input, swipe, and autocomplete. */
    var allowStandardKeyboard: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (windowToken != null) {
                flushCompositionBeforeRestart()
                inputMethodManager.restartInput(this)
            }
        }

    /**
     * Raw mode — returns a null [InputConnection] and reports this view as
     * not-a-text-editor, so the IME has nothing to attach to. Gboard's mic,
     * suggestion strip, and AI Core writing assist all disappear because
     * there is no text field for them to decorate. Physical keyboards still
     * work (dispatchKeyEvent goes through View). Trade-offs: IME composition
     * is impossible (no CJK) and soft-keyboard input comes through as raw
     * key events only.
     */
    var rawKeyboardMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (windowToken != null) {
                flushCompositionBeforeRestart()
                inputMethodManager.restartInput(this)
            }
        }

    /**
     * Live IME composition text (romaji mid-conversion, pinyin candidates, etc.)
     * Emits the empty string when no composition is active. Observed by the
     * terminal UI to render a floating composer overlay near the cursor
     * instead of projecting the partial text inline into the terminal.
     * Never null; never empty+non-empty transitions spuriously.
     */
    private val _composingText = MutableStateFlow("")
    val composingText: StateFlow<String> = _composingText.asStateFlow()

    /**
     * `restartInput()` discards the live [TerminalInputConnection]. If the
     * user is mid-composition when a mode flag changes (e.g. they toggle
     * Standard keyboard while a Japanese romaji string is partially typed),
     * the projected characters are already in the terminal but the state
     * that knows about them — [TerminalInputConnection.composingText] — is
     * about to be thrown away. Erase the projected text first so the
     * terminal matches the "empty composition" state the new connection
     * will start from.
     */
    private fun flushCompositionBeforeRestart() {
        activeConnection?.flushCompositionAsBackspaces()
    }

    /**
     * Show the IME forcefully. This is more reliable than SoftwareKeyboardController.
     */
    @Suppress("DEPRECATION")
    fun showIme() {
        if (requestFocus()) {
            inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_FORCED)
        }
    }

    /**
     * Hide the IME.
     */
    fun hideIme() {
        inputMethodManager.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Always hide IME when view is detached to prevent SHOW_FORCED from keeping keyboard
        // open after the app/activity is destroyed
        hideIme()
        // Cancel any deferred Enter-fallback so a Handler callback doesn't fire
        // into a detached view after the IME committed "\n" just before teardown.
        activeConnection?.cancelPending()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        // Raw mode: no InputConnection at all. Gboard has nothing to attach
        // to, so its mic / suggestion strip / AI Core writing assist all go
        // away. Physical key events still flow through View.dispatchKeyEvent.
        if (rawKeyboardMode) {
            activeConnection = null
            return null
        }

        // Configure IME options. NO_PERSONALIZED_LEARNING tells on-device
        // IME features (Gboard's word bank, Gemini Nano / AI Core writing
        // assist) to keep their hands off this field. On recent Gboard
        // builds it's the single strongest signal — strong enough that
        // Gboard also hides the microphone in response. So we only set it
        // when the user has NOT explicitly opted into Standard keyboard —
        // in Standard mode the user has asked for full IME features and
        // we shouldn't suppress them.
        val noLearning = if (allowStandardKeyboard) 0
        else EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        outAttrs.imeOptions = outAttrs.imeOptions or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_ENTER_ACTION or
                noLearning or
                EditorInfo.IME_ACTION_NONE

        if (isComposeModeActive) {
            // Compose mode: allow voice input and IME suggestions.
            // TYPE_CLASS_TEXT without NO_SUGGESTIONS keeps the suggestion strip (and its
            // microphone button) visible. fullEditor=true makes BaseInputConnection provide
            // a real Editable so getExtractedText() returns non-null (required by Gboard
            // for voice input).
            outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT
            outAttrs.initialSelStart = 0
            outAttrs.initialSelEnd = 0
        } else if (allowStandardKeyboard) {
            // Standard keyboard: voice input, swipe typing, autocomplete enabled.
            // The IME may learn typed input including passwords.
            outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT
        } else {
            // Terminal mode (default):
            // TYPE_CLASS_TEXT enables IME composition (required for CJK input
            // methods — Japanese, Chinese, Korean). TYPE_TEXT_FLAG_NO_SUGGESTIONS
            // suppresses autocomplete/prediction while keeping the composition
            // protocol active. See #96.
            outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT or
                    EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        return TerminalInputConnection(this, isComposeModeActive).also { activeConnection = it }
    }

    override fun onCheckIsTextEditor(): Boolean = !rawKeyboardMode

    private var activeConnection: TerminalInputConnection? = null

    /**
     * Clears the IME's internal text buffer and resets its selection state to (0, 0).
     *
     * Call this after key events that are dispatched outside the InputConnection (e.g. physical
     * keyboard events handled via onPreviewKeyEvent or setOnKeyListener), so that the IME's
     * suggestion context stays in sync with the terminal's stateless text model.
     */
    fun resetImeBuffer() {
        // Clear both the Editable AND the composing-text tracking so an
        // in-flight IME composition doesn't resume against stale state
        // (which would make the next setComposingText() produce ghost
        // backspaces or duplicated input).
        activeConnection?.editable?.clear()
        activeConnection?.resetComposition()
        inputMethodManager.updateSelection(this, 0, 0, -1, -1)
    }

    /**
     * Custom InputConnection that handles backspace and other special keys for terminal input.
     */
    private inner class TerminalInputConnection(
        targetView: View,
        fullEditor: Boolean
    ) : BaseInputConnection(targetView, fullEditor) {

        private var composingText: String = ""

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val newText = text?.toString() ?: ""
            super.setComposingText(text, newCursorPosition)

            // Floating-composer model: the in-flight composition lives in an
            // overlay, never in the terminal. We only update the tracker and
            // the flow; no bytes hit the remote shell until commitText() fires.
            // Crucial for CJK (romaji → kanji conversion used to flicker the
            // terminal with backspace+rewrite each candidate) and for high-
            // latency transports (mosh, ET) where those round-trips were slow.
            if (newText != composingText) {
                composingText = newText
                _composingText.value = newText
            }
            return true
        }

        override fun finishComposingText(): Boolean {
            super.finishComposingText()
            composingText = ""
            _composingText.value = ""
            // Clear the internal Editable to prevent unbounded accumulation
            editable?.clear()
            return true
        }

        override fun deleteSurroundingText(leftLength: Int, rightLength: Int): Boolean {
            // Handle backspace by sending DEL key events.
            // When IME sends delete, it often sends (0, 0) or (1, 0) for backspace.
            if (rightLength == 0 && leftLength == 0) {
                return sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            }

            // Cap the loop: real IMEs send single-digit values here. A misbehaving
            // or hostile IME asking for ~2^31 deletions would freeze the UI thread.
            val bounded = leftLength.coerceIn(0, MAX_DELETE_SURROUNDING)

            // If a composition is in flight, the IME is trying to delete part of
            // ITS composition (e.g. user pressed backspace while composing). The
            // composition lives only in the floating overlay, never in the
            // terminal, so we must NOT send DEL keys to the shell — just shrink
            // our tracker and let the overlay follow.
            if (composingText.isNotEmpty()) {
                val newLength = (composingText.length - bounded).coerceAtLeast(0)
                composingText = composingText.substring(0, newLength)
                _composingText.value = composingText
                super.deleteSurroundingText(leftLength, rightLength)
                return true
            }

            // No composition active — this is a real backspace through the IME,
            // dispatch it to the terminal.
            for (i in 0 until bounded) {
                sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            }

            // TODO: Implement forward delete if rightLength > 0
            super.deleteSurroundingText(leftLength, rightLength)
            return true
        }

        // Guard: when true, sendKeyEvent is suppressed to prevent double input
        // from BaseInputConnection.commitText() dispatching key events AND
        // our sendTextInput() both sending the same characters.
        private var suppressKeyEvents = false

        private val handler = android.os.Handler(android.os.Looper.getMainLooper())

        // Enter deduplication: commitText("\n") always defers to sendKeyEvent.
        // If sendKeyEvent(ENTER) arrives (from the same IME action), we cancel
        // the deferred dispatch. If it doesn't arrive within one frame (~16ms),
        // the deferred dispatch fires — covering IMEs that only commitText.
        private var enterHandledByKeyEvent = false
        private val enterFallbackRunnable = Runnable {
            if (!enterHandledByKeyEvent) {
                this@ImeInputView.dispatchKeyEvent(
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER),
                )
            }
            enterHandledByKeyEvent = false
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (suppressKeyEvents) return true
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER) {
                // Real Enter from IME — cancel any deferred commitText Enter
                enterHandledByKeyEvent = true
                handler.removeCallbacks(enterFallbackRunnable)
            }
            val result = this@ImeInputView.dispatchKeyEvent(event)
            // After any key event, clear the IME's text buffer and reset the selection to (0,0).
            // This prevents Gboard from accumulating terminal input into its suggestion context
            // (e.g. treating "git status<enter>ls -l" as a single suggestion candidate).
            if (event.action == KeyEvent.ACTION_DOWN) {
                editable?.clear()
                inputMethodManager.updateSelection(this@ImeInputView, 0, 0, -1, -1)
            }
            return result
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val committedText = text?.toString() ?: ""
            // super.commitText() updates the Editable state (needed for IME sync)
            // but also dispatches key events via sendKeyEvent(). Suppress those
            // to avoid double input — we send the text ourselves via sendTextInput().
            suppressKeyEvents = true
            super.commitText(text, newCursorPosition)
            suppressKeyEvents = false

            if (committedText.isNotEmpty()) {
                // The floating-composer model never projects the in-flight
                // composition into the terminal, so there is nothing to erase
                // here — just dispatch the final committed text.
                //
                // Filter newlines from committed text — Enter is handled by
                // sendKeyEvent(KEYCODE_ENTER) when the IME sends both.
                // For IMEs that only commitText("\n"), a deferred fallback
                // dispatches Enter after one frame if sendKeyEvent didn't fire.
                val filtered = committedText.replace("\n", "").replace("\r", "")
                if (filtered.isNotEmpty()) {
                    sendTextInput(filtered)
                } else if (committedText.contains('\n') || committedText.contains('\r')) {
                    // Defer Enter dispatch — give sendKeyEvent a chance to handle it
                    enterHandledByKeyEvent = false
                    handler.removeCallbacks(enterFallbackRunnable)
                    handler.postDelayed(enterFallbackRunnable, 16)
                }
            }
            composingText = ""
            _composingText.value = ""
            // Clear the internal Editable to prevent unbounded accumulation
            editable?.clear()
            return true
        }

        private fun sendBackspaces(count: Int) {
            repeat(count.coerceAtLeast(0)) {
                sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            }
        }

        private fun sendTextInput(text: String) {
            if (text.isNotEmpty()) {
                keyboardHandler.onTextInput(text.toByteArray(Charsets.UTF_8))
            }
        }

        /**
         * Cancel any pending deferred dispatches. Called from
         * [ImeInputView.onDetachedFromWindow] so a posted fallback doesn't
         * fire into a dead view after teardown.
         */
        internal fun cancelPending() {
            handler.removeCallbacks(enterFallbackRunnable)
            enterHandledByKeyEvent = false
        }

        /** Drop in-flight composition state without touching the terminal output. */
        internal fun resetComposition() {
            composingText = ""
            _composingText.value = ""
        }

        /**
         * Drop any in-flight composition before the enclosing view calls
         * [InputMethodManager.restartInput]. Previously this also sent
         * backspaces to the terminal because the composition had been
         * projected inline; in the floating-composer model nothing was
         * ever sent, so we only need to clear the tracker + overlay.
         */
        internal fun flushCompositionAsBackspaces() {
            if (composingText.isNotEmpty()) {
                composingText = ""
                _composingText.value = ""
            }
        }
    }

    private companion object {
        /**
         * Upper bound on `deleteSurroundingText.leftLength`. Real IMEs request
         * at most a few chars at a time; this cap protects against a runaway
         * value from a buggy IME freezing the UI thread in a DEL-key loop.
         */
        const val MAX_DELETE_SURROUNDING = 4096
    }
}
