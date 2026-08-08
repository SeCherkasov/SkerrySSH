package app.skerry.ui.vault

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `authorize without biometrics opens the password form and defers the action`() = runTest {
        var copied = false
        val auth = SecretCopyAuthorizer(
            FakeUnlockedVault("master"), biometrics = null, scope = this,
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
            FakeUnlockedVault("master"), biometrics = null, scope = this,
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
            FakeUnlockedVault("master"), biometrics = null, scope = this,
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
    fun `an export is gated by the same check and words the prompt for itself`() = runTest {
        var exported = false
        val auth = SecretCopyAuthorizer(
            FakeUnlockedVault("master"), biometrics = null, scope = this,
            kdfDispatcher = StandardTestDispatcher(testScheduler),
        )

        auth.authorize(SecretAccess.EXPORT) { exported = true }

        assertEquals(SecretAccess.EXPORT, auth.access, "the dialog reads this to name the action")
        assertTrue(auth.passwordPromptVisible)
        assertFalse(exported, "a private key leaves the vault only after the password check")

        auth.submitPassword("master")
        advanceUntilIdle()
        assertTrue(exported)
    }

    @Test
    fun `a copy after an export words the prompt back for a copy`() = runTest {
        val auth = SecretCopyAuthorizer(
            FakeUnlockedVault("master"), biometrics = null, scope = this,
            kdfDispatcher = StandardTestDispatcher(testScheduler),
        )
        auth.authorize(SecretAccess.EXPORT) {}
        auth.dismiss()

        auth.authorize { }

        assertEquals(SecretAccess.COPY, auth.access)
    }

    @Test
    fun `dismiss drops the pending action`() = runTest {
        var copied = false
        val auth = SecretCopyAuthorizer(
            FakeUnlockedVault("master"), biometrics = null, scope = this,
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
