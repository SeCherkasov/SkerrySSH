package app.skerry.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Android: Compose's string resource environment reads locale from [android.content.res.Configuration].
 * [provides] sets the chosen locale via [Locale.setDefault] plus the active context's `Configuration`
 * (or restores the system locale on `null`) and updates a composition-local to trigger `stringResource`
 * recomposition. The original system locale is captured on first call.
 */
actual object LocalAppLocale {
    private var systemDefault: Locale? = null
    private val local = staticCompositionLocalOf { Locale.getDefault().toLanguageTag() }

    actual val current: String
        @Composable get() = local.current

    @Composable
    actual infix fun provides(languageTag: String?): ProvidedValue<*> {
        if (systemDefault == null) systemDefault = Locale.getDefault()
        val locale = if (languageTag == null) systemDefault!! else Locale.forLanguageTag(languageTag)
        Locale.setDefault(locale)
        val configuration = LocalConfiguration.current
        configuration.setLocale(locale)
        val resources = LocalContext.current.resources
        @Suppress("DEPRECATION")
        resources.updateConfiguration(configuration, resources.displayMetrics)
        return local.provides(locale.toLanguageTag())
    }
}
