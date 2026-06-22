package app.skerry.ui.session

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState

/**
 * Одна открытая сессия — вкладка в titlebar. Владеет собственным [ConnectionController]
 * (один shell на сессию). [hostId] связывает вкладку с профилем из каталога хостов, чтобы
 * сайдбар мог подсветить статус-точкой хосты, у которых есть живая сессия; для ad-hoc
 * подключений без сохранённого хоста он `null`. [title]/[subtitle] — ярлык вкладки и
 * строка `user@host:port` для session-bar.
 */
@Stable
class Session(
    val id: String,
    val hostId: String?,
    val title: String,
    val subtitle: String,
    val controller: ConnectionController,
) {
    /**
     * Заголовок для вкладки: живой OSC 0/1/2-title терминала (когда сессия подключена и приложение
     * его задало), иначе фолбэк на статичный [title] (имя хоста). Чтение реактивно — Compose
     * перерисует вкладку при смене `uiState` или `terminal.title`.
     */
    val displayTitle: String
        get() = effectiveTabTitle(
            liveTitle = (controller.uiState as? ConnectionUiState.Connected)?.terminal?.title,
            fallback = title,
        )
}

/** Эффективный заголовок вкладки: непустой живой [liveTitle] перекрывает [fallback]. */
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

    /**
     * Вторая сессия, показываемая рядом в split-панели терминала (focus-модель: пользователь сам
     * назначает её через пикер). `null` — split-панель пуста. Может совпадать с активной либо
     * указывать на любую открытую сессию; сбрасывается, когда выбранная сессия закрывается.
     */
    var splitId: String? by mutableStateOf(null)
        private set

    val active: Session? get() = sessions.firstOrNull { it.id == activeId }

    val split: Session? get() = sessions.firstOrNull { it.id == splitId }

    /**
     * Открыть новую сессию к [target] и сделать её активной; подключение стартует сразу.
     * Возвращает id созданной вкладки.
     */
    fun open(hostId: String?, title: String, subtitle: String, target: SshTarget, auth: SshAuth): String {
        val controller = controllerFactory()
        val session = Session(newId(), hostId, title, subtitle, controller)
        sessions = sessions + session
        activeId = session.id
        controller.connect(target, auth)
        return session.id
    }

    /** Сделать сессию [id] активной; неизвестный id игнорируется. */
    fun activate(id: String) {
        if (sessions.any { it.id == id }) activeId = id
    }

    /**
     * Назначить сессию [id] в split-панель (или `null`, чтобы очистить её). Неизвестный id
     * игнорируется — в панель нельзя поставить несуществующую сессию.
     */
    fun setSplit(id: String?) {
        if (id == null || sessions.any { it.id == id }) splitId = id
    }

    /** Закрыть сессию [id]: разорвать соединение, убрать вкладку, при необходимости выбрать соседа. */
    fun close(id: String) {
        val index = sessions.indexOfFirst { it.id == id }
        if (index < 0) return
        sessions[index].controller.disconnect()
        if (splitId == id) splitId = null
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

    /** Закрыть все сессии — вызывать при teardown экрана, чтобы не утекли сокеты. */
    fun disconnectAll() {
        sessions.forEach { it.controller.disconnect() }
        sessions = emptyList()
        activeId = null
        splitId = null
    }
}
