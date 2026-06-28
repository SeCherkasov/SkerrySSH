package app.skerry.ui.vault

import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.RecordType
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
 * Парольный путь [SecretCopyAuthorizer] (biometrics == null — как desktop, и мобильный путь без
 * включённой биометрии): запрос показывает форму пароля, копирование происходит только после
 * верной сверки через [Vault.verifyPassword]. Сверка идёт в корутине (off-thread KDF), поэтому
 * тесты продвигают виртуальное время. Биометрический путь покрыт на уровне ядра
 * (`VaultBiometricsTest.confirm…`), здесь не дублируется.
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
        override fun lock() = Unit
        override fun reset() = Unit
        override fun records(): List<VaultRecord> = emptyList()
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
        assertFalse(copied, "копирование откладывается до подтверждения паролем")
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
        assertTrue(auth.passwordPromptVisible, "форма остаётся открытой для повторной попытки")

        // Повторная верная попытка после ошибки всё-таки копирует.
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
        // После отмены даже верный пароль ничего не копирует — действие сброшено.
        auth.submitPassword("master")
        advanceUntilIdle()
        assertFalse(copied)
    }
}
