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

/**
 * Callback interface for terminal gesture events.
 *
 * The host app implements this to inject behavior (e.g. SGR mouse sequences)
 * at gesture decision points without needing a separate gesture interceptor layer.
 * Terminal.kt calls these at the appropriate moments; returning true suppresses
 * the default behavior for that gesture.
 */
interface TerminalGestureCallback {
    /** Single tap at terminal cell. Return true to suppress default (hyperlink/focus). */
    fun onTap(col: Int, row: Int): Boolean = false

    /** Long-press at terminal cell. Return true to suppress default (selection start). */
    fun onLongPress(col: Int, row: Int): Boolean = false

    /** Vertical scroll at terminal cell. Return true to suppress default (scrollback). */
    fun onScroll(col: Int, row: Int, scrollUp: Boolean): Boolean = false
}
