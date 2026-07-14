package app.skerry.ui.terminal

import androidx.compose.ui.input.key.KeyEvent

// Soft-keyboard (and injected) key events carry a negative deviceId (KeyCharacterMap.VIRTUAL_KEYBOARD
// is -1); physical keyboards report a real deviceId >= 0.
internal actual fun isSoftKeyboardEvent(event: KeyEvent): Boolean =
    event.nativeKeyEvent.deviceId < 0
