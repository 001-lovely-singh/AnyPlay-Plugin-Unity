package com.anyplay.engine

import android.view.KeyEvent

/** D-pad / joystick directions send the arrow keys. */
object InputMapper {
    fun keyCodeFor(button: GamepadButton): Int = when (button) {
        GamepadButton.UP -> KeyEvent.KEYCODE_DPAD_UP
        GamepadButton.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        GamepadButton.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        GamepadButton.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
    }
}
