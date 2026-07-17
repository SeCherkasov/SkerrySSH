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
 * Tests [VaultBiometrics] orchestration against a real [FileVault] + [IonspinVaultCrypto] +
 * [FakeFileSystem] (as in [FileVaultTest]), with [FakeBiometricKeyStore] standing in for hardware.
 * Covers externally visible behavior: end-to-end unlock with the same `dataKey` (a record written
 * before enabling reads back after a biometric unlock on a fresh instance), independence from
 * password changes, graceful fallback, and invalidation. Real `actual` implementations
 * (Keystore/Keychain) are verified manually.
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
    fun `bio artifact write hardens the tmp file before the move`() = bioTest {
        // The harden hook must run on the tmp file before atomicMove, closing the umask permission window.
        val hardened = mutableListOf<String>()
        val store = FileBioArtifactStore(bioPath, fs, harden = { hardened += it.toString() })

        store.write(BioArtifact(1, "alias", "device-1", byteArrayOf(1, 2, 3)))

        assertEquals(listOf("/vault.bio.tmp"), hardened)
        assertTrue(fs.exists(bioPath))
        assertFalse(fs.exists("/vault.bio.tmp".toPath()), "tmp should be renamed to the target")
    }

    @Test
    fun `enable then biometric unlock on a fresh vault opens the same record`() = bioTest {
        val keyStore = FakeBiometricKeyStore()
        // Create the vault, store a secret, enable biometrics.
        run {
            val v = vault()
            v.create("master-pass".toCharArray())
            v.put("id-1", RecordType.IDENTITY, secret)
            assertEquals(BiometricEnableResult.Enabled, biometrics(v, keyStore).enable(prompt))
        }
        assertTrue(artifacts().exists(), "vault.bio should appear")

        // Cold start: new instance, unlock via biometrics, read the secret.
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
        val exported = v.exportDataKey()!!.bytes // for comparison; zeroed below
        biometrics(v, keyStore).enable(prompt)

        val wrapped = artifacts().read()!!.wrappedBio
        assertFalse(wrapped.contentEquals(exported), "the wrap should go to disk, not the dataKey itself")
        exported.fill(0)
    }

    @Test
    fun `enable while vault is locked reports VaultLocked and writes nothing`() = bioTest {
        val keyStore = FakeBiometricKeyStore()
        vault().create("master-pass".toCharArray()) // file exists, but this instance is locked
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
        assertFalse(artifacts().exists(), "invalidation should disable biometrics")
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

        // vault.bio is untouched by the password change; biometrics unlocks the same dataKey and record.
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
        // Biometrics will unwrap the key fine, but vault.json itself is corrupt.
        fs.write(vaultPath) { writeUtf8("{ not valid vault json") }

        val fresh = vault()
        assertEquals(BiometricUnlockResult.Corrupted, biometrics(fresh, keyStore).unlock(prompt))
        assertFalse(fresh.isUnlocked)
    }

    @Test
    fun `obsolete bio artifact version is migrated away and reported as a reset`() = bioTest {
        // A vault.bio written by a superseded key scheme (older formatVersion) can't be unwrapped by
        // the current bioKey (see #23: the pre-time-bound per-operation key never authorized on some
        // OEM ROMs). Unlock must clear it — delete the stale key and artifact — and report Invalidated
        // (the "biometrics were reset — use your password" flow) so an upgrading user gets feedback
        // instead of a silent no-op, then re-enables biometrics on the current scheme.
        val keyStore = FakeBiometricKeyStore()
        run {
            val v = vault().also { it.create("master-pass".toCharArray()) }
            biometrics(v, keyStore).enable(prompt)
        }
        val current = artifacts().read()!!
        artifacts().write(current.copy(formatVersion = current.formatVersion - 1)) // simulate the old scheme

        val fresh = vault()
        assertEquals(BiometricUnlockResult.Invalidated, biometrics(fresh, keyStore).unlock(prompt))
        assertFalse(fresh.isUnlocked)
        assertFalse(artifacts().exists(), "the obsolete enrollment should be cleared so the user re-enables")
        assertTrue(keyStore.deletedAliases.isNotEmpty(), "the stale key must be deleted")
    }

    @Test
    fun `bio artifact from a newer format version is left untouched`() = bioTest {
        // A future formatVersion (written by a newer app build) is not something this build can
        // migrate — leave the key and artifact alone rather than deleting a possibly-valid enrollment.
        val keyStore = FakeBiometricKeyStore()
        run {
            val v = vault().also { it.create("master-pass".toCharArray()) }
            biometrics(v, keyStore).enable(prompt)
        }
        val current = artifacts().read()!!
        artifacts().write(current.copy(formatVersion = current.formatVersion + 1)) // a newer build's scheme

        val fresh = vault()
        assertEquals(BiometricUnlockResult.NotEnabled, biometrics(fresh, keyStore).unlock(prompt))
        assertTrue(artifacts().exists(), "a newer-version artifact must not be deleted")
        assertTrue(keyStore.deletedAliases.isEmpty(), "a newer-version enrollment must not be cleared")
    }

    @Test
    fun `artifact from another device is ignored and falls back to password`() = bioTest {
        val keyStore = FakeBiometricKeyStore()
        run {
            val v = vault().also { it.create("master-pass".toCharArray()) }
            biometrics(v, keyStore).enable(prompt)
        }
        // Swap deviceId in vault.bio for a foreign one; the orchestrator must not trust it.
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

        // Fresh (locked) instance: confirm proves presence but does not unlock the vault.
        val fresh = vault()
        assertEquals(BiometricConfirmResult.Confirmed, biometrics(fresh, keyStore).confirm(prompt))
        assertFalse(fresh.isUnlocked, "confirm should not unlock the vault")
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
        assertFalse(artifacts().exists(), "invalidation should disable biometrics")
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
        assertContentEquals(a, b, "copies are equal by content")
        a.fill(0)
        assertFalse(a.contentEquals(b), "these are independent copies — wiping one doesn't touch the other")
        b.fill(0)
    }
}
