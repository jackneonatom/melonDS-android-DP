package me.magnum.melonds.ui.emulator.model

import me.magnum.melonds.domain.model.rewind.RewindWindowPosition
import me.magnum.melonds.domain.model.RomInfo
import me.magnum.melonds.domain.model.SaveStateSlot
import me.magnum.melonds.ui.emulator.rewind.model.RewindWindow

sealed class EmulatorUiEvent {
    sealed class OpenScreen : EmulatorUiEvent() {
        data object SettingsScreen : OpenScreen()
        data class CheatsScreen(val romInfo: RomInfo) : OpenScreen()
        data class GameControlsScreen(val gameKey: String, val gameName: String) : OpenScreen()
    }
    data class ShowPauseMenu(val pauseMenu: PauseMenu) : EmulatorUiEvent()
    data class ShowTouchMacroEditor(val macros: List<me.magnum.melonds.domain.model.TouchMacro>) : EmulatorUiEvent()
    data class ShowRewindWindow(val rewindWindow: RewindWindow, val windowPosition: RewindWindowPosition) : EmulatorUiEvent()
    data class ShowRomSaveStates(val saveStates: List<SaveStateSlot>, val reason: Reason) : EmulatorUiEvent() {
        enum class Reason {
            SAVING,
            LOADING,
        }
    }
    data object ShowAchievementList : EmulatorUiEvent()
    data object ShowPendingSubmissionsDialog : EmulatorUiEvent()
    data object CloseEmulator : EmulatorUiEvent()
}