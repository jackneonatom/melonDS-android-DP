package me.magnum.melonds.ui.emulator.input

/**
 * The DS has a single touch point, but several things may want it at once: a real finger, a touch
 * macro and the analog touch stick. The highest-priority active source wins (finger > macro > stick).
 *
 * Whenever the touchscreen changes hands, and before any new touch after a release, the screen is left
 * untouched for at least [LIFT_GAP_MS]. The emulated DS samples the touchscreen once per frame, so
 * without that gap a switch would look like the finger sliding from one spot to another, and two quick
 * taps would merge into one.
 *
 * Pure Kotlin: [nowMs] is the clock and [requestFrame] asks for [onFrame] to be called on the next
 * display frame (while waiting out a gap).
 */
class TouchArbiter(
    private val native: NativeTouch,
    private val nowMs: () -> Long,
    private val requestFrame: () -> Unit,
) {
    interface NativeTouch {
        fun down(x: Int, y: Int)
        fun move(x: Int, y: Int)
        fun up()
    }

    enum class Source(val priority: Int) {
        STICK(1),
        MACRO(2),
        FINGER(3),
    }

    companion object {
        /** A bit over two 60 Hz frames. */
        const val LIFT_GAP_MS = 40L
    }

    private class SourceState {
        var down = false
        var x = 0
        var y = 0
        /** Distinguishes touches from the same source (e.g. different macros). */
        var token = 0
    }

    private val states = Source.entries.associateWith { SourceState() }

    private var nativeDown = false
    private var ownerSource: Source? = null
    private var ownerToken = 0
    private var nativeX = -1
    private var nativeY = -1
    private var lastUpMs = Long.MIN_VALUE / 2

    val isTouching: Boolean
        get() = nativeDown

    fun set(source: Source, down: Boolean, x: Int = 0, y: Int = 0, token: Int = 0) {
        val state = states.getValue(source)
        state.down = down
        if (down) {
            state.x = x
            state.y = y
            state.token = token
        }
        update()
    }

    fun isActive(source: Source): Boolean = states.getValue(source).down

    /** True if a source more important than [source] is active (so [source] won't get the screen). */
    fun higherPriorityActive(source: Source): Boolean {
        return Source.entries.any { it.priority > source.priority && states.getValue(it).down }
    }

    /** Releases everything immediately (e.g. when the emulator pauses). */
    fun reset() {
        states.values.forEach { it.down = false }
        update()
    }

    fun onFrame() {
        update()
    }

    private fun update() {
        val winner = Source.entries.filter { states.getValue(it).down }.maxByOrNull { it.priority }

        if (winner == null) {
            liftNative()
            return
        }

        val state = states.getValue(winner)
        if (nativeDown) {
            if (ownerSource == winner && ownerToken == state.token) {
                if (state.x != nativeX || state.y != nativeY) {
                    nativeX = state.x
                    nativeY = state.y
                    native.move(state.x, state.y)
                }
                return
            }
            // someone else takes over: lift first, touch down after the gap
            liftNative()
            requestFrame()
            return
        }

        if (nowMs() - lastUpMs < LIFT_GAP_MS) {
            requestFrame()
            return
        }

        nativeDown = true
        ownerSource = winner
        ownerToken = state.token
        nativeX = state.x
        nativeY = state.y
        native.down(state.x, state.y)
    }

    private fun liftNative() {
        if (nativeDown) {
            nativeDown = false
            ownerSource = null
            native.up()
            lastUpMs = nowMs()
        }
    }
}
