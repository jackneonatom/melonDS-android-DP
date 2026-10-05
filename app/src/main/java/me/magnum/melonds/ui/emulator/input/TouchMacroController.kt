package me.magnum.melonds.ui.emulator.input

import me.magnum.melonds.domain.model.TouchMacro

/**
 * Turns macro button presses into touches.
 *  - HOLD macros touch while their button is held.
 *  - TAP macros touch for [TAP_FRAMES] frames on each press.
 * If several are active, the most recently pressed one is touched; when it ends, the touch moves back to
 * the previous one still active (the arbiter lifts in between, so it's a fresh touch).
 *
 * Pure Kotlin: call [tick] once per display frame while [needsTicks] is true.
 */
class TouchMacroController(
    macros: List<TouchMacro>,
    private val sink: Sink,
) {
    interface Sink {
        fun touch(macroId: Int, x: Int, y: Int)
        fun release()
    }

    companion object {
        /** Long enough for any game to register the tap, even right after a lift gap. */
        const val TAP_FRAMES = 6
    }

    private val macrosById = macros.associateBy { it.id }

    /** Active macros in press order; the last one gets the touch. */
    private val active = ArrayList<Int>()
    private val tapFramesLeft = HashMap<Int, Int>()
    private var touchedId: Int? = null

    val needsTicks: Boolean
        get() = tapFramesLeft.isNotEmpty()

    fun onPress(macroId: Int) {
        val macro = macrosById[macroId] ?: return
        active.remove(macroId)
        active.add(macroId)
        if (macro.mode == TouchMacro.Mode.TAP) {
            tapFramesLeft[macroId] = TAP_FRAMES
            if (touchedId == macroId) {
                // pressed again mid-tap: lift so it's a second tap rather than a longer one
                touchedId = null
                sink.release()
            }
        }
        apply()
    }

    fun onRelease(macroId: Int) {
        val macro = macrosById[macroId] ?: return
        if (macro.mode == TouchMacro.Mode.HOLD) {
            active.remove(macroId)
        }
        // TAP macros run out on their own
        apply()
    }

    fun tick() {
        val finished = ArrayList<Int>()
        tapFramesLeft.entries.forEach { entry ->
            entry.setValue(entry.value - 1)
            if (entry.value <= 0) finished.add(entry.key)
        }
        finished.forEach {
            tapFramesLeft.remove(it)
            active.remove(it)
        }
        if (finished.isNotEmpty()) apply()
    }

    fun reset() {
        active.clear()
        tapFramesLeft.clear()
        apply()
    }

    private fun apply() {
        val top = active.lastOrNull()?.let { macrosById[it] }
        if (top == null) {
            if (touchedId != null) {
                touchedId = null
                sink.release()
            }
            return
        }
        if (touchedId != top.id) {
            touchedId = top.id
            sink.touch(top.id, top.x, top.y)
        }
    }
}
