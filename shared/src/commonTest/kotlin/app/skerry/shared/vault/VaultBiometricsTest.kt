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
    private fun biometrics(
        v: Vault,
        keyStore: FakeBiometricKeyStore,
        support: BiometricSupportStore = BiometricSupportStore.Volatile(),
    ) = VaultBiometrics(v, keyStore, artifacts(), deviceId = "device-1", support = support)

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
    fun `enable verifies the round trip and keeps the strongest working key`() = bioTest {
        // The whole point of the round trip: enable() must prove the wrapper it stores can be read
        // back, not just written. On a healthy device that succeeds on the first rung.
        val keyStore = FakeBiometricKeyStore()
        val v = vault().also { it.create("master-pass".toCharArray()) }

        assertEquals(BiometricEnableResult.Enabled, biometrics(v, keyStore).enable(prompt))

        assertEquals(listOf(BiometricKeyHardening.Strongest), keyStore.hardeningUsed)
        assertTrue(artifacts().exists())
    }

    @Test
    fun `enable walks down the hardening ladder when the enclave refuses the strongest key`() = bioTest {
        // #23: the keystore accepts an auth-bound key and then never authorizes operations on it.
        // Instead of enabling something that can never unlock, enable() retries on weaker rungs and
        // keeps the first one the device actually honours.
        val keyStore = FakeBiometricKeyStore(workingHardenings = setOf(BiometricKeyHardening.Relaxed))
        val v = vault().also { it.create("master-pass".toCharArray()) }

        assertEquals(BiometricEnableResult.Enabled, biometrics(v, keyStore).enable(prompt))

        assertEquals(BiometricKeyHardening.entries.toList(), keyStore.hardeningUsed)
        // The stored wrapper belongs to the working rung: a fresh instance can unlock with it.
        val fresh = vault()
        assertEquals(BiometricUnlockResult.Unlocked, biometrics(fresh, keyStore).unlock(prompt))
    }

    @Test
    fun `the rung the enrollment ended up on is remembered so the UI can admit a weaker one`() = bioTest {
        // Relaxed drops "unusable while the device is locked". The user never chose that downgrade, so
        // it must be visible rather than silent — the UI reads it back through enrolledHardening().
        val keyStore = FakeBiometricKeyStore(workingHardenings = setOf(BiometricKeyHardening.Relaxed))
        val support = BiometricSupportStore.Volatile()
        val v = vault().also { it.create("master-pass".toCharArray()) }

        assertEquals(BiometricEnableResult.Enabled, biometrics(v, keyStore, support).enable(prompt))

        assertEquals(BiometricKeyHardening.Relaxed, biometrics(v, keyStore, support).enrolledHardening())
    }

    @Test
    fun `enable reports Unsupported and remembers it when no rung works`() = bioTest {
        // Nothing on this device can decrypt the vault biometrically. Enabling must leave no
        // artifact and no key behind, and the verdict must stick so the UI stops offering it.
        val keyStore = FakeBiometricKeyStore(workingHardenings = emptySet())
        val support = BiometricSupportStore.Volatile()
        val v = vault().also { it.create("master-pass".toCharArray()) }

        assertEquals(BiometricEnableResult.Unsupported, biometrics(v, keyStore, support).enable(prompt))

        assertEquals(BiometricKeyHardening.entries.toList(), keyStore.hardeningUsed, "every rung must be tried")
        assertFalse(artifacts().exists(), "a vault.bio that can't be read back must not be stored")
        assertTrue(keyStore.deletedAliases.isNotEmpty(), "the unusable key must not be left behind")
        assertTrue(support.isUnsupported())
    }

    @Test
    fun `enable stops at the first rung when the user cancels`() = bioTest {
        // Cancelling is the user's verdict, not the device's: don't march down the ladder throwing
        // more prompts at them, and don't brand the device unsupported.
        val keyStore = FakeBiometricKeyStore()
        keyStore.nextWrap = BiometricOutcome.Cancelled
        val support = BiometricSupportStore.Volatile()
        val v = vault().also { it.create("master-pass".toCharArray()) }

        assertEquals(BiometricEnableResult.Cancelled, biometrics(v, keyStore, support).enable(prompt))

        assertEquals(listOf(BiometricKeyHardening.Strongest), keyStore.hardeningUsed)
        assertFalse(support.isUnsupported())
        assertFalse(artifacts().exists())
    }

    @Test
    fun `enable descends when the enclave accepts encryption but refuses decryption`() = bioTest {
        // The device shape from #23 (Xiaomi HyperOS): wrap succeeds, the round-trip unwrap is the
        // first operation the enclave refuses. That refusal is evidence about the key configuration
        // and must walk the ladder — a rung that never decrypts must not end the attempt as a
        // hardware failure, or weaker rungs that might work are never tried.
        val keyStore = object : FakeBiometricKeyStore() {
            override suspend fun unwrap(alias: String, wrapped: ByteArray, prompt: BiometricPrompt) =
                if (hardeningUsed.size < BiometricKeyHardening.entries.size) BiometricResult.Unusable
                else super.unwrap(alias, wrapped, prompt)
        }
        val v = vault().also { it.create("master-pass".toCharArray()) }

        assertEquals(BiometricEnableResult.Enabled, biometrics(v, keyStore).enable(prompt))

        assertEquals(BiometricKeyHardening.entries.toList(), keyStore.hardeningUsed)
        val fresh = vault()
        assertEquals(BiometricUnlockResult.Unlocked, biometrics(fresh, keyStore).unlock(prompt))
    }

    @Test
    fun `enable descends when the enclave calls its own fresh wrapper a tag mismatch`() = bioTest {
        // What #23 actually reports (Xiaomi HyperOS, StrongBox): KeyMint answers VERIFICATION_FAILED
        // when finishing the round-trip decryption, which the platform surfaces as a bad GCM tag on a
        // wrapper this very key produced a second earlier. That can't be a real mismatch, so at enable
        // time it is evidence about the key configuration — walk the ladder instead of aborting.
        val keyStore = object : FakeBiometricKeyStore() {
            override suspend fun unwrap(alias: String, wrapped: ByteArray, prompt: BiometricPrompt) =
                if (hardeningUsed.size < BiometricKeyHardening.entries.size) BiometricResult.TagMismatch
                else super.unwrap(alias, wrapped, prompt)
        }
        val v = vault().also { it.create("master-pass".toCharArray()) }

        assertEquals(BiometricEnableResult.Enabled, biometrics(v, keyStore).enable(prompt))

        assertEquals(BiometricKeyHardening.entries.toList(), keyStore.hardeningUsed)
        val fresh = vault()
        assertEquals(BiometricUnlockResult.Unlocked, biometrics(fresh, keyStore).unlock(prompt))
    }

    @Test
    fun `enable reports Unsupported when every rung answers with a tag mismatch`() = bioTest {
        val keyStore = FakeBiometricKeyStore().also { it.nextUnwrap = BiometricOutcome.TagMismatch }
        val support = BiometricSupportStore.Volatile()
        val v = vault().also { it.create("master-pass".toCharArray()) }

        assertEquals(BiometricEnableResult.Unsupported, biometrics(v, keyStore, support).enable(prompt))

        assertTrue(support.isUnsupported())
        assertFalse(artifacts().exists())
    }

    @Test
    fun `a tag mismatch at unlock stays an ordinary failure`() = bioTest {
        // Outside the round-trip check a mismatching wrapper means what it says — a stale or tampered
        // `vault.bio`, not a broken enclave. Fall back to the password without destroying anything.
        val keyStore = FakeBiometricKeyStore()
        val support = BiometricSupportStore.Volatile()
        run {
            val v = vault().also { it.create("master-pass".toCharArray()) }
            biometrics(v, keyStore, support).enable(prompt)
        }
        keyStore.nextUnwrap = BiometricOutcome.TagMismatch

        assertEquals(BiometricUnlockResult.Failed, biometrics(vault(), keyStore, support).unlock(prompt))
        assertEquals(BiometricUnlockResult.Failed, biometrics(vault(), keyStore, support).unlock(prompt))

        assertTrue(artifacts().exists())
        assertFalse(support.isUnsupported())
    }

    @Test
    fun `enable moves to the next rung when verification returns the wrong key`() = bioTest {
        // A round trip that "succeeds" with different bytes is as broken as one that throws — the
        // stored wrapper would unlock nothing.
        val keyStore = object : FakeBiometricKeyStore() {
            override suspend fun unwrap(alias: String, wrapped: ByteArray, prompt: BiometricPrompt) =
                if (hardeningUsed.size == 1) BiometricResult.Success(ByteArray(wrapped.size))
                else super.unwrap(alias, wrapped, prompt)
        }
        val v = vault().also { it.create("master-pass".toCharArray()) }

        assertEquals(BiometricEnableResult.Enabled, biometrics(v, keyStore).enable(prompt))

        assertTrue(keyStore.hardeningUsed.size > 1, "a mismatching round trip must not be accepted")
    }

    @Test
    fun `a single enclave refusal at unlock is not enough to destroy the enrollment`() = bioTest {
        // The refusal comes from a catch-all after a successful auth, so one occurrence is as likely to
        // be a keystore hiccup as a broken ROM. Reacting to it would delete a working enrollment.
        val keyStore = FakeBiometricKeyStore()
        val support = BiometricSupportStore.Volatile()
        run {
            val v = vault().also { it.create("master-pass".toCharArray()) }
            biometrics(v, keyStore, support).enable(prompt)
        }
        keyStore.nextUnwrap = BiometricOutcome.Unusable

        assertEquals(BiometricUnlockResult.Failed, biometrics(vault(), keyStore, support).unlock(prompt))

        assertTrue(artifacts().exists(), "one refusal must not disable biometrics")
        assertFalse(support.isUnsupported())
    }

    @Test
    fun `repeated enclave refusals disable biometrics and record the verdict`() = bioTest {
        // A streak is evidence: the enclave stopped honouring a key that passed the round trip at enable
        // time (a ROM update). Retrying can't fix it — disable, remember, let the UI explain.
        val keyStore = FakeBiometricKeyStore()
        val support = BiometricSupportStore.Volatile()
        run {
            val v = vault().also { it.create("master-pass".toCharArray()) }
            biometrics(v, keyStore, support).enable(prompt)
        }
        keyStore.nextUnwrap = BiometricOutcome.Unusable
        biometrics(vault(), keyStore, support).unlock(prompt)

        val fresh = vault()
        assertEquals(BiometricUnlockResult.Unsupported, biometrics(fresh, keyStore, support).unlock(prompt))

        assertFalse(fresh.isUnlocked)
        assertFalse(artifacts().exists(), "a key the enclave won't honour must not stay enabled")
        assertTrue(support.isUnsupported())
    }

    @Test
    fun `a working unlock forgets an earlier refusal`() = bioTest {
        // Only *consecutive* refusals convict: a hiccup two sessions ago must not count towards the verdict.
        val keyStore = FakeBiometricKeyStore()
        val support = BiometricSupportStore.Volatile()
        run {
            val v = vault().also { it.create("master-pass".toCharArray()) }
            biometrics(v, keyStore, support).enable(prompt)
        }
        keyStore.nextUnwrap = BiometricOutcome.Unusable
        biometrics(vault(), keyStore, support).unlock(prompt)
        keyStore.nextUnwrap = BiometricOutcome.Success
        assertEquals(BiometricUnlockResult.Unlocked, biometrics(vault(), keyStore, support).unlock(prompt))

        keyStore.nextUnwrap = BiometricOutcome.Unusable
        assertEquals(BiometricUnlockResult.Failed, biometrics(vault(), keyStore, support).unlock(prompt))
        assertTrue(artifacts().exists(), "the streak restarts after a working unlock")
    }

    @Test
    fun `a hardware failure on the first rung keeps the enrollment the device already had`() = bioTest {
        // Timeouts, a destroyed Activity, a sensor hiccup — none of that says anything about the key
        // configuration. Re-enabling must not cost the user a working setup because of one of them.
        val keyStore = FakeBiometricKeyStore()
        val v = vault().also { it.create("master-pass".toCharArray()) }
        assertEquals(BiometricEnableResult.Enabled, biometrics(v, keyStore).enable(prompt))
        keyStore.nextWrap = BiometricOutcome.Failed

        assertEquals(BiometricEnableResult.Failed, biometrics(v, keyStore).enable(prompt))

        assertTrue(artifacts().exists(), "the previous enrollment must survive a hardware failure")
    }

    @Test
    fun `cancelling the verification prompt keeps the enrollment the device already had`() = bioTest {
        // The second prompt is ours, not the user's idea — dismissing it must be free.
        val keyStore = FakeBiometricKeyStore()
        val v = vault().also { it.create("master-pass".toCharArray()) }
        assertEquals(BiometricEnableResult.Enabled, biometrics(v, keyStore).enable(prompt))
        keyStore.nextUnwrap = BiometricOutcome.Cancelled

        assertEquals(BiometricEnableResult.Cancelled, biometrics(v, keyStore).enable(prompt))

        assertTrue(artifacts().exists())
    }

    @Test
    fun `descending the ladder without success leaves no artifact behind`() = bioTest {
        // Here the key really was recreated, so the old wrapper points at nothing — biometrics goes off.
        val keyStore = FakeBiometricKeyStore()
        val v = vault().also { it.create("master-pass".toCharArray()) }
        assertEquals(BiometricEnableResult.Enabled, biometrics(v, keyStore).enable(prompt))
        keyStore.workingHardenings = emptySet()

        assertEquals(BiometricEnableResult.Unsupported, biometrics(v, keyStore).enable(prompt))

        assertFalse(artifacts().exists(), "a stale wrapper must not survive a rebuilt key")
    }

    @Test
    fun `a successful enable clears a previous unsupported verdict`() = bioTest {
        // The user re-checks after a ROM update and it works — the device must not stay branded.
        val keyStore = FakeBiometricKeyStore()
        val support = BiometricSupportStore.Volatile().apply { markUnsupported() }
        val v = vault().also { it.create("master-pass".toCharArray()) }

        assertEquals(BiometricEnableResult.Enabled, biometrics(v, keyStore, support).enable(prompt))

        assertFalse(support.isUnsupported())
    }

    @Test
    fun `the unsupported verdict survives a restart and is scoped to this device`() = bioTest {
        // Persisted next to vault.json: a copied workspace directory must not silence biometrics on
        // another device, so the verdict carries the deviceId that produced it.
        val path = "/vault.bio.unsupported".toPath()
        FileBiometricSupportStore(path, fs, deviceId = "device-1").markUnsupported()

        assertTrue(FileBiometricSupportStore(path, fs, deviceId = "device-1").isUnsupported())
        assertFalse(FileBiometricSupportStore(path, fs, deviceId = "device-2").isUnsupported())

        FileBiometricSupportStore(path, fs, deviceId = "device-1").clear()
        assertFalse(FileBiometricSupportStore(path, fs, deviceId = "device-1").isUnsupported())
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
