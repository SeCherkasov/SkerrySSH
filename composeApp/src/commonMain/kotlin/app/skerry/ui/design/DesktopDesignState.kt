package app.skerry.ui.design

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import app.skerry.shared.host.Host
import app.skerry.ui.session.SessionView

/** Левый rail / основные view макета. */
enum class DesktopView { Terminal, Sftp, Ports, Snippets, Vault, Known, Teams }

/**
 * View уровня приложения (не привязаны к конкретной SSH-сессии): Ports(Tunnels)/Snippets/Vault/
 * Known/Teams. Они открываются «поверх» вкладок ([DesktopDesignState.appOverlay]) и общие на весь
 * app, тогда как Terminal/SFTP — подвью активной вкладки ([app.skerry.ui.session.Session.view]).
 *
 * Tunnels — глобальный список сохранённых пробросов (привычная модель SSH-клиентов): туннель самостоятелен и сам
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

/** Вкладка сессии в titlebar: имя хоста + цвет статус-точки. */
@Stable
data class SessionTab(val name: String, val dot: Color)

/** Строка демо-терминала: команда (с промптом) либо вывод. */
@Stable
data class TermLine(val text: String, val isCmd: Boolean, val color: Color = D.textMid)

/**
 * Состояние десктопного макета `docs/new/Skerry.html` — порт `class Component` из прототипа
 * (state + методы `setView`/`closeTab`/`toggle`/`runCmd`/…). Чисто UI-состояние без бэкенда:
 * демо-терминал (`exec`) и переключатели — заглушки, как в макете; живая функциональность
 * подключается отдельно. Compose-state через [mutableStateOf], мутаторы инкапсулированы
 * (`private set`) — тем же приёмом, что [app.skerry.ui.session.SessionsController].
 */
@Stable
class DesktopDesignState(
    // Стартовая видимость info-панели (на desktop читается из персиста при запуске) + колбэк её
    // изменения (там же пишется обратно), чтобы выбор пользователя переживал перезапуск. Дефолты
    // сохраняют прежнее поведение для мок/превью/тестов.
    initialInfoPanel: Boolean = true,
    private val onInfoPanelChange: (Boolean) -> Unit = {},
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
    var selectedHost: String by mutableStateOf("prod-web-01"); private set
    var activeTab: Int by mutableStateOf(0); private set
    var modalPolicy: AiPolicy by mutableStateOf(AiPolicy.Strict); private set

    /** Хост, открытый в модалке на правку (null — модалка в режиме «New connection»). */
    var editingHost: Host? by mutableStateOf(null); private set

    /** Хост, для которого показан диалог подтверждения удаления (null — диалога нет). */
    var pendingDeleteHost: Host? by mutableStateOf(null); private set

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

    fun lock() { locked = true }
    fun unlock() { locked = false }
    fun openModal() { editingHost = null; modalOpen = true }
    fun openEditModal(host: Host) { editingHost = host; modalOpen = true }
    fun closeModal() { modalOpen = false; editingHost = null }
    fun requestDeleteHost(host: Host) { pendingDeleteHost = host }
    fun dismissDeleteHost() { pendingDeleteHost = null }
    fun choosePolicy(p: AiPolicy) { modalPolicy = p }
    fun openSettings() { settingsOpen = true }
    fun closeSettings() { settingsOpen = false }
    fun showSettingsTab(t: SettingsTab) { settingsTab = t }
    fun toggleSplit() { split = !split }
    fun toggleInfo() { infoPanel = !infoPanel; onInfoPanelChange(infoPanel) }
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
