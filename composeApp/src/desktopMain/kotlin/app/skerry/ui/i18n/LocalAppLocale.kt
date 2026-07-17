package app.skerry.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/**
 * Compose's string resources read [Locale.getDefault]. [provides] sets it to the chosen locale
 * (or restores the original system locale on `null`) and updates a composition-local tag to force
 * `stringResource` recomposition. The original system locale is captured on first call so
 * [UiLanguage.System] can return to it exactly.
 */
actual object LocalAppLocale {
    private var systemDefault: Locale? = null
    private val local = staticCompositionLocalOf { Locale.getDefault().toLanguageTag() }

    actual val current: String
        @Composable get() = local.current

    @Composable
    actual infix fun provides(languageTag: String?): ProvidedValue<*> {
        if (systemDefault == null) systemDefault = Locale.getDefault()
        val locale = if (languageTag == null) {
            val sys = systemDefault!!
            if (sys.language in setOf("en", "ru", "zh")) sys else Locale.forLanguageTag("zh")
        } else Locale.forLanguageTag(languageTag)
        Locale.setDefault(locale)
        return local.provides(locale.toLanguageTag())
    }
}
