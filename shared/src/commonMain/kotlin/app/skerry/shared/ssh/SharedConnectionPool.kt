package app.skerry.shared.ssh

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.encodeUtf8

/**
 * One authenticated connection per (target, auth), handed out as leases — what OpenSSH calls
 * `ControlMaster`/`ControlPersist`. For a jump host that authenticates a person (a 2FA code, a
 * browser login): every session through it rides the same connection, so the person is asked once.
 *
 * - Concurrent [acquire]s of one key share a single dial: ten tabs opened at once cost one login.
 *   The dial runs in [scope], not in the caller, so one waiter giving up does not fail the others.
 * - A lease is returned by `disconnect()` on the connection [acquire] handed out. When the last one
 *   goes, the connection stays for [lingerMillis] and is then closed; an [acquire] in between reuses
 *   it after a round-trip proves it still answers (nothing kept it alive while it sat unused).
 * - A dial nobody waits for any more is cancelled rather than left to linger — that dial may be
 *   holding a 2FA prompt on screen for a tab that was already closed.
 * - [closeIdle] closes every connection without a lease now; the vault lock calls it, so an
 *   authenticated way into the network does not sit behind the lock screen with no tab on it. A
 *   connection still leased then goes down with its last session instead of lingering.
 *
 * Connections are told apart by who logs in where along the whole chain, secrets included, so two
 * accounts on one jump host never share one; per-session settings such as keep-alive do not split
 * them. The key is a digest of that: the pool holds no secret once the dial has finished.
 */
class SharedConnectionPool(
    private val transport: SshTransport,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val lingerMillis: Long = DEFAULT_LINGER_MILLIS,
) {
    private inner class Entry(val key: String) {
        lateinit var dial: Deferred<Result<SshConnection>>

        /**
         * What the dial produced, set before the dial completes. A cancel that lands after the
         * connection is made still completes [dial] as cancelled and drops the value; this keeps it
         * reachable for [disconnect].
         */
        @Volatile var produced: SshConnection? = null

        var leases = 0
        var linger: Job? = null

        /** Set by [closeIdle] while leased: the last session to leave closes it, no linger. */
        var closeWhenFree = false

        /** The dialed connection, or `null` while dialing or after the dial failed. */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun connectionOrNull(): SshConnection? =
            if (dial.isCompleted && !dial.isCancelled) dial.getCompleted().getOrNull() else null

        /** Dial finished and nothing usable came of it: failed, cancelled, or since dropped. */
        fun isDead(): Boolean = dial.isCompleted && connectionOrNull()?.isConnected != true
    }

    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    /**
     * A connection to [target] authenticated with [auth]: the pooled one, or a new dial shared with
     * everyone asking for the same key meanwhile. `disconnect()` on the result returns the lease.
     */
    suspend fun acquire(target: SshTarget, auth: SshAuth): SshConnection {
        val key = poolKey(target, auth)
        // Once around for a pooled connection that stopped answering while nobody used it; the
        // second pass dials fresh, so this cannot loop.
        repeat(2) { attempt ->
            val (entry, wasIdle) = lease(key) { transport.connect(target, auth) }
            var handedOut = false
            try {
                val connection = entry.dial.await().getOrThrow()
                if (!wasIdle || attempt == 1 || connection.answers()) {
                    handedOut = true
                    return Lease(connection, entry)
                }
                retire(entry)
            } finally {
                // A failed dial, a waiter cancelled mid-wait or mid-check, and a connection found
                // dead all hand the lease back; only a lease handed out keeps it.
                if (!handedOut) release(entry)
            }
        }
        error("unreachable")
    }

    /** Closes every pooled connection nobody holds a lease on. Leased ones stay with their sessions. */
    suspend fun closeIdle() {
        val idle = mutex.withLock {
            entries.values.onEach { it.closeWhenFree = true }.filter { it.leases == 0 }.onEach {
                entries.remove(it.key)
                it.linger?.cancel()
            }
        }
        idle.forEach { close(it) }
    }

    /** [closeIdle] from code that cannot suspend (the vault lock's teardown). */
    fun closeIdleInBackground(): Job = scope.launch { closeIdle() }

    /** Takes a lease on [key]'s entry (creating the dial if needed) and says whether it sat unused. */
    private suspend fun lease(key: String, dial: suspend () -> SshConnection): Pair<Entry, Boolean> = mutex.withLock {
        val existing = entries[key]?.takeUnless { it.isDead() }
        val entry = existing ?: Entry(key).also { created ->
            created.dial = scope.async { runCatching { dial() }.onSuccess { created.produced = it } }
            entries[key] = created
        }
        val wasIdle = existing != null && entry.leases == 0
        entry.leases++
        entry.linger?.cancel()
        entry.linger = null
        entry to wasIdle
    }

    /**
     * Returns one lease. Under [NonCancellable]: the callers are a cancelled wait in [acquire] and a
     * session's teardown, and a lease that fails to come back keeps the connection open for good.
     */
    private suspend fun release(entry: Entry) = withContext(NonCancellable) {
        val closeNow = mutex.withLock {
            entry.leases--
            when {
                entry.leases > 0 -> false
                // Replaced or retired while leased: nobody can reach it any more.
                entries[entry.key] !== entry -> true
                // Nobody is waiting for this dial now. Finishing it would only leave a login
                // prompt on screen for a tab that is gone. A failed dial has nothing to keep, and
                // one the vault lock asked for goes with its last session.
                !entry.dial.isCompleted || entry.connectionOrNull() == null || entry.closeWhenFree -> {
                    entries.remove(entry.key)
                    true
                }
                else -> {
                    entry.linger = scope.launch {
                        delay(lingerMillis)
                        expire(entry)
                    }
                    false
                }
            }
        }
        if (closeNow) close(entry)
    }

    /** Takes [entry] out of the pool so the next [acquire] dials fresh; its leases still release. */
    private suspend fun retire(entry: Entry) {
        mutex.withLock { if (entries[entry.key] === entry) entries.remove(entry.key) }
    }

    private suspend fun expire(entry: Entry) {
        val expired = mutex.withLock {
            (entry.leases == 0 && entries[entry.key] === entry).also { if (it) entries.remove(entry.key) }
        }
        if (expired) close(entry)
    }

    private suspend fun close(entry: Entry) {
        if (!entry.dial.isCompleted) {
            entry.dial.cancel()
            // A dial that made its connection as the cancel landed; nothing else holds it any more.
            entry.dial.invokeOnCompletion { scope.launch { disconnect(entry) } }
            return
        }
        disconnect(entry)
    }

    private suspend fun disconnect(entry: Entry) {
        val connection = entry.produced ?: return
        // A session's teardown may be what brought the lease count to zero, and it may be cancelled;
        // the connection must go down all the same.
        withContext(NonCancellable) {
            try {
                connection.disconnect()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Already gone: the pool has let go of it, and there is nothing left to release.
            }
        }
    }

    private suspend fun SshConnection.answers(): Boolean = isConnected && measureRoundTrip() != null

    /**
     * Who logs in where, hop by hop, as a digest — the map holds no secret after the dial. Every
     * field is length-prefixed so no two chains spell the same string.
     */
    private fun poolKey(target: SshTarget, auth: SshAuth): String {
        val out = StringBuilder()
        fun field(value: String?) {
            if (value == null) out.append('-') else out.append(value.length).append(':').append(value)
        }
        fun hop(host: String, port: Int, username: String, hopAuth: SshAuth) {
            field(host); field(port.toString()); field(username)
            when (hopAuth) {
                is SshAuth.Password -> { field("P"); field(hopAuth.secret) }
                is SshAuth.PublicKey -> { field("K"); field(hopAuth.privateKeyPem); field(hopAuth.passphrase) }
                is SshAuth.Certificate -> {
                    field("C"); field(hopAuth.privateKeyPem); field(hopAuth.certificate); field(hopAuth.passphrase)
                }
                is SshAuth.KeyFile -> {
                    field("F"); field(hopAuth.privateKeyRef); field(hopAuth.certificateRef); field(hopAuth.passphrase)
                }
                SshAuth.Interactive -> field("I")
            }
        }
        hop(target.host, target.port, target.username, auth)
        var outer = target.jump
        while (outer != null) {
            hop(outer.host, outer.port, outer.username, outer.auth)
            outer = outer.jump
        }
        return out.toString().encodeUtf8().sha256().hex()
    }

    /** What [acquire] hands out: the pooled connection, where `disconnect()` returns the lease once. */
    private inner class Lease(private val inner: SshConnection, private val entry: Entry) : SshConnection by inner {
        private var released = false

        override suspend fun disconnect() = withContext(NonCancellable) {
            val first = mutex.withLock { !released.also { released = true } }
            if (first) release(entry)
        }
    }

    companion object {
        /** How long a connection outlives its last session, like `ControlPersist 10m`. */
        const val DEFAULT_LINGER_MILLIS: Long = 10 * 60 * 1000L
    }
}
