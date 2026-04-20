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
import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
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
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager,
    internal val onUpdateSelection: (view: View, selStart: Int, selEnd: Int, candidatesStart: Int, candidatesEnd: Int) -> Unit =
        { view, selStart, selEnd, candidatesStart, candidatesEnd ->
            inputMethodManager.updateSelection(view, selStart, selEnd, candidatesStart, candidatesEnd)
        },
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
        // NO_EXTRACT_UI suppresses the IME's fullscreen-editor UI in
        // landscape. For Secure/Compose modes this is right — we render
        // our own text. But in Standard keyboard mode the user has
        // explicitly opted into full IME features, and some IMEs
        // (Gboard included) interpret NO_EXTRACT_UI as "this field
        // doesn't want rich editing" and suppress composition-based
        // autocorrect.
        val noExtractUi = if (allowStandardKeyboard) 0
        else EditorInfo.IME_FLAG_NO_EXTRACT_UI
        outAttrs.imeOptions = outAttrs.imeOptions or
                noExtractUi or
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
            // Standard keyboard: voice input, swipe typing, autocomplete
            // and autocorrect all enabled. The IME may learn typed input
            // including passwords.
            //
            // TYPE_TEXT_FLAG_AUTO_CORRECT tells the IME "this field wants
            // correction-as-you-type" — without it, Gboard shows
            // suggestions but never triggers the composition-based
            // replacement protocol (setComposingRegion + commitText).
            // See #99.
            outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT or
                    EditorInfo.TYPE_TEXT_FLAG_AUTO_CORRECT
        } else {
            // Terminal mode (default):
            // TYPE_CLASS_TEXT enables IME composition (required for CJK input
            // methods — Japanese, Chinese, Korean). TYPE_TEXT_FLAG_NO_SUGGESTIONS
            // suppresses autocomplete/prediction while keeping the composition
            // protocol active. See #96.
            outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT or
                    EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        // fullEditor=true gives BaseInputConnection a real Editable, which
        // Gboard needs to track replacement ranges for autocorrect via
        // setComposingRegion / deleteSurroundingText. Enable it whenever
        // the user has opted into a mode that allows IME suggestions —
        // compose mode (CJK) and Standard keyboard. Leave it off for
        // default Secure mode where NO_SUGGESTIONS is already set and we
        // don't want the editable to accumulate shell input.
        val fullEditor = isComposeModeActive || allowStandardKeyboard
        return TerminalInputConnection(this, fullEditor).also { activeConnection = it }
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
        onUpdateSelection(this, 0, 0, -1, -1)
    }

    /**
     * Custom InputConnection that handles backspace and other special keys for terminal input.
     */
    private inner class TerminalInputConnection(
        targetView: View,
        private val fullEditor: Boolean,
    ) : BaseInputConnection(targetView, fullEditor) {

        private var composingText: String = ""

        /**
         * Tell the IME where the cursor is. Custom InputConnection
         * implementations are required to do this explicitly after every
         * edit — TextView/EditText get it for free via the view layer, but
         * BaseInputConnection does not. Without these calls Gboard keeps
         * its own cursor-tracking frozen at (0, 0), never re-queries
         * [getTextBeforeCursor], and therefore never enters the
         * composition-based replacement protocol that word-level
         * autocorrect relies on (#99).
         *
         * Derives selection + composing-region positions from the
         * underlying [Editable], which is the single source of truth —
         * [BaseInputConnection] keeps it in sync as part of each super.*
         * call. Only fires in modes where [fullEditor] is true; in Secure
         * mode the Editable is a no-op proxy and the selection never
         * actually advances.
         */
        private fun notifyImeSelection() {
            if (!fullEditor) return
            val ed = editable ?: return
            inputMethodManager.updateSelection(
                this@ImeInputView,
                Selection.getSelectionStart(ed),
                Selection.getSelectionEnd(ed),
                getComposingSpanStart(ed),
                getComposingSpanEnd(ed),
            )
        }

        /**
         * Number of chars the IME has asked us (via [setComposingRegion])
         * to treat as re-composable. Consumed by the next [commitText] /
         * [setComposingText], which back-spaces over that many chars
         * before laying down its replacement.
         *
         * This is what makes English Gboard autocorrect work after a word
         * boundary: the IME marks the already-committed word as a
         * composing region and then commits the correction. Without this,
         * the correction just appends (terminal ends up with "teh the ").
         */
        private var pendingReplacementLength: Int = 0

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            super.setComposingRegion(start, end)
            // Only honour this when no composition is already in flight.
            // An active composition means the IME is re-scoping its own
            // ongoing composition, which the floating-composer tracker in
            // [composingText] already handles — translating to terminal
            // backspaces there would double-erase.
            if (composingText.isEmpty()) {
                // Cap at a sane word/phrase length. A runaway IME handing us
                // e.g. the whole document length would otherwise blow away
                // the user's shell history on the next commit.
                pendingReplacementLength = (end - start).coerceIn(0, MAX_REPLACEMENT_LENGTH)
            }
            notifyImeSelection()
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (!fullEditor) return super.setComposingText(text, newCursorPosition)

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
            notifyImeSelection()
            return true
        }

        override fun finishComposingText(): Boolean {
            if (!fullEditor) return super.finishComposingText()

            super.finishComposingText()

            // Replacement confirmed via finishComposingText (rather than
            // commitText): the IME marked a region as composing then
            // laid down a new composition on top. Some keyboards
            // (Gboard's English autocorrect in particular) then fire
            // finishComposingText to accept it, never a commitText. If
            // we just clear state here the correction vanishes — apply
            // it now.
            if (pendingReplacementLength > 0 && composingText.isNotEmpty()) {
                sendBackspaces(pendingReplacementLength)
                sendTextInput(composingText)
            }

            composingText = ""
            _composingText.value = ""
            // Either applied above or cancelled without a commit — either
            // way, don't carry a pending length into an unrelated future
            // commit.
            pendingReplacementLength = 0
            // Do NOT clear the editable here. finishComposingText only
            // signals end of ONE composition event, not end of the
            // whole input. Clearing would wipe the context Gboard uses
            // to decide whether to offer a correction on the next word.
            // Enter (in sendKeyEvent) is the one real reset point.
            notifyImeSelection()
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
                notifyImeSelection()
                return true
            }

            // No composition active — this is a real backspace through the IME,
            // dispatch it to the terminal.
            for (i in 0 until bounded) {
                sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            }

            // TODO: Implement forward delete if rightLength > 0
            super.deleteSurroundingText(leftLength, rightLength)
            notifyImeSelection()
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

        /**
         * IME moved the cursor explicitly (e.g. the user tapped to
         * place the caret inside an existing word). Mirror the new
         * position back to Gboard so its tracked selection stays in
         * sync with the Editable.
         */
        override fun setSelection(start: Int, end: Int): Boolean {
            val r = super.setSelection(start, end)
            notifyImeSelection()
            return r
        }

        /**
         * Code-point variant of [deleteSurroundingText]. BaseInputConnection
         * handles the editable update; we just mirror the selection back
         * to the IME so it doesn't fall out of sync.
         */
        override fun deleteSurroundingTextInCodePoints(
            beforeLength: Int,
            afterLength: Int,
        ): Boolean {
            val r = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            notifyImeSelection()
            return r
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (suppressKeyEvents) return true
            val isEnterDown = event.action == KeyEvent.ACTION_DOWN &&
                event.keyCode == KeyEvent.KEYCODE_ENTER
            if (isEnterDown) {
                // Real Enter from IME — cancel any deferred commitText Enter
                enterHandledByKeyEvent = true
                handler.removeCallbacks(enterFallbackRunnable)
            }
            val result = this@ImeInputView.dispatchKeyEvent(event)
            // Clear the IME's text buffer + reset selection ONLY on Enter.
            // Previously this ran after any ACTION_DOWN which clobbered
            // Gboard's editable-tracking after every backspace / DEL we
            // dispatched (including our own autocorrect backspaces), so
            // Gboard could never build up enough context to offer
            // composition-based corrections. Enter is the one real
            // command-boundary signal where dropping accumulated IME
            // state is correct — prevents "git status<enter>ls -l"
            // from landing in Gboard's suggestion bar as one candidate.
            if (isEnterDown) {
                editable?.clear()
                onUpdateSelection(this@ImeInputView, 0, 0, -1, -1)
            }
            return result
        }

        /**
         * API 34+ autocorrect replacement path. Gboard on Android 14+
         * uses this instead of `setComposingRegion` + `commitText` when
         * the user taps a suggestion-bar correction for an
         * already-committed word. [BaseInputConnection.replaceText]
         * rewrites the internal [Editable] via `content.replace(start,
         * end, text)` but — unlike `commitText` — doesn't dispatch key
         * events or otherwise inform the host of the change. Without
         * overriding this, Gboard's correction lands in our Editable
         * but never reaches the shell, leaving the terminal with the
         * uncorrected typing.
         *
         * Translation: the editable is a mirror of the characters the
         * terminal has received, so replacing `[start, end)` with
         * `text` maps to `(end - start)` backspaces followed by
         * emitting `text`. Only correct when `end` is at the cursor —
         * autocorrect always replaces the current word, so that holds
         * in practice. If an IME ever calls this with `end` mid-buffer
         * we log and stay conservative (editable updates, terminal
         * doesn't), because re-emitting the suffix is unsafe (the
         * shell may have moved the cursor, processed escapes, etc.).
         */
        override fun replaceText(
            start: Int,
            end: Int,
            text: CharSequence,
            newCursorPosition: Int,
            textAttribute: android.view.inputmethod.TextAttribute?,
        ): Boolean {
            val ed = editable
            val cursorBefore = ed?.let { Selection.getSelectionStart(it) } ?: -1
            val result = super.replaceText(start, end, text, newCursorPosition, textAttribute)
            notifyImeSelection()

            // Only rewrite the terminal when the replacement ends at the
            // current cursor — i.e. Gboard is correcting the last word.
            // An IME replacing mid-buffer text would require us to also
            // re-emit the suffix, which isn't safe because the shell may
            // have already echoed / processed those bytes.
            if (ed != null && end == cursorBefore && start in 0..end) {
                val backspaces = end - start
                if (backspaces > 0) sendBackspaces(backspaces)
                val replacement = text.toString().replace("\n", "").replace("\r", "")
                if (replacement.isNotEmpty()) sendTextInput(replacement)
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
                    // If the IME earlier marked a region as composing (English
                    // autocorrect: "teh " selected for replacement with "the"),
                    // back-space that many chars before laying the new text
                    // down. See [setComposingRegion] + [pendingReplacementLength].
                    if (pendingReplacementLength > 0) {
                        sendBackspaces(pendingReplacementLength)
                        pendingReplacementLength = 0
                    }
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
            // Do NOT clear the editable here — Gboard needs recent chars
            // in the Editable to decide whether to offer an autocorrect
            // suggestion and to route replacement via setComposingRegion.
            // Clearing on every commit leaves it permanently empty, which
            // drops Gboard into a dumb per-char insert mode (the #99
            // symptom). Enter still clears on the sendKeyEvent path to
            // avoid one command's text carrying into the next.
            //
            // Runaway protection: cap the editable length at
            // EDITABLE_MAX_LENGTH; if a caller commits more than that
            // without hitting Enter, drop the oldest half.
            editable?.let { ed ->
                if (ed.length > EDITABLE_MAX_LENGTH) {
                    ed.delete(0, ed.length / 2)
                }
            }
            // Tell the IME the cursor moved. Without this Gboard keeps
            // its tracked selection frozen at (0, 0), never re-queries
            // [getTextBeforeCursor], and therefore never enters the
            // composition protocol for word-level autocorrect. See
            // [notifyImeSelection].
            notifyImeSelection()
            return true
        }

        /**
         * Dispatch backspaces to the terminal without triggering the
         * enclosing view's key listener. That listener (see Terminal's
         * [setOnKeyListener]) calls [resetImeBuffer] on every
         * ACTION_DOWN to keep the IME's editable in sync with physical
         * keyboard input. For our internal autocorrect / replacement
         * flow that side-effect is actively harmful: it wipes the
         * Editable Gboard has just been notified about, so the IME
         * loses context one keystroke after we just established it.
         * Route straight to [KeyboardHandler.onKeyEvent] instead.
         */
        private fun sendBackspaces(count: Int) {
            repeat(count.coerceAtLeast(0)) {
                val native = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
                keyboardHandler.onKeyEvent(
                    androidx.compose.ui.input.key.KeyEvent(native),
                )
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
            pendingReplacementLength = 0
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
            pendingReplacementLength = 0
        }

        /** Drop any pending setComposingRegion replacement without consuming it. */
        internal fun resetPendingReplacement() {
            pendingReplacementLength = 0
        }
    }

    private companion object {
        /**
         * Upper bound on `deleteSurroundingText.leftLength`. Real IMEs request
         * at most a few chars at a time; this cap protects against a runaway
         * value from a buggy IME freezing the UI thread in a DEL-key loop.
         */
        const val MAX_DELETE_SURROUNDING = 4096

        /**
         * Upper bound on an IME-requested `setComposingRegion` length. Real
         * IMEs use this for word-level corrections (≤20 chars). Capping
         * prevents a misaligned IME state from sending a runaway backspace
         * count on the following commit.
         */
        const val MAX_REPLACEMENT_LENGTH = 64

        /**
         * Soft ceiling on the [BaseInputConnection] editable length. Enter
         * clears it normally, but a user who types a very long command
         * without pressing Enter would otherwise accumulate forever.
         * When exceeded we drop the first half — plenty of context left
         * for Gboard's correction window (usually the current word),
         * without unbounded memory growth.
         */
        const val EDITABLE_MAX_LENGTH = 512
    }
}
