package app.skerry.ui.secure

import android.util.Log
import android.view.Window
import android.view.WindowManager
import java.lang.ref.WeakReference

/**
 * Слабая ссылка на окно Activity для применения FLAG_SECURE из общего UI-кода. install зовётся в
 * MainActivity.onCreate (как SafBridge). Слабая ссылка — чтобы не держать Activity после пересоздания.
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
        // install() не вызван (или Activity нет окна) — защита молча не применится. Логируем,
        // чтобы пропущенная проводка (напр. новая Activity без WindowBridge.install) была видна.
        if (secure) Log.e("SecureScreen", "WindowBridge has no window — FLAG_SECURE not applied")
        return
    }
    if (secure) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
