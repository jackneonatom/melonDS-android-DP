package me.magnum.melonds.ui.inputsetup.ui

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.ContentAlpha
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import me.magnum.melonds.R
import me.magnum.melonds.domain.model.Input
import me.magnum.melonds.domain.model.InputConfig
import me.magnum.melonds.ui.common.MelonPreviewSet
import me.magnum.melonds.ui.common.input.BindingCapture
import me.magnum.melonds.ui.inputsetup.InputSetupViewModel
import me.magnum.melonds.ui.theme.MelonTheme

@Composable
fun InputSetupScreen(
    viewModel: InputSetupViewModel,
    onBackClick: () -> Unit,
) {
    val inputConfig by viewModel.inputConfiguration.collectAsStateWithLifecycle()
    val inputUnderConfiguration by viewModel.inputUnderAssignment.collectAsStateWithLifecycle()

    InputSetupScreenContent(
        gameName = viewModel.gameName,
        inputConfig = inputConfig,
        inputUnderConfiguration = inputUnderConfiguration,
        onInputAssignedEvent = viewModel.onInputAssignedEvent,
        onInputClick = viewModel::startInputAssignment,
        onRemoveAssignment = viewModel::removeAssignment,
        onClearInputClick = viewModel::clearInputAssignment,
        onResetDefaults = viewModel::resetToDefaults,
        onCancelInputConfiguration = viewModel::stopInputAssignment,
        onBackClick = onBackClick,
    )
}

private enum class MappingSection { DS, TOUCH_STICK, EMULATOR }

private fun sectionOf(input: Input): MappingSection = when {
    input in Input.TOUCH_STICK_DIRECTIONS -> MappingSection.TOUCH_STICK
    input.isSystemInput -> MappingSection.DS
    else -> MappingSection.EMULATOR
}

@Composable
private fun InputSetupScreenContent(
    gameName: String? = null,
    inputConfig: List<InputConfig>,
    inputUnderConfiguration: Input?,
    onInputAssignedEvent: Flow<Input>,
    onInputClick: (Input) -> Unit,
    onRemoveAssignment: (Input, InputConfig.Assignment) -> Unit,
    onClearInputClick: (Input) -> Unit,
    onResetDefaults: () -> Unit,
    onCancelInputConfiguration: () -> Unit,
    onBackClick: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var showResetDialog by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = inputUnderConfiguration != null) {
        onCancelInputConfiguration()
    }
    LaunchedEffect(Unit) {
        onInputAssignedEvent.collect {
            focusManager.moveFocus(focusDirection = FocusDirection.Down)
        }
    }

    // where each physical input is used, to point out shared bindings
    val usage = remember(inputConfig) {
        val map = HashMap<InputConfig.Assignment, MutableList<Input>>()
        inputConfig.forEach { config -> config.assignments.forEach { map.getOrPut(it) { mutableListOf() }.add(config.input) } }
        map
    }

    Scaffold(
        topBar = {
            Box(Modifier.background(MaterialTheme.colors.primaryVariant).statusBarsPadding()) {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.key_mapping))
                            if (gameName != null) {
                                Text(gameName, style = MaterialTheme.typography.caption)
                            }
                        }
                    },
                    backgroundColor = MaterialTheme.colors.primary,
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showResetDialog = true }) {
                            Icon(Icons.Default.RestartAlt, contentDescription = stringResource(R.string.mapping_reset_defaults))
                        }
                    },
                    windowInsets = WindowInsets.safeDrawing.exclude(WindowInsets(bottom = Int.MAX_VALUE)),
                )
            }
        },
        backgroundColor = MaterialTheme.colors.surface,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Box(Modifier.fillMaxSize().consumeWindowInsets(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding,
            ) {
                item(key = "help") {
                    Text(
                        text = stringResource(R.string.mapping_help),
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
                        modifier = Modifier.padding(16.dp),
                    )
                }

                var lastSection: MappingSection? = null
                inputConfig.forEach { config ->
                    val section = sectionOf(config.input)
                    if (section != lastSection) {
                        lastSection = section
                        item(key = "section_$section") { SectionHeader(section) }
                    }
                    item(key = config.input) {
                        Column {
                        InputRow(
                            config = config,
                            isBeingConfigured = config.input == inputUnderConfiguration,
                            sharedWith = { assignment -> usage[assignment].orEmpty().filter { it != config.input } },
                            onClick = { onInputClick(config.input) },
                            onRemoveAssignment = { onRemoveAssignment(config.input, it) },
                            onClearClick = { onClearInputClick(config.input) },
                        )
                        Divider()
                        }
                    }
                }
            }

            if (inputUnderConfiguration != null) {
                WaitingForInputOverlay(
                    inputName = getInputName(inputUnderConfiguration) ?: "",
                    onCancel = onCancelInputConfiguration,
                )
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            text = { Text(stringResource(R.string.mapping_reset_defaults_confirm)) },
            confirmButton = {
                TextButton(onClick = { showResetDialog = false; onResetDefaults() }) {
                    Text(stringResource(R.string.mapping_reset_defaults))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(section: MappingSection) {
    val title = when (section) {
        MappingSection.DS -> R.string.mapping_section_ds
        MappingSection.TOUCH_STICK -> R.string.mapping_section_touch_stick
        MappingSection.EMULATOR -> R.string.mapping_section_emulator
    }
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.subtitle2,
        color = MaterialTheme.colors.secondary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InputRow(
    config: InputConfig,
    isBeingConfigured: Boolean,
    sharedWith: (InputConfig.Assignment) -> List<Input>,
    onClick: () -> Unit,
    onRemoveAssignment: (InputConfig.Assignment) -> Unit,
    onClearClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = getInputName(config.input) ?: config.input.name,
                style = MaterialTheme.typography.body1,
            )
            Spacer(Modifier.height(6.dp))

            if (isBeingConfigured) {
                Text(stringResource(R.string.press_any_button), style = MaterialTheme.typography.body2)
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    config.assignments.forEach { assignment ->
                        BindingChip(
                            label = describeAssignment(assignment),
                            sharedWith = sharedWith(assignment).mapNotNull { getInputName(it) },
                            onRemove = { onRemoveAssignment(assignment) },
                        )
                    }
                    AddChip(onClick = onClick, emphasized = config.assignments.isEmpty())
                }
            }
        }
        if (config.hasKeyAssigned()) {
            IconButton(onClick = onClearClick) {
                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.mapping_clear_all))
            }
        }
    }
}

@Composable
private fun BindingChip(label: String, sharedWith: List<String>, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column {
        Row(
            modifier = Modifier
                .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.3f), shape)
                .padding(start = 10.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.body2)
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.mapping_remove_binding),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (sharedWith.isNotEmpty()) {
            Text(
                text = stringResource(R.string.mapping_already_used, sharedWith.joinToString(", ")),
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun AddChip(onClick: () -> Unit, emphasized: Boolean) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .border(1.dp, MaterialTheme.colors.secondary.copy(alpha = if (emphasized) 0.9f else 0.5f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colors.secondary)
        Spacer(Modifier.size(4.dp))
        Text(
            text = stringResource(if (emphasized) R.string.not_set else R.string.mapping_add_binding),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.secondary,
        )
    }
}

private fun describeAssignment(assignment: InputConfig.Assignment): String = BindingCapture.describe(assignment)

@Composable
private fun WaitingForInputOverlay(inputName: String, onCancel: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background.copy(alpha = 0.85f))
            .clickable(enabled = true, onClick = { })
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = inputName, style = MaterialTheme.typography.h6)
            Spacer(Modifier.height(8.dp))
            Text(text = stringResource(R.string.waiting_for_input), style = MaterialTheme.typography.body1)
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onCancel) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    }
}

@Composable
private fun getInputName(input: Input): String? {
    val resource = when (input) {
        Input.A -> R.string.input_a
        Input.B -> R.string.input_b
        Input.X -> R.string.input_x
        Input.Y -> R.string.input_y
        Input.LEFT -> R.string.input_left
        Input.RIGHT -> R.string.input_right
        Input.UP -> R.string.input_up
        Input.DOWN -> R.string.input_down
        Input.L -> R.string.input_l
        Input.R -> R.string.input_r
        Input.START -> R.string.input_start
        Input.SELECT -> R.string.input_select
        Input.HINGE -> R.string.input_lid
        Input.PAUSE -> R.string.input_pause
        Input.FAST_FORWARD -> R.string.input_fast_forward
        Input.MICROPHONE -> R.string.input_microphone
        Input.RESET -> R.string.input_reset
        Input.SWAP_SCREENS -> R.string.input_swap_screens
        Input.QUICK_SAVE -> R.string.input_quick_save
        Input.QUICK_LOAD -> R.string.input_quick_load
        Input.REWIND -> R.string.rewind
        Input.TOUCH_STICK_UP -> R.string.input_touch_stick_up
        Input.TOUCH_STICK_DOWN -> R.string.input_touch_stick_down
        Input.TOUCH_STICK_LEFT -> R.string.input_touch_stick_left
        Input.TOUCH_STICK_RIGHT -> R.string.input_touch_stick_right
        else -> return null
    }

    return stringResource(resource)
}

@MelonPreviewSet
@Composable
private fun PreviewInputSetupScreen() {
    MelonTheme {
        InputSetupScreenContent(
            inputConfig = listOf(
                InputConfig(Input.A, listOf(InputConfig.Assignment.Key(null, KeyEvent.KEYCODE_BUTTON_B), InputConfig.Assignment.Axis(null, MotionEvent.AXIS_Z, InputConfig.Assignment.Axis.Direction.POSITIVE))),
                InputConfig(Input.B, InputConfig.Assignment.Key(null, KeyEvent.KEYCODE_BUTTON_A)),
                InputConfig(Input.X),
                InputConfig(Input.TOUCH_STICK_RIGHT, InputConfig.Assignment.Axis(null, MotionEvent.AXIS_Z, InputConfig.Assignment.Axis.Direction.POSITIVE)),
                InputConfig(Input.PAUSE, InputConfig.Assignment.Key(null, KeyEvent.KEYCODE_BUTTON_MODE)),
            ),
            inputUnderConfiguration = null,
            onInputAssignedEvent = emptyFlow(),
            onInputClick = { },
            onRemoveAssignment = { _, _ -> },
            onClearInputClick = { },
            onResetDefaults = { },
            onCancelInputConfiguration = { },
            onBackClick = { },
        )
    }
}
