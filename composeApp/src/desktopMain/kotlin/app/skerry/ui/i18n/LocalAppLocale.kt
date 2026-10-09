package app.skerry.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalLocalization
import androidx.compose.ui.platform.PlatformLocalization
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.text_context_copy
import app.skerry.ui.generated.resources.text_context_cut
import app.skerry.ui.generated.resources.text_context_paste
import app.skerry.ui.generated.resources.text_context_select_all
import org.jetbrains.compose.resources.stringResource
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
        val locale = if (languageTag == null) systemDefault!! else Locale.forLanguageTag(languageTag)
        Locale.setDefault(locale)
        return local.provides(locale.toLanguageTag())
    }

    @Composable
    actual fun ProvidePlatformLocalization(content: @Composable () -> Unit) {
        // Compose's default LocalLocalization captures its labels once, before later locale changes.
        val copy = stringResource(Res.string.text_context_copy)
        val cut = stringResource(Res.string.text_context_cut)
        val paste = stringResource(Res.string.text_context_paste)
        val selectAll = stringResource(Res.string.text_context_select_all)
        val localization = remember(copy, cut, paste, selectAll) {
            object : PlatformLocalization {
                override val copy = copy
                override val cut = cut
                override val paste = paste
                override val selectAll = selectAll
            }
        }
        CompositionLocalProvider(LocalLocalization provides localization, content = content)
    }
}
