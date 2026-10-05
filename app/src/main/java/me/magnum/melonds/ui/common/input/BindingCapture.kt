package me.magnum.melonds.ui.common.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import me.magnum.melonds.domain.model.InputConfig
import kotlin.math.absoluteValue

/**
 * Detects which controller button or stick direction the user presses while binding a control.
 * Call [reset] when a new capture starts.
 */
class BindingCapture {

    /**
     * Per axis, the first value seen since the capture started and whether it has changed since. An axis
     * that reads fully pegged and never moves is most likely a trigger that rests at -1 on that
     * controller, not something the user is pushing.
     */
    private class AxisObservation(val firstValue: Float, var changed: Boolean = false)

    private val observedAxes = mutableMapOf<Int, AxisObservation>()

    fun reset() {
        observedAxes.clear()
    }

    /** A key binding for a key press, or null for keys that can't be bound (Back). */
    fun onKeyDown(event: KeyEvent): InputConfig.Assignment.Key? {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return null
        return InputConfig.Assignment.Key(null, event.keyCode)
    }

    /** The most-pushed stick/trigger direction in [event], once one is pushed far enough. */
    fun onMotion(event: MotionEvent): InputConfig.Assignment.Axis? {
        if (!event.isFromSource(InputDevice.SOURCE_CLASS_JOYSTICK) || event.action != MotionEvent.ACTION_MOVE) return null

        val axes = event.device?.motionRanges
            ?.filter { it.isFromSource(InputDevice.SOURCE_CLASS_JOYSTICK) }
            ?.map { it.axis }
            ?.distinct()
            .orEmpty()

        var bestAxis = -1
        var bestValue = 0f
        axes.forEach { axis ->
            val value = event.getAxisValue(axis)
            val observation = observedAxes.getOrPut(axis) { AxisObservation(value) }
            if ((value - observation.firstValue).absoluteValue > 0.05f) {
                observation.changed = true
            }

            val restingPegged = !observation.changed && observation.firstValue.absoluteValue >= 0.9f
            if (value.absoluteValue >= 0.5f && !restingPegged && value.absoluteValue > bestValue.absoluteValue) {
                bestAxis = axis
                bestValue = value
            }
        }

        if (bestAxis < 0) return null
        val direction = if (bestValue > 0f) InputConfig.Assignment.Axis.Direction.POSITIVE else InputConfig.Assignment.Axis.Direction.NEGATIVE
        return InputConfig.Assignment.Axis(null, bestAxis, direction)
    }

    companion object {
        /** Human-readable name of a binding, e.g. "BUTTON A" or "Right stick →". */
        fun describe(assignment: InputConfig.Assignment): String {
            return when (assignment) {
                is InputConfig.Assignment.Key -> {
                    KeyEvent.keyCodeToString(assignment.keyCode).replace("KEYCODE", "").replace("_", " ").trim()
                }
                is InputConfig.Assignment.Axis -> {
                    val positive = assignment.direction == InputConfig.Assignment.Axis.Direction.POSITIVE
                    when (assignment.axisCode) {
                        MotionEvent.AXIS_X -> if (positive) "Left stick →" else "Left stick ←"
                        MotionEvent.AXIS_Y -> if (positive) "Left stick ↓" else "Left stick ↑"
                        MotionEvent.AXIS_Z -> if (positive) "Right stick →" else "Right stick ←"
                        MotionEvent.AXIS_RZ -> if (positive) "Right stick ↓" else "Right stick ↑"
                        MotionEvent.AXIS_HAT_X -> if (positive) "D-pad →" else "D-pad ←"
                        MotionEvent.AXIS_HAT_Y -> if (positive) "D-pad ↓" else "D-pad ↑"
                        MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE -> "Left trigger"
                        MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS -> "Right trigger"
                        else -> {
                            val name = MotionEvent.axisToString(assignment.axisCode).removePrefix("AXIS_").replace("_", " ").trim()
                            if (positive) "$name +" else "$name −"
                        }
                    }
                }
                InputConfig.Assignment.None -> ""
            }
        }
    }
}
