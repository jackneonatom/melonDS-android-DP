package me.magnum.melonds.input

import kotlinx.serialization.json.Json
import me.magnum.melonds.domain.model.ControllerConfiguration
import me.magnum.melonds.domain.model.Input
import me.magnum.melonds.domain.model.InputConfig
import me.magnum.melonds.impl.dtos.input.ControllerConfigurationDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputConfigDtoTest {

    // same settings as the app (AppModule.provideJson)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun legacyTwoSlotFile_loads() {
        val legacy = """
            {"inputMapper":[
              {"input":"A","assignment":{"type":"key","deviceId":null,"keyCode":97},"altAssignment":{"type":"key","deviceId":null,"keyCode":96}},
              {"input":"B","assignment":{"type":"none"}}
            ]}
        """.trimIndent()

        val config = json.decodeFromString(ControllerConfigurationDto.serializer(), legacy).toControllerConfiguration()
        val a = config.inputMapper.first { it.input == Input.A }
        assertEquals(listOf(InputConfig.Assignment.Key(null, 97), InputConfig.Assignment.Key(null, 96)), a.assignments)
        assertTrue(config.inputMapper.first { it.input == Input.B }.assignments.isEmpty())
        // inputs that didn't exist in the old file get their defaults
        assertEquals(1, config.inputMapper.first { it.input == Input.TOUCH_STICK_UP }.assignments.size)
    }

    @Test
    fun manyBindings_roundTrip_andKeepLegacyFields() {
        val bindings = listOf(
            InputConfig.Assignment.Key(null, 96),
            InputConfig.Assignment.Key(null, 97),
            InputConfig.Assignment.Axis(null, 11, InputConfig.Assignment.Axis.Direction.POSITIVE),
        )
        val original = ControllerConfiguration(listOf(InputConfig(Input.A, bindings), InputConfig(Input.TOUCH_STICK_UP)))
        val text = json.encodeToString(ControllerConfigurationDto.serializer(), ControllerConfigurationDto.fromControllerConfiguration(original))

        val restored = json.decodeFromString(ControllerConfigurationDto.serializer(), text).toControllerConfiguration()
        assertEquals(bindings, restored.inputMapper.first { it.input == Input.A }.assignments)
        // explicitly cleared stays cleared
        assertTrue(restored.inputMapper.first { it.input == Input.TOUCH_STICK_UP }.assignments.isEmpty())

        // older versions require "assignment" on every entry, even unbound ones
        val entries = text.split("{\"input\":").drop(1)
        assertTrue(entries.all { it.contains("\"assignment\"") })
    }
}
