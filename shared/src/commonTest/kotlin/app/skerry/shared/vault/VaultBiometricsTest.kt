package app.skerry.shared.vault

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Тесты оркестрации биометрии [VaultBiometrics] на настоящем [FileVault] + [IonspinVaultCrypto] +
 * [FakeFileSystem] (как [FileVaultTest]), но с [FakeBiometricKeyStore] вместо железа. Проверяем
 * поведение, видимое снаружи: end-to-end разблокировка тем же `dataKey` (запись, положенная до
 * включения, читается после биометрической разблокировки на свежем инстансе), развязку от смены
 * пароля, мягкие откаты и инвалидацию. Реальные `actual` (Keystore/Keychain) — ручная проверка.
 */
class VaultBiometricsTest {

    private val crypto: VaultCrypto = IonspinVaultCrypto()
    private val fs = FakeFileSystem()
    private val vaultPath = "/vault.json".toPath()
    private val bioPath = "/vault.bio".toPath()
    private val prompt = BiometricPrompt(title = "Unlock", cancelLabel = "Cancel")
    private val secret = "ssh-key-payload".encodeToByteArray()

    private fun vault() = FileVault(vaultPath, crypto, deviceId = "device-1", fileSystem = fs, now = { "2026-06-12T00:00:00Z" })
    private fun artifacts() = FileBioArtifactStore(bioPath, fs)
    private fun biometrics(v: Vault, keyStore: FakeBiometricKeyStore) =
        VaultBiometrics(v, keyStore, artifacts(), deviceId = "device-1")

    private fun bioTest(block: suspend () -> Unit): TestResult = runTest {
        initializeVaultCrypto()
        block()
    }

    @Test
    fun `enable then biometric unlock on a fresh vault opens the same record`() = bioTest {
        val keyStore = FakeBiometricKeyStore()
        // Создаём vault, кладём секрет, включаем биометрию.
        run {
            val v = vault()
            v.create("master-pass".toCharArray())
            v.put("id-1", RecordType.IDENTITY, secret)
            assertEquals(BiometricEnableResult.Enabled, biometrics(v, keyStore).enable(prompt))
        }
        assertTrue(artifacts().exists(), "vault.bio должен появиться")

        // Холодный старт: новый инстанс, разблокируем биометрией, читаем секрет.
        val fresh = vault()
        assertFalse(fresh.isUnlocked)
        assertEquals(BiometricUnlockResult.Unlocked, biometrics(fresh, keyStore).unlock(prompt))
        assertTrue(fresh.isUnlocked)
        assertContentEquals(secret, fresh.openPayload("id-1"))
    }

    @Test
    fun `stored bio wrapping is not the raw data key`() = bioTest {
        val keyStore = FakeBiometricKeyStore()
        val v = vault()
        v.create("master-pass".toCharArray())
        val exported = v.exportDataKey()!!.bytes // для сравнения; затрём ниже
        biometrics(v, keyStore).enable(prompt)

        val wrapped = artifacts().read()!!.wrappedBio
        assertFalse(wrapped.contentEquals(exported), "на диск должна уходить обёртка, а не сам dataKey")
        exported.fill(0)
    }

    @Test
    fun `enable while vault is locked reports VaultLocked and writes nothing`() = bioTest {
        val keyStore = FakeBiometricKeyStore()
        vault().create("master-pass".toCharArray()) // файл есть, но этот инстанс заблокирован
        val locked = vault()

        assertEquals(BiometricEnableResult.VaultLocked, biometrics(locked, keyStore).enable(prompt))
        assertFalse(artifacts().exists())
    }

    @Test
    fun `enable when biometrics unavailable reports Unavailable`() = bioTest {
        val keyStore = FakeBiometricKeyStore(currentAvailability = BiometricAvailability.NotEnrolled)
        val v = vault().also { it.create("master-pass".toCharArray()) }

        assertEquals(BiometricEnableResult.Unavailable, biometrics(v, keyStore).enable(prompt))
        assertFalse(artifacts().exists())
    }

    @Test
    fun `cancelled enable writes no artifact`() = bioTest {
        val keyStore = FakeBiometricKeyStore(nextWrap = BiometricOutcome.Cancelled)
        val v = vault().also { it.create("master-pass".toCharArray()) }

        assertEquals(BiometricEnableResult.Cancelled, biometrics(v, keyStore).enable(prompt))
        assertFalse(artifacts().exists())
    }

    @Test
    fun `biometric unlock when not enabled reports NotEnabled`() = bioTest {
        val keyStore = FakeBiometricKeyStore()
        vault().create("master-pass".toCharArray())

        assertEquals(BiometricUnlockResult.NotEnabled, biometrics(vault(), keyStore).unlock(prompt))
    }

    @Test
    fun `cancelled biometric unlock leaves vault locked`() = bioTest {
        val keyStore = FakeBiometricKeyStore()
        run {
            val v = vault().also { it.create("master-pass".toCharArray()) }
            biometrics(v, keyStore).enable(prompt)
        }
        keyStore.nextUnwrap = BiometricOutcome.Cancelled

        val fresh = vault()
        assertEquals(BiometricUnlockResult.Cancelled, biometrics(fresh, keyStore).unlock(prompt))
        assertFalse(fresh.isUnlocked)
    }

    @Test
    fun `invalidated key disables biometrics and demands password`() = bioTest {
        val keyStore = FakeBiometricKeyStore()
        run {
            val v = vault().also { it.create("master-pass".toCharArray()) }
            biometrics(v, keyStore).enable(prompt)
        }
        assertTrue(artifacts().exists())
        keyStore.nextUnwrap = BiometricOutcome.Invalidated

        val fresh = vault()
        assertEquals(BiometricUnlockResult.Invalidated, biometrics(fresh, keyStore).unlock(prompt))
        assertFalse(fresh.isUnlocked)
        assertFalse(artifacts().exists(), "инвалидация должна снять биометрию")
    }

    @Test
    fun `changing master password keeps biometric unlock working`() = bioTest {
        val keyStore = FakeBiometricKeyStore()
        run {
            val v = vault().also { it.create("master-pass".toCharArray()) }
            v.put("id-1", RecordType.IDENTITY, secret)
            biometrics(v, keyStore).enable(prompt)
            assertTrue(v.changePassword("master-pass".toCharArray(), "brand-new-pass".toCharArray()))
        }

        // vault.bio не трогался сменой пароля — биометрия открывает тот же dataKey и запись.
        val fresh = vault()
        assertEquals(BiometricUnlockResult.Unlocked, biometrics(fresh, keyStore).unlock(prompt))
        assertContentEquals(secret, fresh.openPayload("id-1"))
    }

    @Test
    fun `biometric unlock reports Corrupted when vault file is unreadable`() = bioTest {
        val keyStore = FakeBiometricKeyStore()
        run {
            val v = vault().also { it.create("master-pass".toCharArray()) }
            biometrics(v, keyStore).enable(prompt)
        }
        // Биометрия развернёт ключ, но сам vault.json уже битый.
        fs.write(vaultPath) { writeUtf8("{ not valid vault json") }

        val fresh = vault()
        assertEquals(BiometricUnlockResult.Corrupted, biometrics(fresh, keyStore).unlock(prompt))
        assertFalse(fresh.isUnlocked)
    }

    @Test
    fun `artifact from another device is ignored and falls back to password`() = bioTest {
        val keyStore = FakeBiometricKeyStore()
        run {
            val v = vault().also { it.create("master-pass".toCharArray()) }
            biometrics(v, keyStore).enable(prompt)
        }
        // Подменяем deviceId в vault.bio на чужой — оркестратор не должен ему доверять.
        val tampered = artifacts().read()!!.copy(deviceId = "other-device")
        artifacts().write(tampered)

        val fresh = vault()
        assertEquals(BiometricUnlockResult.NotEnabled, biometrics(fresh, keyStore).unlock(prompt))
        assertFalse(fresh.isUnlocked)
    }

    @Test
    fun `confirm succeeds with enrolled biometrics and does not unlock the vault`() = bioTest {
        val keyStore = FakeBiometricKeyStore()
        run {
            val v = vault().also { it.create("master-pass".toCharArray()) }
            biometrics(v, keyStore).enable(prompt)
        }

        // Свежий (заблокированный) инстанс: confirm доказывает присутствие, но vault не открывает.
        val fresh = vault()
        assertEquals(BiometricConfirmResult.Confirmed, biometrics(fresh, keyStore).confirm(prompt))
        assertFalse(fresh.isUnlocked, "confirm не должен разблокировать vault")
    }

    @Test
    fun `confirm when biometrics not enabled reports NotEnabled`() = bioTest {
        val keyStore = FakeBiometricKeyStore()
        vault().create("master-pass".toCharArray())

        assertEquals(BiometricConfirmResult.NotEnabled, biometrics(vault(), keyStore).confirm(prompt))
    }

    @Test
    fun `cancelled confirm reports Cancelled`() = bioTest {
        val keyStore = FakeBiometricKeyStore()
        run {
            val v = vault().also { it.create("master-pass".toCharArray()) }
            biometrics(v, keyStore).enable(prompt)
        }
        keyStore.nextUnwrap = BiometricOutcome.Cancelled

        assertEquals(BiometricConfirmResult.Cancelled, biometrics(vault(), keyStore).confirm(prompt))
    }

    @Test
    fun `invalidated key during confirm disables biometrics`() = bioTest {
        val keyStore = FakeBiometricKeyStore()
        run {
            val v = vault().also { it.create("master-pass".toCharArray()) }
            biometrics(v, keyStore).enable(prompt)
        }
        assertTrue(artifacts().exists())
        keyStore.nextUnwrap = BiometricOutcome.Invalidated

        assertEquals(BiometricConfirmResult.Invalidated, biometrics(vault(), keyStore).confirm(prompt))
        assertFalse(artifacts().exists(), "инвалидация должна снять биометрию")
    }

    @Test
    fun `disable removes artifact and key`() = bioTest {
        val keyStore = FakeBiometricKeyStore()
        val orchestrator = run {
            val v = vault().also { it.create("master-pass".toCharArray()) }
            biometrics(v, keyStore).also { it.enable(prompt) }
        }
        assertTrue(artifacts().exists())

        orchestrator.disable()

        assertFalse(artifacts().exists())
        assertTrue(keyStore.deletedAliases.isNotEmpty())
    }

    @Test
    fun `exportDataKey returns null while locked and a copy while unlocked`() = bioTest {
        val v = vault()
        assertNull(v.exportDataKey())

        v.create("master-pass".toCharArray())
        val a = v.exportDataKey()!!.bytes
        val b = v.exportDataKey()!!.bytes
        assertContentEquals(a, b, "копии равны по содержимому")
        a.fill(0)
        assertFalse(a.contentEquals(b), "это независимые копии — затирание одной не трогает другую")
        b.fill(0)
    }
}
