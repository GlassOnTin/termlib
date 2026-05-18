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

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeScrollTest {

    // Viewport: 1000px tall, edge zone 0.12 => top zone y < 120, bottom zone y > 880.

    private fun dir(
        posY: Float,
        scrollbackPosition: Int,
        maxScrollback: Int,
    ) = edgeScrollDirection(posY, viewportHeightPx = 1000f, scrollbackPosition, maxScrollback)

    @Test
    fun topZone_withScrollbackRoom_scrollsUp() {
        assertEquals(EdgeScroll.UP, dir(posY = 50f, scrollbackPosition = 0, maxScrollback = 100))
    }

    @Test
    fun topZone_atMaxScrollback_isNone() {
        assertEquals(EdgeScroll.NONE, dir(posY = 50f, scrollbackPosition = 100, maxScrollback = 100))
    }

    @Test
    fun bottomZone_aboveLiveBottom_scrollsDown() {
        assertEquals(EdgeScroll.DOWN, dir(posY = 950f, scrollbackPosition = 50, maxScrollback = 100))
    }

    @Test
    fun bottomZone_atLiveBottom_isNone() {
        assertEquals(EdgeScroll.NONE, dir(posY = 950f, scrollbackPosition = 0, maxScrollback = 100))
    }

    @Test
    fun middle_isNone() {
        assertEquals(EdgeScroll.NONE, dir(posY = 500f, scrollbackPosition = 50, maxScrollback = 100))
    }

    @Test
    fun exactlyAtTopEdgeBoundary_isNone() {
        // relY == edgeZone is not strictly less than edgeZone.
        assertEquals(EdgeScroll.NONE, dir(posY = 120f, scrollbackPosition = 0, maxScrollback = 100))
    }

    @Test
    fun justInsideTopEdgeBoundary_scrollsUp() {
        assertEquals(EdgeScroll.UP, dir(posY = 119f, scrollbackPosition = 0, maxScrollback = 100))
    }

    @Test
    fun exactlyAtBottomEdgeBoundary_isNone() {
        // relY == 1 - edgeZone is not strictly greater.
        assertEquals(EdgeScroll.NONE, dir(posY = 880f, scrollbackPosition = 50, maxScrollback = 100))
    }

    @Test
    fun justInsideBottomEdgeBoundary_scrollsDown() {
        assertEquals(EdgeScroll.DOWN, dir(posY = 881f, scrollbackPosition = 50, maxScrollback = 100))
    }

    @Test
    fun zeroViewportHeight_isNone() {
        assertEquals(
            EdgeScroll.NONE,
            edgeScrollDirection(posY = 0f, viewportHeightPx = 0f, scrollbackPosition = 0, maxScrollback = 100),
        )
    }

    // --- edgeScrollRowsPerTick (issue #94 — depth-based velocity scaling) ---

    @Test
    fun rowsPerTick_justInsideTopZone_isOne() {
        // posY = 119 -> relY = 0.119, depth = (0.12 - 0.119) / 0.12 ≈ 0.008 → 1 row.
        assertEquals(
            1,
            edgeScrollRowsPerTick(posY = 119f, viewportHeightPx = 1000f, dir = EdgeScroll.UP),
        )
    }

    @Test
    fun rowsPerTick_atTopEdge_capsAtMax() {
        // posY = 0 -> relY = 0.0, depth = 1.0 → 1 + 7 = 8 rows (cap).
        assertEquals(
            8,
            edgeScrollRowsPerTick(posY = 0f, viewportHeightPx = 1000f, dir = EdgeScroll.UP),
        )
    }

    @Test
    fun rowsPerTick_halfwayIntoTopZone_isMidValue() {
        // posY = 60 -> relY = 0.06, depth = (0.12 - 0.06) / 0.12 = 0.5 → 1 + 3 = 4 rows.
        assertEquals(
            4,
            edgeScrollRowsPerTick(posY = 60f, viewportHeightPx = 1000f, dir = EdgeScroll.UP),
        )
    }

    @Test
    fun rowsPerTick_atBottomEdge_capsAtMax() {
        // posY = 1000 -> relY = 1.0, depth = (1.0 - 0.88) / 0.12 = 1.0 → 8 rows.
        assertEquals(
            8,
            edgeScrollRowsPerTick(posY = 1000f, viewportHeightPx = 1000f, dir = EdgeScroll.DOWN),
        )
    }

    @Test
    fun rowsPerTick_outsideEdgeZone_isZero() {
        // Callers normally guard with edgeScrollDirection(), but defend.
        assertEquals(
            0,
            edgeScrollRowsPerTick(posY = 500f, viewportHeightPx = 1000f, dir = EdgeScroll.NONE),
        )
    }

    @Test
    fun rowsPerTick_zeroViewport_isZero() {
        assertEquals(
            0,
            edgeScrollRowsPerTick(posY = 0f, viewportHeightPx = 0f, dir = EdgeScroll.UP),
        )
    }
}
