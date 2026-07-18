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
 * Issue #28 — the account (remote) password is the single source of truth, and enabling sync must not
 * silently diverge from, or silently replace, the local vault password:
 *
 *  - connecting with the VAULT's own password establishes the account under it, no prompt, no change;
 *  - connecting with a non-vault password that isn't a real account password is rejected (we never
 *    register a divergent account);
 *  - connecting with the password of an EXISTING account (different from the vault's) re-keys this
 *    device to the account password — but only after the user confirms it.
 *
 * The account vault is a real [FileVault] over the system filesystem and crypto is real
 * [IonspinVaultCrypto] (Argon2id) — the whole point is password/key wrapping, so nothing is faked
 * there; only the network ([SyncClient]) is stubbed to model the server's account state.
 */
class SyncCoordinatorPasswordReplaceTest {

    private val crypto = IonspinVaultCrypto()
    private val serverUrl = "https://sync.test"
    private val account = "maya"
    private val vaultPassword = "vault-A"
    private val accountPassword = "account-B"

    /**
     * Network stub modelling one account. [existingAccountPassword] = the password the account already
     * exists under, or `null` if there's no account yet (so `register` creates it). `login` succeeds only
     * for the matching authKey; `register` collides when the account exists. The wrapped account dataKey is
     * a DIFFERENT key wrapped under the account password (adopting it re-keys the joining vault).
     */
    private inner class FakeAccountClient(existingAccountPassword: String?) : SyncClient {
        private val expectedAuthKey: ByteArray?
        private val wrappedAccountKey: ByteArray?

        init {
            if (existingAccountPassword == null) {
                expectedAuthKey = null
                wrappedAccountKey = null
            } else {
                val mk = crypto.deriveMasterKey(existingAccountPassword.toCharArray(), crypto.deriveSyncSalt(account))
                expectedAuthKey = crypto.deriveAuthKey(mk)
                val accountKey = crypto.newDataKey()
                wrappedAccountKey = crypto.wrapDataKey(mk, accountKey)
                mk.zeroize()
                accountKey.zeroize()
            }
        }

        var registered = false; private set

        override suspend fun register(accountId: String, authKey: ByteArray, wrappedDataKey: ByteArray, device: DeviceInfo): SyncSession {
            if (expectedAuthKey != null) throw SyncException(SyncException.Kind.CONFLICT, "account exists")
            registered = true
            return SyncSession(accountId, accessToken = "access", refreshToken = "refresh")
        }

        override suspend fun login(accountId: String, authKey: ByteArray, device: DeviceInfo): SyncSession {
            if (expectedAuthKey != null && authKey.contentEquals(expectedAuthKey)) {
                return SyncSession(accountId, accessToken = "access", refreshToken = "refresh")
            }
            throw SyncException(SyncException.Kind.UNAUTHORIZED, "wrong password") // server hides "no such account"
        }

        override suspend fun fetchWrappedDataKey(session: SyncSession): ByteArray =
            wrappedAccountKey?.copyOf() ?: throw NotImplementedError("no account")

        override fun changes(session: SyncSession): Flow<SyncSignal> = emptyFlow()
        override suspend fun ping(): Boolean = true
        override suspend fun close() {}
        override suspend fun pull(session: SyncSession, since: Long): RecordPage = nope()
        override suspend fun push(session: SyncSession, records: List<RemoteRecord>): RecordPage = nope()
        override suspend fun listDevices(session: SyncSession): List<RemoteDevice> = nope()
        override suspend fun revokeDevice(session: SyncSession, deviceId: String): Boolean = nope()
        override suspend fun refresh(session: SyncSession): SyncSession = nope()
        override suspend fun startPairing(session: SyncSession, encryptedDataKey: ByteArray): PairingTicket = nope()
        override suspend fun claimPairing(code: String, device: DeviceInfo): PairingResult = nope()
        private fun nope(): Nothing = throw NotImplementedError("the connect flow should not call this")
    }

    /** A local vault created under [vaultPassword] (unlocked, with its own random dataKey). */
    private fun localVault(): Vault {
        val file = Files.createTempFile("skerry-issue28", ".json").toString().toPath()
        FileSystem.SYSTEM.delete(file) // FileVault creates it
        return FileVault(file, crypto, deviceId = "dev-local", fileSystem = FileSystem.SYSTEM, now = { "2026-07-18T00:00:00Z" })
            .also { it.create(vaultPassword.toCharArray()) }
    }

    private fun coordinator(vault: Vault, client: SyncClient): SyncCoordinator = SyncCoordinator(
        clientFactory = { client },
        crypto = crypto,
        vault = vault,
        engineFactory = { _ -> SyncRunner { _ -> SyncOutcome(pulled = 0, pushed = 0, cursor = 0L) } },
    )

    @Test
    fun `joining an existing account under a different password pauses without changing the vault password`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val sut = coordinator(vault, FakeAccountClient(existingAccountPassword = accountPassword))
        try {
            sut.connect(serverUrl, account, accountPassword.toCharArray())
            withTimeout(30_000) { sut.status.first { it is SyncStatus.NeedsPasswordReplaceConfirm } }
            // The bug: the vault unlock password must NOT have silently changed.
            assertTrue(vault.verifyPassword(vaultPassword.toCharArray()), "local vault password must still work")
            assertFalse(vault.verifyPassword(accountPassword.toCharArray()), "account password must not have replaced it yet")
        } finally {
            sut.close()
        }
    }

    @Test
    fun `confirming re-keys the vault to the account password`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val sut = coordinator(vault, FakeAccountClient(existingAccountPassword = accountPassword))
        try {
            sut.connect(serverUrl, account, accountPassword.toCharArray())
            withTimeout(30_000) { sut.status.first { it is SyncStatus.NeedsPasswordReplaceConfirm } }
            sut.confirmPasswordReplace()
            withTimeout(30_000) { sut.status.first { it is SyncStatus.Online } }
            assertFalse(vault.verifyPassword(vaultPassword.toCharArray()), "old vault password must no longer work")
            assertTrue(vault.verifyPassword(accountPassword.toCharArray()), "account password now unlocks the vault")
        } finally {
            sut.close()
        }
    }

    @Test
    fun `cancelling keeps the vault password and returns to the prior state`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val sut = coordinator(vault, FakeAccountClient(existingAccountPassword = accountPassword))
        try {
            sut.connect(serverUrl, account, accountPassword.toCharArray())
            withTimeout(30_000) { sut.status.first { it is SyncStatus.NeedsPasswordReplaceConfirm } }
            sut.cancelPasswordReplace()
            withTimeout(30_000) { sut.status.first { it is SyncStatus.Disabled } }
            assertTrue(vault.verifyPassword(vaultPassword.toCharArray()), "local vault password must be untouched")
            assertFalse(vault.verifyPassword(accountPassword.toCharArray()), "account password must not unlock the vault")
        } finally {
            sut.close()
        }
    }

    @Test
    fun `connecting with the vault password creates the account without prompting`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val client = FakeAccountClient(existingAccountPassword = null) // no account yet → register
        val sut = coordinator(vault, client)
        try {
            sut.connect(serverUrl, account, vaultPassword.toCharArray())
            withTimeout(30_000) { sut.status.first { it is SyncStatus.Online } }
            assertTrue(client.registered, "account should be registered under the vault password")
            assertTrue(vault.verifyPassword(vaultPassword.toCharArray()), "vault password is unchanged")
        } finally {
            sut.close()
        }
    }

    @Test
    fun `connecting with a non-vault password and no matching account is rejected without registering`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val client = FakeAccountClient(existingAccountPassword = null) // no account exists
        val sut = coordinator(vault, client)
        try {
            // "wrong-C" is neither the vault password nor a real account password.
            sut.connect(serverUrl, account, "wrong-C".toCharArray())
            withTimeout(30_000) {
                sut.status.first { it is SyncStatus.Failed && it.reason == SyncFailureReason.Unauthorized }
            }
            assertFalse(client.registered, "must not register a divergent account under a non-vault password")
            assertTrue(vault.verifyPassword(vaultPassword.toCharArray()), "vault password is untouched")
        } finally {
            sut.close()
        }
    }
}
