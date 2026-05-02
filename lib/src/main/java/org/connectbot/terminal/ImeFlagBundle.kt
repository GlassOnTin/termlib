package org.connectbot.terminal

/**
 * Concrete EditorInfo flag set used by [ImeInputView] when the host has
 * opted into custom keyboard mode. Mirrors the `ImeFlagSet` type in the
 * Haven core-terminal layer; we keep a separate copy here so termlib has
 * no dependency on the Haven module above it.
 *
 * Each boolean controls one bit:
 *  - [noSuggestions] → `TYPE_TEXT_FLAG_NO_SUGGESTIONS`
 *  - [visiblePassword] → `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`
 *  - [autoCorrect] → `TYPE_TEXT_FLAG_AUTO_CORRECT`
 *  - [fullEditor] → `BaseInputConnection`'s `fullEditor` arg
 *  - [noExtractUi] → `IME_FLAG_NO_EXTRACT_UI`
 *  - [noPersonalizedLearning] → `IME_FLAG_NO_PERSONALIZED_LEARNING`
 *
 * Conflicts (e.g. [visiblePassword] vs [fullEditor]) are not resolved
 * here — the Settings UI documents them and the user picks; this struct
 * just carries values verbatim through to [ImeInputView].
 */
data class ImeFlagBundle(
    val noSuggestions: Boolean,
    val visiblePassword: Boolean,
    val autoCorrect: Boolean,
    val fullEditor: Boolean,
    val noExtractUi: Boolean,
    val noPersonalizedLearning: Boolean,
)
