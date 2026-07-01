package app.skerry.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/**
 * Desktop: окружение строковых ресурсов Compose читает [Locale.getDefault]. [provides] выставляет её
 * в выбранную локаль (или восстанавливает исходную системную при `null`) и обновляет
 * composition-local тегом, что форсит рекомпозицию `stringResource`. Исходная системная локаль
 * запоминается при первом вызове, чтобы режим [UiLanguage.System] точно к ней возвращался.
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
        return local.provides(locale.toLanguageTag())
    }
}
