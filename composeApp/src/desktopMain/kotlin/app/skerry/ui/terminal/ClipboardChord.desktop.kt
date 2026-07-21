package app.skerry.ui.terminal

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * Desktop Insert detection. Compose's [Key] comes from the AWT key code, and normally that is
 * `Key.Insert`; the raw AWT code is checked as a fallback so a toolkit or layout that names the key
 * differently still pastes on Shift+Insert.
 *
 * `Key.NumPad0` is deliberately NOT treated as Insert: with NumLock on it is the digit zero, and
 * claiming it would swallow `Shift+0` from the keypad.
 */
actual fun KeyEvent.isInsertKey(): Boolean {
    if (key == Key.Insert) return true
    val awt = nativeKeyEvent as? AwtKeyEvent ?: return false
    return awt.keyCode == AwtKeyEvent.VK_INSERT
}
