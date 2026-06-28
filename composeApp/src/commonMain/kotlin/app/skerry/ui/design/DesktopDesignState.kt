package app.skerry.ui.design

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import app.skerry.shared.host.Host
import app.skerry.ui.session.SessionView
import app.skerry.ui.terminal.DEFAULT_TERMINAL_FONT_SIZE
import app.skerry.ui.terminal.TERMINAL_FONT_SIZES
import app.skerry.ui.terminal.TerminalFont

/** Левый rail / основные view макета. */
enum class DesktopView { Terminal, Sftp, Ports, Snippets, Vault, Known, Teams }

/**
 * View уровня приложения (не привязаны к конкретной SSH-сессии): Ports(Tunnels)/Snippets/Vault/
 * Known/Teams. Они открываются «поверх» вкладок ([DesktopDesignState.appOverlay]) и общие на весь
 * app, тогда как Terminal/SFTP — подвью активной вкладки ([app.skerry.ui.session.Session.view]).
 *
 * Tunnels — глобальный список сохранённых пробросов: туннель самостоятелен и сам
 * открывает соединение к хосту, поэтому раздел общий, а не часть открытой сессии.
 */
val DesktopView.isAppLevel: Boolean
    get() = this == DesktopView.Ports || this == DesktopView.Snippets || this == DesktopView.Vault ||
        this == DesktopView.Known || this == DesktopView.Teams

/** DesktopView (rail) → подвью сессии; app-level/Terminal сводятся к Terminal. */
fun DesktopView.asSessionView(): SessionView = when (this) {
    DesktopView.Sftp -> SessionView.Sftp
    else -> SessionView.Terminal
}

/** Подвью сессии → пункт rail для подсветки. */
fun SessionView.asDesktopView(): DesktopView = when (this) {
    SessionView.Terminal -> DesktopView.Terminal
    SessionView.Sftp -> DesktopView.Sftp
}

/** Вкладки панели настроек. */
enum class SettingsTab { Account, AI, Sync, Security, Appearance, Terminal, Keyboard, About }

/** AI-политика подключения (модалка New connection). */
enum class AiPolicy { Strict, Balanced, Permissive, Off }

/**
 * Запрошенное деструктивное действие над сессией, ждущее подтверждения ([ConfirmActionDialog]).
 * Само действие (close/closeSplit) выполняет [DesktopChrome] — там доступен менеджер сессий.
 */
sealed interface PendingClose {
    /** Разрыв сессии-вкладки целиком (кнопка power в тулбаре). */
    data class Session(val id: String) : PendingClose

    /** Закрытие split-панели вкладки [parentId] (крестик в шапке split). */
    data class Split(val parentId: String) : PendingClose
}

/**
 * Открытый диалог управления группой хостов сайдбара: создание новой ([Create]) либо правка
 * существующей по имени ([Rename]). `null` в [DesktopDesignState.groupDialog] — диалога нет.
 */
sealed interface GroupDialog {
    /** Создание новой (пока пустой) группы. */
    data object Create : GroupDialog

    /** Правка группы [name]: переименование либо удаление (разгруппировать её хосты). */
    data class Rename(val name: String) : GroupDialog
}

/** Вкладка сессии в titlebar: имя хоста + цвет статус-точки. */
@Stable
data class SessionTab(val name: String, val dot: Color)

/** Строка демо-терминала: команда (с промптом) либо вывод. */
@Stable
data class TermLine(val text: String, val isCmd: Boolean, val color: Color = D.textMid)

/**
 * UI-состояние десктопного приложения без бэкенда: демо-терминал (`exec`) и переключатели —
 * заглушки; живая функциональность подключается отдельно. Compose-state через [mutableStateOf],
 * мутаторы инкапсулированы (`private set`) — тем же приёмом, что [app.skerry.ui.session.SessionsController].
 */
@Stable
class DesktopDesignState(
    // Стартовая видимость info-панели (на desktop читается из персиста при запуске) + колбэк её
    // изменения (там же пишется обратно), чтобы выбор пользователя переживал перезапуск. Дефолты
    // сохраняют прежнее поведение для мок/превью/тестов.
    initialInfoPanel: Boolean = true,
    private val onInfoPanelChange: (Boolean) -> Unit = {},
    // Схлопнутые папки хостов в сайдбаре (имена групп). Стартовое значение читается из персиста при
    // запуске, колбэк пишет его обратно — состояние папок переживает перезапуск. Дефолты (всё
    // развёрнуто, no-op) сохраняют прежнее поведение для мок/превью/тестов.
    initialCollapsedGroups: Set<String> = emptySet(),
    private val onCollapsedGroupsChange: (Set<String>) -> Unit = {},
    // Недавние подключения (секция RECENT в сайдбаре): id хостов в порядке свежести, новейший —
    // первым. Стартовое значение читается из персиста, колбэк пишет обратно, чтобы список переживал
    // перезапуск. Дефолты (пусто, no-op) сохраняют прежнее поведение для мок/превью/тестов.
    initialRecentHostIds: List<String> = emptyList(),
    private val onRecentHostIdsChange: (List<String>) -> Unit = {},
    // Пользовательские группы хостов, ещё не имеющие профилей (созданы кнопкой «+папка» до того, как
    // в них перетащили/завели хост). Группы с хостами выводятся из [Host.group], а пустые жить там не
    // могут — поэтому держим их именами здесь и персистим. Дефолты (пусто, no-op) — для мок/превью/тестов.
    initialCustomGroups: List<String> = emptyList(),
    private val onCustomGroupsChange: (List<String>) -> Unit = {},
    // Шрифт терминала (Appearance → Font) и его кегль. Стартовые значения читаются из персиста при
    // запуске, колбэки пишут их обратно — выбор переживает перезапуск. Дефолты (Hack 13px, no-op) —
    // для мок/превью/тестов.
    initialTerminalFont: TerminalFont = TerminalFont.DEFAULT,
    private val onTerminalFontChange: (TerminalFont) -> Unit = {},
    initialTerminalFontSize: Int = DEFAULT_TERMINAL_FONT_SIZE,
    private val onTerminalFontSizeChange: (Int) -> Unit = {},
) {
    // session-level view (Terminal/SFTP/Ports) — мок/превью-фолбэк, когда нет живых сессий; в живом
    // режиме подвью держит каждая вкладка ([app.skerry.ui.session.Session.view]).
    var view: DesktopView by mutableStateOf(DesktopView.Terminal); private set

    /**
     * Открытый app-level view поверх вкладок (Vault/Known/Teams/Snippets) или `null` — показываем
     * подвью активной вкладки. Эти разделы общие на весь app, поэтому держатся отдельно от [view]
     * и не зависят от того, какая вкладка активна (см. [DesktopView.isAppLevel]).
     */
    var appOverlay: DesktopView? by mutableStateOf(null); private set

    var locked: Boolean by mutableStateOf(false); private set
    var modalOpen: Boolean by mutableStateOf(false); private set
    var settingsOpen: Boolean by mutableStateOf(false); private set
    var settingsTab: SettingsTab by mutableStateOf(SettingsTab.AI); private set
    var split: Boolean by mutableStateOf(false); private set
    var infoPanel: Boolean by mutableStateOf(initialInfoPanel); private set

    /** Имена схлопнутых папок хостов в сайдбаре (свёрнут список их хостов). */
    var collapsedGroups: Set<String> by mutableStateOf(initialCollapsedGroups); private set

    /** Id недавно подключённых хостов, новейший — первым (секция RECENT в сайдбаре). */
    var recentHostIds: List<String> by mutableStateOf(initialRecentHostIds); private set

    /** Имена пользовательских (пока пустых) групп хостов — показываются как папки наравне с выводимыми из хостов. */
    var customGroups: List<String> by mutableStateOf(initialCustomGroups); private set

    /** Выбранный шрифт терминала (Appearance → Font). Проводится в терминал через [app.skerry.ui.terminal.LocalTerminalAppearance]. */
    var terminalFont: TerminalFont by mutableStateOf(initialTerminalFont); private set

    /** Кегль шрифта терминала, px (Appearance → Font size). */
    var terminalFontSize: Int by mutableStateOf(initialTerminalFontSize); private set

    /** Открытый диалог управления группой (создание/правка) или `null`. */
    var groupDialog: GroupDialog? by mutableStateOf(null); private set
    var selectedHost: String by mutableStateOf("prod-web-01"); private set

    /** Текст поиска в сайдбаре хостов (по имени/адресу/пользователю/группе/тегам). Пусто — без фильтра. */
    var hostSearchQuery: String by mutableStateOf(""); private set
    var activeTab: Int by mutableStateOf(0); private set
    var modalPolicy: AiPolicy by mutableStateOf(AiPolicy.Strict); private set

    /** Хост, открытый в модалке на правку (null — модалка в режиме «New connection»). */
    var editingHost: Host? by mutableStateOf(null); private set

    /** Хост, для которого показан диалог подтверждения удаления (null — диалога нет). */
    var pendingDeleteHost: Host? by mutableStateOf(null); private set

    /** Деструктивное действие над сессией, ждущее подтверждения (null — диалога нет). */
    var pendingClose: PendingClose? by mutableStateOf(null); private set

    var tabs: List<SessionTab> by mutableStateOf(
        listOf(
            SessionTab("prod-web-01", D.moss),
            SessionTab("db-master", D.moss),
            SessionTab("homelab-pi", D.amber),
            SessionTab("staging-web", D.faint),
        ),
    )
        private set

    var sanitize: Boolean by mutableStateOf(true); private set
    var preview: Boolean by mutableStateOf(true); private set
    var confirm: Boolean by mutableStateOf(true); private set

    var cmd: String by mutableStateOf(""); private set
    var termLines: List<TermLine> by mutableStateOf(emptyList()); private set

    /**
     * Открыть view из rail: app-level (Vault/Known/Teams/Snippets) → поднимаем оверлей поверх
     * вкладок; session-level (Terminal/SFTP/Ports) → сбрасываем оверлей и фиксируем подвью (для
     * живого режима подвью также проставляет вызывающий на активной вкладке).
     */
    fun showView(v: DesktopView) {
        if (v.isAppLevel) {
            appOverlay = v
        } else {
            appOverlay = null
            view = v
        }
    }

    /**
     * Снять app-оверлей, вернувшись к подвью активной вкладки, НЕ трогая [view]. В живом режиме
     * подвью держит [app.skerry.ui.session.Session.view] — единственный источник правды; [view]
     * остаётся лишь мок/превью-фолбэком и не должен переписываться при навигации с живыми сессиями.
     */
    fun clearOverlay() { appOverlay = null }
    fun selectHost(name: String) { selectedHost = name }
    fun onHostSearch(value: String) { hostSearchQuery = value }
    fun setTab(i: Int) { if (i in tabs.indices) activeTab = i }

    /**
     * Закрыть вкладку [i] — поведение прототипа: индекс активной зажимается в новый диапазон
     * (сосед справа сместился на освободившийся индекс, иначе ближайший слева, иначе 0).
     */
    fun closeTab(i: Int) {
        if (i !in tabs.indices) return
        val next = tabs.toMutableList().apply { removeAt(i) }
        var a = activeTab
        if (a >= next.size) a = next.size - 1
        if (a < 0) a = 0
        tabs = next
        activeTab = a
    }

    fun lock() { locked = true; hostSearchQuery = "" }
    fun unlock() { locked = false }
    fun openModal() { editingHost = null; modalOpen = true }
    fun openEditModal(host: Host) { editingHost = host; modalOpen = true }
    fun closeModal() { modalOpen = false; editingHost = null }
    fun requestDeleteHost(host: Host) { pendingDeleteHost = host }
    fun dismissDeleteHost() { pendingDeleteHost = null }
    fun requestCloseSession(id: String) { pendingClose = PendingClose.Session(id) }
    fun requestCloseSplit(parentId: String) { pendingClose = PendingClose.Split(parentId) }
    fun dismissClose() { pendingClose = null }
    fun choosePolicy(p: AiPolicy) { modalPolicy = p }
    fun openSettings() { settingsOpen = true }
    fun closeSettings() { settingsOpen = false }
    fun showSettingsTab(t: SettingsTab) { settingsTab = t }
    fun toggleSplit() { split = !split }
    fun toggleInfo() { infoPanel = !infoPanel; onInfoPanelChange(infoPanel) }

    /** Свёрнута ли папка [name] (её список хостов скрыт). */
    fun isGroupCollapsed(name: String): Boolean = name in collapsedGroups

    /** Свернуть/развернуть папку [name] и сообщить новый набор наружу (для персиста). */
    fun toggleGroupCollapsed(name: String) {
        collapsedGroups = if (name in collapsedGroups) collapsedGroups - name else collapsedGroups + name
        onCollapsedGroupsChange(collapsedGroups)
    }
    /**
     * Отметить хост [id] как недавно подключённый: двигаем его в начало списка (без дубля),
     * обрезаем до [MAX_RECENT_HOSTS] и сообщаем наружу (для персиста). Повторный коннект к уже
     * первому хосту — no-op (ни мутации, ни записи). Пустой id игнорируется.
     */
    fun recordRecentHost(id: String) {
        if (id.isBlank()) return
        val next = (listOf(id) + recentHostIds.filterNot { it == id }).take(MAX_RECENT_HOSTS)
        if (next == recentHostIds) return
        recentHostIds = next
        onRecentHostIdsChange(recentHostIds)
    }

    fun openCreateGroup() { groupDialog = GroupDialog.Create }
    fun openRenameGroup(name: String) { groupDialog = GroupDialog.Rename(name) }
    fun dismissGroupDialog() { groupDialog = null }

    /**
     * Завести новую (пока пустую) группу. Имя триммится, переносы строк вырезаются (они не хранимы
     * построчно в персисте). Пустое или точно совпадающее с существующей пользовательской группой —
     * игнорируется. Сопоставление точное (по регистру) — как `Host.group`/[groupHostsByFolder]/
     * [collapsedGroups] во всей системе; дубль с группой, выводимой из хостов (точное имя), отсеётся
     * при рендере слиянием папок. Персист — через колбэк.
     */
    fun addCustomGroup(name: String) {
        val n = name.trim().filterNot { it == '\n' || it == '\r' }
        if (n.isEmpty() || n in customGroups) return
        customGroups = customGroups + n
        onCustomGroupsChange(customGroups)
    }

    /**
     * Переименование группы в side-channel: правит список пустых групп и набор схлопнутых ([old]→[new]).
     * Переписывание `Host.group` у реальных профилей делает [app.skerry.ui.host.HostManagerController.renameGroup]
     * — вызывающий UI зовёт обе стороны. Сопоставление точное (по регистру), как в
     * [app.skerry.ui.host.renameHostGroup], чтобы side-channel не разъезжался с профилями (включая
     * правку только регистра). Имя триммится+чистится от переносов; пустое/неизменное [new] — no-op.
     */
    fun renameGroupName(old: String, new: String) {
        val n = new.trim().filterNot { it == '\n' || it == '\r' }
        if (n.isEmpty() || n == old) return
        if (old in customGroups) {
            customGroups = customGroups.map { if (it == old) n else it }.distinct()
            onCustomGroupsChange(customGroups)
        }
        if (old in collapsedGroups) {
            collapsedGroups = collapsedGroups - old + n
            onCollapsedGroupsChange(collapsedGroups)
        }
    }

    /** Снять пользовательскую группу [name] из side-channel (список пустых + набор схлопнутых). */
    fun removeCustomGroup(name: String) {
        if (name in customGroups) {
            customGroups = customGroups.filterNot { it == name }
            onCustomGroupsChange(customGroups)
        }
        if (name in collapsedGroups) {
            collapsedGroups = collapsedGroups - name
            onCollapsedGroupsChange(collapsedGroups)
        }
    }

    /** Выбрать шрифт терминала и сообщить наружу (для персиста). Повтор того же — no-op (ни записи). */
    fun chooseTerminalFont(font: TerminalFont) {
        if (font == terminalFont) return
        terminalFont = font
        onTerminalFontChange(font)
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

    fun toggleSanitize() { sanitize = !sanitize }
    fun togglePreview() { preview = !preview }
    fun toggleConfirm() { confirm = !confirm }

    fun onCmd(value: String) { cmd = value }

    /** Демо-исполнение команды (как `exec` в макете): известные → вывод, иначе not found. */
    fun runCmd() {
        val c = cmd.trim()
        if (c == "clear") { termLines = emptyList(); cmd = ""; return }
        val out = exec(c)
        val lines = termLines.toMutableList()
        lines += TermLine(text = c.ifEmpty { " " }, isCmd = true)
        if (out != null) lines += out
        termLines = lines
        cmd = ""
    }

    private fun exec(c: String): TermLine? {
        if (c.isEmpty()) return null
        DEMO_OUTPUT[c]?.let { return TermLine(text = it, isCmd = false, color = D.textMid) }
        return TermLine(text = "${c.substringBefore(' ')}: command not found", isCmd = false, color = D.sunset)
    }

    private companion object {
        /** Максимум записей в секции RECENT сайдбара — старейшие вытесняются новыми коннектами. */
        const val MAX_RECENT_HOSTS = 8

        val DEMO_OUTPUT = mapOf(
            "ls" to "app  deploy  logs  backup.tar.gz",
            "ls -la" to "total 24\ndrwxr-xr-x  5 root root  app\ndrwxr-xr-x  2 root root  deploy\n-rw-r--r--  1 root root  backup.tar.gz",
            "pwd" to "/root",
            "whoami" to "root",
            "hostname" to "prod-web-01",
            "df -h" to "Filesystem  Size  Used Avail Use%\n/dev/sda1    50G   42G  5.2G  87%",
            "uptime" to "14:25:30 up 6 days,  load average: 0.42, 0.51, 0.48",
            "date" to "Sat Jun 21 14:25:30 UTC 2026",
            "free -h" to "              total        used        free\nMem:           4.0Gi       2.1Gi       1.9Gi",
            "help" to "Demo commands: ls, ls -la, pwd, whoami, hostname, df -h, free -h, uptime, date, clear",
        )
    }
}
