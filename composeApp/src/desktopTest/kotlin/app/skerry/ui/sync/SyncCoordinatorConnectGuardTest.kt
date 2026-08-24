package app.skerry.ui.sync

import app.skerry.shared.sync.InMemorySyncStateStore
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Issue #328: what the "a connect is already in flight" guard is allowed to swallow.
 *
 * The guard drops a second connect so a repeat click cannot spawn a second session, and it used to
 * stand until the coroutine that ran the connect returned — well past the point where the result is
 * on screen. Everything after the status is published (the superseded client's socket pool, the
 * subscriptions, the link write) is invisible to the caller, so a connect issued on the strength of
 * that status hit a guard nothing on screen accounted for and was dropped in silence: no Busy, no
 * failure, no session. What is left of the previous connect is serialized by the coordinator's own
 * operation lock; the new one has only to queue behind it.
 */
class SyncCoordinatorConnectGuardTest {

    private val crypto = IonspinVaultCrypto()
    private val account = "maya"
    private val password = "vault-A"
    private val firstUrl = "https://one.test"
    private val secondUrl = "https://two.test"
    private val thirdUrl = "https://three.test"

    @Test
    fun `a connect issued on a published status is not swallowed by the previous connect's tail`() = runBlocking {
        initializeVaultCrypto()
        val vault = newAccountVault(crypto, password)
        val wrap = wrapOwnKey(vault, crypto, password, account)
        // The first client's close is what the second connect ends on — held open, it pins the
        // coordinator inside that tail for as long as this test needs to aim at it.
        val closing = CompletableDeferred<Unit>()
        val first = ReactivatingClient(wrap, reactivated = false, closeGate = closing)
        val second = ReactivatingClient(wrap, reactivated = false)
        val third = ReactivatingClient(wrap, reactivated = false)
        val sut = SyncCoordinator(
            clientFactory = { url ->
                when (url) {
                    firstUrl -> first
                    secondUrl -> second
                    else -> third
                }
            },
            crypto = crypto,
            vault = vault,
            configStore = InMemorySyncConfigStore(),
            debtStore = InMemoryReconcileDebtStore(),
            syncState = InMemorySyncStateStore(),
        )
        try {
            sut.connect(firstUrl, account, password.toCharArray())
            sut.status.awaitStatus("the first session to come up") { it is SyncStatus.Online }

            // Every connect publishes Busy on the caller's thread before it launches, so this waits
            // for the SECOND connect's own Online and not for the one already standing.
            sut.connect(secondUrl, account, password.toCharArray())
            sut.status.awaitStatus("the second session to come up") { it is SyncStatus.Online }
            // On the server, not just on screen: a swallowed second connect would publish no Busy at all,
            // and the wait above would have matched the first connect's Online without noticing.
            awaitSync("the second server to be connected to") { while (second.pulledSince.isEmpty()) delay(20) }

            // Online is on screen and the second connect is still winding down inside `close`.
            sut.connect(thirdUrl, account, password.toCharArray())
            closing.complete(Unit)
            awaitSync("the third server to be connected to") { while (third.pulledSince.isEmpty()) delay(20) }
        } finally {
            closing.complete(Unit)
            sut.close()
        }
    }

    /**
     * The other half of the same guard: what it must still refuse. The status is written by a dozen paths
     * that never took the guard — a background cycle on the session that is still live, a disconnect, an
     * auto-lock — and the release cannot be read off the status alone, or any of them would hand the guard
     * back for an operation that has not published anything yet. Here the connect is held at the server
     * while a manual cycle publishes Online for the PREVIOUS session; a connect issued on that status is
     * still a double submit and is still dropped.
     */
    @Test
    fun `a background cycle on the old session does not release the guard of a connect in flight`() = runBlocking {
        initializeVaultCrypto()
        val vault = newAccountVault(crypto, password)
        val wrap = wrapOwnKey(vault, crypto, password, account)
        val holding = CompletableDeferred<Unit>()
        val first = ReactivatingClient(wrap, reactivated = false)
        val second = ReactivatingClient(wrap, reactivated = false, loginGate = holding)
        val third = ReactivatingClient(wrap, reactivated = false)
        val sut = SyncCoordinator(
            clientFactory = { url ->
                when (url) {
                    firstUrl -> first
                    secondUrl -> second
                    else -> third
                }
            },
            crypto = crypto,
            vault = vault,
            configStore = InMemorySyncConfigStore(),
            debtStore = InMemoryReconcileDebtStore(),
            syncState = InMemorySyncStateStore(),
        )
        try {
            sut.connect(firstUrl, account, password.toCharArray())
            sut.status.awaitStatus("the first session to come up") { it is SyncStatus.Online }

            sut.connect(secondUrl, account, password.toCharArray())
            awaitSync("the second connect to reach the server") { second.loggingIn.await() }

            // It is holding the operation lock with nothing published; the first session is still live,
            // and a cycle over it publishes a result that belongs to neither connect.
            sut.syncNow()
            sut.status.awaitStatus("the background cycle to publish its own result") { it is SyncStatus.Online }

            // connect() raises Busy on the caller's thread before it launches, so an admitted connect is
            // visible right here — no waiting, no race with the one still at the server.
            sut.connect(thirdUrl, account, password.toCharArray())
            assertTrue(
                sut.status.value is SyncStatus.Online,
                "the connect still at the server owns the guard; a cycle that never took it must not hand it " +
                    "back — status was ${sut.status.value}",
            )
        } finally {
            holding.complete(Unit)
            sut.close()
        }
    }
}
