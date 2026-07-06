package app.skerry.shared.serial

import android.content.Context

/**
 * Holds the application context for USB-OTG serial: [expect object SerialSystem] is static and
 * can't take a Context constructor param, so the Android actual pulls it from here. Installed once
 * from `MainActivity.onCreate` via [install] (same pattern as `AndroidLockContext`/`SafBridge`).
 * Only the applicationContext is stored — the Activity itself is never retained.
 */
object SerialUsbBridge {
    @Volatile
    private var appContext: Context? = null

    /** Bind the application context (idempotent). */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /** Current application context, or `null` if [install] hasn't been called yet. */
    fun context(): Context? = appContext
}
