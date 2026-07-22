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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PR #51, the second half: excluding revoked devices from the tombstone watermark lets the account purge a
 * tombstone while a revoked device still holds the record LIVE (it never pulled the tombstone). On
 * reactivation that device's full push would re-upload the record (the server has no row for it → resurrected)
 * and it would spread back to every peer. The coordinator closes this window: when the login reports the
 * device was reactivated, it rebuilds the vault from the server snapshot before the first push.
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
     * reports the device was reactivated, the served wrap is this vault's OWN key (so the connect adopts
     * nothing — isolating the reactivation path), and the server no longer holds `r1`, so `pull` returns
     * nothing. `push` records exactly what the client sent.
     */
    private inner class ReactivatingClient(private val ownWrappedKey: ByteArray) : SyncClient {
        val pushed = mutableListOf<RemoteRecord>()

        override suspend fun register(accountId: String, authKey: ByteArray, wrappedDataKey: ByteArray, device: DeviceInfo): SyncSession =
            throw SyncException(SyncException.Kind.CONFLICT, "account exists")
        override suspend fun login(accountId: String, authKey: ByteArray, device: DeviceInfo): SyncSession =
            SyncSession(accountId, accessToken = "access", refreshToken = "refresh", reactivated = true)
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

    @Test
    fun `reactivated device drops its stale record and does not re-push a purged one`() = runBlocking {
        initializeVaultCrypto()
        val file = Files.createTempFile("skerry-reactivate", ".json").toString().toPath()
        FileSystem.SYSTEM.delete(file) // FileVault creates it
        val vault: Vault = FileVault(file, crypto, deviceId = "devA", fileSystem = FileSystem.SYSTEM, now = { "2026-07-22T00:00:00Z" })
            .also { it.create(password.toCharArray()) }
        // The device still holds r1 LIVE — it was revoked before it could pull the tombstone.
        vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

        // The account key IS this vault's own key, wrapped under the account (= vault) password: the connect
        // adopts nothing, so only the reactivation reconcile can change the vault.
        val mk = crypto.deriveMasterKey(password.toCharArray(), crypto.deriveSyncSalt(account))
        val dk = vault.exportDataKey()!!
        val ownWrapped = crypto.wrapDataKey(mk, dk)
        mk.zeroize(); dk.zeroize()

        val client = ReactivatingClient(ownWrapped)
        val sut = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault)
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            withTimeout(30_000) { sut.status.first { it is SyncStatus.Online || it is SyncStatus.Failed } }
            assertTrue(sut.status.value is SyncStatus.Online, "reactivation connect should come Online")
            // The stale live record is gone locally…
            assertFalse(vault.records().any { it.id == "r1" }, "a reactivated device must discard its pre-revocation records")
            // …and was never pushed back to the server, so it can't resurrect and spread to peers.
            assertFalse(client.pushed.any { it.id == "r1" }, "a reactivated device must not re-push a purged record")
        } finally {
            sut.close()
        }
    }
}
