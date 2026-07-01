package app.skerry.ui.i18n

import androidx.compose.runtime.Composable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.language_system
import org.jetbrains.compose.resources.stringResource

/**
 * Отображаемое имя языка для пикера (Appearance → Language): конкретные языки — автонимом
 * ([UiLanguage.displayName]: «English», «Русский»), режим «System» локализуется по текущему языку
 * интерфейса.
 */
@Composable
fun UiLanguage.label(): String = when (this) {
    UiLanguage.System -> stringResource(Res.string.language_system)
    else -> displayName
}
