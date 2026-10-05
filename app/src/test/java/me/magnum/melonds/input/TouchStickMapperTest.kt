package me.magnum.melonds.input

import me.magnum.melonds.domain.model.TouchStickSettings
import me.magnum.melonds.domain.model.TouchStickSettings.Mode
import me.magnum.melonds.ui.emulator.input.TouchStickMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchStickMapperTest {

    private class FakeScreen : TouchStickMapper.TouchSink {
        val events = mutableListOf<String>()
        var down = false
        var x = -1
        var y = -1
        var finger = false

        override fun press(x: Int, y: Int) { check(!down) { "pressed twice" }; down = true; this.x = x; this.y = y; events += "press $x,$y" }
        override fun move(x: Int, y: Int) { check(down) { "moved while lifted" }; this.x = x; this.y = y; events += "move $x,$y" }
        override fun release() { check(down) { "released while lifted" }; down = false; events += "release" }
        override fun isFingerTouching() = finger
    }

    private fun mapper(settings: TouchStickSettings, screen: FakeScreen) = TouchStickMapper(settings, screen)

    @Test
    fun off_doesNothing() {
        val s = FakeScreen()
        val m = mapper(TouchStickSettings(mode = Mode.OFF), s)
        m.setStick(1f, 0f)
        m.tick(16f)
        assertTrue(s.events.isEmpty())
        assertFalse(m.needsTicks)
    }

    @Test
    fun joystick_holdsOffsetFromCenter_andLiftsAtRest() {
        val s = FakeScreen()
        val m = mapper(TouchStickSettings(mode = Mode.JOYSTICK, centerX = 100, centerY = 80, radius = 40, deadzone = 0f), s)

        m.setStick(1f, 0f)
        assertEquals(listOf("press 140,80"), s.events)

        m.setStick(0f, -1f)
        assertEquals(100, s.x)
        assertEquals(40, s.y)

        m.setStick(0f, 0f)
        assertFalse(s.down)
    }

    @Test
    fun joystick_deadzoneIgnoresSmallTilt_butFullTiltStillReachesRadius() {
        val s = FakeScreen()
        val m = mapper(TouchStickSettings(mode = Mode.JOYSTICK, centerX = 128, centerY = 96, radius = 50, deadzone = 0.2f), s)

        m.setStick(0.15f, 0f)
        assertFalse(s.down)

        m.setStick(1f, 0f)
        assertEquals(178, s.x)
    }

    @Test
    fun joystick_staysOnScreen() {
        val s = FakeScreen()
        val m = mapper(TouchStickSettings(mode = Mode.JOYSTICK, centerX = 250, centerY = 190, radius = 64, deadzone = 0f), s)
        m.setStick(1f, 1f)
        assertTrue(s.x in 0..255)
        assertTrue(s.y in 0..191)
    }

    @Test
    fun joystick_invertY() {
        val s = FakeScreen()
        val m = mapper(TouchStickSettings(mode = Mode.JOYSTICK, centerX = 128, centerY = 96, radius = 30, deadzone = 0f, invertY = true), s)
        m.setStick(0f, 1f)
        assertEquals(66, s.y)
    }

    @Test
    fun camera_dragsProportionallyToTilt() {
        val s = FakeScreen()
        val m = mapper(TouchStickSettings(mode = Mode.CAMERA, centerX = 128, centerY = 96, speed = 600, recenterDistance = 100, deadzone = 0f), s)

        m.setStick(1f, 0f)
        assertTrue(m.needsTicks)
        m.tick(16f) // touches down at the center
        assertEquals("press 128,96", s.events.last())

        m.tick(50f) // full tilt, 600 px/s for 50ms = 30 px
        assertEquals(158, s.x)
        assertEquals(96, s.y)

        m.setStick(0.5f, 0f) // half tilt is slower than half speed (response curve)
        val before = s.x
        m.tick(50f)
        val moved = s.x - before
        assertTrue("moved $moved", moved in 5..14)
    }

    @Test
    fun camera_recentersByLiftingAndTouchingAgain() {
        val s = FakeScreen()
        val m = mapper(TouchStickSettings(mode = Mode.CAMERA, centerX = 128, centerY = 96, speed = 1000, recenterDistance = 20, deadzone = 0f), s)

        m.setStick(1f, 0f)
        m.tick(16f)          // press at center
        m.tick(16f)          // +16
        m.tick(16f)          // +32 > 20 -> lift
        assertFalse(s.down)
        repeat(TouchStickMapper.RECENTER_LIFT_FRAMES) { m.tick(16f) } // stays lifted so the game sees it
        assertFalse(s.down)
        m.tick(16f)          // touches down at the center again
        assertTrue(s.down)
        assertEquals(128, s.x)
    }

    @Test
    fun camera_liftsWhenStickReturnsToCenter() {
        val s = FakeScreen()
        val m = mapper(TouchStickSettings(mode = Mode.CAMERA, deadzone = 0.1f), s)
        m.setStick(0f, 1f)
        m.tick(16f)
        m.tick(16f)
        assertTrue(s.down)
        m.setStick(0.05f, 0f)
        assertFalse(s.down)
        assertFalse(m.needsTicks)
    }

    @Test
    fun realFinger_takesPriority_thenStickResumes() {
        val s = FakeScreen()
        val m = mapper(TouchStickSettings(mode = Mode.JOYSTICK, deadzone = 0f), s)

        m.setStick(1f, 0f)
        assertTrue(s.down)

        // a finger (or macro) touches: the stick gives up its touch
        s.finger = true
        m.tick(16f)
        assertFalse(s.down)
        assertEquals("release", s.events.last())

        s.finger = false
        m.tick(16f)
        assertTrue(s.down) // the stick touches again
    }

    @Test
    fun reset_liftsFinger() {
        val s = FakeScreen()
        val m = mapper(TouchStickSettings(mode = Mode.JOYSTICK, deadzone = 0f), s)
        m.setStick(1f, 0f)
        m.reset()
        assertFalse(s.down)
        assertFalse(m.needsTicks)
    }

    @Test
    fun changingSettings_liftsFinger() {
        val s = FakeScreen()
        val m = mapper(TouchStickSettings(mode = Mode.JOYSTICK, deadzone = 0f), s)
        m.setStick(1f, 0f)
        m.settings = TouchStickSettings(mode = Mode.CAMERA)
        assertFalse(s.down)
    }
}
