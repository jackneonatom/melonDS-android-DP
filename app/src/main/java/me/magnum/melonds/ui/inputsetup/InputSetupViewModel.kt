package me.magnum.melonds.ui.inputsetup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import me.magnum.melonds.domain.repositories.GameInputProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import me.magnum.melonds.domain.model.ControllerConfiguration
import me.magnum.melonds.domain.model.Input
import me.magnum.melonds.domain.model.InputConfig
import me.magnum.melonds.domain.repositories.SettingsRepository
import me.magnum.melonds.impl.input.ControllerConfigurationFactory
import me.magnum.melonds.utils.EventSharedFlow
import javax.inject.Inject

/**
 * Key mapping. Each control can have any number of bindings, and a physical input may be bound to
 * several controls (many-to-many, like PPSSPP).
 */
@HiltViewModel
class InputSetupViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val controllerConfigurationFactory: ControllerConfigurationFactory,
    private val gameInputProfileRepository: GameInputProfileRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Set when editing one game's own mapping rather than the global one. */
    private val gameKey: String? = savedStateHandle.get<String>(InputSetupActivity.KEY_GAME_KEY)
    val gameName: String? = savedStateHandle.get<String>(InputSetupActivity.KEY_GAME_NAME)

    private val _inputConfig = MutableStateFlow(loadConfiguration().inputMapper)
    val inputConfiguration = _inputConfig.asStateFlow()

    private val _inputUnderAssignment = MutableStateFlow<Input?>(null)
    val inputUnderAssignment = _inputUnderAssignment.asStateFlow()

    private val _onInputAssignedEvent = EventSharedFlow<Input>()
    val onInputAssignedEvent = _onInputAssignedEvent.asSharedFlow()

    fun startInputAssignment(input: Input) {
        _inputUnderAssignment.value = input
    }

    fun stopInputAssignment() {
        _inputUnderAssignment.value = null
    }

    fun updateInputAssignedKey(key: Int) {
        addAssignment(InputConfig.Assignment.Key(null, key))
    }

    fun updateInputAssignedAxis(axis: Int, direction: InputConfig.Assignment.Axis.Direction) {
        addAssignment(InputConfig.Assignment.Axis(null, axis, direction))
    }

    fun removeAssignment(input: Input, assignment: InputConfig.Assignment) {
        updateConfig(input) { it.withoutAssignment(assignment) }
    }

    fun clearInputAssignment(input: Input) {
        updateConfig(input) { it.copy(assignments = emptyList()) }
        _inputUnderAssignment.value = null
    }

    fun resetToDefaults() {
        _inputUnderAssignment.value = null
        val defaults = controllerConfigurationFactory.buildDefaultControllerConfiguration()
        _inputConfig.value = defaults.inputMapper
        saveConfiguration(defaults)
    }

    private fun loadConfiguration(): ControllerConfiguration {
        val key = gameKey ?: return settingsRepository.getControllerConfiguration()
        return gameInputProfileRepository.getProfile(key).controllerConfiguration ?: settingsRepository.getControllerConfiguration()
    }

    private fun saveConfiguration(configuration: ControllerConfiguration) {
        val key = gameKey
        if (key == null) {
            settingsRepository.setControllerConfiguration(configuration)
        } else {
            val profile = gameInputProfileRepository.getProfile(key)
            gameInputProfileRepository.saveProfile(key, profile.copy(controllerConfiguration = configuration))
        }
    }

    /** Other controls the same physical input is bound to (it's allowed, but worth showing). */
    fun otherInputsBoundTo(assignment: InputConfig.Assignment, except: Input): List<Input> {
        return _inputConfig.value.filter { it.input != except && assignment in it.assignments }.map { it.input }
    }

    private fun addAssignment(assignment: InputConfig.Assignment) {
        val input = _inputUnderAssignment.value ?: return
        val wasEmpty = _inputConfig.value.firstOrNull { it.input == input }?.hasKeyAssigned() != true
        updateConfig(input) { it.withAssignment(assignment) }
        _inputUnderAssignment.value = null

        // When filling in an empty control, move on to the next one, so a whole controller can be set
        // up quickly. Adding an extra binding to a control stays put.
        if (wasEmpty) {
            focusOnNextInput(input)
        }
    }

    private fun updateConfig(input: Input, change: (InputConfig) -> InputConfig) {
        val current = _inputConfig.value
        val index = current.indexOfFirst { it.input == input }
        if (index < 0) return

        val updated = current.toMutableList().apply { this[index] = change(this[index]) }
        _inputConfig.value = updated
        saveConfiguration(ControllerConfiguration(updated))
    }

    private fun focusOnNextInput(currentInput: Input) {
        val currentInputIndex = _inputConfig.value.indexOfFirst { it.input == currentInput }
        val nextInput = _inputConfig.value.getOrNull(currentInputIndex + 1)
        if (nextInput != null) {
            _onInputAssignedEvent.tryEmit(nextInput.input)
        }
    }
}
