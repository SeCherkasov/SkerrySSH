package app.skerry.ui.sync

import app.skerry.shared.sync.InMemorySyncStateStore
import app.skerry.shared.sync.SyncSettings
import app.skerry.shared.sync.SyncSettingsStore
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PR #51, the second half: excluding revoked devices from the tombstone watermark lets the account purge a
 * tombstone while a revoked device still holds the record LIVE (it never pulled the tombstone). On
 * reactivation that device's full push would re-upload the record (the server has no row for it → resurrected)
 * and it would spread back to every peer. The coordinator closes this window: when the login reports the
 * device was reactivated — or a rebuild recorded earlier is still owed — it rebuilds the vault from the
 * server snapshot before the first push.
 *
 * What the debt itself must survive (a link overwritten, a disconnect, a restart) is
 * [SyncCoordinatorReconcileDebtTest]; the doubles both use live in `ReactivationFixtures.kt`.
 *
 * Real [Vault] + real [IonspinVaultCrypto] (the reconcile is the whole point) and the REAL sync engine runs
 * so the push path is genuinely exercised; only the network is stubbed.
 */
class SyncCoordinatorReactivationTest {

    private val crypto = IonspinVaultCrypto()
    private val serverUrl = "https://sync.test"
    private val account = "maya"
    private val password = "vault-A"

    private fun freshVault(): Vault = newAccountVault(crypto, password)
    private fun ownWrap(vault: Vault): ByteArray = wrapOwnKey(vault, crypto, password, account)


    @Test
    fun `reactivated device drops its stale record, does not re-push it, and retires the debt`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        // The device still holds r1 LIVE — it was revoked before it could pull the tombstone.
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val client = ReactivatingClient(ownWrap(vault), reactivated = true)
        val debts = InMemoryReconcileDebtStore()
        val sut = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault, debtStore = debts)
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(sut.status.value is SyncStatus.Online, "reactivation connect should come Online")
            assertFalse(vault.records().any { it.id == "r1" }, "a reactivated device must discard its pre-revocation records")
            assertFalse(client.pushed.any { it.id == "r1" }, "a reactivated device must not re-push a purged record")
            // The debt is retired once the reconcile's first sync succeeded.
            assertFalse(debts.owes(serverUrl, account), "a completed reconcile retires the debt")
        } finally {
            sut.close()
        }
    }

    @Test
    fun `a debt from an interrupted run is redone even when the login is not a reactivation`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        // A previous reactivation was interrupted after the server cleared revocation but before the vault
        // was rebuilt: the recorded debt survived. This login is NOT a reactivation (server already sees
        // the device as live), so only the debt can drive the reconcile.
        val config = InMemorySyncConfigStore()
        config.save(SyncConfig(serverUrl, account, deviceId = "devA"))
        val debts = InMemoryReconcileDebtStore().also { it.save(setOf(ServerLink(serverUrl, account))) }
        val client = ReactivatingClient(ownWrap(vault), reactivated = false)
        val sut = SyncCoordinator(
            clientFactory = { client },
            crypto = crypto,
            vault = vault,
            configStore = config,
            debtStore = debts,
        )
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(sut.status.value is SyncStatus.Online, "reconnect should come Online")
            assertFalse(vault.records().any { it.id == "r1" }, "an owed rebuild must still rebuild the vault")
            assertFalse(client.pushed.any { it.id == "r1" }, "an owed rebuild must not let the stale record push")
            assertFalse(debts.owes(serverUrl, account), "the redone reconcile retires the debt")
        } finally {
            sut.close()
        }
    }

    /**
     * Issue #168: the login succeeds and reports the reactivation, then the connect fails on its way to
     * the session — here on an account wrap that doesn't open (issue #133's refusal). The server already
     * cleared the revocation on that verify and will never report it again, so an intent persisted only
     * by a connect that reaches the end is no intent at all: the next connect would look like an ordinary
     * incremental reconnect and push the pre-revocation records straight back.
     */
    @Test
    fun `a connect that fails after the login keeps the reactivation it was told about`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val config = InMemorySyncConfigStore()
        val debts = InMemoryReconcileDebtStore()
        // The first connect's key fetch serves an unopenable wrap; the second one is served the real key.
        val client = ReactivatingClient(ownWrap(vault), reactivated = true, corruptWraps = 1)
        val sut = SyncCoordinator(
            clientFactory = { client },
            crypto = crypto,
            vault = vault,
            configStore = config,
            debtStore = debts,
        )
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to fail on the unopenable wrap") { it is SyncStatus.Failed }
            assertEquals(
                SyncFailureReason.AccountKeyNotAdopted,
                (sut.status.value as? SyncStatus.Failed)?.reason,
                "was ${sut.status.value}",
            )
            assertEquals(null, config.load(), "a connect that never reached a session must not save a link")
            assertTrue(debts.owes(serverUrl, account), "and it records the rebuild it was told about, link or no link")

            // The repaired server state, and a login that reports nothing: the debt the failed connect
            // recorded is the only thing that still knows a rebuild is owed.
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the repaired connect to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(sut.status.value is SyncStatus.Online, "was ${sut.status.value}")
            assertFalse(vault.records().any { it.id == "r1" }, "the deferred reconcile must still drop the pre-revocation record")
            assertFalse(client.pushed.any { it.id == "r1" }, "the purged record must never be pushed back")
            assertFalse(debts.owes(serverUrl, account), "the completed reconcile retires the debt")
        } finally {
            sut.close()
        }
    }

    /**
     * The connect need not fail with a status of its own to lose the signal: a network error while
     * fetching the account key throws straight past every early return into the catch-all. The debt has
     * to be on disk before that fetch, not after it.
     */
    @Test
    fun `a connect that throws after the login keeps the reactivation`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val config = InMemorySyncConfigStore().also { it.save(SyncConfig(serverUrl, account, deviceId = "devA")) }
        val debts = InMemoryReconcileDebtStore()
        val client = ReactivatingClient(ownWrap(vault), reactivated = true, throwOnFirstFetch = true)
        val sut = SyncCoordinator(
            clientFactory = { client },
            crypto = crypto,
            vault = vault,
            configStore = config,
            debtStore = debts,
        )
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to fail on the key fetch") { it is SyncStatus.Failed }
            assertTrue(debts.owes(serverUrl, account), "a throw after the login must not take the reactivation with it")
        } finally {
            sut.close()
        }
    }

    /**
     * A keep-connected device recovers without a password: the next launch refreshes its saved token
     * instead of logging in, and `refresh` carries no `reactivated` signal at all. The debt recorded by
     * the failed connect is the only thing that can make that silent restore rebuild the vault first —
     * which is the whole reason the intent is durable rather than kept in memory.
     */
    @Test
    fun `a keep-connected restore finishes the reconcile a failed connect left owed`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        // The link a keep-connected device already has — the failed connect below never reaches a session,
        // so it seals no token of its own.
        val dataKey = vault.exportDataKey()!!
        val sealed = SealedTokenCodec(crypto).seal(dataKey, "refresh").also { dataKey.zeroize() }
        val config = InMemorySyncConfigStore().also {
            it.save(SyncConfig(serverUrl, account, deviceId = "devA", keepConnected = true, sealedRefreshToken = sealed))
        }
        val debts = InMemoryReconcileDebtStore()
        val client = ReactivatingClient(ownWrap(vault), reactivated = true, corruptWraps = 1)
        val failed = SyncCoordinator(
            clientFactory = { client },
            crypto = crypto,
            vault = vault,
            configStore = config,
            debtStore = debts,
        )
        try {
            failed.connect(serverUrl, account, password.toCharArray(), keepConnected = true)
            failed.status.awaitStatus("the connect to fail on the unopenable wrap") { it is SyncStatus.Failed }
        } finally {
            failed.close()
        }
        assertTrue(debts.owes(serverUrl, account), "the failed connect left the rebuild owed")

        // A new process: no coordinator state survives, only the stores and the vault.
        val restored = SyncCoordinator(
            clientFactory = { client },
            crypto = crypto,
            vault = vault,
            configStore = config,
            debtStore = debts,
        )
        try {
            restored.restoreSession()
            restored.status.awaitStatus("the silent restore to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(restored.status.value is SyncStatus.Online, "was ${restored.status.value}")
            assertFalse(vault.records().any { it.id == "r1" }, "the restore must run the reconcile the connect never got to")
            assertFalse(client.pushed.any { it.id == "r1" }, "the purged record must never be pushed back")
            assertFalse(debts.owes(serverUrl, account), "the completed reconcile retires the debt")
        } finally {
            restored.close()
        }
    }

    /**
     * [SyncStatus.Online] is what the UI and every other observer react to, so it must not arrive while the
     * debt still stands: an observer would read a state the reconcile has already left behind. The sample is
     * taken by an unconfined collector, i.e. inside the emission itself, so it sees exactly what the
     * coordinator published rather than what it got around to writing afterwards.
     */
    @Test
    fun `the reconcile debt is already retired when the connect publishes Online`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val debts = InMemoryReconcileDebtStore()
        val client = ReactivatingClient(ownWrap(vault), reactivated = true)
        val sut = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault, debtStore = debts)
        val markerAtOnline = CompletableDeferred<Boolean?>()
        val observer = launch(Dispatchers.Unconfined) {
            sut.status.collect { if (it is SyncStatus.Online) markerAtOnline.complete(debts.owes(serverUrl, account)) }
        }
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            assertEquals(
                false,
                awaitSync("the reactivation connect to publish Online") { markerAtOnline.await() },
                "Online must not be published while the rebuild is still owed",
            )
        } finally {
            observer.cancel()
            sut.close()
        }
    }

    /**
     * The reconcile is finished by whichever cycle first succeeds, not only by the one the connect
     * starts: a first cycle that fails leaves the vault cleared and the cursor at 0, so the next sync is
     * still the full re-pull the debt is waiting for. Retiring it only in the connect's own cycle would
     * leave the debt standing for good and redo the reconcile on every later connect.
     */
    @Test
    fun `a reconcile whose first cycle failed is completed by the next sync that succeeds`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val debts = InMemoryReconcileDebtStore()
        val client = ReactivatingClient(ownWrap(vault), reactivated = true, failFirstPull = true)
        val sut = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault, debtStore = debts)
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the reconcile's first cycle to fail") { it is SyncStatus.Failed }
            assertTrue(debts.owes(serverUrl, account), "a failed cycle must leave the debt standing")

            sut.syncNow()
            sut.status.awaitStatus("the status to come Online") { it is SyncStatus.Online }
            assertFalse(debts.owes(serverUrl, account), "the cycle that succeeded completes the reconcile")
        } finally {
            sut.close()
        }
    }

    /**
     * The debt is recorded BEFORE the vault is cleared (a crash in between must not lose the signal), so it
     * can stand on a device whose reconcile never actually ran — the clear threw and the session published
     * a moment earlier is still live. That session must not sync: its vault still holds the pre-revocation
     * records the server purged, and a cycle would push them straight back and report Online while doing it
     * (issue #142). The cycle is refused and the status parks on the link state; the rebuild is left to the
     * next connect/restore, which redoes the reconcile.
     */
    @Test
    fun `a session whose reconcile never cleared the vault refuses to sync`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val debts = InMemoryReconcileDebtStore()
        val client = ReactivatingClient(ownWrap(vault), reactivated = true)
        val sut = SyncCoordinator(
            clientFactory = { client },
            crypto = crypto,
            vault = ClearFailingVault(vault),
            debtStore = debts,
        )
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to fail on the clear") { it is SyncStatus.Failed }
            assertTrue(debts.owes(serverUrl, account), "a reconcile that could not clear the vault keeps the debt")

            sut.syncNow()
            val settled = sut.status.awaitStatus("the manual sync to settle") {
                it is SyncStatus.Online || (it as? SyncStatus.Failed)?.reason == SyncFailureReason.ReconcileRequired
            }
            assertEquals(
                SyncStatus.Failed(SyncFailureReason.ReconcileRequired),
                settled,
                "a device that owes a reconcile must refuse the cycle, not run it and report Online",
            )
            assertFalse(
                client.pushed.any { it.id == "r1" },
                "a refused cycle must not push the records the reconcile was supposed to drop",
            )
            assertTrue(debts.owes(serverUrl, account), "only a reconcile that actually ran may retire the debt")
            assertTrue(vault.records().any { it.id == "r1" }, "the stale record is still there — the reconcile never ran")
        } finally {
            sut.close()
        }
    }

    /**
     * The refusal is a stop, not a dead end: the debt is still recorded, so the next connect on the SAME
     * coordinator redoes the reconcile — this time over a vault that lets the clear through — and the
     * device comes back Online with its records rebuilt from the server. Recovery through a fresh
     * coordinator (an app restart) is a different path and is covered by the interrupted-run test above.
     */
    @Test
    fun `a reconnect finishes the reconcile the refused cycle was waiting for`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val debts = InMemoryReconcileDebtStore()
        val client = ReactivatingClient(ownWrap(vault), reactivated = true)
        val sut = SyncCoordinator(
            clientFactory = { client },
            crypto = crypto,
            vault = ClearFailingVault(vault, failures = 1),
            debtStore = debts,
        )
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to fail on the clear") { it is SyncStatus.Failed }
            sut.syncNow()
            sut.status.awaitStatus("the cycle to be refused") {
                (it as? SyncStatus.Failed)?.reason == SyncFailureReason.ReconcileRequired
            }

            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the reconnect to settle") { it is SyncStatus.Online }
            assertFalse(vault.records().any { it.id == "r1" }, "the redone reconcile must drop the pre-revocation record")
            assertFalse(client.pushed.any { it.id == "r1" }, "the purged record must never have been pushed")
            assertFalse(debts.owes(serverUrl, account), "the completed reconcile retires the debt")
        } finally {
            sut.close()
        }
    }

    /**
     * The clear fails because the vault locked inside the connect, and what the user does next is unlock it
     * — exactly the condition the reconcile was missing. The unlock must redo the reconcile on the session
     * that is still live, not only re-run the cycle that keeps being refused: the rebuild needs no password,
     * so sending the user back to Settings → Sync to retype the master password is a dead end (issue #147).
     */
    @Test
    fun `an unlock finishes the reconcile the refused cycle was waiting for`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val debts = InMemoryReconcileDebtStore()
        val client = ReactivatingClient(ownWrap(vault), reactivated = true)
        // A cursor from before the revocation: the reconcile has to reset it, or the re-pull that rebuilds
        // the vault would ask for changes since the tip and get nothing back.
        val state = InMemorySyncStateStore().also { it.setCursor(account, 42) }
        val sut = SyncCoordinator(
            clientFactory = { client },
            crypto = crypto,
            vault = ClearFailingVault(vault, failures = 1),
            debtStore = debts,
            syncState = state,
        )
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to fail on the clear") { it is SyncStatus.Failed }
            sut.syncNow()
            sut.status.awaitStatus("the cycle to be refused") {
                (it as? SyncStatus.Failed)?.reason == SyncFailureReason.ReconcileRequired
            }

            // The whole lock cycle, not just the resume callback: this is the state the user is actually
            // in — the vault that made the clear fail is locked, and they unlock it.
            vault.lock()
            sut.pauseForLock()
            sut.status.awaitStatus("the lock to park the status") { it is SyncStatus.Configured }
            assertTrue(vault.unlock(password.toCharArray()) is UnlockResult.Success)
            sut.resumeAfterUnlock()
            sut.status.awaitStatus("the unlock to finish the reconcile") { it is SyncStatus.Online }
            assertFalse(vault.records().any { it.id == "r1" }, "the redone reconcile must drop the pre-revocation record")
            assertFalse(client.pushed.any { it.id == "r1" }, "the purged record must never have been pushed")
            assertFalse(debts.owes(serverUrl, account), "the completed reconcile retires the debt")
            // The cycle the connect would have run never happened (the clear threw first), so the first
            // pull of the whole test is the one the redo armed.
            assertEquals(0L, client.pulledSince.firstOrNull(), "the rebuild must be a full re-pull, not one from the stale cursor")
        } finally {
            sut.close()
        }
    }

    /**
     * The redone reconcile can fail too — the vault re-locked between the unlock and the clear. That must
     * stay the same stop as before (the debt standing, the cycle refused), not turn the unlock into a new
     * failure that hides the recovery the status names.
     *
     * The lock is real here (`pauseForLock` parks the status on Configured), so the refusal after the
     * resume is a status the redo path has to publish rather than the one already on screen: a redo that
     * lets the clear's exception escape kills the resume before its cycle runs, and the status stays
     * Configured.
     */
    @Test
    fun `an unlock whose reconcile fails again keeps the refusal, not a new failure`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val debts = InMemoryReconcileDebtStore()
        val client = ReactivatingClient(ownWrap(vault), reactivated = true)
        val clearFailing = ClearFailingVault(vault)
        val sut = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = clearFailing, debtStore = debts)
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to fail on the clear") { it is SyncStatus.Failed }
            sut.syncNow()
            sut.status.awaitStatus("the cycle to be refused") {
                (it as? SyncStatus.Failed)?.reason == SyncFailureReason.ReconcileRequired
            }

            vault.lock()
            sut.pauseForLock()
            sut.status.awaitStatus("the lock to park the status") { it is SyncStatus.Configured }
            assertTrue(vault.unlock(password.toCharArray()) is UnlockResult.Success)
            sut.resumeAfterUnlock()

            sut.status.awaitStatus("the refusal to be published again") {
                (it as? SyncStatus.Failed)?.reason == SyncFailureReason.ReconcileRequired
            }
            assertEquals(2, clearFailing.clearAttempts, "the unlock must have retried the reconcile exactly once")
            assertTrue(debts.owes(serverUrl, account), "a reconcile that failed again keeps the debt")
            assertTrue(vault.records().any { it.id == "r1" }, "nothing was cleared — the record is still there")
            assertFalse(client.pushed.any { it.id == "r1" }, "and it must not have been pushed")
        } finally {
            sut.close()
        }
    }

    /**
     * The other branch of the same guard, and the one with teeth: an unlock on a session that owes nothing
     * must not reconcile. Without the [SyncCoordinator] check, every ordinary unlock would record a fresh
     * debt and wipe every host and snippet in the vault — the mirror image of the bug this path fixes.
     */
    @Test
    fun `an unlock with no reconcile owed leaves the vault alone`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val debts = InMemoryReconcileDebtStore()
        // Not a reactivation and nothing owed: an ordinary connected device.
        val client = ReactivatingClient(ownWrap(vault), reactivated = false)
        val sut = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault, debtStore = debts)
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to come Online") { it is SyncStatus.Online }

            vault.lock()
            sut.pauseForLock()
            sut.status.awaitStatus("the lock to park the status") { it is SyncStatus.Configured }
            assertTrue(vault.unlock(password.toCharArray()) is UnlockResult.Success)
            sut.resumeAfterUnlock()
            sut.status.awaitStatus("the unlock to bring sync back") { it is SyncStatus.Online }

            assertTrue(vault.records().any { it.id == "r1" }, "an unlock that owes no reconcile must not clear the vault")
            assertFalse(debts.owes(serverUrl, account), "and must not record a debt of its own")
        } finally {
            sut.close()
        }
    }

    /**
     * A password rotation re-activates the session without reconciling, and the debt it lands on is still
     * standing. When the reconcile it belongs to already ran here (records dropped, only the retiring write
     * refused), that re-activation must not disarm the coordinator: doing so would make every later cycle
     * refuse — including the one that would finally retire the debt — and the device would sit blocked
     * until a reconnect for a rebuild that already happened.
     */
    @Test
    fun `a password rotation over a reconcile that already ran keeps syncing`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val debts = DebtClearFailingStore(InMemoryReconcileDebtStore())
        val client = ReactivatingClient(ownWrap(vault), reactivated = true)
        val sut = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault, debtStore = debts)
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(sut.status.value is SyncStatus.Online, "the reconcile itself succeeded — only its retiring write was refused")
            assertTrue(debts.owes(serverUrl, account), "the refused write leaves the debt standing")

            // changeAccountPassword awaits the activation it triggers, so the cycle has already run here.
            assertEquals(
                AccountPasswordChange.Success,
                sut.changeAccountPassword(password.toCharArray(), "vault-B".toCharArray()),
            )
            assertTrue(
                sut.status.value is SyncStatus.Online,
                "a re-activation landing on a debt whose reconcile already ran must keep syncing",
            )
        } finally {
            sut.close()
        }
    }

    /**
     * The mirror case: the reconcile never ran (the clear threw), and a password rotation re-publishes
     * the session without reconciling either. The rotation itself succeeds — the server did change the
     * password — but the vault it re-publishes still holds the purged records, so the cycle is refused
     * instead of pushing them. The activation path is not the reactivation one, which is the point:
     * the refusal is keyed on the debt, not on who published the session.
     */
    @Test
    fun `a password rotation cannot sync a vault whose reconcile never ran`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val client = ReactivatingClient(ownWrap(vault), reactivated = true)
        val sut = SyncCoordinator(
            clientFactory = { client },
            crypto = crypto,
            vault = ClearFailingVault(vault),
        )
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to fail on the clear") { it is SyncStatus.Failed }

            assertEquals(
                AccountPasswordChange.Success,
                sut.changeAccountPassword(password.toCharArray(), "vault-B".toCharArray()),
            )
            assertEquals(
                SyncStatus.Failed(SyncFailureReason.ReconcileRequired),
                sut.status.value,
                "an activation that doesn't reconcile must not sync a vault that still owes one",
            )
            assertFalse(client.pushed.any { it.id == "r1" }, "the purged record must not reach the server")
        } finally {
            sut.close()
        }
    }

    @Test
    fun `reactivation clears a record whose type is locally disabled but may be enabled on the server`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        // This device has "sync hosts" turned OFF locally, so its stale HOST record is not in the local
        // push filter. But the account may have hosts sync ON: after the reconciling pull applies the
        // server's settings, the push filter flips on and the stale record would resurrect — unless the
        // clear covers every sync-capable type regardless of the (stale) local toggle. It must.
        SyncSettingsStore(vault).save(SyncSettings(syncHosts = false))
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val client = ReactivatingClient(ownWrap(vault), reactivated = true)
        val sut = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault)
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(sut.status.value is SyncStatus.Online, "reactivation connect should come Online")
            assertFalse(
                vault.records().any { it.id == "r1" },
                "the clear must not be gated by the stale local sync toggles — a locally-disabled type must be cleared too",
            )
        } finally {
            sut.close()
        }
    }
}
