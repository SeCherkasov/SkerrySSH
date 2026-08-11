package app.skerry.ui.sync

import app.skerry.shared.sync.InMemorySyncStateStore
import app.skerry.shared.sync.RemoteRecord
import app.skerry.shared.sync.SyncStateStore
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issues #242 and #243: what a piece of per-server state is filed under.
 *
 * One account id names two different accounts on a home and a work instance — that is why the reactivation
 * debt is keyed on the whole [ServerLink]. The delta cursor was not: it was keyed on the account id alone,
 * so two servers under one id shared one cursor and the second one's first pull started at the first one's
 * tip, silently skipping everything below it (#242). And the link itself was keyed on the URL exactly as
 * typed, so a variant spelling of the same server made a standing rebuild not owed (#243).
 */
class SyncCoordinatorLinkKeyedStateTest {

    private val crypto = IonspinVaultCrypto()
    private val workUrl = "https://work.test"
    private val homeUrl = "https://home.test"
    private val account = "maya"
    private val password = "vault-A"

    private fun freshVault(): Vault = newAccountVault(crypto, password)
    private fun ownWrap(vault: Vault): ByteArray = wrapOwnKey(vault, crypto, password, account)

    /** One record from the server — enough to move the cursor off zero (an empty page never does). */
    private fun servedRecord() = RemoteRecord(
        id = "s1",
        type = RecordType.HOST.name,
        version = 1,
        updatedAt = "2026-07-22T00:00:00Z",
        deviceId = "devB",
        deleted = false,
        blob = ByteArray(64),
    )

    /**
     * Work is synced to its tip; home is a different account that happens to carry the same id. Home's
     * first pull has to be a full one — asking for the delta since work's tip means every home record at
     * or below that number is never pulled, permanently, under an Online status. Work's own cursor has to
     * survive the trip, or the fix is just "re-pull everything, always".
     */
    @Test
    fun `two servers under one account id keep separate cursors`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        val work = ReactivatingClient(
            ownWrap(vault),
            reactivated = false,
            serves = listOf(servedRecord()),
            servesCursor = 5,
        )
        val home = ReactivatingClient(ownWrap(vault), reactivated = false)
        val sut = SyncCoordinator(
            clientFactory = { url -> if (url == homeUrl) home else work },
            crypto = crypto,
            vault = vault,
            configStore = InMemorySyncConfigStore(),
            debtStore = InMemoryReconcileDebtStore(),
            syncState = InMemorySyncStateStore(),
        )
        try {
            sut.connect(workUrl, account, password.toCharArray())
            sut.status.awaitStatus("the work session to come up") { it is SyncStatus.Online }
            awaitSync("work's cursor to reach its tip") { while (work.pulledSince.none { it == 5L }) delay(20) }

            sut.connect(homeUrl, account, password.toCharArray())
            sut.status.awaitStatus("the home session to come up") { it is SyncStatus.Online }
            assertEquals(
                0L,
                home.pulledSince.first(),
                "home's first pull continued from work's tip: ${home.pulledSince}",
            )

            // The WS guard reads the same cursor: a signal at 3 is news on home (whose own cursor is 0)
            // and would be an echo of our own push if it were judged against work's tip.
            assertTrue(sut.signalAdvancesCursor(3), "a home signal was judged against work's cursor")

            val seen = work.pulledSince.size
            sut.connect(workUrl, account, password.toCharArray())
            awaitSync("work to pull again") { while (work.pulledSince.size == seen) delay(20) }
            assertEquals(5L, work.pulledSince[seen], "work's own cursor did not survive the trip to home")
            assertFalse(sut.signalAdvancesCursor(3), "back on work, a signal below its tip is an echo")
        } finally {
            sut.close()
        }
    }

    /**
     * The manual recovery re-pull — what a team whose key never arrived asks for — resets the cursor too,
     * and it was resetting the one filed under the bare account id. On a device that has been on two
     * servers under one id that is either the wrong server's cursor or nobody's: the recovery reports
     * itself done, the next pull continues from the tip, and the record it was called to fetch is still
     * missing. Work's own cursor has to survive, or "recovery" just means "re-pull everything, always".
     */
    @Test
    fun `a recovery re-pull resets the cursor of the server it is on`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        val work = ReactivatingClient(ownWrap(vault), reactivated = false, serves = listOf(servedRecord()), servesCursor = 5)
        val home = ReactivatingClient(ownWrap(vault), reactivated = false, serves = listOf(servedRecord()), servesCursor = 9)
        val state = InMemorySyncStateStore()
        val sut = SyncCoordinator(
            clientFactory = { url -> if (url == homeUrl) home else work },
            crypto = crypto,
            vault = vault,
            configStore = InMemorySyncConfigStore(),
            debtStore = InMemoryReconcileDebtStore(),
            syncState = state,
        )
        val workKey = ServerLink(workUrl, account).cursorKey
        val homeKey = ServerLink(homeUrl, account).cursorKey
        try {
            sut.connect(workUrl, account, password.toCharArray())
            sut.status.awaitStatus("the work session to come up") { it is SyncStatus.Online }
            awaitSync("work's cursor to reach its tip") { while (state.cursor(workKey) != 5L) delay(20) }

            sut.connect(homeUrl, account, password.toCharArray())
            sut.status.awaitStatus("the home session to come up") { it is SyncStatus.Online }
            awaitSync("home's cursor to reach its tip") { while (state.cursor(homeKey) != 9L) delay(20) }

            // Waits for a full pull anywhere after this point rather than for the very next one: an
            // ordinary cycle can land in between, and "the next pull was a full one" is not the claim.
            val seen = home.pulledSince.size
            sut.recoverFullPull()
            awaitSync("the recovery's full re-pull") { while (home.pulledSince.drop(seen).none { it == 0L }) delay(20) }
            assertEquals(5L, state.cursor(workKey), "the recovery reset the other server's cursor")
        } finally {
            sut.close()
        }
    }

    /**
     * The reset is a file write and it can be refused (a full config dir). It used to run on the caller's
     * own thread, where the team operation that asked for it reported the failure; from a coroutine on a
     * SupervisorJob with no handler an exception reaches the platform's uncaught handler instead — which on
     * Android kills the app over a recovery the user never asked for by name. It has to become a status.
     */
    @Test
    fun `a recovery whose cursor write is refused is reported, not thrown into the void`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        val work = ReactivatingClient(ownWrap(vault), reactivated = false, serves = listOf(servedRecord()), servesCursor = 5)
        val state = CursorResetFailingStore(InMemorySyncStateStore())
        val sut = SyncCoordinator(
            clientFactory = { work },
            crypto = crypto,
            vault = vault,
            configStore = InMemorySyncConfigStore(),
            debtStore = InMemoryReconcileDebtStore(),
            syncState = state,
        )
        try {
            sut.connect(workUrl, account, password.toCharArray())
            sut.status.awaitStatus("the work session to come up") { it is SyncStatus.Online }
            val key = ServerLink(workUrl, account).cursorKey
            awaitSync("work's cursor to reach its tip") { while (state.cursor(key) != 5L) delay(20) }

            state.refuseReset = true
            sut.recoverFullPull()
            val failed = sut.status.awaitStatus("the refused reset to surface") { it is SyncStatus.Failed }
            assertEquals(
                SyncFailureReason.SyncFailed,
                (failed as SyncStatus.Failed).reason,
                "a refused cursor reset must be a failure the user can see",
            )
            assertEquals(5L, state.cursor(key), "the cursor must be left where it was")
        } finally {
            sut.close()
        }
    }

    /**
     * The rebuild owed to work was recorded under the URL as it was typed at the time. `disconnect` erases
     * the saved link, so the reconnect is typed from memory — and a spelling that differs only in case, a
     * default port or a trailing slash used to be a different server: no rebuild owed, and the records the
     * account purged while this device was revoked pushed straight back.
     */
    @Test
    fun `a rebuild owed to a server is owed however its url was typed`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "purged-while-revoked".encodeToByteArray())
        val work = ReactivatingClient(ownWrap(vault), reactivated = false)
        val debts = InMemoryReconcileDebtStore()
            .also { it.save(setOf(ServerLink(workUrl, account))) }
        val sut = SyncCoordinator(
            clientFactory = { work },
            crypto = crypto,
            vault = vault,
            configStore = InMemorySyncConfigStore(),
            debtStore = debts,
        )
        try {
            sut.connect("HTTPS://Work.test:443/", account, password.toCharArray())
            sut.status.awaitStatus("the connect to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(sut.status.value is SyncStatus.Online, "was ${sut.status.value}")

            assertFalse(work.pushed.any { it.id == "r1" }, "the record work purged must not be pushed back")
            assertTrue(work.pulledSince.contains(0L), "the rebuild's full re-pull did not run")
            assertFalse(debts.owes(workUrl, account), "the discharged rebuild must be retired")
        } finally {
            sut.close()
        }
    }

    /** Cursor store that refuses exactly the write a recovery makes (a full config dir). */
    private class CursorResetFailingStore(private val delegate: SyncStateStore) : SyncStateStore by delegate {
        @Volatile
        var refuseReset = false

        override fun setCursor(key: String, cursor: Long) {
            if (refuseReset && cursor == 0L) error("cursor write failed")
            delegate.setCursor(key, cursor)
        }
    }
}
