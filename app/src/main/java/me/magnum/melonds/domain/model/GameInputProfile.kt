package me.magnum.melonds.domain.model

/**
 * Input settings for one game. A null field means "use the global setting".
 */
data class GameInputProfile(
    val controllerConfiguration: ControllerConfiguration? = null,
    val touchStick: TouchStickSettings? = null,
    val macros: List<TouchMacro> = emptyList(),
) {
    val isEmpty: Boolean
        get() = controllerConfiguration == null && touchStick == null && macros.isEmpty()

    /** Id for a new macro, or null if the game already has the maximum. */
    fun nextMacroId(): Int? = (1..TouchMacro.MAX_MACROS).firstOrNull { id -> macros.none { it.id == id } }
}
