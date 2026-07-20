package app.skerry.ui.i18n

/**
 * Interface language, selected in settings (Appearance → Language). [System] means autodetect
 * from the OS locale ([localeTag] == null): Compose string resources pick up the system locale
 * (Russian supported, otherwise English fallback). [id] is the stable persistence key (desktop
 * `main` / Android `MainActivity`), [localeTag] is the BCP-47 tag for locale override
 * ([LocalAppLocale]).
 */
enum class UiLanguage(val id: String, val localeTag: String?, val displayName: String) {
    /** Follows the system language (default). */
    System("system", null, "System"),

    /** English — source language for strings and the general fallback. */
    English("en", "en", "English"),

    /** Russian. */
    Russian("ru", "ru", "Русский"),

    /** Simplified Chinese. */
    Chinese("zh", "zh", "简体中文");

    companion object {
        val DEFAULT = System

        /** Parses a stored [id] back into a value; unknown/`null` falls back to [DEFAULT]. */
        fun fromId(id: String?): UiLanguage = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * English name of the language for the AI prompt ("English"/"Russian") from the applied BCP-47
 * locale tag ([LocalAppLocale]). Maps the resolved tag, not the selected [UiLanguage], since
 * [UiLanguage.System] is already resolved to the actual OS locale by that point.
 */
fun aiResponseLanguageName(localeTag: String): String = when {
    localeTag.startsWith("ru", ignoreCase = true) -> "Russian"
    localeTag.startsWith("zh", ignoreCase = true) -> "Chinese"
    else -> "English"
}
