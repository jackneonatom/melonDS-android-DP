package me.magnum.melonds.input

import me.magnum.melonds.domain.model.ControllerConfiguration
import me.magnum.melonds.domain.model.Input
import me.magnum.melonds.domain.model.InputConfig
import me.magnum.melonds.domain.model.InputConfig.Assignment.Axis.Direction.NEGATIVE
import me.magnum.melonds.domain.model.InputConfig.Assignment.Axis.Direction.POSITIVE
import me.magnum.melonds.ui.emulator.input.InputMappingEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputMappingEngineTest {

    // Android key / axis codes, as plain numbers
    private val BUTTON_A = 96
    private val BUTTON_B = 97
    private val BUTTON_X = 99
    private val BUTTON_Y = 100
    private val AXIS_Z = 11
    private val AXIS_RZ = 14
    private val PAD = 5

    private fun key(code: Int) = InputConfig.Assignment.Key(null, code)
    private fun axis(code: Int, dir: InputConfig.Assignment.Axis.Direction) = InputConfig.Assignment.Axis(null, code, dir)

    private class Recorder {
        val events = mutableListOf<String>()
        val analog = mutableMapOf<Input, Float>()
    }

    // Touch stick controls not mentioned by a test are declared unbound, so the automatic right-stick
    // defaults for them don't add events to tests that use the right stick for buttons.
    private fun engine(vararg configs: InputConfig, rec: Recorder, analog: Set<Input> = emptySet()) =
        InputMappingEngine(
            ControllerConfiguration(
                configs.toList() + Input.TOUCH_STICK_DIRECTIONS.filter { dir -> configs.none { it.input == dir } }.map { InputConfig(it) }
            ),
            analogInputs = analog,
            onPress = { rec.events += "+$it" },
            onRelease = { rec.events += "-$it" },
            onAnalog = { input, value -> rec.analog[input] = value },
        )

    private fun axes(vararg values: Pair<Int, Float>): (Int) -> Float {
        val map = values.toMap()
        return { map[it] ?: 0f }
    }

    @Test
    fun severalButtonsOnOneControl_heldUntilAllReleased() {
        val rec = Recorder()
        val e = engine(InputConfig(Input.A, listOf(key(BUTTON_A), key(BUTTON_B), key(BUTTON_X))), rec = rec)

        e.onKey(PAD, BUTTON_A, true)
        e.onKey(PAD, BUTTON_B, true)
        e.onKey(PAD, BUTTON_A, false)
        assertTrue(e.isPressed(Input.A))
        e.onKey(PAD, BUTTON_B, false)

        assertEquals(listOf("+A", "-A"), rec.events)
    }

    @Test
    fun oneButtonOnSeveralControls_drivesAll() {
        val rec = Recorder()
        val e = engine(
            InputConfig(Input.A, key(BUTTON_A)),
            InputConfig(Input.R, key(BUTTON_A)),
            rec = rec,
        )

        e.onKey(PAD, BUTTON_A, true)
        e.onKey(PAD, BUTTON_A, false)

        assertEquals(setOf("+A", "+R"), rec.events.take(2).toSet())
        assertEquals(setOf("-A", "-R"), rec.events.drop(2).toSet())
    }

    @Test
    fun rightStickOnFaceButtons_whileFaceButtonsStillWork() {
        // the user's example: right stick -> DS face buttons, controller face buttons -> DS face buttons too
        val rec = Recorder()
        val e = engine(
            InputConfig(Input.A, listOf(key(BUTTON_B), axis(AXIS_Z, POSITIVE))),
            InputConfig(Input.Y, listOf(key(BUTTON_X), axis(AXIS_Z, NEGATIVE))),
            InputConfig(Input.X, listOf(key(BUTTON_Y), axis(AXIS_RZ, NEGATIVE))),
            InputConfig(Input.B, listOf(key(BUTTON_A), axis(AXIS_RZ, POSITIVE))),
            rec = rec,
        )

        e.onAxes(PAD, axes(AXIS_Z to 0.9f))
        assertEquals(listOf("+A"), rec.events)

        // pressing the physical button for A too, then letting go of the stick: A stays held
        e.onKey(PAD, BUTTON_B, true)
        e.onAxes(PAD, axes(AXIS_Z to 0f))
        assertEquals(listOf("+A"), rec.events)
        e.onKey(PAD, BUTTON_B, false)
        assertEquals(listOf("+A", "-A"), rec.events)

        // stick up-left: X and Y together
        rec.events.clear()
        e.onAxes(PAD, axes(AXIS_Z to -0.8f, AXIS_RZ to -0.8f))
        assertEquals(setOf("+Y", "+X"), rec.events.toSet())

        // face buttons are unaffected by the stick bindings
        rec.events.clear()
        e.onAxes(PAD, axes())
        e.onKey(PAD, BUTTON_A, true)
        assertTrue(e.isPressed(Input.B))
    }

    @Test
    fun axisHysteresis_noFlickerAroundThreshold() {
        val rec = Recorder()
        val e = engine(InputConfig(Input.RIGHT, axis(AXIS_Z, POSITIVE)), rec = rec)

        e.onAxes(PAD, axes(AXIS_Z to 0.55f)) // press
        e.onAxes(PAD, axes(AXIS_Z to 0.45f)) // still held (release is below 0.4)
        e.onAxes(PAD, axes(AXIS_Z to 0.52f))
        e.onAxes(PAD, axes(AXIS_Z to 0.30f)) // release

        assertEquals(listOf("+RIGHT", "-RIGHT"), rec.events)
    }

    @Test
    fun analogValues_forTouchStick() {
        val rec = Recorder()
        val e = engine(
            InputConfig(Input.TOUCH_STICK_RIGHT, listOf(axis(AXIS_Z, POSITIVE), key(BUTTON_A))),
            InputConfig(Input.TOUCH_STICK_LEFT, axis(AXIS_Z, NEGATIVE)),
            rec = rec,
            analog = Input.TOUCH_STICK_DIRECTIONS.toSet(),
        )

        e.onAxes(PAD, axes(AXIS_Z to 0.3f))
        assertEquals(0.3f, e.analogValue(Input.TOUCH_STICK_RIGHT), 1e-6f)
        assertEquals(0f, e.analogValue(Input.TOUCH_STICK_LEFT), 1e-6f)

        // a button bound to an analog control gives full deflection
        e.onKey(PAD, BUTTON_A, true)
        assertEquals(1f, e.analogValue(Input.TOUCH_STICK_RIGHT), 1e-6f)
        e.onKey(PAD, BUTTON_A, false)
        assertEquals(0.3f, e.analogValue(Input.TOUCH_STICK_RIGHT), 1e-6f)

        e.onAxes(PAD, axes(AXIS_Z to -0.7f))
        assertEquals(0f, e.analogValue(Input.TOUCH_STICK_RIGHT), 1e-6f)
        assertEquals(0.7f, e.analogValue(Input.TOUCH_STICK_LEFT), 1e-6f)
    }

    @Test
    fun twoControllers_sameButton_heldUntilBothRelease() {
        val rec = Recorder()
        val e = engine(InputConfig(Input.A, key(BUTTON_A)), rec = rec)

        e.onKey(1, BUTTON_A, true)
        e.onKey(2, BUTTON_A, true)
        e.onKey(1, BUTTON_A, false)
        assertTrue(e.isPressed(Input.A))
        e.onKey(2, BUTTON_A, false)
        assertFalse(e.isPressed(Input.A))
    }

    @Test
    fun keyRepeat_doesNotRetrigger() {
        val rec = Recorder()
        val e = engine(InputConfig(Input.A, key(BUTTON_A)), rec = rec)
        e.onKey(PAD, BUTTON_A, true)
        e.onKey(PAD, BUTTON_A, true)
        e.onKey(PAD, BUTTON_A, true)
        assertEquals(listOf("+A"), rec.events)
    }

    @Test
    fun unboundKey_notConsumed() {
        val rec = Recorder()
        val e = engine(InputConfig(Input.A, key(BUTTON_A)), rec = rec)
        assertFalse(e.onKey(PAD, BUTTON_Y, true))
        assertTrue(e.onKey(PAD, BUTTON_A, true))
    }

    @Test
    fun releaseAll_releasesEverything() {
        val rec = Recorder()
        val e = engine(InputConfig(Input.A, key(BUTTON_A)), InputConfig(Input.UP, axis(AXIS_RZ, NEGATIVE)), rec = rec)
        e.onKey(PAD, BUTTON_A, true)
        e.onAxes(PAD, axes(AXIS_RZ to -1f))
        e.releaseAll()
        assertFalse(e.isPressed(Input.A))
        assertFalse(e.isPressed(Input.UP))
        assertEquals(setOf("-A", "-UP"), rec.events.drop(2).toSet())
    }

    @Test
    fun savedConfigWithoutTouchStick_getsRightStickDefaults_butClearedStaysCleared() {
        val old = ControllerConfiguration(listOf(InputConfig(Input.A, key(BUTTON_A))))
        val right = old.inputMapper.first { it.input == Input.TOUCH_STICK_RIGHT }
        assertEquals(listOf(axis(AXIS_Z, POSITIVE)), right.assignments)

        val cleared = ControllerConfiguration(listOf(InputConfig(Input.TOUCH_STICK_RIGHT)))
        assertTrue(cleared.inputMapper.first { it.input == Input.TOUCH_STICK_RIGHT }.assignments.isEmpty())
    }

    @Test
    fun legacyConstructor_keepsTwoSlots() {
        val config = InputConfig(Input.A, key(BUTTON_A), key(BUTTON_B))
        assertEquals(listOf(key(BUTTON_A), key(BUTTON_B)), config.assignments)
        assertEquals(key(BUTTON_A), config.assignment)
        assertEquals(key(BUTTON_B), config.altAssignment)
        assertEquals(1, InputConfig(Input.A, key(BUTTON_A), key(BUTTON_A)).assignments.size)
    }
}
