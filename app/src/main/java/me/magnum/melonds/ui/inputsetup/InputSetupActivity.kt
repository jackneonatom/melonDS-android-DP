package me.magnum.melonds.ui.inputsetup

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import me.magnum.melonds.domain.model.InputConfig
import me.magnum.melonds.ui.inputsetup.ui.InputSetupScreen
import me.magnum.melonds.ui.theme.MelonTheme
import kotlin.math.absoluteValue

@AndroidEntryPoint
class InputSetupActivity : AppCompatActivity() {

    private val viewModel: InputSetupViewModel by viewModels()

    /**
     * Per axis, what we've seen since the current assignment started: the first value and whether it
     * has changed since. An axis that reads fully pegged and never moves is most likely a trigger
     * that rests at -1 on that controller, not something the user is pushing.
     */
    private class AxisObservation(val firstValue: Float, var changed: Boolean = false)
    private val observedAxes = mutableMapOf<Int, AxisObservation>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)

        setContent {
            MelonTheme {
                InputSetupScreen(
                    viewModel = viewModel,
                    onBackClick = ::onNavigateUp,
                )
            }
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.inputUnderAssignment.collect {
                    if (it != null) {
                        // a new assignment has started
                        observedAxes.clear()
                    }
                }
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (viewModel.inputUnderAssignment.value != null && event.isFromSource(InputDevice.SOURCE_CLASS_JOYSTICK)) {
            if (event.action == MotionEvent.ACTION_MOVE) {
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

                if (bestAxis >= 0) {
                    val direction = if (bestValue > 0f) {
                        InputConfig.Assignment.Axis.Direction.POSITIVE
                    } else {
                        InputConfig.Assignment.Axis.Direction.NEGATIVE
                    }
                    viewModel.updateInputAssignedAxis(bestAxis, direction)
                }
                return true
            }
        }

        return super.onGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && viewModel.inputUnderAssignment.value != null) {
            @SuppressLint("GestureBackNavigation")
            if (event.keyCode != KeyEvent.KEYCODE_BACK) {
                viewModel.updateInputAssignedKey(event.keyCode)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
