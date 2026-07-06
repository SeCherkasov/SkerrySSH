package app.skerry.ui.secure

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Reference-counted holder for the screenshot-protection flag: enabled on the first acquire,
 * disabled only on the last release, so overlapping secure screens don't disable it prematurely.
 * `apply` runs from Compose effects (UI thread), so the counter needs no synchronization.
 */
class SecureFlagController(private val apply: (Boolean) -> Unit) {
    private var holders = 0

    fun acquire() {
        holders++
        if (holders == 1) apply(true)
    }

    fun release() {
        if (holders == 0) return
        holders--
        if (holders == 0) apply(false)
    }
}

/** Applies the platform screenshot-protection flag. Android: FLAG_SECURE; desktop: no-op. */
expect fun applyPlatformSecureFlag(secure: Boolean)

private val secureFlagController = SecureFlagController(::applyPlatformSecureFlag)

/**
 * Marks the screen as secret: while this composable is in composition, the window is protected
 * from screenshots and recent-apps previews. Android only; desktop is a no-op.
 */
@Composable
fun SecureScreen() {
    DisposableEffect(Unit) {
        secureFlagController.acquire()
        onDispose { secureFlagController.release() }
    }
}
