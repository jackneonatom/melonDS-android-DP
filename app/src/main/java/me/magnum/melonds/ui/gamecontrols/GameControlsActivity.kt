package me.magnum.melonds.ui.gamecontrols

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import me.magnum.melonds.ui.gamecontrols.ui.GameControlsScreen
import me.magnum.melonds.ui.inputsetup.InputSetupActivity
import me.magnum.melonds.ui.theme.MelonTheme

/**
 * Input settings for one game: its own key mapping, touch stick settings and touch macros.
 */
@AndroidEntryPoint
class GameControlsActivity : AppCompatActivity() {

    companion object {
        const val KEY_GAME_KEY = "game_key"
        const val KEY_GAME_NAME = "game_name"

        fun intent(context: Context, gameKey: String, gameName: String): Intent {
            return Intent(context, GameControlsActivity::class.java).apply {
                putExtra(KEY_GAME_KEY, gameKey)
                putExtra(KEY_GAME_NAME, gameName)
            }
        }
    }

    private val viewModel: GameControlsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)

        setContent {
            MelonTheme {
                GameControlsScreen(
                    viewModel = viewModel,
                    onEditMapping = {
                        startActivity(InputSetupActivity.intentForGame(this, viewModel.gameKey, viewModel.gameName))
                    },
                    onBackClick = ::finish,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // the key mapping may have been edited in its own screen
        viewModel.reload()
    }
}
