package app.skerry.ui.secure

import android.util.Log
import android.view.Window
import android.view.WindowManager
import java.lang.ref.WeakReference

/**
 * Weak reference to the Activity's window for applying FLAG_SECURE from shared UI code. install is
 * called from MainActivity.onCreate. Weak reference avoids holding the Activity after recreation.
 */
object WindowBridge {
    private var windowRef: WeakReference<Window>? = null

    fun install(window: Window) {
        windowRef = WeakReference(window)
    }

    fun window(): Window? = windowRef?.get()
}

actual fun applyPlatformSecureFlag(secure: Boolean) {
    val window = WindowBridge.window()
    if (window == null) {
        // install() wasn't called (or the Activity has no window) — the flag silently won't apply. Logged
        // so missing wiring (e.g. a new Activity without WindowBridge.install) is visible.
        if (secure) Log.e("SecureScreen", "WindowBridge has no window — FLAG_SECURE not applied")
        return
    }
    if (secure) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
