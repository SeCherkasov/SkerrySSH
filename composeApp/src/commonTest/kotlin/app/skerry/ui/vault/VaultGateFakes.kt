package app.skerry.ui.vault

import app.skerry.shared.vault.BioArtifact
import app.skerry.shared.vault.BioArtifactStore
import app.skerry.shared.vault.BiometricAvailability
import app.skerry.shared.vault.BiometricKeyHardening
import app.skerry.shared.vault.BiometricSupportStore
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
import app.skerry.shared.vault.UnlockFactor
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultBiometrics
import app.skerry.shared.vault.VaultRecord

/* Fakes shared by the gate controller tests. */

/** Builds a real [VaultBiometrics] over fake hardware/artifact stubs; contract lives in `commonMain`. */
internal fun biometrics(
    availability: BiometricAvailability,
    artifacts: BioArtifactStore = FakeArtifacts(),
    vault: Vault = FakeVault(exists = true),
    factor: UnlockFactor = UnlockFactor.Biometric,
    wrap: BiometricResult<ByteArray>? = null,
): VaultBiometrics = VaultBiometrics(vault, FakeKeyStore(availability, factor = factor, wrapOutcome = wrap), artifacts, deviceId = "test-device")

/**
 * A [VaultBiometrics] that is already enabled (valid `vault.bio` seeded for `test-device`) and whose
 * next unwrap yields [unwrapOutcome] — lets the gate tests drive biometric-unlock failure/cancel paths.
 */
internal fun biometricsForUnlock(
    unwrapOutcome: BiometricResult<ByteArray> = BiometricResult.Failed,
    availability: BiometricAvailability = BiometricAvailability.Available,
    support: BiometricSupportStore = BiometricSupportStore.Volatile(),
    factor: UnlockFactor = UnlockFactor.Biometric,
    vault: Vault = FakeVault(exists = true),
): VaultBiometrics {
    val artifacts = FakeArtifacts().apply {
        write(
            BioArtifact(
                formatVersion = 1,
                alias = "skerry.vault.bio.test-device",
                deviceId = "test-device",
                wrappedBio = ByteArray(16),
            ),
        )
    }
    return VaultBiometrics(
        vault,
        FakeKeyStore(availability, unwrapOutcome, factor),
        artifacts,
        deviceId = "test-device",
        support = support,
    )
}

/**
 * In-memory [Vault] for gate tests: models the create/unlock/lock lifecycle and wipes the passed
 * password (like the file-backed implementation). CRUD is not exercised by the gate controller.
 */
/** A security log whose write fails — the step that runs once the vault already exists. */
internal object ThrowingSecurityLog : SecurityLog {
    override fun record(type: SecurityEventType, detail: String?) = error("security log unavailable")
    override fun recent(limit: Int): List<SecurityEvent> = emptyList()
    override fun lastPasswordChangeAt(): String? = null
    override fun clear() = Unit
}

internal class FakeVault(
    exists: Boolean,
    var unlockResult: UnlockResult = UnlockResult.Success,
    private val unlockThrows: Boolean = false,
    /** A vault directory that cannot be written — a read-only or full config dir. */
    private val createThrows: Boolean = false,
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
        if (createThrows) error("could not write the vault")
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
    /** The master password [verifyPassword] accepts; `null` accepts none. */
    var masterPassword: String? = null
    override fun verifyPassword(password: CharArray): Boolean =
        (masterPassword != null && password.concatToString() == masterPassword).also { password.fill('\u0000') }

    // The biometrics path with a live dataKey is covered in shared (DataKey constructor is internal); stubs here.
    /** What [exportDataKey] hands out while unlocked; `null` models a vault with nothing to wrap. */
    var exportable: DataKey? = null

    override fun unlockWithDataKey(dataKey: DataKey): UnlockResult {
        if (unlockResult == UnlockResult.Success) isUnlocked = true
        return unlockResult
    }
    override fun exportDataKey(): DataKey? = exportable?.takeIf { isUnlocked }
    override fun adoptDataKey(newDataKey: DataKey, password: CharArray): Boolean = false
}

/**
 * Fake secure-enclave: [availability] controls availability, wrap/unwrap are identity ops. When
 * [unwrapOutcome] is set the next unwrap returns it verbatim (to script failure/cancel unlock paths).
 */
internal class FakeKeyStore(
    private val availability: BiometricAvailability,
    private val unwrapOutcome: BiometricResult<ByteArray>? = null,
    override val factor: UnlockFactor = UnlockFactor.Biometric,
    private val wrapOutcome: BiometricResult<ByteArray>? = null,
) : BiometricKeyStore {
    override fun availability(): BiometricAvailability = availability
    override suspend fun ensureKey(alias: String, hardening: BiometricKeyHardening): Boolean =
        availability == BiometricAvailability.Available
    override suspend fun wrap(alias: String, plaintext: ByteArray, prompt: BiometricPrompt): BiometricResult<ByteArray> =
        wrapOutcome ?: BiometricResult.Success(plaintext.copyOf())
    override suspend fun unwrap(alias: String, wrapped: ByteArray, prompt: BiometricPrompt): BiometricResult<ByteArray> =
        unwrapOutcome ?: BiometricResult.Success(wrapped.copyOf())
    override fun deleteKey(alias: String) = Unit
}

/** In-memory [SecurityLog] for tests: monotonic stamps t0, t1, ... make order deterministic. */
internal class RecordingSecurityLog : SecurityLog {
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
internal class FakeArtifacts : BioArtifactStore {
    private var artifact: BioArtifact? = null
    override fun exists(): Boolean = artifact != null
    override fun read(): BioArtifact? = artifact
    override fun write(artifact: BioArtifact) { this.artifact = artifact }
    override fun clear() { artifact = null }
}
