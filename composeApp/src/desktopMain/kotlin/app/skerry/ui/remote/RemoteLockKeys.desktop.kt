package app.skerry.ui.remote

import androidx.compose.ui.input.key.KeyEvent
import java.awt.Toolkit

/**
 * AWT owns the answer on desktop; the event carries none. `getLockingKeyState` is allowed to throw
 * per key (X11 has no Kana, some toolkits no Scroll Lock), so each key degrades alone — but when
 * the platform can answer for none of them there is nothing worth syncing.
 */
actual fun readLockKeys(event: KeyEvent?): LockKeys? {
    val toolkit = runCatching { Toolkit.getDefaultToolkit() }.getOrNull() ?: return null
    val caps = lockStateOf(toolkit, java.awt.event.KeyEvent.VK_CAPS_LOCK)
    val num = lockStateOf(toolkit, java.awt.event.KeyEvent.VK_NUM_LOCK)
    val scroll = lockStateOf(toolkit, java.awt.event.KeyEvent.VK_SCROLL_LOCK)
    if (caps == null && num == null && scroll == null) return null
    return LockKeys(scroll = scroll ?: false, num = num ?: false, caps = caps ?: false)
}

private fun lockStateOf(toolkit: Toolkit, key: Int): Boolean? =
    runCatching { toolkit.getLockingKeyState(key) }.getOrNull()
