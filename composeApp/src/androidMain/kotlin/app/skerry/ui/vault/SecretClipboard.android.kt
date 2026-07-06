package app.skerry.ui.vault

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import app.skerry.ui.sftp.SafBridge

/**
 * Copies a password to the system clipboard, marking the clip sensitive on Android 13+ (hides it
 * from clipboard history/preview), and schedules auto-clear after [CLIPBOARD_CLEAR_SECONDS] —
 * only if the clipboard still holds this password at that point. Uses [SafBridge]'s application
 * context to avoid leaking an Activity.
 */
actual fun copyPasswordToClipboard(password: String) {
    val ctx = SafBridge.context() ?: return
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText("password", password).apply {
        if (Build.VERSION.SDK_INT >= 33) {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
    }
    cm.setPrimaryClip(clip)
    Handler(Looper.getMainLooper()).postDelayed({
        val current = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        if (current == password) {
            if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
            else cm.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }, CLIPBOARD_CLEAR_SECONDS * 1000L)
}

/** Copies non-secret text: a plain clip, no sensitive flag or auto-clear. */
actual fun copyTextToClipboard(text: String) {
    val ctx = SafBridge.context() ?: return
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("text", text))
}
