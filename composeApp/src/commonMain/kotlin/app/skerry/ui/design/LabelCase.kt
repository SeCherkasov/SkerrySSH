package app.skerry.ui.design

import androidx.compose.runtime.Composable
import app.skerry.ui.i18n.LocalAppLocale

/**
 * Uppercase for the small-caps section/field labels of the design system.
 *
 * `String.uppercase()` is locale-invariant (Locale.ROOT), which is wrong for Turkish/Azerbaijani:
 * dotted `i` must become `İ`, dotless `ı` must become `I`. Scripts without case (Chinese, …) are
 * left alone by `uppercase()` itself, so only the dotted-i pair needs handling.
 */
@Composable
fun labelUppercase(text: String): String = uppercaseForLocale(text, LocalAppLocale.current)

/** Testable core of [labelUppercase]; [languageTag] is BCP-47 (e.g. `tr-TR`). */
fun uppercaseForLocale(text: String, languageTag: String): String {
    val language = languageTag.substringBefore('-').substringBefore('_').lowercase()
    if (language != "tr" && language != "az") return text.uppercase()
    return buildString(text.length) {
        for (ch in text) when (ch) {
            'i' -> append('İ')
            'ı' -> append('I')
            else -> append(ch.uppercase())
        }
    }
}
