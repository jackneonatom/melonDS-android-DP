package me.magnum.melonds.ui.inputsetup

import android.content.Context
import android.content.Intent
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
import me.magnum.melonds.ui.common.input.BindingCapture
import me.magnum.melonds.ui.inputsetup.ui.InputSetupScreen
import me.magnum.melonds.ui.theme.MelonTheme

@AndroidEntryPoint
class InputSetupActivity : AppCompatActivity() {

    companion object {
        const val KEY_GAME_KEY = "game_key"
        const val KEY_GAME_NAME = "game_name"

        /** Edit one game's own key mapping instead of the global one. */
        fun intentForGame(context: Context, gameKey: String, gameName: String): Intent {
            return Intent(context, InputSetupActivity::class.java).apply {
                putExtra(KEY_GAME_KEY, gameKey)
                putExtra(KEY_GAME_NAME, gameName)
            }
        }
    }

    private val viewModel: InputSetupViewModel by viewModels()

    private val bindingCapture = BindingCapture()

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
                        bindingCapture.reset()
                    }
                }
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (viewModel.inputUnderAssignment.value != null && event.isFromSource(InputDevice.SOURCE_CLASS_JOYSTICK)) {
            bindingCapture.onMotion(event)?.let { axis ->
                viewModel.updateInputAssignedAxis(axis.axisCode, axis.direction)
            }
            return true
        }

        return super.onGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && viewModel.inputUnderAssignment.value != null) {
            bindingCapture.onKeyDown(event)?.let { key ->
                viewModel.updateInputAssignedKey(key.keyCode)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
