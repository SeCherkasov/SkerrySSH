package app.skerry.ui.terminal

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key

/**
 * Android Insert detection: a hardware keyboard reports `KEYCODE_INSERT`, which Compose maps to
 * [Key.Insert]. The keypad's 0 stays a digit here (Android has no NumLock-off aliasing).
 */
actual fun KeyEvent.isInsertKey(): Boolean = key == Key.Insert
