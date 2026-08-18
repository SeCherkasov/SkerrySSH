package app.skerry.ui.i18n

import java.util.Locale

/**
 * Runs [block] with the JVM default locale set to [tag], and restores it afterwards.
 *
 * Compose Resources read the default locale outside a composition (`getSystemResourceEnvironment`
 * asks `Locale.getDefault()` on every call), and `ResourceEnvironment` cannot be built from outside
 * the library — so setting the process-global is the only way a test can read another language.
 * The suite shares one JVM and pins `user.language=en`, which is why the restore is not optional.
 */
internal suspend fun withLocale(tag: String, block: suspend () -> Unit) {
    val previous = Locale.getDefault()
    Locale.setDefault(Locale.forLanguageTag(tag))
    try {
        block()
    } finally {
        Locale.setDefault(previous)
    }
}
