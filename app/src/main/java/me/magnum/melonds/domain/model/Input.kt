package me.magnum.melonds.domain.model

/**
 * Input representation that is assigned to the given key code. If the input does not represent a
 * system input (i.e., it represents additional functionality offered by the emulator), a key code
 * of -1 must be used.
 *
 * @param keyCode The key code that the input represents in the system or -1 if it is not assigned
 * to any system input
 */
enum class Input(val keyCode: Int) {
    A(0),
    B(1),
    SELECT(2),
    START(3),
    RIGHT(4),
    LEFT(5),
    UP(6),
    DOWN(7),
    R(8),
    L(9),
    X(10),
    Y(11),
    DEBUG(16 + 3),
    TOUCHSCREEN(16 + 6),
    HINGE(16 + 7),
    PAUSE(-1),
    FAST_FORWARD(-1),
    MICROPHONE(-1),
    RESET(-1),
    TOGGLE_SOFT_INPUT(-1),
    SWAP_SCREENS(-1),
    QUICK_SAVE(-1),
    QUICK_LOAD(-1),
    REWIND(-1),

    // Analog stick -> touchscreen (see TouchStickMapper). These are mapped like any other
    // control, so the "touch stick" can be driven by the right stick, the left stick or buttons.
    TOUCH_STICK_UP(-1),
    TOUCH_STICK_DOWN(-1),
    TOUCH_STICK_LEFT(-1),
    TOUCH_STICK_RIGHT(-1),

    // Touch macro slots (see TouchMacro). Bound through each game's macros, not the key mapping list.
    TOUCH_MACRO_1(-1), TOUCH_MACRO_2(-1), TOUCH_MACRO_3(-1), TOUCH_MACRO_4(-1),
    TOUCH_MACRO_5(-1), TOUCH_MACRO_6(-1), TOUCH_MACRO_7(-1), TOUCH_MACRO_8(-1),
    TOUCH_MACRO_9(-1), TOUCH_MACRO_10(-1), TOUCH_MACRO_11(-1), TOUCH_MACRO_12(-1),
    TOUCH_MACRO_13(-1), TOUCH_MACRO_14(-1), TOUCH_MACRO_15(-1), TOUCH_MACRO_16(-1);

    val isSystemInput: Boolean
        get() = keyCode != -1

    companion object {
        val SYSTEM_BUTTONS = listOf(A, B, X, Y, L, R, START, SELECT, LEFT, RIGHT, UP, DOWN)
        val TOUCH_STICK_DIRECTIONS = listOf(TOUCH_STICK_UP, TOUCH_STICK_DOWN, TOUCH_STICK_LEFT, TOUCH_STICK_RIGHT)
        val TOUCH_MACROS = listOf(
            TOUCH_MACRO_1, TOUCH_MACRO_2, TOUCH_MACRO_3, TOUCH_MACRO_4, TOUCH_MACRO_5, TOUCH_MACRO_6, TOUCH_MACRO_7, TOUCH_MACRO_8,
            TOUCH_MACRO_9, TOUCH_MACRO_10, TOUCH_MACRO_11, TOUCH_MACRO_12, TOUCH_MACRO_13, TOUCH_MACRO_14, TOUCH_MACRO_15, TOUCH_MACRO_16,
        )

        /** Input slot for macro [id] (1-based). */
        fun touchMacro(id: Int): Input? = TOUCH_MACROS.getOrNull(id - 1)

        /** Macro id (1-based) of a macro slot, or null. */
        fun touchMacroId(input: Input): Int? = TOUCH_MACROS.indexOf(input).takeIf { it >= 0 }?.plus(1)
    }
}