package app.skerry.ui.design

import androidx.compose.ui.input.key.Key

/**
 * Глобальный хоткей десктопного каркаса (титлбар/рейл/сессии), распознанный из аккорда клавиш.
 * Чистый тип без Compose-зависимостей — матчинг ([matchDesktopShortcut]) и исполнение
 * ([runDesktopShortcut]) тестируются без композиции.
 */
sealed interface DesktopShortcut {
    /** Открыть модалку «New connection» (⌘N / Ctrl+Shift+N). */
    data object NewConnection : DesktopShortcut

    /** Разделить/свести панель терминала активной вкладки (⌘D / Ctrl+Shift+D). */
    data object SplitTerminal : DesktopShortcut

    /** Открыть SFTP активной вкладки (⌘F / Ctrl+Shift+F). */
    data object OpenSftp : DesktopShortcut

    /** Сфокусировать строку ввода AI-бара (⌘/ / Ctrl+Shift+/). */
    data object FocusAiBar : DesktopShortcut

    /** Запереть vault (⌘L / Ctrl+Shift+L). */
    data object Lock : DesktopShortcut

    /** Следующая вкладка (Ctrl+Tab). */
    data object NextTab : DesktopShortcut

    /** Предыдущая вкладка (Ctrl+Shift+Tab). */
    data object PrevTab : DesktopShortcut

    /** Выбрать вкладку по номеру, 0-based (Alt+1..9). */
    data class SelectTab(val index: Int) : DesktopShortcut
}

/**
 * Сопоставить аккорд клавиш глобальному хоткею каркаса или `null`, если совпадения нет.
 *
 * Схема выбрана так, чтобы НЕ отбирать клавиши у терминала (корневой `onPreviewKeyEvent` видит их
 * раньше него):
 * - **Alt+цифра** — выбор вкладки. Требование запроса пользователя; сознательно перехватывает
 *   Alt-цифру (терминальный Meta-префикс `ESC 1`) в пользу навигации.
 * - **Ctrl+Tab / Ctrl+Shift+Tab** — переключение вкладок (Tab не буква, с терминальным
 *   `Ctrl+буква`→C0 не пересекается).
 * - **Модификатор приложения** = `⌘` (Meta без Ctrl/Alt) на macOS ИЛИ `Ctrl+Shift` на Linux/Windows.
 *   Требование Shift на Ctrl-пути оставляет чистый `Ctrl+буква` терминалу (Ctrl+L очистка, Ctrl+D EOF,
 *   Ctrl+C сигнал и т.д. продолжают работать в шелле), а `Ctrl+Shift+C/V` не задеты — это копипаст.
 *
 * AltGr (на многих раскладках = Ctrl+Alt) исключён из ветки Alt+цифра проверкой `!ctrl`.
 */
fun matchDesktopShortcut(ctrl: Boolean, shift: Boolean, alt: Boolean, meta: Boolean, key: Key): DesktopShortcut? {
    // Alt+цифра → выбрать вкладку (только Alt, без прочих модификаторов).
    if (alt && !ctrl && !meta && !shift) {
        digitIndex(key)?.let { return DesktopShortcut.SelectTab(it) }
    }
    // Переключение вкладок циклически.
    if (ctrl && !alt && !meta && key == Key.Tab) {
        return if (shift) DesktopShortcut.PrevTab else DesktopShortcut.NextTab
    }
    val appMod = (meta && !ctrl && !alt) || (ctrl && shift && !alt && !meta)
    if (!appMod) return null
    return when (key) {
        Key.N -> DesktopShortcut.NewConnection
        Key.D -> DesktopShortcut.SplitTerminal
        Key.F -> DesktopShortcut.OpenSftp
        Key.L -> DesktopShortcut.Lock
        Key.Slash -> DesktopShortcut.FocusAiBar
        else -> null
    }
}

/** Индекс вкладки (0-based) для цифровой клавиши верхнего ряда 1..9, иначе `null`. */
private fun digitIndex(key: Key): Int? = when (key) {
    Key.One -> 0
    Key.Two -> 1
    Key.Three -> 2
    Key.Four -> 3
    Key.Five -> 4
    Key.Six -> 5
    Key.Seven -> 6
    Key.Eight -> 7
    Key.Nine -> 8
    else -> null
}
