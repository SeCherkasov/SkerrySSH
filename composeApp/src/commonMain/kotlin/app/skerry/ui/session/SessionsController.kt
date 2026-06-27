package app.skerry.ui.session

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.terminal.TerminalScreenState

/**
 * Подвью сессии (привязана к вкладке): что показано в её рабочей области. Туннели сюда НЕ входят —
 * это глобальный раздел (модель Termius), см. [app.skerry.ui.design.DesktopView.isAppLevel].
 */
enum class SessionView { Terminal, Sftp }

/**
 * Одна открытая сессия — вкладка в titlebar. Владеет собственным [ConnectionController]
 * (один shell на сессию). [hostId] связывает вкладку с профилем из каталога хостов, чтобы
 * сайдбар мог подсветить статус-точкой хосты, у которых есть живая сессия; для ad-hoc
 * подключений без сохранённого хоста он `null`. [title]/[subtitle] — ярлык вкладки и
 * строка `user@host:port` для session-bar.
 *
 * Поля коннекта ([hostId]/[title]/[subtitle]) мутируемы (snapshot-стейт): пустая вкладка
 * ([isBlank]) создаётся незаполненной и «обживается» первым подключением через
 * [SessionsController.connect] (заполнить можно один раз — соединение этой вкладки уже
 * стартовало). [view] — выбранная подвью, своя у каждой вкладки (паттерн «таб1=Terminal,
 * таб2=SFTP сохраняются на протяжении сессии»).
 */
@Stable
class Session(
    val id: String,
    hostId: String?,
    title: String,
    subtitle: String,
    val controller: ConnectionController,
) {
    var hostId: String? by mutableStateOf(hostId)
        private set
    var title: String by mutableStateOf(title)
        private set
    var subtitle: String by mutableStateOf(subtitle)
        private set

    /** Выбранная подвью этой вкладки (Terminal/SFTP), переживает переключение вкладок. */
    var view: SessionView by mutableStateOf(SessionView.Terminal)
        private set

    /**
     * Split (модель Termius): вкладка может держать рядом ВТОРУЮ независимую сессию. [splitOpen] —
     * показана ли split-область (включается кнопкой split на тулбаре); [splitSession] — вторичная
     * панель со своим [ConnectionController] (своё соединение/терминал/выделение), `null` пока хост
     * не выбран (тогда панель показывает пикер хостов). [focusedSplit] — какая панель в фокусе
     * (false=основная, true=split): определяет, какой хост показывает чип вкладки.
     */
    var splitOpen: Boolean by mutableStateOf(false)
        private set
    var splitSession: Session? by mutableStateOf(null)
        private set
    var focusedSplit: Boolean by mutableStateOf(false)
        private set

    /**
     * Пустая вкладка без сессии: хост не выбран и соединение ещё не запускалось (контроллер в
     * [ConnectionUiState.Form]). Именно такую создаёт кнопка «+»; первое подключение её заполняет.
     * После [ConnectionController.disconnect] вкладка с уже выбранным хостом пустой не становится.
     */
    val isBlank: Boolean get() = hostId == null && controller.uiState is ConnectionUiState.Form

    internal fun setView(v: SessionView) { view = v }

    internal fun setSplitOpen(open: Boolean) { splitOpen = open }
    internal fun setSplitSession(session: Session?) { splitSession = session }
    internal fun setFocusedSplit(focused: Boolean) { focusedSplit = focused }

    /**
     * Заполнить пустую вкладку профилем перед первым подключением (см. [SessionsController.connect]).
     * Только пока вкладка пуста ([isBlank]): «обжить» её можно один раз — после старта соединения
     * перезапись hostId/title сорвала бы соответствие вкладки её живой сессии.
     */
    internal fun bind(hostId: String?, title: String, subtitle: String) {
        check(isBlank) { "bind() на непустой вкладке: соединение уже стартовало" }
        this.hostId = hostId
        this.title = title
        this.subtitle = subtitle
    }

    /**
     * Заголовок для вкладки: имя хоста из каталога ([title]) — поведение SSH-менеджеров
     * (Termius/Tabby/Royal TSX) и нашего шаблона (короткие имена хостов на вкладках).
     *
     * Живой OSC 0/1/2-title терминала НАМЕРЕННО не подставляется: на серверах с обычным bash он
     * сводится к шумному `root@<hostname>` и перекрывал бы понятный лейбл, причём непоследовательно
     * (роутеры на busybox OSC не шлют → у них оставался лейбл). ТЕХДОЛГ: вынести «показывать живой
     * OSC-заголовок» в настройки приложения (выкл по умолчанию) — хелпер [effectiveTabTitle] уже
     * готов под это. До этого вкладка всегда = лейбл хоста.
     */
    val displayTitle: String get() = title
}

/**
 * Эффективный заголовок вкладки: непустой живой [liveTitle] перекрывает [fallback]. Пока не
 * вызывается из UI (см. [Session.displayTitle]) — заготовка под будущую настройку «показывать
 * OSC-заголовок терминала на вкладке».
 */
fun effectiveTabTitle(liveTitle: String?, fallback: String): String =
    liveTitle?.takeIf { it.isNotBlank() } ?: fallback

/**
 * Менеджер открытых сессий поверх [ConnectionController] — модель вкладок desktop-каркаса.
 * Каждая вкладка изолирована своим контроллером (одна сессия = один shell), [activeId]
 * указывает на видимую в основной области.
 *
 * Контроллеры создаёт [controllerFactory] (в проде — `ConnectionController(transport, scope)`;
 * в тестах — с тестовым диспетчером), id вкладок выдаёт [newId] — тем же приёмом, что и
 * [app.skerry.ui.host.HostManagerController], платформенная точка входа инжектит UUID.
 *
 * [close] повторяет поведение вкладок прототипа: после удаления активной выбирается соседняя
 * справа, иначе слева, иначе активной не остаётся. Соединение закрытой вкладки рвётся явно
 * ([ConnectionController.disconnect] идемпотентен), иначе сокет утечёт.
 */
@Stable
class SessionsController(
    private val newId: () -> String,
    private val controllerFactory: () -> ConnectionController,
) {
    var sessions: List<Session> by mutableStateOf(emptyList())
        private set

    var activeId: String? by mutableStateOf(null)
        private set

    val active: Session? get() = sessions.firstOrNull { it.id == activeId }

    /**
     * Открыть новую сессию к [target] и сделать её активной; подключение стартует сразу.
     * Возвращает id созданной вкладки.
     */
    fun open(
        hostId: String?,
        title: String,
        subtitle: String,
        target: SshTarget,
        auth: SshAuth,
        onConnected: ((TerminalScreenState) -> Unit)? = null,
    ): String {
        val controller = controllerFactory()
        val session = Session(newId(), hostId, title, subtitle, controller)
        sessions = sessions + session
        activeId = session.id
        controller.connect(target, auth, onConnected)
        return session.id
    }

    /**
     * Открыть пустую вкладку без сессии (кнопка «+»): соединение НЕ стартует, контроллер остаётся
     * в [ConnectionUiState.Form]. Становится активной; заполнится первым [connect]. Возвращает id.
     */
    fun openBlank(title: String = "New tab"): String {
        val controller = controllerFactory()
        val session = Session(newId(), hostId = null, title = title, subtitle = "", controller)
        sessions = sessions + session
        activeId = session.id
        return session.id
    }

    /**
     * Подключиться к [target]: если активная вкладка пустая ([Session.isBlank]) — заполнить и
     * подключить её на месте (без новой вкладки); иначе открыть новую через [open]. Возвращает id
     * вкладки, в которой стартовало соединение. Поведение «+→пустой таб, затем выбор хоста коннектит
     * в него же».
     */
    fun connect(
        hostId: String?,
        title: String,
        subtitle: String,
        target: SshTarget,
        auth: SshAuth,
        onConnected: ((TerminalScreenState) -> Unit)? = null,
    ): String {
        val blank = active?.takeIf { it.isBlank }
        if (blank != null) {
            blank.bind(hostId, title, subtitle)
            blank.controller.connect(target, auth, onConnected)
            return blank.id
        }
        return open(hostId, title, subtitle, target, auth, onConnected)
    }

    /** Сменить подвью активной вкладки (Terminal/SFTP); без активной — no-op. */
    fun setActiveView(view: SessionView) {
        active?.setView(view)
    }

    /** Сделать сессию [id] активной; неизвестный id игнорируется. */
    fun activate(id: String) {
        if (sessions.any { it.id == id }) activeId = id
    }

    /**
     * Переставить вкладку с [fromIndex] на позицию [toIndex] (drag-reorder в titlebar, модель Termius).
     * Оба индекса должны быть валидны; перенос «на место» — no-op. [activeId] адресует вкладку по id,
     * поэтому активная при переносе не меняется.
     */
    fun moveTab(fromIndex: Int, toIndex: Int) {
        val indices = sessions.indices
        if (fromIndex !in indices || toIndex !in indices || fromIndex == toIndex) return
        sessions = sessions.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    }

    /**
     * Переключить split-область вкладки [id] (по умолчанию активной): нет split → открыть пустую
     * (покажет пикер хостов); есть → закрыть через [closeSplit] (порвав вторичное соединение).
     */
    fun toggleSplit(id: String? = activeId) {
        val tab = sessions.firstOrNull { it.id == id } ?: return
        if (tab.splitOpen) closeSplit(tab.id) else tab.setSplitOpen(true)
    }

    /**
     * Подключить НОВУЮ независимую вторичную сессию в split-панель вкладки [parentId]: своя
     * [ConnectionController] (своё соединение/терминал), кладётся в [Session.splitSession] и
     * получает фокус. Вторичная сессия НЕ попадает в [sessions] — ею владеет родительская вкладка.
     */
    fun connectSplit(parentId: String, hostId: String?, title: String, subtitle: String, target: SshTarget, auth: SshAuth) {
        val parent = sessions.firstOrNull { it.id == parentId } ?: return
        parent.splitSession?.controller?.disconnect() // заменяем прежнюю вторичную, если была
        val secondary = Session(newId(), hostId, title, subtitle, controllerFactory())
        parent.setSplitOpen(true)
        parent.setSplitSession(secondary)
        parent.setFocusedSplit(true)
        secondary.controller.connect(target, auth)
    }

    /** Закрыть split вкладки [id]: порвать вторичное соединение и сбросить split-флаги. */
    fun closeSplit(id: String) {
        val tab = sessions.firstOrNull { it.id == id } ?: return
        tab.splitSession?.controller?.disconnect()
        tab.setSplitSession(null)
        tab.setSplitOpen(false)
        tab.setFocusedSplit(false)
    }

    /** Сфокусировать панель вкладки [id]: false — основная, true — split. Определяет заголовок чипа. */
    fun focusPane(id: String, split: Boolean) {
        sessions.firstOrNull { it.id == id }?.setFocusedSplit(split)
    }

    /** Закрыть сессию [id]: разорвать соединение (вместе с её split), убрать вкладку, выбрать соседа. */
    fun close(id: String) {
        val index = sessions.indexOfFirst { it.id == id }
        if (index < 0) return
        sessions[index].controller.disconnect()
        sessions[index].splitSession?.controller?.disconnect()
        val remaining = sessions.toMutableList().apply { removeAt(index) }
        if (activeId == id) {
            // Сосед справа сместился на освободившийся индекс; иначе берём слева, иначе пусто.
            activeId = remaining.getOrNull(index)?.id ?: remaining.getOrNull(index - 1)?.id
        }
        sessions = remaining
    }

    /** Состояние самой свежей сессии для хоста [hostId] (для статус-точки в сайдбаре), либо null. */
    fun statusFor(hostId: String): ConnectionUiState? =
        sessions.lastOrNull { it.hostId == hostId }?.controller?.uiState

    /** Закрыть все сессии (вместе с их split) — вызывать при teardown экрана, чтобы не утекли сокеты. */
    fun disconnectAll() {
        sessions.forEach {
            it.controller.disconnect()
            it.splitSession?.controller?.disconnect()
        }
        sessions = emptyList()
        activeId = null
    }
}
