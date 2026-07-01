package app.skerry.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue

/**
 * Переопределение локали приложения для строковых ресурсов Compose. Окружение ресурсов CMP
 * (`LocalComposeEnvironment`) `internal`, поэтому язык меняется через системную локаль, которую это
 * окружение читает: на desktop — `Locale.getDefault()`, на Android — `Configuration`. `actual`
 * держит `staticCompositionLocalOf` с текущим language-tag; смена значения форсит рекомпозицию
 * потребителей `stringResource`. [provides] с `null` восстанавливает исходную системную локаль
 * (режим [UiLanguage.System] — автоопределение).
 */
expect object LocalAppLocale {
    /** Текущий применённый language-tag (BCP-47) — для потребителей/диагностики. */
    val current: String @Composable get

    /** Применить [languageTag] (BCP-47) как локаль приложения; `null` — вернуть системную. */
    @Composable
    infix fun provides(languageTag: String?): ProvidedValue<*>
}

/**
 * Обернуть [content] в выбранный язык интерфейса. [UiLanguage.System] ([UiLanguage.localeTag] == null)
 * оставляет системную локаль (автоопределение). Ставится в корне приложения над темой, чтобы весь
 * `stringResource` перечитывался при смене языка без перезапуска.
 */
@Composable
fun AppLocaleProvider(language: UiLanguage, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppLocale provides language.localeTag) {
        content()
    }
}
