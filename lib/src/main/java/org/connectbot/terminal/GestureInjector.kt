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
 * Drives synthetic touch gestures through the terminal's real pointer
 * pipeline — the same `awaitEachGesture` handler a physical finger feeds.
 *
 * Exists so a test harness (Haven's MCP agent) can exercise the actual
 * gesture code — long-press selection, drag-extend, the held-still
 * edge-scroll ticker — rather than a model-level reimplementation. The
 * implementation builds `MotionEvent`s and dispatches them to the hosting
 * View, so classification, edge-zone detection and the auto-repeat ticker
 * all run exactly as in production.
 *
 * Provided to callers via `Terminal(onGestureInjectorReady = ...)`.
 */
interface GestureInjector {
    /**
     * Dispatch a synthetic touch-drag and block the calling thread until
     * it completes.
     *
     * The sequence is: touch-down at `path[0]`; hold still for [pressMs]
     * (long enough for the long-press selection timeout to fire); move
     * through `path[1..]` one [stepMs] apart; hold still at `path.last()`
     * for [holdMs] (the window the held-still edge-scroll ticker runs in);
     * lift.
     *
     * @param path cells `(row, col)` to traverse, viewport-relative.
     *   `path[0]` is the touch-down point, `path.last()` the lift point.
     *   Out-of-viewport rows still map to valid pixels, so a path can
     *   target the top/bottom edge zone. Must be non-empty.
     * @param pressMs initial still-hold before the first move.
     * @param stepMs delay between successive move events.
     * @param holdMs still-hold at the final cell before lifting.
     * @throws IllegalArgumentException if [path] is empty.
     * @throws IllegalStateException if called on the main thread, or
     *   before the terminal has been measured.
     */
    fun injectDrag(
        path: List<Pair<Int, Int>>,
        pressMs: Long = 900L,
        stepMs: Long = 30L,
        holdMs: Long = 1000L,
    )
}
