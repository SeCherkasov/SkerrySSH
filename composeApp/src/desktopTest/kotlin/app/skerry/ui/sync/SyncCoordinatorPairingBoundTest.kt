package app.skerry.ui.sync

import app.skerry.shared.sync.AccountSummary
import app.skerry.shared.sync.DeviceInfo
import app.skerry.shared.sync.MAX_ACCOUNT_ID_CHARS
import app.skerry.shared.sync.PairingResult
import app.skerry.shared.sync.PairingTicket
import app.skerry.shared.sync.RecordPage
import app.skerry.shared.sync.RemoteDevice
import app.skerry.shared.sync.RemoteRecord
import app.skerry.shared.sync.SyncClient
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.sync.SyncSignal
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.VaultCrypto
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A device joining by pairing does not know the account id — it takes the one the server answers with, and
 * that id then goes into the saved config, into the account key derivation, and into a per-link store key
 * rewritten on every cycle. Nothing else in the claim response is a value this device did not already
 * have, so it is the one field a hostile or broken server chooses for it.
 *
 * It is refused rather than truncated: an account id is looked up whole, and half of one names nobody.
 */
class SyncCoordinatorPairingBoundTest {

    private val crypto = IonspinVaultCrypto()
    private val serverUrl = "https://work.test"
    private val password = "vault-A"

    /** Answers a claim with whatever account id and envelope the test asks for. */
    private class ClaimingClient(private val accountId: String, private val envelope: ByteArray) : SyncClient {
        override suspend fun claimPairing(code: String, device: DeviceInfo): PairingResult =
            PairingResult(accountId, envelope.copyOf(), SyncSession(accountId, "access", "refresh"))

        override suspend fun ping(): Boolean = false
        override suspend fun close() = Unit
        override fun changes(session: SyncSession): Flow<SyncSignal> = flow { awaitCancellation() }
        override suspend fun register(accountId: String, authKey: ByteArray, wrappedDataKey: ByteArray, device: DeviceInfo): SyncSession = nope()
        override suspend fun login(accountId: String, authKey: ByteArray, device: DeviceInfo): SyncSession = nope()
        override suspend fun changePassword(accountId: String, currentAuthKey: ByteArray, newAuthKey: ByteArray, newWrappedDataKey: ByteArray, device: DeviceInfo): SyncSession = nope()
        override suspend fun fetchWrappedDataKey(session: SyncSession): ByteArray = nope()
        override suspend fun pull(session: SyncSession, since: Long): RecordPage = nope()
        override suspend fun push(session: SyncSession, records: List<RemoteRecord>): RecordPage = nope()
        override suspend fun listDevices(session: SyncSession): List<RemoteDevice> = nope()
        override suspend fun accountSummary(session: SyncSession): AccountSummary = nope()
        override suspend fun revokeDevice(session: SyncSession, deviceId: String): Boolean = nope()
        override suspend fun refresh(session: SyncSession): SyncSession = nope()
        override suspend fun startPairing(session: SyncSession, encryptedDataKey: ByteArray): PairingTicket = nope()
        private fun nope(): Nothing = throw NotImplementedError("the claim is refused before this is reached")
    }

    /**
     * Counts the one call that comes right after the gate. The status alone cannot tell the two apart — an
     * unopenable envelope fails the same way, which is the point of reusing that reason — so what pins the
     * gate is that the claim never got as far as opening the envelope.
     */
    private class CountingCrypto(delegate: VaultCrypto) : VaultCrypto by delegate {
        var openCalls = 0
            private set

        override fun openTransferredDataKey(transferKey: ByteArray, envelope: ByteArray): DataKey? {
            openCalls++
            return null
        }
    }

    private fun payload() = PairingPayload(serverUrl, "code-1", ByteArray(32) { 7 }).encode()

    /**
     * The envelope is deliberately unopenable, so nothing past the gate runs. An id exactly AT the bound
     * would have to be carried through a whole successful claim to prove it is accepted, and no fake in
     * this suite serves one — the claim's happy path has never had a test. What is pinned here is the
     * refusal, which is the part this change adds.
     */
    private fun claimWith(accountId: String): Pair<SyncStatus, Int> = runBlocking {
        initializeVaultCrypto()
        val vault = newAccountVault(crypto, password)
        val config = InMemorySyncConfigStore()
        val spy = CountingCrypto(crypto)
        val sut = SyncCoordinator(
            clientFactory = { ClaimingClient(accountId, ByteArray(64)) },
            crypto = spy,
            vault = vault,
            configStore = config,
            debtStore = InMemoryReconcileDebtStore(),
        )
        try {
            sut.claimPairing(payload(), password.toCharArray())
            val status = sut.status.awaitStatus("the claim to settle") { it is SyncStatus.Failed || it is SyncStatus.Online }
            status to spy.openCalls
        } finally {
            sut.close()
        }
    }

    /**
     * Refused with the reason a spent code gets, not a generic protocol error: the claim has already burned
     * the one-time code, so the only way forward is a fresh one and the text has to say so.
     */
    @Test
    fun `an empty account id from the server is refused`() {
        assertRefused(claimWith(""))
    }

    @Test
    fun `an account id longer than the server's own bound is refused`() {
        assertRefused(claimWith("a".repeat(MAX_ACCOUNT_ID_CHARS + 1)))
    }

    private fun assertRefused(outcome: Pair<SyncStatus, Int>) {
        val (status, openCalls) = outcome
        assertEquals(SyncStatus.Failed(SyncFailureReason.PairingCodeInvalid), status)
        assertEquals(0, openCalls, "the id was carried past the gate and only the envelope stopped it")
    }
}
