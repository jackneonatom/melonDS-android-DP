package me.magnum.melonds.domain.model

/**
 * Maps physical controller inputs to emulator inputs. Mapping is many-to-many: an emulator input can
 * have any number of bindings, and a physical input can be bound to several emulator inputs.
 */
class ControllerConfiguration(configList: List<InputConfig>) {
    companion object {
        val configurableInput = listOf(
            Input.A,
            Input.B,
            Input.X,
            Input.Y,
            Input.LEFT,
            Input.RIGHT,
            Input.UP,
            Input.DOWN,
            Input.L,
            Input.R,
            Input.START,
            Input.SELECT,
            Input.HINGE,
            Input.TOUCH_STICK_UP,
            Input.TOUCH_STICK_DOWN,
            Input.TOUCH_STICK_LEFT,
            Input.TOUCH_STICK_RIGHT,
            Input.PAUSE,
            Input.FAST_FORWARD,
            Input.MICROPHONE,
            Input.RESET,
            Input.SWAP_SCREENS,
            Input.QUICK_SAVE,
            Input.QUICK_LOAD,
            Input.REWIND,
        )

        // android.view.MotionEvent axis codes (kept as literals so this class has no Android dependency).
        // On Android, the right stick of almost every gamepad reports as AXIS_Z / AXIS_RZ.
        const val AXIS_Z = 11
        const val AXIS_RZ = 14

        /**
         * Bindings used for inputs that a saved configuration doesn't mention at all, i.e. inputs added
         * after the configuration was saved. An input the user explicitly cleared is saved as an empty
         * list and is left alone.
         */
        val defaultsForNewInputs: Map<Input, List<InputConfig.Assignment>> = mapOf(
            Input.TOUCH_STICK_UP to listOf(InputConfig.Assignment.Axis(null, AXIS_RZ, InputConfig.Assignment.Axis.Direction.NEGATIVE)),
            Input.TOUCH_STICK_DOWN to listOf(InputConfig.Assignment.Axis(null, AXIS_RZ, InputConfig.Assignment.Axis.Direction.POSITIVE)),
            Input.TOUCH_STICK_LEFT to listOf(InputConfig.Assignment.Axis(null, AXIS_Z, InputConfig.Assignment.Axis.Direction.NEGATIVE)),
            Input.TOUCH_STICK_RIGHT to listOf(InputConfig.Assignment.Axis(null, AXIS_Z, InputConfig.Assignment.Axis.Direction.POSITIVE)),
        )
    }

    val inputMapper: List<InputConfig> = configurableInput.map { input ->
        configList.firstOrNull { it.input == input }
            ?: InputConfig(input, defaultsForNewInputs[input].orEmpty())
    }

    fun getInputAssignments(input: Input): List<InputConfig.Assignment>? {
        return inputMapper.firstOrNull { it.input == input && it.hasKeyAssigned() }?.assignments
    }

    /** All emulator inputs bound to [key]. */
    fun keyToInputs(key: Int): List<Input> {
        return inputMapper.filter { config ->
            config.assignments.any { (it as? InputConfig.Assignment.Key)?.keyCode == key }
        }.map { it.input }
    }

    /** All emulator inputs bound to the given axis direction. */
    fun axisToInputs(axis: Int, direction: InputConfig.Assignment.Axis.Direction): List<Input> {
        return inputMapper.filter { config ->
            config.assignments.any { (it as? InputConfig.Assignment.Axis)?.let { ax -> ax.axisCode == axis && ax.direction == direction } ?: false }
        }.map { it.input }
    }

    fun keyToInput(key: Int): Input? = keyToInputs(key).firstOrNull()

    fun axisToInput(axis: Int, direction: InputConfig.Assignment.Axis.Direction): Input? = axisToInputs(axis, direction).firstOrNull()

    fun withInputConfig(config: InputConfig): ControllerConfiguration {
        return ControllerConfiguration(inputMapper.map { if (it.input == config.input) config else it })
    }
}
