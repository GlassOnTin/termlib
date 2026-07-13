package org.connectbot.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the wheel-notch maths. The terminal turns each notch into exactly one
 * `onScroll` callback, so getting this wrong means either a dead wheel (small
 * movements swallowed) or a runaway one (fractions treated as full notches).
 */
class WheelNotchAccumulatorTest {

    @Test fun oneClassicNotchUpIsOneNotchUp() {
        // Compose reports a wheel turned away from the user as negative.
        assertEquals(1, WheelNotchAccumulator().feed(-1f))
    }

    @Test fun oneClassicNotchDownIsOneNotchDown() {
        assertEquals(-1, WheelNotchAccumulator().feed(1f))
    }

    @Test fun fractionsFromHighResWheelsAccumulateIntoANotch() {
        val a = WheelNotchAccumulator()
        assertEquals(0, a.feed(-0.3f)) // banked, not acted on
        assertEquals(0, a.feed(-0.3f))
        assertEquals(0, a.feed(-0.3f))
        assertEquals(1, a.feed(-0.3f)) // 1.2 total → one notch, 0.2 carried
    }

    @Test fun theRemainderCarriesOverRatherThanBeingLost() {
        val a = WheelNotchAccumulator()
        assertEquals(1, a.feed(-1.5f)) // one notch now, 0.5 banked
        assertEquals(1, a.feed(-0.5f)) // the banked 0.5 completes the next
    }

    @Test fun aFastFlickYieldsEveryNotchItEarned() {
        assertEquals(3, WheelNotchAccumulator().feed(-3f))
        assertEquals(-3, WheelNotchAccumulator().feed(3f))
    }

    @Test fun reversingDirectionCancelsTheBankedFraction() {
        val a = WheelNotchAccumulator()
        assertEquals(0, a.feed(-0.6f))
        assertEquals(0, a.feed(0.6f)) // back to zero, no phantom notch
        assertEquals(0, a.feed(-0.9f))
        assertEquals(0, a.feed(0.5f))
    }

    @Test fun noiseIsIgnored() {
        val a = WheelNotchAccumulator()
        assertEquals(0, a.feed(0f))
        assertEquals(0, a.feed(Float.NaN))
    }

    @Test fun resetDropsBankedFractions() {
        val a = WheelNotchAccumulator()
        a.feed(-0.9f)
        a.reset()
        assertEquals(0, a.feed(-0.5f)) // would have been a notch without the reset
    }
}
