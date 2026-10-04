package me.magnum.melonds.ui.emulator.input

import me.magnum.melonds.domain.model.TouchStickSettings
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Turns an analog stick vector into DS touchscreen presses (see [TouchStickSettings.Mode]).
 * Pure Kotlin: the caller provides the stick vector, calls [tick] once per display frame while
 * [needsTicks] is true, and implements [TouchSink] to reach the emulator.
 */
class TouchStickMapper(
    settings: TouchStickSettings,
    private val sink: TouchSink,
) {
    interface TouchSink {
        fun press(x: Int, y: Int)
        fun move(x: Int, y: Int)
        fun release()
        /** True while a real finger is on the touchscreen; it always takes priority. */
        fun isFingerTouching(): Boolean
    }

    companion object {
        const val SCREEN_WIDTH = 256
        const val SCREEN_HEIGHT = 192
        /** Frames the finger stays lifted when re-centering, so the game registers the lift. */
        const val RECENTER_LIFT_FRAMES = 2
        /** Camera mode response curve: >1 gives finer control near the center. */
        const val CAMERA_CURVE = 1.6f
    }

    var settings: TouchStickSettings = settings
        set(value) {
            if (value != field) {
                releaseIfPressed()
                field = value
            }
        }

    private var stickX = 0f
    private var stickY = 0f
    private var pressed = false
    private var posX = 0f
    private var posY = 0f
    private var liftFrames = 0

    /** Whether [tick] should keep being called. */
    val needsTicks: Boolean
        get() = settings.mode != TouchStickSettings.Mode.OFF && (pressed || isTilted())

    val isPressed: Boolean
        get() = pressed

    /** New stick position, x and y in -1..1 (+y = down). */
    fun setStick(x: Float, y: Float) {
        stickX = x
        stickY = y
        if (settings.mode == TouchStickSettings.Mode.JOYSTICK) {
            updateJoystick()
        } else if (settings.mode == TouchStickSettings.Mode.CAMERA && !isTilted()) {
            releaseIfPressed()
        }
    }

    /** Advance by [dtMs] milliseconds. */
    fun tick(dtMs: Float) {
        when (settings.mode) {
            TouchStickSettings.Mode.OFF -> releaseIfPressed()
            TouchStickSettings.Mode.JOYSTICK -> updateJoystick()
            TouchStickSettings.Mode.CAMERA -> updateCamera(dtMs)
        }
    }

    /** Lift the virtual finger, e.g. when the emulator pauses. */
    fun reset() {
        stickX = 0f
        stickY = 0f
        liftFrames = 0
        releaseIfPressed()
    }

    private fun updateJoystick() {
        if (yieldToFinger()) return

        val (vx, vy) = effectiveVector()
        if (vx == 0f && vy == 0f) {
            releaseIfPressed()
            return
        }

        val x = clampX(settings.centerX + vx * settings.radius)
        val y = clampY(settings.centerY + vy * settings.radius)
        if (!pressed) {
            pressed = true
            sink.press(x, y)
        } else {
            sink.move(x, y)
        }
    }

    private fun updateCamera(dtMs: Float) {
        if (yieldToFinger()) return

        val (vx, vy) = effectiveVector()
        if (vx == 0f && vy == 0f) {
            liftFrames = 0
            releaseIfPressed()
            return
        }

        if (liftFrames > 0) {
            liftFrames--
            return
        }

        if (!pressed) {
            posX = settings.centerX.toFloat()
            posY = settings.centerY.toFloat()
            pressed = true
            sink.press(posX.toInt(), posY.toInt())
            return
        }

        // response curve on the magnitude, keeping direction
        val magnitude = sqrt(vx * vx + vy * vy)
        val curved = magnitude.pow(CAMERA_CURVE)
        val scale = if (magnitude > 0f) curved / magnitude else 0f
        val step = settings.speed * (dtMs / 1000f)
        posX += vx * scale * step
        posY += vy * scale * step

        val dx = posX - settings.centerX
        val dy = posY - settings.centerY
        val outOfRange = sqrt(dx * dx + dy * dy) > settings.recenterDistance
        val offScreen = posX < 0f || posX > SCREEN_WIDTH - 1 || posY < 0f || posY > SCREEN_HEIGHT - 1
        if (outOfRange || offScreen) {
            // lift, then touch down at the center again on a later frame
            releaseIfPressed()
            liftFrames = RECENTER_LIFT_FRAMES
            return
        }

        sink.move(posX.toInt(), posY.toInt())
    }

    /** A real finger always wins; once it lifts, the stick takes over again on the next update. */
    private fun yieldToFinger(): Boolean {
        if (sink.isFingerTouching()) {
            pressed = false // the finger owns the touchscreen now; its release lifts it
            return true
        }
        return false
    }

    private fun isTilted(): Boolean {
        val (vx, vy) = effectiveVector()
        return vx != 0f || vy != 0f
    }

    /** Stick vector after deadzone (rescaled so it still reaches 1) and inversion. */
    private fun effectiveVector(): Pair<Float, Float> {
        var x = stickX.coerceIn(-1f, 1f)
        var y = stickY.coerceIn(-1f, 1f)
        if (settings.invertX) x = -x
        if (settings.invertY) y = -y

        val magnitude = sqrt(x * x + y * y)
        val deadzone = settings.deadzone.coerceIn(0f, 0.9f)
        if (magnitude <= deadzone || magnitude == 0f) return 0f to 0f

        val scaled = ((magnitude - deadzone) / (1f - deadzone)).coerceAtMost(1f)
        val factor = scaled / magnitude
        return (x * factor) to (y * factor)
    }

    private fun releaseIfPressed() {
        if (pressed) {
            pressed = false
            sink.release()
        }
    }

    private fun clampX(x: Float) = x.toInt().coerceIn(0, SCREEN_WIDTH - 1)
    private fun clampY(y: Float) = y.toInt().coerceIn(0, SCREEN_HEIGHT - 1)
}
