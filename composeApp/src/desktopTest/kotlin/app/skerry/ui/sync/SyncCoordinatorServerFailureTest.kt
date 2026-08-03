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
import kotlin.test.assertTrue

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

    /**
     * Server that rejects registration with [kind] — the first call any connect makes. What the
     * login fallback (CONFLICT/FORBIDDEN → login) then does is [loginSession] on success or
     * [loginFailure] on refusal; with neither set, login is unreachable for that test.
     * [loginCalls] is what proves the fallback ran at all — the status alone can't tell a
     * rethrown 403 from one that never reached login.
     */
    private class RejectingClient(
        private val kind: SyncException.Kind,
        private val loginSession: SyncSession? = null,
        private val loginFailure: SyncException? = null,
    ) : SyncClient {
        @Volatile
        var loginCalls = 0
            private set

        @Volatile
        var closeCalls = 0
            private set

        override suspend fun ping(): Boolean = true

        override suspend fun register(accountId: String, authKey: ByteArray, wrappedDataKey: ByteArray, device: DeviceInfo): SyncSession =
            throw SyncException(kind, "rejected")

        override fun changes(session: SyncSession): Flow<SyncSignal> = flow { awaitCancellation() }
        override suspend fun close() {
            closeCalls++
        }
        override suspend fun refresh(session: SyncSession): SyncSession = nope()
        override suspend fun login(accountId: String, authKey: ByteArray, device: DeviceInfo): SyncSession {
            loginCalls++
            loginFailure?.let { throw it }
            return loginSession ?: nope()
        }
        override suspend fun changePassword(accountId: String, currentAuthKey: ByteArray, newAuthKey: ByteArray, newWrappedDataKey: ByteArray, device: DeviceInfo): SyncSession = nope()
        // An empty wrap can't be unwrapped, so adoptAccountDataKey returns Undecryptable — which the
        // matchesVault branch treats as "key not ours to adopt" and connects anyway. That is the only
        // reason this stub reaches Online; the tests here are about the register/login handshake, not
        // about key adoption.
        override suspend fun fetchWrappedDataKey(session: SyncSession): ByteArray = ByteArray(0)
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
        failedConnect(RejectingClient(kind)).reason

    private fun failedConnect(client: RejectingClient): SyncStatus.Failed = runBlocking {
        initializeVaultCrypto()
        val sut = SyncCoordinator(
            clientFactory = { client },
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
     * register → 403, login → 401. Two causes are indistinguishable here (the server hides "no such
     * account" behind the wrong-password shape): no account on an instance that won't create one, or
     * an account whose password was rotated from another device while this vault kept the old one.
     * Naming only the 403 would misreport the second case — "registration limit reached" has nothing
     * to do with a stale password — so the failure names both, and keeps the server's own sentence
     * as the detail.
     */
    @Test
    fun `a refused registration whose login also fails names both causes, with the server's own words`() {
        val client = RejectingClient(
            SyncException.Kind.FORBIDDEN,
            loginFailure = SyncException(SyncException.Kind.UNAUTHORIZED, "no such account"),
        )
        val failed = failedConnect(client)
        // The fallback must have been tried before the refusal is reported — otherwise this asserts
        // nothing the pre-fix "rethrow the 403 immediately" code didn't already satisfy.
        assertEquals(1, client.loginCalls, "the 403 must be probed with a login before it is reported")
        // The client is opened by this connect and nothing downstream adopts it — leaving it behind
        // leaks a Ktor pool per failed attempt, and a closed instance fails on every reconnect.
        assertEquals(1, client.closeCalls, "the client must be closed on the way out")
        assertEquals(SyncFailureReason.RegistrationRefusedSignInFailed, failed.reason)
        // Without the detail the user never learns which refusal the server chose.
        assertEquals("rejected", failed.detail)
    }

    /** The server answers 404 rather than 401 for the same "no account" case: same conclusion. */
    @Test
    fun `a refused registration whose login answers not found lands on the same failure`() {
        val failed = failedConnect(
            RejectingClient(
                SyncException.Kind.FORBIDDEN,
                loginFailure = SyncException(SyncException.Kind.NOT_FOUND, "no such account"),
            ),
        )
        assertEquals(SyncFailureReason.RegistrationRefusedSignInFailed, failed.reason)
    }

    /**
     * The 403 is only substituted for a login failure that says "this account can't be signed into".
     * A throttled fallback is a different, retryable fact and must survive as itself — otherwise the
     * user is told to go ask an administrator about a limiter that clears in a minute.
     */
    @Test
    fun `a throttled login fallback keeps the throttling, not the registration refusal`() {
        val failed = failedConnect(
            RejectingClient(
                SyncException.Kind.FORBIDDEN,
                loginFailure = SyncException(SyncException.Kind.TOO_MANY_REQUESTS, "slow down"),
            ),
        )
        assertEquals(SyncFailureReason.TooManyRequests, failed.reason)
    }

    /**
     * The CONFLICT path shares the fallback but not the substitution: on an open instance a 401 after
     * a 409 is an ordinary wrong password, and pinning that here keeps a future edit of the branch
     * condition from silently widening the FORBIDDEN behaviour over it.
     */
    @Test
    fun `an existing account with a wrong password still reports as unauthorized`() {
        val failed = failedConnect(
            RejectingClient(
                SyncException.Kind.CONFLICT,
                loginFailure = SyncException(SyncException.Kind.UNAUTHORIZED, "authentication failed"),
            ),
        )
        assertEquals(SyncFailureReason.Unauthorized, failed.reason)
    }

    /**
     * Regression test for the closed-registration edge: a closed instance answers /auth/register
     * with 403 for every account (it never looks at the id), so an existing account must fall back
     * to login — not be locked out. register → 403, login → OK: connect succeeds.
     */
    @Test
    fun `a closed instance still lets existing accounts log in via the login fallback`() {
        val result = runBlocking {
            initializeVaultCrypto()
            val session = SyncSession(accountId = account, accessToken = "at", refreshToken = "rt")
            val sut = SyncCoordinator(
                clientFactory = { RejectingClient(SyncException.Kind.FORBIDDEN, loginSession = session) },
                crypto = crypto,
                vault = localVault(),
                engineFactory = { _ -> SyncRunner { _ -> SyncOutcome(pulled = 0, pushed = 0, cursor = 0L) } },
            )
            try {
                sut.connect(serverUrl, account, password.toCharArray())
                withTimeout(30_000) { sut.status.first { it is SyncStatus.Online || it is SyncStatus.Failed } }
            } finally {
                sut.close()
            }
        }
        assertTrue(result !is SyncStatus.Failed, "expected connect to succeed, got $result")
    }
}
