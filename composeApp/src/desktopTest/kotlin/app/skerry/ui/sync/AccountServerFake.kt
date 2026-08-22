package app.skerry.ui.sync

import app.skerry.shared.sync.AccountSummary
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
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.VaultCrypto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger


/**
 * Network stub modelling one account. [existingAccountPassword] = the password the account already
 * exists under, or `null` if there's no account yet (so `register` creates it). `login` succeeds only
 * for the matching authKey; `register` collides when the account exists. The wrapped account dataKey is
 * a DIFFERENT key wrapped under the account password (adopting it re-keys the joining vault).
 */
internal class FakeAccountClient(
    private val crypto: VaultCrypto,
    private val account: String,
    existingAccountPassword: String?,
    /** The account key to publish. `null` = a fresh random one (a genuinely foreign account). */
    accountDataKey: DataKey? = null,
    /** Serve a wrap that can't be unwrapped, modelling a corrupted/mismatched server record. */
    private val corruptWrap: Boolean = false,
    /** This device is revoked on the account: the first successful login reactivates it and says so once. */
    revoked: Boolean = false,
    /** Holds `refresh` until completed, so a test can drive what happens while a silent restore is in flight. */
    private val refreshGate: CompletableDeferred<Unit>? = null,
    /** Holds `login` until completed, parking a connect mid-flight for a second one to race. */
    private val loginGate: CompletableDeferred<Unit>? = null,
    /** Holds the account-key fetch — only the confirmed re-run gets that far, so it parks that one. */
    private val fetchGate: CompletableDeferred<Unit>? = null,
) : SyncClient {
    // var: a password rotation ([changePassword]) swaps both to the new password's material.
    private var expectedAuthKey: ByteArray?
    private var wrappedAccountKey: ByteArray?

    init {
        if (existingAccountPassword == null) {
            expectedAuthKey = null
            wrappedAccountKey = null
        } else {
            // Shared cache, not zeroized here: this class builds a client per test and the Argon2id
            // derivation is what makes the class the slowest in the suite (issue #141).
            val mk = syncAccountKey(crypto, existingAccountPassword, account)
            expectedAuthKey = crypto.deriveAuthKey(mk)
            val key = accountDataKey ?: crypto.newDataKey()
            wrappedAccountKey = crypto.wrapDataKey(mk, key)
            key.zeroize()
        }
    }

    var registered = false; private set

    // Cleared by the verify that reports it, exactly as the server does it (re-authentication
    // reactivates the device), so a later login of the same device carries nothing.
    private val revoked = AtomicBoolean(revoked)

    override suspend fun register(accountId: String, authKey: ByteArray, wrappedDataKey: ByteArray, device: DeviceInfo): SyncSession {
        if (expectedAuthKey != null) throw SyncException(SyncException.Kind.CONFLICT, "account exists")
        registered = true
        return SyncSession(accountId, accessToken = "access", refreshToken = "refresh")
    }

    /** Completed once a connect has reached the login. */
    val loggingIn = CompletableDeferred<Unit>()
    private val loginCalls = AtomicInteger(0)

    /** How many connects got as far as the login. */
    val logins: Int get() = loginCalls.get()

    override suspend fun login(accountId: String, authKey: ByteArray, device: DeviceInfo): SyncSession {
        loginCalls.incrementAndGet()
        loggingIn.complete(Unit)
        loginGate?.await()
        if (expectedAuthKey != null && authKey.contentEquals(expectedAuthKey)) {
            return SyncSession(accountId, accessToken = "access", refreshToken = "refresh", reactivated = revoked.getAndSet(false))
        }
        throw SyncException(SyncException.Kind.UNAUTHORIZED, "wrong password") // server hides "no such account"
    }

    /** Completed once a connect has reached the account-key fetch. */
    val fetching = CompletableDeferred<Unit>()

    override suspend fun fetchWrappedDataKey(session: SyncSession): ByteArray {
        fetching.complete(Unit)
        fetchGate?.await()
        val wrap = wrappedAccountKey?.copyOf() ?: throw NotImplementedError("no account")
        if (corruptWrap) wrap.indices.forEach { wrap[it] = 0 }
        return wrap
    }

    /**
     * Models the server rotation (issue #32): the SRP proof is only accepted for the CURRENT
     * authKey, then the verifier (authKey) and the wrapped dataKey are swapped to the new ones.
     * Other-device revocation isn't observable through this stub, so it's not modelled.
     */
    override suspend fun changePassword(
        accountId: String,
        currentAuthKey: ByteArray,
        newAuthKey: ByteArray,
        newWrappedDataKey: ByteArray,
        device: DeviceInfo,
    ): SyncSession {
        if (expectedAuthKey == null || !currentAuthKey.contentEquals(expectedAuthKey)) {
            throw SyncException(SyncException.Kind.UNAUTHORIZED, "wrong current password")
        }
        expectedAuthKey = newAuthKey.copyOf()
        wrappedAccountKey = newWrappedDataKey.copyOf()
        return SyncSession(accountId, accessToken = "access2", refreshToken = "refresh2")
    }

    var closeCalls = 0; private set

    override fun changes(session: SyncSession): Flow<SyncSignal> = emptyFlow()

    // Unreachable on purpose, as in [ReactivatingClient]: a health ping that comes up drives the
    // coordinator's own self-heal ([SyncCoordinator]'s init — no session, so `restoreSession`), and a
    // restore racing the connects these tests drive is what made this class flake (issue #278). The
    // restore is driven explicitly where a test is about it.
    override suspend fun ping(): Boolean = false
    override suspend fun close() {
        closeCalls++
    }
    override suspend fun pull(session: SyncSession, since: Long): RecordPage = nope()
    override suspend fun push(session: SyncSession, records: List<RemoteRecord>): RecordPage = nope()
    override suspend fun listDevices(session: SyncSession): List<RemoteDevice> = nope()
    override suspend fun accountSummary(session: SyncSession): AccountSummary = nope()
    override suspend fun revokeDevice(session: SyncSession, deviceId: String): Boolean = nope()
    /** Completed once a silent restore has reached the token exchange (and is holding `opMutex`). */
    val refreshing = CompletableDeferred<Unit>()
    private val refreshCalls = AtomicInteger(0)

    /** How many silent restores got as far as the token exchange. */
    val refreshes: Int get() = refreshCalls.get()

    override suspend fun refresh(session: SyncSession): SyncSession {
        refreshCalls.incrementAndGet()
        refreshing.complete(Unit)
        refreshGate?.await()
        // What the restore does next isn't what these tests are about — fail it, so the status falls
        // back to Configured and only what the connect does is left to look at.
        //
        // This used to be a loud `nope()`, which doubled as a tripwire for the tests that never meant to
        // restore at all. Together with `ping()` returning unreachable, an unexpected restore in those now
        // parks on Configured instead of failing the run — deliberate: what the tripwire kept catching was
        // the coordinator's own self-heal, and that is what made this package flake (issue #278).
        throw SyncException(SyncException.Kind.NETWORK, "unreachable")
    }
    override suspend fun startPairing(session: SyncSession, encryptedDataKey: ByteArray): PairingTicket = nope()
    private val claimCalls = AtomicInteger(0)

    /** How many pairing claims reached the server. */
    val claims: Int get() = claimCalls.get()

    // The envelope opens under no transfer key, so the claim fails on the step right after this one.
    // What these tests need is that it got as far as the server, not that pairing succeeds.
    override suspend fun claimPairing(code: String, device: DeviceInfo): PairingResult {
        claimCalls.incrementAndGet()
        return PairingResult(account, ByteArray(64), SyncSession(account, "access", "refresh"))
    }
    private fun nope(): Nothing = throw NotImplementedError("the connect flow should not call this")
}
