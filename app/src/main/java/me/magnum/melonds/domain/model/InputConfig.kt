package me.magnum.melonds.domain.model

/**
 * The physical inputs bound to one emulator [input]. Any number of bindings is allowed, and the same
 * physical input may also appear in other [InputConfig]s: mapping is many-to-many, like PPSSPP.
 */
data class InputConfig(
    val input: Input,
    val assignments: List<Assignment> = emptyList(),
) {

    /**
     * Legacy two-slot constructor (primary + alternative binding).
     */
    constructor(input: Input, assignment: Assignment, altAssignment: Assignment = Assignment.None) : this(
        input,
        listOf(assignment, altAssignment).filter { it != Assignment.None }.distinct(),
    )

    sealed class Assignment(open val deviceId: Int?) {
        data object None : Assignment(null)
        data class Key(override val deviceId: Int?, val keyCode: Int) : Assignment(deviceId)
        data class Axis(override val deviceId: Int?, val axisCode: Int, val direction: Direction) : Assignment(deviceId) {
            enum class Direction {
                POSITIVE,
                NEGATIVE,
            }
        }
    }

    /** First binding, kept for code and files that only know about two slots. */
    val assignment: Assignment
        get() = assignments.getOrNull(0) ?: Assignment.None

    /** Second binding, kept for code and files that only know about two slots. */
    val altAssignment: Assignment
        get() = assignments.getOrNull(1) ?: Assignment.None

    fun hasKeyAssigned(): Boolean {
        return assignments.isNotEmpty()
    }

    fun withAssignment(newAssignment: Assignment): InputConfig {
        if (newAssignment == Assignment.None || newAssignment in assignments) return this
        return copy(assignments = assignments + newAssignment)
    }

    fun withoutAssignment(oldAssignment: Assignment): InputConfig {
        return copy(assignments = assignments - oldAssignment)
    }
}
