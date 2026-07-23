package app.skerry.ui.sync

import app.skerry.shared.sync.DeviceInfo
import app.skerry.shared.sync.PairingResult
import app.skerry.shared.sync.PairingTicket
import app.skerry.shared.sync.RecordPage
import app.skerry.shared.sync.RemoteDevice
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Mid-session token refresh: the server's access token has a short TTL (15 min by default), so a
 * session older than that gets 401 on its next sync. The coordinator holds a perfectly valid refresh
 * token (30 days) in [SyncSession] — it must rotate the tokens and retry instead of surfacing
 * Failed(Unauthorized), which the mobile UI renders as "logged out, re-enter password".
 *
 * Real [FileVault] + real crypto (the sealed-token re-seal is part of the contract); the network and
 * the sync cycle are stubbed ([SyncClient] fake + `engineFactory`).
 */
class SyncCoordinatorTokenRefreshTest {

    private val crypto = IonspinVaultCrypto()
    private val serverUrl = "https://sync.test"
    private val account = "maya"
    private val password = "vault-A"

    /**
     * Existing account under this vault's own key (register collides, login adopts nothing). Tokens
     * are numbered: login issues `access-1`/`refresh-1`, each [refresh] rotates to the next pair.
     * [expiredTokens] simulates server-side access-token expiry; [changesFails] makes the WS stream
     * drop immediately on each subscription (recording the token it was opened with).
     */
    private inner class RotatingClient(
        private val ownWrappedKey: ByteArray,
        private val changesFails: Boolean = false,
    ) : SyncClient {
        val expiredTokens = CopyOnWriteArrayList<String>()
        val refreshCalls = AtomicInteger(0)
        val watchTokens = CopyOnWriteArrayList<String>()
        val closeCalls = AtomicInteger(0)
        @Volatile
        var refreshUnauthorized = false
        @Volatile
        var refreshNetworkError = false
        private val issued = AtomicInteger(1)

        override suspend fun register(accountId: String, authKey: ByteArray, wrappedDataKey: ByteArray, device: DeviceInfo): SyncSession =
            throw SyncException(SyncException.Kind.CONFLICT, "account exists")
        override suspend fun login(accountId: String, authKey: ByteArray, device: DeviceInfo): SyncSession =
            SyncSession(accountId, accessToken = "access-1", refreshToken = "refresh-1")
        override suspend fun fetchWrappedDataKey(session: SyncSession): ByteArray = ownWrappedKey.copyOf()
        override suspend fun refresh(session: SyncSession): SyncSession {
            refreshCalls.incrementAndGet()
            if (refreshUnauthorized) throw SyncException(SyncException.Kind.UNAUTHORIZED, "invalid refresh token")
            if (refreshNetworkError) throw SyncException(SyncException.Kind.NETWORK, "refresh unreachable")
            val n = issued.incrementAndGet()
            return SyncSession(session.accountId, accessToken = "access-$n", refreshToken = "refresh-$n")
        }
        override fun changes(session: SyncSession): Flow<SyncSignal> = flow {
            watchTokens += session.accessToken
            if (changesFails) throw SyncException(SyncException.Kind.NETWORK, "ws drop")
            awaitCancellation()
        }
        override suspend fun pull(session: SyncSession, since: Long): RecordPage = RecordPage(emptyList(), 1)
        override suspend fun push(session: SyncSession, records: List<app.skerry.shared.sync.RemoteRecord>): RecordPage = RecordPage(emptyList(), 1)
        override suspend fun ping(): Boolean = true
        override suspend fun close() { closeCalls.incrementAndGet() }
        override suspend fun listDevices(session: SyncSession): List<RemoteDevice> = emptyList()
        override suspend fun revokeDevice(session: SyncSession, deviceId: String): Boolean = false
        override suspend fun changePassword(accountId: String, currentAuthKey: ByteArray, newAuthKey: ByteArray, newWrappedDataKey: ByteArray, device: DeviceInfo): SyncSession = throw NotImplementedError()
        override suspend fun startPairing(session: SyncSession, encryptedDataKey: ByteArray): PairingTicket = throw NotImplementedError()
        override suspend fun claimPairing(code: String, device: DeviceInfo): PairingResult = throw NotImplementedError()
    }

    private fun freshVault(): Vault {
        val file = Files.createTempFile("skerry-token-refresh", ".json").toString().toPath()
        FileSystem.SYSTEM.delete(file) // FileVault creates it
        return FileVault(file, crypto, deviceId = "devA", fileSystem = FileSystem.SYSTEM, now = { "2026-07-23T00:00:00Z" })
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

    /** Coordinator whose sync cycle fails with 401 whenever the session's access token is expired. */
    private fun coordinator(client: RotatingClient, vault: Vault, config: InMemorySyncConfigStore) =
        SyncCoordinator(
            clientFactory = { client },
            crypto = crypto,
            vault = vault,
            configStore = config,
            engineFactory = { _ ->
                SyncRunner { s ->
                    if (s.accessToken in client.expiredTokens) throw SyncException(SyncException.Kind.UNAUTHORIZED, "token expired")
                    SyncOutcome(pulled = 0, pushed = 0, cursor = 1)
                }
            },
        )

    @Test
    fun `expired access token is rotated via the refresh token and the sync retried`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        val client = RotatingClient(ownWrap(vault))
        val config = InMemorySyncConfigStore()
        val sut = coordinator(client, vault, config)
        try {
            sut.connect(serverUrl, account, password.toCharArray(), keepConnected = true)
            withTimeout(30_000) { sut.status.first { it is SyncStatus.Online || it is SyncStatus.Failed } }
            assertTrue(sut.status.value is SyncStatus.Online, "connect should come Online, got ${sut.status.value}")
            val sealedAtConnect = config.load()?.sealedRefreshToken
            assertNotNull(sealedAtConnect, "keep-connected must seal the refresh token at connect")

            // The 15-minute TTL passes: the server now rejects the session's access token.
            client.expiredTokens += "access-1"
            sut.syncNow()
            val terminal = withTimeout(30_000) {
                sut.status.first { (it is SyncStatus.Online && client.refreshCalls.get() > 0) || it is SyncStatus.Failed || it is SyncStatus.Configured }
            }
            assertTrue(terminal is SyncStatus.Online, "an expired access token must refresh+retry, not surface as $terminal")
            assertEquals(1, client.refreshCalls.get(), "exactly one refresh for one expired sync")

            // The rotated refresh token must be re-sealed, or the next cold-start restore would use a stale one.
            val sealedAfter = config.load()?.sealedRefreshToken
            assertNotNull(sealedAfter)
            assertNotEquals(sealedAtConnect, sealedAfter, "rotated refresh token must be re-sealed into the config")
            val dk = vault.exportDataKey()!!
            try {
                assertEquals("refresh-2", SealedTokenCodec(crypto).open(dk, sealedAfter), "re-sealed token must be the rotated one")
            } finally {
                dk.zeroize()
            }
        } finally {
            sut.close()
        }
    }

    @Test
    fun `dead refresh token falls back to Configured (password reauth), not a raw error`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        val client = RotatingClient(ownWrap(vault))
        val config = InMemorySyncConfigStore()
        val sut = coordinator(client, vault, config)
        try {
            sut.connect(serverUrl, account, password.toCharArray(), keepConnected = true)
            withTimeout(30_000) { sut.status.first { it is SyncStatus.Online || it is SyncStatus.Failed } }
            assertTrue(sut.status.value is SyncStatus.Online)

            // Both tokens are dead (device revoked / 30-day refresh expiry): the link survives, the
            // session doesn't — the user reconnects with the password, like restoreSession's fallback.
            client.expiredTokens += "access-1"
            client.refreshUnauthorized = true
            sut.syncNow()
            val terminal = withTimeout(30_000) {
                sut.status.first { it is SyncStatus.Configured || it is SyncStatus.Failed }
            }
            assertTrue(terminal is SyncStatus.Configured, "a dead refresh token must ask for the password (Configured), got $terminal")
            assertEquals(null, sut.currentSession(), "the dead session must be dropped")
            assertTrue(client.closeCalls.get() > 0, "the dead session's client must be closed")
            assertEquals(null, config.load()?.sealedRefreshToken, "the known-dead sealed token must be dropped from the config")
        } finally {
            sut.close()
        }
    }

    @Test
    fun `network failure during refresh surfaces as Network and keeps the session for a later retry`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        val client = RotatingClient(ownWrap(vault))
        val config = InMemorySyncConfigStore()
        val sut = coordinator(client, vault, config)
        try {
            sut.connect(serverUrl, account, password.toCharArray(), keepConnected = true)
            withTimeout(30_000) { sut.status.first { it is SyncStatus.Online || it is SyncStatus.Failed } }
            assertTrue(sut.status.value is SyncStatus.Online)

            // Access token expired AND the refresh round trip hits a network blip: the truthful status
            // is Network — Failed(Unauthorized) here would be the very "logged out" rendering the
            // recovery exists to remove. The session must survive for the next attempt.
            client.expiredTokens += "access-1"
            client.refreshNetworkError = true
            sut.syncNow()
            val failed = withTimeout(30_000) { sut.status.first { it is SyncStatus.Failed || it is SyncStatus.Configured } }
            assertTrue(failed is SyncStatus.Failed && failed.reason == SyncFailureReason.Network, "a refresh network blip must surface as Network, got $failed")
            assertTrue(sut.currentSession() != null, "the session must survive a transient refresh failure")

            // The blip passes: the next sync recovers through the normal refresh+retry path.
            client.refreshNetworkError = false
            sut.syncNow()
            withTimeout(30_000) { sut.status.first { it is SyncStatus.Online } }
            assertEquals(2, client.refreshCalls.get(), "one failed and one successful refresh")
        } finally {
            sut.close()
        }
    }

    @Test
    fun `watch reconnect picks up the rotated access token`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        val client = RotatingClient(ownWrap(vault), changesFails = true)
        val config = InMemorySyncConfigStore()
        val sut = coordinator(client, vault, config)
        try {
            sut.connect(serverUrl, account, password.toCharArray(), keepConnected = true)
            withTimeout(30_000) { sut.status.first { it is SyncStatus.Online || it is SyncStatus.Failed } }
            assertTrue(sut.status.value is SyncStatus.Online)

            // Every WS drop rotates the session (best-effort refresh) — the NEXT handshake must use
            // the fresh token, not the one captured when the watch started (a dead captured token
            // would otherwise loop 401 forever while the status stays Online).
            withTimeout(30_000) {
                while (client.watchTokens.size < 2) delay(50)
            }
            assertEquals("access-1", client.watchTokens[0])
            assertEquals("access-2", client.watchTokens[1], "watch reconnect must use the refreshed session")
        } finally {
            sut.close()
        }
    }
}
