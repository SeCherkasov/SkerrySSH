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
import app.skerry.shared.sync.SyncOutcome
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.sync.SyncSignal
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * Either way the device must end up holding the ACCOUNT's dataKey: a wrap that doesn't open under the
 * password the server just accepted fails the connect on both paths (issue #133).
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
    private inner class FakeAccountClient(
        existingAccountPassword: String?,
        /** The account key to publish. `null` = a fresh random one (a genuinely foreign account). */
        accountDataKey: DataKey? = null,
        /** Serve a wrap that can't be unwrapped, modelling a corrupted/mismatched server record. */
        private val corruptWrap: Boolean = false,
        /** This device is revoked on the account: the first successful login reactivates it and says so once. */
        revoked: Boolean = false,
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

        override suspend fun login(accountId: String, authKey: ByteArray, device: DeviceInfo): SyncSession {
            if (expectedAuthKey != null && authKey.contentEquals(expectedAuthKey)) {
                return SyncSession(accountId, accessToken = "access", refreshToken = "refresh", reactivated = revoked.getAndSet(false))
            }
            throw SyncException(SyncException.Kind.UNAUTHORIZED, "wrong password") // server hides "no such account"
        }

        override suspend fun fetchWrappedDataKey(session: SyncSession): ByteArray {
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
        override suspend fun ping(): Boolean = true
        override suspend fun close() {
            closeCalls++
        }
        override suspend fun pull(session: SyncSession, since: Long): RecordPage = nope()
        override suspend fun push(session: SyncSession, records: List<RemoteRecord>): RecordPage = nope()
        override suspend fun listDevices(session: SyncSession): List<RemoteDevice> = nope()
        override suspend fun accountSummary(session: SyncSession): AccountSummary = nope()
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

    private fun coordinator(
        vault: Vault,
        client: SyncClient,
        configStore: SyncConfigStore = InMemorySyncConfigStore(),
    ): SyncCoordinator = SyncCoordinator(
        clientFactory = { client },
        crypto = crypto,
        vault = vault,
        configStore = configStore,
        deviceIdProvider = { "dev-local" },
        engineFactory = { _ -> SyncRunner { _ -> SyncOutcome(pulled = 0, pushed = 0, cursor = 0L) } },
    )

    /** Sync already configured for [account] on [serverUrl] (so [SyncCoordinator.changeAccountPassword] runs). */
    private fun configuredStore(): SyncConfigStore = InMemorySyncConfigStore().apply {
        save(SyncConfig(serverUrl, account, deviceId = "dev-local"))
    }

    /** A vault whose local re-wrap fails — models the client dying between the server rotate and the local re-wrap. */
    private class RewrapFailingVault(private val delegate: Vault) : Vault by delegate {
        override fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean {
            oldPassword.fill(' '); newPassword.fill(' ')
            return false
        }
    }

    @Test
    fun `joining an existing account under a different password pauses without changing the vault password`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val sut = coordinator(vault, FakeAccountClient(existingAccountPassword = accountPassword))
        try {
            sut.connect(serverUrl, account, accountPassword.toCharArray())
            sut.status.awaitStatus("the password-replace confirmation to be asked") { it is SyncStatus.NeedsPasswordReplaceConfirm }
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
            sut.status.awaitStatus("the password-replace confirmation to be asked") { it is SyncStatus.NeedsPasswordReplaceConfirm }
            sut.confirmPasswordReplace()
            sut.status.awaitStatus("the status to come Online") { it is SyncStatus.Online }
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
            sut.status.awaitStatus("the password-replace confirmation to be asked") { it is SyncStatus.NeedsPasswordReplaceConfirm }
            sut.cancelPasswordReplace()
            sut.status.awaitStatus("the status to come Disabled") { it is SyncStatus.Disabled }
            assertTrue(vault.verifyPassword(vaultPassword.toCharArray()), "local vault password must be untouched")
            assertFalse(vault.verifyPassword(accountPassword.toCharArray()), "account password must not unlock the vault")
        } finally {
            sut.close()
        }
    }

    @Test
    fun `dismissing the confirmation resolves the paused connect instead of only hiding it`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val sut = coordinator(vault, FakeAccountClient(existingAccountPassword = accountPassword))
        var closed = false
        try {
            sut.connect(serverUrl, account, accountPassword.toCharArray())
            sut.status.awaitStatus("the password-replace confirmation to be asked") { it is SyncStatus.NeedsPasswordReplaceConfirm }
            // Esc on the desktop modal (and leaving the mobile screen): it must decline the replace, not
            // leave the connect paused with the
            // kept password alive and the status stuck on NeedsPasswordReplaceConfirm ("Syncing…" forever).
            dismissPasswordReplace(sut) { closed = true }.invoke()
            sut.status.awaitStatus("the status to come Disabled") { it is SyncStatus.Disabled }
            assertTrue(closed, "the modal still closes")
            // Nothing left pending: a stale confirm (reopened dialog, queued tap) can't re-key the vault.
            sut.confirmPasswordReplace()
            assertTrue(sut.status.value is SyncStatus.Disabled, "a stale confirm must be a no-op, not a re-connect")
            assertTrue(vault.verifyPassword(vaultPassword.toCharArray()), "local vault password must be untouched")
        } finally {
            sut.close()
        }
    }

    /**
     * The account key can already BE this vault's key while the passwords have diverged: register under a
     * password, then change the vault password locally (Settings → Security). Reconnecting with the account
     * password then confirms a replace that [Vault.adoptDataKey] refuses to perform — it deliberately keeps
     * the meta when the key is unchanged — so without an explicit re-wrap the vault would keep unlocking
     * with the local password while the account stays on its own: exactly the divergence of issue #28,
     * only this time with the user's consent on screen.
     */
    @Test
    fun `confirming re-keys the vault even when the account key is already ours`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val ourKey = vault.exportDataKey()!!
        val client = FakeAccountClient(existingAccountPassword = accountPassword, accountDataKey = ourKey)
        val sut = coordinator(vault, client)
        try {
            // The vault password drifts away from the account password (Settings → Security).
            assertTrue(vault.changePassword(vaultPassword.toCharArray(), "changed-X".toCharArray()))
            sut.connect(serverUrl, account, accountPassword.toCharArray())
            sut.status.awaitStatus("the password-replace confirmation to be asked") { it is SyncStatus.NeedsPasswordReplaceConfirm }
            sut.confirmPasswordReplace()
            sut.status.awaitStatus("the connect to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(sut.status.value is SyncStatus.Online, "connects: the key is ours, only the wrap changes")
            assertTrue(vault.verifyPassword(accountPassword.toCharArray()), "the account password now unlocks the vault")
            assertFalse(vault.verifyPassword("changed-X".toCharArray()), "the old local password no longer unlocks")
        } finally {
            sut.close()
        }
    }

    /**
     * If the account's wrapped key can't be unwrapped, the local key stays — so the confirmed replace did
     * NOT happen. Connecting anyway would leave the user believing a password change they consented to
     * took effect, on a device whose records can't decrypt. Fail the connect instead.
     */
    @Test
    fun `confirming fails loudly when the account key cannot be adopted`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val client = FakeAccountClient(existingAccountPassword = accountPassword, corruptWrap = true)
        val sut = coordinator(vault, client)
        try {
            sut.connect(serverUrl, account, accountPassword.toCharArray())
            sut.status.awaitStatus("the password-replace confirmation to be asked") { it is SyncStatus.NeedsPasswordReplaceConfirm }
            sut.confirmPasswordReplace()
            sut.status.awaitStatus("the connect to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertEquals(
                SyncFailureReason.AccountKeyNotAdopted,
                (sut.status.value as? SyncStatus.Failed)?.reason,
                "must not report success on a replace that didn't happen, was ${sut.status.value}",
            )
            assertTrue(vault.verifyPassword(vaultPassword.toCharArray()), "the vault password is left as it was")
        } finally {
            sut.close()
        }
    }

    /**
     * Issue #133, the same refusal on the ordinary reconnect path (vault password == account password,
     * register → CONFLICT → login). The SRP login already proved the typed password IS the account
     * password, so a wrap that doesn't open means the account's records live under a dataKey this device
     * doesn't have (a corrupted or partially restored server record). Syncing anyway pulls records we
     * can't decrypt and pushes records the other devices can't — silently. Fail the connect instead.
     */
    @Test
    fun `reconnecting fails loudly when the account key cannot be adopted`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val client = FakeAccountClient(existingAccountPassword = vaultPassword, corruptWrap = true)
        val sut = coordinator(vault, client)
        try {
            sut.connect(serverUrl, account, vaultPassword.toCharArray())
            sut.status.awaitStatus("the connect to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertEquals(
                SyncFailureReason.AccountKeyNotAdopted,
                (sut.status.value as? SyncStatus.Failed)?.reason,
                "must not sync under a key that isn't the account's, was ${sut.status.value}",
            )
            assertTrue(vault.verifyPassword(vaultPassword.toCharArray()), "the vault password is left as it was")
            // Opened by this connect and adopted by nothing downstream: left behind it leaks a Ktor pool
            // per attempt.
            assertEquals(1, client.closeCalls, "the client must be closed on the way out")
        } finally {
            sut.close()
        }
    }

    /**
     * Issue #168 on the replace path. The login that only VERIFIES the typed password is a full SRP
     * verify: it reactivates this revoked device server-side and consumes the one-shot signal, and then
     * the connect pauses for the user's confirmation. By the time the confirmed re-run logs in again the
     * server sees an ordinary live device and reports nothing — so unless the paused connect carries the
     * signal across the confirmation, the rebuild the reactivation owes never happens and this device
     * pushes back the records the account purged while it was locked out.
     */
    @Test
    fun `a reactivation reported by the verify-only login survives the confirmation`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        // Held LIVE through the revocation — the server purged it in the meantime.
        vault.put("r1", RecordType.HOST, "stale".encodeToByteArray())
        val client = FakeAccountClient(existingAccountPassword = accountPassword, revoked = true)
        val store = InMemorySyncConfigStore()
        val sut = coordinator(vault, client, store)
        try {
            sut.connect(serverUrl, account, accountPassword.toCharArray())
            sut.status.awaitStatus("the password-replace confirmation to be asked") { it is SyncStatus.NeedsPasswordReplaceConfirm }
            // A password the user has only typed is not a link: nothing may be saved before they confirm.
            assertEquals(null, store.load(), "a paused connect must not save a link the user hasn't confirmed")

            sut.confirmPasswordReplace()
            sut.status.awaitStatus("the connect to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(sut.status.value is SyncStatus.Online, "was ${sut.status.value}")
            assertFalse(vault.records().any { it.id == "r1" }, "the reactivated device must rebuild from the server before it pushes")
            assertEquals(false, store.load()?.pendingReconcile, "the completed reconcile retires the marker")
        } finally {
            sut.close()
        }
    }

    /**
     * The other half of the same pause. Declining must leave the device exactly as it was — the verify
     * login was a password probe, not a link — so the reactivation carried across the pause has to live
     * with the paused connect and die with it, not on disk. Otherwise Cancel leaves the app "Configured"
     * for an account the user refused to join, and armed to wipe the vault whenever it does connect.
     */
    @Test
    fun `declining the confirmation leaves no link behind after a reactivating verify`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val client = FakeAccountClient(existingAccountPassword = accountPassword, revoked = true)
        val store = InMemorySyncConfigStore()
        val sut = coordinator(vault, client, store)
        try {
            sut.connect(serverUrl, account, accountPassword.toCharArray())
            sut.status.awaitStatus("the password-replace confirmation to be asked") { it is SyncStatus.NeedsPasswordReplaceConfirm }
            sut.cancelPasswordReplace()
            sut.status.awaitStatus("the status to come Disabled") { it is SyncStatus.Disabled }
            assertEquals(null, store.load(), "a declined connect must not leave a saved link")
        } finally {
            sut.close()
        }
    }

    /**
     * The pause is not a reason to withhold the marker from a link this device ALREADY has: recording it
     * there creates no new state and takes nothing back from the user, and the device owes the rebuild
     * whatever it decides about the password. Only the fields the reactivation is about may change —
     * the deviceId and the keep-connected token belong to a link that is still valid.
     */
    @Test
    fun `a reactivating verify marks the link this device already has`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val client = FakeAccountClient(existingAccountPassword = accountPassword, revoked = true)
        val linked = SyncConfig(serverUrl, account, deviceId = "dev-local", keepConnected = true, sealedRefreshToken = "sealed")
        val store = InMemorySyncConfigStore().apply { save(linked) }
        val sut = coordinator(vault, client, store)
        try {
            sut.connect(serverUrl, account, accountPassword.toCharArray())
            sut.status.awaitStatus("the password-replace confirmation to be asked") { it is SyncStatus.NeedsPasswordReplaceConfirm }
            assertEquals(
                linked.copy(pendingReconcile = true),
                store.load(),
                "the marker goes onto the existing link, and nothing else about it changes",
            )
        } finally {
            sut.close()
        }
    }

    /** A vault that refuses the re-wrap the confirmed replace needs (`VaultRekeyFailed`). */
    private class RewrapUnderFailingVault(private val delegate: Vault) : Vault by delegate {
        override fun rewrapUnder(password: CharArray): Boolean {
            password.fill(' ')
            return false
        }
    }

    /**
     * The third early return of issue #168: the confirmed replace logs in, the account key turns out to
     * already be ours, and re-wrapping the vault under the account password fails. The connect stops
     * there — but the reactivation the paused connect carried is already the server's last word on the
     * subject, so it must be on the device's link by then rather than die with the failed re-key. (The
     * device is linked here, which is what a rotation from another device leaves behind: the account
     * password moved on and this vault still holds the old one.)
     */
    @Test
    fun `a confirmed replace that fails on the re-key keeps the reactivation`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val client = FakeAccountClient(existingAccountPassword = accountPassword, accountDataKey = vault.exportDataKey(), revoked = true)
        val store = configuredStore()
        val sut = coordinator(RewrapUnderFailingVault(vault), client, store)
        try {
            sut.connect(serverUrl, account, accountPassword.toCharArray())
            sut.status.awaitStatus("the password-replace confirmation to be asked") { it is SyncStatus.NeedsPasswordReplaceConfirm }
            sut.confirmPasswordReplace()
            sut.status.awaitStatus("the connect to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertEquals(
                SyncFailureReason.VaultRekeyFailed,
                (sut.status.value as? SyncStatus.Failed)?.reason,
                "was ${sut.status.value}",
            )
            assertEquals(true, store.load()?.pendingReconcile, "the rebuild is owed even though the re-key failed")
        } finally {
            sut.close()
        }
    }

    /**
     * Recording the reactivation can fail — the config file is on a full disk — and that failure travels
     * out through the catch-all rather than a return of its own. The client the verify opened must not go
     * with it: nothing else holds a reference, so every retry would strand another socket pool.
     */
    @Test
    fun `a refused reactivation write still closes the client the verify opened`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val client = FakeAccountClient(existingAccountPassword = accountPassword, revoked = true)
        // The link is already there, so the reactivation has somewhere to be written — and that write fails.
        val linked = InMemorySyncConfigStore().apply { save(SyncConfig(serverUrl, account, deviceId = "dev-local")) }
        val store = object : SyncConfigStore by linked {
            override fun save(config: SyncConfig): Unit = error("config write failed")
        }
        val sut = coordinator(vault, client, store)
        try {
            sut.connect(serverUrl, account, accountPassword.toCharArray())
            sut.status.awaitStatus("the connect to fail on the refused write") { it is SyncStatus.Failed }
            assertEquals(1, client.closeCalls, "the verify's client must be closed even when the write throws")
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
            sut.status.awaitStatus("the status to come Online") { it is SyncStatus.Online }
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
            sut.status.awaitStatus("the connect to be refused as unauthorized") {
                it is SyncStatus.Failed && it.reason == SyncFailureReason.Unauthorized
            }
            assertFalse(client.registered, "must not register a divergent account under a non-vault password")
            assertTrue(vault.verifyPassword(vaultPassword.toCharArray()), "vault password is untouched")
        } finally {
            sut.close()
        }
    }

    // --- issue #32: change account password ---

    private val rotated = "rotated-E"

    @Test
    fun `changing the account password rotates it and re-keys the local vault`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault() // on a synced device the vault password IS the account password
        val client = FakeAccountClient(existingAccountPassword = vaultPassword, accountDataKey = vault.exportDataKey())
        val sut = coordinator(vault, client, configuredStore())
        try {
            val result = sut.changeAccountPassword(vaultPassword.toCharArray(), rotated.toCharArray())
            assertTrue(result is AccountPasswordChange.Success, "rotation succeeds, was $result")
            sut.status.awaitStatus("the status to come Online") { it is SyncStatus.Online }
            assertTrue(vault.verifyPassword(rotated.toCharArray()), "the vault now unlocks with the new password")
            assertFalse(vault.verifyPassword(vaultPassword.toCharArray()), "the old password no longer unlocks")
        } finally {
            sut.close()
        }
    }

    @Test
    fun `changing the account password with a wrong current password changes nothing`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val client = FakeAccountClient(existingAccountPassword = vaultPassword, accountDataKey = vault.exportDataKey())
        val sut = coordinator(vault, client, configuredStore())
        try {
            val result = sut.changeAccountPassword("not-the-password".toCharArray(), rotated.toCharArray())
            assertTrue(result is AccountPasswordChange.WrongCurrentPassword, "wrong current password is refused, was $result")
            assertTrue(vault.verifyPassword(vaultPassword.toCharArray()), "the vault password is untouched")
            assertFalse(vault.verifyPassword(rotated.toCharArray()), "the new password does not unlock")
        } finally {
            sut.close()
        }
    }

    @Test
    fun `changing the account password without sync configured is a no-op`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val client = FakeAccountClient(existingAccountPassword = vaultPassword)
        val sut = coordinator(vault, client) // no configStore → not configured
        try {
            val result = sut.changeAccountPassword(vaultPassword.toCharArray(), rotated.toCharArray())
            assertTrue(result is AccountPasswordChange.NotConfigured, "nothing to rotate, was $result")
            assertTrue(vault.verifyPassword(vaultPassword.toCharArray()), "the vault password is untouched")
        } finally {
            sut.close()
        }
    }

    /**
     * The failure the issue calls out: the client dies between the server rotate (step 3) and the local
     * re-wrap (step 4). The account must end up on the new password (the server committed atomically),
     * and the device must heal on its next reconnect via the confirmed-replace path (#28) — never a
     * half-applied state that locks the user out.
     */
    @Test
    fun `an interrupted rotation leaves the account on the new password and heals on reconnect`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val client = FakeAccountClient(existingAccountPassword = vaultPassword, accountDataKey = vault.exportDataKey())
        val store = configuredStore()

        // The server rotates, but the local re-wrap fails (client "dies" between step 3 and 4).
        val sut = coordinator(RewrapFailingVault(vault), client, store)
        try {
            val result = sut.changeAccountPassword(vaultPassword.toCharArray(), rotated.toCharArray())
            assertTrue(result is AccountPasswordChange.LocalRewrapFailed, "the interruption is reported, not a silent success, was $result")
            assertTrue(vault.verifyPassword(vaultPassword.toCharArray()), "the local vault is still on the old password")
        } finally {
            sut.close()
        }

        // Reconnecting with the NEW password heals the local wrap (the account already expects it).
        val heal = coordinator(vault, client, store)
        try {
            heal.connect(serverUrl, account, rotated.toCharArray())
            heal.status.awaitStatus("the password-replace confirmation to be asked") { it is SyncStatus.NeedsPasswordReplaceConfirm }
            heal.confirmPasswordReplace()
            heal.status.awaitStatus("the connect to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(heal.status.value is SyncStatus.Online, "reconnect with the new password heals the device, was ${heal.status.value}")
            assertTrue(vault.verifyPassword(rotated.toCharArray()), "the local vault now unlocks with the new password")
        } finally {
            heal.close()
        }
    }

    /**
     * On a keep-connected device an interrupted rotation must drop the saved refresh token, or
     * [SyncCoordinator.restoreSession] would silently bring the device back Online under the OLD
     * password on the next launch — the account moved to the new one, and the leaked old password
     * would keep unlocking this device (issue #32 divergence).
     */
    @Test
    fun `an interrupted rotation on a keep-connected device clears the auto-restore token`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val client = FakeAccountClient(existingAccountPassword = vaultPassword, accountDataKey = vault.exportDataKey())
        val store = InMemorySyncConfigStore().apply {
            save(SyncConfig(serverUrl, account, deviceId = "dev-local", keepConnected = true, sealedRefreshToken = "sealed-old"))
        }
        val sut = coordinator(RewrapFailingVault(vault), client, store)
        try {
            val result = sut.changeAccountPassword(vaultPassword.toCharArray(), rotated.toCharArray())
            assertTrue(result is AccountPasswordChange.LocalRewrapFailed, "was $result")
            assertEquals(null, store.load()?.sealedRefreshToken, "the auto-restore token must be cleared after an interrupted rotation")
        } finally {
            sut.close()
        }
    }

    /** Delegates everything but the rotation, which the server turns away with [kind]. */
    private class RotationRejectingClient(
        inner: SyncClient,
        private val kind: SyncException.Kind,
    ) : SyncClient by inner {
        override suspend fun changePassword(
            accountId: String,
            currentAuthKey: ByteArray,
            newAuthKey: ByteArray,
            newWrappedDataKey: ByteArray,
            device: DeviceInfo,
        ): SyncSession = throw SyncException(kind, "rejected")
    }

    /**
     * The rate limiter turns the rotation away before the route runs, so nothing rotated: the
     * keep-connected token must survive. Clearing it (the treatment ambiguous failures get) would
     * cost the user their auto-restore for a failure that changed nothing on the server — and 429
     * is the one a user hits by simply retrying a mistyped password a few times.
     */
    @Test
    fun `a throttled rotation keeps the auto-restore token and is named as throttling`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val client = FakeAccountClient(existingAccountPassword = vaultPassword, accountDataKey = vault.exportDataKey())
        val store = InMemorySyncConfigStore().apply {
            save(SyncConfig(serverUrl, account, deviceId = "dev-local", keepConnected = true, sealedRefreshToken = "sealed-old"))
        }
        val sut = coordinator(vault, RotationRejectingClient(client, SyncException.Kind.TOO_MANY_REQUESTS), store)
        try {
            val result = sut.changeAccountPassword(vaultPassword.toCharArray(), rotated.toCharArray())
            assertEquals(
                SyncFailureReason.TooManyRequests,
                (result as? AccountPasswordChange.Failed)?.reason,
                "a throttled rotation must be named as throttling, was $result",
            )
            assertEquals("sealed-old", store.load()?.sealedRefreshToken, "nothing rotated — the auto-restore token stays")
            assertTrue(vault.verifyPassword(vaultPassword.toCharArray()), "the local vault is untouched")
        } finally {
            sut.close()
        }
    }

    /**
     * A 5xx is ambiguous — the server may have committed the rotation before dying — so it keeps the
     * conservative treatment (token cleared), but it must still be named as a server failure.
     */
    @Test
    fun `a rotation against a broken server is named as a server failure`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val client = FakeAccountClient(existingAccountPassword = vaultPassword, accountDataKey = vault.exportDataKey())
        val store = InMemorySyncConfigStore().apply {
            save(SyncConfig(serverUrl, account, deviceId = "dev-local", keepConnected = true, sealedRefreshToken = "sealed-old"))
        }
        val sut = coordinator(vault, RotationRejectingClient(client, SyncException.Kind.SERVER_ERROR), store)
        try {
            val result = sut.changeAccountPassword(vaultPassword.toCharArray(), rotated.toCharArray())
            assertEquals(
                SyncFailureReason.ServerError,
                (result as? AccountPasswordChange.Failed)?.reason,
                "was $result",
            )
            assertEquals(null, store.load()?.sealedRefreshToken, "an ambiguous failure still clears the auto-restore token")
        } finally {
            sut.close()
        }
    }

    /**
     * The current password passes the local check but the SERVER rejects the SRP proof (e.g. another
     * device already rotated the account): the coordinator surfaces it as a failure, unchanged locally.
     */
    @Test
    fun `a server-rejected current password surfaces as a failure without changing the vault`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        // Account exists under a DIFFERENT password than this vault's: local verifyPassword passes,
        // but the server's SRP proof of the current password fails.
        val client = FakeAccountClient(existingAccountPassword = accountPassword)
        val sut = coordinator(vault, client, configuredStore())
        try {
            val result = sut.changeAccountPassword(vaultPassword.toCharArray(), rotated.toCharArray())
            assertTrue(
                result is AccountPasswordChange.Failed && result.reason == SyncFailureReason.Unauthorized,
                "server rejection maps to Failed(Unauthorized), was $result",
            )
            assertTrue(vault.verifyPassword(vaultPassword.toCharArray()), "the vault password is untouched")
            assertFalse(vault.verifyPassword(rotated.toCharArray()), "the new password does not unlock")
        } finally {
            sut.close()
        }
    }
}
