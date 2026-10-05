package me.magnum.melonds.ui.gamecontrols

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.magnum.melonds.domain.model.GameInputProfile
import me.magnum.melonds.domain.model.TouchStickSettings
import me.magnum.melonds.domain.repositories.GameInputProfileRepository
import me.magnum.melonds.domain.repositories.SettingsRepository
import javax.inject.Inject

@HiltViewModel
class GameControlsViewModel @Inject constructor(
    private val profiles: GameInputProfileRepository,
    private val settingsRepository: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val gameKey: String = savedStateHandle.get<String>(GameControlsActivity.KEY_GAME_KEY).orEmpty()
    val gameName: String = savedStateHandle.get<String>(GameControlsActivity.KEY_GAME_NAME).orEmpty()

    private val _profile = MutableStateFlow(profiles.getProfile(gameKey))
    val profile = _profile.asStateFlow()

    fun reload() {
        _profile.value = profiles.getProfile(gameKey)
    }

    /** Custom mapping starts as a copy of the global one. */
    fun setCustomMapping(enabled: Boolean) = update { profile ->
        profile.copy(controllerConfiguration = if (enabled) profile.controllerConfiguration ?: settingsRepository.getControllerConfiguration() else null)
    }

    /** Custom touch stick settings start as a copy of the global ones. */
    fun setCustomTouchStick(enabled: Boolean) = update { profile ->
        profile.copy(touchStick = if (enabled) profile.touchStick ?: settingsRepository.getTouchStickSettings() else null)
    }

    fun updateTouchStick(settings: TouchStickSettings) = update { it.copy(touchStick = settings) }

    fun deleteMacro(id: Int) = update { profile -> profile.copy(macros = profile.macros.filter { it.id != id }) }

    fun deleteAllMacros() = update { it.copy(macros = emptyList()) }

    fun resetToGlobal() = update { GameInputProfile() }

    private fun update(change: (GameInputProfile) -> GameInputProfile) {
        val updated = change(_profile.value)
        profiles.saveProfile(gameKey, updated)
        _profile.value = updated
    }
}
