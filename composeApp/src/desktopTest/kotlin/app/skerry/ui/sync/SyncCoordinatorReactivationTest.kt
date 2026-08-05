package app.skerry.ui.sync

import app.skerry.shared.sync.AccountSummary
import app.skerry.shared.sync.DeviceInfo
import app.skerry.shared.sync.InMemorySyncStateStore
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
import app.skerry.shared.vault.UnlockResult
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
import java.util.concurrent.atomic.AtomicBoolean
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
        reactivated: Boolean = true,
        /** Makes the reconcile's own first cycle fail, leaving the rebuild to a later sync. */
        private val failFirstPull: Boolean = false,
        /** How many key fetches serve an unopenable wrap — a connect that fails AFTER the login. */
        private val corruptWraps: Int = 0,
        /** The first key fetch dies on the network — a connect that THROWS after the login. */
        private val throwOnFirstFetch: Boolean = false,
    ) : SyncClient {
        val pushed = mutableListOf<RemoteRecord>()

        /** What each pull asked for — `0` is the full re-pull a reconcile's cursor reset forces. */
        val pulledSince = mutableListOf<Long>()
        private val pulls = AtomicInteger(0)

        // The reactivation is reported exactly once, like the server does it: the verify that reports it
        // is the one that clears the revocation, so every later login of this device is an ordinary one.
        private val revoked = AtomicBoolean(reactivated)
        private val fetches = AtomicInteger(0)

        override suspend fun register(accountId: String, authKey: ByteArray, wrappedDataKey: ByteArray, device: DeviceInfo): SyncSession =
            throw SyncException(SyncException.Kind.CONFLICT, "account exists")
        override suspend fun login(accountId: String, authKey: ByteArray, device: DeviceInfo): SyncSession =
            SyncSession(accountId, accessToken = "access", refreshToken = "refresh", reactivated = revoked.getAndSet(false))
        override suspend fun fetchWrappedDataKey(session: SyncSession): ByteArray {
            val n = fetches.getAndIncrement()
            if (throwOnFirstFetch && n == 0) throw SyncException(SyncException.Kind.NETWORK, "unreachable")
            return if (n < corruptWraps) ByteArray(ownWrappedKey.size) else ownWrappedKey.copyOf()
        }
        override suspend fun pull(session: SyncSession, since: Long): RecordPage {
            pulledSince += since
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

        // Unreachable on purpose: a health ping that comes up drives the coordinator's own self-heal
        // ([SyncCoordinator]'s init — no session, so `restoreSession`), which would race the connects these
        // tests drive and re-save the config from under the assertions. Reachability is covered elsewhere.
        override suspend fun ping(): Boolean = false
        override suspend fun close() {}
        override suspend fun listDevices(session: SyncSession): List<RemoteDevice> = emptyList()
        override suspend fun accountSummary(session: SyncSession): AccountSummary = error("unused")
        override suspend fun revokeDevice(session: SyncSession, deviceId: String): Boolean = false
        // The silent restore of a keep-connected device: a token exchange, with no `reactivated` in it.
        override suspend fun refresh(session: SyncSession): SyncSession =
            SyncSession(session.accountId, accessToken = "access2", refreshToken = "refresh2")
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
        private val attempts = AtomicInteger(0)

        /** How many clears were tried — the observable "the reconcile ran again" for a retry that fails too. */
        val clearAttempts: Int get() = attempts.get()

        override fun clearRecords(types: Set<RecordType>) {
            if (attempts.getAndIncrement() < failures) error("vault is locked")
            delegate.clearRecords(types)
        }
    }

    /** Config store that refuses exactly the write RAISING the reconcile marker (a full disk). */
    private class MarkerRaiseFailingStore(private val delegate: SyncConfigStore) : SyncConfigStore {
        /** Cleared once the test wants the disk to have room again. */
        @Volatile
        var refuse = true

        override fun load(): SyncConfig? = delegate.load()
        override fun save(config: SyncConfig) {
            if (refuse && config.pendingReconcile && delegate.load()?.pendingReconcile != true) error("config write failed")
            delegate.save(config)
        }
        override fun clear() = delegate.clear()
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
        // The first connect's key fetch serves an unopenable wrap; the second one is served the real key.
        val client = ReactivatingClient(ownWrap(vault), reactivated = true, corruptWraps = 1)
        val sut = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault, configStore = config)
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to fail on the unopenable wrap") { it is SyncStatus.Failed }
            assertEquals(
                SyncFailureReason.AccountKeyNotAdopted,
                (sut.status.value as? SyncStatus.Failed)?.reason,
                "was ${sut.status.value}",
            )
            assertEquals(null, config.load(), "a connect that never reached a session must not save a link")

            // The repaired server state, and a login that reports nothing: the connect that failed is the
            // only thing that still knows a rebuild is owed.
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the repaired connect to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(sut.status.value is SyncStatus.Online, "was ${sut.status.value}")
            assertFalse(vault.records().any { it.id == "r1" }, "the deferred reconcile must still drop the pre-revocation record")
            assertFalse(client.pushed.any { it.id == "r1" }, "the purged record must never be pushed back")
            assertEquals(false, config.load()?.pendingReconcile, "the completed reconcile retires the marker")
        } finally {
            sut.close()
        }
    }

    /**
     * The device that gets reactivated normally still HAS its link — that is the production shape of the
     * write, and the one the fresh-store tests never take. Only the reconcile marker may change: the
     * deviceId identifies this device to the account, and the keep-connected token is what lets the next
     * launch restore without a password. Rebuilding the config from the connect's own parameters instead
     * of marking the saved one would drop both and no other test would notice.
     */
    @Test
    fun `marking a reactivation preserves the link it is written onto`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()

        val linked = SyncConfig(serverUrl, account, deviceId = "devA", keepConnected = true, sealedRefreshToken = "sealed")
        val config = InMemorySyncConfigStore().also { it.save(linked) }
        val client = ReactivatingClient(ownWrap(vault), reactivated = true, corruptWraps = 1)
        val sut = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault, configStore = config)
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to fail on the unopenable wrap") { it is SyncStatus.Failed }
            assertEquals(linked.copy(pendingReconcile = true), config.load(), "only the marker may change")
        } finally {
            sut.close()
        }
    }

    /**
     * The connect need not fail with a status of its own to lose the signal: a network error while
     * fetching the account key throws straight past every early return into the catch-all. The marker
     * has to be down on disk before that fetch, not after it.
     */
    @Test
    fun `a connect that throws after the login keeps the reactivation`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val config = InMemorySyncConfigStore().also { it.save(SyncConfig(serverUrl, account, deviceId = "devA")) }
        val client = ReactivatingClient(ownWrap(vault), reactivated = true, throwOnFirstFetch = true)
        val sut = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault, configStore = config)
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to fail on the key fetch") { it is SyncStatus.Failed }
            assertEquals(true, config.load()?.pendingReconcile, "a throw after the login must not take the reactivation with it")
        } finally {
            sut.close()
        }
    }

    /**
     * A keep-connected device recovers without a password: the next launch refreshes its saved token
     * instead of logging in, and `refresh` carries no `reactivated` signal at all. The marker raised by
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
        val client = ReactivatingClient(ownWrap(vault), reactivated = true, corruptWraps = 1)
        val failed = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault, configStore = config)
        try {
            failed.connect(serverUrl, account, password.toCharArray(), keepConnected = true)
            failed.status.awaitStatus("the connect to fail on the unopenable wrap") { it is SyncStatus.Failed }
        } finally {
            failed.close()
        }
        assertEquals(true, config.load()?.pendingReconcile, "the failed connect left the rebuild owed")

        // A new process: no coordinator state survives, only the config file and the vault.
        val restored = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault, configStore = config)
        try {
            restored.restoreSession()
            restored.status.awaitStatus("the silent restore to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(restored.status.value is SyncStatus.Online, "was ${restored.status.value}")
            assertFalse(vault.records().any { it.id == "r1" }, "the restore must run the reconcile the connect never got to")
            assertFalse(client.pushed.any { it.id == "r1" }, "the purged record must never be pushed back")
            assertEquals(false, config.load()?.pendingReconcile, "the completed reconcile retires the marker")
        } finally {
            restored.close()
        }
    }

    /**
     * The marker is written onto the saved link, and a connect that hasn't succeeded yet has not earned
     * one: a failed connect to another account must leave the link — its deviceId and its keep-connected
     * token — exactly as it was, rather than trade a device that is still fine for a reconcile intent that
     * cannot even be read for that account. The signal is carried live instead (`reactivated`), and the
     * connect that succeeds writes its own link with the marker.
     */
    @Test
    fun `a failed connect does not overwrite the link to another account`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()

        val linked = SyncConfig(serverUrl, "other-account", deviceId = "devOther", keepConnected = true, sealedRefreshToken = "sealed")
        val config = InMemorySyncConfigStore().also { it.save(linked) }
        val client = ReactivatingClient(ownWrap(vault), reactivated = true, corruptWraps = 1)
        val sut = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault, configStore = config)
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to fail on the unopenable wrap") { it is SyncStatus.Failed }
            assertEquals(linked, config.load(), "a failed connect must leave the saved link untouched")
        } finally {
            sut.close()
        }
    }

    /**
     * Disconnect erases the link, and with it the durable marker — but it rebuilds nothing: the records
     * the reconcile was supposed to drop are still in the vault. A debt that was never actually paid must
     * therefore survive it, or the reconnect after a disconnect is the ordinary incremental one and pushes
     * the purged records straight back. (Here the reconcile's clear failed, which is exactly the state in
     * which a user reaches for Disconnect.)
     */
    @Test
    fun `a disconnect does not pay a rebuild that never ran`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val config = InMemorySyncConfigStore().also { it.save(SyncConfig(serverUrl, account, deviceId = "devA")) }
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

            sut.disconnect()
            sut.status.awaitStatus("the link to be erased") { it is SyncStatus.Disabled }
            assertEquals(null, config.load(), "disconnect erases the link — and the durable marker with it")

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

        // Linked to the home instance, so the failed connect below marks THAT link — the marker and the
        // in-memory debt both have to stay charged to it.
        val config = InMemorySyncConfigStore()
            .also { it.save(SyncConfig("https://home.test", account, deviceId = "devA")) }
        val client = ReactivatingClient(ownWrap(vault), reactivated = true, corruptWraps = 1)
        val sut = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault, configStore = config)
        try {
            // The home instance: this device was revoked there, and the connect fails after the login.
            sut.connect("https://home.test", account, password.toCharArray())
            sut.status.awaitStatus("the connect to fail on the unopenable wrap") { it is SyncStatus.Failed }

            // The work instance, same account id: an ordinary connect that owes nothing.
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the second connect to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(sut.status.value is SyncStatus.Online, "was ${sut.status.value}")
            assertTrue(vault.records().any { it.id == "r1" }, "another server's reactivation must not clear this vault")
        } finally {
            sut.close()
        }
    }

    /**
     * The reactivation is reported, the marker write is refused (a full disk), and the connect fails
     * loudly — but the device is keep-connected, so what happens next is a silent restore that never
     * logs in again. It has to see the rebuild is owed from the only place it was recorded: this
     * process's memory. Otherwise the session comes Online and pushes the purged records back.
     */
    @Test
    fun `a restore honors a rebuild whose marker could not be written`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val dataKey = vault.exportDataKey()!!
        val sealed = SealedTokenCodec(crypto).seal(dataKey, "refresh").also { dataKey.zeroize() }
        val config = MarkerRaiseFailingStore(
            InMemorySyncConfigStore().also {
                it.save(SyncConfig(serverUrl, account, deviceId = "devA", keepConnected = true, sealedRefreshToken = sealed))
            },
        )
        val client = ReactivatingClient(ownWrap(vault), reactivated = true)
        val sut = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault, configStore = config)
        try {
            sut.connect(serverUrl, account, password.toCharArray(), keepConnected = true)
            sut.status.awaitStatus("the connect to fail on the refused write") { it is SyncStatus.Failed }
            assertEquals(false, config.load()?.pendingReconcile, "the marker never made it to disk")

            config.refuse = false // the disk has room again by the time the restore runs
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
     * Standing down from the write is not the same as forgetting: the server said this device was
     * reactivated and will never say it again, so the retry that finally connects must still rebuild —
     * even though the intent had nowhere on disk to wait (the saved link is another account's, and a
     * refused write leaves the same nothing behind).
     */
    @Test
    fun `a reactivation with nowhere to be saved still reconciles on the next connect`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val config = InMemorySyncConfigStore()
            .also { it.save(SyncConfig(serverUrl, "other-account", deviceId = "devOther")) }
        val client = ReactivatingClient(ownWrap(vault), reactivated = true, corruptWraps = 1)
        val sut = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault, configStore = config)
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to fail on the unopenable wrap") { it is SyncStatus.Failed }

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

        val config = InMemorySyncConfigStore()
        val client = ReactivatingClient(ownWrap(vault), reactivated = true)
        // A cursor from before the revocation: the reconcile has to reset it, or the re-pull that rebuilds
        // the vault would ask for changes since the tip and get nothing back.
        val state = InMemorySyncStateStore().also { it.setCursor(account, 42) }
        val sut = SyncCoordinator(
            clientFactory = { client },
            crypto = crypto,
            vault = ClearFailingVault(vault, failures = 1),
            configStore = config,
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
            assertEquals(false, config.load()?.pendingReconcile, "the completed reconcile retires the marker")
            // The cycle the connect would have run never happened (the clear threw first), so the first
            // pull of the whole test is the one the redo armed.
            assertEquals(0L, client.pulledSince.firstOrNull(), "the rebuild must be a full re-pull, not one from the stale cursor")
        } finally {
            sut.close()
        }
    }

    /**
     * The redone reconcile can fail too — the vault re-locked between the unlock and the clear. That must
     * stay the same stop as before (the marker up, the cycle refused), not turn the unlock into a new
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

        val config = InMemorySyncConfigStore()
        val client = ReactivatingClient(ownWrap(vault), reactivated = true)
        val clearFailing = ClearFailingVault(vault)
        val sut = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = clearFailing, configStore = config)
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
            assertEquals(true, config.load()?.pendingReconcile, "a reconcile that failed again keeps the marker")
            assertTrue(vault.records().any { it.id == "r1" }, "nothing was cleared — the record is still there")
            assertFalse(client.pushed.any { it.id == "r1" }, "and it must not have been pushed")
        } finally {
            sut.close()
        }
    }

    /**
     * The other branch of the same guard, and the one with teeth: an unlock on a session that owes nothing
     * must not reconcile. Without the [SyncCoordinator] check, every ordinary unlock would raise a fresh
     * marker and wipe every host and snippet in the vault — the mirror image of the bug this path fixes.
     */
    @Test
    fun `an unlock with no reconcile owed leaves the vault alone`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val config = InMemorySyncConfigStore()
        // Not a reactivation and no pending marker: an ordinary connected device.
        val client = ReactivatingClient(ownWrap(vault), reactivated = false)
        val sut = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault, configStore = config)
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
            assertEquals(false, config.load()?.pendingReconcile, "and must not raise a reconcile marker of its own")
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
     * The refusal guard reads the durable marker, so a debt that never reached disk must be visible to it
     * too. A password rotation re-publishes the session without reconciling: with nothing to see, its first
     * cycle would push the pre-revocation records the failed connect never got to drop.
     */
    @Test
    fun `a rotation cannot sync over a rebuild whose marker could not be written`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        val config = MarkerRaiseFailingStore(
            InMemorySyncConfigStore().also { it.save(SyncConfig(serverUrl, account, deviceId = "devA")) },
        )
        val client = ReactivatingClient(ownWrap(vault), reactivated = true)
        val sut = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault, configStore = config)
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to fail on the refused write") { it is SyncStatus.Failed }
            assertEquals(false, config.load()?.pendingReconcile, "the marker never made it to disk")

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
