package me.magnum.melonds.impl.dtos.input

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.magnum.melonds.domain.model.GameInputProfile
import me.magnum.melonds.domain.model.TouchMacro
import me.magnum.melonds.domain.model.TouchStickSettings

@Serializable
data class GameInputProfileDto(
    @SerialName("version") val version: Int = 1,
    @SerialName("controllerConfiguration") val controllerConfiguration: ControllerConfigurationDto? = null,
    @SerialName("touchStick") val touchStick: TouchStickSettingsDto? = null,
    @SerialName("macros") val macros: List<TouchMacroDto> = emptyList(),
) {
    @Serializable
    data class TouchStickSettingsDto(
        @SerialName("mode") val mode: String,
        @SerialName("centerX") val centerX: Int,
        @SerialName("centerY") val centerY: Int,
        @SerialName("radius") val radius: Int,
        @SerialName("speed") val speed: Int,
        @SerialName("recenterDistance") val recenterDistance: Int,
        @SerialName("deadzone") val deadzone: Float,
        @SerialName("invertX") val invertX: Boolean = false,
        @SerialName("invertY") val invertY: Boolean = false,
    ) {
        fun toModel() = TouchStickSettings(
            mode = TouchStickSettings.Mode.entries.firstOrNull { it.name == mode } ?: TouchStickSettings.Mode.OFF,
            centerX = centerX.coerceIn(0, 255),
            centerY = centerY.coerceIn(0, 191),
            radius = radius,
            speed = speed,
            recenterDistance = recenterDistance,
            deadzone = deadzone.coerceIn(0f, 0.9f),
            invertX = invertX,
            invertY = invertY,
        )

        companion object {
            fun from(s: TouchStickSettings) = TouchStickSettingsDto(
                s.mode.name, s.centerX, s.centerY, s.radius, s.speed, s.recenterDistance, s.deadzone, s.invertX, s.invertY,
            )
        }
    }

    @Serializable
    data class TouchMacroDto(
        @SerialName("id") val id: Int,
        @SerialName("name") val name: String,
        @SerialName("x") val x: Int,
        @SerialName("y") val y: Int,
        @SerialName("mode") val mode: String = TouchMacro.Mode.HOLD.name,
        @SerialName("assignments") val assignments: List<InputConfigDto.AssignmentDto> = emptyList(),
    ) {
        fun toModel() = TouchMacro(
            id = id,
            name = name,
            x = x.coerceIn(0, 255),
            y = y.coerceIn(0, 191),
            mode = TouchMacro.Mode.entries.firstOrNull { it.name == mode } ?: TouchMacro.Mode.HOLD,
            assignments = assignments.map { it.toAssignment() }.filter { it != me.magnum.melonds.domain.model.InputConfig.Assignment.None }.distinct(),
        )

        companion object {
            fun from(m: TouchMacro) = TouchMacroDto(
                m.id, m.name, m.x, m.y, m.mode.name, m.assignments.map { InputConfigDto.AssignmentDto.fromAssignment(it) },
            )
        }
    }

    fun toModel(): GameInputProfile = GameInputProfile(
        controllerConfiguration = controllerConfiguration?.toControllerConfiguration(),
        touchStick = touchStick?.toModel(),
        macros = macros.map { it.toModel() }
            .filter { it.id in 1..TouchMacro.MAX_MACROS }
            .distinctBy { it.id },
    )

    companion object {
        fun from(profile: GameInputProfile) = GameInputProfileDto(
            controllerConfiguration = profile.controllerConfiguration?.let { ControllerConfigurationDto.fromControllerConfiguration(it) },
            touchStick = profile.touchStick?.let { TouchStickSettingsDto.from(it) },
            macros = profile.macros.map { TouchMacroDto.from(it) },
        )
    }
}
