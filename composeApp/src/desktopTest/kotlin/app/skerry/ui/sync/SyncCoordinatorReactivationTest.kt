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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
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
    ) : SyncClient {
        val pushed = mutableListOf<RemoteRecord>()

        override suspend fun register(accountId: String, authKey: ByteArray, wrappedDataKey: ByteArray, device: DeviceInfo): SyncSession =
            throw SyncException(SyncException.Kind.CONFLICT, "account exists")
        override suspend fun login(accountId: String, authKey: ByteArray, device: DeviceInfo): SyncSession =
            SyncSession(accountId, accessToken = "access", refreshToken = "refresh", reactivated = reactivated)
        override suspend fun fetchWrappedDataKey(session: SyncSession): ByteArray = ownWrappedKey.copyOf()
        override suspend fun pull(session: SyncSession, since: Long): RecordPage = RecordPage(emptyList(), 1)
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
        override suspend fun startPairing(session: SyncSession, encryptedDataKey: ByteArray): PairingTicket = throw NotImplementedError()
        override suspend fun claimPairing(code: String, device: DeviceInfo): PairingResult = throw NotImplementedError()
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
        val mk = crypto.deriveMasterKey(password.toCharArray(), crypto.deriveSyncSalt(account))
        val dk = vault.exportDataKey()!!
        val wrapped = crypto.wrapDataKey(mk, dk)
        mk.zeroize(); dk.zeroize()
        return wrapped
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
            withTimeout(30_000) { sut.status.first { it is SyncStatus.Online || it is SyncStatus.Failed } }
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
            withTimeout(30_000) { sut.status.first { it is SyncStatus.Online || it is SyncStatus.Failed } }
            assertTrue(sut.status.value is SyncStatus.Online, "reconnect should come Online")
            assertFalse(vault.records().any { it.id == "r1" }, "a pending reconcile must still rebuild the vault")
            assertFalse(client.pushed.any { it.id == "r1" }, "a pending reconcile must not let the stale record push")
            assertEquals(false, config.load()?.pendingReconcile, "the redone reconcile clears the marker")
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
            withTimeout(30_000) { sut.status.first { it is SyncStatus.Online || it is SyncStatus.Failed } }
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
