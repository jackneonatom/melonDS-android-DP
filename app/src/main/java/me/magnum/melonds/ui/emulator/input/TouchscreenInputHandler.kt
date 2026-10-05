package me.magnum.melonds.ui.emulator.input

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.MotionEvent.PointerCoords
import android.view.View
import me.magnum.melonds.domain.model.Point

/**
 * Real finger on the on-screen touchscreen. Goes through [TouchRouter], where a finger always takes
 * priority over touch macros and the analog touch stick.
 */
class TouchscreenInputHandler(inputListener: IInputListener) : BaseInputHandler(inputListener) {
    private val touchPoint: Point = Point()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                val point = normalizeTouchCoordinates(event, v.width, v.height)
                TouchRouter.set(TouchArbiter.Source.FINGER, true, point.x, point.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                TouchRouter.set(TouchArbiter.Source.FINGER, false)
            }
        }
        return true
    }

    private fun normalizeTouchCoordinates(event: MotionEvent, viewWidth: Int, viewHeight: Int): Point {
        var averageTouchX = 0f
        var averageTouchY = 0f
        val pointerCoordinates = PointerCoords()

        // Average out touch positions. Even though the DS has a resistive touch screen, some games rely on the nuances
        // of this technology for some mechanics. Averaging out the coordinates of the touch position allows us to
        // simulate those nuances to some degree
        for (i in 0 until event.pointerCount) {
            event.getPointerCoords(i, pointerCoordinates)
            averageTouchX += pointerCoordinates.x
            averageTouchY += pointerCoordinates.y
        }
        averageTouchX /= event.pointerCount
        averageTouchY /= event.pointerCount

        touchPoint.x = (averageTouchX / viewWidth * 256).toInt().coerceIn(0, 255)
        touchPoint.y = (averageTouchY / viewHeight * 192).toInt().coerceIn(0, 191)
        return touchPoint
    }
}
