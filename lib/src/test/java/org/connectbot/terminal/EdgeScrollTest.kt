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
}
