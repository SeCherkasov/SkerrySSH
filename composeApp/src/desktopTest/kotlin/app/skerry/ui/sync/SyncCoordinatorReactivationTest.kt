package app.skerry.ui.sync

import app.skerry.shared.sync.DeviceInfo
import app.skerry.shared.sync.PairingResult
import app.skerry.shared.sync.PairingTicket
import app.skerry.shared.sync.RecordPage
import app.skerry.shared.sync.RemoteDevice
import app.skerry.shared.sync.RemoteRecord
import app.skerry.shared.sync.SyncClient
import app.skerry.shared.sync.SyncException
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.sync.SyncSettings
import app.skerry.shared.sync.SyncSettingsStore
import app.skerry.shared.sync.SyncSignal
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PR #51, the second half: excluding revoked devices from the tombstone watermark lets the account purge a
 * tombstone while a revoked device still holds the record LIVE (it never pulled the tombstone). On
 * reactivation that device's full push would re-upload the record (the server has no row for it → resurrected)
 * and it would spread back to every peer. The coordinator closes this window: when the login reports the
 * device was reactivated — or a previously interrupted reconcile is still pending — it rebuilds the vault
 * from the server snapshot before the first push.
 *
 * Real [FileVault] + real [IonspinVaultCrypto] (the reconcile is the whole point) and the REAL [SyncEngine]
 * runs so the push path is genuinely exercised; only the network is stubbed.
 */
class SyncCoordinatorReactivationTest {

    private val crypto = IonspinVaultCrypto()
    private val serverUrl = "https://sync.test"
    private val account = "maya"
    private val password = "vault-A"

    /**
     * This device's own account after a server-side purge: `register` collides (account exists), `login`
     * reports [reactivated], the served wrap is this vault's OWN key (so the connect adopts nothing —
     * isolating the reactivation path), and the server no longer holds `r1` (purged), so `pull` returns
     * nothing. `push` records exactly what the client sent.
     */
    private inner class ReactivatingClient(
        private val ownWrappedKey: ByteArray,
        private val reactivated: Boolean = true,
        /** Makes the reconcile's own first cycle fail, leaving the rebuild to a later sync. */
        private val failFirstPull: Boolean = false,
    ) : SyncClient {
        val pushed = mutableListOf<RemoteRecord>()
        private val pulls = AtomicInteger(0)

        override suspend fun register(accountId: String, authKey: ByteArray, wrappedDataKey: ByteArray, device: DeviceInfo): SyncSession =
            throw SyncException(SyncException.Kind.CONFLICT, "account exists")
        override suspend fun login(accountId: String, authKey: ByteArray, device: DeviceInfo): SyncSession =
            SyncSession(accountId, accessToken = "access", refreshToken = "refresh", reactivated = reactivated)
        override suspend fun fetchWrappedDataKey(session: SyncSession): ByteArray = ownWrappedKey.copyOf()
        override suspend fun pull(session: SyncSession, since: Long): RecordPage {
            // Not a SyncException(NETWORK): that one arms the backoff retry loop, and the test wants the
            // next cycle to be the one it triggers itself.
            if (failFirstPull && pulls.getAndIncrement() == 0) error("pull unreachable")
            return RecordPage(emptyList(), 1)
        }
        override suspend fun push(session: SyncSession, records: List<RemoteRecord>): RecordPage {
            pushed += records
            return RecordPage(emptyList(), 1)
        }
        override fun changes(session: SyncSession): Flow<SyncSignal> = emptyFlow()
        override suspend fun ping(): Boolean = true
        override suspend fun close() {}
        override suspend fun listDevices(session: SyncSession): List<RemoteDevice> = emptyList()
        override suspend fun revokeDevice(session: SyncSession, deviceId: String): Boolean = false
        override suspend fun refresh(session: SyncSession): SyncSession = throw NotImplementedError()
        // The rotation itself isn't what these tests are about: they need the activation that follows it,
        // which re-publishes the session WITHOUT reconciling. Accepts any current password.
        override suspend fun changePassword(accountId: String, currentAuthKey: ByteArray, newAuthKey: ByteArray, newWrappedDataKey: ByteArray, device: DeviceInfo): SyncSession =
            SyncSession(accountId, accessToken = "access2", refreshToken = "refresh2")
        override suspend fun startPairing(session: SyncSession, encryptedDataKey: ByteArray): PairingTicket = throw NotImplementedError()
        override suspend fun claimPairing(code: String, device: DeviceInfo): PairingResult = throw NotImplementedError()
    }

    /**
     * A real vault whose reconcile-time clear fails — what an auto-lock landing inside the connect does
     * (`clearRecords` requires an unlocked vault), leaving the durable marker up with nothing cleared.
     * [failures] bounds how many clears fail, so a test can also drive the recovery: the lock is gone by
     * the next connect and the reconcile finally runs.
     */
    private class ClearFailingVault(private val delegate: Vault, private val failures: Int = Int.MAX_VALUE) : Vault by delegate {
        private var attempts = 0

        override fun clearRecords(types: Set<RecordType>) {
            if (attempts++ < failures) error("vault is locked")
            delegate.clearRecords(types)
        }
    }

    /** Config store that refuses exactly the write retiring the reconcile marker (a full disk). */
    private class MarkerClearFailingStore(private val delegate: SyncConfigStore) : SyncConfigStore {
        @Volatile
        var refuseClear = true

        override fun load(): SyncConfig? = delegate.load()
        override fun save(config: SyncConfig) {
            if (refuseClear && !config.pendingReconcile && delegate.load()?.pendingReconcile == true) {
                error("config write failed")
            }
            delegate.save(config)
        }
        override fun clear() = delegate.clear()
    }

    /** A fresh unlocked account vault under [password], with its own random dataKey. */
    private fun freshVault(): Vault {
        val file = Files.createTempFile("skerry-reactivate", ".json").toString().toPath()
        FileSystem.SYSTEM.delete(file) // FileVault creates it
        return FileVault(file, crypto, deviceId = "devA", fileSystem = FileSystem.SYSTEM, now = { "2026-07-22T00:00:00Z" })
            .also { it.create(password.toCharArray()) }
    }

    /** This vault's own dataKey wrapped under the account (= vault) password, so the connect adopts nothing. */
    private fun ownWrap(vault: Vault): ByteArray {
        val dk = vault.exportDataKey()!!
        return crypto.wrapDataKey(syncAccountKey(crypto, password, account), dk).also { dk.zeroize() }
    }

    @Test
    fun `reactivated device drops its stale record, does not re-push it, and clears the pending marker`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        // The device still holds r1 LIVE — it was revoked before it could pull the tombstone.
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val client = ReactivatingClient(ownWrap(vault), reactivated = true)
        val config = InMemorySyncConfigStore()
        val sut = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault, configStore = config)
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(sut.status.value is SyncStatus.Online, "reactivation connect should come Online")
            assertFalse(vault.records().any { it.id == "r1" }, "a reactivated device must discard its pre-revocation records")
            assertFalse(client.pushed.any { it.id == "r1" }, "a reactivated device must not re-push a purged record")
            // The durable marker is cleared once the reconcile's first sync succeeded.
            assertEquals(false, config.load()?.pendingReconcile, "a completed reconcile clears the pending marker")
        } finally {
            sut.close()
        }
    }

    @Test
    fun `a pending reconcile from an interrupted run is redone even when the login is not a reactivation`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        // A previous reactivation was interrupted after the server cleared revocation but before the vault
        // was rebuilt: the durable marker survived. This login is NOT a reactivation (server already sees
        // the device as live), so only the marker can drive the reconcile.
        val config = InMemorySyncConfigStore()
        config.save(SyncConfig(serverUrl, account, deviceId = "devA", pendingReconcile = true))
        val client = ReactivatingClient(ownWrap(vault), reactivated = false)
        val sut = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault, configStore = config)
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(sut.status.value is SyncStatus.Online, "reconnect should come Online")
            assertFalse(vault.records().any { it.id == "r1" }, "a pending reconcile must still rebuild the vault")
            assertFalse(client.pushed.any { it.id == "r1" }, "a pending reconcile must not let the stale record push")
            assertEquals(false, config.load()?.pendingReconcile, "the redone reconcile clears the marker")
        } finally {
            sut.close()
        }
    }

    /**
     * [SyncStatus.Online] is what the UI and every other observer react to, so it must not arrive while the
     * reconcile marker is still up: an observer would read a config the reconcile has already left behind.
     * The sample is taken by an unconfined collector, i.e. inside the emission itself, so it sees exactly
     * what the coordinator published rather than what it got around to writing afterwards.
     */
    @Test
    fun `the reconcile marker is already down when the connect publishes Online`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val config = InMemorySyncConfigStore()
        val client = ReactivatingClient(ownWrap(vault), reactivated = true)
        val sut = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault, configStore = config)
        val markerAtOnline = CompletableDeferred<Boolean?>()
        val observer = launch(Dispatchers.Unconfined) {
            sut.status.collect { if (it is SyncStatus.Online) markerAtOnline.complete(config.load()?.pendingReconcile) }
        }
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            assertEquals(
                false,
                awaitSync("the reactivation connect to publish Online") { markerAtOnline.await() },
                "Online must not be published while the reconcile marker is still up",
            )
        } finally {
            observer.cancel()
            sut.close()
        }
    }

    /**
     * The reconcile is finished by whichever cycle first succeeds, not only by the one the connect
     * starts: a first cycle that fails leaves the vault cleared and the cursor at 0, so the next sync is
     * still the full re-pull the marker is waiting for. Clearing it only in the connect's own cycle would
     * leave the marker up for good and redo the reconcile on every later connect.
     */
    @Test
    fun `a reconcile whose first cycle failed is completed by the next sync that succeeds`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val config = InMemorySyncConfigStore()
        val client = ReactivatingClient(ownWrap(vault), reactivated = true, failFirstPull = true)
        val sut = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault, configStore = config)
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the reconcile's first cycle to fail") { it is SyncStatus.Failed }
            assertEquals(true, config.load()?.pendingReconcile, "a failed cycle must leave the marker up")

            sut.syncNow()
            sut.status.awaitStatus("the status to come Online") { it is SyncStatus.Online }
            assertEquals(false, config.load()?.pendingReconcile, "the cycle that succeeded completes the reconcile")
        } finally {
            sut.close()
        }
    }

    /**
     * The marker is persisted BEFORE the vault is cleared (a crash in between must not lose the signal),
     * so it can be up on a device whose reconcile never actually ran — the clear threw and the session
     * published a moment earlier is still live. That session must not sync: its vault still holds the
     * pre-revocation records the server purged, and a cycle would push them straight back and report
     * Online while doing it (issue #142). The cycle is refused and the status parks on the link state;
     * the rebuild is left to the next connect/restore, which redoes the reconcile.
     */
    @Test
    fun `a session whose reconcile never cleared the vault refuses to sync`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val config = InMemorySyncConfigStore()
        val client = ReactivatingClient(ownWrap(vault), reactivated = true)
        val sut = SyncCoordinator(
            clientFactory = { client },
            crypto = crypto,
            vault = ClearFailingVault(vault),
            configStore = config,
        )
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to fail on the clear") { it is SyncStatus.Failed }
            assertEquals(true, config.load()?.pendingReconcile, "a reconcile that could not clear the vault keeps the marker")

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
            assertEquals(true, config.load()?.pendingReconcile, "only a reconcile that actually ran may retire the marker")
            assertTrue(vault.records().any { it.id == "r1" }, "the stale record is still there — the reconcile never ran")
        } finally {
            sut.close()
        }
    }

    /**
     * The refusal is a stop, not a dead end: the marker is still on disk, so the next connect on the SAME
     * coordinator redoes the reconcile — this time over a vault that lets the clear through — and the
     * device comes back Online with its records rebuilt from the server. Recovery through a fresh
     * coordinator (an app restart) is a different path and is covered by the pending-marker test above.
     */
    @Test
    fun `a reconnect finishes the reconcile the refused cycle was waiting for`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val config = InMemorySyncConfigStore()
        val client = ReactivatingClient(ownWrap(vault), reactivated = true)
        val sut = SyncCoordinator(
            clientFactory = { client },
            crypto = crypto,
            vault = ClearFailingVault(vault, failures = 1),
            configStore = config,
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
            assertEquals(false, config.load()?.pendingReconcile, "the completed reconcile retires the marker")
        } finally {
            sut.close()
        }
    }

    /**
     * A password rotation re-activates the session without reconciling, and the config it saves still
     * carries the marker. When the reconcile it belongs to already ran here (records dropped, only the
     * marker write refused), that re-activation must not disarm the coordinator: doing so would make
     * every later cycle refuse — including the one that would finally retire the marker — and the device
     * would sit blocked until a reconnect for a rebuild that already happened.
     */
    @Test
    fun `a password rotation over a reconcile that already ran keeps syncing`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val config = MarkerClearFailingStore(InMemorySyncConfigStore())
        val client = ReactivatingClient(ownWrap(vault), reactivated = true)
        val sut = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault, configStore = config)
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(sut.status.value is SyncStatus.Online, "the reconcile itself succeeded — only its marker write was refused")
            assertEquals(true, config.load()?.pendingReconcile, "the refused write leaves the marker up")

            // changeAccountPassword awaits the activation it triggers, so the cycle has already run here.
            assertEquals(
                AccountPasswordChange.Success,
                sut.changeAccountPassword(password.toCharArray(), "vault-B".toCharArray()),
            )
            assertTrue(
                sut.status.value is SyncStatus.Online,
                "a re-activation carrying a marker whose reconcile already ran must keep syncing",
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
     * the refusal is keyed on the marker, not on who published the session.
     */
    @Test
    fun `a password rotation cannot sync a vault whose reconcile never ran`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val config = InMemorySyncConfigStore()
        val client = ReactivatingClient(ownWrap(vault), reactivated = true)
        val sut = SyncCoordinator(
            clientFactory = { client },
            crypto = crypto,
            vault = ClearFailingVault(vault),
            configStore = config,
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

    /**
     * Retiring the marker is a config write, and a config write can fail. It must not turn a sync that
     * actually succeeded into a reported failure — the marker simply stays up, and the next cycle retires
     * it, the same fallback an interrupted reconcile relies on.
     */
    @Test
    fun `a refused marker write keeps the sync green and is retried by the next cycle`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val config = MarkerClearFailingStore(InMemorySyncConfigStore())
        val client = ReactivatingClient(ownWrap(vault), reactivated = true)
        val sut = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault, configStore = config)
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(sut.status.value is SyncStatus.Online, "a refused marker write must not fail a sync that succeeded")
            assertEquals(true, config.load()?.pendingReconcile, "a refused write leaves the marker up")

            config.refuseClear = false
            sut.syncNow()
            awaitSync("the retried clear to land") { while (config.load()?.pendingReconcile != false) delay(20) }
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
