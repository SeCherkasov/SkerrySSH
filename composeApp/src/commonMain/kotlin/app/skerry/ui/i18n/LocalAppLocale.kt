package app.skerry.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue

/**
 * App locale override for Compose string resources. CMP's resource environment
 * (`LocalComposeEnvironment`) is `internal`, so the language is changed via the system locale it
 * reads: `Locale.getDefault()` on desktop, `Configuration` on Android. `actual` holds a
 * `staticCompositionLocalOf` with the current language tag; changing the value forces
 * recomposition of `stringResource` consumers. [provides] with `null` restores the original
 * system locale ([UiLanguage.System] mode, auto-detect).
 */
expect object LocalAppLocale {
    /** Currently applied language tag (BCP-47), for consumers/diagnostics. */
    val current: String @Composable get

    /** Apply [languageTag] (BCP-47) as the app locale; `null` reverts to the system locale. */
    @Composable
    infix fun provides(languageTag: String?): ProvidedValue<*>
}

/**
 * Wrap [content] in the selected UI language. [UiLanguage.System] ([UiLanguage.localeTag] == null)
 * keeps the system locale (auto-detect). Placed at the app root above the theme, so every
 * `stringResource` re-reads on language change without a restart.
 */
@Composable
fun AppLocaleProvider(language: UiLanguage, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppLocale provides language.localeTag) {
        content()
    }
}
