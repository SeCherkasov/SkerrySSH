package app.skerry.ui.vault

import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultGateControllerTest {

    @Test
    fun `starts in NeedsCreate when no vault file exists`() {
        val controller = VaultGateController(FakeVault(exists = false))

        assertEquals(VaultGateState.NeedsCreate, controller.state)
        assertNull(controller.error)
    }

    @Test
    fun `starts in NeedsUnlock when a vault file already exists`() {
        val controller = VaultGateController(FakeVault(exists = true))

        assertEquals(VaultGateState.NeedsUnlock, controller.state)
    }

    @Test
    fun `create with matching passwords creates the vault and unlocks`() {
        val vault = FakeVault(exists = false)
        val controller = VaultGateController(vault, minPasswordLength = 8)

        controller.create("correct horse".toCharArray(), "correct horse".toCharArray())

        assertEquals(VaultGateState.Unlocked, controller.state)
        assertNull(controller.error)
        assertEquals(1, vault.createCalls)
    }

    @Test
    fun `create rejects a password shorter than the minimum without touching the vault`() {
        val vault = FakeVault(exists = false)
        val controller = VaultGateController(vault, minPasswordLength = 8)

        controller.create("short".toCharArray(), "short".toCharArray())

        assertEquals(VaultGateState.NeedsCreate, controller.state)
        assertEquals(VaultGateError.PasswordTooShort, controller.error)
        assertEquals(0, vault.createCalls)
    }

    @Test
    fun `create rejects mismatched confirmation without touching the vault`() {
        val vault = FakeVault(exists = false)
        val controller = VaultGateController(vault, minPasswordLength = 8)

        controller.create("correct horse".toCharArray(), "correct house".toCharArray())

        assertEquals(VaultGateState.NeedsCreate, controller.state)
        assertEquals(VaultGateError.PasswordMismatch, controller.error)
        assertEquals(0, vault.createCalls)
    }

    @Test
    fun `create zeroes both password buffers`() {
        val controller = VaultGateController(FakeVault(exists = false), minPasswordLength = 8)
        val password = "correct horse".toCharArray()
        val confirm = "correct horse".toCharArray()

        controller.create(password, confirm)

        assertTrue(password.all { it == ' ' }, "password buffer must be wiped")
        assertTrue(confirm.all { it == ' ' }, "confirm buffer must be wiped")
    }

    @Test
    fun `create zeroes buffers even when validation fails`() {
        val controller = VaultGateController(FakeVault(exists = false), minPasswordLength = 8)
        val password = "correct horse".toCharArray()
        val confirm = "mismatch here".toCharArray()

        controller.create(password, confirm)

        assertTrue(password.all { it == ' ' })
        assertTrue(confirm.all { it == ' ' })
    }

    @Test
    fun `unlock with the right password unlocks`() {
        val vault = FakeVault(exists = true, unlockResult = UnlockResult.Success)
        val controller = VaultGateController(vault)

        controller.unlock("correct horse".toCharArray())

        assertEquals(VaultGateState.Unlocked, controller.state)
        assertNull(controller.error)
    }

    @Test
    fun `unlock with a wrong password stays on the unlock screen with an error`() {
        val vault = FakeVault(exists = true, unlockResult = UnlockResult.WrongPassword)
        val controller = VaultGateController(vault)

        controller.unlock("nope".toCharArray())

        assertEquals(VaultGateState.NeedsUnlock, controller.state)
        assertEquals(VaultGateError.WrongPassword, controller.error)
    }

    @Test
    fun `unlock zeroes the password buffer even when the vault throws`() {
        // vault бросает и НЕ затирает буфер сам — затирание докажет finally контроллера.
        val controller = VaultGateController(FakeVault(exists = true, unlockThrows = true))
        val password = "secret password".toCharArray()

        assertFailsWith<IllegalStateException> { controller.unlock(password) }

        assertTrue(password.all { it == ' ' }, "password buffer must be wiped on the exception path")
    }

    @Test
    fun `unlock surfaces a corrupted vault file`() {
        val vault = FakeVault(exists = true, unlockResult = UnlockResult.Corrupted)
        val controller = VaultGateController(vault)

        controller.unlock("whatever".toCharArray())

        assertEquals(VaultGateState.NeedsUnlock, controller.state)
        assertEquals(VaultGateError.Corrupted, controller.error)
    }

    @Test
    fun `a successful attempt clears a previous error`() {
        val vault = FakeVault(exists = true, unlockResult = UnlockResult.WrongPassword)
        val controller = VaultGateController(vault)
        controller.unlock("nope".toCharArray())
        assertEquals(VaultGateError.WrongPassword, controller.error)

        vault.unlockResult = UnlockResult.Success
        controller.unlock("correct horse".toCharArray())

        assertNull(controller.error)
        assertEquals(VaultGateState.Unlocked, controller.state)
    }

    @Test
    fun `lock returns to the unlock screen and locks the vault`() {
        val vault = FakeVault(exists = true, unlockResult = UnlockResult.Success)
        val controller = VaultGateController(vault)
        controller.unlock("correct horse".toCharArray())

        controller.lock()

        assertEquals(VaultGateState.NeedsUnlock, controller.state)
        assertEquals(1, vault.lockCalls)
    }
}

/**
 * In-memory [Vault] для тестов гейта: моделирует жизненный цикл create/unlock/lock и затирание
 * переданного пароля (как у файловой реализации). CRUD не задействован контроллером гейта.
 */
private class FakeVault(
    exists: Boolean,
    var unlockResult: UnlockResult = UnlockResult.Success,
    private val unlockThrows: Boolean = false,
) : Vault {
    private var fileExists = exists
    override var isUnlocked = false
        private set
    var createCalls = 0
        private set
    var lockCalls = 0
        private set

    override fun exists(): Boolean = fileExists

    override fun create(password: CharArray) {
        createCalls++
        fileExists = true
        isUnlocked = true
        password.fill(' ')
    }

    override fun unlock(password: CharArray): UnlockResult {
        // Реальная реализация затирает буфер сама; в режиме unlockThrows моделируем сбой ДО
        // затирания, чтобы проверить, что буфер гасит finally контроллера.
        if (unlockThrows) error("unlock failed")
        password.fill(' ')
        if (unlockResult == UnlockResult.Success) isUnlocked = true
        return unlockResult
    }

    override fun lock() {
        lockCalls++
        isUnlocked = false
    }

    override fun records(): List<VaultRecord> = emptyList()
    override fun openPayload(id: String): ByteArray? = null
    override fun put(id: String, type: RecordType, payload: ByteArray) = Unit
    override fun remove(id: String) = Unit
    override fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean = false

    // Путь биометрии гейтом не тестируется — стабы.
    override fun unlockWithDataKey(dataKey: DataKey): UnlockResult = UnlockResult.Corrupted
    override fun exportDataKey(): DataKey? = null
}
