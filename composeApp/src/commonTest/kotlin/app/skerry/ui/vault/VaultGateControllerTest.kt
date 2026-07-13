package app.skerry.ui.vault

import app.skerry.shared.vault.BioArtifact
import app.skerry.shared.vault.BioArtifactStore
import app.skerry.shared.vault.BiometricAvailability
import app.skerry.shared.vault.BiometricKeyStore
import app.skerry.shared.vault.BiometricPrompt
import app.skerry.shared.vault.BiometricResult
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.MergeResult
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.SecurityEvent
import app.skerry.shared.vault.SecurityEventType
import app.skerry.shared.vault.SecurityLog
import app.skerry.shared.vault.SyncMeta
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultBiometrics
import app.skerry.shared.vault.VaultRecord
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultGateControllerTest {

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
    fun `starts in NeedsCreate when no vault file exists`() {
        val controller = gate(FakeVault(exists = false))

        assertEquals(VaultGateState.NeedsCreate, controller.state)
        assertNull(controller.error)
    }

    @Test
    fun `starts in NeedsUnlock when a vault file already exists`() {
        val controller = gate(FakeVault(exists = true))

        assertEquals(VaultGateState.NeedsUnlock, controller.state)
    }

    @Test
    fun `create with matching passwords creates the vault and unlocks`() {
        val vault = FakeVault(exists = false)
        val controller = gate(vault, minPasswordLength = 8)

        controller.create("correct horse".toCharArray(), "correct horse".toCharArray())

        assertEquals(VaultGateState.Unlocked, controller.state)
        assertNull(controller.error)
        assertEquals(1, vault.createCalls)
    }

    @Test
    fun `create rejects a password shorter than the minimum without touching the vault`() {
        val vault = FakeVault(exists = false)
        val controller = gate(vault, minPasswordLength = 8)

        controller.create("short".toCharArray(), "short".toCharArray())

        assertEquals(VaultGateState.NeedsCreate, controller.state)
        assertEquals(VaultGateError.PasswordTooShort, controller.error)
        assertEquals(0, vault.createCalls)
    }

    @Test
    fun `create rejects mismatched confirmation without touching the vault`() {
        val vault = FakeVault(exists = false)
        val controller = gate(vault, minPasswordLength = 8)

        controller.create("correct horse".toCharArray(), "correct house".toCharArray())

        assertEquals(VaultGateState.NeedsCreate, controller.state)
        assertEquals(VaultGateError.PasswordMismatch, controller.error)
        assertEquals(0, vault.createCalls)
    }

    @Test
    fun `create zeroes both password buffers`() {
        val controller = gate(FakeVault(exists = false), minPasswordLength = 8)
        val password = "correct horse".toCharArray()
        val confirm = "correct horse".toCharArray()

        controller.create(password, confirm)

        assertTrue(password.all { it == ' ' }, "password buffer must be wiped")
        assertTrue(confirm.all { it == ' ' }, "confirm buffer must be wiped")
    }

    @Test
    fun `create zeroes buffers even when validation fails`() {
        val controller = gate(FakeVault(exists = false), minPasswordLength = 8)
        val password = "correct horse".toCharArray()
        val confirm = "mismatch here".toCharArray()

        controller.create(password, confirm)

        assertTrue(password.all { it == ' ' })
        assertTrue(confirm.all { it == ' ' })
    }

    @Test
    fun `unlock with the right password unlocks`() {
        val vault = FakeVault(exists = true, unlockResult = UnlockResult.Success)
        val controller = gate(vault)

        controller.unlock("correct horse".toCharArray())

        assertEquals(VaultGateState.Unlocked, controller.state)
        assertNull(controller.error)
    }

    @Test
    fun `unlock with a wrong password stays on the unlock screen with an error`() {
        val vault = FakeVault(exists = true, unlockResult = UnlockResult.WrongPassword)
        val controller = gate(vault)

        controller.unlock("nope".toCharArray())

        assertEquals(VaultGateState.NeedsUnlock, controller.state)
        assertEquals(VaultGateError.WrongPassword, controller.error)
    }

    @Test
    fun `unlock zeroes the password buffer even when the vault throws`() {
        // The vault throws without wiping the buffer itself; the wipe proves the controller's finally runs.
        // unlock is async: the exception stays in the controller's scope (not thrown to the caller),
        // but finally must wipe the buffer and clear verifying before it propagates.
        val scopeExceptions = mutableListOf<Throwable>()
        val controller = gate(
            FakeVault(exists = true, unlockThrows = true),
            onException = { scopeExceptions.add(it) },
        )
        val password = "secret password".toCharArray()

        controller.unlock(password)

        assertTrue(password.all { it == ' ' }, "password buffer must be wiped on the exception path")
        assertFalse(controller.verifying, "verifying must be reset on the exception path")
        assertTrue(
            scopeExceptions.single() is IllegalStateException,
            "the vault failure must surface through the scope, not be swallowed",
        )
    }

    @Test
    fun `unlock of a corrupted vault moves to the Corrupted screen`() {
        val vault = FakeVault(exists = true, unlockResult = UnlockResult.Corrupted)
        val controller = gate(vault)

        controller.unlock("whatever".toCharArray())

        // A corrupted file is a dead end: entering a password is pointless, go to the reset screen.
        assertEquals(VaultGateState.Corrupted, controller.state)
    }

    @Test
    fun `beginReset opens the reset screen and cancelReset returns to where it started`() {
        // From the unlock form ("forgot password").
        val fromUnlock = gate(FakeVault(exists = true))
        fromUnlock.beginReset()
        assertEquals(VaultGateState.Resetting, fromUnlock.state)
        fromUnlock.cancelReset()
        assertEquals(VaultGateState.NeedsUnlock, fromUnlock.state)

        // From the Corrupted screen: cancel returns to Corrupted, not to a useless unlock form.
        val fromCorrupted = gate(FakeVault(exists = true, unlockResult = UnlockResult.Corrupted))
        fromCorrupted.unlock("x".toCharArray())
        assertEquals(VaultGateState.Corrupted, fromCorrupted.state)
        fromCorrupted.beginReset()
        fromCorrupted.cancelReset()
        assertEquals(VaultGateState.Corrupted, fromCorrupted.state)
    }

    @Test
    fun `confirmReset wipes the vault, runs external cleanup with the scope, and goes to NeedsCreate`() {
        val vault = FakeVault(exists = true)
        var cleanedScope: ResetScope? = null
        val controller = gate(vault, onReset = { cleanedScope = it })
        controller.beginReset()

        controller.confirmReset(ResetScope.Everything)

        assertEquals(1, vault.resetCalls)
        assertEquals(ResetScope.Everything, cleanedScope)
        assertEquals(VaultGateState.NeedsCreate, controller.state)
        assertNull(controller.error)
    }

    @Test
    fun `confirmReset still reaches NeedsCreate when external cleanup throws`() {
        // The vault file is already erased by the time onReset runs; must not stay stuck on
        // Resetting even if host cleanup fails: the finally guarantees the move to the create form.
        val vault = FakeVault(exists = true)
        val controller = gate(vault, onReset = { error("cleanup failed") })

        controller.confirmReset(ResetScope.Everything)

        assertEquals(1, vault.resetCalls)
        assertEquals(VaultGateState.NeedsCreate, controller.state)
    }

    @Test
    fun `confirmReset forwards SecretsOnly scope to external cleanup`() {
        val vault = FakeVault(exists = true)
        var cleanedScope: ResetScope? = null
        val controller = gate(vault, onReset = { cleanedScope = it })

        controller.confirmReset(ResetScope.SecretsOnly)

        assertEquals(ResetScope.SecretsOnly, cleanedScope)
    }

    @Test
    fun `a successful attempt clears a previous error`() {
        val vault = FakeVault(exists = true, unlockResult = UnlockResult.WrongPassword)
        val controller = gate(vault)
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
        val controller = gate(vault)
        controller.unlock("correct horse".toCharArray())

        controller.lock()

        assertEquals(VaultGateState.NeedsUnlock, controller.state)
        assertEquals(1, vault.lockCalls)
    }

    // --- Biometrics: the settings toggle goes through the controller (canEnable/enable/disable) ---

    @Test
    fun `canEnableBiometric reflects device availability`() {
        val available = gate(FakeVault(exists = true), biometrics(BiometricAvailability.Available))
        val noHardware = gate(FakeVault(exists = true), biometrics(BiometricAvailability.NoHardware))

        assertTrue(available.canEnableBiometric())
        assertFalse(noHardware.canEnableBiometric())
    }

    @Test
    fun `controller without biometrics cannot enable and disable is a no-op`() = runTest {
        val controller = gate(FakeVault(exists = true))

        assertFalse(controller.canEnableBiometric())
        assertFalse(controller.enableBiometric(PROMPT))
        controller.disableBiometric() // must not throw
        assertFalse(controller.biometricEnabled)
    }

    @Test
    fun `enableBiometric on a locked vault returns false and leaves biometricEnabled off`() = runTest {
        // Vault locked -> exportDataKey == null -> VaultBiometrics.enable == VaultLocked.
        // The enable-success path needs a live dataKey (DataKey constructor is internal in :shared)
        // and is covered there, in shared VaultBiometricsTest; here we only check delegation.
        val controller = gate(FakeVault(exists = true), biometrics(BiometricAvailability.Available))
        assertFalse(controller.biometricEnabled)

        assertFalse(controller.enableBiometric(PROMPT))
        assertFalse(controller.biometricEnabled)
    }

    @Test
    fun `disableBiometric clears the artifact and biometricEnabled`() = runTest {
        // A pre-seeded artifact makes biometrics "enabled" at start, without going through enable.
        val artifacts = FakeArtifacts().apply {
            write(BioArtifact(formatVersion = 1, alias = "test-device", deviceId = "test-device", wrappedBio = ByteArray(16)))
        }
        val controller = gate(FakeVault(exists = true), biometrics(BiometricAvailability.Available, artifacts))
        assertTrue(controller.biometricEnabled)

        controller.disableBiometric()

        assertFalse(controller.biometricEnabled)
        assertFalse(artifacts.exists())
    }

    @Test
    fun `create offers biometric enrollment when the device can enable it`() {
        val controller = gate(
            FakeVault(exists = false),
            biometrics(BiometricAvailability.Available),
            minPasswordLength = 8,
        )

        controller.create("correct horse".toCharArray(), "correct horse".toCharArray())

        assertEquals(VaultGateState.OfferBiometric, controller.state)
    }

    @Test
    fun `create unlocks directly when biometrics are unavailable on the device`() {
        val controller = gate(
            FakeVault(exists = false),
            biometrics(BiometricAvailability.NotEnrolled),
            minPasswordLength = 8,
        )

        controller.create("correct horse".toCharArray(), "correct horse".toCharArray())

        assertEquals(VaultGateState.Unlocked, controller.state)
    }

    // --- Sync onboarding: offered before biometrics, so biometrics wraps the final dataKey ---

    @Test
    fun `create offers sync onboarding first when the platform provides a sync form`() {
        // Biometrics is available, but the sync step comes first: otherwise accepting the account
        // key would wipe the biometrics just enabled.
        val controller = gate(
            FakeVault(exists = false),
            biometrics(BiometricAvailability.Available),
            minPasswordLength = 8,
            offersSyncOnboarding = true,
        )

        controller.create("correct horse".toCharArray(), "correct horse".toCharArray())

        assertEquals(VaultGateState.OfferSync, controller.state)
    }

    @Test
    fun `completeSyncOnboarding advances to the biometric offer when the device can enable it`() {
        val controller = gate(
            FakeVault(exists = false),
            biometrics(BiometricAvailability.Available),
            minPasswordLength = 8,
            offersSyncOnboarding = true,
        )
        controller.create("correct horse".toCharArray(), "correct horse".toCharArray())

        controller.completeSyncOnboarding()

        assertEquals(VaultGateState.OfferBiometric, controller.state)
    }

    @Test
    fun `completeSyncOnboarding unlocks directly when biometrics are unavailable`() {
        val controller = gate(
            FakeVault(exists = false),
            biometrics(BiometricAvailability.NotEnrolled),
            minPasswordLength = 8,
            offersSyncOnboarding = true,
        )
        controller.create("correct horse".toCharArray(), "correct horse".toCharArray())
        assertEquals(VaultGateState.OfferSync, controller.state)

        controller.completeSyncOnboarding()

        assertEquals(VaultGateState.Unlocked, controller.state)
    }

    @Test
    fun `completeSyncOnboarding is a no-op outside the OfferSync step`() {
        val controller = gate(FakeVault(exists = true, unlockResult = UnlockResult.Success))
        controller.unlock("correct horse".toCharArray())
        assertEquals(VaultGateState.Unlocked, controller.state)

        controller.completeSyncOnboarding()

        assertEquals(VaultGateState.Unlocked, controller.state)
    }

    @Test
    fun `completePairing advances to the biometric offer when the device can enable it`() {
        // The vault is already created and unlocked by the pairing coordinator on the create screen
        // (NeedsCreate), account key accepted; biometrics can wrap right away.
        val controller = gate(
            FakeVault(exists = false),
            biometrics(BiometricAvailability.Available),
        )
        assertEquals(VaultGateState.NeedsCreate, controller.state)

        controller.completePairing()

        assertEquals(VaultGateState.OfferBiometric, controller.state)
    }

    @Test
    fun `completePairing unlocks directly when biometrics are unavailable`() {
        val controller = gate(
            FakeVault(exists = false),
            biometrics(BiometricAvailability.NotEnrolled),
        )
        assertEquals(VaultGateState.NeedsCreate, controller.state)

        controller.completePairing()

        assertEquals(VaultGateState.Unlocked, controller.state)
    }

    @Test
    fun `completePairing is a no-op once past the create step`() {
        val controller = gate(FakeVault(exists = true, unlockResult = UnlockResult.Success))
        controller.unlock("correct horse".toCharArray())
        assertEquals(VaultGateState.Unlocked, controller.state)

        controller.completePairing()

        assertEquals(VaultGateState.Unlocked, controller.state)
    }

    @Test
    fun `completePairing records a DevicePaired event`() {
        // Create-join screen: the pairing coordinator already created and unlocked the local vault;
        // the gate confirms pairing here, the only point where the device is known to be paired.
        val log = RecordingSecurityLog()
        val controller = gate(
            FakeVault(exists = false),
            biometrics(BiometricAvailability.NotEnrolled),
            securityLog = log,
        )
        assertEquals(VaultGateState.NeedsCreate, controller.state)

        controller.completePairing()

        assertEquals(listOf(SecurityEventType.DevicePaired), log.events.map { it.type })
    }

    @Test
    fun `completePairing past the create step records nothing`() {
        val log = RecordingSecurityLog()
        val controller = gate(
            FakeVault(exists = true, unlockResult = UnlockResult.Success),
            securityLog = log,
        )
        controller.unlock("correct horse".toCharArray())

        controller.completePairing() // no-op outside NeedsCreate; logs nothing

        assertTrue(log.events.none { it.type == SecurityEventType.DevicePaired })
    }

    // --- Security log: the controller records the events it owns and exposes them to Settings ---

    @Test
    fun `create records a VaultCreated event as the password baseline`() {
        val log = RecordingSecurityLog()
        val controller = gate(FakeVault(exists = false), minPasswordLength = 8, securityLog = log)

        controller.create("correct horse".toCharArray(), "correct horse".toCharArray())

        assertEquals(listOf(SecurityEventType.VaultCreated), log.events.map { it.type })
        assertEquals("t0", controller.lastPasswordChangeAt())
    }

    @Test
    fun `changePassword records the change on success and wipes both buffers`() {
        val log = RecordingSecurityLog()
        val controller = gate(FakeVault(exists = true, changePasswordResult = true), securityLog = log)
        val old = "old password!!".toCharArray()
        val fresh = "new password!!".toCharArray()

        assertTrue(controller.changePassword(old, fresh))

        assertEquals(listOf(SecurityEventType.MasterPasswordChanged), log.events.map { it.type })
        assertTrue(old.all { it == ' ' }, "old buffer must be wiped")
        assertTrue(fresh.all { it == ' ' }, "new buffer must be wiped")
    }

    @Test
    fun `changePassword with a wrong current password records nothing and returns false`() {
        val log = RecordingSecurityLog()
        val controller = gate(FakeVault(exists = true, changePasswordResult = false), securityLog = log)

        assertFalse(controller.changePassword("wrong".toCharArray(), "new password!!".toCharArray()))

        assertTrue(log.events.isEmpty())
    }

    @Test
    fun `disableBiometric records a BiometricDisabled event when it was enabled`() {
        val log = RecordingSecurityLog()
        val artifacts = FakeArtifacts().apply {
            write(BioArtifact(formatVersion = 1, alias = "test-device", deviceId = "test-device", wrappedBio = ByteArray(16)))
        }
        val controller = gate(
            FakeVault(exists = true),
            biometrics(BiometricAvailability.Available, artifacts),
            securityLog = log,
        )
        assertTrue(controller.biometricEnabled)

        controller.disableBiometric()

        assertEquals(listOf(SecurityEventType.BiometricDisabled), log.events.map { it.type })
    }

    @Test
    fun `recentSecurityEvents delegates to the log newest first`() {
        val log = RecordingSecurityLog()
        val controller = gate(FakeVault(exists = true), securityLog = log)
        log.record(SecurityEventType.DevicePaired, "iPhone")
        log.record(SecurityEventType.UnlockedBiometric)

        val recent = controller.recentSecurityEvents()

        assertEquals(SecurityEventType.UnlockedBiometric, recent.first().type)
        assertEquals(SecurityEventType.DevicePaired, recent.last().type)
    }

    @Test
    fun `security accessors are empty without a log`() {
        val controller = gate(FakeVault(exists = true))

        assertTrue(controller.recentSecurityEvents().isEmpty())
        assertNull(controller.lastPasswordChangeAt())
    }

    @Test
    fun `dismissBiometricOffer moves from the offer to Unlocked`() {
        val controller = gate(
            FakeVault(exists = false),
            biometrics(BiometricAvailability.Available),
            minPasswordLength = 8,
        )
        controller.create("correct horse".toCharArray(), "correct horse".toCharArray())

        controller.dismissBiometricOffer()

        assertEquals(VaultGateState.Unlocked, controller.state)
    }
}

private val PROMPT = BiometricPrompt(title = "Enable biometrics", cancelLabel = "Cancel")

/** Builds a real [VaultBiometrics] over fake hardware/artifact stubs; contract lives in `commonMain`. */
private fun biometrics(
    availability: BiometricAvailability,
    artifacts: BioArtifactStore = FakeArtifacts(),
    vault: Vault = FakeVault(exists = true),
): VaultBiometrics = VaultBiometrics(vault, FakeKeyStore(availability), artifacts, deviceId = "test-device")

/**
 * In-memory [Vault] for gate tests: models the create/unlock/lock lifecycle and wipes the passed
 * password (like the file-backed implementation). CRUD is not exercised by the gate controller.
 */
private class FakeVault(
    exists: Boolean,
    var unlockResult: UnlockResult = UnlockResult.Success,
    private val unlockThrows: Boolean = false,
    private val changePasswordResult: Boolean = true,
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
        // The real implementation wipes the buffer itself; unlockThrows models a failure before
        // the wipe, to verify the controller's finally clears the buffer.
        if (unlockThrows) error("unlock failed")
        password.fill(' ')
        if (unlockResult == UnlockResult.Success) isUnlocked = true
        return unlockResult
    }

    override fun lock() {
        lockCalls++
        isUnlocked = false
    }

    var resetCalls = 0
        private set

    override fun reset() {
        resetCalls++
        isUnlocked = false
        fileExists = false
    }

    override fun records(): List<VaultRecord> = emptyList()
    override fun syncMeta(): SyncMeta? = null
    override fun mergeRemote(remote: List<VaultRecord>): MergeResult = MergeResult.EMPTY
    override fun openPayload(id: String): ByteArray? = null
    override fun put(id: String, type: RecordType, payload: ByteArray) = Unit
    override fun remove(id: String) = Unit
    override fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean {
        // The real implementation wipes the passed buffers; modeled here for symmetry with the controller.
        oldPassword.fill(' ')
        newPassword.fill(' ')
        return changePasswordResult
    }
    override fun verifyPassword(password: CharArray): Boolean = false

    // The biometrics path with a live dataKey is covered in shared (DataKey constructor is internal); stubs here.
    override fun unlockWithDataKey(dataKey: DataKey): UnlockResult = UnlockResult.Corrupted
    override fun exportDataKey(): DataKey? = null
    override fun adoptDataKey(newDataKey: DataKey, password: CharArray): Boolean = false
}

/** Fake secure-enclave: [availability] controls availability, wrap/unwrap are identity ops. */
private class FakeKeyStore(
    private val availability: BiometricAvailability,
) : BiometricKeyStore {
    override fun availability(): BiometricAvailability = availability
    override suspend fun ensureKey(alias: String): Boolean = availability == BiometricAvailability.Available
    override suspend fun wrap(alias: String, plaintext: ByteArray, prompt: BiometricPrompt): BiometricResult<ByteArray> =
        BiometricResult.Success(plaintext.copyOf())
    override suspend fun unwrap(alias: String, wrapped: ByteArray, prompt: BiometricPrompt): BiometricResult<ByteArray> =
        BiometricResult.Success(wrapped.copyOf())
    override fun deleteKey(alias: String) = Unit
}

/** In-memory [SecurityLog] for tests: monotonic stamps t0, t1, ... make order deterministic. */
private class RecordingSecurityLog : SecurityLog {
    val events = mutableListOf<SecurityEvent>()
    private var tick = 0
    override fun record(type: SecurityEventType, detail: String?) {
        events += SecurityEvent(type, "t${tick++}", detail)
    }
    override fun recent(limit: Int): List<SecurityEvent> = events.asReversed().take(limit)
    override fun lastPasswordChangeAt(): String? = events.lastOrNull {
        it.type == SecurityEventType.VaultCreated || it.type == SecurityEventType.MasterPasswordChanged
    }?.at
    override fun clear() = events.clear()
}

/** Fake `vault.bio` persistence: holds the artifact in memory. */
private class FakeArtifacts : BioArtifactStore {
    private var artifact: BioArtifact? = null
    override fun exists(): Boolean = artifact != null
    override fun read(): BioArtifact? = artifact
    override fun write(artifact: BioArtifact) { this.artifact = artifact }
    override fun clear() { artifact = null }
}
