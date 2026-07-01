package app.skerry.ui.i18n

/**
 * Язык интерфейса, выбираемый в настройках (Appearance → Language). [System] означает
 * автоопределение по локали ОС ([localeTag] == null): окружение строковых ресурсов Compose само
 * берёт системную локаль (поддерживаемая — русский, иначе английский-fallback). [id] — стабильный
 * ключ для персиста (desktop `main` / Android `MainActivity`), [localeTag] — BCP-47 тег для
 * переопределения локали ([LocalAppLocale]). Зеркалит стиль [app.skerry.ui.terminal.TerminalFont].
 */
enum class UiLanguage(val id: String, val localeTag: String?, val displayName: String) {
    /** По языку системы (дефолт): ресурсы берут локаль ОС; смены языка на лету не навязываем. */
    System("system", null, "System"),

    /** Английский — язык-источник строк и общий fallback. */
    English("en", "en", "English"),

    /** Русский. */
    Russian("ru", "ru", "Русский");

    companion object {
        val DEFAULT = System

        /** Разобрать сохранённый [id] обратно в значение; неизвестный/`null` → [DEFAULT]. */
        fun fromId(id: String?): UiLanguage = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Англоязычное имя языка для промпта AI («English»/«Russian») по применённому BCP-47 тегу локали
 * ([LocalAppLocale]). Терминальный AI-бар пишет INFO/ASK на языке интерфейса; для режима
 * [UiLanguage.System] тег уже разрешён к фактической локали ОС, поэтому маппим именно тег, а не выбор.
 */
fun aiResponseLanguageName(localeTag: String): String =
    if (localeTag.startsWith("ru", ignoreCase = true)) "Russian" else "English"
