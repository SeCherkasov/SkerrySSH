package app.skerry.ui.remote

import java.util.Locale

/** Android has no per-window input context; the default locale tracks the user's language. */
actual fun currentKeyboardLayout(): Int {
    val locale = Locale.getDefault()
    return keyboardLayoutFor(locale.language, locale.country)
}
