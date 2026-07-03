package app.skerry.ui.connection

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.files.FileBrowser
import app.skerry.shared.files.SftpFileBrowser
import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.terminal.ShellTerminalSession
import app.skerry.shared.terminal.TerminalHistoryStore
import app.skerry.shared.terminal.TerminalState
import app.skerry.shared.terminal.terminalHistoryKey
import app.skerry.ui.terminal.ThroughputController
import app.skerry.ui.files.FilePaneController
import app.skerry.ui.files.TransferCoordinator
import app.skerry.ui.forward.PortForwardController
import app.skerry.ui.metrics.HostMetricsController
import app.skerry.ui.terminal.TerminalScreenState
import app.skerry.ui.terminal.TerminalSessionPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/** Состояние экрана подключения. */
sealed interface ConnectionUiState {
    /** Показывается форма подключения (старт или возврат после отключения/ошибки). */
    data object Form : ConnectionUiState

    /** Идёт connect/auth/открытие shell. */
    data object Connecting : ConnectionUiState

    /** Сессия открыта; [terminal] — состояние живого терминала. */
    data class Connected(val terminal: TerminalScreenState) : ConnectionUiState

    /** Подключиться не удалось; [message] для показа пользователю. */
    data class Error(val message: String) : ConnectionUiState

    /**
     * Сессия была установлена, но shell закрылся НЕ по нашей инициативе (EOF/обрыв транспорта). Наш
     * [disconnect] сюда не приводит — он отменяет session-scope раньше, чем наблюдатель дождётся
     * закрытия, и переходит в [Form]. [terminal] — застывший экран на момент потери: UI продолжает
     * показывать его, пока авто-реконнект восстанавливает живую сессию поверх.
     *
     * [reconnecting] — идёт ли сейчас попытка авто-реконнекта (true между обрывом и успехом/сдачей);
     * [attempt] — номер текущей/последней попытки (для баннера «Reconnecting… #N»). После исчерпания
     * лимита попыток состояние остаётся [Disconnected] с `reconnecting=false` (связь не восстановлена).
     *
     * [cleanExit] — shell завершился штатно (EOF, например по команде `exit`): авто-реконнекта НЕТ
     * (сессию просто закрываем), баннер нейтральный «Session closed». false — обрыв транспорта.
     */
    data class Disconnected(
        val terminal: TerminalScreenState,
        val reconnecting: Boolean,
        val attempt: Int,
        val cleanExit: Boolean = false,
    ) : ConnectionUiState
}

/**
 * Связывает форму подключения с [SshTransport]: по [connect] устанавливает соединение,
 * открывает интерактивный shell и собирает [TerminalScreenState] поверх [ShellTerminalSession].
 *
 * Лайфтайм сбора вывода и декода живёт в отдельной session-scope (см. [newSessionScope]):
 * [disconnect] отменяет её и рвёт соединение, не трогая основной [scope] контроллера.
 * Тесты подменяют [newSessionScope] тестовым диспетчером для детерминизма.
 *
 * Переходы инициируются только из [ConnectionUiState.Form] (guard в [connect]), поэтому
 * параллельных connect быть не может. [disconnect] отменяет незавершённый connect и
 * закрывает уже установленное соединение; teardown идёт под [NonCancellable], чтобы не
 * потеряться при отмене основного scope.
 */
@Stable
class ConnectionController(
    private val transport: SshTransport,
    private val scope: CoroutineScope,
    private val newSessionScope: () -> CoroutineScope = {
        CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default)
    },
    // Политика авто-реконнекта при обрыве (не по нашей инициативе). Лимит попыток защищает от
    // бесконечного цикла на наглухо упавшем хосте; backoff — экспоненциальный с потолком 30с.
    // Тесты задают свои значения (нулевой backoff, малый лимит) для детерминизма.
    private val maxReconnectAttempts: Int = 6,
    private val reconnectDelayMillis: (attempt: Int) -> Long = { attempt ->
        minOf(30_000L, 1_000L shl (attempt - 1).coerceIn(0, 16))
    },
    // Персист истории команд терминала per-host (для автодополнения). null → без персиста (сессия
    // учится только на себе). Ключ выводится из цели ([terminalHistoryKey]); сохранение уходит на IO,
    // чтобы file-IO vault не блокировал UI-поток на каждой команде.
    private val history: TerminalHistoryStore? = null,
    // Настройки терминала (глубина scrollback + стиль курсора), читаемые в момент КАЖДОГО connect —
    // так смена настроек влияет на новые сессии, а открытые сохраняют свой эмулятор. Дефолт (мок/тесты)
    // даёт стандартные значения.
    private val terminalPrefs: () -> TerminalSessionPrefs = { TerminalSessionPrefs() },
) {
    var uiState: ConnectionUiState by mutableStateOf(ConnectionUiState.Form)
        private set

    /**
     * Согласованный шифр живого соединения этой сессии (для info-панели) или `null`, пока сессия не
     * подключена / транспорт его не сообщает. Хранится snapshot-стейтом (а не геттером по
     * не-snapshot [connection]), чтобы Compose отслеживал чтение и перерисовывал info-панель при
     * появлении/сбросе соединения. Выставляется при переходе в [ConnectionUiState.Connected].
     */
    var cipher: String? by mutableStateOf(null)
        private set

    /**
     * Ident SSH-сервера живого соединения (`SSH-2.0-OpenSSH_8.9p1`) или `null`, пока сессия не
     * подключена / транспорт его не сообщает. Snapshot-стейт по тем же причинам, что и [cipher]
     * (Compose должен перерисовывать статус-бар при появлении/сбросе соединения).
     */
    var serverVersion: String? by mutableStateOf(null)
        private set

    private var connectJob: Job? = null
    // Цель/учётка последнего connect — для авто-реконнекта после обрыва (переподключаемся к тому же).
    private var lastTarget: SshTarget? = null
    private var lastAuth: SshAuth? = null
    // Одноразовое действие при ПЕРВОМ переходе в Connected этого connect (например «Run on host» из
    // сниппета: выполнить команду в только что открытой сессии). Срабатывает и обнуляется в
    // establishSession при успехе; авто-реконнект через establishSession его НЕ выставляет, поэтому
    // команда не повторяется при восстановлении связи.
    private var pendingOnConnected: ((TerminalScreenState) -> Unit)? = null
    private var reconnectJob: Job? = null
    private var connection: SshConnection? = null
    private var shellChannel: ShellChannel? = null
    private var sessionScope: CoroutineScope? = null
    private var portForwards: PortForwardController? = null
    private var sftpClient: SftpClient? = null
    private var transferCoordinator: TransferCoordinator? = null
    private val sftpMutex = Mutex()
    private var metrics: HostMetricsController? = null
    private var throughput: ThroughputController? = null
    private var ping: PingController? = null

    /**
     * Подключиться к [target]/[auth]. [onConnected] (если задан) вызывается РОВНО ОДИН РАЗ при первом
     * переходе в [ConnectionUiState.Connected] с готовым терминалом — точка для действия «выполнить
     * команду сразу после открытия сессии» (см. «Run on host» сниппетов). При авто-реконнекте после
     * обрыва не повторяется (реконнект не несёт этого колбэка).
     */
    fun connect(target: SshTarget, auth: SshAuth, onConnected: ((TerminalScreenState) -> Unit)? = null) {
        // Стартуем только из формы: пока идёт подключение или есть открытая сессия,
        // повторный connect игнорируется — иначе можно утечь scope и соединение.
        if (uiState !is ConnectionUiState.Form) return
        lastTarget = target
        lastAuth = auth
        pendingOnConnected = onConnected
        uiState = ConnectionUiState.Connecting
        connectJob = scope.launch {
            try {
                establishSession(target, auth)
            } catch (e: CancellationException) {
                // disconnect() во время подключения: полуоткрытое соединение уже закрыто внутри
                // establishSession; uiState выставлен в Form самим disconnect().
                throw e
            } catch (e: Exception) {
                uiState = ConnectionUiState.Error(e.message ?: "Не удалось подключиться")
            }
        }
    }

    /**
     * Установить живую сессию к [target]/[auth]: открыть соединение и shell, собрать терминал, перейти
     * в [ConnectionUiState.Connected] и подписать наблюдателя обрыва. При любой ошибке закрывает
     * полуоткрытое соединение и пробрасывает исключение (вызывающий решает: показать [Error] или
     * повторить попытку реконнекта). Используется и первичным [connect], и авто-реконнектом.
     */
    private suspend fun establishSession(target: SshTarget, auth: SshAuth) {
        var conn: SshConnection? = null
        try {
            conn = transport.connect(target, auth)
            coroutineContext.ensureActive()
            val channel = conn.openShell()
            coroutineContext.ensureActive()
            val sScope = newSessionScope()
            connection = conn
            // ВАЖНО: канал должен быть выставлен ДО uiState = Connected — статус-бар по этому
            // переходу зовёт openThroughput(), который требует живой shellChannel (иначе бросит).
            shellChannel = channel
            cipher = conn.cipher
            serverVersion = conn.serverVersion
            sessionScope = sScope
            // История команд для автодополнения: грузим для этого хоста и вешаем персист снимка на
            // каждую закоммиченную команду (уходит на IO-scope контроллера, не на UI-поток).
            val historyKey = terminalHistoryKey(
                target.connectionType.name, target.username, target.host, target.port,
            )
            val loadedHistory = history?.load(historyKey).orEmpty()
            // Снимаем настройки терминала на момент connect: применяются к новой сессии.
            val prefs = terminalPrefs()
            val terminal = TerminalScreenState(
                ShellTerminalSession(channel, sScope),
                sScope,
                initialHistory = loadedHistory,
                scrollback = prefs.effectiveScrollback,
                cursorShape = prefs.cursorStyle.shape,
                cursorBlink = prefs.cursorStyle.blink,
                onHistoryChanged = history?.let { store ->
                    // Уводим запись с UI-потока на scope контроллера (Default): команды редки, запись мелкая.
                    { snapshot -> scope.launch { store.save(historyKey, snapshot) } }
                },
            )
            uiState = ConnectionUiState.Connected(terminal)
            // Одноразовое действие первого подключения (Run on host): берём и обнуляем ДО возможного
            // обрыва, чтобы реконнект через этот же establishSession его не повторил.
            pendingOnConnected?.let { action -> pendingOnConnected = null; action(terminal) }
            watchForSessionLoss(terminal, sScope)
        } catch (e: Exception) {
            conn?.let(::closeConnectionQuietly)
            throw e
        }
    }

    /**
     * Открыть SFTP-канал поверх живого соединения этой сессии. Канал — собственность вызывающего
     * (экран SFTP): закрывать через [app.skerry.shared.sftp.SftpClient.close] в dispose. Само
     * SSH-соединение остаётся за контроллером и [disconnect] его закроет.
     * @throws IllegalStateException сессия не подключена (нет живого соединения)
     */
    suspend fun openSftp(): SftpClient =
        (connection ?: error("Нет активного соединения для SFTP")).openSftp()

    /**
     * Контроллер проброса портов этой сессии — один на соединение, создаётся лениво и кэшируется,
     * поэтому переживает переключение вкладок/панелей UI (туннели живут, пока жива сессия). Операции
     * гоняются на внутреннем [scope] сессии (как у [openTransferCoordinator]), а не на UI-scope экрана —
     * иначе уход вью из композиции отменил бы scope уже закэшированного контроллера и тихо убил бы
     * подъём/снятие туннелей. Все пробросы снимает [disconnect] при закрытии сессии.
     * @throws IllegalStateException сессия не подключена (нет живого соединения)
     */
    fun openPortForwards(): PortForwardController =
        portForwards ?: PortForwardController(
            connection ?: error("Нет активного соединения для проброса портов"),
            scope,
        ).also { portForwards = it }

    /**
     * Двухпанельный SFTP-координатор этой сессии (локальная ФС + удалённый хост) — один на соединение,
     * создаётся лениво и кэшируется (как [openPortForwards]), поэтому переживает переключение view
     * (путь/выделение панелей не сбрасываются). Операции панелей и передачи гоняются на внутреннем
     * [scope] сессии, а сам канал ([sftpClient]) закрывает [disconnect]. Первый вызов открывает канал
     * и запускает загрузку стартовых каталогов обеих панелей ([FilePaneController.start]). [localBrowser]
     * — платформенный браузер локальной ФС (его поставляет UI-слой, чтобы контроллер не зависел от
     * платформенных expect-функций и оставался тестируемым); [hostLabel] — метка удалённой панели.
     * Оба параметра используются лишь при первом создании — повторный вызов отдаёт кэш и их игнорирует.
     * [sftpMutex] сериализует ленивую инициализацию: даже при гонке двух вызывающих канал откроется
     * один раз (без утечки второго), а не-volatile поля кэша безопасно публикуются под локом.
     * @throws IllegalStateException сессия не подключена (нет живого соединения)
     */
    suspend fun openTransferCoordinator(localBrowser: FileBrowser, hostLabel: String): TransferCoordinator =
        sftpMutex.withLock {
            transferCoordinator ?: run {
                val client = (connection ?: error("Нет активного соединения для SFTP")).openSftp()
                sftpClient = client
                val remoteBrowser = SftpFileBrowser(client, hostLabel)
                TransferCoordinator(
                    sftp = client,
                    local = FilePaneController(localBrowser, scope),
                    localBrowser = localBrowser,
                    remote = FilePaneController(remoteBrowser, scope),
                    remoteBrowser = remoteBrowser,
                    scope = scope,
                ).also {
                    it.local.start()
                    it.remote.start()
                    transferCoordinator = it
                }
            }
        }

    /**
     * Контроллер live-метрик хоста этой сессии — один на соединение, создаётся лениво и кэшируется
     * (как [openPortForwards]/[openTransferCoordinator]), опрос гоняется на [scope] сессии и стартует
     * сразу. Останавливается в [disconnect] вместе с сессией.
     * @throws IllegalStateException сессия не подключена (нет живого соединения)
     */
    fun openMetrics(): HostMetricsController {
        val conn = connection ?: error("Нет активного соединения для метрик")
        return metrics ?: HostMetricsController(
            exec = { cmd -> conn.exec(cmd) },
            scope = scope,
        ).also { it.start(); metrics = it }
    }

    /**
     * Контроллер скорости терминального канала этой сессии — один на соединение, создаётся лениво и
     * кэшируется (как [openMetrics]), опрос гоняется на [scope] сессии. Сэмплеры читают живые счётчики
     * канала; после [disconnect] (канал обнулён) вернут 0, но к тому моменту поллер уже остановлен.
     * @throws IllegalStateException сессия не подключена (нет живого канала)
     */
    fun openThroughput(): ThroughputController {
        val channel = shellChannel ?: error("Нет активного канала для замера скорости")
        return throughput ?: ThroughputController(
            sampleUp = { channel.bytesUp },
            sampleDown = { channel.bytesDown },
            scope = scope,
        ).also { it.start(); throughput = it }
    }

    /**
     * Контроллер RTT-пинга этой сессии — один на соединение, лениво/кэш (как [openThroughput]),
     * замер гоняется на [scope] сессии. Останавливается в [disconnect].
     * @throws IllegalStateException сессия не подключена (нет живого соединения)
     */
    fun openPing(): PingController {
        val conn = connection ?: error("Нет активного соединения для пинга")
        return ping ?: PingController(
            measure = { conn.measureRoundTrip() },
            scope = scope,
        ).also { it.start(); ping = it }
    }

    /** Закрыть сессию (если есть) и вернуться к форме. Отменяет и активный connect, и авто-реконнект. */
    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        // Сразу отпускаем ссылку на секрет (auth может нести пароль/ключ) — не держим его на heap
        // дольше жизни соединения.
        lastAuth = null
        lastTarget = null
        // Отменён до Connected — отбрасываем неотработавшее одноразовое действие (Run on host).
        pendingOnConnected = null
        releaseSessionResources()
        uiState = ConnectionUiState.Form
    }

    /**
     * Запретить авто-реконнект, НЕ трогая живую сессию: отменяет ожидающий реконнект и сбрасывает
     * сохранённые цель/учётку. Вызывается при lock vault — открытый сокет остаётся жить (решение
     * проекта), но новый auth-хендшейк после обрыва на запертом vault недопустим (zero-knowledge):
     * без [lastAuth] обрыв приведёт в [ConnectionUiState.Disconnected] без попыток, и пользователь
     * переподключится вручную после разблокировки.
     */
    fun clearReconnectCredentials() {
        reconnectJob?.cancel()
        reconnectJob = null
        lastAuth = null
        lastTarget = null
        // Lock отменяет и отложенное действие первого подключения (Run on host): команда сниппета не
        // должна «выстрелить» в терминал, если хендшейк завершится уже на запертом vault.
        pendingOnConnected = null
    }

    /**
     * Следить за закрытием shell этой сессии: дождавшись [TerminalState.Closed], запускаем обработку
     * потери [onSessionLost]. Наблюдатель живёт на session-scope, поэтому наш [disconnect] (отменяющий
     * этот scope) гасит его ДО прихода Closed — сюда попадаем ТОЛЬКО при обрыве со стороны сервера,
     * что и отличает ненамеренную потерю (→ авто-реконнект) от намеренного закрытия (→ Form).
     */
    private fun watchForSessionLoss(terminal: TerminalScreenState, sScope: CoroutineScope) {
        sScope.launch {
            val closed = terminal.state.first { it is TerminalState.Closed } as TerminalState.Closed
            // Обработку потери диспатчим в основной [scope] — тот же, где идёт [disconnect]. Иначе
            // onSessionLost бежал бы на session-scope (Dispatchers.Default), и запись reconnectJob
            // гонялась бы с чтением/отменой из disconnect на UI-потоке. На одном scope они сериализованы.
            scope.launch { onSessionLost(terminal, closed.cleanExit) }
        }
    }

    /**
     * Закрытие сессии не по нашей инициативе: освобождаем ресурсы (оставляя [frozen] экран для показа).
     * При штатном выходе shell ([cleanExit] — команда `exit`/EOF) реконнекта НЕТ: сбрасываем
     * сохранённую учётку и показываем нейтральный «Session closed». Иначе (обрыв транспорта) запускаем
     * авто-реконнект к последним [lastTarget]/[lastAuth] — но ТОЛЬКО для SSH; у Telnet/Serial реконнекта
     * нет (см. ниже). Без сохранённых цели/учётки остаёмся в [ConnectionUiState.Disconnected] без
     * попыток. Гард на Connected защищает от повторного входа.
     */
    private fun onSessionLost(frozen: TerminalScreenState, cleanExit: Boolean) {
        if (uiState !is ConnectionUiState.Connected) return
        releaseSessionResources()
        if (cleanExit) {
            // Пользователь сам завершил shell (`exit`) — сессию закрываем, не реконнектим. Отпускаем
            // секрет (auth может нести пароль/ключ): держать его незачем, нового коннекта не будет.
            lastAuth = null
            lastTarget = null
            uiState = ConnectionUiState.Disconnected(frozen, reconnecting = false, attempt = 0, cleanExit = true)
            return
        }
        val target = lastTarget
        val auth = lastAuth
        if (target == null || auth == null) {
            uiState = ConnectionUiState.Disconnected(frozen, reconnecting = false, attempt = 0)
            return
        }
        // Авто-реконнект только для SSH. У Telnet/Serial он неуместен: аутентификации нет, а «обрыв» —
        // это обычно закрытие сессии сервером или исчезновение устройства (отключили кабель/остановили
        // стенд), куда молча переподключаться бессмысленно — пользователь коннектится заново вручную.
        if (target.connectionType != ConnectionType.SSH) {
            lastAuth = null
            lastTarget = null
            uiState = ConnectionUiState.Disconnected(frozen, reconnecting = false, attempt = 0)
            return
        }
        startReconnect(frozen, target, auth)
    }

    /**
     * Цикл авто-реконнекта: до [maxReconnectAttempts] попыток с backoff ([reconnectDelayMillis]) между
     * ними. На каждой попытке показываем [ConnectionUiState.Disconnected] с `reconnecting=true`, ждём
     * backoff и пробуем [establishSession] (он сам поставит [ConnectionUiState.Connected] и подпишет
     * нового наблюдателя обрыва при успехе). Исчерпав лимит, остаёмся в Disconnected с
     * `reconnecting=false`. Гоняется на основном [scope] (переживает teardown старой сессии); [disconnect]
     * отменяет [reconnectJob].
     */
    private fun startReconnect(frozen: TerminalScreenState, target: SshTarget, auth: SshAuth) {
        reconnectJob = scope.launch {
            var attempt = 1
            while (attempt <= maxReconnectAttempts) {
                uiState = ConnectionUiState.Disconnected(frozen, reconnecting = true, attempt = attempt)
                delay(reconnectDelayMillis(attempt))
                try {
                    establishSession(target, auth)
                    return@launch // успех: establishSession перевёл в Connected и переподписал наблюдателя
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    attempt++
                }
            }
            uiState = ConnectionUiState.Disconnected(frozen, reconnecting = false, attempt = maxReconnectAttempts)
        }
    }

    /**
     * Освободить ресурсы текущего соединения (туннели/SFTP/метрики/поллеры/session-scope/само
     * соединение), НЕ трогая [uiState]. Общий teardown для [disconnect] (далее → Form) и потери связи
     * (далее → реконнект). Идемпотентен: повторный вызов на уже очищенном контроллере безопасен.
     */
    private fun releaseSessionResources() {
        val conn = connection
        portForwards?.closeAll()
        portForwards = null
        val sftp = sftpClient
        sftpClient = null
        transferCoordinator = null
        metrics?.stop()
        metrics = null
        throughput?.stop()
        throughput = null
        ping?.stop()
        ping = null
        sessionScope?.cancel()
        sessionScope = null
        connection = null
        shellChannel = null
        cipher = null
        serverVersion = null
        if (sftp != null) closeSftpQuietly(sftp)
        if (conn != null) closeConnectionQuietly(conn)
    }

    /** Закрыть соединение, не давая отмене scope сорвать teardown и не пробрасывая ошибки. */
    private fun closeConnectionQuietly(conn: SshConnection) {
        scope.launch(NonCancellable) { runCatching { conn.disconnect() } }
    }

    /** Закрыть SFTP-канал в фоне под [NonCancellable] (SSH-соединение закроется следом отдельно). */
    private fun closeSftpQuietly(client: SftpClient) {
        scope.launch(NonCancellable) { runCatching { client.close() } }
    }
}
