package me.magnum.melonds.ui.emulator.input

import android.view.Choreographer
import me.magnum.melonds.MelonEmulator
import me.magnum.melonds.domain.model.Input
import me.magnum.melonds.domain.model.TouchStickSettings

/**
 * Drives [TouchStickMapper] on the main thread, paced by display frames, and sends the resulting
 * touches to the emulator.
 */
class TouchStickController(settings: TouchStickSettings) {

    /** Set by the on-screen touch handler while a real finger is down. */
    object FingerState {
        @Volatile
        var touching = false
    }

    private val sink = object : TouchStickMapper.TouchSink {
        override fun press(x: Int, y: Int) {
            MelonEmulator.onInputDown(Input.TOUCHSCREEN)
            MelonEmulator.onScreenTouch(x, y)
        }

        override fun move(x: Int, y: Int) {
            MelonEmulator.onScreenTouch(x, y)
        }

        override fun release() {
            MelonEmulator.onInputUp(Input.TOUCHSCREEN)
            MelonEmulator.onScreenRelease()
        }

        override fun isFingerTouching(): Boolean = FingerState.touching
    }

    private val mapper = TouchStickMapper(settings, sink)
    private var ticking = false
    private var lastFrameNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dtMs = if (lastFrameNanos == 0L) 16.7f else ((frameTimeNanos - lastFrameNanos) / 1_000_000f).coerceIn(0f, 100f)
            lastFrameNanos = frameTimeNanos
            mapper.tick(dtMs)
            if (mapper.needsTicks) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                ticking = false
            }
        }
    }

    var settings: TouchStickSettings
        get() = mapper.settings
        set(value) {
            mapper.settings = value
            ensureTicking()
        }

    /** Stick vector from the input mapping, x and y in -1..1 (+y = down). */
    fun onStick(x: Float, y: Float) {
        if (mapper.settings.mode == TouchStickSettings.Mode.OFF) return
        mapper.setStick(x, y)
        ensureTicking()
    }

    /** Lift the finger and stop, e.g. when the emulator is paused. */
    fun stop() {
        if (ticking) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            ticking = false
        }
        mapper.reset()
    }

    private fun ensureTicking() {
        if (!ticking && mapper.needsTicks) {
            ticking = true
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }
}
