package app.skerry.ui.vault

import android.app.KeyguardManager
import android.content.Context

/**
 * Application context for checking device lock state, set in `MainActivity.onCreate`
 * (`appContext = applicationContext`). Holds only the application context, never an Activity.
 */
object AndroidLockContext {
    @Volatile
    var appContext: Context? = null
}

/**
 * Locks the vault on background only if the device is actually locked (keyguard). Switching to
 * another app (system SAF picker, notification shade) while the screen is unlocked does not lock
 * the vault. If the context isn't set yet, defaults to locking (true).
 */
actual fun deviceMandatesAutoLock(): Boolean {
    val ctx = AndroidLockContext.appContext ?: return true
    val keyguard = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return true
    return keyguard.isKeyguardLocked
}
