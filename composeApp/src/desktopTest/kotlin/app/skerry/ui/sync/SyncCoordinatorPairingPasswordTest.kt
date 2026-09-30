package app.skerry.ui.sync

import app.skerry.shared.sync.AccountSummary
import app.skerry.shared.sync.DeviceInfo
import app.skerry.shared.sync.PairingResult
import app.skerry.shared.sync.PairingTicket
import app.skerry.shared.sync.RecordPage
import app.skerry.shared.sync.RemoteDevice
import app.skerry.shared.sync.RemoteRecord
import app.skerry.shared.sync.SyncClient
import app.skerry.shared.sync.SyncOutcome
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.sync.SyncSignal
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Quick pairing hands the account key to whoever scans the code. On a trusted device (issue #398) the
 * vault opens without anyone's password, so the code is released only for the master password — the
 * same bar as exporting a key. Elsewhere pairing stays as it was.
 */
class SyncCoordinatorPairingPasswordTest {

    private val crypto = IonspinVaultCrypto()
    private val password = "vault-A"

    private class PairingClient : SyncClient {
        var envelopes = 0
            private set

        override suspend fun register(accountId: String, authKey: ByteArray, wrappedDataKey: ByteArray, device: DeviceInfo) =
            SyncSession(accountId, accessToken = "access", refreshToken = "refresh")

        override suspend fun startPairing(session: SyncSession, encryptedDataKey: ByteArray): PairingTicket {
            envelopes++
            return PairingTicket("ABCD-1234", expiresAt = Long.MAX_VALUE)
        }

        override fun changes(session: SyncSession): Flow<SyncSignal> = flow { awaitCancellation() }
        override suspend fun ping(): Boolean = true
        override suspend fun close() = Unit
        override suspend fun login(accountId: String, authKey: ByteArray, device: DeviceInfo): SyncSession = nope()
        override suspend fun changePassword(accountId: String, currentAuthKey: ByteArray, newAuthKey: ByteArray, newWrappedDataKey: ByteArray, device: DeviceInfo): SyncSession = nope()
        override suspend fun fetchWrappedDataKey(session: SyncSession): ByteArray = nope()
        override suspend fun pull(session: SyncSession, since: Long): RecordPage = nope()
        override suspend fun push(session: SyncSession, records: List<RemoteRecord>): RecordPage = nope()
        override suspend fun listDevices(session: SyncSession): List<RemoteDevice> = nope()
        override suspend fun accountSummary(session: SyncSession): AccountSummary = nope()
        override suspend fun revokeDevice(session: SyncSession, deviceId: String): Boolean = nope()
        override suspend fun refresh(session: SyncSession): SyncSession = nope()
        override suspend fun claimPairing(code: String, device: DeviceInfo): PairingResult = nope()
        private fun nope(): Nothing = throw NotImplementedError("pairing does not call this")
    }

    @Test
    fun `a trusted device does not release the code without the master password`() = online(trusted = true) { sut, client ->
        assertNull(sut.startPairing())
        assertEquals(0, client.envelopes, "the key is not sealed for anyone")
    }

    @Test
    fun `a trusted device does not release the code for a wrong password`() = online(trusted = true) { sut, client ->
        assertEquals(PairingStart.WrongPassword, sut.startPairing("nope".toCharArray()))
        assertEquals(0, client.envelopes)
    }

    @Test
    fun `a trusted device releases the code for the master password`() = online(trusted = true) { sut, client ->
        assertIs<PairingStart.Offered>(sut.startPairing(password.toCharArray()))
        assertEquals(1, client.envelopes)
    }

    @Test
    fun `the typed password does not outlive the check`() = online(trusted = true) { sut, _ ->
        val right = password.toCharArray()
        val wrong = "nope".toCharArray()
        sut.startPairing(wrong)
        sut.startPairing(right)
        assertTrue(wrong.all { it == '\u0000' } && right.all { it == '\u0000' })
    }

    @Test
    fun `without the trust pairing asks for nothing`() = online(trusted = false) { sut, client ->
        assertNotNull(sut.startPairing())
        assertEquals(1, client.envelopes)
    }

    private fun online(trusted: Boolean, block: suspend (SyncCoordinator, PairingClient) -> Unit) = runBlocking {
        initializeVaultCrypto()
        val client = PairingClient()
        val sut = SyncCoordinator(
            clientFactory = { client },
            crypto = crypto,
            vault = localVault(),
            engineFactory = { _ -> SyncRunner { _ -> SyncOutcome(pulled = 0, pushed = 0, cursor = 0L) } },
            pairingNeedsPassword = { trusted },
        )
        try {
            sut.connect("https://sync.test", "maya", password.toCharArray())
            sut.status.awaitStatus("the status to come Online") { it is SyncStatus.Online }
            block(sut, client)
        } finally {
            sut.close()
        }
    }

    private fun localVault(): Vault {
        val file = Files.createTempFile("skerry-pairing", ".json").toString().toPath()
        FileSystem.SYSTEM.delete(file)
        return FileVault(file, crypto, deviceId = "dev-local", fileSystem = FileSystem.SYSTEM, now = { "2026-09-30T00:00:00Z" })
            .also { it.create(password.toCharArray()) }
    }
}
