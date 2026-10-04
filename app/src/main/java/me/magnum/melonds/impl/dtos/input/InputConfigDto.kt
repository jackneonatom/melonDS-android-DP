package me.magnum.melonds.impl.dtos.input

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.magnum.melonds.domain.model.Input
import me.magnum.melonds.domain.model.InputConfig

/**
 * Stored mapping for one emulator input.
 *
 * [assignments] holds every binding. [assignment] and [altAssignment] mirror the first two bindings so
 * that files keep working with versions that only knew about two slots (and files written by those
 * versions, which have no [assignments], load here).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class InputConfigDto(
    @SerialName("input") val input: Input,
    // always written: versions before multi-binding support require this field
    @EncodeDefault @SerialName("assignment") val assignment: AssignmentDto = AssignmentDto.None,
    @EncodeDefault @SerialName("altAssignment") val altAssignment: AssignmentDto = AssignmentDto.None,
    @SerialName("assignments") val assignments: List<AssignmentDto>? = null,
) {

    @Serializable
    sealed class AssignmentDto {
        @SerialName("deviceId") abstract val deviceId: Int?

        @Serializable
        @SerialName("none")
        data object None : AssignmentDto() {
            override val deviceId: Int? = null
        }

        @Serializable
        @SerialName("key")
        class Key(
            override val deviceId: Int?,
            @SerialName("keyCode") val keyCode: Int,
        ) : AssignmentDto()

        @Serializable
        @SerialName("axis")
        class Axis(
            override val deviceId: Int?,
            @SerialName("axisCode") val axisCode: Int,
            @SerialName("direction") val direction: InputConfig.Assignment.Axis.Direction,
        ) : AssignmentDto()

        fun toAssignment(): InputConfig.Assignment {
            return when (this) {
                is None -> InputConfig.Assignment.None
                is Key -> InputConfig.Assignment.Key(deviceId, keyCode)
                is Axis -> InputConfig.Assignment.Axis(deviceId, axisCode, direction)
            }
        }

        companion object {
            fun fromAssignment(assignment: InputConfig.Assignment): AssignmentDto {
                return when (assignment) {
                    is InputConfig.Assignment.None -> None
                    is InputConfig.Assignment.Key -> Key(assignment.deviceId, assignment.keyCode)
                    is InputConfig.Assignment.Axis -> Axis(assignment.deviceId, assignment.axisCode, assignment.direction)
                }
            }
        }
    }

    companion object {
        fun fromInputConfig(inputConfig: InputConfig): InputConfigDto {
            return InputConfigDto(
                input = inputConfig.input,
                assignment = AssignmentDto.fromAssignment(inputConfig.assignment),
                altAssignment = AssignmentDto.fromAssignment(inputConfig.altAssignment),
                assignments = inputConfig.assignments.map { AssignmentDto.fromAssignment(it) },
            )
        }
    }

    fun toInputConfig(): InputConfig {
        val list = assignments?.map { it.toAssignment() }
            ?: listOf(assignment.toAssignment(), altAssignment.toAssignment())
        return InputConfig(input, list.filter { it != InputConfig.Assignment.None }.distinct())
    }
}
