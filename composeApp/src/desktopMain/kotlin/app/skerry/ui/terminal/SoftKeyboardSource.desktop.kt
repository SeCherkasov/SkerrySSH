package app.skerry.ui.terminal

import androidx.compose.ui.input.key.KeyEvent

// Desktop has no on-screen keyboard, and the terminal's IME capture path is off there anyway.
internal actual fun isSoftKeyboardEvent(event: KeyEvent): Boolean = false
