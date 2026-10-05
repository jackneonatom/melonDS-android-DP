package me.magnum.melonds.input

import kotlinx.serialization.json.Json
import me.magnum.melonds.domain.model.ControllerConfiguration
import me.magnum.melonds.domain.model.GameInputProfile
import me.magnum.melonds.domain.model.Input
import me.magnum.melonds.domain.model.InputConfig
import me.magnum.melonds.domain.model.TouchMacro
import me.magnum.melonds.domain.model.TouchStickSettings
import me.magnum.melonds.impl.dtos.input.GameInputProfileDto
import me.magnum.melonds.ui.emulator.input.InputMappingEngine
import me.magnum.melonds.ui.emulator.input.TouchMacroController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchMacroTest {

    private class Sink : TouchMacroController.Sink {
        val events = mutableListOf<String>()
        override fun touch(macroId: Int, x: Int, y: Int) { events += "touch $macroId@$x,$y" }
        override fun release() { events += "release" }
    }

    private val map = TouchMacro(1, "Map", 200, 20, TouchMacro.Mode.HOLD)
    private val item = TouchMacro(2, "Item", 30, 170, TouchMacro.Mode.HOLD)
    private val jump = TouchMacro(3, "Jump", 128, 96, TouchMacro.Mode.TAP)

    @Test
    fun hold_touchesWhileHeld() {
        val sink = Sink()
        val c = TouchMacroController(listOf(map), sink)
        c.onPress(1)
        c.onRelease(1)
        assertEquals(listOf("touch 1@200,20", "release"), sink.events)
    }

    @Test
    fun tap_lastsFixedFrames_evenIfButtonHeldOrReleasedEarly() {
        val sink = Sink()
        val c = TouchMacroController(listOf(jump), sink)
        c.onPress(3)
        c.onRelease(3) // released immediately: the tap still completes
        repeat(TouchMacroController.TAP_FRAMES - 1) { c.tick() }
        assertEquals(listOf("touch 3@128,96"), sink.events)
        c.tick()
        assertEquals("release", sink.events.last())
        assertTrue(!c.needsTicks)

        // held for a long time: still just one tap
        sink.events.clear()
        c.onPress(3)
        repeat(TouchMacroController.TAP_FRAMES * 3) { c.tick() }
        assertEquals(listOf("touch 3@128,96", "release"), sink.events)
    }

    @Test
    fun tap_pressedAgainMidTap_isASecondTap() {
        val sink = Sink()
        val c = TouchMacroController(listOf(jump), sink)
        c.onPress(3)
        c.tick()
        c.onPress(3)
        assertEquals(listOf("touch 3@128,96", "release", "touch 3@128,96"), sink.events)
    }

    @Test
    fun latestPressWins_thenFallsBack() {
        val sink = Sink()
        val c = TouchMacroController(listOf(map, item), sink)
        c.onPress(1)
        c.onPress(2)
        c.onRelease(2)
        c.onRelease(1)
        assertEquals(listOf("touch 1@200,20", "touch 2@30,170", "touch 1@200,20", "release"), sink.events)
    }

    @Test
    fun macroBindings_shareButtonsWithDsControls() {
        // a macro on the right stick while A stays on its button, and one button driving both A and a macro
        val buttonA = InputConfig.Assignment.Key(null, 96)
        val events = mutableListOf<String>()
        val configs = ControllerConfiguration(
            listOf(InputConfig(Input.A, buttonA)) + Input.TOUCH_STICK_DIRECTIONS.map { InputConfig(it) }
        ).inputMapper + InputConfig(Input.touchMacro(1)!!, listOf(buttonA))
        val engine = InputMappingEngine(configs, onPress = { events += "+$it" }, onRelease = { events += "-$it" })
        engine.onKey(1, 96, true)
        assertEquals(setOf("+A", "+TOUCH_MACRO_1"), events.toSet())
        assertEquals(1, Input.touchMacroId(Input.TOUCH_MACRO_1))
        assertNull(Input.touchMacro(17))
    }

    @Test
    fun profile_roundTrip() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val profile = GameInputProfile(
            controllerConfiguration = ControllerConfiguration(listOf(InputConfig(Input.A, InputConfig.Assignment.Key(null, 99)))),
            touchStick = TouchStickSettings(mode = TouchStickSettings.Mode.CAMERA, centerX = 40, speed = 900),
            macros = listOf(
                map.copy(assignments = listOf(InputConfig.Assignment.Key(null, 102))),
                jump.copy(assignments = listOf(InputConfig.Assignment.Axis(null, 14, InputConfig.Assignment.Axis.Direction.NEGATIVE))),
            ),
        )
        val text = json.encodeToString(GameInputProfileDto.serializer(), GameInputProfileDto.from(profile))
        val restored = json.decodeFromString(GameInputProfileDto.serializer(), text).toModel()

        assertEquals(profile.touchStick, restored.touchStick)
        assertEquals(profile.macros, restored.macros)
        assertEquals(
            listOf(InputConfig.Assignment.Key(null, 99)),
            restored.controllerConfiguration!!.inputMapper.first { it.input == Input.A }.assignments,
        )
    }

    @Test
    fun emptyProfile_meansEverythingGlobal() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val restored = json.decodeFromString(GameInputProfileDto.serializer(), "{}").toModel()
        assertTrue(restored.isEmpty)
        assertNull(restored.controllerConfiguration)
        assertNull(restored.touchStick)
        assertEquals(1, restored.nextMacroId())
    }
}
