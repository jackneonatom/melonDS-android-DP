package me.magnum.melonds.ui.emulator.input

import me.magnum.melonds.domain.model.ControllerConfiguration
import me.magnum.melonds.domain.model.Input
import me.magnum.melonds.domain.model.InputConfig
import kotlin.math.max

/**
 * Resolves physical controller state into emulator inputs, PPSSPP style:
 *  - an emulator input can have any number of bindings, and is held while *any* of them is active
 *    (so releasing one of two buttons bound to A doesn't release A);
 *  - one physical input can be bound to several emulator inputs, and drives all of them.
 *
 * Inputs listed in [analogInputs] also report a 0..1 analog value (the strongest of their bindings),
 * used for the analog stick -> touchscreen feature.
 *
 * Pure Kotlin (no Android types) so it can be unit tested.
 */
class InputMappingEngine(
    inputConfigs: List<InputConfig>,
    private val analogInputs: Set<Input> = emptySet(),
    private val onPress: (Input) -> Unit,
    private val onRelease: (Input) -> Unit,
    private val onAnalog: (Input, Float) -> Unit = { _, _ -> },
) {
    constructor(
        configuration: ControllerConfiguration,
        analogInputs: Set<Input> = emptySet(),
        onPress: (Input) -> Unit,
        onRelease: (Input) -> Unit,
        onAnalog: (Input, Float) -> Unit = { _, _ -> },
    ) : this(configuration.inputMapper, analogInputs, onPress, onRelease, onAnalog)

    companion object {
        /** Stick deflection needed to press a button bound to an axis direction. */
        const val AXIS_PRESS_THRESHOLD = 0.5f
        /** Deflection below which it's released again (a little lower, so it doesn't flicker at the edge). */
        const val AXIS_RELEASE_THRESHOLD = 0.4f
    }

    private class Binding(val input: Input, val assignment: InputConfig.Assignment) {
        var active = false
    }

    private val bindings: List<Binding> = inputConfigs.flatMap { config ->
        config.assignments.filter { it != InputConfig.Assignment.None }.map { Binding(config.input, it) }
    }
    private val bindingsByInput: Map<Input, List<Binding>> = bindings.groupBy { it.input }
    private val keyBindings: Map<Int, List<Binding>> = bindings
        .filter { it.assignment is InputConfig.Assignment.Key }
        .groupBy { (it.assignment as InputConfig.Assignment.Key).keyCode }
    private val axisBindings: Map<Int, List<Binding>> = bindings
        .filter { it.assignment is InputConfig.Assignment.Axis }
        .groupBy { (it.assignment as InputConfig.Assignment.Axis).axisCode }

    /** Axis codes that have at least one binding. */
    val boundAxes: Set<Int> = axisBindings.keys

    // keyCode -> devices currently holding it
    private val pressedKeys = HashMap<Int, MutableSet<Int>>()
    // axisCode -> (deviceId -> value)
    private val axisValues = HashMap<Int, HashMap<Int, Float>>()

    private val inputPressed = HashMap<Input, Boolean>()
    private val inputAnalog = HashMap<Input, Float>()

    fun isKeyBound(keyCode: Int): Boolean = keyBindings.containsKey(keyCode)

    fun isPressed(input: Input): Boolean = inputPressed[input] == true

    fun analogValue(input: Input): Float = inputAnalog[input] ?: 0f

    /**
     * Key down/up from [deviceId]. Repeated downs (auto-repeat) are harmless.
     * @return true if the key is bound to anything
     */
    fun onKey(deviceId: Int, keyCode: Int, down: Boolean): Boolean {
        val affected = keyBindings[keyCode] ?: return false
        val devices = pressedKeys.getOrPut(keyCode) { HashSet() }
        if (down) devices.add(deviceId) else devices.remove(deviceId)

        affected.forEach { binding ->
            val key = binding.assignment as InputConfig.Assignment.Key
            binding.active = if (key.deviceId == null) devices.isNotEmpty() else devices.contains(key.deviceId)
        }
        refresh(affected)
        return true
    }

    /**
     * New values for the bound axes of [deviceId]. [readAxis] returns the current value of an axis.
     * @return true if any axis is bound
     */
    fun onAxes(deviceId: Int, readAxis: (Int) -> Float): Boolean {
        if (axisBindings.isEmpty()) return false

        val affected = ArrayList<Binding>()
        axisBindings.forEach { (axisCode, list) ->
            val value = readAxis(axisCode)
            val perDevice = axisValues.getOrPut(axisCode) { HashMap() }
            if (perDevice[deviceId] == value) return@forEach
            perDevice[deviceId] = value

            list.forEach { binding ->
                val strength = axisStrength(binding.assignment as InputConfig.Assignment.Axis)
                binding.active = if (binding.active) strength >= AXIS_RELEASE_THRESHOLD else strength >= AXIS_PRESS_THRESHOLD
            }
            affected.addAll(list)
        }
        refresh(affected)
        return true
    }

    /** Releases everything, e.g. when the emulator is paused or a controller disconnects. */
    fun releaseAll() {
        pressedKeys.clear()
        axisValues.clear()
        bindings.forEach { it.active = false }
        refresh(bindings)
    }

    /** How far the axis is pushed in the binding's direction, 0..1. */
    private fun axisStrength(axis: InputConfig.Assignment.Axis): Float {
        val perDevice = axisValues[axis.axisCode] ?: return 0f
        var strength = 0f
        perDevice.forEach { (deviceId, value) ->
            if (axis.deviceId != null && axis.deviceId != deviceId) return@forEach
            val directional = when (axis.direction) {
                InputConfig.Assignment.Axis.Direction.POSITIVE -> value
                InputConfig.Assignment.Axis.Direction.NEGATIVE -> -value
            }
            strength = max(strength, directional.coerceIn(0f, 1f))
        }
        return strength
    }

    private fun bindingAnalog(binding: Binding): Float {
        return when (val a = binding.assignment) {
            is InputConfig.Assignment.Key -> if (binding.active) 1f else 0f
            is InputConfig.Assignment.Axis -> axisStrength(a)
            InputConfig.Assignment.None -> 0f
        }
    }

    private fun refresh(affected: Collection<Binding>) {
        affected.map { it.input }.distinct().forEach { input ->
            val inputBindings = bindingsByInput[input].orEmpty()

            val pressed = inputBindings.any { it.active }
            if (pressed != (inputPressed[input] == true)) {
                inputPressed[input] = pressed
                if (pressed) onPress(input) else onRelease(input)
            }

            if (input in analogInputs) {
                val value = inputBindings.maxOfOrNull { bindingAnalog(it) } ?: 0f
                if (value != (inputAnalog[input] ?: 0f)) {
                    inputAnalog[input] = value
                    onAnalog(input, value)
                }
            }
        }
    }
}
