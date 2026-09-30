package app.skerry.ui.vault

import app.skerry.shared.vault.BioArtifact
import app.skerry.shared.vault.BioArtifactStore
import app.skerry.shared.vault.BiometricAvailability
import app.skerry.shared.vault.BiometricPrompt
import app.skerry.shared.vault.BiometricResult
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.initializeVaultCrypto
import app.skerry.shared.vault.SecurityEventType
import app.skerry.shared.vault.SecurityLog
import app.skerry.shared.vault.UnlockFactor
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultBiometrics
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gate controller on a trusted device (issue #398): an unlock nobody is asked for, its own words
 * and events, and a trust set only for the master password.
 */
class VaultGateTrustControllerTest {

    /**
     * Controller with immediate (Unconfined) scope/kdfDispatcher execution: async
     * [VaultGateController.create]/[VaultGateController.unlock] complete before the call returns,
     * so immediate-after asserts stay valid.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun gate(
        vault: Vault,
        biometrics: VaultBiometrics? = null,
        minPasswordLength: Int = MIN_MASTER_PASSWORD_LENGTH,
        onReset: (ResetScope) -> Unit = {},
        offersSyncOnboarding: Boolean = false,
        securityLog: SecurityLog? = null,
        /**
         * Sink for scope exceptions. Required for tests where the vault throws: without it the
         * exception hits the thread's global handler and fails an unrelated test
         * (UncaughtExceptionsBeforeTest).
         */
        onException: ((Throwable) -> Unit)? = null,
    ): VaultGateController {
        val dispatcher = UnconfinedTestDispatcher()
        val context = if (onException != null) {
            dispatcher + CoroutineExceptionHandler { _, e -> onException(e) }
        } else {
            dispatcher
        }
        return VaultGateController(
            vault = vault,
            biometrics = biometrics,
            minPasswordLength = minPasswordLength,
            onReset = onReset,
            offersSyncOnboarding = offersSyncOnboarding,
            securityLog = securityLog,
            scope = CoroutineScope(context),
            kdfDispatcher = dispatcher,
        )
    }

    @Test
    fun `a trusted device unlocks on its own and offers no fingerprint button`() = runTest {
        val controller = gate(FakeVault(exists = true), biometricsForUnlock(BiometricResult.Success(ByteArray(32)), factor = UnlockFactor.DeviceKeyring))

        assertTrue(controller.unlocksAutomatically())
        assertFalse(controller.offersBiometricButton())
    }

    @Test
    fun `a biometric vault keeps its button and waits for the touch`() = runTest {
        val controller = gate(FakeVault(exists = true), biometricsForUnlock(BiometricResult.Success(ByteArray(32))))

        assertFalse(controller.unlocksAutomatically())
        assertTrue(controller.offersBiometricButton())
    }

    @Test
    fun `a trusted-device unlock is logged as its own event`() = runTest {
        val log = RecordingSecurityLog()
        val controller = gate(
            FakeVault(exists = true),
            biometricsForUnlock(BiometricResult.Success(ByteArray(32)), factor = UnlockFactor.DeviceKeyring),
            securityLog = log,
        )

        controller.unlockWithBiometric(PROMPT)

        assertEquals(VaultGateState.Unlocked, controller.state)
        assertEquals(listOf(SecurityEventType.UnlockedTrustedDevice), log.events.map { it.type })
    }

    @Test
    fun `a keyring that fails says so in its own words and leaves the password form`() = runTest {
        val controller = gate(FakeVault(exists = true), biometricsForUnlock(BiometricResult.Failed, factor = UnlockFactor.DeviceKeyring))

        controller.unlockWithBiometric(PROMPT)

        assertEquals(VaultGateState.NeedsUnlock, controller.state)
        assertEquals(VaultGateError.TrustedDeviceFailed, controller.error)
    }

    @Test
    fun `a keyring entry that is gone turns the trust off and says why`() = runTest {
        val controller = gate(FakeVault(exists = true), biometricsForUnlock(BiometricResult.KeyInvalidated, factor = UnlockFactor.DeviceKeyring))

        controller.unlockWithBiometric(PROMPT)

        assertEquals(VaultGateError.TrustedDeviceReset, controller.error)
        assertFalse(controller.biometricEnabled)
        assertFalse(controller.unlocksAutomatically())
    }

    @Test
    fun `a trust the vault refuses turns off instead of opening a stale vault`() = runTest {
        val vault = FakeVault(exists = true, unlockResult = UnlockResult.WrongPassword)
        val controller = gate(vault, biometricsForUnlock(BiometricResult.Success(ByteArray(32)), factor = UnlockFactor.DeviceKeyring, vault = vault))

        controller.unlockWithBiometric(PROMPT)

        assertEquals(VaultGateState.NeedsUnlock, controller.state)
        assertEquals(VaultGateError.TrustedDeviceReset, controller.error)
        assertFalse(controller.biometricEnabled)
    }

    @Test
    fun `a new vault skips the fingerprint offer on a trusted-device platform`() = runTest {
        val controller = gate(FakeVault(exists = false), biometrics(BiometricAvailability.Available, factor = UnlockFactor.DeviceKeyring))

        controller.create("correct horse battery".toCharArray(), "correct horse battery".toCharArray())

        assertEquals(VaultGateState.Unlocked, controller.state)
    }

    @Test
    fun `turning trust on and off is logged as trust, not biometrics`() = runTest {
        initializeVaultCrypto()
        val log = RecordingSecurityLog()
        val vault = trustReadyVault()
        val controller = gate(vault, biometrics(BiometricAvailability.Available, vault = vault, factor = UnlockFactor.DeviceKeyring), securityLog = log)

        assertEquals(TrustEnableResult.Enabled, controller.enableTrustedDevice("password1".toCharArray(), PROMPT))
        controller.disableBiometric()

        assertEquals(
            listOf(SecurityEventType.TrustedDeviceEnabled, SecurityEventType.TrustedDeviceDisabled),
            log.events.map { it.type },
        )
    }

    @Test
    fun `a trust is never set without the master password`() = runTest {
        initializeVaultCrypto()
        val vault = trustReadyVault()
        val controller = gate(vault, biometrics(BiometricAvailability.Available, vault = vault, factor = UnlockFactor.DeviceKeyring))

        assertFalse(controller.enableBiometric(PROMPT))
        assertFalse(controller.biometricEnabled)
    }

    @Test
    fun `a wrong master password sets no trust`() = runTest {
        initializeVaultCrypto()
        val vault = trustReadyVault()
        val password = "wrong".toCharArray()
        val controller = gate(vault, biometrics(BiometricAvailability.Available, vault = vault, factor = UnlockFactor.DeviceKeyring))

        assertEquals(TrustEnableResult.WrongPassword, controller.enableTrustedDevice(password, PROMPT))
        assertFalse(controller.biometricEnabled)
        assertTrue(password.all { it == '\u0000' }, "the typed password is wiped")
    }

    @Test
    fun `the master password sets the trust`() = runTest {
        initializeVaultCrypto()
        val vault = trustReadyVault()
        val controller = gate(vault, biometrics(BiometricAvailability.Available, vault = vault, factor = UnlockFactor.DeviceKeyring))

        assertEquals(TrustEnableResult.Enabled, controller.enableTrustedDevice("password1".toCharArray(), PROMPT))
        assertTrue(controller.biometricEnabled)
    }

    @Test
    fun `a keyring that refuses after the right password is a failure the caller hears about`() = runTest {
        initializeVaultCrypto()
        val vault = trustReadyVault()
        val controller = gate(vault, biometrics(BiometricAvailability.Available, vault = vault, factor = UnlockFactor.DeviceKeyring, wrap = BiometricResult.Failed))

        assertEquals(TrustEnableResult.Failed, controller.enableTrustedDevice("password1".toCharArray(), PROMPT))
        assertFalse(controller.biometricEnabled)
    }

    @Test
    fun `a disable the disk refuses leaves the setting as it is instead of escaping`() = runTest {
        val stuck = object : BioArtifactStore {
            override fun exists() = true
            override fun read(): BioArtifact? = null
            override fun write(artifact: BioArtifact) = Unit
            override fun clear(): Unit = error("read-only config directory")
        }
        val controller = gate(FakeVault(exists = true), biometrics(BiometricAvailability.Available, stuck, factor = UnlockFactor.DeviceKeyring))

        controller.disableBiometric()

        assertTrue(controller.biometricEnabled, "the toggle shows what is on disk")
    }

    @Test
    fun `a trust dialog closed mid-enable still records the trust it set`() = runTest {
        initializeVaultCrypto()
        val log = RecordingSecurityLog()
        val vault = trustReadyVault()
        val disk = FakeArtifacts()
        lateinit var job: Job
        // The dialog's scope goes the moment `vault.bio` is on disk: the trust is set either way.
        val closing = object : BioArtifactStore by disk {
            override fun write(artifact: BioArtifact) {
                disk.write(artifact)
                job.cancel()
            }
        }
        val controller = gate(vault, biometrics(BiometricAvailability.Available, closing, vault, UnlockFactor.DeviceKeyring), securityLog = log)

        job = launch { controller.enableTrustedDevice("password1".toCharArray(), PROMPT) }
        job.join()

        assertTrue(disk.exists())
        assertTrue(controller.biometricEnabled, "the toggle shows the trust on disk")
        assertEquals(listOf(SecurityEventType.TrustedDeviceEnabled), log.events.map { it.type })
    }

    @Test
    fun `settings closed mid-disable still records the trust it dropped`() = runTest {
        initializeVaultCrypto()
        val log = RecordingSecurityLog()
        val vault = trustReadyVault()
        val disk = FakeArtifacts()
        var job: Job? = null
        val closing = object : BioArtifactStore by disk {
            override fun clear() {
                disk.clear()
                job?.cancel()
            }
        }
        val controller = gate(vault, biometrics(BiometricAvailability.Available, closing, vault, UnlockFactor.DeviceKeyring), securityLog = log)
        assertEquals(TrustEnableResult.Enabled, controller.enableTrustedDevice("password1".toCharArray(), PROMPT))

        job = launch { controller.disableBiometric() }
        job.join()

        assertFalse(controller.biometricEnabled)
        assertEquals(
            listOf(SecurityEventType.TrustedDeviceEnabled, SecurityEventType.TrustedDeviceDisabled),
            log.events.map { it.type },
        )
    }

    private fun trustReadyVault() = FakeVault(exists = true).apply {
        unlock("password1".toCharArray())
        exportable = IonspinVaultCrypto().newDataKey()
        masterPassword = "password1"
    }
}

private val PROMPT = BiometricPrompt(title = "Trust", cancelLabel = "Cancel")
