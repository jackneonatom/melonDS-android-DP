package me.magnum.melonds.domain.model

/**
 * Settings for driving the DS touchscreen with an analog stick (the "touch stick" controls).
 * Coordinates are in DS touchscreen pixels (256 x 192).
 */
data class TouchStickSettings(
    val mode: Mode = Mode.OFF,
    /** Where the virtual finger rests. */
    val centerX: Int = 128,
    val centerY: Int = 96,
    /** Joystick mode: how far the finger moves from the center at full tilt. */
    val radius: Int = 64,
    /** Camera mode: finger speed at full tilt, in DS pixels per second. */
    val speed: Int = 500,
    /** Camera mode: how far the finger may drift from the center before it lifts and starts over. */
    val recenterDistance: Int = 56,
    /** Tilt ignored around the stick's center, 0..0.9. */
    val deadzone: Float = 0.15f,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
) {
    enum class Mode {
        /** Touch stick does nothing. */
        OFF,
        /**
         * The finger is held at center + tilt x radius while the stick is tilted, and lifts when it
         * returns to center. For games with an on-screen stick or d-pad on the touchscreen.
         */
        JOYSTICK,
        /**
         * The finger drags across the screen with speed proportional to tilt, lifting and re-touching
         * at the center when it has drifted too far. For aiming and camera control (FPS style).
         */
        CAMERA,
    }
}
