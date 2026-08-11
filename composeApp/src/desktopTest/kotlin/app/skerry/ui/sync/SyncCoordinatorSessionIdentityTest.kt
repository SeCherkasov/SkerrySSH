package app.skerry.ui.sync

import app.skerry.shared.sync.DeviceInfo
import app.skerry.shared.sync.SyncClient
import app.skerry.shared.sync.SyncException
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.sync.WebAccessClient
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issues #240 and #241: which server a live session belongs to.
 *
 * The session object carries an account id and two tokens, never the URL they are valid at, and the
 * coordinator used to answer "which link is this?" by reading the SAVED config — a different fact, written
 * by a store that can refuse the write. An activation that aborts between publishing the session and saving
 * its link left the two disagreeing: the guards evaluated the link the device came from while the session
 * talked to the one it had just moved to (#241). The same abort left the previous session's watch loop
 * running, and that loop re-reads the live session on every attempt — so it woke up and handed the new
 * server's tokens to the old server's client (#240).
 *
 * The doubles live in `ReactivationFixtures.kt`; the reconcile permission these guards read is
 * [SyncCoordinatorArmedLinkTest]'s subject.
 */
class SyncCoordinatorSessionIdentityTest {

    private val crypto = IonspinVaultCrypto()
    private val workUrl = "https://work.test"
    private val homeUrl = "https://home.test"
    private val account = "maya"
    private val password = "vault-A"

    private fun freshVault(): Vault = newAccountVault(crypto, password)
    private fun ownWrap(vault: Vault): ByteArray = wrapOwnKey(vault, crypto, password, account)

    /**
     * A server that also speaks the web-access protocol, parks the first call, and then answers it with
     * the 401 the coordinator recovers from — recording the token of every session it is handed.
     */
    private class WebLeakClient(private val inner: SyncClient) : SyncClient by inner, WebAccessClient {
        val tokens: MutableList<String> = CopyOnWriteArrayList()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        private val calls = AtomicInteger(0)

        override suspend fun webAccessEnabled(session: SyncSession): Boolean = false
        override suspend fun clearWebPassword(session: SyncSession) = Unit
        override suspend fun setWebPassword(session: SyncSession, password: CharArray) {
            tokens += session.accessToken
            if (calls.getAndIncrement() == 0) {
                entered.complete(Unit)
                release.await()
                throw SyncException(SyncException.Kind.UNAUTHORIZED, "token expired")
            }
        }
    }

    /** Parks a connect inside its login, so a second operation can be started while it holds the lock. */
    private class GatedLoginClient(
        private val delegate: SyncClient,
        private val entered: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : SyncClient by delegate {
        override suspend fun login(accountId: String, authKey: ByteArray, device: DeviceInfo): SyncSession {
            entered.complete(Unit)
            release.await()
            return delegate.login(accountId, authKey, device)
        }
    }

    /**
     * The link write is refused exactly when the reactivating connect to home tries to save it: home's
     * session is published and live, home's rebuild is owed, and the config still names work — which owes
     * nothing. Read as this session's identity, the saved link says "no rebuild owed here" and the cycle
     * pushes the un-rebuilt vault to the server that purged it.
     */
    @Test
    fun `a cycle after an aborted activation is refused on the link the session is on`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        val work = ReactivatingClient(ownWrap(vault), reactivated = false)
        val home = ReactivatingClient(ownWrap(vault), reactivated = true)
        val config = LinkWriteFailingStore(InMemorySyncConfigStore())
        val debts = InMemoryReconcileDebtStore()
        val sut = SyncCoordinator(
            clientFactory = { url -> if (url == homeUrl) home else work },
            crypto = crypto,
            vault = vault,
            configStore = config,
            debtStore = debts,
        )
        try {
            sut.connect(workUrl, account, password.toCharArray())
            sut.status.awaitStatus("the work session to come up") { it is SyncStatus.Online }
            assertFalse(debts.owes(workUrl, account), "work never revoked this device")

            config.refuse = true
            sut.connect(homeUrl, account, password.toCharArray())
            sut.status.awaitStatus("the home connect to fail on the link write") { it is SyncStatus.Failed }
            assertEquals(workUrl, config.load()?.serverUrl, "the refused write left the work link saved")
            assertTrue(debts.owes(homeUrl, account), "home's rebuild is owed and nothing has run it")

            vault.put("r1", RecordType.HOST, "purged-by-home".encodeToByteArray())
            sut.syncNow()
            sut.status.awaitStatus("the cycle to settle") {
                it is SyncStatus.Online || it == SyncStatus.Failed(SyncFailureReason.ReconcileRequired)
            }
            assertEquals(
                SyncStatus.Failed(SyncFailureReason.ReconcileRequired),
                sut.status.value,
                "the session is home's, and home's rebuild has not run",
            )
            assertFalse(home.pushed.any { it.id == "r1" }, "the record home purged must not be pushed back")
        } finally {
            sut.close()
        }
    }

    /**
     * The connect to home dies on the clear, so it never restarts the subscriptions: work's watch loop is
     * still parked in its retry backoff with the home session already published. It re-reads the live
     * session on every attempt — by design, so a mid-session token rotation reaches the next handshake —
     * and hands home's access token to work's client. A failing handshake would send work the refresh
     * token too: thirty days of full account access on a server that was never meant to see it.
     */
    @Test
    fun `a stale watch loop never hands one server's session to another`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        val work = ReactivatingClient(ownWrap(vault), reactivated = false, accessToken = "work-token", liveWatch = true)
        val home = ReactivatingClient(ownWrap(vault), reactivated = true, accessToken = "home-token")
        val sut = SyncCoordinator(
            clientFactory = { url -> if (url == homeUrl) home else work },
            crypto = crypto,
            // Home's reconcile finds the vault locked: the activation aborts before it reaches startWatch.
            vault = ClearFailingVault(vault),
            configStore = InMemorySyncConfigStore(),
            debtStore = InMemoryReconcileDebtStore(),
        )
        try {
            sut.connect(workUrl, account, password.toCharArray())
            sut.status.awaitStatus("the work session to come up") { it is SyncStatus.Online }
            awaitSync("work's watch loop to subscribe") { while (work.watched.isEmpty()) delay(20) }

            sut.connect(homeUrl, account, password.toCharArray())
            sut.status.awaitStatus("the home connect to fail on the clear") { it is SyncStatus.Failed }

            // Nothing to wait FOR — the assertion is that nothing happens. The loop's backoff is a second
            // and the served signal keeps it there, so this window holds several wake-ups.
            delay(3_000)
            assertTrue(
                work.watched.none { it.accessToken == "home-token" },
                "work's client was handed home's session: ${work.watched.map { it.accessToken }}",
            )
        } finally {
            sut.close()
        }
    }

    /**
     * The same guarantee on the ordinary path: a connect that SUCCEEDS restarts the subscriptions, and the
     * previous loop must be gone before the new session is anywhere near it — not merely outrun by the
     * cancel-and-join that the successful path happens to reach.
     */
    @Test
    fun `a clean switch leaves no loop behind on the previous server`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        val work = ReactivatingClient(ownWrap(vault), reactivated = false, accessToken = "work-token", liveWatch = true)
        val home = ReactivatingClient(ownWrap(vault), reactivated = false, accessToken = "home-token", liveWatch = true)
        val sut = SyncCoordinator(
            clientFactory = { url -> if (url == homeUrl) home else work },
            crypto = crypto,
            vault = vault,
            configStore = InMemorySyncConfigStore(),
            debtStore = InMemoryReconcileDebtStore(),
        )
        try {
            sut.connect(workUrl, account, password.toCharArray())
            sut.status.awaitStatus("the work session to come up") { it is SyncStatus.Online }
            awaitSync("work's watch loop to subscribe") { while (work.watched.isEmpty()) delay(20) }

            sut.connect(homeUrl, account, password.toCharArray())
            sut.status.awaitStatus("the home session to come up") { it is SyncStatus.Online }
            awaitSync("home's watch loop to subscribe") { while (home.watched.isEmpty()) delay(20) }

            delay(3_000) // several of work's backoff windows, with nothing left that may use its client
            assertTrue(
                work.watched.none { it.accessToken == "home-token" },
                "work's client was handed home's session: ${work.watched.map { it.accessToken }}",
            )
            assertTrue(
                home.watched.all { it.accessToken.startsWith("home-token") },
                "home's client was handed a foreign session: ${home.watched.map { it.accessToken }}",
            )
        } finally {
            sut.close()
        }
    }

    /**
     * The same abort, the other resource: the superseded client owns a socket pool and its own threads, and
     * only the tail of a successful activation used to close it. A connect that dies on the reconcile leaks
     * one per attempt, on a path the user is invited to retry.
     */
    @Test
    fun `an aborted activation closes the client it superseded`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        val work = ReactivatingClient(ownWrap(vault), reactivated = false)
        val home = ReactivatingClient(ownWrap(vault), reactivated = true)
        val sut = SyncCoordinator(
            clientFactory = { url -> if (url == homeUrl) home else work },
            crypto = crypto,
            vault = ClearFailingVault(vault),
            configStore = InMemorySyncConfigStore(),
            debtStore = InMemoryReconcileDebtStore(),
        )
        try {
            sut.connect(workUrl, account, password.toCharArray())
            sut.status.awaitStatus("the work session to come up") { it is SyncStatus.Online }

            sut.connect(homeUrl, account, password.toCharArray())
            sut.status.awaitStatus("the home connect to fail on the clear") { it is SyncStatus.Failed }
            assertTrue(work.closed.get(), "the superseded work client was left open")
        } finally {
            sut.close()
        }
    }

    /**
     * The settings screen's web-access calls run outside both mutexes and recover from a 401 by rotating
     * the session and retrying — and the retry used to re-read the LIVE session while still holding the
     * client it started with. A connect that lands while the call is in flight makes those two belong to
     * different servers, and the retry hands the previous server a valid access token for the account on
     * the new one, with the web password the user is setting in the body.
     */
    @Test
    fun `a web-access retry never carries the new session to the old server`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        val work = WebLeakClient(ReactivatingClient(ownWrap(vault), reactivated = false, accessToken = "work-token"))
        val home = ReactivatingClient(ownWrap(vault), reactivated = false, accessToken = "home-token")
        val sut = SyncCoordinator(
            clientFactory = { url -> if (url == homeUrl) home else work },
            crypto = crypto,
            vault = vault,
            configStore = InMemorySyncConfigStore(),
            debtStore = InMemoryReconcileDebtStore(),
        )
        try {
            sut.connect(workUrl, account, password.toCharArray())
            sut.status.awaitStatus("the work session to come up") { it is SyncStatus.Online }

            val setting = async { sut.setWebPassword("web-secret".toCharArray()) }
            awaitSync("the web-access call to reach work") { work.entered.await() }

            sut.connect(homeUrl, account, password.toCharArray())
            sut.status.awaitStatus("the home session to come up") { it is SyncStatus.Online }

            work.release.complete(Unit)
            val outcome = setting.await()

            assertTrue(
                work.tokens.none { it == "home-token" },
                "work's client was handed home's session: ${work.tokens}",
            )
            assertTrue(outcome !is WebAccessChange.Success, "the call belonged to a session that is gone, was $outcome")
        } finally {
            sut.close()
        }
    }

    /**
     * The rotation reads the saved link before it queues for the lock, and a connect can land in between.
     * Run against that captured link, it rotates the password of the account the device has just LEFT and
     * re-saves that link over the live session's — silently moving the device back to a server the user
     * disconnected from.
     */
    @Test
    fun `a password rotation does not re-link a device a connect moved`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        val home = ReactivatingClient(ownWrap(vault), reactivated = false, accessToken = "home-token")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val work = GatedLoginClient(
            ReactivatingClient(ownWrap(vault), reactivated = false, accessToken = "work-token"),
            entered,
            release,
        )
        val config = InMemorySyncConfigStore()
        val sut = SyncCoordinator(
            clientFactory = { url -> if (url == homeUrl) home else work },
            crypto = crypto,
            vault = vault,
            configStore = config,
            debtStore = InMemoryReconcileDebtStore(),
        )
        try {
            sut.connect(homeUrl, account, password.toCharArray())
            sut.status.awaitStatus("the home session to come up") { it is SyncStatus.Online }

            // Parked inside its login, holding the operation lock.
            sut.connect(workUrl, account, password.toCharArray())
            awaitSync("the work connect to reach its login") { entered.await() }

            // UNDISPATCHED: the rotation reads the saved link as its first statement and then parks on the
            // lock the connect holds — which is exactly the interleaving this is about.
            var outcome: AccountPasswordChange? = null
            val rotating = launch(start = CoroutineStart.UNDISPATCHED) {
                outcome = sut.changeAccountPassword(password.toCharArray(), "vault-B".toCharArray())
            }
            release.complete(Unit)
            awaitSync("the work connect to save its link") { while (config.load()?.serverUrl != workUrl) delay(20) }
            rotating.join()

            assertEquals(workUrl, config.load()?.serverUrl, "the rotation re-saved the link the device left")
            assertEquals(0, home.passwordChanges.get(), "home's account password was rotated from a device on work")
            assertTrue(outcome is AccountPasswordChange.LinkMoved, "was $outcome")
        } finally {
            sut.close()
        }
    }
}
