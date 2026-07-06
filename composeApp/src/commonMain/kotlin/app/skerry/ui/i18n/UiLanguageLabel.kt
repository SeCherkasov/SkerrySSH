package app.skerry.ui.i18n

import androidx.compose.runtime.Composable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.language_system
import org.jetbrains.compose.resources.stringResource

/**
 * Display name of a language for the picker (Appearance → Language): concrete languages use
 * their autonym ([UiLanguage.displayName]), "System" is localized to the current UI language.
 */
@Composable
fun UiLanguage.label(): String = when (this) {
    UiLanguage.System -> stringResource(Res.string.language_system)
    else -> displayName
}
