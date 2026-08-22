package app.skerry.ui.sync

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
 * The reactivation debt as a durable fact of its own: what must survive a link being overwritten by a
 * connect to another server, a disconnect, a restart, and a store that refuses the write (issues #168 and
 * #170). The reconcile it drives is exercised in [SyncCoordinatorReactivationTest]; the doubles both use
 * live in `ReactivationFixtures.kt`.
 */
class SyncCoordinatorReconcileDebtTest {

    private val crypto = IonspinVaultCrypto()
    private val serverUrl = "https://sync.test"
    private val account = "maya"
    private val password = "vault-A"

    private fun freshVault(): Vault = newAccountVault(crypto, password)
    private fun ownWrap(vault: Vault): ByteArray = wrapOwnKey(vault, crypto, password, account)


    /**
     * The upgrade path: 0.2.1 and earlier kept the intent on the saved link ([SyncConfig.legacyPendingReconcile]).
     * A device that upgrades mid-rebuild must not lose it — the server said `reactivated` once, and the
     * marker on the old config file is the only record of it.
     */
    @Test
    fun `a marker written by an older version is migrated into the debt store`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val config = InMemorySyncConfigStore()
        config.save(SyncConfig(serverUrl, account, deviceId = "devA", legacyPendingReconcile = true))
        val debts = InMemoryReconcileDebtStore()
        val client = ReactivatingClient(ownWrap(vault), reactivated = false)
        val sut = SyncCoordinator(
            clientFactory = { client },
            crypto = crypto,
            vault = vault,
            configStore = config,
            debtStore = debts,
        )
        try {
            assertTrue(debts.owes(serverUrl, account), "the marker on the old config is a debt now")
            assertFalse(config.load()!!.legacyPendingReconcile, "and it is cleared off the link, so it migrates once")

            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(sut.status.value is SyncStatus.Online, "was ${sut.status.value}")
            assertFalse(vault.records().any { it.id == "r1" }, "the migrated debt must still rebuild the vault")
            assertFalse(client.pushed.any { it.id == "r1" }, "the purged record must never be pushed back")
        } finally {
            sut.close()
        }
    }

    /**
     * A migration the debt store refuses must not keep the app from starting, and must not be the end of
     * the intent either: the legacy marker stays on the link, so the next launch tries again.
     */
    @Test
    fun `a refused migration leaves the legacy marker for the next launch`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()

        val legacy = SyncConfig(serverUrl, account, deviceId = "devA", legacyPendingReconcile = true)
        val config = InMemorySyncConfigStore().also { it.save(legacy) }
        val debts = DebtRaiseFailingStore(InMemoryReconcileDebtStore())
        val client = ReactivatingClient(ownWrap(vault), reactivated = false)
        val first = SyncCoordinator(
            clientFactory = { client },
            crypto = crypto,
            vault = vault,
            configStore = config,
            debtStore = debts,
        )
        first.close()
        assertFalse(debts.owes(serverUrl, account), "the refused write recorded nothing")
        assertTrue(config.load()!!.legacyPendingReconcile, "so the marker must still be on the link")

        debts.refuse = false // the disk has room again on the next launch
        val second = SyncCoordinator(
            clientFactory = { client },
            crypto = crypto,
            vault = vault,
            configStore = config,
            debtStore = debts,
        )
        try {
            assertTrue(debts.owes(serverUrl, account), "the retried migration records the debt")
            assertFalse(config.load()!!.legacyPendingReconcile, "and retires the marker it came from")
        } finally {
            second.close()
        }
    }

    /**
     * The device that gets reactivated normally still HAS its link — that is the production shape, and the
     * one the fresh-store tests never take. The link must come through the connect untouched: the deviceId
     * identifies this device to the account, and the keep-connected token is what lets the next launch
     * restore without a password. Recording the debt on the config instead of beside it would put both at
     * the mercy of a connect that has not succeeded.
     */
    @Test
    fun `recording a reactivation leaves the saved link untouched`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()

        val linked = SyncConfig(serverUrl, account, deviceId = "devA", keepConnected = true, sealedRefreshToken = "sealed")
        val config = InMemorySyncConfigStore().also { it.save(linked) }
        val debts = InMemoryReconcileDebtStore()
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
            assertEquals(linked, config.load(), "the link must survive the failed connect as it was")
            assertTrue(debts.owes(serverUrl, account), "the rebuild is recorded beside it")
        } finally {
            sut.close()
        }
    }

    /**
     * A connect that hasn't succeeded has earned no link: a failed connect to another account must leave
     * the saved one — its deviceId and its keep-connected token — exactly as it was, rather than trade a
     * device that is still fine for the reconcile intent of an account it isn't linked to. The debt goes
     * to its own store instead, where it belongs to the link it was learned on.
     */
    @Test
    fun `a failed connect does not overwrite the link to another account`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()

        val linked = SyncConfig(serverUrl, "other-account", deviceId = "devOther", keepConnected = true, sealedRefreshToken = "sealed")
        val config = InMemorySyncConfigStore().also { it.save(linked) }
        val debts = InMemoryReconcileDebtStore()
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
            assertEquals(linked, config.load(), "a failed connect must leave the saved link untouched")
            assertTrue(debts.owes(serverUrl, account), "the rebuild is owed by the account that was reactivated")
            assertFalse(debts.owes(serverUrl, "other-account"), "and by no other")
        } finally {
            sut.close()
        }
    }

    /**
     * Disconnect erases the link, but it rebuilds nothing: the records the reconcile was supposed to drop
     * are still in the vault. A debt that was never actually paid must therefore survive it, or the
     * reconnect after a disconnect is the ordinary incremental one and pushes the purged records straight
     * back. (Here the reconcile's clear failed, which is exactly the state in which a user reaches for
     * Disconnect.)
     */
    @Test
    fun `a disconnect does not pay a rebuild that never ran`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val config = InMemorySyncConfigStore().also { it.save(SyncConfig(serverUrl, account, deviceId = "devA")) }
        val debts = InMemoryReconcileDebtStore()
        val client = ReactivatingClient(ownWrap(vault), reactivated = true)
        val sut = SyncCoordinator(
            clientFactory = { client },
            crypto = crypto,
            vault = ClearFailingVault(vault, failures = 1),
            configStore = config,
            debtStore = debts,
        )
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to fail on the clear") { it is SyncStatus.Failed }

            sut.disconnect()
            sut.status.awaitStatus("the link to be erased") { it is SyncStatus.Disabled }
            assertEquals(null, config.load(), "disconnect erases the link")
            assertTrue(debts.owes(serverUrl, account), "but not a rebuild it did not run")

            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the reconnect to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(sut.status.value is SyncStatus.Online, "was ${sut.status.value}")
            assertFalse(vault.records().any { it.id == "r1" }, "the rebuild is still owed — disconnect ran no reconcile")
            assertFalse(client.pushed.any { it.id == "r1" }, "the purged record must never be pushed back")
        } finally {
            sut.close()
        }
    }

    /**
     * Issue #170: the debt belongs to a link and the saved config holds exactly one, so an ordinary
     * successful connect to ANOTHER server saves a config with no room for the previous link's marker.
     * The debt has to be recorded somewhere the config cannot overwrite — otherwise it lives only in
     * process memory, and the restart after that connect loses it: reconnecting to the server that
     * reactivated this device is then an ordinary incremental reconnect that pushes back every record
     * the account purged while it was revoked.
     */
    @Test
    fun `a debt survives a connect to another link and the restart that follows`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val homeUrl = "https://home.test"
        // Home revoked this device: the login reports the reactivation, and the connect then fails on an
        // unopenable wrap — the recorded debt is all that is left of a signal the server never repeats.
        val home = ReactivatingClient(ownWrap(vault), reactivated = true, corruptWraps = 1)
        val work = ReactivatingClient(ownWrap(vault), reactivated = false)
        val factory = { url: String -> if (url == homeUrl) home else work }
        val config = InMemorySyncConfigStore().also { it.save(SyncConfig(homeUrl, account, deviceId = "devA")) }
        val debts = InMemoryReconcileDebtStore()

        val before = SyncCoordinator(
            clientFactory = factory,
            crypto = crypto,
            vault = vault,
            configStore = config,
            debtStore = debts,
        )
        try {
            before.connect(homeUrl, account, password.toCharArray())
            before.status.awaitStatus("the home connect to fail on the unopenable wrap") { it is SyncStatus.Failed }

            // Nothing wrong with this connect — it is another server, it succeeds, and it saves its own link.
            before.connect(serverUrl, account, password.toCharArray())
            before.status.awaitStatus("the work connect to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(before.status.value is SyncStatus.Online, "was ${before.status.value}")
            assertEquals(serverUrl, config.load()?.serverUrl, "the saved link is the work one now")
        } finally {
            before.close()
        }

        // The restart: only the stores and the vault survive it.
        val after = SyncCoordinator(
            clientFactory = factory,
            crypto = crypto,
            vault = vault,
            configStore = config,
            debtStore = debts,
        )
        try {
            after.connect(homeUrl, account, password.toCharArray())
            after.status.awaitStatus("the home reconnect to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(after.status.value is SyncStatus.Online, "was ${after.status.value}")
            assertFalse(vault.records().any { it.id == "r1" }, "the rebuild owed to home survives the link that was overwritten")
            assertFalse(home.pushed.any { it.id == "r1" }, "the purged record must never be pushed back")
        } finally {
            after.close()
        }
    }

    /**
     * The account id is chosen by the user and says nothing about which server it belongs to — the same
     * one names two accounts on a home and a work instance. A rebuild owed to one of them must not be
     * charged to the other: the vault would be wiped of records the other server never purged, and they
     * were never pushed to it either.
     */
    @Test
    fun `a rebuild owed to one server is not charged to another`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        // Linked to the home instance, so the failed connect below records a debt charged to THAT link.
        val config = InMemorySyncConfigStore()
            .also { it.save(SyncConfig("https://home.test", account, deviceId = "devA")) }
        val debts = InMemoryReconcileDebtStore()
        val client = ReactivatingClient(ownWrap(vault), reactivated = true, corruptWraps = 1)
        val sut = SyncCoordinator(
            clientFactory = { client },
            crypto = crypto,
            vault = vault,
            configStore = config,
            debtStore = debts,
        )
        try {
            // The home instance: this device was revoked there, and the connect fails after the login.
            sut.connect("https://home.test", account, password.toCharArray())
            sut.status.awaitStatus("the connect to fail on the unopenable wrap") { it is SyncStatus.Failed }

            // The work instance, same account id: an ordinary connect that owes nothing.
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the second connect to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(sut.status.value is SyncStatus.Online, "was ${sut.status.value}")
            assertTrue(vault.records().any { it.id == "r1" }, "another server's reactivation must not clear this vault")
            assertTrue(debts.owes("https://home.test", account), "and the home rebuild is still owed")
        } finally {
            sut.close()
        }
    }

    /**
     * The reactivation is reported, the debt write is refused (a full disk), and the connect fails
     * loudly — but the device is keep-connected, so what happens next is a silent restore that never
     * logs in again. It has to see the rebuild is owed from the only place it was recorded: this
     * process's memory. Otherwise the session comes Online and pushes the purged records back.
     */
    @Test
    fun `a restore honors a rebuild that could not be written down`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val config = InMemorySyncConfigStore().also {
            it.save(keepConnectedLink(vault, crypto, serverUrl, account, deviceId = "devA"))
        }
        val debts = DebtRaiseFailingStore(InMemoryReconcileDebtStore())
        val client = ReactivatingClient(ownWrap(vault), reactivated = true)
        val sut = SyncCoordinator(
            clientFactory = { client },
            crypto = crypto,
            vault = vault,
            configStore = config,
            debtStore = debts,
        )
        try {
            sut.connect(serverUrl, account, password.toCharArray(), keepConnected = true)
            sut.status.awaitStatus("the connect to fail on the refused write") { it is SyncStatus.Failed }
            assertFalse(debts.owes(serverUrl, account), "the debt never made it to disk")

            debts.refuse = false // the disk has room again by the time the restore runs
            sut.restoreSession()
            sut.status.awaitStatus("the silent restore to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(sut.status.value is SyncStatus.Online, "was ${sut.status.value}")
            assertFalse(vault.records().any { it.id == "r1" }, "the restore must rebuild — the debt is still owed")
            assertFalse(client.pushed.any { it.id == "r1" }, "the purged record must never be pushed back")
        } finally {
            sut.close()
        }
    }

    /**
     * The debt outlives the connect that learned of it even when this device is linked somewhere else
     * entirely: the server said it was reactivated on THIS link and will never say it again, so the retry
     * that finally connects must still rebuild. Under the old design there was nowhere to put it — the
     * saved link was another account's, and the config holds one — which is how the intent used to die.
     */
    @Test
    fun `a reactivation on a link this device is not on still reconciles on the next connect`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val config = InMemorySyncConfigStore()
            .also { it.save(SyncConfig(serverUrl, "other-account", deviceId = "devOther")) }
        val debts = InMemoryReconcileDebtStore()
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
            assertTrue(debts.owes(serverUrl, account), "the debt is charged to the link that reactivated it")

            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the retry to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(sut.status.value is SyncStatus.Online, "was ${sut.status.value}")
            assertFalse(vault.records().any { it.id == "r1" }, "the retry must rebuild — the signal is gone from the server")
            assertFalse(client.pushed.any { it.id == "r1" }, "the purged record must never be pushed back")
        } finally {
            sut.close()
        }
    }

    /**
     * The refusal guard reads the debts this process knows about, so one that never reached disk must be
     * visible to it too. A password rotation re-publishes the session without reconciling: with nothing to
     * see, its first cycle would push the pre-revocation records the failed connect never got to drop.
     */
    @Test
    fun `a rotation cannot sync over a rebuild that could not be written down`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val config = InMemorySyncConfigStore().also { it.save(SyncConfig(serverUrl, account, deviceId = "devA")) }
        val debts = DebtRaiseFailingStore(InMemoryReconcileDebtStore())
        val client = ReactivatingClient(ownWrap(vault), reactivated = true)
        val sut = SyncCoordinator(
            clientFactory = { client },
            crypto = crypto,
            vault = vault,
            configStore = config,
            debtStore = debts,
        )
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to fail on the refused write") { it is SyncStatus.Failed }
            assertFalse(debts.owes(serverUrl, account), "the debt never made it to disk")

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

    /**
     * Retiring a debt is a store write, and a store write can fail. It must not turn a sync that actually
     * succeeded into a reported failure — the debt simply stays standing, and the next cycle retires it,
     * the same fallback an interrupted reconcile relies on.
     */
    @Test
    fun `a refused retiring write keeps the sync green and is retried by the next cycle`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val debts = DebtClearFailingStore(InMemoryReconcileDebtStore())
        val client = ReactivatingClient(ownWrap(vault), reactivated = true)
        val sut = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault, debtStore = debts)
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(sut.status.value is SyncStatus.Online, "a refused debt write must not fail a sync that succeeded")
            assertTrue(debts.owes(serverUrl, account), "a refused write leaves the debt standing")

            debts.refuseClear = false
            sut.syncNow()
            awaitSync("the retried write to land") { while (debts.owes(serverUrl, account)) delay(20) }
        } finally {
            sut.close()
        }
    }
}
