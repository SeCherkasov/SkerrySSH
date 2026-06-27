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
 * Кладёт пароль в системный буфер, помечая клип sensitive на Android 13+ (скрывает его из истории
 * буфера/превью), и планирует автоочистку через [CLIPBOARD_CLEAR_SECONDS] с — но только если к тому
 * моменту в буфере всё ещё наш пароль (иначе пользователь скопировал что-то поверх, не трогаем).
 * Контекст берём у [SafBridge] (тот же application-контекст, что и SFTP) — без утечки Activity.
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

/** Не-секретный текст: обычный клип без sensitive-пометки и автоочистки. */
actual fun copyTextToClipboard(text: String) {
    val ctx = SafBridge.context() ?: return
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("text", text))
}
