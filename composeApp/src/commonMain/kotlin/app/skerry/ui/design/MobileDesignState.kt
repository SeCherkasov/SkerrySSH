package app.skerry.ui.design

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.host.Host
import app.skerry.ui.terminal.DEFAULT_TERMINAL_FONT_SIZE
import app.skerry.ui.terminal.TERMINAL_FONT_SIZES
import app.skerry.ui.i18n.UiLanguage
import app.skerry.ui.terminal.TerminalFont
import app.skerry.ui.terminal.TerminalTheme
import app.skerry.ui.terminal.TerminalThemes

/**
 * Нижняя навигация — ровно 4 корневых таба ([showTabs]=true). [icon] — лигатура Material Symbols
 * (см. [Sym]), согласована с desktop-rail ([RAIL]) там, где раздел совпадает (Snippets/Vault). Files
 * в баре нет: SFTP открывается push-экраном ([MobileRoute.Files]) с карточки хоста (кнопка SFTP), как
 * терминал — отдельный корневой таб дублировал бы его.
 */
enum class MobileTab(val icon: String, val label: String) {
    Hosts("dns", "Hosts"),
    Snippets("code_blocks", "Snippets"),
    Vault("vpn_key", "Vault"),
    More("more_horiz", "More"),
}

/**
 * Полноэкранные push-экраны поверх таб-навигации (таб-бар скрыт): терминал и деталь хоста
 * открываются из Hosts, SFTP (Files) — кнопкой SFTP на карточке хоста, а Ports/Known/Team — из таба More.
 */
enum class MobileRoute { Terminal, Files, HostDetail, Ports, Known, Team, Appearance, Sync, Ai }

/** Что должен сделать системный «назад» на мобильном каркасе. */
enum class MobileBackAction {
    /** Закрыть открытый полноэкранный push-экран (вернуться на подлежащий таб). */
    PopRoute,

    /** Уйти с не-корневого таба на корневой Hosts. */
    GoHome,
}

/**
 * Решает, что делает системный «назад» по состоянию навигации каркаса. `null` — перехватывать нечего
 * (корневой таб Hosts без открытого push-экрана): событие отдаётся системе, которая закрывает Activity
 * (штатный выход из приложения). Открытые листы/диалоги здесь НЕ учитываются — они перехватывают свой
 * back сами (собственными `BackHandler` поверх этого), поэтому до навигации событие при них не доходит.
 */
fun mobileBackAction(route: MobileRoute?, tab: MobileTab): MobileBackAction? = when {
    route != null -> MobileBackAction.PopRoute
    tab != MobileTab.Hosts -> MobileBackAction.GoHome
    else -> null
}

/**
 * Состояние мобильного макета — навигация (текущий таб + открытый push-экран) и оверлей листа
 * New connection. Чисто UI-состояние, мутаторы инкапсулированы (`private set`), как в
 * [app.skerry.ui.session.SessionsController]. Блокировка vault живёт в `VaultGate`, а не здесь.
 */
@Stable
class MobileDesignState(
    // Свёрнутые папки хостов в списке (имена групп). Стартовое значение читается из персиста при
    // запуске, колбэк пишет его обратно — состояние папок переживает перезапуск. Дефолты (всё
    // развёрнуто, no-op) сохраняют прежнее поведение для превью/тестов.
    initialCollapsedGroups: Set<String> = emptySet(),
    private val onCollapsedGroupsChange: (Set<String>) -> Unit = {},
    // Шрифт терминала (More → Appearance → Font) и его кегль. Стартовые значения читаются из персиста
    // при запуске, колбэки пишут обратно — выбор переживает перезапуск. Дефолты (Hack 13px, no-op) —
    // для превью/тестов.
    initialTerminalFont: TerminalFont = TerminalFont.DEFAULT,
    private val onTerminalFontChange: (TerminalFont) -> Unit = {},
    initialTerminalFontSize: Int = DEFAULT_TERMINAL_FONT_SIZE,
    private val onTerminalFontSizeChange: (Int) -> Unit = {},
    // Цветовая тема терминала (More → Appearance → карточки тем). Проводится в терминал через
    // [app.skerry.ui.terminal.LocalTerminalTheme]; на мобильном пока не персистится (в памяти).
    initialTerminalTheme: TerminalTheme = TerminalThemes.DEFAULT,
    private val onTerminalThemeChange: (TerminalTheme) -> Unit = {},
    // Язык интерфейса (More → Appearance → Language). Стартовое значение читается из персиста при
    // запуске, колбэк пишет его обратно — выбор переживает перезапуск. Дефолты (System, no-op) —
    // автоопределение по локали ОС для превью/тестов.
    initialUiLanguage: UiLanguage = UiLanguage.DEFAULT,
    private val onUiLanguageChange: (UiLanguage) -> Unit = {},
) {
    var tab: MobileTab by mutableStateOf(MobileTab.Hosts); private set
    var route: MobileRoute? by mutableStateOf(null); private set
    var sheetNewConn: Boolean by mutableStateOf(false); private set

    /**
     * Профиль, открытый листом New connection в режиме правки (Edit с экрана детали), либо `null` —
     * лист в режиме создания. Лист предзаполняет форму из него ([NewConnectionFormState.fromHost]) и
     * удерживает [Host.id] при сохранении (паритет desktop-модалки с её параметром `editHost`).
     */
    var editingHost: Host? by mutableStateOf(null); private set

    /** Идентификатор хоста, открытого на [MobileRoute.HostDetail] — экран читает его из стора по id. */
    var selectedHostId: String? by mutableStateOf(null); private set

    /**
     * Открыт ли модальный оверлей таба (например, vault-диалог Generate/Import) — таб-бар при этом
     * прячется, иначе он плавает поверх диалога и перекрывает нижние поля ввода над клавиатурой.
     * Мутируется только через [modalOverlay] (инкапсуляция, как у остальных полей).
     */
    var modalOpen: Boolean by mutableStateOf(false); private set

    /** Пометить, открыт ли модальный оверлей текущего таба (vault-диалоги/лист деталей) — прячет таб-бар. */
    fun modalOverlay(open: Boolean) { modalOpen = open }

    /** Таб-бар виден только на корневых экранах без открытой модалки: push-экраны полноэкранные. */
    val showTabs: Boolean get() = route == null && !modalOpen

    /** Переключить корневой таб — закрывает любой открытый push-экран и сбрасывает выбранный хост. */
    fun select(t: MobileTab) {
        tab = t
        route = null
        selectedHostId = null
    }

    /** Открыть полноэкранный под-экран поверх текущего таба. */
    fun push(r: MobileRoute) { route = r }

    /** Открыть деталь конкретного хоста (тап по строке списка): запоминает id и пушит экран. */
    fun openHost(id: String) {
        selectedHostId = id
        route = MobileRoute.HostDetail
    }

    /** Вернуться с push-экрана на текущий таб (back-стрелка макета); снимает выбор хоста. */
    fun pop() {
        route = null
        selectedHostId = null
    }

    /** Имена свёрнутых папок хостов (их список хостов скрыт). */
    var collapsedGroups: Set<String> by mutableStateOf(initialCollapsedGroups); private set

    /** Свёрнута ли папка [name] (её список хостов скрыт). */
    fun isGroupCollapsed(name: String): Boolean = name in collapsedGroups

    /** Свернуть/развернуть папку [name] и сообщить новый набор наружу (для персиста). */
    fun toggleGroupCollapsed(name: String) {
        collapsedGroups = if (name in collapsedGroups) collapsedGroups - name else collapsedGroups + name
        onCollapsedGroupsChange(collapsedGroups)
    }

    /**
     * Имя группы, открытой диалогом «Rename group» (карандаш у заголовка папки), либо `null` — диалог
     * закрыт. Переименование/удаление профилей делает [app.skerry.ui.host.HostManagerController]; этот
     * стор синхронизирует только side-channel свёрнутости ([onGroupRenamed]/[onGroupDeleted]).
     */
    var renamingGroup: String? by mutableStateOf(null); private set

    /** Открыть диалог правки группы [name] (карандаш у заголовка папки). */
    fun openRenameGroup(name: String) { renamingGroup = name }

    /** Закрыть диалог правки группы. */
    fun dismissRenameGroup() { renamingGroup = null }

    /**
     * Синхронизировать свёрнутость при переименовании группы [old]→[new] (профили правит контроллер):
     * свёрнутая папка остаётся свёрнутой под новым именем. Имя триммится; пустое/неизменное — no-op.
     * Колбэк персиста вызывается только при реальной правке.
     */
    fun onGroupRenamed(old: String, new: String) {
        val n = new.trim().filterNot { it == '\n' || it == '\r' }
        if (n.isEmpty() || n == old) return
        if (old in collapsedGroups) {
            collapsedGroups = collapsedGroups - old + n
            onCollapsedGroupsChange(collapsedGroups)
        }
    }

    /** Синхронизировать свёрнутость при удалении группы [name] (профили разгруппировывает контроллер). */
    fun onGroupDeleted(name: String) {
        if (name in collapsedGroups) {
            collapsedGroups = collapsedGroups - name
            onCollapsedGroupsChange(collapsedGroups)
        }
    }

    /** Открыть лист в режиме создания нового хоста (форма пустая). */
    fun openNewConn() { editingHost = null; sheetNewConn = true }

    /** Открыть лист в режиме правки [host] (форма предзаполняется, сохранение удерживает его id). */
    fun openEditConn(host: Host) { editingHost = host; sheetNewConn = true }

    fun closeSheet() { sheetNewConn = false; editingHost = null }

    /** Выбранный шрифт терминала (More → Appearance → Font). Проводится в терминал через [app.skerry.ui.terminal.LocalTerminalAppearance]. */
    var terminalFont: TerminalFont by mutableStateOf(initialTerminalFont); private set

    /** Кегль шрифта терминала, px (More → Appearance → Font size). */
    var terminalFontSize: Int by mutableStateOf(initialTerminalFontSize); private set

    /** Тема терминала (More → Appearance → карточки). Проводится через [app.skerry.ui.terminal.LocalTerminalTheme]. */
    var terminalTheme: TerminalTheme by mutableStateOf(initialTerminalTheme); private set

    /** Язык интерфейса (More → Appearance → Language). Проводится в корень через [app.skerry.ui.i18n.AppLocaleProvider]. */
    var uiLanguage: UiLanguage by mutableStateOf(initialUiLanguage); private set

    /** Выбрать шрифт терминала и сообщить наружу (для персиста). Повтор того же — no-op (ни записи). */
    fun chooseTerminalFont(font: TerminalFont) {
        if (font == terminalFont) return
        terminalFont = font
        onTerminalFontChange(font)
    }

    /** Выбрать тему терминала и сообщить наружу. Повтор той же — no-op. */
    fun chooseTerminalTheme(theme: TerminalTheme) {
        if (theme == terminalTheme) return
        terminalTheme = theme
        onTerminalThemeChange(theme)
    }

    /**
     * Задать кегль шрифта терминала и сообщить наружу (для персиста). Значение вне [TERMINAL_FONT_SIZES]
     * и повтор текущего — no-op (ни записи, ни колбэка).
     */
    fun chooseTerminalFontSize(px: Int) {
        if (px == terminalFontSize || px !in TERMINAL_FONT_SIZES) return
        terminalFontSize = px
        onTerminalFontSizeChange(px)
    }

    /** Выбрать язык интерфейса и сообщить наружу (для персиста). Повтор того же — no-op (ни записи). */
    fun chooseUiLanguage(language: UiLanguage) {
        if (language == uiLanguage) return
        uiLanguage = language
        onUiLanguageChange(language)
    }
}
