package me.magnum.melonds.ui.emulator.macros

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.magnum.melonds.R
import me.magnum.melonds.domain.model.InputConfig
import me.magnum.melonds.domain.model.TouchMacro
import me.magnum.melonds.ui.common.input.BindingCapture
import kotlin.math.roundToInt

/**
 * Editing state for the touch macro editor. [touchscreenImage] is the game's current touchscreen
 * (256 x 192), shown enlarged so markers can be placed on it, or null if it couldn't be captured.
 */
@Stable
class TouchMacroEditorState(initial: List<TouchMacro>, val touchscreenImage: ImageBitmap?) {
    val macros = mutableStateListOf<TouchMacro>().apply { addAll(initial.sortedBy { it.id }) }
    var selectedId by mutableStateOf(initial.minByOrNull { it.id }?.id)
    /** True while waiting for a controller button to bind to the selected macro. */
    var capturing by mutableStateOf(false)

    /** Incremented each time a capture starts, so the capture logic can start fresh. */
    var captureSession = 0
        private set

    fun startCapture() {
        if (selectedId == null) return
        captureSession++
        capturing = true
    }

    val selected: TouchMacro?
        get() = macros.firstOrNull { it.id == selectedId }

    val canAdd: Boolean
        get() = macros.size < TouchMacro.MAX_MACROS

    fun add() {
        val id = (1..TouchMacro.MAX_MACROS).firstOrNull { id -> macros.none { it.id == id } } ?: return
        // new markers start near the center, nudged so they don't all stack
        val n = macros.size
        val x = (128 + ((n % 4) - 1.5f) * 24).roundToInt().coerceIn(0, 255)
        val y = (96 + ((n / 4) - 1.5f) * 24).roundToInt().coerceIn(0, 191)
        macros.add(TouchMacro(id = id, name = "Macro $id", x = x, y = y))
        selectedId = id
        capturing = false
    }

    fun update(id: Int, change: (TouchMacro) -> TouchMacro) {
        val index = macros.indexOfFirst { it.id == id }
        if (index >= 0) macros[index] = change(macros[index])
    }

    fun delete(id: Int) {
        macros.removeAll { it.id == id }
        if (selectedId == id) selectedId = macros.firstOrNull()?.id
        capturing = false
    }

    fun select(id: Int) {
        if (selectedId != id) capturing = false
        selectedId = id
    }

    /** Called by the activity with the captured controller input. */
    fun onBindingCaptured(assignment: InputConfig.Assignment) {
        val id = selectedId ?: return
        update(id) { macro ->
            if (assignment in macro.assignments) macro else macro.copy(assignments = macro.assignments + assignment)
        }
        capturing = false
    }
}

@Composable
fun TouchMacroEditor(
    state: TouchMacroEditorState,
    onDone: (List<TouchMacro>) -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler {
        if (state.capturing) state.capturing = false else onCancel()
    }

    // Covers the whole screen (so the paused game's on-screen buttons get no touches) and shows the
    // touchscreen enlarged next to the editing panel. Working on a copy of the touchscreen image means it
    // doesn't matter where, or on which display, the layout draws the real one.
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                state.capturing = false
            }
    ) {
        val landscape = maxWidth > maxHeight
        val panelWidth = minOf(380.dp, maxWidth * 0.45f)
        val panelMaxHeight = maxHeight * 0.55f
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                TouchscreenPreview(state, Modifier.weight(1f).fillMaxHeight().padding(12.dp))
                EditorPanel(
                    state = state,
                    onDone = { onDone(state.macros.toList()) },
                    onCancel = onCancel,
                    modifier = Modifier.width(panelWidth).fillMaxHeight().padding(8.dp),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                TouchscreenPreview(state, Modifier.weight(1f).fillMaxWidth().padding(12.dp))
                EditorPanel(
                    state = state,
                    onDone = { onDone(state.macros.toList()) },
                    onCancel = onCancel,
                    modifier = Modifier.fillMaxWidth().heightIn(max = panelMaxHeight).padding(8.dp),
                )
            }
        }
    }
}

/** The DS touchscreen at the largest 4:3 size that fits, with the macro markers on it. */
@Composable
private fun TouchscreenPreview(state: TouchMacroEditorState, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        BoxWithConstraints(
            Modifier
                .aspectRatio(256f / 192f)
                .border(2.dp, Color.White)
        ) {
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = constraints.maxHeight.toFloat()

            val image = state.touchscreenImage
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.macro_no_preview), color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
                }
            }

            state.macros.forEach { macro ->
                MacroMarker(state, macro, widthPx, heightPx)
            }
        }
    }
}

@Composable
private fun MacroMarker(state: TouchMacroEditorState, macro: TouchMacro, areaWidth: Float, areaHeight: Float) {
    val density = LocalDensity.current
    val sizeDp = 44.dp
    val sizePx = with(density) { sizeDp.toPx() }
    val centerX = (macro.x + 0.5f) / 256f * areaWidth
    val centerY = (macro.y + 0.5f) / 192f * areaHeight
    val selected = state.selectedId == macro.id

    // fractional DS position while dragging, so slow drags don't get lost to rounding
    val dragPosition = remember(macro.id) { floatArrayOf(0f, 0f) }

    Box(
        modifier = Modifier
            .offset { IntOffset((centerX - sizePx / 2).roundToInt(), (centerY - sizePx / 2).roundToInt()) }
            .size(sizeDp)
            .background(
                color = (if (selected) MaterialTheme.colors.secondary else MaterialTheme.colors.primary).copy(alpha = 0.55f),
                shape = CircleShape,
            )
            .border(2.dp, if (selected) Color.White else Color.White.copy(alpha = 0.7f), CircleShape)
            .pointerInput(macro.id) {
                detectTapGestures(onTap = { state.select(macro.id) })
            }
            .pointerInput(macro.id, areaWidth, areaHeight) {
                detectDragGestures(
                    onDragStart = {
                        state.select(macro.id)
                        val current = state.macros.firstOrNull { it.id == macro.id }
                        dragPosition[0] = current?.x?.toFloat() ?: 0f
                        dragPosition[1] = current?.y?.toFloat() ?: 0f
                    },
                ) { change, drag ->
                    change.consume()
                    dragPosition[0] = (dragPosition[0] + drag.x / areaWidth * 256f).coerceIn(0f, 255f)
                    dragPosition[1] = (dragPosition[1] + drag.y / areaHeight * 192f).coerceIn(0f, 191f)
                    state.update(macro.id) { it.copy(x = dragPosition[0].roundToInt(), y = dragPosition[1].roundToInt()) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = macro.id.toString(),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorPanel(
    state: TouchMacroEditorState,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    Card(modifier = modifier, elevation = 8.dp) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.touch_macros),
                    style = MaterialTheme.typography.h6,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = state::add, enabled = state.canAdd) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.macro_add))
                }
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
                Button(onClick = onDone) { Text(stringResource(R.string.macro_done)) }
            }

            val selected = state.selected
            if (selected == null) {
                Text(
                    text = stringResource(if (state.macros.isEmpty()) R.string.macro_hint_empty else R.string.macro_hint_select),
                    style = MaterialTheme.typography.body2,
                )
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = selected.name,
                    onValueChange = { name -> state.update(selected.id) { it.copy(name = name.take(24)) } },
                    label = { Text(stringResource(R.string.macro_name)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "${selected.x}, ${selected.y}",
                    style = MaterialTheme.typography.caption,
                    textAlign = TextAlign.End,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.macro_mode), modifier = Modifier.padding(end = 8.dp))
                ModeOption(stringResource(R.string.macro_mode_hold), selected.mode == TouchMacro.Mode.HOLD) {
                    state.update(selected.id) { it.copy(mode = TouchMacro.Mode.HOLD) }
                }
                ModeOption(stringResource(R.string.macro_mode_tap), selected.mode == TouchMacro.Mode.TAP) {
                    state.update(selected.id) { it.copy(mode = TouchMacro.Mode.TAP) }
                }
            }

            if (state.capturing) {
                Text(
                    text = stringResource(R.string.macro_press_button),
                    style = MaterialTheme.typography.body1,
                    color = MaterialTheme.colors.secondary,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    selected.assignments.forEach { assignment ->
                        BindingChip(BindingCapture.describe(assignment)) {
                            state.update(selected.id) { it.copy(assignments = it.assignments - assignment) }
                        }
                    }
                    TextButton(onClick = { state.startCapture() }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(if (selected.assignments.isEmpty()) R.string.macro_bind_button else R.string.macro_bind_another))
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.macro_drag_hint),
                    style = MaterialTheme.typography.caption,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { state.delete(selected.id) }) {
                    Text(stringResource(R.string.macro_delete), color = MaterialTheme.colors.error)
                }
            }
        }
    }
}

@Composable
private fun ModeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .selectable(selected = selected, onClick = onClick)
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun BindingChip(label: String, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.3f), shape)
            .padding(start = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.body2)
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.mapping_remove_binding), modifier = Modifier.size(16.dp))
        }
    }
}
