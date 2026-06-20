package app.skerry.ui.vault

import android.app.KeyguardManager
import android.content.Context

/**
 * Контекст приложения для проверки состояния блокировки устройства. Заполняется в
 * `MainActivity.onCreate` (`appContext = applicationContext`). Держит только application-контекст —
 * без утечки Activity.
 */
object AndroidLockContext {
    @Volatile
    var appContext: Context? = null
}

/**
 * Android: запираем vault при уходе в фон ТОЛЬКО если устройство реально заблокировано (keyguard).
 * Переключение на другое приложение (системный SAF-пикер, шторка) при разблокированном экране фон
 * vault не запирает — иначе выбор файла рвал бы сессию. Если контекст ещё не установлен —
 * перестраховываемся и запираем (true).
 */
actual fun deviceMandatesAutoLock(): Boolean {
    val ctx = AndroidLockContext.appContext ?: return true
    val keyguard = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return true
    return keyguard.isKeyguardLocked
}
