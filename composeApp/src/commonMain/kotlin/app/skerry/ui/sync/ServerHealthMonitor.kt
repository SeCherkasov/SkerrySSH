package app.skerry.ui.sync

import app.skerry.shared.sync.SyncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Период health-пинга сервера для индикатора доступности: достаточно часто, чтобы статус был «живым»
 * (падение/возврат сервера видно в пределах ~15 с), и достаточно редко, чтобы не нагружать сервер.
 */
internal const val HEALTH_POLL_MS = 15_000L

/**
 * Периодический health-пробник sync-сервера ([SyncClient.ping] → `GET /healthz`), питающий
 * [reachable] НЕЗАВИСИМО от состояния vault и наличия сессии — индикатор «сервер работает и
 * доступен» честен и при заблокированном хранилище. Цель задаётся [setTarget]; смена цели
 * перезапускает цикл (старый пинг-луп отменяется на точке [delay] через [collectLatest]),
 * `null` = sync не настроен → клиент закрывается, статус [ServerReachable.UNKNOWN].
 *
 * Держит СОБСТВЕННЫЙ выделенный клиент (переиспользуется между тиками, пересоздаётся при смене URL):
 * пинг должен идти и без рабочей сессии координатора. Отмена [scope] (см. [SyncCoordinator.close])
 * закрывает клиент в finally под [NonCancellable] — поллер не течёт в тестах/teardown.
 */
internal class ServerHealthMonitor(
    private val clientFactory: (serverUrl: String) -> SyncClient,
    scope: CoroutineScope,
    initialTarget: String? = null,
    private val pollMs: Long = HEALTH_POLL_MS,
) {
    private val target = MutableStateFlow(initialTarget)

    private val _reachable = MutableStateFlow(ServerReachable.UNKNOWN)
    val reachable: StateFlow<ServerReachable> = _reachable.asStateFlow()

    // Клиент только для пинга; доступ строго из цикла ниже (collectLatest сериализует), гонок нет.
    private var client: SyncClient? = null
    private var clientUrl: String? = null

    init {
        scope.launch {
            try {
                target.collectLatest { url ->
                    if (url == null) {
                        closeClient()
                        _reachable.value = ServerReachable.UNKNOWN
                        return@collectLatest
                    }
                    while (true) {
                        val ok = runCatching { clientFor(url).ping() }.getOrDefault(false)
                        _reachable.value =
                            if (ok) ServerReachable.REACHABLE else ServerReachable.UNREACHABLE
                        delay(pollMs)
                    }
                }
            } finally {
                // Отмена scope не должна оставить живой Ktor-клиент (пул/сокеты) на весь процесс.
                withContext(NonCancellable) { closeClient() }
            }
        }
    }

    /** Сменить цель пинга (connect/disconnect координатора); `null` гасит поллер в UNKNOWN. */
    fun setTarget(serverUrl: String?) {
        target.value = serverUrl
    }

    // suspend не лишний: [SyncClient.close] — suspend, а закрытие старого клиента при смене URL
    // идёт внутри пинг-цикла.
    private suspend fun clientFor(url: String): SyncClient {
        if (clientUrl != url) {
            closeClient()
            client = clientFactory(url)
            clientUrl = url
        }
        return client!!
    }

    private suspend fun closeClient() {
        val c = client
        client = null
        clientUrl = null
        if (c != null) runCatching { c.close() }
    }
}
