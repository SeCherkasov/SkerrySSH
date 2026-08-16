package app.skerry.ui.remote

import androidx.compose.ui.input.key.KeyEvent

/** The local machine's lock-key state, as far as the platform can tell. */
data class LockKeys(val scroll: Boolean, val num: Boolean, val caps: Boolean)

/**
 * Read the local lock-key state, from [event]'s own metadata where the platform carries it
 * (an Android key event does) or from the system where it does not (desktop asks AWT and ignores
 * the event). Null when the platform cannot say — then no sync is sent, which beats guessing.
 */
expect fun readLockKeys(event: KeyEvent?): LockKeys?
