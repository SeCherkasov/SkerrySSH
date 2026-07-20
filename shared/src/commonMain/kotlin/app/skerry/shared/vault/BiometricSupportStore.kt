package app.skerry.shared.vault

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * Remembers that biometric unlock is impossible on this device: every [BiometricKeyHardening] rung
 * was tried and the enclave still refused to decrypt with an authorized key (#23 — Xiaomi/HyperOS
 * and friends). Without persistence the toggle would keep inviting the user into a flow that cannot
 * work; with it the UI offers the master password instead, plus an explicit "check again" (a ROM
 * update or a reboot can fix the enclave).
 *
 * The verdict is a UX hint, never a security decision: nothing here can enable biometrics or expose
 * key material.
 */
interface BiometricSupportStore {

    /** Whether this device already proved it cannot decrypt the vault via biometrics. */
    fun isUnsupported(): Boolean

    /** Record the verdict after the whole hardening ladder failed. */
    fun markUnsupported()

    /** Forget the verdict — on a successful enable, or when the user asks to re-check. */
    fun clear()

    /** Session-scoped default: the verdict holds until the app restarts. */
    class Volatile : BiometricSupportStore {
        private var unsupported = false
        override fun isUnsupported(): Boolean = unsupported
        override fun markUnsupported() { unsupported = true }
        override fun clear() { unsupported = false }
    }
}

/** On-disk form of the verdict. [deviceId] scopes it, like [BioArtifact] — a copied workspace directory must not silence biometrics on another device. */
@Serializable
data class BiometricSupportVerdict(val deviceId: String)

/**
 * File-backed [BiometricSupportStore] over okio, stored next to `vault.json` as
 * `vault.bio.unsupported`. Reads never throw (a corrupt file just means "no verdict"): a failure
 * here must not keep the app from starting, and the worst case is offering a toggle that fails once.
 */
class FileBiometricSupportStore(
    private val path: Path,
    private val fileSystem: FileSystem,
    private val deviceId: String,
    private val harden: (Path) -> Unit = {},
) : BiometricSupportStore {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override fun isUnsupported(): Boolean =
        runCatching { json.decodeFromString<BiometricSupportVerdict>(fileSystem.read(path) { readUtf8() }) }
            .getOrNull()?.deviceId == deviceId

    override fun markUnsupported() {
        runCatching { atomicWriteUtf8(fileSystem, path, json.encodeToString(BiometricSupportVerdict(deviceId)), harden) }
    }

    override fun clear() {
        runCatching { fileSystem.delete(path, mustExist = false) }
    }
}
