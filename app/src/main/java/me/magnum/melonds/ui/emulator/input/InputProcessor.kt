package me.magnum.melonds.ui.emulator.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import me.magnum.melonds.domain.model.ControllerConfiguration
import me.magnum.melonds.domain.model.Input

/**
 * Feeds Android key/motion events through [InputMappingEngine] and routes the resulting emulator inputs:
 * DS buttons to [systemInputListener], emulator functions to [frontendInputListener], and the touch
 * stick directions to [touchStickListener] as an analog vector (x, y in -1..1, +y = down).
 */
class InputProcessor(
    controllerConfiguration: ControllerConfiguration,
    private val systemInputListener: IInputListener,
    private val frontendInputListener: IInputListener,
    private val touchStickListener: ((Float, Float) -> Unit)? = null,
) : INativeInputListener {

    private val engine = InputMappingEngine(
        configuration = controllerConfiguration,
        analogInputs = Input.TOUCH_STICK_DIRECTIONS.toSet(),
        onPress = { input -> route(input, true) },
        onRelease = { input -> route(input, false) },
        onAnalog = { _, _ -> publishTouchStick() },
    )

    private fun route(input: Input, pressed: Boolean) {
        if (input in Input.TOUCH_STICK_DIRECTIONS) return // handled as analog
        val listener = if (input.isSystemInput) systemInputListener else frontendInputListener
        if (pressed) listener.onKeyPress(input) else listener.onKeyReleased(input)
    }

    private fun publishTouchStick() {
        val listener = touchStickListener ?: return
        val x = engine.analogValue(Input.TOUCH_STICK_RIGHT) - engine.analogValue(Input.TOUCH_STICK_LEFT)
        val y = engine.analogValue(Input.TOUCH_STICK_DOWN) - engine.analogValue(Input.TOUCH_STICK_UP)
        listener(x, y)
    }

    override fun onKeyEvent(keyEvent: KeyEvent): Boolean {
        return when (keyEvent.action) {
            KeyEvent.ACTION_DOWN -> engine.onKey(keyEvent.deviceId, keyEvent.keyCode, true)
            KeyEvent.ACTION_UP -> engine.onKey(keyEvent.deviceId, keyEvent.keyCode, false)
            else -> engine.isKeyBound(keyEvent.keyCode)
        }
    }

    override fun onMotionEvent(motionEvent: MotionEvent): Boolean {
        if (!motionEvent.isFromSource(InputDevice.SOURCE_CLASS_JOYSTICK)) return false
        if (motionEvent.action != MotionEvent.ACTION_MOVE) return false
        return engine.onAxes(motionEvent.deviceId) { axis -> motionEvent.getAxisValue(axis) }
    }

    /** Lets go of every input, e.g. when the emulator pauses. */
    fun releaseAll() {
        engine.releaseAll()
    }
}
