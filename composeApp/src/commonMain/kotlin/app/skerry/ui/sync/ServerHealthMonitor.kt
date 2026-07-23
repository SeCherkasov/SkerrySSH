package app.skerry.ui.sync

import app.skerry.shared.sync.SyncClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Poll period for the server health ping used by the reachability indicator: frequent enough for
 * the status to feel live (server down/up detected within ~15s), infrequent enough to not load
 * the server.
 */
internal const val HEALTH_POLL_MS = 15_000L

/**
 * Periodic health probe of the sync server ([SyncClient.ping] → `GET /healthz`), feeding
 * [reachable] independently of vault state and session presence — the "server is up and
 * reachable" indicator stays honest even with a locked vault. Target is set via [setTarget];
 * changing it restarts the loop (the old ping loop is cancelled at the [delay] point via
 * [collectLatest]); `null` means sync isn't configured, so the client closes and status becomes
 * [ServerReachable.UNKNOWN].
 *
 * Holds its own dedicated client (reused across ticks, recreated on URL change): pinging must
 * work without a working coordinator session. Cancelling [scope] (see [SyncCoordinator.close])
 * closes the client in a finally block under [NonCancellable] so the poller doesn't leak across
 * tests/teardown.
 */
internal class ServerHealthMonitor(
    private val clientFactory: (serverUrl: String) -> SyncClient,
    scope: CoroutineScope,
    initialTarget: String? = null,
    private val pollMs: Long = HEALTH_POLL_MS,
) {
    private val target = MutableStateFlow(initialTarget)

    // A [pingNow] nudge: the ping loop waits out the poll interval on it, so a send short-circuits
    // the wait. CONFLATED keeps a nudge that arrives during an in-flight ping for the next wait
    // instead of dropping it (that ping started before the nudge, so its result may be stale).
    private val kick = Channel<Unit>(Channel.CONFLATED)

    private val _reachable = MutableStateFlow(ServerReachable.UNKNOWN)
    val reachable: StateFlow<ServerReachable> = _reachable.asStateFlow()

    // Client is only for pinging; accessed solely from the loop below (collectLatest serializes it), no races.
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
                        // Not runCatching: it would swallow CancellationException and report a cancelled
                        // ping (scope teardown, target change) as "server down" — on a StateFlow that
                        // outlives the scope, so the indicator would stay stuck on it.
                        val ok = try {
                            clientFor(url).ping()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            false
                        }
                        _reachable.value =
                            if (ok) ServerReachable.REACHABLE else ServerReachable.UNREACHABLE
                        withTimeoutOrNull(pollMs) { kick.receive() } // next tick early on a pingNow nudge
                    }
                }
            } finally {
                // Cancelling scope must not leave a live Ktor client (pool/sockets) for the rest of the process.
                withContext(NonCancellable) { closeClient() }
            }
        }
    }

    /** Changes the ping target (coordinator connect/disconnect); null stops the poller at UNKNOWN. */
    fun setTarget(serverUrl: String?) {
        target.value = serverUrl
    }

    /**
     * Ping right now instead of waiting out the rest of the poll interval — the indicator may hold a
     * value from before the device slept (app resume/unlock). No-op while no target is set.
     */
    fun pingNow() {
        kick.trySend(Unit)
    }

    // suspend isn't superfluous: [SyncClient.close] is suspend, and closing the old client on URL
    // change happens inside the ping loop.
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
