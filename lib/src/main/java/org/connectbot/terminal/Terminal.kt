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

import android.app.Activity
import android.content.Intent
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.TextPaint
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * Gesture type for unified gesture handling state machine.
 */
private enum class GestureType {
    Undetermined,
    Scroll,
    Selection,
    Zoom,
    HandleDrag,
    /**
     * Mouse drag forwarded to the remote (tmux mouse mode). Press/motion/release
     * dispatched via [TerminalGestureCallback.onMouseDrag]. Selected when
     * a gestureCallback is present, the user starts dragging without a
     * long-press, and the callback claims the gesture (returns true on
     * [MouseDragPhase.Start]). (#94)
     */
    MouseDrag,
}

/**
 * The rate at which the cursor blinks in milliseconds when enabled.
 */
private const val CURSOR_BLINK_RATE_MS = 500L

/**
 * Amount of time to wait for second touch to detect multitouch gesture in milliseconds.
 */
private const val WAIT_FOR_SECOND_TOUCH_MS = 40L

/**
 * Long-press threshold for the gesture callback (ms).
 * When a gestureCallback is present, fires onLongPress at this time.
 * Short enough to feel responsive as a right-click; user must hold past
 * MOUSE_MODE_SELECTION_DELAY_MS for text selection.
 */
private const val CALLBACK_LONG_PRESS_MS = 400L

/**
 * Long-press delay for text selection when a gesture callback is present (ms).
 * Longer than CALLBACK_LONG_PRESS_MS so the user can distinguish
 * callback action (release before 500ms) from selection (hold past 500ms).
 */
private const val MOUSE_MODE_SELECTION_DELAY_MS = 500L

/**
 * Millis after last multi-touch event to suppress tap from stale
 * finger lift-off after a pinch-to-zoom. Only blocks taps, not
 * long-press or drag gestures.
 */
private const val MULTITOUCH_LIFTOFF_MS = 200L

/**
 * Pixels of vertical drag accumulated before emitting one scroll callback event.
 * Lower values feel more responsive in TUI apps but send more escape sequences.
 */
private const val SCROLL_THRESHOLD_PX = 24f

/**
 * Fraction of terminal height at top/bottom that triggers edge-scroll
 * when dragging selection near the boundary.
 */
private const val EDGE_SCROLL_ZONE = 0.12f

/**
 * Text selection magnifier loupe size in dp.
 */
private const val MAGNIFIER_SIZE_DP = 100

/**
 * How much to scale up the text in the magnifier loupe.
 */
private const val MAGNIFIER_SCALE = 2.5f

/**
 * Delay in milliseconds before showing the IME (Input Method Editor).
 */
private const val IME_SHOW_DELAY_MS = 100L

/**
 * Delay in milliseconds to allow UI to settle before requesting focus.
 */
private const val UI_SETTLE_DELAY_MS = 100L

/**
 * Delay in milliseconds before showing the soft keyboard.
 */
private const val KEYBOARD_SHOW_DELAY_MS = 50L

/**
 * Border width for the terminal display in dp.
 */
private val TERMINAL_BORDER_WIDTH = 2.dp

/**
 * Minimum font size for pinch-to-zoom gesture (sp).
 */
private const val MIN_PINCH_FONT_SP = 6f

/**
 * Maximum font size for pinch-to-zoom gesture (sp).
 */
private const val MAX_PINCH_FONT_SP = 32f

/**
 * Size of the copy button when selection is active in dp.
 */
private val COPY_BUTTON_SIZE = 48.dp

/**
 * Vertical offset for the copy button above the selection in dp.
 */
private val COPY_BUTTON_OFFSET = 48.dp

/**
 * Touch radius in pixels for detecting selection handle touches.
 */
private const val HANDLE_HIT_RADIUS = 80f

/**
 * Vertical offset in dp to position the magnifier above (or below) the finger.
 */
private val MAGNIFIER_VERTICAL_OFFSET = 40.dp

/**
 * Estimated finger contact height in dp, used to avoid positioning the magnifier under the finger.
 */
private val FINGER_HEIGHT_DP = 50.dp

/**
 * Center offset multiplier for magnifier positioning.
 */
private const val MAGNIFIER_CENTER_OFFSET_MULTIPLIER = 1.2f

/**
 * Border width for the magnifier loupe in dp.
 */
private val MAGNIFIER_BORDER_WIDTH = 2.dp

/**
 * Background alpha for the magnifier loupe (0.0 = transparent, 1.0 = opaque).
 */
private const val MAGNIFIER_BACKGROUND_ALPHA = 0.9f

/**
 * Number of rows to display on each side of the touch point in the magnifier.
 */
private const val MAGNIFIER_ROW_RANGE = 3

/**
 * Width of selection handles (teardrop shape) in dp.
 */
private val SELECTION_HANDLE_WIDTH = 24.dp

/**
 * Alpha value for the block cursor.
 */
private const val CURSOR_BLOCK_ALPHA = 0.7f

/**
 * Alpha value for the underline and bar cursors.
 */
private const val CURSOR_LINE_ALPHA = 0.9f

/**
 * Percentage of cell height for underline cursor (0.0 to 1.0).
 */
private const val CURSOR_UNDERLINE_HEIGHT_RATIO = 0.15f

/**
 * Percentage of cell width for bar cursor (0.0 to 1.0).
 */
private const val CURSOR_BAR_WIDTH_RATIO = 0.15f

/**
 * Convergence threshold for binary search when finding optimal font size.
 */
private const val FONT_SIZE_SEARCH_EPSILON = 0.1f

/**
 * Number of wavelengths per character width for the curly underline pattern.
 */
private const val CURLY_UNDERLINE_CYCLES_PER_CHAR = 2f

/**
 * Amplitude (height) of the curly underline pattern in pixels.
 */
private const val CURLY_UNDERLINE_AMPLITUDE = 1.5f

/**
 * Spacing between the two lines in a double underline in pixels.
 */
private const val DOUBLE_UNDERLINE_SPACING = 2f

/**
 * Terminal - A Jetpack Compose terminal screen component.
 *
 * This component:
 * - Renders terminal output using Canvas
 * - Handles terminal resize based on available space
 * - Displays cursor
 * - Supports colors, bold, italic, underline, etc.
 *
 * @param terminalEmulator The terminal emulator containing terminal state
 * @param modifier Modifier for the composable
 * @param typeface Typeface for terminal text (default: Typeface.MONOSPACE)
 * @param initialFontSize Initial font size for terminal text (can be changed with pinch-to-zoom)
 * @param minFontSize Minimum font size for pinch-to-zoom
 * @param maxFontSize Maximum font size for pinch-to-zoom
 * @param backgroundColor Default background color
 * @param foregroundColor Default foreground color
 * @param keyboardEnabled Enable keyboard input handling (default: false for display-only mode).
 *                        When false, no keyboard input (hardware or soft) is accepted.
 * @param showSoftKeyboard Whether to show the soft keyboard/IME (default: true when keyboardEnabled=true).
 *                         Only applies when keyboardEnabled=true. Hardware keyboard always works when keyboardEnabled=true.
 * @param focusRequester Focus requester for keyboard input (if enabled)
 * @param onTerminalTap Callback for a simple tap event on the terminal (when no selection is active)
 * @param onImeVisibilityChanged Callback invoked when IME visibility changes (true = shown, false = hidden)
 * @param forcedSize Force terminal to specific dimensions (rows, cols). When set, font size is calculated to fit.
 * @param onSelectionControllerAvailable Optional callback providing access to the SelectionController for controlling selection mode
 * @param onHyperlinkClick Callback when user taps on an OSC8 hyperlink. Receives the URL as parameter.
 * @param selectionBackgroundColor Background color for selected text (default: 0xFFB3D7FF)
 * @param selectionForegroundColor Foreground color for selected text (default: Black)
 */
@Composable
fun Terminal(
    terminalEmulator: TerminalEmulator,
    modifier: Modifier = Modifier,
    typeface: Typeface = Typeface.MONOSPACE,
    initialFontSize: TextUnit = 11.sp,
    minFontSize: TextUnit = 6.sp,
    maxFontSize: TextUnit = 30.sp,
    backgroundColor: Color = Color.Black,
    foregroundColor: Color = Color.White,
    selectionBackgroundColor: Color = Color(0xFFB3D7FF),
    selectionForegroundColor: Color = Color.Black,
    keyboardEnabled: Boolean = false,
    showSoftKeyboard: Boolean = true,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onTerminalTap: () -> Unit = {},
    onTerminalDoubleTap: () -> Unit = {},
    onImeVisibilityChanged: (Boolean) -> Unit = {},
    forcedSize: Pair<Int, Int>? = null,
    modifierManager: ModifierManager? = null,
    onSelectionControllerAvailable: ((SelectionController) -> Unit)? = null,
    onHyperlinkClick: (String) -> Unit = {},
    onComposeControllerAvailable: ((ComposeController) -> Unit)? = null,
    onFontSizeChanged: ((TextUnit) -> Unit)? = null,
    gestureCallback: TerminalGestureCallback? = null,
    allowStandardKeyboard: Boolean = false,
    rawKeyboardMode: Boolean = false,
    onPasteShortcut: (() -> Unit)? = null,
    onPasteRequest: (() -> Unit)? = null,
    rightAltMode: RightAltMode = RightAltMode.CharacterModifier,
    delKeyMode: DelKeyMode = DelKeyMode.Delete,
) {
    if (LocalInspectionMode.current) {
        TerminalPreview(modifier, backgroundColor, foregroundColor)
        return
    }

    TerminalWithAccessibility(
        terminalEmulator = terminalEmulator,
        modifier = modifier,
        typeface = typeface,
        initialFontSize = initialFontSize,
        minFontSize = minFontSize,
        maxFontSize = maxFontSize,
        backgroundColor = backgroundColor,
        foregroundColor = foregroundColor,
        keyboardEnabled = keyboardEnabled,
        showSoftKeyboard = showSoftKeyboard,
        focusRequester = focusRequester,
        onTerminalTap = onTerminalTap,
        onTerminalDoubleTap = onTerminalDoubleTap,
        onImeVisibilityChanged = onImeVisibilityChanged,
        forcedSize = forcedSize,
        modifierManager = modifierManager,
        onSelectionControllerAvailable = onSelectionControllerAvailable,
        onHyperlinkClick = onHyperlinkClick,
        onComposeControllerAvailable = onComposeControllerAvailable,
        onFontSizeChanged = onFontSizeChanged,
        gestureCallback = gestureCallback,
        allowStandardKeyboard = allowStandardKeyboard,
        rawKeyboardMode = rawKeyboardMode,
        onPasteShortcut = onPasteShortcut,
        onScrollControllerAvailable = null,
        onPasteRequest = onPasteRequest,
        rightAltMode = rightAltMode,
        selectionBackgroundColor = selectionBackgroundColor,
        selectionForegroundColor = selectionForegroundColor,
        delKeyMode = delKeyMode,
    )
}

/**
 * Used for testing accessibility.
 *
 * @see Terminal
 */
@VisibleForTesting
@Composable
internal fun TerminalWithAccessibility(
    terminalEmulator: TerminalEmulator,
    modifier: Modifier = Modifier,
    typeface: Typeface = Typeface.MONOSPACE,
    initialFontSize: TextUnit = 11.sp,
    minFontSize: TextUnit = 6.sp,
    maxFontSize: TextUnit = 30.sp,
    backgroundColor: Color = Color.Black,
    foregroundColor: Color = Color.White,
    keyboardEnabled: Boolean = false,
    showSoftKeyboard: Boolean = true,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onTerminalTap: () -> Unit = {},
    onTerminalDoubleTap: () -> Unit = {},
    onImeVisibilityChanged: (Boolean) -> Unit = {},
    forcedSize: Pair<Int, Int>? = null,
    modifierManager: ModifierManager? = null,
    forceAccessibilityEnabled: Boolean? = null,
    onSelectionControllerAvailable: ((SelectionController) -> Unit)? = null,
    onHyperlinkClick: (String) -> Unit = {},
    onComposeControllerAvailable: ((ComposeController) -> Unit)? = null,
    onFontSizeChanged: ((TextUnit) -> Unit)? = null,
    gestureCallback: TerminalGestureCallback? = null,
    allowStandardKeyboard: Boolean = false,
    rawKeyboardMode: Boolean = false,
    onPasteShortcut: (() -> Unit)? = null,
    onScrollControllerAvailable: ((ScrollController) -> Unit)? = null,
    onPasteRequest: (() -> Unit)? = null,
    rightAltMode: RightAltMode = RightAltMode.CharacterModifier,
    selectionBackgroundColor: Color = Color(0xFFB3D7FF),
    selectionForegroundColor: Color = Color.Black,
    delKeyMode: DelKeyMode = DelKeyMode.Delete,
) {
    if (terminalEmulator !is TerminalEmulatorImpl) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            Text("Unknown TerminalEmulator type")
        }
        return
    }

    val terminalEmulator: TerminalEmulatorImpl = terminalEmulator

    // Remember updated callbacks to avoid stale lambdas inside pointerInput
    val currentOnTerminalTap by rememberUpdatedState(onTerminalTap)
    val currentOnHyperlinkClick by rememberUpdatedState(onHyperlinkClick)

    val density = LocalDensity.current
    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    // Track accessibility state - only enable accessibility features when needed
    val systemAccessibilityEnabled by rememberAccessibilityState()
    val accessibilityEnabled = forceAccessibilityEnabled ?: systemAccessibilityEnabled

    // Observe terminal state via StateFlow
    val screenState = rememberTerminalScreenState(terminalEmulator)

    // Keyboard handler (will be updated with selectionController after it's created)
    val keyboardHandler = remember(terminalEmulator) {
        KeyboardHandler(terminalEmulator, modifierManager)
    }
    keyboardHandler.rightAltMode = rightAltMode
    keyboardHandler.delKeyMode = delKeyMode

    // Re-bind the paste shortcut callback whenever the host swaps it
    // (e.g. when the user toggles the preference). Reading null here
    // releases the binding so V keys flow through unchanged.
    SideEffect {
        keyboardHandler.onPasteShortcut = onPasteShortcut
    }

    // Font size state (pinch-to-zoom adjusts this directly)
    var calculatedFontSize by remember(terminalEmulator) { mutableStateOf(initialFontSize) }
    // Track whether a pinch gesture set the font size, to avoid the
    // initialFontSize LaunchedEffect overwriting it before the preference
    // round-trips back.
    var fontSetByPinch by remember(terminalEmulator) { mutableStateOf(false) }

    // Magnifying glass state
    var showMagnifier by remember(terminalEmulator) { mutableStateOf(false) }
    var magnifierPosition by remember(terminalEmulator) { mutableStateOf(Offset.Zero) }

    // Cursor blink state
    var cursorBlinkVisible by remember(terminalEmulator) { mutableStateOf(true) }

    // IME text field state (hidden BasicTextField for capturing IME input)
    val imeFocusRequester = remember { FocusRequester() }

    // Review Mode state for accessibility
    var isReviewMode by remember(terminalEmulator) { mutableStateOf(false) }
    val reviewFocusRequester = remember { FocusRequester() }

    // Manage focus and IME visibility
    // Determine if IME should be shown:
    // 1. keyboardEnabled is true (master switch)
    // 2. showSoftKeyboard is true (user wants IME visible)
    val shouldShowIme = keyboardEnabled && showSoftKeyboard

    // Keep reference to ImeInputView for controlling IME
    var imeInputView by remember { mutableStateOf<ImeInputView?>(null) }

    // Live IME composition text (romaji mid-conversion, pinyin candidates).
    // Rendered as a floating overlay near the terminal cursor so the partial
    // text never has to be projected into the terminal itself — avoids the
    // backspace-and-rewrite flicker that inline projection produced during
    // CJK conversion, and avoids paying per-keystroke round-trips over mosh.
    val imeComposerText by (imeInputView?.composingText
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow("") }).collectAsState()

    // Cleanup IME when component is disposed
    DisposableEffect(imeInputView) {
        onDispose {
            Log.d("Terminal", "Disposing Terminal - hiding IME")
            imeInputView?.hideIme()
        }
    }

    // React to IME state changes
    LaunchedEffect(shouldShowIme, imeInputView) {
        Log.d("Terminal", "IME state changed: shouldShowIme=$shouldShowIme (imeInputView=$imeInputView)")

        imeInputView?.let { view ->
            if (shouldShowIme) {
                Log.d("Terminal", "Showing IME via InputMethodManager")
                delay(IME_SHOW_DELAY_MS)
                view.showIme()
                Log.d("Terminal", "IME show completed")
                onImeVisibilityChanged(true)
            } else {
                Log.d("Terminal", "Hiding IME via InputMethodManager")
                view.hideIme()
                Log.d("Terminal", "IME hide completed")
                onImeVisibilityChanged(false)
            }
        }
    }

    // Manage focus based on Review Mode
    LaunchedEffect(isReviewMode) {
        if (isReviewMode) {
            // Entering Review Mode: hide keyboard, focus on accessibility overlay
            keyboardController?.hide()
            delay(UI_SETTLE_DELAY_MS)
            try {
                reviewFocusRequester.requestFocus()
            } catch (_: IllegalStateException) {
                // Focus requester not attached yet, ignore
            }
        } else {
            // Exiting Review Mode: return focus to input field if keyboard enabled
            if (keyboardEnabled && shouldShowIme) {
                delay(UI_SETTLE_DELAY_MS)
                imeFocusRequester.requestFocus()
                delay(KEYBOARD_SHOW_DELAY_MS)
                keyboardController?.show()
            }
        }
    }

    // Cursor blink animation
    LaunchedEffect(
        screenState.snapshot.cursorVisible,
        screenState.snapshot.cursorBlink,
        screenState.snapshot.cursorRow,
        screenState.snapshot.cursorCol,
    ) {
        if (screenState.snapshot.cursorVisible) {
            cursorBlinkVisible = true
            if (screenState.snapshot.cursorBlink) {
                // Show cursor immediately when it moves or becomes visible
                while (true) {
                    delay(CURSOR_BLINK_RATE_MS)
                    cursorBlinkVisible = !cursorBlinkVisible
                }
            }
        } else {
            cursorBlinkVisible = false
        }
    }

    // Create TextPaint for measuring and drawing (base size)
    val textPaint = remember(typeface, calculatedFontSize) {
        TextPaint().apply {
            this.typeface = typeface
            textSize = with(density) { calculatedFontSize.toPx() }
            isAntiAlias = true
        }
    }

    // Base character dimensions (unzoomed)
    val baseCharWidth = remember(textPaint) {
        textPaint.measureText("M")
    }

    // Snap to integer pixels so adjacent rows share a pixel boundary; otherwise
    // fractional row pitch leaves a gap between background rects (issue #2069).
    val baseCharHeight = remember(textPaint) {
        val metrics = textPaint.fontMetrics
        val height = ceil(metrics.descent - metrics.ascent)
        if (height <= 0f) 20f else height // Fallback for tests
    }

    val baseCharBaseline = remember(textPaint) {
        ceil(-textPaint.fontMetrics.ascent)
    }

    // Scroll animation state
    val scrollOffset = remember(terminalEmulator) { Animatable(0f) }
    val maxScroll = remember(screenState.snapshot.scrollback.size, baseCharHeight) {
        screenState.snapshot.scrollback.size * baseCharHeight
    }
    LaunchedEffect(maxScroll) {
        scrollOffset.updateBounds(0f, maxScroll)
    }

    // Selection manager
    val selectionManager = remember(terminalEmulator) {
        SelectionManager()
    }

    // Selection controller - expose API for external control
    val selectionController = remember(terminalEmulator, selectionManager) {
        object : SelectionController {
            override val isSelectionActive: Boolean
                get() = selectionManager.mode != SelectionMode.NONE

            override fun startSelection(mode: SelectionMode) {
                if (selectionManager.mode == SelectionMode.NONE) {
                    // Start at cursor position or center of screen
                    val row = screenState.snapshot.cursorRow.coerceIn(0, screenState.snapshot.rows - 1)
                    val col = screenState.snapshot.cursorCol.coerceIn(0, screenState.snapshot.cols - 1)
                    selectionManager.startSelection(row, col, screenState.snapshot.cols, mode, screenState.snapshot, screenState.scrollbackPosition)
                }
            }

            override fun toggleSelection() {
                if (selectionManager.mode == SelectionMode.NONE) {
                    startSelection()
                } else {
                    clearSelection()
                }
            }

            override fun moveSelectionUp() {
                selectionManager.moveSelectionUp(screenState.snapshot.rows)
            }

            override fun moveSelectionDown() {
                selectionManager.moveSelectionDown(screenState.snapshot.rows)
            }

            override fun moveSelectionLeft() {
                selectionManager.moveSelectionLeft(screenState.snapshot.cols)
            }

            override fun moveSelectionRight() {
                selectionManager.moveSelectionRight(screenState.snapshot.cols)
            }

            override fun toggleSelectionMode() {
                selectionManager.toggleMode(screenState.snapshot.cols, screenState.snapshot, screenState.scrollbackPosition)
            }

            override fun setSelectionMode(mode: SelectionMode) {
                selectionManager.setMode(mode, screenState.snapshot.cols, screenState.snapshot, screenState.scrollbackPosition)
            }

            override fun selectAll() {
                selectionManager.selectAll(screenState.snapshot.rows, screenState.snapshot.cols)
            }

            override fun finishSelection() {
                selectionManager.endSelection()
            }

            override fun copySelection(): String {
                val text = selectionManager.getSelectedText(screenState.snapshot, screenState.scrollbackPosition)
                if (text.isNotEmpty()) {
                    clipboardManager.setText(AnnotatedString(text))
                    selectionManager.clearSelection()
                }
                return text
            }

            override fun clearSelection() {
                selectionManager.clearSelection()
            }

            override fun updateSelectionStart(row: Int, col: Int) {
                selectionManager.updateSelectionStart(row, col)
            }

            override fun updateSelectionEnd(row: Int, col: Int) {
                selectionManager.updateSelectionEnd(row, col)
            }

            override fun getSelectionRange(): SelectionRange? {
                return selectionManager.selectionRange
            }
        }
    }

    // Compose mode state
    val composeMode = remember(terminalEmulator) {
        ComposeMode()
    }

    // Compose controller - expose API for external control
    val composeController = remember(terminalEmulator, composeMode, selectionController) {
        object : ComposeController {
            override val isComposeModeActive: Boolean
                get() = composeMode.isActive

            override fun startComposeMode() {
                selectionController.clearSelection()
                composeMode.activate()
            }

            override fun stopComposeMode() {
                composeMode.cancel()
            }

            override fun toggleComposeMode() {
                if (composeMode.isActive) {
                    stopComposeMode()
                } else {
                    startComposeMode()
                }
            }

            override fun getComposedText(): String = composeMode.buffer

            override val pendingDeadChar: Int
                get() = keyboardHandler.pendingDeadChar
        }
    }

    // Wire compose mode into keyboard handler
    LaunchedEffect(composeMode) {
        keyboardHandler.composeMode = composeMode
    }

    // Provide selection controller to caller and keyboard handler
    LaunchedEffect(selectionController) {
        keyboardHandler.selectionController = selectionController
        onSelectionControllerAvailable?.invoke(selectionController)
    }

    // Provide compose controller to caller
    LaunchedEffect(composeController) {
        onComposeControllerAvailable?.invoke(composeController)
    }

    // Scroll controller implementation
    val scrollController = remember(screenState, scrollOffset, scope, maxScroll) {
        object : ScrollController {
            override val scrollbackPosition: Int
                get() = screenState.scrollbackPosition

            override val maxScrollback: Int
                get() = screenState.snapshot.scrollback.size

            override fun scrollToBottom() {
                screenState.scrollToBottom()
                scope.launch {
                    scrollOffset.snapTo(0f)
                }
            }

            override fun scrollToTop() {
                screenState.scrollToTop()
                scope.launch {
                    scrollOffset.snapTo(maxScroll)
                }
            }

            override fun scrollBy(lines: Int) {
                val newPosition = (screenState.scrollbackPosition + lines)
                    .coerceIn(0, maxScrollback)
                screenState.scrollBy(newPosition - screenState.scrollbackPosition)
                scope.launch {
                    scrollOffset.snapTo(newPosition * baseCharHeight)
                }
            }
        }
    }

    // Provide scroll controller to caller
    LaunchedEffect(scrollController) {
        onScrollControllerAvailable?.invoke(scrollController)
    }

    // Sync compose mode active state to ImeInputView so onCreateInputConnection returns the
    // correct outAttrs (and restartInput is called to apply the change).
    LaunchedEffect(composeMode.isActive, imeInputView) {
        imeInputView?.isComposeModeActive = composeMode.isActive
    }

    // Coroutine scope for animations
    val coroutineScope = rememberCoroutineScope()

    // Last tap tracking for double-tap detection.
    // Stored as plain vars in a remembered holder so writes don't trigger recomposition.
    val tapTracker = remember(terminalEmulator) {
        object {
            var lastTimestamp = 0L
            var lastPosition = Offset.Zero
        }
    }

    // Setup keyboard input callback to reset scroll position
    LaunchedEffect(screenState, scrollOffset) {
        keyboardHandler.onInputProcessed = {
            screenState.scrollToBottom()
            coroutineScope.launch {
                scrollOffset.snapTo(0f)
            }
        }
    }
    val viewConfiguration = LocalViewConfiguration.current

    var availableWidth by remember { mutableStateOf(0) }
    var availableHeight by remember { mutableStateOf(0) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged {
                availableWidth = it.width
                availableHeight = it.height
            }
            .then(
                if (keyboardEnabled) {
                    Modifier
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            // In Review Mode, let accessibility system handle navigation keys
                            if (isReviewMode) {
                                // Allow arrow keys, Page Up/Down to navigate accessibility tree
                                when (event.key) {
                                    Key.DirectionUp,
                                    Key.DirectionDown,
                                    Key.DirectionLeft,
                                    Key.DirectionRight,
                                    Key.PageUp,
                                    Key.PageDown,
                                    -> false

                                    // Don't consume - let system handle
                                    else -> {
                                        // Any other key exits Review Mode and goes to shell
                                        isReviewMode = false
                                        keyboardHandler.onKeyEvent(event)
                                    }
                                }
                            } else {
                                // Input Mode: send all keys to shell
                                keyboardHandler.onKeyEvent(event)
                            }
                        }
                } else {
                    Modifier
                },
            ),
    ) {
        // Calculate font size if forcedSize is specified
        if (forcedSize != null) {
            val (forcedRows, forcedCols) = forcedSize
            LaunchedEffect(availableWidth, availableHeight, forcedRows, forcedCols) {
                if (availableWidth == 0 || availableHeight == 0) {
                    return@LaunchedEffect
                }

                val optimalSize = findOptimalFontSize(
                    targetRows = forcedRows,
                    targetCols = forcedCols,
                    availableWidth = availableWidth,
                    availableHeight = availableHeight,
                    minSize = minFontSize.value,
                    maxSize = maxFontSize.value,
                    typeface = typeface,
                    density = density.density,
                )
                calculatedFontSize = optimalSize.sp
            }
        } else {
            // Sync from external font size changes (e.g. settings slider).
            // Skip if a pinch gesture just set the size — the preference
            // will round-trip back as a new initialFontSize momentarily.
            LaunchedEffect(initialFontSize) {
                if (fontSetByPinch) {
                    fontSetByPinch = false
                } else if (calculatedFontSize != initialFontSize) {
                    calculatedFontSize = initialFontSize
                }
            }
        }

        // Resize terminal when dimensions change
        LaunchedEffect(terminalEmulator, availableWidth, availableHeight, forcedSize, baseCharWidth, baseCharHeight) {
            if (availableWidth == 0 || availableHeight == 0 || baseCharWidth <= 0f || baseCharHeight <= 0f) {
                return@LaunchedEffect
            }

            // Use base dimensions for terminal sizing (not zoomed dimensions)
            val newCols =
                forcedSize?.second ?: charsPerDimension(availableWidth, baseCharWidth)
            val newRows =
                forcedSize?.first ?: charsPerDimension(availableHeight, baseCharHeight)

            val dimensions = terminalEmulator.dimensions
            if (newRows != dimensions.rows || newCols != dimensions.columns) {
                terminalEmulator.resize(newRows, newCols)

                // If selection is active, ensure it stays within the new visible bounds.
                // This ensures the Copy button resets to the last visible line when the screen
                // shrinks (e.g. keyboard up) without forcing a scroll to the bottom.
                if (selectionManager.mode != SelectionMode.NONE) {
                    selectionManager.clampToDimensions(newRows, newCols)
                }
            }
        }

        // Use base dimensions for terminal sizing (not zoomed dimensions)
        val newCols =
            forcedSize?.second ?: charsPerDimension(availableWidth, baseCharWidth)
        val newRows =
            forcedSize?.first ?: charsPerDimension(availableHeight, baseCharHeight)

        // Auto-scroll to bottom when new content arrives (if not manually scrolled).
        //
        // Two cases where we need to snap back to the bottom:
        // 1. scrollbackPosition drifted to a small non-zero value due to a brief
        //    accidental touch (gesture set it to 1–2 lines, then updateSnapshot
        //    adjusted it upward as scrollback grew). Without this, the terminal
        //    stays stuck showing stale scrollback content mixed with the current
        //    screen — visible as green blocks and garbled text during animations.
        // 2. scrollbackPosition became non-zero due to updateSnapshot adjusting
        //    it after a resize that changed scrollback size.
        //
        // We auto-scroll only when the user is NOT actively scrolling (scrollOffset
        // animation not running) and the position is "close" to the bottom — a
        // small drift from 0 that the user didn't intentionally create by a long
        // sustained scroll gesture.
        LaunchedEffect(screenState.snapshot.lines.size, screenState.snapshot.scrollback.size) {
            val pos = screenState.scrollbackPosition
            if (pos in 1..3 && !scrollOffset.isRunning) {
                // Small drift — snap back to bottom
                screenState.scrollToBottom()
                scrollOffset.snapTo(0f)
            }
        }

        // Sync scrollOffset when scrollbackPosition changes externally (but not during user scrolling)
        LaunchedEffect(screenState.scrollbackPosition) {
            val targetOffset = screenState.scrollbackPosition * baseCharHeight
            if (!scrollOffset.isRunning && scrollOffset.value != targetOffset) {
                scrollOffset.snapTo(targetOffset)
            }
        }

        // Calculate actual terminal dimensions in pixels
        val terminalWidthPx = newCols * baseCharWidth
        val terminalHeightPx = newRows * baseCharHeight

        // Draw terminal content with context menu overlay
        Box(
            modifier = (if (forcedSize != null) {
                // Add border outside the terminal content
                Modifier
                    .size(
                        width = with(density) { terminalWidthPx.toDp() },
                        height = with(density) { terminalHeightPx.toDp() }
                    )
                    .border(
                        width = TERMINAL_BORDER_WIDTH,
                        color = Color(0xFF4CAF50).copy(alpha = 0.6f)
                    )
            } else {
                Modifier.fillMaxSize()
            }).pointerInput(terminalEmulator, gestureCallback) {
                val touchSlopSquared =
                    viewConfiguration.touchSlop * viewConfiguration.touchSlop
                var lastMultiTouchTime = 0L
                var lastTapTime = 0L
                coroutineScope {
                    awaitEachGesture {
                        var gestureType: GestureType = GestureType.Undetermined
                        val down = awaitFirstDown(requireUnconsumed = false)

                        // Suppress taps/swipes for a window after pinch-to-zoom ends
                        val sincePinch = System.currentTimeMillis() - lastMultiTouchTime
                        val inPinchCooldown = sincePinch < MULTITOUCH_LIFTOFF_MS

                        // 1. Check if touching a selection handle first
                        if (gestureType == GestureType.Undetermined && selectionManager.mode != SelectionMode.NONE && !selectionManager.isSelecting) {
                            val range = selectionManager.selectionRange
                            if (range != null) {
                                val (touchingStart, touchingEnd) = isTouchingHandle(
                                    down.position,
                                    range,
                                    baseCharWidth,
                                    baseCharHeight,
                                )
                                if (touchingStart || touchingEnd) {
                                    gestureType = GestureType.HandleDrag
                                    // Handle drag
                                    showMagnifier = true
                                    magnifierPosition = down.position

                                    // Local variable to keep track of which handle we are moving in case they cross
                                    var isMovingStart = touchingStart

                                    drag(down.id) { change ->
                                        val newCol =
                                            (change.position.x / baseCharWidth).toInt()
                                                .coerceIn(0, screenState.snapshot.cols - 1)
                                        val newRow =
                                            (change.position.y / baseCharHeight).toInt()
                                                .coerceIn(0, screenState.snapshot.rows - 1)

                                        val current = selectionManager.selectionRange
                                        if (current != null) {
                                            val result = applyHandleDrag(
                                                startRow = current.startRow,
                                                startCol = current.startCol,
                                                endRow = current.endRow,
                                                endCol = current.endCol,
                                                isMovingStart = isMovingStart,
                                                newRow = newRow,
                                                newCol = newCol,
                                            )
                                            isMovingStart = result.isMovingStart
                                            selectionManager.updateSelectionStart(result.startRow, result.startCol)
                                            selectionManager.updateSelectionEnd(result.endRow, result.endCol)
                                            selectionManager.adjustSelectionForMode(
                                                screenState.snapshot.cols,
                                                screenState.snapshot,
                                                screenState.scrollbackPosition,
                                            )
                                        }

                                        magnifierPosition = change.position
                                        change.consume()
                                    }

                                    // After lifting finger, ensure selection is fully adjusted and handles snap
                                    selectionManager.adjustSelectionForMode(
                                        screenState.snapshot.cols,
                                        screenState.snapshot,
                                        screenState.scrollbackPosition,
                                    )

                                    showMagnifier = false
                                    // Don't auto-show menu again after dragging handle
                                    return@awaitEachGesture
                                }
                            }
                        }

                        // 2. Long press detection
                        // When gestureCallback is present, fire onLongPress at 400ms
                        // then wait until 1000ms for selection start.
                        // When no callback, use the system long-press timeout for selection.
                        var longPressDetected = false
                        var gestureEnded = false
                        var callbackLongPressFired = false

                        val callbackLongPressJob = if (gestureCallback != null) {
                            launch {
                                delay(CALLBACK_LONG_PRESS_MS)
                                if (gestureType == GestureType.Undetermined && !gestureEnded) {
                                    val col = (down.position.x / baseCharWidth).toInt()
                                        .coerceIn(0, screenState.snapshot.cols - 1)
                                    val row = (down.position.y / baseCharHeight).toInt()
                                        .coerceIn(0, screenState.snapshot.rows - 1)
                                    callbackLongPressFired = true
                                    gestureCallback.onLongPress(col, row)
                                }
                            }
                        } else null

                        val longPressJob = launch {
                            delay(
                                if (gestureCallback != null) MOUSE_MODE_SELECTION_DELAY_MS
                                else viewConfiguration.longPressTimeoutMillis
                            )
                            if (gestureType == GestureType.Undetermined &&
                                selectionManager.mode == SelectionMode.NONE &&
                                !gestureEnded
                            ) {
                                longPressDetected = true
                                gestureType = GestureType.Selection

                                // Start selection
                                val col = (down.position.x / baseCharWidth).toInt()
                                    .coerceIn(0, screenState.snapshot.cols - 1)
                                val row = (down.position.y / baseCharHeight).toInt()
                                    .coerceIn(0, screenState.snapshot.rows - 1)
                                selectionManager.startSelection(
                                    row,
                                    col,
                                    screenState.snapshot.cols,
                                    SelectionMode.CHARACTER,
                                )
                                showMagnifier = true
                                magnifierPosition = down.position
                            }
                        }

                        // 3. Check for multi-touch (zoom)
                        val secondPointer = withTimeoutOrNull(
                            WAIT_FOR_SECOND_TOUCH_MS
                        ) {
                            awaitPointerEvent().changes.firstOrNull { it.id != down.id && it.pressed }
                        }

                        if (secondPointer != null && forcedSize == null) {
                            longPressJob.cancel()
                            callbackLongPressJob?.cancel()
                            gestureType = GestureType.Zoom

                            // Pinch-to-zoom: adjust font size directly
                            val startFontSize = calculatedFontSize.value
                            var cumulativeZoom = 1f

                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.all { !it.pressed }) break

                                if (event.changes.size > 1) {
                                    cumulativeZoom *= event.calculateZoom()
                                    val newSize = (startFontSize * cumulativeZoom)
                                        .coerceIn(MIN_PINCH_FONT_SP, MAX_PINCH_FONT_SP)
                                    calculatedFontSize = newSize.sp

                                    event.changes.forEach { it.consume() }
                                }
                            }

                            // Persist the new font size; flag prevents the
                            // LaunchedEffect from resetting before round-trip.
                            fontSetByPinch = true
                            onFontSizeChanged?.invoke(calculatedFontSize)
                            lastMultiTouchTime = System.currentTimeMillis()

                            return@awaitEachGesture
                        }

                        // 4. Track velocity for scroll fling
                        val velocityTracker = VelocityTracker()
                        velocityTracker.addPosition(down.uptimeMillis, down.position)

                        // 5. Event loop for single-touch gestures
                        // Track accumulated scroll for callback quantization
                        var accumulatedScrollY = 0f
                        // Track whether this is a horizontal drag (tab swipe)
                        var isHorizontalDrag = false
                        // Last cell coordinates dispatched as a MouseDrag(Move),
                        // used to quantize per-pixel events down to per-cell.
                        var lastMouseDragCol = -1
                        var lastMouseDragRow = -1

                        while (true) {
                            val event: PointerEvent =
                                awaitPointerEvent(PointerEventPass.Main)
                            if (event.changes.all { !it.pressed }) break

                            val change = event.changes.first()
                            velocityTracker.addPosition(
                                change.uptimeMillis,
                                change.position
                            )
                            // Use raw position delta — positionChange() may return
                            // zero if pagerSwipeOverride consumed on Initial pass.
                            val dragAmount = change.position - change.previousPosition

                            // Determine gesture if still undetermined.
                            // Use total distance from touch-down (not per-frame delta)
                            // so moderate-speed scrolling still triggers classification.
                            // Skip once long-press or callback has fired — the user is
                            // holding intentionally and movement should extend selection.
                            if (gestureType == GestureType.Undetermined &&
                                !longPressDetected && !callbackLongPressFired
                            ) {
                                val totalDx = change.position.x - down.position.x
                                val totalDy = change.position.y - down.position.y
                                if (totalDx * totalDx + totalDy * totalDy > touchSlopSquared) {
                                    val absDx = kotlin.math.abs(totalDx)
                                    val absDy = kotlin.math.abs(totalDy)
                                    if (absDx > absDy) {
                                        // Horizontal drag — clear stale selection, let pager handle
                                        isHorizontalDrag = true
                                        longPressJob.cancel()
                                        callbackLongPressJob?.cancel()
                                        if (selectionManager.mode != SelectionMode.NONE) {
                                            selectionManager.clearSelection()
                                        }
                                    } else {
                                        longPressJob.cancel()
                                        callbackLongPressJob?.cancel()
                                        // Mouse-mode drag: offer the gesture to the
                                        // callback first so the remote (tmux/zellij/...)
                                        // can own the selection across its own scrollback.
                                        // The callback decides per-call whether to claim
                                        // (e.g. gated by a user setting); falling through
                                        // to Scroll preserves the legacy drag-to-scroll-
                                        // tmux behaviour.  (#94)
                                        val downCol = (down.position.x / baseCharWidth).toInt()
                                            .coerceIn(0, screenState.snapshot.cols - 1)
                                        val downRow = (down.position.y / baseCharHeight).toInt()
                                            .coerceIn(0, screenState.snapshot.rows - 1)
                                        val claimed = gestureCallback
                                            ?.onMouseDrag(downCol, downRow, MouseDragPhase.Start)
                                            ?: false
                                        gestureType = if (claimed) {
                                            GestureType.MouseDrag
                                        } else {
                                            GestureType.Scroll
                                        }
                                        // Clear any active selection when scrolling/dragging starts
                                        if (selectionManager.mode != SelectionMode.NONE) {
                                            selectionManager.clearSelection()
                                        }
                                    }
                                }
                            }

                            // Handle based on gesture type
                            when (gestureType) {
                                GestureType.Selection -> {
                                    if (selectionManager.isSelecting) {
                                        val dragCol =
                                            (change.position.x / baseCharWidth).toInt()
                                                .coerceIn(0, screenState.snapshot.cols - 1)
                                        val dragRow =
                                            (change.position.y / baseCharHeight).toInt()
                                                .coerceIn(0, screenState.snapshot.rows - 1)
                                        selectionManager.updateSelection(
                                            dragRow,
                                            dragCol
                                        )
                                        magnifierPosition = change.position

                                        // Edge-scroll: when dragging near top/bottom
                                        // during selection, fire scroll callback so TUI
                                        // apps scroll the underlying content.
                                        if (gestureCallback != null) {
                                            val relY = change.position.y /
                                                (screenState.snapshot.rows * baseCharHeight)
                                            if (relY < EDGE_SCROLL_ZONE || relY > 1f - EDGE_SCROLL_ZONE) {
                                                val scrollUp = relY < EDGE_SCROLL_ZONE
                                                gestureCallback.onScroll(dragCol, dragRow, scrollUp)
                                            }
                                        }
                                    }
                                }

                                GestureType.MouseDrag -> {
                                    // Quantize motion to cell boundaries — the remote
                                    // (tmux et al.) only cares about cell-resolution
                                    // changes; sending per-pixel events would flood the
                                    // wire and make tmux's selection-extension stutter.
                                    val dragCol = (change.position.x / baseCharWidth).toInt()
                                        .coerceIn(0, screenState.snapshot.cols - 1)
                                    val dragRow = (change.position.y / baseCharHeight).toInt()
                                        .coerceIn(0, screenState.snapshot.rows - 1)
                                    if (dragCol != lastMouseDragCol || dragRow != lastMouseDragRow) {
                                        gestureCallback?.onMouseDrag(dragCol, dragRow, MouseDragPhase.Move)
                                        lastMouseDragCol = dragCol
                                        lastMouseDragRow = dragRow
                                    }
                                    // Edge-zone wheel events: same shape as Selection's
                                    // edge-scroll, so tmux's copy-mode auto-scroll +
                                    // selection-extension fires when the finger reaches
                                    // the top/bottom of the viewport.
                                    if (gestureCallback != null) {
                                        val relY = change.position.y /
                                            (screenState.snapshot.rows * baseCharHeight)
                                        if (relY < EDGE_SCROLL_ZONE || relY > 1f - EDGE_SCROLL_ZONE) {
                                            val scrollUp = relY < EDGE_SCROLL_ZONE
                                            gestureCallback.onScroll(dragCol, dragRow, scrollUp)
                                        }
                                    }
                                }

                                GestureType.Scroll -> {
                                    if (gestureCallback != null) {
                                        // Quantized scroll: accumulate drag and fire
                                        // callback for each SCROLL_THRESHOLD_PX crossed
                                        accumulatedScrollY += dragAmount.y
                                        while (kotlin.math.abs(accumulatedScrollY) >= SCROLL_THRESHOLD_PX) {
                                            val draggedUp = accumulatedScrollY < 0
                                            accumulatedScrollY += if (draggedUp) SCROLL_THRESHOLD_PX else -SCROLL_THRESHOLD_PX
                                            // Natural scrolling: finger down = scroll up (older content)
                                            val scrollUp = !draggedUp
                                            val col = (change.position.x / baseCharWidth).toInt()
                                                .coerceIn(0, screenState.snapshot.cols - 1)
                                            val row = (change.position.y / baseCharHeight).toInt()
                                                .coerceIn(0, screenState.snapshot.rows - 1)
                                            val consumed = gestureCallback.onScroll(col, row, scrollUp)
                                            if (!consumed) {
                                                // Callback didn't handle it — do scrollback
                                                val scrollDir = if (scrollUp) 1 else -1
                                                screenState.scrollBy(scrollDir)
                                                coroutineScope.launch {
                                                    scrollOffset.snapTo(
                                                        screenState.scrollbackPosition * baseCharHeight
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        // No callback: smooth pixel-level scrollback
                                        val newOffset = (scrollOffset.value + dragAmount.y)
                                            .coerceIn(0f, maxScroll)
                                        coroutineScope.launch {
                                            scrollOffset.snapTo(newOffset)
                                        }

                                        // Update terminal buffer scrollback position
                                        val scrolledLines =
                                            (newOffset / baseCharHeight).toInt()
                                        screenState.scrollBy(scrolledLines - screenState.scrollbackPosition)
                                    }
                                }

                                else -> {}
                            }

                            // Always consume on Main pass to prevent the
                            // HorizontalPager's scrollable from intercepting
                            // drags. Tab swiping still works because the
                            // pagerSwipeOverride runs on Initial pass (before Main).
                            change.consume()
                        }

                        // 6. Gesture ended - cleanup
                        gestureEnded = true
                        longPressJob.cancel()
                        callbackLongPressJob?.cancel()
                        when (gestureType) {
                            GestureType.Scroll -> {
                                // Flush any remaining accumulated scroll that didn't
                                // reach the threshold — ensures small flicks register.
                                if (gestureCallback != null && kotlin.math.abs(accumulatedScrollY) > SCROLL_THRESHOLD_PX / 3f) {
                                    val scrollUp = accumulatedScrollY > 0  // natural: positive drag = scroll up
                                    val col = (down.position.x / baseCharWidth).toInt()
                                        .coerceIn(0, screenState.snapshot.cols - 1)
                                    val row = (down.position.y / baseCharHeight).toInt()
                                        .coerceIn(0, screenState.snapshot.rows - 1)
                                    val consumed = gestureCallback.onScroll(col, row, scrollUp)
                                    if (!consumed) {
                                        val scrollDir = if (scrollUp) 1 else -1
                                        screenState.scrollBy(scrollDir)
                                        coroutineScope.launch {
                                            scrollOffset.snapTo(
                                                screenState.scrollbackPosition * baseCharHeight
                                            )
                                        }
                                    }
                                } else if (gestureCallback == null) {
                                    // Apply fling animation (only for non-callback scrollback)
                                    val velocity = velocityTracker.calculateVelocity()
                                    coroutineScope.launch {
                                        var targetValue = scrollOffset.targetValue
                                        scrollOffset.animateDecay(
                                            initialVelocity = velocity.y,
                                            animationSpec = splineBasedDecay(density)
                                        ) {
                                            targetValue = value.coerceIn(0f, maxScroll)
                                            // Update terminal buffer during animation
                                            val scrolledLines =
                                                (targetValue / baseCharHeight).toInt()
                                            screenState.scrollBy(scrolledLines - screenState.scrollbackPosition)
                                        }

                                        // Snap to final clamped position.
                                        // If we settled within 2 lines of the bottom, snap
                                        // fully to 0 — prevents accidental micro-flicks from
                                        // leaving the terminal stuck 1–2 lines scrolled up,
                                        // which causes garbled display during animations.
                                        val threshold = 2f * baseCharHeight
                                        scrollOffset.snapTo(targetValue.coerceIn(0f, maxScroll))
                                        if (targetValue <= threshold) {
                                            screenState.scrollToBottom()
                                            scrollOffset.snapTo(0f)
                                        }
                                    }
                                }
                            }

                            GestureType.Selection -> {
                                showMagnifier = false
                                if (selectionManager.isSelecting) {
                                    selectionManager.endSelection()
                                }
                            }

                            GestureType.MouseDrag -> {
                                // Use the last dispatched cell if we have one,
                                // otherwise fall back to the current pointer
                                // position (covers the rare release-without-move case).
                                val endCol = if (lastMouseDragCol >= 0) lastMouseDragCol
                                    else (down.position.x / baseCharWidth).toInt()
                                        .coerceIn(0, screenState.snapshot.cols - 1)
                                val endRow = if (lastMouseDragRow >= 0) lastMouseDragRow
                                    else (down.position.y / baseCharHeight).toInt()
                                        .coerceIn(0, screenState.snapshot.rows - 1)
                                gestureCallback?.onMouseDrag(endCol, endRow, MouseDragPhase.End)
                            }

                            GestureType.Undetermined -> {
                                // No drag occurred — this is a tap
                                if (isHorizontalDrag) {
                                    // Horizontal swipe — not a tap (selection already
                                    // cleared during classification above)
                                } else if (inPinchCooldown) {
                                    // Suppress accidental taps from pinch finger liftoff
                                } else if (selectionManager.mode != SelectionMode.NONE) {
                                    selectionManager.clearSelection()
                                } else {
                                    val tapCol = (down.position.x / baseCharWidth).toInt()
                                        .coerceIn(0, screenState.snapshot.cols - 1)
                                    val tapRow = (down.position.y / baseCharHeight).toInt()
                                        .coerceIn(0, screenState.snapshot.rows - 1)

                                    // Check hyperlinks first — they take priority over
                                    // mouse mode callbacks so URLs are always tappable.
                                    // Uses screen-state method which joins soft-wrapped
                                    // lines for cross-line URL detection.
                                    val hyperlinkUrl = screenState.getHyperlinkUrlAt(tapRow, tapCol)

                                    if (hyperlinkUrl != null) {
                                        onHyperlinkClick(hyperlinkUrl)
                                    } else {
                                        // Give callback chance to handle tap (mouse mode)
                                        val callbackHandled = gestureCallback?.onTap(tapCol, tapRow) == true

                                        if (!callbackHandled) {
                                            if (keyboardEnabled) {
                                                focusRequester.requestFocus()
                                            }
                                            val now = System.currentTimeMillis()
                                            if (now - lastTapTime < 300) {
                                                onTerminalDoubleTap()
                                                lastTapTime = 0L
                                            } else {
                                                onTerminalTap()
                                                lastTapTime = now
                                            }
                                        }
                                    }
                                }
                                // Record tap for double-tap detection
                                tapTracker.lastTimestamp = down.uptimeMillis
                                tapTracker.lastPosition = down.position
                            }

                            else -> {}
                        }
                    }
                }
            },
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics {
                        // Hide Canvas from accessibility tree - AccessibilityOverlay provides semantic structure
                    },
            ) {
                // Fill background
                drawRect(
                    color = backgroundColor,
                    size = size,
                )

                // Precompute which rows are part of a multi-line URL group.
                // A row is a URL continuation iff the previous row ends with a
                // URL-safe character (after trimming terminal-width padding)
                // AND this row begins with a URL-safe character **at column 0**
                // — i.e. no leading whitespace. The "no leading whitespace"
                // rule distinguishes a wrapped URL ("…/issu" + "es/78") from
                // a URL followed by a new indented line of prose. Matches the
                // same rule used for tap-to-open in
                // [TerminalScreenState.getHyperlinkUrlAt]; without it, the
                // visual underline would extend onto unrelated text.
                val urlContinuationRows = mutableSetOf<Int>()
                for (row in 1 until screenState.snapshot.rows) {
                    val prev = screenState.getVisibleLine(row - 1).text.trimEnd()
                    val curText = screenState.getVisibleLine(row).text
                    if (prev.isNotEmpty() && curText.isNotEmpty() &&
                        prev.last().isUrlSafe() &&
                        curText[0] != ' ' && curText[0] != '\t' &&
                        curText[0].isUrlSafe()) {
                        // Check that the chain traces back to a line with an actual URL
                        if ((row - 1) in urlContinuationRows ||
                            screenState.getVisibleLine(row - 1).autoDetectedUrls.isNotEmpty()) {
                            urlContinuationRows.add(row)
                        }
                    }
                }

                // Draw each line
                for (row in 0 until screenState.snapshot.rows) {
                    val line = screenState.getVisibleLine(row)
                    // For URL continuation rows, compute where the URL-safe
                    // prefix ends: skip leading spaces, then find the first
                    // space after URL-safe content.
                    val urlEndCol = if (row in urlContinuationRows) {
                        val cells = line.cells
                        var i = 0
                        // Skip leading spaces (left margin padding)
                        while (i < cells.size && cells[i].char == ' ') i++
                        // Advance through URL-safe characters
                        while (i < cells.size && cells[i].char.isUrlSafe()) i++
                        i
                    } else 0
                    drawLine(
                        line = line,
                        row = row,
                        isUrlContinuation = row in urlContinuationRows,
                        urlContinuationEndCol = urlEndCol,
                        charWidth = baseCharWidth,
                        charHeight = baseCharHeight,
                        charBaseline = baseCharBaseline,
                        textPaint = textPaint,
                        defaultFg = foregroundColor,
                        defaultBg = backgroundColor,
                        selectionManager = selectionManager,
                        autoDetectUrls = terminalEmulator.autoDetectUrls,
                        selectionBackgroundColor = selectionBackgroundColor,
                        selectionForegroundColor = selectionForegroundColor,
                    )
                }

                // Draw cursor (only when viewing current screen, not scrollback)
                if (screenState.snapshot.cursorVisible && screenState.scrollbackPosition == 0 && cursorBlinkVisible) {
                    drawCursor(
                        row = screenState.snapshot.cursorRow,
                        col = screenState.snapshot.cursorCol,
                        charWidth = baseCharWidth,
                        charHeight = baseCharHeight,
                        foregroundColor = foregroundColor,
                        backgroundColor = backgroundColor,
                        cursorShape = screenState.snapshot.cursorShape,
                        pendingDeadChar = composeController.pendingDeadChar,
                        charBaseline = baseCharBaseline,
                        textPaint = textPaint,
                    )
                }

                // Draw compose mode overlay
                if (composeMode.isActive && screenState.scrollbackPosition == 0) {
                    drawComposeOverlay(
                        buffer = composeMode.buffer,
                        cursorRow = screenState.snapshot.cursorRow,
                        cursorCol = screenState.snapshot.cursorCol,
                        totalCols = screenState.snapshot.cols,
                        charWidth = baseCharWidth,
                        charHeight = baseCharHeight,
                        charBaseline = baseCharBaseline,
                        textPaint = textPaint,
                    )
                }

                // Draw IME composer overlay (romaji/pinyin etc. mid-composition).
                // Rendered at the cursor; the remote shell sees nothing until the
                // IME calls commitText() with the final selection.
                if (imeComposerText.isNotEmpty() && screenState.scrollbackPosition == 0) {
                    drawImeComposerOverlay(
                        text = imeComposerText,
                        cursorRow = screenState.snapshot.cursorRow,
                        cursorCol = screenState.snapshot.cursorCol,
                        totalCols = screenState.snapshot.cols,
                        charWidth = baseCharWidth,
                        charHeight = baseCharHeight,
                        charBaseline = baseCharBaseline,
                        textPaint = textPaint
                    )
                }

                // Draw selection handles
                if (selectionManager.mode != SelectionMode.NONE && !selectionManager.isSelecting) {
                    val range = selectionManager.selectionRange
                    if (range != null) {
                        // Start handle
                        val startPosition = range.getStartPosition()
                        drawSelectionHandle(
                            row = startPosition.first,
                            col = startPosition.second,
                            charWidth = baseCharWidth,
                            charHeight = baseCharHeight,
                            pointingDown = false,
                        )

                        // End handle
                        val endPosition = range.getEndPosition()
                        drawSelectionHandle(
                            row = endPosition.first,
                            col = endPosition.second,
                            charWidth = baseCharWidth,
                            charHeight = baseCharHeight,
                            pointingDown = true,
                        )
                    }
                }
            }

            if (accessibilityEnabled) {
                AccessibilityOverlay(
                    screenState = screenState,
                    charHeight = baseCharHeight,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(reviewFocusRequester)
                        .focusable(),
                    onToggleReviewMode = { isReviewMode = !isReviewMode },
                    isReviewMode = isReviewMode,
                )

                if (!isReviewMode && keyboardEnabled) {
                    LiveOutputRegion(
                        screenState = screenState,
                        enabled = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // Magnifying glass
        if (showMagnifier) {
            MagnifyingGlass(
                position = magnifierPosition,
                screenState = screenState,
                baseCharWidth = baseCharWidth,
                baseCharHeight = baseCharHeight,
                baseCharBaseline = baseCharBaseline,
                textPaint = textPaint,
                backgroundColor = backgroundColor,
                foregroundColor = foregroundColor,
                selectionManager = selectionManager,
                autoDetectUrls = terminalEmulator.autoDetectUrls,
                componentWidth = availableWidth,
                componentHeight = availableHeight,
                selectionBackgroundColor = selectionBackgroundColor,
                selectionForegroundColor = selectionForegroundColor,
            )
        }

        // Copy button (+ overflow menu) when text is selected
        if (selectionManager.mode != SelectionMode.NONE && !selectionManager.isSelecting) {
            val range = selectionManager.selectionRange
            if (range != null) {
                val endPosition = range.getEndPosition()
                val buttonRowWidthPx = with(density) { (COPY_BUTTON_SIZE * 2 + 8.dp).toPx() }
                val buttonX = (endPosition.second * baseCharWidth)
                    .coerceAtMost(availableWidth - buttonRowWidthPx)
                    .coerceAtLeast(0f)
                val buttonY = endPosition.first * baseCharHeight - with(density) { COPY_BUTTON_OFFSET.toPx() }
                val context = LocalContext.current
                var overflowMenuExpanded by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier
                        .offset(
                            x = with(density) { buttonX.toDp() },
                            y = with(density) { buttonY.coerceAtLeast(0f).toDp() },
                        ),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FloatingActionButton(
                            onClick = {
                                val selectedText =
                                    selectionManager.getSelectedText(screenState.snapshot, screenState.scrollbackPosition)
                                clipboardManager.setText(AnnotatedString(selectedText))
                                selectionManager.clearSelection()
                            },
                            modifier = Modifier.size(COPY_BUTTON_SIZE),
                            containerColor = Color.White,
                            contentColor = Color.Black,
                        ) {
                            Text(stringResource(R.string.terminal_selection_copy), style = MaterialTheme.typography.labelSmall)
                        }

                        Box {
                            val moreOptionsLabel = stringResource(R.string.terminal_selection_more_options_description)
                            FloatingActionButton(
                                onClick = {
                                    overflowMenuExpanded = true
                                    if (shouldShowIme) {
                                        scope.launch {
                                            delay(IME_SHOW_DELAY_MS)
                                            imeInputView?.showIme()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .size(COPY_BUTTON_SIZE)
                                    .semantics {
                                        contentDescription = moreOptionsLabel
                                        role = Role.Button
                                    },
                                containerColor = Color.White,
                                contentColor = Color.Black,
                            ) {
                                Text(stringResource(R.string.terminal_selection_more_options), style = MaterialTheme.typography.labelSmall)
                            }

                            DropdownMenu(
                                expanded = overflowMenuExpanded,
                                onDismissRequest = {
                                    overflowMenuExpanded = false
                                    if (shouldShowIme) {
                                        scope.launch {
                                            delay(IME_SHOW_DELAY_MS)
                                            imeInputView?.showIme()
                                        }
                                    }
                                },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.terminal_selection_select_all)) },
                                    onClick = {
                                        selectionController.selectAll()
                                        overflowMenuExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.terminal_selection_share)) },
                                    onClick = {
                                        val selectedText = selectionManager.getSelectedText(
                                            screenState.snapshot,
                                            screenState.scrollbackPosition,
                                        )
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, selectedText)
                                            if (context !is Activity) {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        }
                                        try {
                                            context.startActivity(Intent.createChooser(intent, null))
                                        } catch (_: android.content.ActivityNotFoundException) {
                                            // No app available to handle the share intent; silently ignore
                                        }
                                        overflowMenuExpanded = false
                                    },
                                )
                                if (onPasteRequest != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.terminal_selection_paste)) },
                                        onClick = {
                                            selectionManager.clearSelection()
                                            onPasteRequest()
                                            overflowMenuExpanded = false
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = {
                                        val modeLabel = when (selectionManager.mode) {
                                            SelectionMode.CHARACTER -> stringResource(R.string.terminal_selection_mode_character)
                                            SelectionMode.WORD -> stringResource(R.string.terminal_selection_mode_word)
                                            else -> stringResource(R.string.terminal_selection_mode_line)
                                        }
                                        Text(modeLabel)
                                    },
                                    onClick = {
                                        selectionController.toggleSelectionMode()
                                        // Keep menu open for easy cycling
                                    },
                                )
                            }
                        }
                    }
                    if (onPasteRequest != null) {
                        FloatingActionButton(
                            onClick = {
                                selectionManager.clearSelection()
                                onPasteRequest()
                            },
                            modifier = Modifier.size(COPY_BUTTON_SIZE),
                            containerColor = Color.White,
                            contentColor = Color.Black
                        ) {
                            Text("Paste", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        // Hidden AndroidView with custom InputConnection for IME (software keyboard) input
        // This provides proper backspace, enter key, and keyboard type handling
        // Must have non-zero size for Android to accept IME focus
        if (keyboardEnabled) {
            AndroidView(
                factory = { context ->
                    ImeInputView(context, keyboardHandler).apply {
                        this.allowStandardKeyboard = allowStandardKeyboard
                        this.rawKeyboardMode = rawKeyboardMode
                        // Set up key event handling
                        setOnKeyListener { _, _, event ->
                            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                                ImeInputView.shouldResetImeBufferOnKey(event.keyCode)
                            ) {
                                resetImeBuffer()
                            }
                            keyboardHandler.onKeyEvent(
                                androidx.compose.ui.input.key.KeyEvent(event),
                            )
                        }
                        // Store reference for IME control
                        imeInputView = this
                    }
                },
                update = { view ->
                    view.allowStandardKeyboard = allowStandardKeyboard
                    view.rawKeyboardMode = rawKeyboardMode
                },
                modifier = Modifier
                    .size(1.dp)
                    .focusable()
                    .focusRequester(focusRequester),
            )
        }
    }
}

/**
 * Draw a single terminal line.
 */
private fun DrawScope.drawLine(
    line: TerminalLine,
    row: Int,
    isUrlContinuation: Boolean = false,
    urlContinuationEndCol: Int = 0,
    charWidth: Float,
    charHeight: Float,
    charBaseline: Float,
    textPaint: TextPaint,
    defaultFg: Color,
    defaultBg: Color,
    selectionManager: SelectionManager,
    autoDetectUrls: Boolean = false,
    selectionBackgroundColor: Color = Color(0xFFB3D7FF),
    selectionForegroundColor: Color = Color.Black,
) {
    val y = row * charHeight
    var x = 0f

    line.cells.forEachIndexed { col, cell ->
        val cellWidth = charWidth * cell.width

        // Check if this cell is selected
        val isSelected = selectionManager.isCellSelected(row, col, line)

        // Check if this cell is part of a hyperlink.
        // For URL continuation rows, only underline the leading URL-safe
        // prefix (spaces are allowed at the left/right terminal margins
        // where wrapping occurs, but a mid-line space ends the URL).
        val isHyperlink = line.getHyperlinkUrlAt(col, autoDetectUrls) != null ||
            (isUrlContinuation && col < urlContinuationEndCol)

        // Determine colors (handle reverse video and selection)
        val baseFgColor = if (cell.reverse) cell.bgColor else cell.fgColor
        val bgColor = if (cell.reverse) cell.fgColor else cell.bgColor

        // Draw background (with selection highlight)
        val finalBgColor = if (isSelected) selectionBackgroundColor else bgColor
        if (finalBgColor != defaultBg || isSelected) {
            drawRect(
                color = finalBgColor,
                topLeft = Offset(x, y),
                size = Size(cellWidth, charHeight),
            )
        }

        // Draw character
        if (cell.char != ' ' || cell.combiningChars.isNotEmpty()) {
            val text = buildString {
                append(cell.char)
                cell.combiningChars.forEach { append(it) }
            }

            // Force high contrast for text on the selection background
            val fgColor = if (isSelected) selectionForegroundColor else baseFgColor

            // Configure text paint for this cell
            textPaint.color = fgColor.toArgb()
            textPaint.isFakeBoldText = cell.bold
            textPaint.textSkewX = if (cell.italic) -0.25f else 0f
            // Underline if cell has underline OR if it's a hyperlink
            textPaint.isUnderlineText = cell.underline == 1 || isHyperlink
            textPaint.isStrikeThruText = cell.strike

            // Draw text
            drawContext.canvas.nativeCanvas.drawText(
                text,
                x,
                y + charBaseline,
                textPaint,
            )

            // Draw double underline if needed
            if (cell.underline == 2) {
                drawDoubleUnderline(
                    x = x,
                    y = y + charBaseline,
                    width = cellWidth,
                    color = fgColor,
                )
            }

            // Draw curly underline if needed
            if (cell.underline == 3) {
                drawCurlyUnderline(
                    x = x,
                    y = y + charBaseline,
                    width = cellWidth,
                    charWidth = charWidth,
                    color = fgColor,
                )
            }
        }

        x += cellWidth
    }
}

/**
 * Draw a double underline (two parallel lines).
 *
 * @param x Start x position
 * @param y Baseline y position
 * @param width Width to draw the underline
 * @param color Color of the underline
 */
private fun DrawScope.drawDoubleUnderline(
    x: Float,
    y: Float,
    width: Float,
    color: Color,
) {
    val paint = Paint().apply {
        this.color = color.toArgb()
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
    }

    val baseY = y + 2f
    val canvas = drawContext.canvas.nativeCanvas

    // Draw first line
    canvas.drawLine(x, baseY, x + width, baseY, paint)

    // Draw second line below the first
    canvas.drawLine(x, baseY + DOUBLE_UNDERLINE_SPACING, x + width, baseY + DOUBLE_UNDERLINE_SPACING, paint)
}

/**
 * Draw a curly/zigzag underline pattern (like spell-check underlines).
 * The pattern repeats seamlessly across characters by aligning with the character grid.
 * Each character starts at the same phase (top of wave) ensuring perfect continuity.
 *
 * @param x Start x position
 * @param y Baseline y position
 * @param width Width to draw the underline
 * @param charWidth Base character width (used to calculate wavelength)
 * @param color Color of the underline
 */
private fun DrawScope.drawCurlyUnderline(
    x: Float,
    y: Float,
    width: Float,
    charWidth: Float,
    color: Color,
) {
    val path = Path()
    val wavelength = charWidth / CURLY_UNDERLINE_CYCLES_PER_CHAR
    val amplitude = CURLY_UNDERLINE_AMPLITUDE
    val halfWave = wavelength / 2

    val baseY = y + 3f

    // Start at the top of the wave (phase = 0)
    path.moveTo(x, baseY - amplitude)

    // Draw zigzag pattern across the width
    var currentX = x
    var goingDown = true // Start by going down from the top

    while (currentX < x + width) {
        currentX += halfWave
        if (currentX > x + width) {
            currentX = x + width
        }

        val nextY = if (goingDown) baseY + amplitude else baseY - amplitude
        path.lineTo(currentX, nextY)

        goingDown = !goingDown
    }

    // Draw the path
    drawContext.canvas.nativeCanvas.drawPath(
        path,
        Paint().apply {
            this.color = color.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 1f
            isAntiAlias = true
        },
    )
}

/**
 * Result of a single handle-drag update step.
 *
 * @param startRow Updated selection start row
 * @param startCol Updated selection start col
 * @param endRow Updated selection end row
 * @param endCol Updated selection end col
 * @param isMovingStart Which handle the finger is now dragging after this step
 */
internal data class HandleDragResult(
    val startRow: Int,
    val startCol: Int,
    val endRow: Int,
    val endCol: Int,
    val isMovingStart: Boolean,
)

/**
 * Computes the next selection range and active-handle flag for one drag event, handling
 * the case where the two handles cross each other.
 *
 * When the moving handle passes the anchor, ownership flips and the anchor is restored to
 * its pre-cross position so it doesn't jump to the dragged handle's column.
 *
 * @param startRow Current selection start row (before this event)
 * @param startCol Current selection start col (before this event)
 * @param endRow Current selection end row (before this event)
 * @param endCol Current selection end col (before this event)
 * @param isMovingStart True if the start handle is being dragged
 * @param newRow Row the finger moved to
 * @param newCol Col the finger moved to
 */
@VisibleForTesting
internal fun applyHandleDrag(
    startRow: Int,
    startCol: Int,
    endRow: Int,
    endCol: Int,
    isMovingStart: Boolean,
    newRow: Int,
    newCol: Int,
): HandleDragResult {
    val anchorRow = if (isMovingStart) endRow else startRow
    val anchorCol = if (isMovingStart) endCol else startCol

    val updatedStartRow = if (isMovingStart) newRow else startRow
    val updatedStartCol = if (isMovingStart) newCol else startCol
    val updatedEndRow = if (isMovingStart) endRow else newRow
    val updatedEndCol = if (isMovingStart) endCol else newCol

    val crossed = if (isMovingStart) {
        newRow > anchorRow || (newRow == anchorRow && newCol > anchorCol)
    } else {
        newRow < anchorRow || (newRow == anchorRow && newCol < anchorCol)
    }

    return if (!crossed) {
        HandleDragResult(updatedStartRow, updatedStartCol, updatedEndRow, updatedEndCol, isMovingStart)
    } else {
        // Flip ownership and restore the stationary handle to its pre-cross position.
        val newIsMovingStart = !isMovingStart
        if (newIsMovingStart) {
            // Was moving end, now moving start — end goes back to anchor
            HandleDragResult(newRow, newCol, anchorRow, anchorCol, newIsMovingStart)
        } else {
            // Was moving start, now moving end — start goes back to anchor
            HandleDragResult(anchorRow, anchorCol, newRow, newCol, newIsMovingStart)
        }
    }
}

/**
 * Check if a touch position is near a selection handle.
 * Returns (touchingStart, touchingEnd).
 */
private fun isTouchingHandle(
    touchPos: Offset,
    range: SelectionRange,
    charWidth: Float,
    charHeight: Float,
    hitRadius: Float = HANDLE_HIT_RADIUS,
): Pair<Boolean, Boolean> {
    // Handle circles are drawn offset from the character edge by their radius (~12dp).
    // Start handle points up (circle above the character top),
    // end handle points down (circle below the character bottom).
    // Use a generous hit radius centered on the circle's visual center.
    val handleRadius = charHeight * 0.4f // approximate circle radius
    val startPos = Offset(
        range.startCol * charWidth + charWidth / 2,
        range.startRow * charHeight - handleRadius,
    )
    val endPos = Offset(
        range.endCol * charWidth + charWidth / 2,
        range.endRow * charHeight + charHeight + handleRadius,
    )

    val distToStart = (touchPos - startPos).getDistance()
    val distToEnd = (touchPos - endPos).getDistance()

    return Pair(
        distToStart < hitRadius,
        distToEnd < hitRadius,
    )
}

/**
 * Lightweight placeholder shown when the Terminal composable is rendered
 * inside Android Studio's Compose Preview (LocalInspectionMode).
 */
@Composable
private fun TerminalPreview(
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Black,
    foregroundColor: Color = Color.White,
) {
    val previewLines = listOf(
        "$ echo hello",
        "hello",
        "$ _",
    )

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
    ) {
        val textPaint = TextPaint().apply {
            color = foregroundColor.toArgb()
            typeface = Typeface.MONOSPACE
            textSize = 14f * density
        }
        val metrics = textPaint.fontMetrics
        val lineHeight = ceil(metrics.descent - metrics.ascent)
        val baseline = ceil(-metrics.ascent)

        previewLines.forEachIndexed { index, line ->
            drawContext.canvas.nativeCanvas.drawText(
                line,
                0f,
                baseline + index * lineHeight,
                textPaint,
            )
        }
    }
}

/**
 * Calculates the top-left offset for the magnifying glass box so it stays visible and
 * never obscured by the finger.
 *
 * Prefers positioning above the touch point (with [verticalOffsetPx] gap). Flips below
 * the finger (adding [fingerHeightPx] + [verticalOffsetPx]) when there is not enough
 * room above. The result is clamped to the component bounds on all sides.
 */
@VisibleForTesting
internal fun magnifierOffset(
    position: Offset,
    magnifierSizePx: Float,
    verticalOffsetPx: Float,
    fingerHeightPx: Float,
    componentWidth: Int,
    componentHeight: Int,
): Offset {
    val clampedX = (position.x - magnifierSizePx / 2)
        .coerceIn(0f, (componentWidth.toFloat() - magnifierSizePx).coerceAtLeast(0f))
    val spaceAbove = position.y - verticalOffsetPx - magnifierSizePx
    return if (spaceAbove >= 0f) {
        Offset(x = clampedX, y = spaceAbove)
    } else {
        val yBelow = position.y + fingerHeightPx + verticalOffsetPx
        Offset(x = clampedX, y = yBelow.coerceAtMost((componentHeight.toFloat() - magnifierSizePx).coerceAtLeast(0f)))
    }
}

/**
 * Magnifying glass for text selection.
 */
@Composable
private fun MagnifyingGlass(
    position: Offset,
    screenState: TerminalScreenState,
    baseCharWidth: Float,
    baseCharHeight: Float,
    baseCharBaseline: Float,
    textPaint: TextPaint,
    backgroundColor: Color,
    foregroundColor: Color,
    selectionManager: SelectionManager,
    autoDetectUrls: Boolean = false,
    componentWidth: Int = 0,
    componentHeight: Int = 0,
    selectionBackgroundColor: Color = Color(0xFFB3D7FF),
    selectionForegroundColor: Color = Color.Black,
) {
    val magnifierSize = MAGNIFIER_SIZE_DP.dp
    val magnifierScale = MAGNIFIER_SCALE
    val density = LocalDensity.current
    val magnifierSizePx = with(density) { magnifierSize.toPx() }

    val verticalOffset = with(density) { MAGNIFIER_VERTICAL_OFFSET.toPx() }
    val fingerHeightPx = with(density) { FINGER_HEIGHT_DP.toPx() }

    val magnifierPos = magnifierOffset(
        position = position,
        magnifierSizePx = magnifierSizePx,
        verticalOffsetPx = verticalOffset,
        fingerHeightPx = fingerHeightPx,
        componentWidth = componentWidth,
        componentHeight = componentHeight,
    )

    // Compute the terminal-coordinate origin that maps the touch point
    // to the center of the magnifier. scale() uses the Canvas center as
    // pivot, so the offset is simply position minus half the magnifier.
    val centerOffset = Offset(
        x = position.x - magnifierSizePx / 2f,
        y = position.y - magnifierSizePx / 2f,
    )

    Box(
        modifier = Modifier
            .offset(
                x = with(density) { magnifierPos.x.toDp() },
                y = with(density) { magnifierPos.y.toDp() },
            )
            .size(magnifierSize)
            .border(
                width = MAGNIFIER_BORDER_WIDTH,
                color = Color.Gray,
                shape = CircleShape,
            )
            .background(
                color = Color.White.copy(alpha = MAGNIFIER_BACKGROUND_ALPHA),
                shape = CircleShape,
            )
            .clip(CircleShape),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Fill background
            drawRect(
                color = backgroundColor,
                size = size,
            )

            // Apply magnification and translate to center the touch point
            translate(-centerOffset.x * magnifierScale, -centerOffset.y * magnifierScale) {
                scale(magnifierScale, magnifierScale) {
                    // Calculate which rows to draw
                    val centerRow = (position.y / baseCharHeight).toInt().coerceIn(0, screenState.snapshot.rows - 1)

                    // Draw a few rows around the touch point
                    for (rowOffset in -MAGNIFIER_ROW_RANGE..MAGNIFIER_ROW_RANGE) {
                        val row = (centerRow + rowOffset).coerceIn(0, screenState.snapshot.rows - 1)
                        val line = screenState.getVisibleLine(row)
                        drawLine(
                            line = line,
                            row = row,
                            charWidth = baseCharWidth,
                            charHeight = baseCharHeight,
                            charBaseline = baseCharBaseline,
                            textPaint = textPaint,
                            defaultFg = foregroundColor,
                            defaultBg = backgroundColor,
                            selectionManager = selectionManager,
                            autoDetectUrls = autoDetectUrls,
                            selectionBackgroundColor = selectionBackgroundColor,
                            selectionForegroundColor = selectionForegroundColor,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Draw a selection handle (teardrop shape).
 */
private fun DrawScope.drawSelectionHandle(
    row: Int,
    col: Int,
    charWidth: Float,
    charHeight: Float,
    pointingDown: Boolean,
    color: Color = Color.White,
) {
    val handleWidthPx = SELECTION_HANDLE_WIDTH.toPx()

    // Position handle at the character position
    val charX = col * charWidth
    val charY = row * charHeight

    // Center the handle horizontally on the character
    val handleX = charX + charWidth / 2

    // Position vertically based on direction
    val handleY = if (pointingDown) {
        charY + charHeight // Bottom of character
    } else {
        charY // Top of character
    }

    val circleRadius = handleWidthPx / 2
    val circleY = if (pointingDown) {
        handleY + circleRadius
    } else {
        handleY - circleRadius
    }

    drawCircle(
        color = color,
        radius = circleRadius,
        center = Offset(handleX, circleY),
    )
}

/**
 * Draw the cursor with shape support (block, underline, bar).
 */
private fun DrawScope.drawCursor(
    row: Int,
    col: Int,
    charWidth: Float,
    charHeight: Float,
    foregroundColor: Color,
    backgroundColor: Color = Color.Transparent,
    cursorShape: CursorShape = CursorShape.BLOCK,
    pendingDeadChar: Int = 0,
    charBaseline: Float = 0f,
    textPaint: TextPaint? = null,
) {
    val x = col * charWidth
    val y = row * charHeight

    when (cursorShape) {
        CursorShape.BLOCK -> {
            // Block cursor - full cell rectangle outline
            drawRect(
                color = foregroundColor,
                topLeft = Offset(x, y),
                size = Size(charWidth, charHeight),
                alpha = CURSOR_BLOCK_ALPHA,
            )
        }

        CursorShape.UNDERLINE -> {
            // Underline cursor - line at bottom of cell
            val underlineHeight = charHeight * CURSOR_UNDERLINE_HEIGHT_RATIO
            drawRect(
                color = foregroundColor,
                topLeft = Offset(x, y + charHeight - underlineHeight),
                size = Size(charWidth, underlineHeight),
                alpha = CURSOR_LINE_ALPHA,
            )
        }

        CursorShape.BAR_LEFT -> {
            // Bar cursor - vertical line at left of cell
            val barWidth = charWidth * CURSOR_BAR_WIDTH_RATIO
            drawRect(
                color = foregroundColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, charHeight),
                alpha = CURSOR_LINE_ALPHA,
            )
        }
    }

    // Draw pending dead character if present
    if (pendingDeadChar != 0 && textPaint != null) {
        val savedColor = textPaint.color
        val savedBold = textPaint.isFakeBoldText
        val savedSkew = textPaint.textSkewX
        val savedUnderline = textPaint.isUnderlineText
        val savedStrike = textPaint.isStrikeThruText

        // Use opposite color for block cursor to ensure visibility
        val accentColor =
            if (cursorShape == CursorShape.BLOCK) {
                if (foregroundColor.luminance() > 0.5f) Color.Black else Color.White
            } else {
                foregroundColor
            }

        textPaint.color = accentColor.toArgb()
        textPaint.isFakeBoldText = false
        textPaint.textSkewX = 0f
        textPaint.isUnderlineText = false
        textPaint.isStrikeThruText = false

        drawContext.canvas.nativeCanvas.drawText(
            String(Character.toChars(pendingDeadChar)),
            x,
            y + charBaseline,
            textPaint,
        )

        textPaint.color = savedColor
        textPaint.isFakeBoldText = savedBold
        textPaint.textSkewX = savedSkew
        textPaint.isUnderlineText = savedUnderline
        textPaint.isStrikeThruText = savedStrike
    }
}

/**
 * Background color for the compose mode overlay.
 */
private val COMPOSE_OVERLAY_BACKGROUND = Color(0xFF2E7D32).copy(alpha = 0.85f)
// Distinct blue tint so the IME composer doesn't look identical to the
// compose-key overlay — users can tell "romaji being converted" apart
// from "compose key buffering".
private val IME_COMPOSER_BACKGROUND = Color(0xFF1565C0).copy(alpha = 0.85f)

/**
 * Width of the compose mode cursor bar in pixels.
 */
private const val COMPOSE_CURSOR_BAR_WIDTH = 2f

/**
 * Draw the compose mode overlay at the cursor position.
 *
 * Displays the buffered text with a slide-left behavior when the buffer
 * exceeds available space. Shows an ellipsis at the left edge when text
 * is truncated.
 */
private fun DrawScope.drawComposeOverlay(
    buffer: String,
    cursorRow: Int,
    cursorCol: Int,
    totalCols: Int,
    charWidth: Float,
    charHeight: Float,
    charBaseline: Float,
    textPaint: TextPaint,
) {
    val x = cursorCol * charWidth
    val y = cursorRow * charHeight
    val availableCols = totalCols - cursorCol

    // Determine displayed text with slide-left behavior
    val displayText = if (buffer.length > availableCols) {
        "\u2026" + buffer.takeLast(availableCols - 1)
    } else {
        buffer
    }

    val displayWidth = displayText.length * charWidth

    // Draw background
    drawRect(
        color = COMPOSE_OVERLAY_BACKGROUND,
        topLeft = Offset(x, y),
        size = Size(displayWidth.coerceAtLeast(charWidth), charHeight),
    )

    // Draw text
    if (displayText.isNotEmpty()) {
        val savedColor = textPaint.color
        val savedBold = textPaint.isFakeBoldText
        val savedSkew = textPaint.textSkewX
        val savedUnderline = textPaint.isUnderlineText
        val savedStrike = textPaint.isStrikeThruText

        textPaint.color = Color.White.toArgb()
        textPaint.isFakeBoldText = false
        textPaint.textSkewX = 0f
        textPaint.isUnderlineText = false
        textPaint.isStrikeThruText = false

        drawContext.canvas.nativeCanvas.drawText(
            displayText,
            x,
            y + charBaseline,
            textPaint,
        )

        textPaint.color = savedColor
        textPaint.isFakeBoldText = savedBold
        textPaint.textSkewX = savedSkew
        textPaint.isUnderlineText = savedUnderline
        textPaint.isStrikeThruText = savedStrike
    }

    // Draw cursor bar at end of displayed text
    val cursorX = x + displayText.length * charWidth
    drawRect(
        color = Color.White,
        topLeft = Offset(cursorX, y),
        size = Size(COMPOSE_CURSOR_BAR_WIDTH, charHeight),
        alpha = CURSOR_LINE_ALPHA,
    )
}

/**
 * Draw the IME composer overlay at the terminal cursor. The partial IME
 * composition (romaji being converted to kanji, pinyin candidates, swipe-
 * typed word in progress, etc.) never reaches the terminal stream — it
 * lives here until [TerminalInputConnection.commitText] fires. Distinct
 * background tint from [drawComposeOverlay] so users can tell the compose
 * key state apart from IME composition.
 */
private fun DrawScope.drawImeComposerOverlay(
    text: String,
    cursorRow: Int,
    cursorCol: Int,
    totalCols: Int,
    charWidth: Float,
    charHeight: Float,
    charBaseline: Float,
    textPaint: TextPaint,
) {
    if (text.isEmpty()) return
    val x = cursorCol * charWidth
    val y = cursorRow * charHeight
    // Use TextPaint to measure real display width — matters for CJK glyphs
    // whose visual width is ~2 cells rather than 1.
    val measuredWidth = textPaint.measureText(text)
    val availablePx = (totalCols - cursorCol) * charWidth

    // If it overflows the line, slide-left with an ellipsis leader.
    val displayText: String
    val displayWidth: Float
    if (measuredWidth > availablePx) {
        val ellipsis = "\u2026"
        var endIndex = text.length
        // Walk back from the end until the ellipsis + tail fits.
        while (endIndex > 0 &&
            textPaint.measureText(ellipsis + text.substring(text.length - endIndex)) > availablePx
        ) {
            endIndex--
        }
        displayText = if (endIndex == 0) ellipsis
        else ellipsis + text.substring(text.length - endIndex)
        displayWidth = textPaint.measureText(displayText)
    } else {
        displayText = text
        displayWidth = measuredWidth
    }

    drawRect(
        color = IME_COMPOSER_BACKGROUND,
        topLeft = Offset(x, y),
        size = Size(displayWidth.coerceAtLeast(charWidth), charHeight),
    )

    val savedColor = textPaint.color
    val savedBold = textPaint.isFakeBoldText
    val savedSkew = textPaint.textSkewX
    val savedUnderline = textPaint.isUnderlineText
    val savedStrike = textPaint.isStrikeThruText

    textPaint.color = Color.White.toArgb()
    textPaint.isFakeBoldText = false
    textPaint.textSkewX = 0f
    textPaint.isUnderlineText = true
    textPaint.isStrikeThruText = false

    drawContext.canvas.nativeCanvas.drawText(
        displayText,
        x,
        y + charBaseline,
        textPaint,
    )

    textPaint.color = savedColor
    textPaint.isFakeBoldText = savedBold
    textPaint.textSkewX = savedSkew
    textPaint.isUnderlineText = savedUnderline
    textPaint.isStrikeThruText = savedStrike
}

/**
 * Calculate pixel dimensions for a specific row/column count at a given font size.
 *
 * @param rows Number of rows
 * @param cols Number of columns
 * @param fontSize Font size in sp
 * @param typeface The typeface to use for measurement
 * @param density Screen density for sp to px conversion
 * @return Pair of (width in pixels, height in pixels)
 */
private fun calculateDimensions(
    rows: Int,
    cols: Int,
    fontSize: Float,
    typeface: Typeface,
    density: Float,
): Pair<Float, Float> {
    val textPaint = TextPaint().apply {
        this.typeface = typeface
        textSize = fontSize * density
    }

    val charWidth = textPaint.measureText("M")
    val metrics = textPaint.fontMetrics
    val charHeight = ceil(metrics.descent - metrics.ascent)

    val width = cols * charWidth
    val height = rows * charHeight

    return Pair(width, height)
}

/**
 * Find the optimal font size that allows the terminal to fit within the available space
 * while maintaining the exact target rows and columns.
 *
 * Uses binary search to efficiently find the largest font size that fits.
 *
 * @param targetRows Target number of rows
 * @param targetCols Target number of columns
 * @param availableWidth Available width in pixels
 * @param availableHeight Available height in pixels
 * @param minSize Minimum font size in sp
 * @param maxSize Maximum font size in sp
 * @param typeface The typeface to use for measurement
 * @param density Screen density for sp to px conversion
 * @return Optimal font size in sp
 */
private fun findOptimalFontSize(
    targetRows: Int,
    targetCols: Int,
    availableWidth: Int,
    availableHeight: Int,
    minSize: Float,
    maxSize: Float,
    typeface: Typeface,
    density: Float,
): Float {
    var minSizeCurrent = minSize
    var maxSizeCurrent = maxSize

    // Binary search for optimal font size
    while (maxSizeCurrent - minSizeCurrent > FONT_SIZE_SEARCH_EPSILON) {
        val midSize = (minSizeCurrent + maxSizeCurrent) / 2f
        val (width, height) = calculateDimensions(
            rows = targetRows,
            cols = targetCols,
            fontSize = midSize,
            typeface = typeface,
            density = density,
        )

        if (width <= availableWidth && height <= availableHeight) {
            // This size fits, try larger
            minSizeCurrent = midSize
        } else {
            // This size doesn't fit, try smaller
            maxSizeCurrent = midSize
        }
    }

    // Return the largest size that fits
    return minSizeCurrent.coerceIn(minSize, maxSize)
}

private fun charsPerDimension(pixels: Int, charPixels: Float) = (pixels / charPixels).toInt().coerceAtLeast(1)
