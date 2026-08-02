/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.connectbot.terminal

private val TRAILING_DETECTED_URL_PUNCTUATION = setOf('.', ',', ';', ':', '!')

/** True if the character commonly appears in URLs. */
internal fun Char.isUrlSafe(): Boolean = isLetterOrDigit() || this in "/:@!$&'()*+,;=-._~%?#[]"

internal fun Char.isUrlPrefixDecoration(): Boolean = this in "|│├└┌┬┼`>•●⎿\""

/**
 * Regexes intentionally match URL-ish spans broadly; trim punctuation that is
 * usually prose around a URL rather than part of it.
 */
internal fun String.trimDetectedUrl(): String {
    var end = length
    while (end > 0) {
        val ch = this[end - 1]
        val shouldTrim = ch in TRAILING_DETECTED_URL_PUNCTUATION ||
            (ch == ')' && countOpenLessThanClose(this, end, '(', ')')) ||
            (ch == ']' && countOpenLessThanClose(this, end, '[', ']'))
        if (!shouldTrim) break
        end--
    }
    return substring(0, end)
}

/**
 * Does [run] look like the tail of a URL broken inside a *filename*, rather
 * than the first word of a sentence?
 *
 * A tool that hard-wraps at its own width can break a URL anywhere, including
 * mid-filename, and the continuation then carries none of the `/#?&=` that
 * otherwise marks a row as continuing a URL — `.../cases-recorded/gs-e` +
 * `quilibrium.html, flux surfaces and all.` being the case that prompted this.
 * A trailing extension is the one signal left: prose does not begin with a
 * bare `word.ext` token often, and `i think this is prose` has nothing that
 * resembles one.
 *
 * Deliberately narrow — two to four *letters* after the final dot. That takes
 * `.html`, `.php`, `.md` and rejects version numbers like `1.2.3`, initials,
 * and a sentence ending in a full stop.
 */
internal fun looksLikeWrappedFileTail(run: String): Boolean {
    val trimmed = run.trimDetectedUrl()
    val dot = trimmed.lastIndexOf('.')
    if (dot <= 0 || dot == trimmed.lastIndex) return false
    val extension = trimmed.substring(dot + 1)
    return extension.length in 2..4 && extension.all { it.isLetter() }
}

private fun countOpenLessThanClose(s: String, end: Int, openChar: Char, closeChar: Char): Boolean {
    var openCount = 0
    var closeCount = 0
    for (i in 0 until end) {
        if (s[i] == openChar) {
            openCount++
        } else if (s[i] == closeChar) {
            closeCount++
        }
    }
    return openCount < closeCount
}
