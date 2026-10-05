package me.magnum.melonds.domain.model

/**
 * A touchscreen spot that controller buttons can press. Positions are in DS touchscreen pixels
 * (256 x 192). Each macro carries its own bindings, so it belongs entirely to one game's profile.
 */
data class TouchMacro(
    /** 1..[MAX_MACROS], unique within a profile. */
    val id: Int,
    val name: String,
    val x: Int,
    val y: Int,
    val mode: Mode = Mode.HOLD,
    val assignments: List<InputConfig.Assignment> = emptyList(),
) {
    enum class Mode {
        /** Touches while the button is held, like a finger resting there. */
        HOLD,
        /** A short tap on each press, however long the button is held. */
        TAP,
    }

    companion object {
        const val MAX_MACROS = 16
    }
}
