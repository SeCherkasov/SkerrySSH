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
import app.skerry.shared.sync.WebAccessClient
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Setting, rotating and removing the web password from the app (Settings → Sync → Web access). It is
 * the only way in to the browser account zone, so every outcome the card renders has to be the
 * truth: a state it can't read is not "off", a change that didn't land is not a success, and an
 * access token that merely expired is not "you are logged out".
 */
class SyncCoordinatorWebAccessTest {

    private val crypto = IonspinVaultCrypto()
    private val serverUrl = "https://sync.test"
    private val account = "maya"
    private val password = "vault-A"

    /**
     * Network stub modelling one account's web password. [staleToken] is the access token that has
     * expired: a call carrying it is answered with 401 once, exactly as the server would after the
     * 15-minute TTL, and [refresh] hands out the token that works.
     */
    private class FakeWebAccessClient(
        private val staleToken: String? = null,
        private val failWith: SyncException? = null,
    ) : SyncClient, WebAccessClient {

        var stored: String? = null; private set
        var calls = 0; private set
        var refreshes = 0; private set

        /** Set after connecting to make the refresh attempt itself fail (a blip, not a dead session). */
        var refreshFailure: Exception? = null

        /** Set to park a call inside the client: completed once it is entered, then it never returns. */
        var entered: CompletableDeferred<Unit>? = null

        override suspend fun webAccessEnabled(session: SyncSession): Boolean {
            guard(session)
            return stored != null
        }

        override suspend fun setWebPassword(session: SyncSession, password: CharArray) {
            guard(session)
            park()
            stored = String(password)
        }

        override suspend fun clearWebPassword(session: SyncSession) {
            guard(session)
            stored = null
        }

        private fun guard(session: SyncSession) {
            calls++
            failWith?.let { throw it }
            if (session.accessToken == staleToken) throw SyncException(SyncException.Kind.UNAUTHORIZED, "token expired")
        }

        private suspend fun park() {
            val gate = entered ?: return
            gate.complete(Unit)
            awaitCancellation()
        }

        override suspend fun register(accountId: String, authKey: ByteArray, wrappedDataKey: ByteArray, device: DeviceInfo): SyncSession =
            SyncSession(accountId, accessToken = staleToken ?: "access", refreshToken = "refresh")

        override suspend fun refresh(session: SyncSession): SyncSession {
            refreshes++
            refreshFailure?.let { throw it }
            return SyncSession(session.accountId, accessToken = "access-fresh", refreshToken = "refresh2")
        }

        override fun changes(session: SyncSession): Flow<SyncSignal> = emptyFlow()
        override suspend fun ping(): Boolean = true
        override suspend fun close() {}
        override suspend fun login(accountId: String, authKey: ByteArray, device: DeviceInfo): SyncSession = nope()
        override suspend fun changePassword(
            accountId: String,
            currentAuthKey: ByteArray,
            newAuthKey: ByteArray,
            newWrappedDataKey: ByteArray,
            device: DeviceInfo,
        ): SyncSession = nope()
        override suspend fun fetchWrappedDataKey(session: SyncSession): ByteArray = nope()
        override suspend fun pull(session: SyncSession, since: Long): RecordPage = nope()
        override suspend fun push(session: SyncSession, records: List<RemoteRecord>): RecordPage = nope()
        override suspend fun listDevices(session: SyncSession): List<RemoteDevice> = nope()
        override suspend fun revokeDevice(session: SyncSession, deviceId: String): Boolean = nope()
        override suspend fun startPairing(session: SyncSession, encryptedDataKey: ByteArray): PairingTicket = nope()
        override suspend fun claimPairing(code: String, device: DeviceInfo): PairingResult = nope()
        private fun nope(): Nothing = throw NotImplementedError("the web-access flow should not call this")
    }

    private fun localVault(): Vault {
        val file = Files.createTempFile("skerry-webaccess", ".json").toString().toPath()
        FileSystem.SYSTEM.delete(file) // FileVault creates it
        return FileVault(file, crypto, deviceId = "dev-local", fileSystem = FileSystem.SYSTEM, now = { "2026-07-31T00:00:00Z" })
            .also { it.create(password.toCharArray()) }
    }

    private fun coordinator(vault: Vault, client: SyncClient): SyncCoordinator = SyncCoordinator(
        clientFactory = { client },
        crypto = crypto,
        vault = vault,
        deviceIdProvider = { "dev-local" },
        engineFactory = { _ -> SyncRunner { _ -> SyncOutcome(pulled = 0, pushed = 0, cursor = 0L) } },
    )

    /** A connected coordinator (register path — the fake has no account yet). */
    private suspend fun connected(client: SyncClient): SyncCoordinator {
        initializeVaultCrypto()
        val sut = coordinator(localVault(), client)
        sut.connect(serverUrl, account, password.toCharArray())
        withTimeout(30_000) { sut.status.first { it is SyncStatus.Online } }
        return sut
    }

    @Test
    fun `the state is read from the server, and set and clear move it`() = runBlocking {
        val client = FakeWebAccessClient()
        val sut = connected(client)
        try {
            assertEquals(false, sut.webAccessEnabled())

            assertEquals(WebAccessChange.Success, sut.setWebPassword("web-pw-123".toCharArray()))
            assertEquals("web-pw-123", client.stored)
            assertEquals(true, sut.webAccessEnabled())

            assertEquals(WebAccessChange.Success, sut.clearWebPassword())
            assertNull(client.stored)
            assertEquals(false, sut.webAccessEnabled())
        } finally {
            sut.close()
        }
    }

    @Test
    fun `the caller's copy of the password is wiped, whatever the outcome`() = runBlocking {
        val sut = connected(FakeWebAccessClient())
        try {
            val typed = "web-pw-123".toCharArray()
            sut.setWebPassword(typed)
            assertTrue(typed.all { it == ' ' }, "the array handed in must not still hold the password")

            // Same on the failure path: a refused change is exactly when a copy would be left behind.
            val refused = coordinator(localVault(), FakeWebAccessClient())
            val typedAgain = "web-pw-456".toCharArray()
            assertEquals(WebAccessChange.NotConnected, refused.setWebPassword(typedAgain))
            assertTrue(typedAgain.all { it == ' ' })
            refused.close()
        } finally {
            sut.close()
        }
    }

    @Test
    fun `without a session there is nothing to change, and no state to report`() = runBlocking {
        initializeVaultCrypto()
        val sut = coordinator(localVault(), FakeWebAccessClient())
        try {
            // null, not false: an account may well have web access on — this device just can't see it.
            assertNull(sut.webAccessEnabled())
            assertEquals(WebAccessChange.NotConnected, sut.clearWebPassword())
        } finally {
            sut.close()
        }
    }

    @Test
    fun `an expired access token is refreshed once, not reported as a rejection`() = runBlocking {
        // The settings screen is opened after the app sat idle past the 15-minute access TTL. The
        // session is alive — the refresh token is — so the change must land, not tell the user their
        // password was refused.
        val client = FakeWebAccessClient(staleToken = "access")
        val sut = connected(client)
        try {
            assertEquals(WebAccessChange.Success, sut.setWebPassword("web-pw-123".toCharArray()))
            assertEquals("web-pw-123", client.stored)
            assertTrue(sut.status.value is SyncStatus.Online, "recovering a token must not park the session")
        } finally {
            sut.close()
        }
    }

    @Test
    fun `a refusal is reported with its own reason, and the sync status is left alone`() = runBlocking {
        val client = FakeWebAccessClient(failWith = SyncException(SyncException.Kind.TOO_MANY_REQUESTS, "slow down"))
        val sut = connected(client)
        try {
            // Reason and detail both come from the shared exception mapping, which keeps a technical
            // detail only where it adds something — "wait and retry" needs no exception message.
            assertEquals(
                WebAccessChange.Failed(SyncFailureReason.TooManyRequests),
                sut.setWebPassword("web-pw-123".toCharArray()),
            )
            // The card owns this failure. Painting the whole Sync section red for it would say the
            // sync session broke, which it did not.
            assertTrue(sut.status.value is SyncStatus.Online)
        } finally {
            sut.close()
        }
    }

    @Test
    fun `a refresh that fails on the network says so, not that the password was refused`() = runBlocking {
        // The access token expired and the recovery ran into a network blip. "Unauthorized" here
        // would read as "your session is gone" and send the user to re-enter a master password that
        // was never the problem — the same distinction runSyncLocked already makes.
        val client = FakeWebAccessClient(staleToken = "access")
        val sut = connected(client)
        try {
            client.refreshFailure = SyncException(SyncException.Kind.NETWORK, "unreachable")
            assertEquals(
                WebAccessChange.Failed(SyncFailureReason.Network, "unreachable"),
                sut.setWebPassword("web-pw-123".toCharArray()),
            )
        } finally {
            sut.close()
        }
    }

    @Test
    fun `a rejected refresh token is a dead session, and the card says so`() = runBlocking {
        // The other half of the branch above: the server refused the refresh token itself, so the
        // session really is gone. `refreshSessionLocked` tears it down, and the honest answer to the
        // caller is the original 401 — not the network reason the blip case reports.
        val client = FakeWebAccessClient(staleToken = "access")
        val sut = connected(client)
        try {
            client.refreshFailure = SyncException(SyncException.Kind.UNAUTHORIZED, "refresh token rejected")
            // Unauthorized carries no detail: the reason is the credential, not a server sentence.
            assertEquals(
                WebAccessChange.Failed(SyncFailureReason.Unauthorized, null),
                sut.setWebPassword("web-pw-123".toCharArray()),
            )
            // The teardown is the point: the session is gone, so the next call has nothing to run on.
            assertNull(sut.webAccessEnabled())
            assertEquals(WebAccessChange.NotConnected, sut.clearWebPassword())
            // Parked on Configured, not Disabled: the saved server link survives a dead session, or
            // the person has to re-enter the URL and account id to sign in again.
            assertTrue(sut.status.value is SyncStatus.Configured)
        } finally {
            sut.close()
        }
    }

    @Test
    fun `cancelling mid-submit still wipes the password`() = runBlocking {
        // Leaving the settings screen while the request is in flight cancels the scope the card
        // launched in. The typed array must not survive that — it is the one copy this side can clear.
        val client = FakeWebAccessClient()
        val sut = connected(client)
        try {
            val gate = CompletableDeferred<Unit>()
            client.entered = gate
            val typed = "web-pw-123".toCharArray()
            val job = launch { sut.setWebPassword(typed) }
            withTimeout(30_000) { gate.await() }
            job.cancelAndJoin()
            assertTrue(typed.all { it == ' ' }, "a cancelled submit must not leave the password in the array")
        } finally {
            sut.close()
        }
    }

    @Test
    fun `two web-access calls racing an expired token rotate it once and both land`() = runBlocking {
        val client = FakeWebAccessClient(staleToken = "access")
        val sut = connected(client)
        try {
            val before = client.refreshes
            val set = async { sut.setWebPassword("web-pw-123".toCharArray()) }
            val state = async { sut.webAccessEnabled() }
            assertEquals(WebAccessChange.Success, withTimeout(30_000) { set.await() })
            withTimeout(30_000) { state.await() }
            // One rotation, not one per caller: the second finds the session already replaced under
            // syncMutex and retries with it rather than spending another refresh token.
            assertEquals(1, client.refreshes - before)
            assertTrue(sut.status.value is SyncStatus.Online)
        } finally {
            sut.close()
        }
    }

    @Test
    fun `a state that cannot be read is unknown, not off`() = runBlocking {
        val client = FakeWebAccessClient(failWith = SyncException(SyncException.Kind.NETWORK, "unreachable"))
        val sut = connected(client)
        try {
            assertNull(sut.webAccessEnabled())
        } finally {
            sut.close()
        }
    }
}
