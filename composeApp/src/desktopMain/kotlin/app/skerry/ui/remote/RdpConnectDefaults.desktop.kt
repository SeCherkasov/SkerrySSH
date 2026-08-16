package app.skerry.ui.remote

import java.awt.im.InputContext
import java.util.Locale

/**
 * The input context's locale is the active layout where the toolkit tracks one; the default locale
 * is the fallback — imperfect, but the machine a user types Cyrillic on has a Cyrillic default.
 */
actual fun currentKeyboardLayout(): Int {
    val locale = runCatching { InputContext.getInstance()?.locale }.getOrNull() ?: Locale.getDefault()
    return keyboardLayoutFor(locale.language, locale.country)
}
