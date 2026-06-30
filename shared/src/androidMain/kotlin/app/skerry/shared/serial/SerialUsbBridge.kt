package app.skerry.shared.serial

import android.content.Context

/**
 * Держатель application-контекста для USB-OTG serial: [expect object SerialSystem] статичен и не может
 * принять Context конструктором, поэтому Android-actual берёт его отсюда. Ставится один раз из
 * `MainActivity.onCreate` через [install] (по образцу `AndroidLockContext`/`SafBridge`). Хранится только
 * applicationContext — Activity не удерживается.
 */
object SerialUsbBridge {
    @Volatile
    private var appContext: Context? = null

    /** Привязать application-контекст (идемпотентно). */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /** Текущий application-контекст или `null`, если [install] ещё не вызывался. */
    fun context(): Context? = appContext
}
