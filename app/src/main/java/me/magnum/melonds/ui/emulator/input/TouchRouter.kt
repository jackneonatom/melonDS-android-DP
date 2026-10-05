package me.magnum.melonds.ui.emulator.input

import android.os.SystemClock
import android.view.Choreographer
import me.magnum.melonds.MelonEmulator
import me.magnum.melonds.domain.model.Input
import me.magnum.melonds.domain.model.TouchMacro

/**
 * The single path to the emulated touchscreen. The on-screen finger handler, the touch stick and touch
 * macros all report here, and [TouchArbiter] decides what the DS sees. Main thread only.
 */
object TouchRouter {

    private val nativeTouch = object : TouchArbiter.NativeTouch {
        override fun down(x: Int, y: Int) {
            MelonEmulator.onInputDown(Input.TOUCHSCREEN)
            MelonEmulator.onScreenTouch(x, y)
        }

        override fun move(x: Int, y: Int) {
            MelonEmulator.onScreenTouch(x, y)
        }

        override fun up() {
            MelonEmulator.onInputUp(Input.TOUCHSCREEN)
            MelonEmulator.onScreenRelease()
        }
    }

    private var frameRequested = false
    private val arbiter = TouchArbiter(nativeTouch, { SystemClock.uptimeMillis() }, { requestFrame() })

    private val macroSink = object : TouchMacroController.Sink {
        override fun touch(macroId: Int, x: Int, y: Int) = arbiter.set(TouchArbiter.Source.MACRO, true, x, y, macroId)
        override fun release() = arbiter.set(TouchArbiter.Source.MACRO, false)
    }
    private var macroController = TouchMacroController(emptyList(), macroSink)

    private val frameCallback = Choreographer.FrameCallback {
        frameRequested = false
        macroController.tick()
        arbiter.onFrame()
        if (macroController.needsTicks) requestFrame()
    }

    fun set(source: TouchArbiter.Source, down: Boolean, x: Int = 0, y: Int = 0) {
        arbiter.set(source, down, x, y)
    }

    fun higherPriorityActive(source: TouchArbiter.Source): Boolean = arbiter.higherPriorityActive(source)

    fun setMacros(macros: List<TouchMacro>) {
        macroController.reset()
        macroController = TouchMacroController(macros, macroSink)
    }

    fun onMacroPressed(macroId: Int) {
        macroController.onPress(macroId)
        if (macroController.needsTicks) requestFrame()
    }

    fun onMacroReleased(macroId: Int) {
        macroController.onRelease(macroId)
    }

    /** Lift everything, e.g. when the emulator pauses. */
    fun reset() {
        macroController.reset()
        arbiter.reset()
    }

    private fun requestFrame() {
        if (!frameRequested) {
            frameRequested = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }
}
