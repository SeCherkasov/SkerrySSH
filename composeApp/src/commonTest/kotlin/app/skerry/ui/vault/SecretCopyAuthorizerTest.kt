package app.skerry.ui.vault

import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.MergeResult
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.SyncMeta
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Password path of [SecretCopyAuthorizer] (biometrics == null, as on desktop or mobile without
 * biometrics enabled): the request shows a password form, and copy only runs after a successful
 * check via [Vault.verifyPassword]. The check runs in a coroutine (off-thread KDF), so tests
 * advance virtual time. The biometric path is covered at the core level
 * (`VaultBiometricsTest.confirm…`) and isn't duplicated here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecretCopyAuthorizerTest {

    private class FakeVault(private val correct: String) : Vault {
        override fun verifyPassword(password: CharArray): Boolean = password.concatToString() == correct

        override fun exists(): Boolean = true
        override val isUnlocked: Boolean = true
        override fun create(password: CharArray) = Unit
        override fun unlock(password: CharArray): UnlockResult = UnlockResult.Success
        override fun unlockWithDataKey(dataKey: DataKey): UnlockResult = UnlockResult.Success
        override fun exportDataKey(): DataKey? = null
        override fun adoptDataKey(newDataKey: DataKey, password: CharArray): Boolean = false
        override fun lock() = Unit
        override fun reset() = Unit
        override fun records(): List<VaultRecord> = emptyList()
        override fun syncMeta(): SyncMeta? = null
        override fun mergeRemote(remote: List<VaultRecord>): MergeResult = MergeResult.EMPTY
        override fun openPayload(id: String): ByteArray? = null
        override fun put(id: String, type: RecordType, payload: ByteArray) = Unit
        override fun remove(id: String) = Unit
        override fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean = true
    }

    @Test
    fun `authorize without biometrics opens the password form and defers the action`() = runTest {
        var copied = false
        val auth = SecretCopyAuthorizer(
            FakeVault("master"), biometrics = null, scope = this,
            kdfDispatcher = StandardTestDispatcher(testScheduler),
        )

        auth.authorize { copied = true }

        assertTrue(auth.passwordPromptVisible)
        assertFalse(auth.passwordError)
        assertFalse(copied, "copying is deferred until password confirmation")
    }

    @Test
    fun `correct password runs the deferred copy and closes the form`() = runTest {
        var copied = false
        val auth = SecretCopyAuthorizer(
            FakeVault("master"), biometrics = null, scope = this,
            kdfDispatcher = StandardTestDispatcher(testScheduler),
        )
        auth.authorize { copied = true }

        auth.submitPassword("master")
        advanceUntilIdle()

        assertTrue(copied)
        assertFalse(auth.passwordPromptVisible)
        assertFalse(auth.passwordError)
        assertFalse(auth.verifying)
    }

    @Test
    fun `wrong password flags an error and keeps the action pending`() = runTest {
        var copied = false
        val auth = SecretCopyAuthorizer(
            FakeVault("master"), biometrics = null, scope = this,
            kdfDispatcher = StandardTestDispatcher(testScheduler),
        )
        auth.authorize { copied = true }

        auth.submitPassword("nope")
        advanceUntilIdle()

        assertFalse(copied)
        assertTrue(auth.passwordError)
        assertTrue(auth.passwordPromptVisible, "the form stays open for a retry")

        // A subsequent correct attempt after an error still copies.
        auth.submitPassword("master")
        advanceUntilIdle()
        assertTrue(copied)
        assertFalse(auth.passwordPromptVisible)
    }

    @Test
    fun `dismiss drops the pending action`() = runTest {
        var copied = false
        val auth = SecretCopyAuthorizer(
            FakeVault("master"), biometrics = null, scope = this,
            kdfDispatcher = StandardTestDispatcher(testScheduler),
        )
        auth.authorize { copied = true }

        auth.dismiss()

        assertFalse(auth.passwordPromptVisible)
        // After dismiss, even the correct password copies nothing — the action was dropped.
        auth.submitPassword("master")
        advanceUntilIdle()
        assertFalse(copied)
    }
}
