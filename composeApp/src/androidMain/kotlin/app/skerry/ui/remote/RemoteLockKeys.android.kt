package app.skerry.ui.remote

import androidx.compose.ui.input.key.KeyEvent

/**
 * Android has no global lock-key read; the state rides on each key event's meta flags, which is
 * exactly when it matters — a session that never sees a hardware key has nothing to drift.
 */
actual fun readLockKeys(event: KeyEvent?): LockKeys? {
    val native = event?.nativeKeyEvent ?: return null
    return LockKeys(
        scroll = native.isScrollLockOn,
        num = native.isNumLockOn,
        caps = native.isCapsLockOn,
    )
}
