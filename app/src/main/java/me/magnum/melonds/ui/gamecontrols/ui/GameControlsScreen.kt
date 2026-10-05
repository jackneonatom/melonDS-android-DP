package me.magnum.melonds.ui.gamecontrols.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.ContentAlpha
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.RadioButton
import androidx.compose.material.Scaffold
import androidx.compose.material.Slider
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.magnum.melonds.R
import me.magnum.melonds.domain.model.TouchMacro
import me.magnum.melonds.domain.model.TouchStickSettings
import me.magnum.melonds.ui.common.input.BindingCapture
import me.magnum.melonds.ui.gamecontrols.GameControlsViewModel
import kotlin.math.roundToInt

@Composable
fun GameControlsScreen(
    viewModel: GameControlsViewModel,
    onEditMapping: () -> Unit,
    onBackClick: () -> Unit,
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteMacros by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Box(Modifier.background(MaterialTheme.colors.primaryVariant).statusBarsPadding()) {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.game_controls))
                            Text(viewModel.gameName, style = MaterialTheme.typography.caption)
                        }
                    },
                    backgroundColor = MaterialTheme.colors.primary,
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    windowInsets = WindowInsets.safeDrawing.exclude(WindowInsets(bottom = Int.MAX_VALUE)),
                )
            }
        },
        backgroundColor = MaterialTheme.colors.surface,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // key mapping
            Section(stringResource(R.string.key_mapping)) {
                val custom = profile.controllerConfiguration != null
                Choice(stringResource(R.string.game_controls_use_global), !custom) { viewModel.setCustomMapping(false) }
                Choice(stringResource(R.string.game_controls_custom), custom) { viewModel.setCustomMapping(true) }
                if (custom) {
                    Button(onClick = onEditMapping, modifier = Modifier.padding(top = 4.dp)) {
                        Text(stringResource(R.string.game_controls_edit_mapping))
                    }
                }
            }

            // analog stick -> touchscreen
            Section(stringResource(R.string.touch_stick_category)) {
                val settings = profile.touchStick
                Choice(stringResource(R.string.game_controls_use_global), settings == null) { viewModel.setCustomTouchStick(false) }
                Choice(stringResource(R.string.game_controls_custom), settings != null) { viewModel.setCustomTouchStick(true) }
                if (settings != null) {
                    TouchStickForm(settings, viewModel::updateTouchStick)
                }
            }

            // touch macros
            Section(stringResource(R.string.touch_macros)) {
                Text(
                    text = stringResource(R.string.game_controls_macros_hint),
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
                )
                if (profile.macros.isEmpty()) {
                    Text(stringResource(R.string.game_controls_no_macros), style = MaterialTheme.typography.body1)
                } else {
                    profile.macros.sortedBy { it.id }.forEach { macro ->
                        MacroRow(macro, onDelete = { viewModel.deleteMacro(macro.id) })
                    }
                    TextButton(onClick = { confirmDeleteMacros = true }) {
                        Text(stringResource(R.string.game_controls_delete_macros), color = MaterialTheme.colors.error)
                    }
                }
            }

            TextButton(onClick = { confirmReset = true }, enabled = !profile.isEmpty) {
                Text(stringResource(R.string.game_controls_reset))
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            text = stringResource(R.string.game_controls_reset_confirm),
            confirm = stringResource(R.string.game_controls_reset),
            onConfirm = { confirmReset = false; viewModel.resetToGlobal() },
            onDismiss = { confirmReset = false },
        )
    }
    if (confirmDeleteMacros) {
        ConfirmDialog(
            text = stringResource(R.string.game_controls_delete_macros_confirm),
            confirm = stringResource(R.string.game_controls_delete_macros),
            onConfirm = { confirmDeleteMacros = false; viewModel.deleteAllMacros() },
            onDismiss = { confirmDeleteMacros = false },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = 2.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.h6, modifier = Modifier.padding(bottom = 4.dp))
            content()
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun MacroRow(macro: TouchMacro, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${macro.id}. ${macro.name}", fontWeight = FontWeight.Bold)
            val mode = stringResource(if (macro.mode == TouchMacro.Mode.TAP) R.string.macro_mode_tap else R.string.macro_mode_hold)
            val bindings = macro.assignments.joinToString(", ") { BindingCapture.describe(it) }
                .ifEmpty { stringResource(R.string.not_set) }
            Text(
                text = "$mode · (${macro.x}, ${macro.y}) · $bindings",
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.macro_delete))
        }
    }
}

@Composable
private fun TouchStickForm(settings: TouchStickSettings, onChange: (TouchStickSettings) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.touch_stick_mode), fontWeight = FontWeight.Bold)
        val modes = listOf(
            TouchStickSettings.Mode.OFF to R.string.touch_stick_mode_off,
            TouchStickSettings.Mode.JOYSTICK to R.string.touch_stick_mode_joystick,
            TouchStickSettings.Mode.CAMERA to R.string.touch_stick_mode_camera,
        )
        modes.forEach { (mode, label) ->
            Choice(stringResource(label), settings.mode == mode) { onChange(settings.copy(mode = mode)) }
        }

        IntSlider(stringResource(R.string.touch_stick_center_x), settings.centerX, 0..255) { onChange(settings.copy(centerX = it)) }
        IntSlider(stringResource(R.string.touch_stick_center_y), settings.centerY, 0..191) { onChange(settings.copy(centerY = it)) }
        IntSlider(stringResource(R.string.touch_stick_radius), settings.radius, 4..128) { onChange(settings.copy(radius = it)) }
        IntSlider(stringResource(R.string.touch_stick_speed), settings.speed, 50..2000) { onChange(settings.copy(speed = it)) }
        IntSlider(stringResource(R.string.touch_stick_recenter), settings.recenterDistance, 8..128) { onChange(settings.copy(recenterDistance = it)) }
        IntSlider(stringResource(R.string.touch_stick_deadzone), (settings.deadzone * 100).roundToInt(), 0..90) { onChange(settings.copy(deadzone = it / 100f)) }

        SwitchRow(stringResource(R.string.touch_stick_invert_x), settings.invertX) { onChange(settings.copy(invertX = it)) }
        SwitchRow(stringResource(R.string.touch_stick_invert_y), settings.invertY) { onChange(settings.copy(invertY = it)) }
    }
}

/** Slider that saves when the finger lifts, not on every step. */
@Composable
private fun IntSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.padding(top = 8.dp)) {
        Row {
            Text(label, modifier = Modifier.weight(1f))
            Text(current.roundToInt().toString(), fontWeight = FontWeight.Bold)
        }
        Slider(
            value = current,
            onValueChange = { current = it },
            onValueChangeFinished = { onChange(current.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ConfirmDialog(text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
