package app.skerry.ui.sync

import app.skerry.shared.sync.DeviceInfo
import app.skerry.shared.sync.PairingResult
import app.skerry.shared.sync.PairingTicket
import app.skerry.shared.sync.RecordPage
import app.skerry.shared.sync.RemoteDevice
import app.skerry.shared.sync.RemoteRecord
import app.skerry.shared.sync.SyncClient
import app.skerry.shared.sync.SyncException
import app.skerry.shared.sync.SyncOutcome
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.sync.SyncSignal
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A throttled or broken server must be named as such. Both are reachable states of a self-hosted
 * instance — the rate limiter guards register/SRP/pairing, and a restart behind a reverse proxy
 * answers 5xx — but both used to collapse into PROTOCOL, i.e. "protocol error", which reads like a
 * client bug and tells the user nothing about waiting or checking the server.
 */
class SyncCoordinatorServerFailureTest {

    private val crypto = IonspinVaultCrypto()
    private val serverUrl = "https://sync.test"
    private val account = "maya"
    private val password = "vault-A"

    /** Server that rejects registration with [kind] — the first call any connect makes. */
    private class RejectingClient(private val kind: SyncException.Kind) : SyncClient {
        override suspend fun ping(): Boolean = true

        override suspend fun register(accountId: String, authKey: ByteArray, wrappedDataKey: ByteArray, device: DeviceInfo): SyncSession =
            throw SyncException(kind, "rejected")

        override fun changes(session: SyncSession): Flow<SyncSignal> = flow { awaitCancellation() }
        override suspend fun close() {}
        override suspend fun refresh(session: SyncSession): SyncSession = nope()
        override suspend fun login(accountId: String, authKey: ByteArray, device: DeviceInfo): SyncSession = nope()
        override suspend fun changePassword(accountId: String, currentAuthKey: ByteArray, newAuthKey: ByteArray, newWrappedDataKey: ByteArray, device: DeviceInfo): SyncSession = nope()
        override suspend fun fetchWrappedDataKey(session: SyncSession): ByteArray = nope()
        override suspend fun pull(session: SyncSession, since: Long): RecordPage = nope()
        override suspend fun push(session: SyncSession, records: List<RemoteRecord>): RecordPage = nope()
        override suspend fun listDevices(session: SyncSession): List<RemoteDevice> = nope()
        override suspend fun revokeDevice(session: SyncSession, deviceId: String): Boolean = nope()
        override suspend fun startPairing(session: SyncSession, encryptedDataKey: ByteArray): PairingTicket = nope()
        override suspend fun claimPairing(code: String, device: DeviceInfo): PairingResult = nope()
        private fun nope(): Nothing = throw NotImplementedError("connect fails before this is reached")
    }

    private fun localVault(): Vault {
        val file = Files.createTempFile("skerry-server-failure", ".json").toString().toPath()
        FileSystem.SYSTEM.delete(file) // FileVault creates it
        return FileVault(file, crypto, deviceId = "dev-local", fileSystem = FileSystem.SYSTEM, now = { "2026-07-25T00:00:00Z" })
            .also { it.create(password.toCharArray()) }
    }

    private fun reasonForRejectedConnect(kind: SyncException.Kind): SyncFailureReason =
        failedConnect(kind).reason

    private fun failedConnect(kind: SyncException.Kind): SyncStatus.Failed = runBlocking {
        initializeVaultCrypto()
        val sut = SyncCoordinator(
            clientFactory = { RejectingClient(kind) },
            crypto = crypto,
            vault = localVault(),
            engineFactory = { _ -> SyncRunner { _ -> SyncOutcome(pulled = 0, pushed = 0, cursor = 0L) } },
        )
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            withTimeout(30_000) { sut.status.first { it is SyncStatus.Failed } as SyncStatus.Failed }
        } finally {
            sut.close()
        }
    }

    @Test
    fun `a throttled server is reported as rate limiting, not as a protocol error`() {
        assertEquals(SyncFailureReason.TooManyRequests, reasonForRejectedConnect(SyncException.Kind.TOO_MANY_REQUESTS))
    }

    @Test
    fun `a broken server is reported as a server failure, not as a protocol error`() {
        assertEquals(SyncFailureReason.ServerError, reasonForRejectedConnect(SyncException.Kind.SERVER_ERROR))
    }

    @Test
    fun `an actual protocol error still reports as one`() {
        assertEquals(SyncFailureReason.Protocol, reasonForRejectedConnect(SyncException.Kind.PROTOCOL))
    }

    /**
     * A refusal the server chose (closed registration, a blocked account id) is neither the user's
     * fault nor a retryable failure: it is named, so the server's own explanation can be shown
     * instead of "protocol error".
     */
    @Test
    fun `a refused registration is reported as a rejection, with the server's own words`() {
        val failed = failedConnect(SyncException.Kind.FORBIDDEN)
        assertEquals(SyncFailureReason.Rejected, failed.reason)
        // Without the detail the user only learns "rejected" — never by whom or what to do about it.
        assertEquals("rejected", failed.detail)
    }
}
