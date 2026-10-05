package me.magnum.melonds.input

import me.magnum.melonds.ui.emulator.input.TouchArbiter
import me.magnum.melonds.ui.emulator.input.TouchArbiter.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchArbiterTest {

    private class Screen : TouchArbiter.NativeTouch {
        val events = mutableListOf<String>()
        var down = false
        override fun down(x: Int, y: Int) { check(!down); down = true; events += "down $x,$y" }
        override fun move(x: Int, y: Int) { check(down); events += "move $x,$y" }
        override fun up() { check(down); down = false; events += "up" }
    }

    private var now = 1_000L
    private var frameRequests = 0
    private val screen = Screen()
    private val arbiter = TouchArbiter(screen, { now }, { frameRequests++ })

    /** Let [ms] pass, delivering display frames. */
    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(16L, left)
            now += step
            left -= step
            arbiter.onFrame()
        }
    }

    @Test
    fun singleSource_touchesAndMoves() {
        arbiter.set(Source.STICK, true, 10, 20)
        arbiter.set(Source.STICK, true, 11, 20)
        arbiter.set(Source.STICK, false)
        assertEquals(listOf("down 10,20", "move 11,20", "up"), screen.events)
    }

    @Test
    fun fingerOverridesStick_withLiftInBetween() {
        arbiter.set(Source.STICK, true, 10, 10)
        arbiter.set(Source.FINGER, true, 200, 150)
        // the stick's touch lifts immediately; the finger's comes after the gap
        assertEquals(listOf("down 10,10", "up"), screen.events)
        assertTrue(frameRequests > 0)

        advance(TouchArbiter.LIFT_GAP_MS)
        assertEquals("down 200,150", screen.events.last())

        // finger lifts: the stick gets the screen back, again after a gap
        arbiter.set(Source.FINGER, false)
        assertEquals("up", screen.events.last())
        advance(TouchArbiter.LIFT_GAP_MS)
        assertEquals("down 10,10", screen.events.last())
    }

    @Test
    fun lowerPriorityCantTakeOver() {
        arbiter.set(Source.FINGER, true, 1, 1)
        arbiter.set(Source.STICK, true, 5, 5)
        arbiter.set(Source.MACRO, true, 9, 9)
        assertEquals(listOf("down 1,1"), screen.events)
        assertTrue(arbiter.higherPriorityActive(Source.STICK))
        assertTrue(arbiter.higherPriorityActive(Source.MACRO))
        assertFalse(arbiter.higherPriorityActive(Source.FINGER))
    }

    @Test
    fun differentMacros_areSeparateTaps() {
        arbiter.set(Source.MACRO, true, 50, 50, token = 1)
        arbiter.set(Source.MACRO, true, 100, 100, token = 2)
        // a different macro is a new touch, not a drag
        assertEquals(listOf("down 50,50", "up"), screen.events)
        advance(TouchArbiter.LIFT_GAP_MS)
        assertEquals("down 100,100", screen.events.last())
    }

    @Test
    fun quickRetap_waitsForGap() {
        arbiter.set(Source.MACRO, true, 50, 50, token = 1)
        arbiter.set(Source.MACRO, false)
        arbiter.set(Source.MACRO, true, 50, 50, token = 1)
        assertEquals(listOf("down 50,50", "up"), screen.events) // second tap held back
        advance(TouchArbiter.LIFT_GAP_MS)
        assertEquals(listOf("down 50,50", "up", "down 50,50"), screen.events)
    }

    @Test
    fun touchAfterLongIdle_isImmediate() {
        arbiter.set(Source.MACRO, true, 50, 50)
        arbiter.set(Source.MACRO, false)
        now += 5_000
        arbiter.set(Source.FINGER, true, 3, 4)
        assertEquals("down 3,4", screen.events.last())
    }

    @Test
    fun reset_liftsEverything() {
        arbiter.set(Source.STICK, true, 1, 1)
        arbiter.set(Source.MACRO, true, 2, 2)
        arbiter.reset()
        advance(200)
        assertFalse(screen.down)
        assertFalse(arbiter.isActive(Source.STICK))
    }
}
